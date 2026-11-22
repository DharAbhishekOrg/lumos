/*
 * Lumos - android-app/protocol-java / The protocol's primitives per ADR-001; curves and AEAD behind a seam.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-CRY-001, TRS-CRY-003, TRS-CRY-011, TRS-TST-001
 * ADRs: ADR-001
 * Threading: any
 * Notes: SHA-256, HMAC-SHA-256 and HKDF come from the JCA, which has them
 * on every Android level the app supports. HKDF is written out over Mac
 * because JDK 21 predates javax.crypto.KDF; it is RFC 5869 extract-then-expand
 * and nothing more. X25519, Ed25519 verification and ChaCha20-Poly1305 go
 * through the installed CryptoBackend (WP-05 S7d): Android's JCA has XDH and
 * Ed25519 only from API 33 and ChaCha20-Poly1305 only from API 28, while the
 * app runs from API 26, so the app installs libsodium (ADR-001) and the JVM
 * keeps the JCA backend. Every call here takes and returns raw bytes.
 */
package com.lumos.protocol.wire;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** The protocol's primitives, matching ADR-001. */
public final class LumosCrypto {

    private LumosCrypto() {
        throw new AssertionError("no instances");
    }

    /** ChaCha20-Poly1305 key size in bytes. */
    public static final int AEAD_KEY_SIZE = 32;

    /** ChaCha20-Poly1305 (IETF) nonce size in bytes. */
    public static final int AEAD_NONCE_SIZE = 12;

    /** The installed backend, or null for the JVM default. */
    private static volatile CryptoBackend installed;

    /**
     * @param data input bytes
     * @return SHA-256 digest
     */
    public static byte[] sha256(final byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * @param key HMAC key
     * @param data message
     * @return HMAC-SHA-256 tag
     */
    public static byte[] hmacSha256(final byte[] key, final byte[] data) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA-256 unavailable", e);
        }
    }

    /**
     * RFC 5869 HKDF-SHA-256, extract then expand.
     *
     * @param ikm input keying material
     * @param salt salt; a zero-filled 32-byte salt is used by the Lumos KDFs
     * @param info context string
     * @param length output length in bytes
     * @return derived key material
     */
    public static byte[] hkdf(final byte[] ikm, final byte[] salt, final byte[] info, final int length) {
        final byte[] prk = hmacSha256(salt, ikm);
        final byte[] out = new byte[length];
        byte[] block = new byte[0];
        int filled = 0;
        byte counter = 1;
        while (filled < length) {
            final byte[] input = new byte[block.length + info.length + 1];
            System.arraycopy(block, 0, input, 0, block.length);
            System.arraycopy(info, 0, input, block.length, info.length);
            input[input.length - 1] = counter;
            block = hmacSha256(prk, input);
            final int take = Math.min(block.length, length - filled);
            System.arraycopy(block, 0, out, filled, take);
            filled += take;
            counter++;
        }
        return out;
    }

    // ---- the seam: curves and AEAD come from the installed backend --------------------------

    /** Lazily the JCA backend, so a JVM caller needs no setup; never reached on Android. */
    private static final class Default {
        private static final CryptoBackend BACKEND = JcaCryptoBackend.INSTANCE;
    }

    /**
     * Installs the backend every curve and AEAD operation uses from now on.
     * The Android app installs its libsodium backend at process start,
     * before any protocol code runs; a JVM caller installs nothing and gets
     * the JCA backend.
     *
     * @param backend the backend
     */
    public static void install(final CryptoBackend backend) {
        if (backend == null) {
            throw new IllegalArgumentException("a backend is required");
        }
        installed = backend;
    }

    /** @return the backend in use */
    public static CryptoBackend backend() {
        final CryptoBackend b = installed;
        return b != null ? b : Default.BACKEND;
    }

    /**
     * Seals with ChaCha20-Poly1305 (IETF).
     *
     * @param key 32-byte key
     * @param nonce 12-byte nonce
     * @param plaintext data to encrypt
     * @param aad associated data
     * @return ciphertext with appended tag
     * @throws GeneralSecurityException on any cipher failure
     */
    public static byte[] aeadSeal(final byte[] key, final byte[] nonce, final byte[] plaintext, final byte[] aad)
            throws GeneralSecurityException {
        return backend().aeadSeal(key, nonce, plaintext, aad);
    }

    /**
     * Opens a ChaCha20-Poly1305 (IETF) ciphertext.
     *
     * @param key 32-byte key
     * @param nonce 12-byte nonce
     * @param ciphertext ciphertext with appended tag
     * @param aad associated data
     * @return plaintext
     * @throws GeneralSecurityException when authentication fails for any reason
     */
    public static byte[] aeadOpen(final byte[] key, final byte[] nonce, final byte[] ciphertext, final byte[] aad)
            throws GeneralSecurityException {
        return backend().aeadOpen(key, nonce, ciphertext, aad);
    }

    /**
     * Performs an X25519 agreement over raw keys.
     *
     * @param privateScalar this side's 32-byte scalar
     * @param peerPublic the peer's 32-byte public key
     * @return the 32-byte shared secret
     * @throws GeneralSecurityException on a malformed key or an all-zero result
     */
    public static byte[] x25519(final byte[] privateScalar, final byte[] peerPublic) throws GeneralSecurityException {
        return backend().x25519(privateScalar, peerPublic);
    }

    /**
     * Derives the raw public key for an X25519 private scalar.
     *
     * @param rawPrivate the scalar
     * @return the raw 32-byte public key
     * @throws GeneralSecurityException on failure
     */
    public static byte[] x25519PublicFromPrivate(final byte[] rawPrivate) throws GeneralSecurityException {
        return backend().x25519Base(rawPrivate);
    }

    /**
     * Verifies an Ed25519 signature, failing closed.
     *
     * @param rawPublic raw 32-byte public key
     * @param message signed message
     * @param signature 64-byte signature
     * @return true when the signature verifies
     */
    public static boolean ed25519Verify(final byte[] rawPublic, final byte[] message, final byte[] signature) {
        return backend().ed25519Verify(rawPublic, message, signature);
    }

    /**
     * Concatenates byte arrays in argument order.
     *
     * @param a the arrays to join, in order
     * @return a new array holding every input's bytes end to end
     */
    public static byte[] concat(final byte[]... a) {
        int n = 0;
        for (final byte[] part : a) {
            n += part.length;
        }
        final byte[] out = new byte[n];
        int at = 0;
        for (final byte[] part : a) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }

    /**
     * @param b input
     * @return a reversed copy
     */
    public static byte[] reversed(final byte[] b) {
        final byte[] out = new byte[b.length];
        for (int i = 0; i < b.length; i++) {
            out[i] = b[b.length - 1 - i];
        }
        return out;
    }

    /**
     * @param b big-endian value
     * @param n number of bytes
     * @return the last n bytes
     */
    public static byte[] tail(final byte[] b, final int n) {
        return Arrays.copyOfRange(b, b.length - n, b.length);
    }

    /**
     * @param b input
     * @param n prefix length
     * @return the first n bytes
     */
    public static byte[] prefix(final byte[] b, final int n) {
        return Arrays.copyOfRange(b, 0, n);
    }
}
