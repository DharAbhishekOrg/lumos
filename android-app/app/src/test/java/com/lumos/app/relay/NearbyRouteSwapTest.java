/*
 * Lumos - android-app/app / The RELAY_ROUTE swap over real interest sessions, then chat over the relay.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-001, SRS-MAT-005, SRS-MAT-006, SRS-RMT-018, TRS-TST-001
 * ADRs: ADR-002
 * Threading: test-only
 * Notes: Two people's InterestSessions over a loopback link, exactly as
 * AT-005's flow test drives them, each bound to a NearbyRouteSwap on a
 * RelayDevice. The swap must stay silent until the match, cross once after
 * it, not repeat while nothing changed, and leave both devices able to chat
 * over the relay with no nearby radio at all.
 */

package com.lumos.app.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.Fixtures;
import com.lumos.app.match.InterestLedger;
import com.lumos.app.match.InterestSession;
import com.lumos.app.match.PersistedBudget;
import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.TextEventKind;
import com.lumos.protocol.chat.RemoteText;
import com.lumos.protocol.match.MatchMachine;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The route swap on a live session. */
public class NearbyRouteSwapTest {

    private static final long NOW = 1_755_561_600_000L;

    private final Fixtures.TestClock clock = new Fixtures.TestClock(1_700_000_000_000L);
    private final FakeRelay relay = new FakeRelay(clock);
    private final List<AutoCloseable> open = new ArrayList<>();

    @AfterEach
    void closeAll() throws Exception {
        for (final AutoCloseable c : open) {
            c.close();
        }
    }

    private static byte[] id(final int seed) {
        final byte[] out = new byte[LumosConstants.PEER_ID_LEN_DEFAULT];
        Arrays.fill(out, (byte) seed);
        return out;
    }

    private static String hex(final byte[] b) {
        return Hex.encode(b);
    }

    /** One person: a relay device, an interest session with the other, and what it sent. */
    private final class Person {
        private final RelayDevice device;
        private final Deque<byte[]> sent = new ArrayDeque<>();
        private final InterestSession session;
        private final NearbyRouteSwap swap;

        Person(final int self, final int peer, final byte[] matchId) throws Exception {
            device = new RelayDevice(clock, relay, open);
            assertNull(device.sync.run().stoppedBy());
            final InterestLedger ledger = new InterestLedger(device.store, new PersistedBudget(device.store), id(self));
            session = new InterestSession(ledger, hex(id(peer)), "preview".getBytes(StandardCharsets.UTF_8), sent::add);
            swap = NearbyRouteSwap.on(session, device.exchange, matchId);
        }
    }

    private static int deliver(final Person from, final Person to) throws Exception {
        int n = 0;
        while (!from.sent.isEmpty()) {
            assertTrue(to.session.onFrame(from.sent.poll(), NOW).delivered());
            n++;
        }
        return n;
    }

    @Test
    void theRoutesCrossOnceAfterTheMatchAndTheyChatOverTheRelay() throws Exception {
        final byte[] matchId = id(0x5A);
        final Person asha = new Person(0x11, 0x22, matchId);
        final Person ravi = new Person(0x22, 0x11, matchId);

        asha.session.local(MatchMachine.Event.LOCAL_REQUEST, NOW);
        assertFalse(asha.swap.offerIfMatched(), "no route while only requested");
        deliver(asha, ravi);
        assertFalse(ravi.swap.offerIfMatched(), "no route before accepting");

        ravi.session.local(MatchMachine.Event.LOCAL_ACCEPT, NOW);
        assertTrue(ravi.swap.offerIfMatched(), "Ravi offers his route the moment he accepts");
        assertFalse(ravi.swap.offerIfMatched(), "and not again while nothing changed");
        deliver(ravi, asha);
        assertEquals(MatchMachine.State.MATCHED, asha.session.state());
        assertEquals(RouteExchange.Taken.STORED, asha.swap.lastTaken());
        assertTrue(asha.swap.offerIfMatched());
        deliver(asha, ravi);
        assertEquals(RouteExchange.Taken.STORED, ravi.swap.lastTaken());

        // The radio is gone; everything from here is the relay alone (SRS-RMT-018).
        asha.device.chat.acceptConversation(matchId, ravi.device.edPub);
        ravi.device.chat.acceptConversation(matchId, asha.device.edPub);
        asha.device.sync.run();
        ravi.device.sync.run();
        assertTrue(asha.device.sessions.start(matchId, ravi.device.edPub));
        asha.device.chat.send(matchId, new RemoteText.TextEvent(TextEventKind.TEXT, null,
                "Made it home".getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, asha.device.sync.run().sent());
        assertEquals(1, ravi.device.sync.run().shown());
    }

    @Test
    void aForgedRouteBeforeAMatchIsRefusedByTheGateAndNeverStored() throws Exception {
        final byte[] matchId = id(0x5B);
        final Person asha = new Person(0x11, 0x22, matchId);
        final Person mallory = new Person(0x22, 0x11, matchId);
        final byte[] route = mallory.device.exchange.offer(matchId, MatchMachine.State.MATCHED).orElseThrow();
        final com.lumos.protocol.wire.Frame.Header h = new com.lumos.protocol.wire.Frame.Header();
        h.type = com.lumos.protocol.MessageClass.RELAY_ROUTE.code();
        h.sequence = 7;
        final InterestSession.Inbound in = asha.session.onFrame(com.lumos.protocol.wire.Frame.encode(h, route), NOW);
        assertFalse(in.delivered(), "a conversation frame before a match is refused (SRS-MAT-006)");
        assertNull(asha.swap.lastTaken(), "and never reaches the route store");
        assertFalse(asha.device.routes.forMatch(matchId).isPresent());
    }
}
