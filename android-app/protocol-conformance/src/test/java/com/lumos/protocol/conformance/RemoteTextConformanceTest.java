/*
 * Lumos - android-app/protocol-conformance / Holds the Java remote-text wire and sessions to the Go vectors.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-001, SRS-RMT-004, SRS-RMT-009, SRS-RMT-011, TRS-CRY-001, TRS-CRY-002, TRS-TST-001
 * ADRs: ADR-001, ADR-008
 * Threading: test-only
 * Notes: Replays test-vectors/chat/remote_text.json. Every text event decodes
 * and re-encodes byte for byte; every negative earns its exact code; and the
 * scripted session runs both sides from the recorded keys - each seal must
 * reproduce the Go envelope exactly, each open must yield the Go plaintext.
 * Bob's bundle is assembled from the recorded PUBLIC fields and signatures,
 * so the Java X3DH verifies real Go signatures rather than its own.
 */

package com.lumos.protocol.conformance;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lumos.protocol.chat.RemoteSession;
import com.lumos.protocol.chat.RemoteText;
import com.lumos.protocol.ratchet.DoubleRatchet;
import com.lumos.protocol.wire.Frame;
import com.lumos.protocol.x3dh.X3dh;
import java.security.GeneralSecurityException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The Java remote-text wire and sessions against the committed vectors. */
public class RemoteTextConformanceTest {

    private static final String FILE = "test-vectors/chat/remote_text.json";

    private static DoubleRatchet.KeyProvider recorded(final JsonObject party) {
        final Deque<byte[]> keys = new ArrayDeque<>();
        for (final JsonElement e : Vectors.arr(party, "testonly_ratchet_turn_privs_hex")) {
            keys.add(Vectors.hexToBytes(e.getAsString()));
        }
        return () -> {
            if (keys.isEmpty()) {
                throw new GeneralSecurityException("recorded ratchet keys exhausted");
            }
            return keys.poll();
        };
    }

    @Test
    void everyTextEventDecodesAndReencodesByteForByte() throws Exception {
        final JsonObject root = Vectors.load(FILE);
        assertFalse(Vectors.arr(root, "text_events").isEmpty(), "an empty section proves nothing");
        for (final JsonElement el : Vectors.arr(root, "text_events")) {
            final JsonObject c = Vectors.obj(el);
            final byte[] encoded = Vectors.hex(c, "encoded_hex");
            final RemoteText.TextEvent e = RemoteText.decodeEvent(encoded);
            assertEquals(Vectors.i(c, "kind"), e.kind().code(), Vectors.str(c, "name"));
            if (c.has("body_hex")) {
                assertArrayEquals(Vectors.hex(c, "body_hex"), e.body(), Vectors.str(c, "name"));
            }
            if (c.has("target_message_id_hex")) {
                assertArrayEquals(Vectors.hex(c, "target_message_id_hex"), e.target(), Vectors.str(c, "name"));
            }
            assertArrayEquals(encoded, e.encode(), "re-encoding " + Vectors.str(c, "name"));
        }
    }

    @Test
    void everyNegativeEarnsItsExactCode() {
        final JsonObject root = Vectors.load(FILE);
        for (final JsonElement el : Vectors.arr(root, "text_event_negatives")) {
            final JsonObject c = Vectors.obj(el);
            final Frame.WireException e = assertThrows(Frame.WireException.class,
                    () -> RemoteText.decodeEvent(Vectors.hex(c, "encoded_hex")), Vectors.str(c, "name"));
            assertEquals(Vectors.str(c, "expected_error"), e.code(), Vectors.str(c, "name"));
        }
        for (final JsonElement el : Vectors.arr(root, "envelope_negatives")) {
            final JsonObject c = Vectors.obj(el);
            final Frame.WireException e = assertThrows(Frame.WireException.class,
                    () -> RemoteText.decodeEnvelope(Vectors.hex(c, "encoded_hex")), Vectors.str(c, "name"));
            assertEquals(Vectors.str(c, "expected_error"), e.code(), Vectors.str(c, "name"));
        }
    }

