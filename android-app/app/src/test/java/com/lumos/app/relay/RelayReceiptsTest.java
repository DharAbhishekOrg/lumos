/*
 * Lumos - android-app/app / Ticks from the peer, the read-receipt setting, the route swap's gate, and unmatch.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-005, SRS-RMT-014, SRS-RMT-004, SRS-RMT-016, SRS-RMT-017, SRS-MAT-005, SRS-MAT-006, TRS-TST-006
 * ADRs: ADR-001, ADR-002
 * Threading: test-only
 * Notes: Real chat stores on SQLite files talking through FakeRelay, as in
 * RelaySyncTest. The tick assertions are the owner's 2026-11-21 ruling made
 * executable: one tick when the relay takes a message, two only when the
 * peer's delivered receipt arrives, blue only when its read receipt does -
 * and no read receipt ever leaves a device whose user turned them off.
 */

package com.lumos.app.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.Fixtures;
import com.lumos.app.chat.ChatRecords;
import com.lumos.protocol.MessageClass;
import com.lumos.protocol.TextEventKind;
import com.lumos.protocol.chat.DeliveryMachine;
import com.lumos.protocol.chat.RemoteText;
import com.lumos.protocol.match.MatchMachine;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Receipts and routes, end to end. */
public class RelayReceiptsTest {

    private static final SecureRandom RNG = new SecureRandom();
    private static final MatchMachine.State MATCHED = MatchMachine.State.MATCHED;

    private final Fixtures.TestClock clock = new Fixtures.TestClock(1_700_000_000_000L);
    private final FakeRelay relay = new FakeRelay(clock);
    private final List<AutoCloseable> open = new ArrayList<>();

    @AfterEach
    void closeAll() throws Exception {
        for (final AutoCloseable c : open) {
            c.close();
        }
    }

