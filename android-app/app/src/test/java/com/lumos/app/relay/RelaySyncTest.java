/*
 * Lumos - android-app/app / Chat over the relay, offline, rate limited, refused, and killed at every step.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-001, SRS-RMT-003, SRS-RMT-004, SRS-RMT-009, SRS-RMT-015, SRS-RMT-017, SRS-RMT-018, TRS-TRN-012, TRS-PAT-004, TRS-TST-006, TRS-BCK-007
 * ADRs: ADR-001, ADR-003
 * Threading: test-only
 * Notes: Two or three RelayDevices - real chat stores on real SQLite files,
 * real Ed25519 identities - talk through FakeRelay. "Killed" means the
 * device's every object is dropped mid-pass and rebuilt from its file, which
 * is what Android does to a process. The kill points are the ones that
 * matter: just before and just after the relay applies a send, and between
 * a committed open and its acknowledgement. No nearby radio exists in any of
 * these tests, which is the internet-only case (SRS-RMT-018).
 */

package com.lumos.app.relay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.Fixtures;
import com.lumos.app.chat.ChatRecords;
import com.lumos.app.store.RecordKind;
import com.lumos.protocol.MessageClass;
import com.lumos.protocol.TextEventKind;
import com.lumos.protocol.chat.DeliveryMachine;
import com.lumos.protocol.chat.RemoteText;
import com.lumos.protocol.match.MatchMachine;
import com.lumos.protocol.wire.Frame;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The relay pass, end to end. */
public class RelaySyncTest {

    private static final SecureRandom RNG = new SecureRandom();

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