    @Test
    void theScriptedSessionReplaysByteForByte() throws Exception {
        final JsonObject s = Vectors.obj(Vectors.load(FILE).get("session"));
        final JsonObject a = Vectors.obj(s.get("alice"));
        final JsonObject b = Vectors.obj(s.get("bob"));
        final byte[] matchId = Vectors.hex(s, "match_id_hex");
        final long now = Vectors.l(s, "now");
        final byte[] aliceEd = Vectors.hex(a, "identity_ed_pub_hex");
        final RemoteSession.Identity alice = new RemoteSession.Identity(aliceEd,
                Vectors.hex(a, "testonly_identity_dh_priv_hex"), Vectors.hex(a, "identity_dh_sig_hex"));
        final RemoteSession.Identity bob = new RemoteSession.Identity(Vectors.hex(b, "identity_ed_pub_hex"),
                Vectors.hex(b, "testonly_identity_dh_priv_hex"), Vectors.hex(b, "identity_dh_sig_hex"));
        final X3dh.Bundle bundle = new X3dh.Bundle(Vectors.hex(b, "identity_ed_pub_hex"),
                Vectors.hex(b, "identity_dh_pub_hex"), Vectors.hex(b, "identity_dh_sig_hex"), Vectors.l(b, "spk_id"),
                Vectors.hex(b, "spk_pub_hex"), Vectors.hex(b, "spk_sig_hex"), Vectors.l(b, "opk_id"),
                Vectors.hex(b, "opk_pub_hex"));
        final DoubleRatchet.KeyProvider aliceKeys = recorded(a);
        final DoubleRatchet.KeyProvider bobKeys = recorded(b);

        final RemoteSession.Started started = RemoteSession.initiate(alice, bundle, matchId,
                Vectors.hex(a, "testonly_ephemeral_priv_hex"), Vectors.hex(a, "testonly_first_ratchet_priv_hex"));
        DoubleRatchet bobState = null;
        boolean heardBack = false;
        final Set<Integer> kinds = new HashSet<>();
        for (final JsonElement el : Vectors.arr(s, "steps")) {
            final JsonObject step = Vectors.obj(el);
            final JsonObject m = Vectors.obj(Vectors.arr(s, "messages").get(Vectors.i(step, "message")));
            final boolean byBob = "bob".equals(Vectors.str(step, "actor"));
            final byte[] envelope = Vectors.hex(m, "envelope_hex");
            final byte[] plaintext = Vectors.hex(m, "plaintext_hex");
            if ("seal".equals(Vectors.str(step, "op"))) {
                final RemoteText.Plaintext p = RemoteText.decodePlaintext(plaintext);
                kinds.add(p.event().kind().code());
                final byte[] sealed = byBob
                        ? RemoteSession.seal(bobState, p, null, now)
                        : RemoteSession.seal(started.ratchet(), p, heardBack ? null : started.prekey(), now);
                assertEquals(Vectors.toHex(envelope), Vectors.toHex(sealed), "seal must reproduce the Go envelope");
                continue;
            }
            final RemoteText.Envelope e = RemoteText.decodeEnvelope(envelope);
            final byte[] outerId = Vectors.hex(m, "message_id_hex");
            final RemoteText.Plaintext got;
            if (byBob) {
                if (bobState == null) {
                    assertNotNull(e.prekey(), "first contact is a PREKEY envelope");
                    bobState = RemoteSession.respond(bob, new RemoteSession.ResponderKeys(
                            Vectors.hex(b, "testonly_spk_priv_hex"), Vectors.l(b, "spk_id"),
                            Vectors.hex(b, "testonly_opk_priv_hex"), Vectors.l(b, "opk_id")), aliceEd, e.prekey(), matchId);
                }
                got = RemoteSession.open(bobState, e, outerId, now, bobKeys);
            } else {
                got = RemoteSession.open(started.ratchet(), e, outerId, now, aliceKeys);
                heardBack = true;
            }
            assertArrayEquals(plaintext, got.encode(), "open must yield the Go plaintext");
        }
        assertEquals(4, kinds.size(), "the session exercises all four event kinds");
    }

    @Test
    void aRelabelledOrTamperedFirstMessageIsRefused() throws Exception {
        final JsonObject s = Vectors.obj(Vectors.load(FILE).get("session"));
        final JsonObject a = Vectors.obj(s.get("alice"));
        final JsonObject b = Vectors.obj(s.get("bob"));
        final JsonObject first = Vectors.obj(Vectors.arr(s, "messages").get(0));
        final RemoteText.Envelope e = RemoteText.decodeEnvelope(Vectors.hex(first, "envelope_hex"));
        final RemoteSession.Identity bob = new RemoteSession.Identity(Vectors.hex(b, "identity_ed_pub_hex"),
                Vectors.hex(b, "testonly_identity_dh_priv_hex"), Vectors.hex(b, "identity_dh_sig_hex"));
        final RemoteSession.ResponderKeys keys = new RemoteSession.ResponderKeys(Vectors.hex(b, "testonly_spk_priv_hex"),
                Vectors.l(b, "spk_id"), Vectors.hex(b, "testonly_opk_priv_hex"), Vectors.l(b, "opk_id"));
        final byte[] matchId = Vectors.hex(s, "match_id_hex");

        // Someone other than the matched peer: Bob's own identity stands in for a stranger.
        final Frame.WireException stranger = assertThrows(Frame.WireException.class,
                () -> RemoteSession.respond(bob, keys, Vectors.hex(b, "identity_ed_pub_hex"), e.prekey(), matchId));
        assertEquals("ERR_CRYPTO_PREKEY_SIGNATURE_INVALID", stranger.code());

        final DoubleRatchet state = RemoteSession.respond(bob, keys, Vectors.hex(a, "identity_ed_pub_hex"), e.prekey(), matchId);
        final byte[] wrongId = Vectors.hex(Vectors.obj(Vectors.arr(s, "messages").get(1)), "message_id_hex");
        final Frame.WireException relabelled = assertThrows(Frame.WireException.class,
                () -> RemoteSession.open(state, e, wrongId, Vectors.l(s, "now"), recorded(b)));
        assertEquals("ERR_PAYLOAD_MALFORMED", relabelled.code());
        assertTrue(true);
    }
}
