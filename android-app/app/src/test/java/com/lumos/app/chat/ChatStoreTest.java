/*
 * Lumos - android-app/app / Two devices chat through their outboxes, die, restart, and lose nothing.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-001, SRS-RMT-003, SRS-RMT-004, SRS-RMT-009, SRS-RMT-011, SRS-RMT-017, TRS-LOC-004, TRS-LOC-005, TRS-PAT-004, TRS-CRY-005, TRS-TST-001
 * ADRs: ADR-001, ADR-003
 * Threading: test-only
 * Notes: Each device is a real ChatStore over a real SQLite file through the
 * same sealed LocalStore the app uses. The "relay" is this test moving outbox
 * envelopes. "Process death" closes the database, drops every object and
 * reopens with the same data key. Identities are real Ed25519 keys from the
 * JDK, so the bundle and binding signatures the protocol verifies are real.
 */

package com.lumos.app.chat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.Fixtures;
import com.lumos.app.data.DataKeyHierarchy;
import com.lumos.app.keystore.SoftwareKekStore;
import com.lumos.app.store.JdbcSqlDatabase;
import com.lumos.app.store.LocalStore;
import com.lumos.app.store.RecordKind;
import com.lumos.app.store.StoreException;
import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.TextEventKind;
import com.lumos.protocol.chat.DeliveryMachine;
import com.lumos.protocol.chat.RemoteSession;
import com.lumos.protocol.chat.RemoteText;
import com.lumos.protocol.ratchet.DoubleRatchet;
import com.lumos.protocol.wire.LumosCrypto;
import com.lumos.protocol.x3dh.X3dh;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The chat store, end to end between two devices. */
public class ChatStoreTest {

    private static final SecureRandom RNG = new SecureRandom();
    private static final byte[] MATCH = random(16);
    private static final long SPK_ID = 7;
    private static final long OPK_ID = 42;
    private static final int SPKI_PREFIX = 12;

    private final Fixtures.TestClock clock = new Fixtures.TestClock(1_700_000_000_000L);
    private final java.util.List<AutoCloseable> open = new java.util.ArrayList<>();

