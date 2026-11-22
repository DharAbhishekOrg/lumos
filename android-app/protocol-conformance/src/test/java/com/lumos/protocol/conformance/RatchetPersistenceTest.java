/*
 * Lumos - android-app/protocol-conformance / A Double Ratchet session survives process death without reusing a key.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-017, TRS-CRY-002, TRS-CRY-006, TRS-CRY-007, TRS-TST-001
 * ADRs: ADR-001
 * Threading: test-only
 * Notes: "Process death" is modelled as export, drop every object, restore.
 * The properties: a restored session is indistinguishable from one that never
 * restarted (same ciphertexts for the same inputs, given the same key
 * provider); a message key already used stays gone after a restart, so a
 * replayed envelope fails; skipped keys, and their timestamps, survive; and
 * bytes that are not a state are refused with a stable code, never a crash.
 */

package com.lumos.protocol.conformance;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lumos.protocol.ratchet.DoubleRatchet;
import com.lumos.protocol.wire.Frame;
import com.lumos.protocol.wire.LumosCrypto;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Export and restore of Double Ratchet state. */
public class RatchetPersistenceTest {

    private static final long NOW = 1_700_000_000L;
    private static final SecureRandom RNG = new SecureRandom();

    private static byte[] random32() {
        final byte[] b = new byte[32];
        RNG.nextBytes(b);
        return b;
    }

    /** A key provider that replays a fixed list, so twins can be compared. */
    private static DoubleRatchet.KeyProvider fixed(final List<byte[]> keys) {
        final List<byte[]> copy = new ArrayList<>(keys);
        return () -> copy.remove(0);
    }

