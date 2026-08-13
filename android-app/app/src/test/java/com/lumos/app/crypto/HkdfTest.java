/*
 * Lumos - android-app/app / HKDF known-answer test against RFC 5869.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-CRY-001, TRS-CRY-015
 * ADRs: ADR-001
 * Threading: any
 * Notes: RFC 5869 Appendix A test case 1, transcribed BY HAND from the RFC so
 * the test cannot agree with the implementation by construction (the WP-01
 * closeout Sec. 5.2 lesson). The app's Hkdf must also agree byte-for-byte with
 * the conformance runner's, since both claim to be the same function.
 */

package com.lumos.app.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Pins Hkdf to the RFC 5869 known answers. */
final class HkdfTest {

    /**
     * Decodes a hex fixture.
     *
     * @param s the hex string
     * @return the bytes
     */
    private static byte[] hex(final String s) {
        final byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    @Test
    void rfc5869TestCase1() {
        final byte[] ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        final byte[] salt = hex("000102030405060708090a0b0c");
        final byte[] info = hex("f0f1f2f3f4f5f6f7f8f9");
        final byte[] expected = hex(
                "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865");
        assertArrayEquals(expected, Hkdf.derive(ikm, salt, info, 42),
                "HKDF-SHA-256 must reproduce the RFC 5869 A.1 OKM exactly");
    }

    @Test
    void expansionPastOneBlockIsContinuous() {
        final byte[] ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        final byte[] salt = new byte[Hkdf.HASH_LEN];
        final byte[] info = "lumos/test".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final byte[] longOut = Hkdf.derive(ikm, salt, info, 64);
        final byte[] shortOut = Hkdf.derive(ikm, salt, info, 32);
        assertArrayEquals(shortOut, Arrays.copyOfRange(longOut, 0, 32),
                "a longer derivation must extend, not alter, a shorter one");
        assertEquals(new BigInteger(1, longOut).bitLength() > 0, true,
                "derived material must be non-degenerate");
    }
}
