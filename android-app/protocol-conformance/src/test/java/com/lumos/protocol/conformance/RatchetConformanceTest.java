/*
 * Lumos - android-app/protocol-conformance / The Double Ratchet, replayed on Java.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-CRY-002, TRS-CRY-006, TRS-CRY-007, TRS-CRY-013, TRS-TST-001
 * ADRs: ADR-001, ADR-008
 * Threading: test-only
 * Notes: Every committed sequence of crypto/ratchet_sequences.json decrypts
 * byte-for-byte on this port using the RECORDED turn keys (never re-derived
 * seeds), and the MAX_SKIP negative fails with its exact code. Beyond the
 * vectors: a used key is gone (replay fails), a tampered header fails, a
 * skipped key is consumed once, and an expired skipped key is pruned.
 */

package com.lumos.protocol.conformance;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lumos.protocol.LumosErrors;
import com.lumos.protocol.ratchet.DoubleRatchet;
import com.lumos.protocol.wire.Frame;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayDeque;
import java.util.Deque;
import org.junit.jupiter.api.Test;

/** Holds the Java ratchet to the committed golden vectors. */
public class RatchetConformanceTest {

    private static final String FILE = "test-vectors/crypto/ratchet_sequences.json";
    private static final long NOW = 1_700_000_000L;

    /** Replays a recorded list of turn scalars, as the Go consumer does. */
    private static DoubleRatchet.KeyProvider recorded(final JsonObject root, final String field) {
        final Deque<byte[]> keys = new ArrayDeque<>();
        for (final JsonElement e : Vectors.arr(root, field)) {
            keys.add(Vectors.hexToBytes(e.getAsString()));
        }
        return () -> {
            if (keys.isEmpty()) {
                throw new GeneralSecurityException("recorded turn-key list exhausted");
            }
            return keys.poll();
        };
    }

    private static final class Pair {
        private final DoubleRatchet alice;
        private final DoubleRatchet bob;

        Pair(final JsonObject root) throws GeneralSecurityException, Frame.WireException {
            final byte[] sk = Vectors.hex(root, "shared_secret_hex");
            final byte[] ad = Vectors.hex(root, "associated_data_hex");
            final byte[] respPriv = Vectors.hex(root, "testonly_responder_ratchet_priv_hex");
            final byte[] respPub = com.lumos.protocol.wire.LumosCrypto.x25519PublicFromPrivate(respPriv);
            alice = DoubleRatchet.initiator(sk, ad, respPub,
                    Vectors.hex(root, "testonly_initiator_ratchet_priv_hex"), DoubleRatchet.Bounds.defaults());
            bob = DoubleRatchet.responder(sk, ad, respPriv, DoubleRatchet.Bounds.defaults());
        }
    }

    @Test
    void committedSequencesDecryptByteForByte() throws Exception {
        final JsonObject root = Vectors.load(FILE);
        assertFalse(Vectors.arr(root, "sequences").isEmpty(), "an empty sequence set proves nothing");
        for (final JsonElement s : Vectors.arr(root, "sequences")) {
            final JsonObject seq = Vectors.obj(s);
            final Pair p = new Pair(root);
            final DoubleRatchet.KeyProvider kpA = recorded(root, "testonly_initiator_dh_turn_privs_hex");
            final DoubleRatchet.KeyProvider kpB = recorded(root, "testonly_responder_dh_turn_privs_hex");
            for (final JsonElement m : Vectors.arr(seq, "messages")) {
                final JsonObject msg = Vectors.obj(m);
                final boolean toBob = "a2b".equals(Vectors.str(msg, "direction"));
                final DoubleRatchet.Header h = DoubleRatchet.Header.parse(Vectors.hex(msg, "header_hex"));
                final byte[] pt = (toBob ? p.bob : p.alice).decrypt(h, Vectors.hex(msg, "ciphertext_hex"), NOW,
                        toBob ? kpB : kpA);
                assertEquals(Vectors.str(msg, "plaintext_hex"), Vectors.toHex(pt),
                        Vectors.str(seq, "name") + ": plaintext");
            }
        }
    }

