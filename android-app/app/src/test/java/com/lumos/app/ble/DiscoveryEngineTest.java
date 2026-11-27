/*
 * Lumos - android-app/app / Phones finding each other on an in-memory air.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-ENV-001, TRS-ENV-002, SRS-NBY-005, SRS-NBY-006, SRS-NBY-007, SRS-NBY-017, TRS-TST-001
 * ADRs: ADR-002, ADR-013
 * Threading: test-only
 * Notes: Each phone is a DiscoveryEngine with real JCA identities. The Air
 * stands in for the radio: a phone's "advertisement" is a sighting other
 * phones' scanners receive, connect() makes a central link on one side and a
 * subscribed peripheral link on the other, and frames cross whole (the
 * fragmenting FrameLink is tested on its own). What a real floor adds -
 * interference, OEM stacks, iOS backgrounding - is G-01's physical half.
 */

package com.lumos.app.ble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.nearby.PeerFixtures;
import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.hello.Hello;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** DiscoveryEngine. */
final class DiscoveryEngineTest {

    private static final long TIMEOUT = 10_000;
    private static final long AGE_OUT = 1000L * LumosConstants.PRESENCE_AGEOUT_SEC_DEFAULT;

    private final PeerFixtures fixtures = new PeerFixtures();
    private long now = 1_000_000;
    private int nonce;

    DiscoveryEngineTest() throws Exception {
    }

    /** The radio between phones. */
    private final class Air {
        private final Map<String, Phone> byToken = new HashMap<>();
        private final Map<String, String> peerHandle = new HashMap<>();
        private final Map<String, Phone> owner = new HashMap<>();
        private final Deque<Runnable> pending = new ArrayDeque<>();
        private int maxLinksSeen;
        private int nextServerHandle;

        void flush() {
            while (!pending.isEmpty()) {
                pending.poll().run();
                for (final Phone p : byToken.values()) {
                    maxLinksSeen = Math.max(maxLinksSeen, p.engine.activeLinks());
                }
            }
        }

        /** Every phone's scanner sees every other advertising phone. */
        void scan() {
            for (final Phone scanner : byToken.values()) {
                for (final Phone advertiser : byToken.values()) {
                    if (scanner != advertiser && advertiser.advertising) {
                        scanner.engine.onSighting(new ScanSighting(advertiser.token, null, null, advertiser.rssi, now));
                    }
                }
            }
        }
    }

    /** One phone. */
    private final class Phone implements DiscoveryEngine.Radio {
        final String token;
        final Air air;
        final DiscoveryEngine engine;
        final Hello.Fields identity;
        boolean advertising = true;
        boolean silent;
        int rssi = -50;

        Phone(final Air air, final String token, final int index) throws Exception {
            this.air = air;
            this.token = token;
            final PeerFixtures.Party party = fixtures.peer(index, 1);
            this.identity = Hello.parseHello(party.freshExchange().start(new byte[Hello.NONCE_LEN]));
            this.engine = new DiscoveryEngine(this, party::freshExchange, () -> {
                final byte[] n = new byte[Hello.NONCE_LEN];
                nonce++;
                n[0] = (byte) nonce;
                n[1] = (byte) (nonce >> 8);
                return n;
            }, TIMEOUT);
            air.byToken.put(token, this);
            engine.start();
        }

        @Override
        public void connect(final String handle, final String linkToken) {
            final Phone target = air.byToken.get(linkToken);
            final String serverHandle = "s" + (++air.nextServerHandle);
            air.peerHandle.put(token + "/" + handle, linkToken + "/" + serverHandle);
            air.peerHandle.put(linkToken + "/" + serverHandle, token + "/" + handle);
            air.owner.put(token + "/" + handle, this);
            air.owner.put(linkToken + "/" + serverHandle, target);
            air.pending.add(() -> {
                if (!target.silent) {
                    target.engine.onCentralSubscribed(serverHandle, now);
                }
                engine.onLinkReady(handle, now);
            });
        }

        @Override
        public void send(final String handle, final byte[] frame) {
            final String to = air.peerHandle.get(token + "/" + handle);
            if (to == null) {
                return;
            }
            final Phone receiver = air.owner.get(to);
            final String theirHandle = to.substring(to.indexOf('/') + 1);
            air.pending.add(() -> {
                if (!receiver.silent) {
                    receiver.engine.onFrame(theirHandle, frame, now);
                }
            });
        }

        @Override
        public void disconnect(final String handle) {
            final String to = air.peerHandle.remove(token + "/" + handle);
            if (to != null) {
                air.peerHandle.remove(to);
                final Phone other = air.owner.get(to);
                final String theirHandle = to.substring(to.indexOf('/') + 1);
                air.pending.add(() -> other.engine.onLinkClosed(theirHandle));
            }
        }
    }