    private static RemoteText.TextEvent text(final String s) {
        return new RemoteText.TextEvent(TextEventKind.TEXT, null, s.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> bodies(final RelayDevice d, final byte[] match) throws Exception {
        final List<String> out = new ArrayList<>();
        for (final ChatRecords.Message m : d.chat.messages(match)) {
            out.add(new String(m.body(), StandardCharsets.UTF_8));
        }
        return out;
    }

    private RelayDevice device() throws Exception {
        final RelayDevice d = new RelayDevice(clock, relay, open);
        final RelaySync.Report r = d.sync.run();
        assertNull(r.stoppedBy(), "a first pass registers and publishes");
        return d;
    }

    /**
     * What the match and the RELAY_ROUTE exchange (CR-005) leave behind: both
     * sides pinned, both routes swapped over the "nearby session" (this test
     * handing the payloads across), both proofs allowed, and a session that
     * the initiator started from the responder's published bundle.
     */
    private byte[] match(final RelayDevice initiator, final RelayDevice responder) throws Exception {
        final byte[] match = random(16);
        initiator.chat.acceptConversation(match, responder.edPub);
        responder.chat.acceptConversation(match, initiator.edPub);
        final MatchMachine.State matched = MatchMachine.State.MATCHED;
        assertEquals(RouteExchange.Taken.STORED, responder.exchange.take(match, matched,
                initiator.exchange.offer(match, matched).orElseThrow()));
        assertEquals(RouteExchange.Taken.STORED, initiator.exchange.take(match, matched,
                responder.exchange.offer(match, matched).orElseThrow()));
        assertNull(initiator.sync.run().stoppedBy());
        assertNull(responder.sync.run().stoppedBy());
        assertTrue(initiator.sessions.start(match, responder.edPub));
        return match;
    }

    @Test
    void aMessageCrossesTheRelayShowsOnceAndIsOneTickNotDelivered() throws Exception {
        final RelayDevice alice = device();
        final RelayDevice bob = device();
        final int stockBefore = relay.oneTimeLeft(bob.routingId());
        final byte[] match = match(alice, bob);
        assertEquals(stockBefore - 1, relay.oneTimeLeft(bob.routingId()), "the claim took one one-time prekey");

        alice.chat.send(match, text("Hi! Lovely meeting you tonight."));
        final RelaySync.Report sent = alice.sync.run();
        assertEquals(1, sent.sent());
        assertTrue(alice.chat.pendingOutbox().isEmpty(), "the relay took it, so the outbox is done");
        assertEquals(DeliveryMachine.State.SENT, alice.chat.messages(match).get(0).state(),
                "the relay taking it is one tick, never delivered (owner ruling 2026-11-21)");

        final int bobSecrets = bob.store.count(RecordKind.PREKEY_SECRET);
        final RelaySync.Report got = bob.sync.run();
        assertEquals(1, got.shown());
        assertEquals(1, got.acknowledged());
        assertEquals(0, relay.waiting(bob.routingId()), "acknowledged means deleted from the relay (SRS-RMT-015)");
        assertEquals(bobSecrets - 1, bob.store.count(RecordKind.PREKEY_SECRET), "the used one-time prekey is gone");
        assertEquals(List.of("Hi! Lovely meeting you tonight."), bodies(bob, match));

        bob.chat.send(match, text("Saturday coffee?"));
        bob.sync.run();
        assertEquals(1, alice.sync.run().shown(), "the reply comes back the same way");
        assertEquals(List.of("Hi! Lovely meeting you tonight.", "Saturday coffee?"), bodies(alice, match));
    }

    @Test
    void offlineEverythingWaitsInOrderAndGoesWhenThePathReturns() throws Exception {
        final RelayDevice alice = device();
        final RelayDevice bob = device();
        final byte[] match = match(alice, bob);
        relay.setOffline(true);
        for (final String s : List.of("one", "two", "three")) {
            alice.chat.send(match, text(s));
        }
        final RelaySync.Report r = alice.sync.run();
        assertEquals(RelayException.Kind.OFFLINE, r.stoppedBy().kind());
        assertTrue(r.retryLater());
        assertEquals(3, alice.chat.pendingOutbox().size(), "nothing is lost offline (SRS-RMT-003)");

        relay.setOffline(false);
        assertEquals(3, alice.sync.run().sent(), "it retries by itself when the path returns");
        bob.sync.run();
        assertEquals(List.of("one", "two", "three"), bodies(bob, match));
    }

    @Test
    void killedJustAfterTheRelayTookASendItGoesAgainAndIsStoredOnce() throws Exception {
        final RelayDevice alice = device();
        final RelayDevice bob = device();
        final byte[] match = match(alice, bob);
        alice.chat.send(match, text("Are you still at the venue?"));
        final int storedBefore = relay.storedEver();

        relay.fault("POST /v1/envelopes", FakeRelay.Fault.DIE_AFTER);
        assertThrows(FakeRelay.ProcessDeath.class, alice.sync::run);
        alice.kill();
        assertEquals(1, alice.chat.pendingOutbox().size(), "the outbox item survived the kill");

        assertEquals(1, alice.sync.run().sent(), "the next launch sends the same id again");
        assertEquals(storedBefore + 1, relay.storedEver(), "the relay stored it once (TRS-BCK-004)");
        assertEquals(1, bob.sync.run().shown());
        assertEquals(1, bob.chat.messages(match).size(), "shown once (SRS-RMT-004)");
    }

    @Test
    void killedJustBeforeTheRelayTookASendNothingIsLost() throws Exception {
        final RelayDevice alice = device();
        final RelayDevice bob = device();
        final byte[] match = match(alice, bob);
        alice.chat.send(match, text("See you there"));

        relay.fault("POST /v1/envelopes", FakeRelay.Fault.DIE_BEFORE);
        assertThrows(FakeRelay.ProcessDeath.class, alice.sync::run);
        assertEquals(0, relay.waiting(bob.routingId()));
        alice.kill();
        assertEquals(1, alice.sync.run().sent());
        assertEquals(1, bob.sync.run().shown());
    }

    @Test
    void killedBetweenOpeningAndAcknowledgingItIsShownOnceAndNoKeyIsReused() throws Exception {
        final RelayDevice alice = device();
        final RelayDevice bob = device();
        final byte[] match = match(alice, bob);
        alice.chat.send(match, text("first"));
        alice.sync.run();

        relay.fault("POST /v1/envelopes/ack", FakeRelay.Fault.DIE_BEFORE);
        assertThrows(FakeRelay.ProcessDeath.class, bob.sync::run);
        bob.kill();
        assertEquals(1, relay.waiting(bob.routingId()), "the ack never reached the relay");
        assertEquals(List.of("first"), bodies(bob, match), "the open had committed before the kill");

        final RelaySync.Report again = bob.sync.run();
        assertEquals(0, again.shown(), "the refetched copy is a duplicate");
        assertEquals(1, again.acknowledged(), "and is acknowledged this time");
        assertEquals(List.of("first"), bodies(bob, match), "shown once (SRS-RMT-004)");

        alice.chat.send(match, text("second"));
        alice.sync.run();
        assertEquals(1, bob.sync.run().shown(), "the restored session opens the next message (SRS-RMT-017)");
        assertEquals(List.of("first", "second"), bodies(bob, match));
    }

    @Test
    void aFullMailboxHoldsThatConversationInOrderWhileAnotherFlows() throws Exception {
        final RelayDevice alice = device();
        final RelayDevice bob = device();
        final RelayDevice carol = device();
        final byte[] withBob = match(alice, bob);
        final byte[] withCarol = match(alice, carol);
        relay.quota(bob.routingId(), 1);

        alice.chat.send(withBob, text("b1"));
        alice.chat.send(withBob, text("b2"));
        alice.chat.send(withCarol, text("c1"));
        final RelaySync.Report r = alice.sync.run();
        assertNull(r.stoppedBy(), "a full mailbox is one peer's problem, not the pass's");
        assertEquals(2, r.sent(), "b1 and c1 went; b2 waits behind the full mailbox");
        assertEquals(1, alice.chat.pendingOutbox().size());

        assertEquals(1, carol.sync.run().shown());
        bob.sync.run();
        assertEquals(1, alice.sync.run().sent(), "b2 goes once bob made room");
        bob.sync.run();
        assertEquals(List.of("b1", "b2"), bodies(bob, withBob), "and in order");
    }

    @Test
    void rateLimitedThePassStopsAndKeepsEverything() throws Exception {
        final RelayDevice alice = device();
        final RelayDevice bob = device();
        final byte[] match = match(alice, bob);
        alice.chat.send(match, text("hello"));
        relay.rateLimitNextSend(30);
        final RelaySync.Report r = alice.sync.run();
        assertEquals(RelayException.Kind.BUSY, r.stoppedBy().kind());
        assertEquals(30, r.stoppedBy().retryAfterSeconds(), "the server's Retry-After reaches the scheduler");
        assertEquals(1, alice.chat.pendingOutbox().size());
        assertEquals(1, alice.sync.run().sent());
    }

    @Test
    void aRefusedCredentialRegistersAfreshAndRebuildsTheMailbox() throws Exception {
        final RelayDevice alice = device();
        final RelayDevice bob = device();
        final byte[] match = match(alice, bob);
        final byte[] oldRoute = bob.routingId();

        relay.revokeDevice(bob.credential());
        final RelaySync.Report refused = bob.sync.run();
        assertEquals(RelayException.Kind.AUTH, refused.stoppedBy().kind());
        assertFalse(bob.account.current().isPresent(), "the dead credential is dropped");

        assertNull(bob.sync.run().stoppedBy(), "the next pass registers again");
        final byte[] newRoute = bob.routingId();
        assertFalse(java.util.Arrays.equals(oldRoute, newRoute), "a new registration is a new mailbox");
        assertEquals(com.lumos.protocol.LumosConstants.ONE_TIME_PREKEYS_UPLOADED_DEFAULT,
                relay.oneTimeLeft(newRoute), "prekeys were published again under it");
        assertArrayEquals(newRoute, bob.routes.forMatch(match).orElseThrow().allowedFor(),
                "and every conversation's proof was allowed again");
    }

    @Test
    void badEnvelopesAreAcknowledgedNeverShownAndTheSessionCarriesOn() throws Exception {
        final RelayDevice alice = device();
        final RelayDevice bob = device();
        final byte[] match = match(alice, bob);
        final byte[] proof = bob.routes.forMatch(match).orElseThrow().proof();
        final int text = MessageClass.TEXT_MESSAGE.code();

        relay.inject(bob.routingId(), random(16), random(32), text, random(80));
        relay.inject(bob.routingId(), random(16), proof, text, random(80));
        relay.inject(bob.routingId(), random(16), proof, MessageClass.DELIVERY_RECEIPT.code(), random(80));
        final RelaySync.Report r = bob.sync.run();
        assertEquals(0, r.shown(), "unknown sender, forged envelope and unexpected class show nothing (SRS-RMT-009)");
        assertEquals(3, r.acknowledged(), "and are acknowledged, so they are not fetched forever");
        assertEquals(0, relay.waiting(bob.routingId()));

        alice.chat.send(match, text("still here"));
        alice.sync.run();
        relay.serveExpired(true);
        clock.advance((FakeRelay.TTL_SECONDS + 1) * 1000L);
        final RelaySync.Report late = bob.sync.run();
        assertEquals(0, late.shown(), "an envelope past its expiry is not shown, even if a relay serves it");
        assertEquals(1, late.acknowledged());

        relay.serveExpired(false);
        alice.chat.send(match, text("and now?"));
        alice.sync.run();
        assertEquals(1, bob.sync.run().shown(), "the session survived all of it");
    }

    @Test
    void aSendRefusedForGoodShowsFailedAndTheNextOneStillGoes() throws Exception {
        final RelayDevice alice = device();
        final RelayDevice bob = device();
        final byte[] match = match(alice, bob);
        final byte[] id = alice.chat.send(match, text("this one is refused"));
        // Another envelope already holds this id at the relay: the replay rule refuses ours for good.
        relay.inject(bob.routingId(), id, bob.routes.forMatch(match).orElseThrow().proof(),
                MessageClass.TEXT_MESSAGE.code(), random(40));

        final RelaySync.Report r = alice.sync.run();
        assertEquals(1, r.failed());
        assertEquals(DeliveryMachine.State.FAILED, alice.chat.messages(match).get(0).state(), "the red mark");
        assertTrue(alice.chat.pendingOutbox().isEmpty());

        alice.chat.send(match, text("this one goes"));
        assertEquals(1, alice.sync.run().sent());
        bob.sync.run();
        assertTrue(bodies(bob, match).contains("this one goes"), "the refused message is only a skipped key");
    }

    @Test
    void aSessionStartsOnlyOnceTheRouteAndTheBundleAreThereAndOnlyForThePinnedPeer() throws Exception {
        final RelayDevice alice = device();
        final RelayDevice bob = device();
        final RelayDevice carol = device();
        final byte[] match = random(16);
        alice.chat.acceptConversation(match, bob.edPub);
        alice.routes.issue(match);
        assertFalse(alice.sessions.start(match, bob.edPub), "no route to bob yet");

        alice.routes.setPeer(match, random(16), random(32));
        assertFalse(alice.sessions.start(match, bob.edPub), "an unknown routing id has no bundle");

        alice.routes.setPeer(match, bob.routingId(), random(32));
        assertThrows(Frame.WireException.class, () -> alice.sessions.start(match, carol.edPub),
                "a bundle that is not the pinned peer's is refused");
        assertFalse(alice.chat.hasSession(match));
        assertTrue(alice.sessions.start(match, bob.edPub));
        assertTrue(alice.sessions.start(match, bob.edPub), "starting again is a no-op");
        assertTrue(alice.chat.hasSession(match));
    }
}