    private static byte[] random(final int n) {
        final byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    private RelayDevice device() throws Exception {
        final RelayDevice d = new RelayDevice(clock, relay, open);
        assertNull(d.sync.run().stoppedBy());
        return d;
    }

    private byte[] match(final RelayDevice a, final RelayDevice b) throws Exception {
        final byte[] m = random(16);
        a.chat.acceptConversation(m, b.edPub);
        b.chat.acceptConversation(m, a.edPub);
        b.exchange.take(m, MATCHED, a.exchange.offer(m, MATCHED).orElseThrow());
        a.exchange.take(m, MATCHED, b.exchange.offer(m, MATCHED).orElseThrow());
        a.sync.run();
        b.sync.run();
        assertTrue(a.sessions.start(m, b.edPub));
        return m;
    }

    private static DeliveryMachine.State firstState(final RelayDevice d, final byte[] m) throws Exception {
        return d.chat.messages(m).get(0).state();
    }

    private static RemoteText.TextEvent text(final String s) {
        return new RemoteText.TextEvent(TextEventKind.TEXT, null, s.getBytes(StandardCharsets.UTF_8));
    }

    private static long receiptsQueued(final RelayDevice d, final MessageClass kind) throws Exception {
        return d.chat.pendingOutbox().stream().filter(i -> i.messageClass() == kind).count();
    }

    @Test
    void oneTickWhenTheRelayTakesItTwoOnlyFromTheDeliveredReceiptBlueOnlyFromTheRead() throws Exception {
        final RelayDevice alice = device();
        final RelayDevice bob = device();
        final byte[] m = match(alice, bob);
        alice.chat.send(m, text("See you at eight?"));
        alice.sync.run();
        assertEquals(DeliveryMachine.State.SENT, firstState(alice, m));

        bob.sync.run();
        assertEquals(1, receiptsQueued(bob, MessageClass.DELIVERY_RECEIPT),
                "showing the message queued its delivered receipt in the same transaction");
        alice.sync.run();
        assertEquals(DeliveryMachine.State.SENT, firstState(alice, m), "no receipt has travelled yet");

        bob.sync.run();
        alice.sync.run();
        assertEquals(DeliveryMachine.State.DELIVERED, firstState(alice, m), "two grey ticks, from Bob's receipt");

        assertEquals(1, bob.chat.markRead(m, true));
        bob.sync.run();
        alice.sync.run();
        assertEquals(DeliveryMachine.State.READ, firstState(alice, m), "two blue ticks, from Bob's read receipt");
        assertEquals(0, bob.chat.markRead(m, true), "a message is read once");
    }

    @Test
    void withReadReceiptsOffTheReadStaysOnTheDeviceAndDeliveredStillGoes() throws Exception {
        final RelayDevice alice = device();
        final RelayDevice bob = device();
        final byte[] m = match(alice, bob);
        alice.chat.send(m, text("Hello"));
        alice.sync.run();
        bob.sync.run();
        assertEquals(1, bob.chat.markRead(m, false));
        assertEquals(DeliveryMachine.State.READ, firstState(bob, m), "read on Bob's side");
        assertEquals(0, receiptsQueued(bob, MessageClass.READ_RECEIPT), "no read receipt leaves (SRS-RMT-014)");
        assertEquals(1, receiptsQueued(bob, MessageClass.DELIVERY_RECEIPT), "the delivered receipt still goes");
        bob.sync.run();
        alice.sync.run();
        assertEquals(DeliveryMachine.State.DELIVERED, firstState(alice, m), "Alice sees delivered, never read");
    }

    @Test
    void killedBetweenShowingAndAcknowledgingOneDeliveredReceiptIsSent() throws Exception {
        final RelayDevice alice = device();
        final RelayDevice bob = device();
        final byte[] m = match(alice, bob);
        alice.chat.send(m, text("Are you home?"));
        alice.sync.run();
        relay.fault("POST /v1/envelopes/ack", FakeRelay.Fault.DIE_BEFORE);
        assertThrows(FakeRelay.ProcessDeath.class, bob.sync::run);
        bob.kill();
        assertEquals(1, receiptsQueued(bob, MessageClass.DELIVERY_RECEIPT), "the receipt survived the kill with the message");
        bob.sync.run();
        assertEquals(0, receiptsQueued(bob, MessageClass.DELIVERY_RECEIPT), "sent, and the refetched copy queued none");
        alice.sync.run();
        assertEquals(DeliveryMachine.State.DELIVERED, firstState(alice, m));
    }

    @Test
    void aRouteIsOfferedAndTakenOnlyAfterAMatchAndOnlyWellFormed() throws Exception {
        final RelayDevice alice = device();
        final RelayDevice unregistered = new RelayDevice(clock, relay, open);
        final byte[] m = random(16);
        for (final MatchMachine.State s : MatchMachine.State.values()) {
            if (s != MATCHED) {
                assertFalse(alice.exchange.offer(m, s).isPresent(), "no route before a match: " + s);
                assertEquals(RouteExchange.Taken.NOT_MATCHED, alice.exchange.take(m, s,
                        alice.exchange.offer(m, MATCHED).orElseThrow()), "none taken before a match: " + s);
            }
        }
        assertFalse(unregistered.exchange.offer(m, MATCHED).isPresent(), "nothing to offer without a routing id");
        assertEquals(RouteExchange.Taken.MALFORMED, alice.exchange.take(m, MATCHED, random(49)),
                "random bytes are not a route");
        assertEquals(RouteExchange.Taken.MALFORMED, alice.exchange.take(m, MATCHED, new byte[0]));
    }

    @Test
    void afterUnmatchThePeersSendsAreTakenByTheRelayButNeverDelivered() throws Exception {
        final RelayDevice alice = device();
        final RelayDevice bob = device();
        final byte[] m = match(alice, bob);
        relay.setOffline(true);
        assertEquals(RelayException.Kind.OFFLINE, bob.sync.forget(m).kind());
        assertTrue(bob.routes.forMatch(m).isPresent(), "kept, so the revoke can be retried");
        relay.setOffline(false);
        assertNull(bob.sync.forget(m));
        assertFalse(bob.routes.forMatch(m).isPresent());

        alice.chat.send(m, text("Hello?"));
        assertEquals(1, alice.sync.run().sent(), "accepted: Alice learns nothing (SRS-MAT-003)");
        assertEquals(0, relay.waiting(bob.routingId()), "and nothing reaches Bob (SRS-RMT-016)");
        assertEquals(DeliveryMachine.State.SENT, firstState(alice, m), "one tick, forever");
    }

    @Test
    void manyUnreadMessagesAreAcknowledgedInBatchesOfTheRegisterDefault() throws Exception {
        final RelayDevice alice = device();
        final RelayDevice bob = device();
        final byte[] m = match(alice, bob);
        final int n = com.lumos.protocol.LumosConstants.RECEIPT_IDS_PER_ENVELOPE_DEFAULT + 1;
        for (int i = 0; i < n; i++) {
            alice.chat.send(m, text("msg " + i));
        }
        alice.sync.run();
        while (bob.sync.run().moreWaiting()) {
            continue;
        }
        assertEquals(n, bob.chat.messages(m).size());
        bob.chat.markRead(m, true);
        assertEquals(2, receiptsQueued(bob, MessageClass.READ_RECEIPT), "33 ids need two receipts");
        for (int i = 0; i < 3; i++) {
            bob.sync.run();
            alice.sync.run();
        }
        for (final ChatRecords.Message msg : alice.chat.messages(m)) {
            assertEquals(DeliveryMachine.State.READ, msg.state());
        }
    }
}
