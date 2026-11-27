/*
 * Lumos - android-app/app / What discovery finds, through the feed's vetoes.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-NBY-005, SRS-NBY-012, SRS-NBY-013, SRS-SAF-002, TRS-ENV-001
 * ADRs: ADR-002, ADR-013
 * Threading: test-only
 * Notes: Two DiscoveryEngines on a back-to-back radio, the first writing into
 * the presence table its Nearby feed reads. A peer found over a real HELLO
 * exchange is a card; a hidden or blocked identity found again over a fresh
 * exchange (new nonces, the rotation path) is not - the engine finds, the
 * feed decides what the person sees. With the block list wired into the
 * engine as its refusals, a blocked person is not even answered: neither
 * phone gets a card, and this phone does not dial them again until the
 * age-out bound passes.
 */

package com.lumos.app.nearby;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lumos.app.Fixtures;
import com.lumos.app.ble.DiscoveryEngine;
import com.lumos.app.ble.DiscoverySuccessMetric;
import com.lumos.app.ble.LinkSession;
import com.lumos.app.ble.PresenceTable;
import com.lumos.app.ble.RescanLadder;
import com.lumos.app.ble.ScanSighting;
import com.lumos.app.store.LocalStore;
import com.lumos.app.store.RecordKind;
import com.lumos.app.store.StoreException;
import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.hello.Hello;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import org.junit.jupiter.api.Test;

/** DiscoveryEngine into NearbyFeedController over the real store. */
final class DiscoveryFeedTest {

    private static final long NOW = 1_755_561_600_000L;
    private static final long TIMEOUT = 10_000L;
    private static final long AGE_OUT = LumosConstants.PRESENCE_AGEOUT_SEC_DEFAULT * 1000L;

    private final PeerFixtures fixtures = new PeerFixtures();
    private final Deque<Runnable> air = new ArrayDeque<>();
    private int nonce;

    DiscoveryFeedTest() throws Exception {
    }

    /** One end of a back-to-back radio: the other phone's engine is the far end. */
    private final class End implements DiscoveryEngine.Radio {
        End other;
        DiscoveryEngine engine;
        String token;
        long now = NOW;
        int connects;

        @Override
        public void connect(final String handle, final String linkToken) {
            connects++;
            air.add(() -> {
                other.engine.onCentralSubscribed("in-" + handle, now);
                engine.onLinkReady(handle, now);
            });
        }

        @Override
        public void send(final String handle, final byte[] frame) {
            final String theirs = handle.startsWith("in-") ? handle.substring(3) : "in-" + handle;
            air.add(() -> other.engine.onFrame(theirs, frame, now));
        }

        @Override
        public void disconnect(final String handle) {
            final String theirs = handle.startsWith("in-") ? handle.substring(3) : "in-" + handle;
            air.add(() -> other.engine.onLinkClosed(theirs));
        }
    }

    private DiscoveryEngine.Identity identity(final int index) throws Exception {
        final PeerFixtures.Party party = fixtures.peer(index, 1);
        return party::freshExchange;
    }

    private byte[] nonce() {
        final byte[] n = new byte[Hello.NONCE_LEN];
        nonce++;
        n[0] = (byte) nonce;
        n[1] = (byte) (nonce >> 8);
        return n;
    }

    private End[] pair(final PresenceTable mine) throws Exception {
        return pair(mine, LinkSession.Refusals.NONE);
    }

    private End[] pair(final PresenceTable mine, final LinkSession.Refusals refusals) throws Exception {
        final End me = new End();
        final End them = new End();
        me.other = them;
        them.other = me;
        me.token = "me";
        them.token = "them";
        me.engine = new DiscoveryEngine(me, identity(1), this::nonce, TIMEOUT, mine, refusals);
        them.engine = new DiscoveryEngine(them, identity(2), this::nonce, TIMEOUT);
        me.engine.start();
        them.engine.start();
        return new End[] {me, them};
    }

    private void find(final End me, final long now) {
        me.now = now;
        me.other.now = now;
        me.engine.onSighting(new ScanSighting(me.other.token, null, null, -50, now));
        me.engine.tick(now);
        while (!air.isEmpty()) {
            air.poll().run();
        }
    }