    private static byte[] utf8(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static DoubleRatchet[] pair(final byte[] sk, final byte[] ad, final byte[] alicePriv, final byte[] bobPriv)
            throws Exception {
        return new DoubleRatchet[] {
            DoubleRatchet.initiator(sk, ad, LumosCrypto.x25519PublicFromPrivate(bobPriv), alicePriv,
                    DoubleRatchet.Bounds.defaults()),
            DoubleRatchet.responder(sk, ad, bobPriv, DoubleRatchet.Bounds.defaults()),
        };
    }

    private static DoubleRatchet reborn(final DoubleRatchet r) throws Frame.WireException {
        return DoubleRatchet.restoreState(r.exportState(), DoubleRatchet.Bounds.defaults());
    }

    @Test
    void aRestoredSessionIsIndistinguishableFromOneThatNeverRestarted() throws Exception {
        final byte[] sk = random32();
        final byte[] ad = random32();
        final byte[] alicePriv = random32();
        final byte[] bobPriv = random32();
        final List<byte[]> turns = List.of(random32(), random32(), random32(), random32());
        final DoubleRatchet[] steady = pair(sk, ad, alicePriv, bobPriv);
        final DoubleRatchet[] restarted = pair(sk, ad, alicePriv, bobPriv);
        final DoubleRatchet.KeyProvider steadyBob = fixed(turns);
        final DoubleRatchet.KeyProvider restartedBob = fixed(turns);
        final DoubleRatchet.KeyProvider steadyAlice = fixed(turns.subList(2, 4));
        final DoubleRatchet.KeyProvider restartedAlice = fixed(turns.subList(2, 4));

        for (int round = 0; round < 3; round++) {
            // Alice -> Bob, with both "restarted" sides dying and coming back each step.
            final byte[] msg = utf8("round " + round);
            final DoubleRatchet.Sealed a = steady[0].encrypt(msg, NOW);
            final DoubleRatchet.Sealed b = restarted[0].encrypt(msg, NOW);
            assertArrayEquals(a.ciphertext(), b.ciphertext(), "a restored sender seals exactly as before");
            restarted[0] = reborn(restarted[0]);
            assertArrayEquals(msg, steady[1].decrypt(a.header(), a.ciphertext(), NOW, steadyBob));
            assertArrayEquals(msg, restarted[1].decrypt(b.header(), b.ciphertext(), NOW, restartedBob));
            restarted[1] = reborn(restarted[1]);
            // Bob -> Alice, turning the DH ratchet.
            final DoubleRatchet.Sealed c = steady[1].encrypt(msg, NOW);
            final DoubleRatchet.Sealed d = restarted[1].encrypt(msg, NOW);
            assertArrayEquals(c.ciphertext(), d.ciphertext(), "a restored responder seals exactly as before");
            restarted[1] = reborn(restarted[1]);
            assertArrayEquals(msg, steady[0].decrypt(c.header(), c.ciphertext(), NOW, steadyAlice));
            assertArrayEquals(msg, restarted[0].decrypt(d.header(), d.ciphertext(), NOW, restartedAlice));
            restarted[0] = reborn(restarted[0]);
            if (round == 0) {
                break; // one DH turn each is enough for the fixed key lists
            }
        }
    }

    @Test
    void aKeyAlreadyUsedStaysGoneAfterARestart() throws Exception {
        final DoubleRatchet[] p = pair(random32(), random32(), random32(), random32());
        final DoubleRatchet.Sealed first = p[0].encrypt(utf8("once"), NOW);
        p[1].decrypt(first.header(), first.ciphertext(), NOW, RatchetPersistenceTest::random32);
        final DoubleRatchet bobAfterDeath = reborn(p[1]);
        final Frame.WireException replay = assertThrows(Frame.WireException.class,
                () -> bobAfterDeath.decrypt(first.header(), first.ciphertext(), NOW, RatchetPersistenceTest::random32));
        assertEquals("ERR_CRYPTO_AEAD_AUTH_FAILED", replay.code(), "a used message key must not come back (SRS-RMT-017)");
    }

    @Test
    void skippedKeysSurviveARestartAndAreStillUsedOnce() throws Exception {
        final DoubleRatchet[] p = pair(random32(), random32(), random32(), random32());
        final DoubleRatchet.Sealed m0 = p[0].encrypt(utf8("zero"), NOW);
        final DoubleRatchet.Sealed m1 = p[0].encrypt(utf8("one"), NOW);
        final DoubleRatchet.Sealed m2 = p[0].encrypt(utf8("two"), NOW);
        assertArrayEquals(utf8("two"), p[1].decrypt(m2.header(), m2.ciphertext(), NOW, RatchetPersistenceTest::random32));
        assertEquals(2, p[1].skippedCount());
        final DoubleRatchet bob = reborn(p[1]);
        assertEquals(2, bob.skippedCount(), "skipped keys are part of the state");
        assertArrayEquals(utf8("zero"), bob.decrypt(m0.header(), m0.ciphertext(), NOW, RatchetPersistenceTest::random32));
        final DoubleRatchet again = reborn(bob);
        assertArrayEquals(utf8("one"), again.decrypt(m1.header(), m1.ciphertext(), NOW, RatchetPersistenceTest::random32));
        assertEquals(0, again.skippedCount());
        final DoubleRatchet last = reborn(again);
        assertThrows(Frame.WireException.class,
                () -> last.decrypt(m0.header(), m0.ciphertext(), NOW, RatchetPersistenceTest::random32));
    }

    @Test
    void bytesThatAreNotAStateAreRefusedWithAStableCode() throws Exception {
        final DoubleRatchet[] p = pair(random32(), random32(), random32(), random32());
        final byte[] good = p[0].exportState();
        final List<byte[]> bad = List.of(new byte[0], new byte[] {9}, Arrays.copyOf(good, good.length - 1),
                Arrays.copyOf(good, good.length + 1));
        for (final byte[] b : bad) {
            final Frame.WireException e = assertThrows(Frame.WireException.class,
                    () -> DoubleRatchet.restoreState(b, DoubleRatchet.Bounds.defaults()));
            assertEquals("ERR_PAYLOAD_MALFORMED", e.code());
        }
    }
}