    private void round(final Air air) {
        air.scan();
        for (final Phone p : air.byToken.values()) {
            p.engine.tick(now);
        }
        air.flush();
    }

    @Test
    void twoPhonesFindEachOtherAndLetTheLinkGo() throws Exception {
        final Air air = new Air();
        final Phone android = new Phone(air, "oneplus", 1);
        final Phone iphone = new Phone(air, "iphone", 2);
        round(air);
        assertEquals(1, android.engine.cards().size(), "the OnePlus shows the iPhone");
        assertEquals(1, iphone.engine.cards().size(), "and the iPhone shows the OnePlus");
        assertEquals(0, android.engine.activeLinks(), "finished links are closed at once");
        assertEquals(0, iphone.engine.activeLinks());
    }

    @Test
    void aVenueIsFoundWithoutEverExceedingTheLinkBudget() throws Exception {
        final Air air = new Air();
        final List<Phone> venue = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            venue.add(new Phone(air, "p" + i, 10 + i));
        }
        // Every phone at the same signal strength is the worst case: they all
        // reach for the same peers first. Real rooms spread out; the bound
        // here is what the adversarial case needs.
        int rounds = 0;
        while (rounds < 40 && !venue.stream().allMatch(p -> p.engine.cards().size() == 11)) {
            round(air);
            now += 1000;
            rounds++;
        }
        for (final Phone p : venue) {
            assertEquals(11, p.engine.cards().size(), "every phone shows every other, once each");
        }
        assertTrue(air.maxLinksSeen <= LumosConstants.MAX_CONCURRENT_GATT_LINKS_DEFAULT,
                "no phone ever held more links than the register budget: " + air.maxLinksSeen);
    }

    @Test
    void knownStrongPeersDoNotStarveTheOnesStillToBeFound() throws Exception {
        final Air air = new Air();
        final Phone me = new Phone(air, "me", 1);
        for (int i = 0; i < 3; i++) {
            new Phone(air, "near" + i, 2 + i).rssi = -40;
            new Phone(air, "far" + i, 5 + i).rssi = -80;
        }
        for (int wave = 1; wave <= 2; wave++) {
            air.scan();
            me.engine.tick(now);
            air.flush();
            assertEquals(3 * wave, me.engine.cards().size(), "each wave finds three new peers");
        }
    }

    @Test
    void aPresentPeerIsNotConnectedAgainUntilItsCardAgesOut() throws Exception {
        final Air air = new Air();
        final Phone a = new Phone(air, "a", 1);
        final Phone b = new Phone(air, "b", 2);
        b.advertising = false;
        round(air);
        assertEquals(1, a.engine.cards().size());
        final int linksBefore = air.nextServerHandle;
        now += 5000;
        round(air);
        assertEquals(linksBefore, air.nextServerHandle, "no new link while the card is fresh (ADR-013 §6)");
        now += AGE_OUT + 1;
        a.engine.tick(now);
        assertEquals(0, a.engine.cards().size(), "the card ages out (SRS-NBY-007)");
        round(air);
        assertEquals(linksBefore + 1, air.nextServerHandle, "and then it is found again");
        assertEquals(1, a.engine.cards().size());
    }

    @Test
    void aSilentPeerCostsOneBoundedLinkAndNoCard() throws Exception {
        final Air air = new Air();
        final Phone a = new Phone(air, "a", 1);
        final Phone mute = new Phone(air, "mute", 2);
        mute.silent = true;
        mute.advertising = true;
        a.engine.tick(now);
        air.scan();
        a.engine.tick(now);
        air.flush();
        assertEquals(1, a.engine.activeLinks(), "connected, waiting for a HELLO that never comes");
        now += TIMEOUT;
        a.engine.tick(now);
        air.flush();
        assertEquals(0, a.engine.activeLinks(), "the timer frees the budget");
        assertTrue(a.engine.cards().isEmpty(), "and nothing was granted");
    }

    @Test
    void stopClosesEveryLinkAndListensToNothing() throws Exception {
        final Air air = new Air();
        final Phone a = new Phone(air, "a", 1);
        final Phone mute = new Phone(air, "mute", 2);
        mute.silent = true;
        air.scan();
        a.engine.tick(now);
        air.flush();
        assertEquals(1, a.engine.activeLinks());
        a.engine.stop();
        assertEquals(0, a.engine.activeLinks(), "the one-action stop closes every link (SRS-NBY-017)");
        assertFalse(a.engine.running());
        air.scan();
        a.engine.tick(now);
        air.flush();
        assertEquals(0, a.engine.activeLinks(), "and nothing new starts");
        a.engine.onCentralSubscribed("s99", now);
        assertEquals(0, a.engine.activeLinks(), "nor does anyone get in");
    }
}