    private static NearbyFeedController feed(final LocalStore store, final PresenceTable presence) {
        return new NearbyFeedController(presence, new HiddenPeers(store),
                new BlockedPeers(store), new RescanLadder(), new DiscoverySuccessMetric(store));
    }

    @Test
    void aPeerFoundOverTheRadioIsACardOnTheFeed() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final PresenceTable presence = new PresenceTable();
            final End me = pair(presence)[0];
            final NearbyFeedController feed = feed(stack.store(), presence);
            assertEquals(FeedState.Kind.SCANNING_EMPTY, feed.state(NOW).kind());

            find(me, NOW);
            final FeedState state = feed.state(NOW);
            assertEquals(FeedState.Kind.CARDS, state.kind(), "discovery feeds the screen");
            assertEquals(1, state.cards().size());
            assertEquals(me.engine.cards().get(0).peerKey(), state.cards().get(0).peerKey());
        }
    }

    @Test
    void aBlockedPeerIsNotAnsweredAndNotDialledAgain() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final BlockedPeers blocked = new BlockedPeers(stack.store());
            final LinkSession.Refusals refusals = key -> {
                try {
                    return blocked.isBlocked(key);
                } catch (StoreException unreadable) {
                    return true;
                }
            };
            final PresenceTable presence = new PresenceTable();
            final End me = pair(presence, refusals)[0];
            find(me, NOW);
            final String key = me.engine.cards().get(0).peerKey();
            stack.store().put(RecordKind.BLOCK_ENTRY, null,
                    "blocked".getBytes(StandardCharsets.UTF_8), HiddenPeers.naturalKey(key));

            // Both cards age out; then the two phones meet again.
            final long later = NOW + AGE_OUT + 1;
            me.engine.tick(later);
            me.other.engine.tick(later);
            final int before = me.connects;
            find(me, later);
            assertEquals(before + 1, me.connects, "one link, to learn who it is");
            assertEquals(0, me.engine.cards().size(), "a blocked identity is never recorded");
            assertEquals(0, me.other.engine.cards().size(),
                    "and gets no ACK, so no card of this person (SRS-SAF-002)");
            assertEquals(0, me.engine.activeLinks(), "the refused link is closed at once");

            find(me, later + 1_000);
            assertEquals(before + 1, me.connects, "not dialled again while the refusal stands");
            find(me, later + AGE_OUT + 1);
            assertEquals(before + 2, me.connects, "the refusal memory ages out like presence");
            assertEquals(0, me.other.engine.cards().size());
        }
    }

    @Test
    void aHiddenOrBlockedPeerFoundAgainStaysOffTheFeed() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final PresenceTable presence = new PresenceTable();
            final End me = pair(presence)[0];
            final NearbyFeedController feed = feed(stack.store(), presence);
            find(me, NOW);
            final String key = me.engine.cards().get(0).peerKey();

            feed.hideCard(key, NOW);
            assertEquals(FeedState.Kind.SCANNING_EMPTY, feed.state(NOW).kind(), "hidden at once");

            // Age out, then find the same person again over a fresh exchange.
            final long later = NOW + AGE_OUT + 1;
            feed.state(later);
            find(me, later);
            assertEquals(1, me.engine.cards().size(), "the engine did find them again");
            assertEquals(key, me.engine.cards().get(0).peerKey(), "same identity under fresh nonces");
            assertEquals(FeedState.Kind.SCANNING_EMPTY, feed(stack.store(), presence).state(later).kind(),
                    "a hidden identity stays hidden when rediscovered (SRS-NBY-012)");

            stack.store().put(RecordKind.BLOCK_ENTRY, null,
                    "blocked".getBytes(StandardCharsets.UTF_8), HiddenPeers.naturalKey(key));
            final long again = later + AGE_OUT + 1;
            feed.state(again);
            find(me, again);
            assertEquals(FeedState.Kind.SCANNING_EMPTY, feed(stack.store(), presence).state(again).kind(),
                    "a blocked identity never comes back through discovery (SRS-NBY-013)");
        }
    }
}