    private static byte[] random(final int n) {
        final byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    private static byte[] utf8(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** One device's identity and published prekeys, with real Ed25519 signatures. */
    private static final class Keys {
        private final KeyPair ed;
        private final byte[] edPub;
        private final byte[] dhPriv = random(32);
        private final byte[] spkPriv = random(32);
        private final byte[] opkPriv = random(32);

        Keys() throws Exception {
            ed = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            final byte[] spki = ed.getPublic().getEncoded();
            edPub = Arrays.copyOfRange(spki, SPKI_PREFIX, spki.length);
        }

        byte[] sign(final byte[] message) throws Exception {
            final Signature s = Signature.getInstance("Ed25519");
            s.initSign(ed.getPrivate());
            s.update(message);
            return s.sign();
        }

        byte[] bindingSig() throws Exception {
            return sign(LumosCrypto.concat(utf8(LumosConstants.X3DH_IDENTITY_BINDING_INFO_VALUE),
                    LumosCrypto.x25519PublicFromPrivate(dhPriv)));
        }

        RemoteSession.Identity identity() throws Exception {
            return new RemoteSession.Identity(edPub, dhPriv, bindingSig());
        }

        X3dh.Bundle bundle() throws Exception {
            final byte[] spkPub = LumosCrypto.x25519PublicFromPrivate(spkPriv);
            final byte[] spkSig = sign(LumosCrypto.concat(utf8(LumosConstants.X3DH_PREKEY_SIGNING_INFO_VALUE),
                    ByteBuffer.allocate(4).putInt((int) SPK_ID).array(), spkPub));
            return new X3dh.Bundle(edPub, LumosCrypto.x25519PublicFromPrivate(dhPriv), bindingSig(), SPK_ID, spkPub,
                    spkSig, OPK_ID, LumosCrypto.x25519PublicFromPrivate(opkPriv));
        }
    }

    /** A device: its keys, its database file and its chat store. */
    private final class Device {
        private final Keys keys;
        private final Path file;
        private final SoftwareKekStore kek = SoftwareKekStore.create();
        /** Created once: a restart reopens the same keys, as a real device does. */
        private final DataKeyHierarchy hierarchy = DataKeyHierarchy.create(kek);
        private final AtomicLong capacity = new AtomicLong(Long.MAX_VALUE);
        private LocalStore store;
        private ChatStore chat;

        Device(final Keys keys) throws Exception {
            this.keys = keys;
            this.file = Files.createTempFile("lumos-chat", ".db");
            this.file.toFile().deleteOnExit();
            boot();
        }

        /** (Re)starts the "process": a fresh connection, store and chat store on the same file. */
        void boot() throws Exception {
            final JdbcSqlDatabase db = JdbcSqlDatabase.atPath(file.toString());
            open.add(db);
            store = LocalStore.open(db, hierarchy.deriveKey(DataKeyHierarchy.DataDomain.DATABASE),
                    capacity::get, clock);
            chat = ChatStore.over(store, keys.identity(), clock, () -> random(32));
        }

        void publishPrekeys() throws StoreException {
            chat.putPrekeySecret(new PrekeySecret(false, SPK_ID, keys.spkPriv));
            chat.putPrekeySecret(new PrekeySecret(true, OPK_ID, keys.opkPriv));
        }
    }

    @AfterEach
    void closeAll() throws Exception {
        for (final AutoCloseable c : open) {
            c.close();
        }
    }

    private static RemoteText.TextEvent text(final String s) {
        return new RemoteText.TextEvent(TextEventKind.TEXT, null, utf8(s));
    }

    /** Moves every queued envelope from one device to the other, in the given order, and marks it accepted. */
    private static List<ChatStore.Received> deliver(final Device from, final Device to, final int... order)
            throws Exception {
        final List<ChatRecords.OutboxItem> items = from.chat.pendingOutbox();
        final java.util.List<ChatStore.Received> out = new java.util.ArrayList<>();
        final int[] sequence = order.length > 0 ? order : java.util.stream.IntStream.range(0, items.size()).toArray();
        for (final int i : sequence) {
            final ChatRecords.OutboxItem item = items.get(i);
            out.add(to.chat.receive(MATCH, item.messageId(), item.envelope()));
        }
        for (final ChatRecords.OutboxItem item : items) {
            from.chat.markAccepted(item.messageId());
        }
        return out;
    }

    private Device[] matchedPair() throws Exception {
        final Device alice = new Device(new Keys());
        final Device bob = new Device(new Keys());
        bob.publishPrekeys();
        bob.chat.acceptConversation(MATCH, alice.keys.edPub);
        alice.chat.startSession(MATCH, bob.keys.edPub, bob.keys.bundle(), random(32), random(32));
        return new Device[] {alice, bob};
    }

    @Test
    void twoDevicesChatThroughTheirOutboxesWhileOneIsOffline() throws Exception {
        final Device[] p = matchedPair();
        final Device alice = p[0];
        final Device bob = p[1];
        final byte[] first = alice.chat.send(MATCH, text("Hi! Lovely meeting you tonight."));
        final byte[] second = alice.chat.send(MATCH, text("Coffee on Saturday?"));
        assertEquals(2, alice.chat.pendingOutbox().size(), "both sends wait in the outbox (SRS-RMT-003)");
        for (final ChatRecords.OutboxItem i : alice.chat.pendingOutbox()) {
            assertEquals(RemoteText.FORMAT_PREKEY, i.envelope()[0] & 0xFF, "first contact carries the PREKEY fields");
        }
        assertEquals(1, bob.store.count(RecordKind.PREKEY_SECRET) - 1, "the one-time prekey is still there");

        // Bob comes online and the relay hands him the second message first.
        final List<ChatStore.Received> got = deliver(alice, bob, 1, 0);
        assertEquals(ChatStore.Outcome.DISPLAYED, got.get(0).outcome());
        assertEquals(ChatStore.Outcome.DISPLAYED, got.get(1).outcome());
        assertEquals(1, bob.store.count(RecordKind.PREKEY_SECRET), "the consumed one-time prekey is deleted (TRS-CRY-005)");
        for (final ChatRecords.Message m : alice.chat.messages(MATCH)) {
            assertEquals(DeliveryMachine.State.SENT, m.state(), "accepted by the transport is one tick, never delivered");
        }

        // Bob reacts and replies; Alice's next envelopes drop the PREKEY fields.
        bob.chat.send(MATCH, new RemoteText.TextEvent(TextEventKind.REACTION, first, utf8("❤")));
        bob.chat.send(MATCH, text("Yes! 11am?"));
        final List<ChatStore.Received> back = deliver(bob, alice);
        assertEquals(ChatStore.Outcome.APPLIED, back.get(0).outcome());
        assertEquals(ChatStore.Outcome.DISPLAYED, back.get(1).outcome());
        alice.chat.send(MATCH, new RemoteText.TextEvent(TextEventKind.EDIT, first, utf8("Hi! Lovely meeting you.")));
        assertEquals(RemoteText.FORMAT_NORMAL, alice.chat.pendingOutbox().get(0).envelope()[0] & 0xFF,
                "once the peer replied, envelopes are NORMAL");
        alice.chat.send(MATCH, new RemoteText.TextEvent(TextEventKind.DELETE_FOR_EVERYONE, second, null));
        deliver(alice, bob);

        final List<ChatRecords.Message> bobs = bob.chat.messages(MATCH);
        assertEquals(3, bobs.size(), "two from Alice, one from Bob; events change messages, they add none");
        final ChatRecords.Message edited = bobs.stream().filter(m -> Arrays.equals(m.messageId(), first)).findFirst()
                .orElseThrow();
        assertArrayEquals(utf8("Hi! Lovely meeting you."), edited.body());
        assertTrue(edited.edited());
        assertArrayEquals(utf8("❤"), edited.myReaction(), "Bob's own reaction shows on his side too");
        assertTrue(bobs.stream().anyMatch(m -> m.deleted() && m.body().length == 0), "deleted for everyone: body gone");
        final ChatRecords.Message alicesFirst = alice.chat.messages(MATCH).stream()
                .filter(m -> Arrays.equals(m.messageId(), first)).findFirst().orElseThrow();
        assertArrayEquals(utf8("❤"), alicesFirst.peerReaction(), "Alice sees Bob's reaction");
    }

    @Test
    void aMessageShowsAtMostOnceEvenIfDeliveredTwice() throws Exception {
        final Device[] p = matchedPair();
        p[0].chat.send(MATCH, text("once"));
        final ChatRecords.OutboxItem item = p[0].chat.pendingOutbox().get(0);
        assertEquals(ChatStore.Outcome.DISPLAYED, p[1].chat.receive(MATCH, item.messageId(), item.envelope()).outcome());
        assertEquals(ChatStore.Outcome.DUPLICATE, p[1].chat.receive(MATCH, item.messageId(), item.envelope()).outcome());
        assertEquals(1, p[1].chat.messages(MATCH).size(), "SRS-RMT-004");
    }

    @Test
    void aRestartLosesNothingAndReusesNoKey() throws Exception {
        final Device[] p = matchedPair();
        final Device alice = p[0];
        final Device bob = p[1];
        alice.chat.send(MATCH, text("one"));
        alice.chat.send(MATCH, text("two"));
        final List<ChatRecords.OutboxItem> before = alice.chat.pendingOutbox();

        alice.boot(); // process death on Alice's side
        final List<ChatRecords.OutboxItem> after = alice.chat.pendingOutbox();
        assertEquals(2, after.size(), "the outbox survives process death (TRS-LOC-004)");
        assertArrayEquals(before.get(0).envelope(), after.get(0).envelope(), "a retry resends the same bytes");

        assertEquals(ChatStore.Outcome.DISPLAYED,
                bob.chat.receive(MATCH, after.get(0).messageId(), after.get(0).envelope()).outcome());
        bob.boot(); // process death on Bob's side, between two messages
        assertEquals(ChatStore.Outcome.DISPLAYED,
                bob.chat.receive(MATCH, after.get(1).messageId(), after.get(1).envelope()).outcome());
        assertEquals(2, bob.chat.messages(MATCH).size());

        // Even once the dedup window has passed, the first message cannot open again: its key is gone.
        clock.advance((LumosConstants.DEDUP_CACHE_DAYS + 1) * 24L * 60 * 60 * 1000);
        assertEquals(2, bob.chat.purgeExpiredDedup());
        bob.boot();
        final ChatStore.Received replay = bob.chat.receive(MATCH, after.get(0).messageId(), after.get(0).envelope());
        assertEquals(ChatStore.Outcome.REJECTED, replay.outcome(), "a deleted message key is never reused (SRS-RMT-017)");
        assertEquals(2, bob.chat.messages(MATCH).size());

        // Alice's sends continue on the same session after her restart.
        alice.chat.send(MATCH, text("three"));
        assertEquals(ChatStore.Outcome.DISPLAYED, deliver(alice, bob, 2).get(0).outcome());
    }

    @Test
    void theMessageAndItsOutboxItemLandTogetherOrNotAtAll() throws Exception {
        final Device[] p = matchedPair();
        final Device alice = p[0];
        final byte[] stateBefore = alice.store.findByNaturalKey(RecordKind.RATCHET_SESSION, MATCH).get(0);
        // The store fills up mid-send: whatever was written first must roll back with it.
        alice.capacity.set(0);
        assertThrows(StoreException.class, () -> alice.chat.send(MATCH, text("lost?")));
        alice.capacity.set(Long.MAX_VALUE);
        assertEquals(0, alice.chat.pendingOutbox().size());
        assertEquals(0, alice.chat.messages(MATCH).size());
        assertArrayEquals(stateBefore, alice.store.findByNaturalKey(RecordKind.RATCHET_SESSION, MATCH).get(0),
                "the ratchet did not advance either");
        // An edit of a message that does not exist is refused before anything is written.
        assertThrows(StoreException.class, () -> alice.chat.send(MATCH,
                new RemoteText.TextEvent(TextEventKind.EDIT, random(16), utf8("x"))));
        assertEquals(0, alice.chat.pendingOutbox().size());
        // And a normal send still works, on the unadvanced session.
        alice.chat.send(MATCH, text("fine"));
        assertEquals(ChatStore.Outcome.DISPLAYED, deliver(alice, p[1]).get(0).outcome());
    }

    @Test
    void aRelabelledEnvelopeIsRefusedAndChangesNothing() throws Exception {
        final Device[] p = matchedPair();
        p[0].chat.send(MATCH, text("hi"));
        final ChatRecords.OutboxItem item = p[0].chat.pendingOutbox().get(0);
        final ChatStore.Received r = p[1].chat.receive(MATCH, random(16), item.envelope());
        assertEquals(ChatStore.Outcome.REJECTED, r.outcome());
        assertEquals("ERR_PAYLOAD_MALFORMED", r.code());
        assertFalse(p[1].chat.hasSession(MATCH), "a refused first message leaves no session behind");
        assertEquals(2, p[1].store.count(RecordKind.PREKEY_SECRET), "nor consumes the one-time prekey");
        assertEquals(ChatStore.Outcome.DISPLAYED, p[1].chat.receive(MATCH, item.messageId(), item.envelope()).outcome());
    }

    @Test
    void aConsumedOneTimePrekeyIsNeverAcceptedAgain() throws Exception {
        final Device[] p = matchedPair();
        p[0].chat.send(MATCH, text("first"));
        deliver(p[0], p[1]);
        // A second initiator (or a replayed first contact) names the same one-time prekey.
        final Device carol = new Device(new Keys());
        final byte[] other = random(16);
        p[1].chat.acceptConversation(other, carol.keys.edPub);
        final RemoteSession.Started s = RemoteSession.initiate(carol.keys.identity(), p[1].keys.bundle(), other,
                random(32), random(32));
        final byte[] id = ChatRecords.newMessageId();
        final byte[] env = RemoteSession.seal(s.ratchet(), new RemoteText.Plaintext(id, text("me too")), s.prekey(), 0);
        final ChatStore.Received r = p[1].chat.receive(other, id, env);
        assertEquals(ChatStore.Outcome.REJECTED, r.outcome(), "one-time means once (TRS-CRY-005)");
        assertEquals("ERR_CRYPTO_PREKEY_SIGNATURE_INVALID", r.code());
    }

    @Test
    void anUnknownEventKindIsConsumedAndIgnoredAndTheSessionCarriesOn() throws Exception {
        final Device[] p = matchedPair();
        final Device alice = p[0];
        final Device bob = p[1];
        // A newer app's event kind, sealed on Alice's real session.
        final ChatRecords.Session s = ChatRecords.Session.decode(
                alice.store.findByNaturalKey(RecordKind.RATCHET_SESSION, MATCH).get(0));
        final DoubleRatchet r = DoubleRatchet.restoreState(s.ratchetState(), DoubleRatchet.Bounds.defaults());
        final byte[] id = ChatRecords.newMessageId();
        final DoubleRatchet.Sealed sealed = r.encrypt(LumosCrypto.concat(id, new byte[] {0x7F, 1, 2, 3}), 0);
        final byte[] fields = s.prekeyFields();
        final byte[] env = LumosCrypto.concat(new byte[] {(byte) RemoteText.FORMAT_PREKEY}, fields,
                sealed.header().bytes(), sealed.ciphertext());
        alice.store.putByNaturalKey(RecordKind.RATCHET_SESSION, new ChatRecords.Session(r.exportState(), fields).encode(),
                MATCH);

        assertEquals(ChatStore.Outcome.IGNORED, bob.chat.receive(MATCH, id, env).outcome(),
                "an unknown kind is skipped safely (SRS-RMT-011)");
        assertEquals(ChatStore.Outcome.DUPLICATE, bob.chat.receive(MATCH, id, env).outcome(), "and consumed");
        alice.chat.send(MATCH, text("still here"));
        assertEquals(ChatStore.Outcome.DISPLAYED, deliver(alice, bob).get(0).outcome());
        assertEquals(1, bob.chat.messages(MATCH).size());
    }

    @Test
    void theOutboxIsFirstInFirstOutEvenWithinOneMillisecond() throws Exception {
        final Device[] p = matchedPair();
        final java.util.List<String> sent = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            sent.add(ChatRecords.rowId(p[0].chat.send(MATCH, text("m" + i)))); // the clock does not move
        }
        final java.util.List<String> queued = new java.util.ArrayList<>();
        for (final ChatRecords.OutboxItem item : p[0].chat.pendingOutbox()) {
            queued.add(ChatRecords.rowId(item.messageId()));
        }
        assertEquals(sent, queued, "the transport sends in the order the user did");
        final java.util.List<String> shown = new java.util.ArrayList<>();
        for (final ChatRecords.Message m : p[0].chat.messages(MATCH)) {
            shown.add(ChatRecords.rowId(m.messageId()));
        }
        assertEquals(sent, shown, "and the conversation shows them in that order");
        deliver(p[0], p[1]);
        final java.util.List<String> received = new java.util.ArrayList<>();
        for (final ChatRecords.Message m : p[1].chat.messages(MATCH)) {
            received.add(ChatRecords.rowId(m.messageId()));
        }
        assertEquals(sent, received, "the recipient shows them in arrival order, which here is send order");
    }
}
