/*
 * Lumos - android-app/app / Unmatch and block, end to end through the relay.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-MAT-010, SRS-RMT-016, SRS-SAF-001, SRS-SAF-002, SRS-NBY-013, TRS-TST-006
 * ADRs: ADR-001, ADR-003
 * Threading: test-only
 * Notes: Real chat stores on SQLite files talking through FakeRelay, as in
 * RelayReceiptsTest. The point being proved: ending a conversation needs no
 * network at the moment the person taps it, survives the app being killed
 * before the next pass, and still leaves the peer unable to put anything on
 * this device's screen - whether their message was sent after the revoke or
 * was already waiting in the mailbox before it.
 */

package com.lumos.app.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.Fixtures;
import com.lumos.app.nearby.BlockedPeers;
import com.lumos.app.store.RecordKind;
import com.lumos.protocol.TextEventKind;
import com.lumos.protocol.chat.RemoteText;
import com.lumos.protocol.hello.Hello;
import com.lumos.protocol.match.MatchMachine;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Unmatch and block, end to end. */
public class ConversationSafetyTest {

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

    private static RemoteText.TextEvent text(final String s) {
        return new RemoteText.TextEvent(TextEventKind.TEXT, null, s.getBytes(StandardCharsets.UTF_8));
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

    /** Alice and Bob have talked; Bob has one reply still waiting to go. */
    private byte[] talked(final RelayDevice alice, final RelayDevice bob) throws Exception {
        final byte[] m = match(alice, bob);
        alice.chat.send(m, text("Hi Bob"));
        alice.sync.run();
        assertEquals(1, bob.sync.run().shown());
        relay.setOffline(true);
        bob.chat.send(m, text("Hi"));
        relay.setOffline(false);
        return m;
    }

    private static boolean outboxHolds(final RelayDevice d, final byte[] m) throws Exception {
        return d.chat.pendingOutbox().stream().anyMatch(i -> Arrays.equals(i.matchId(), m));
    }

    @Test
    void unmatchDeletesTheChatFromThisPhoneAndEndsTheRouteWithoutTheNetwork() throws Exception {
        final RelayDevice alice = device();
        final RelayDevice bob = device();
        final byte[] m = talked(alice, bob);
        assertEquals(2, bob.chat.messages(m).size());
        assertTrue(outboxHolds(bob, m));
        assertEquals(1, bob.store.count(RecordKind.RATCHET_SESSION));

        relay.setOffline(true);
        assertTrue(bob.safety.end(m, ConversationSafety.Ending.UNMATCH), "no network is needed to end it");

        assertTrue(bob.chat.messages(m).isEmpty(), "every message is gone");
        assertFalse(bob.chat.peerIdentity(m).isPresent(), "the conversation is gone");
        assertFalse(outboxHolds(bob, m), "the unsent reply never goes");
        assertEquals(0, bob.store.count(RecordKind.RATCHET_SESSION), "the session keys are gone");
        assertTrue(bob.routes.forMatch(m).orElseThrow().ending(), "the route waits to be revoked");
        assertTrue(bob.routes.byProof().isEmpty(), "and accepts nothing meanwhile");
        assertFalse(new BlockedPeers(bob.store).isBlocked(ConversationSafety.peerKeyOf(alice.edPub)),
                "an unmatch is not a block");
    }

    @Test
    void theNextPassRevokesSoTheirLaterMessagesNeverReachThisDevice() throws Exception {
        final RelayDevice alice = device();
        final RelayDevice bob = device();
        final byte[] m = talked(alice, bob);
        assertTrue(bob.safety.end(m, ConversationSafety.Ending.UNMATCH));
        assertNull(bob.sync.run().stoppedBy());
        assertFalse(bob.routes.forMatch(m).isPresent(), "revoked, then forgotten");

        alice.chat.send(m, text("Hello?"));
        assertEquals(1, alice.sync.run().sent(), "the relay takes it: Alice is not told");
        assertEquals(0, relay.waiting(bob.routingId()), "and nothing reaches Bob (SRS-RMT-016)");
    }

    @Test
    void anEndingSurvivesBeingKilledOfflineAndAMessageAlreadyWaitingIsNeverShown() throws Exception {
        final RelayDevice alice = device();
        final RelayDevice bob = device();
        final byte[] m = talked(alice, bob);
        alice.chat.send(m, text("Are you there?"));
        assertEquals(1, alice.sync.run().sent());
        assertEquals(1, relay.waiting(bob.routingId()), "already in Bob's mailbox before he blocks");

        relay.setOffline(true);
        assertTrue(bob.safety.end(m, ConversationSafety.Ending.BLOCK));
        assertTrue(bob.sync.run().retryLater(), "offline: the pass stops and will retry");
        assertTrue(bob.routes.forMatch(m).orElseThrow().ending(), "kept, so the revoke is retried");
        bob.kill();
        assertTrue(bob.routes.forMatch(m).orElseThrow().ending(), "the mark is on disk, not in memory");

        relay.setOffline(false);
        final RelaySync.Report r = bob.sync.run();
        assertNull(r.stoppedBy());
        assertEquals(0, r.shown(), "the waiting message is dropped unread");
        assertEquals(1, r.acknowledged(), "and deleted from the relay");
        assertFalse(bob.routes.forMatch(m).isPresent());
        assertTrue(bob.chat.messages(m).isEmpty());

        alice.chat.send(m, text("Hello?"));
        alice.sync.run();
        assertEquals(0, relay.waiting(bob.routingId()));
    }

    @Test
    void blockPutsTheIdentityOnTheListTheNearbyFeedReads() throws Exception {
        final RelayDevice alice = device();
        final RelayDevice bob = device();
        final byte[] m = talked(alice, bob);
        assertTrue(bob.safety.end(m, ConversationSafety.Ending.BLOCK));
        final String presenceKey = Hex.encode(Hello.peerId(alice.edPub));
        assertEquals(presenceKey, ConversationSafety.peerKeyOf(alice.edPub),
                "the key a presence card carries for the same identity");
        assertTrue(new BlockedPeers(bob.store).isBlocked(presenceKey));
        assertEquals(1, bob.store.count(RecordKind.BLOCK_ENTRY));
        new BlockedPeers(bob.store).block(presenceKey, clock.nowUtcMillis());
        assertEquals(1, bob.store.count(RecordKind.BLOCK_ENTRY), "blocking twice leaves one entry");
    }

    @Test
    void endingAConversationThatDoesNotExistChangesNothing() throws Exception {
        final RelayDevice alice = device();
        final RelayDevice bob = device();
        final byte[] m = talked(alice, bob);
        assertFalse(bob.safety.end(random(16), ConversationSafety.Ending.BLOCK));
        assertEquals(2, bob.chat.messages(m).size());
        assertFalse(bob.routes.forMatch(m).orElseThrow().ending());
        assertEquals(0, bob.store.count(RecordKind.BLOCK_ENTRY));
    }

    @Test
    void theOtherConversationsAreUntouched() throws Exception {
        final RelayDevice alice = device();
        final RelayDevice bob = device();
        final RelayDevice cara = device();
        final byte[] withAlice = talked(alice, bob);
        final byte[] withCara = match(cara, bob);
        cara.chat.send(withCara, text("Hey"));
        cara.sync.run();
        assertEquals(1, bob.sync.run().shown());

        assertTrue(bob.safety.end(withAlice, ConversationSafety.Ending.BLOCK));
        assertNull(bob.sync.run().stoppedBy());
        assertEquals(1, bob.chat.messages(withCara).size());
        cara.chat.send(withCara, text("Still here"));
        cara.sync.run();
        assertEquals(1, bob.sync.run().shown(), "Cara still gets through");
    }
}
