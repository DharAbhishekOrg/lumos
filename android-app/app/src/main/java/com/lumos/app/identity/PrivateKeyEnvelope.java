/*
 * Lumos - android-app/app / The two-key private envelope sealed under the KEK.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-NFR-SEC-003, SRS-DAT-002
 * ADRs: ADR-003, ADR-008
 * Threading: any
 * Notes: Length-prefixed concatenation of the two PKCS#8 private keys, in a
 * fixed order (Ed25519 then X25519), versioned like KekWrapFormat and for the
 * same reason. The envelope only ever exists in plaintext transiently between
 * encode and KEK wrap, or between unwrap and use; both callers zero it. It is
 * a codec, not a store - nothing here retains key material.
 */

package com.lumos.app.identity;

import java.security.GeneralSecurityException;

/** Codec for the KEK-wrapped envelope holding both identity private keys. */
public final class PrivateKeyEnvelope {

    /** The only envelope version this build reads or writes. */
    public static final byte VERSION = 1;

    /** Width of each length prefix in bytes. */
    private static final int LENGTH_PREFIX_BYTES = 2;

    /** Upper bound for a single encoded private key; PKCS#8 Ed25519/X25519 is ~48 bytes. */
    private static final int MAX_KEY_BYTES = 0xFFFF;

    private PrivateKeyEnvelope() {
        throw new AssertionError("no instances");
    }

    /** The decoded envelope contents. */
    public static final class Keys {
        private final byte[] ed25519Pkcs8;
        private final byte[] x25519Pkcs8;

        private Keys(final byte[] ed25519Pkcs8, final byte[] x25519Pkcs8) {
            this.ed25519Pkcs8 = ed25519Pkcs8;
            this.x25519Pkcs8 = x25519Pkcs8;
        }

        /**
         * The Ed25519 private key in PKCS#8 form.
         *
         * @return a copy; the caller zeroes it after use
         */
        public byte[] ed25519Pkcs8() {
            return ed25519Pkcs8.clone();
        }

        /**
         * The X25519 private key in PKCS#8 form.
         *
         * @return a copy; the caller zeroes it after use
         */
        public byte[] x25519Pkcs8() {
            return x25519Pkcs8.clone();
        }
    }

    /**
     * Encodes both private keys into one envelope.
     *
     * @param ed25519Pkcs8 the Ed25519 private key, PKCS#8
     * @param x25519Pkcs8 the X25519 private key, PKCS#8
     * @return the envelope; the caller wraps it under the KEK and zeroes this copy
     * @throws GeneralSecurityException if either key exceeds the length prefix
     */
    public static byte[] encode(final byte[] ed25519Pkcs8, final byte[] x25519Pkcs8)
            throws GeneralSecurityException {
        if (ed25519Pkcs8.length > MAX_KEY_BYTES || x25519Pkcs8.length > MAX_KEY_BYTES) {
            throw new GeneralSecurityException("private key encoding too large");
        }
        final byte[] out = new byte[1 + LENGTH_PREFIX_BYTES + ed25519Pkcs8.length
                + LENGTH_PREFIX_BYTES + x25519Pkcs8.length];
        int at = 0;
        out[at] = VERSION;
        at += 1;
        at = putLengthPrefixed(out, at, ed25519Pkcs8);
        putLengthPrefixed(out, at, x25519Pkcs8);
        return out;
    }

    /**
     * Decodes an envelope, failing closed on any structural surprise.
     *
     * @param envelope the plaintext envelope, as unwrapped from the KEK
     * @return both keys
     * @throws GeneralSecurityException on a bad version, truncation or trailing bytes
     */
    public static Keys decode(final byte[] envelope) throws GeneralSecurityException {
        if (envelope.length < 1 + 2 * LENGTH_PREFIX_BYTES) {
            throw new GeneralSecurityException("envelope too short");
        }
        if (envelope[0] != VERSION) {
            throw new GeneralSecurityException("unknown envelope version");
        }
        int at = 1;
        final byte[] ed = readLengthPrefixed(envelope, at);
        at += LENGTH_PREFIX_BYTES + ed.length;
        final byte[] x = readLengthPrefixed(envelope, at);
        at += LENGTH_PREFIX_BYTES + x.length;
        if (at != envelope.length) {
            throw new GeneralSecurityException("trailing bytes after envelope");
        }
        return new Keys(ed, x);
    }

    /**
     * Writes one length-prefixed field.
     *
     * @param out the destination
     * @param at the write offset
     * @param value the field bytes
     * @return the offset after the field
     */
    private static int putLengthPrefixed(final byte[] out, final int at, final byte[] value) {
        out[at] = (byte) ((value.length >>> 8) & 0xFF);
        out[at + 1] = (byte) (value.length & 0xFF);
        System.arraycopy(value, 0, out, at + LENGTH_PREFIX_BYTES, value.length);
        return at + LENGTH_PREFIX_BYTES + value.length;
    }

    /**
     * Reads one length-prefixed field.
     *
     * @param in the envelope
     * @param at the read offset
     * @return the field bytes
     * @throws GeneralSecurityException on truncation
     */
    private static byte[] readLengthPrefixed(final byte[] in, final int at) throws GeneralSecurityException {
        if (at + LENGTH_PREFIX_BYTES > in.length) {
            throw new GeneralSecurityException("envelope truncated at length prefix");
        }
        final int length = ((in[at] & 0xFF) << 8) | (in[at + 1] & 0xFF);
        if (at + LENGTH_PREFIX_BYTES + length > in.length) {
            throw new GeneralSecurityException("envelope truncated in field");
        }
        final byte[] out = new byte[length];
        System.arraycopy(in, at + LENGTH_PREFIX_BYTES, out, 0, length);
        return out;
    }
}
