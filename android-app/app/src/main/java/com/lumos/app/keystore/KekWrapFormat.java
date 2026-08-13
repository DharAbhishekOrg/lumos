/*
 * Lumos - android-app/app / The wrapped-blob framing shared by every KEK store.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-DAT-002, SRS-NFR-SEC-003
 * ADRs: ADR-003
 * Threading: any
 * Notes: One byte of version, twelve bytes of GCM IV, then ciphertext with the
 * appended tag. The framing lives in one place so the hardware and software
 * stores cannot drift apart in how they lay a blob out - a blob wrapped before
 * a device gained StrongBox must still parse after. The version byte exists for
 * the day the layout changes; an unknown version fails closed rather than being
 * guessed at.
 */

package com.lumos.app.keystore;

import java.security.GeneralSecurityException;

/** Framing for KEK-wrapped blobs: {@code [version:1][iv:12][ciphertext||tag]}. */
public final class KekWrapFormat {

    /** The only wrap-format version this build reads or writes. */
    public static final byte VERSION = 1;

    /** AES-GCM IV length in bytes; the NIST SP 800-38D recommended length. */
    public static final int GCM_IV_BYTES = 12;

    /** AES-GCM authentication-tag length in bits. */
    public static final int GCM_TAG_BITS = 128;

    private KekWrapFormat() {
        throw new AssertionError("no instances");
    }

    /** A parsed wrapped blob. */
    public static final class Parts {
        private final byte[] iv;
        private final byte[] ciphertext;

        private Parts(final byte[] iv, final byte[] ciphertext) {
            this.iv = iv;
            this.ciphertext = ciphertext;
        }

        /**
         * The GCM IV.
         *
         * @return a copy of the twelve IV bytes
         */
        public byte[] iv() {
            return iv.clone();
        }

        /**
         * The ciphertext with its appended tag.
         *
         * @return a copy of the ciphertext bytes
         */
        public byte[] ciphertext() {
            return ciphertext.clone();
        }
    }

    /**
     * Frames an IV and ciphertext into a versioned blob.
     *
     * @param iv the twelve-byte GCM IV
     * @param ciphertext the ciphertext with appended tag
     * @return the framed blob
     * @throws GeneralSecurityException if the IV length is not {@link #GCM_IV_BYTES}
     */
    public static byte[] encode(final byte[] iv, final byte[] ciphertext) throws GeneralSecurityException {
        if (iv.length != GCM_IV_BYTES) {
            throw new GeneralSecurityException("IV must be " + GCM_IV_BYTES + " bytes");
        }
        final byte[] out = new byte[1 + GCM_IV_BYTES + ciphertext.length];
        out[0] = VERSION;
        System.arraycopy(iv, 0, out, 1, GCM_IV_BYTES);
        System.arraycopy(ciphertext, 0, out, 1 + GCM_IV_BYTES, ciphertext.length);
        return out;
    }

    /**
     * Parses a framed blob, failing closed on anything unexpected.
     *
     * @param wrapped the framed blob
     * @return the parsed parts
     * @throws GeneralSecurityException on a short blob or an unknown version
     */
    public static Parts decode(final byte[] wrapped) throws GeneralSecurityException {
        // The minimum meaningful blob carries the version, a full IV and at
        // least a GCM tag's worth of ciphertext.
        final int minLength = 1 + GCM_IV_BYTES + GCM_TAG_BITS / Byte.SIZE;
        if (wrapped.length < minLength) {
            throw new GeneralSecurityException("wrapped blob too short");
        }
        if (wrapped[0] != VERSION) {
            throw new GeneralSecurityException("unknown wrap-format version");
        }
        final byte[] iv = new byte[GCM_IV_BYTES];
        System.arraycopy(wrapped, 1, iv, 0, GCM_IV_BYTES);
        final byte[] ciphertext = new byte[wrapped.length - 1 - GCM_IV_BYTES];
        System.arraycopy(wrapped, 1 + GCM_IV_BYTES, ciphertext, 0, ciphertext.length);
        return new Parts(iv, ciphertext);
    }
}