    @Test
    void theMaxSkipNegativeFailsWithItsExactCode() throws Exception {
        final JsonObject root = Vectors.load(FILE);
        for (final JsonElement n : Vectors.arr(root, "negatives")) {
            final JsonObject neg = Vectors.obj(n);
            final Pair p = new Pair(root);
            DoubleRatchet.Sealed last = null;
            for (int i = 0; i <= Vectors.i(neg, "gap"); i++) {
                last = p.alice.encrypt("x".getBytes(StandardCharsets.UTF_8), NOW);
            }
            final DoubleRatchet.Sealed fin = last;
            final Frame.WireException e = assertThrows(Frame.WireException.class,
                    () -> p.bob.decrypt(fin.header(), fin.ciphertext(), NOW,
                            recorded(root, "testonly_responder_dh_turn_privs_hex")));
            assertEquals(Vectors.str(neg, "expected_error"), e.code(), Vectors.str(neg, "name"));
            assertEquals(0, p.bob.skippedCount(), "refused before any key is stored");
        }
    }

    @Test
    void aUsedKeyIsGoneAndATamperedHeaderFails() throws Exception {
        final JsonObject root = Vectors.load(FILE);
        final Pair p = new Pair(root);
        final DoubleRatchet.KeyProvider kpB = recorded(root, "testonly_responder_dh_turn_privs_hex");
        final DoubleRatchet.Sealed first = p.alice.encrypt(new byte[] {1}, NOW);
        assertArrayEquals(new byte[] {1}, p.bob.decrypt(first.header(), first.ciphertext(), NOW, kpB));
        final Frame.WireException replay = assertThrows(Frame.WireException.class,
                () -> p.bob.decrypt(first.header(), first.ciphertext(), NOW, kpB));
        assertEquals(LumosErrors.ERR_CRYPTO_AEAD_AUTH_FAILED, replay.code(), "a consumed key never decrypts twice");

        final DoubleRatchet.Sealed second = p.alice.encrypt(new byte[] {2}, NOW);
        final DoubleRatchet.Header bent = new DoubleRatchet.Header(second.header().dh(),
                second.header().pn() + 1, second.header().n());
        final Frame.WireException tamper = assertThrows(Frame.WireException.class,
                () -> p.bob.decrypt(bent, second.ciphertext(), NOW, kpB));
        assertEquals(LumosErrors.ERR_CRYPTO_AEAD_AUTH_FAILED, tamper.code(), "the header is authenticated");
    }

    @Test
    void skippedKeysAreConsumedOnceAndExpire() throws Exception {
        final JsonObject root = Vectors.load(FILE);
        final Pair p = new Pair(root);
        final DoubleRatchet.KeyProvider kpB = recorded(root, "testonly_responder_dh_turn_privs_hex");
        final DoubleRatchet.Sealed m0 = p.alice.encrypt(new byte[] {0}, NOW);
        final DoubleRatchet.Sealed m1 = p.alice.encrypt(new byte[] {1}, NOW);
        final DoubleRatchet.Sealed m2 = p.alice.encrypt(new byte[] {2}, NOW);
        p.bob.decrypt(m2.header(), m2.ciphertext(), NOW, kpB);
        assertEquals(2, p.bob.skippedCount(), "keys for 0 and 1 are stored");
        assertArrayEquals(new byte[] {1}, p.bob.decrypt(m1.header(), m1.ciphertext(), NOW, kpB));
        assertEquals(1, p.bob.skippedCount(), "a used skipped key is deleted");
        final long ttl = DoubleRatchet.Bounds.defaults().skippedTtlSec();
        final Frame.WireException late = assertThrows(Frame.WireException.class,
                () -> p.bob.decrypt(m0.header(), m0.ciphertext(), NOW + ttl + 1, kpB));
        assertEquals(LumosErrors.ERR_CRYPTO_AEAD_AUTH_FAILED, late.code(), "an expired key is simply gone");
        assertEquals(0, p.bob.skippedCount());
    }
}
