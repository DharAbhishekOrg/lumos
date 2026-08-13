/*
 * Lumos - protocol-conformance / JCA primitives for the conformance runner.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-CRY-001, TRS-CRY-003, TRS-CRY-011, TRS-TST-001
 * ADRs: ADR-001
 * Threading: any
 * Notes: Everything here comes from the JDK 17 JCA - X25519 (XDH), Ed25519,
 * ChaCha20-Poly1305, SHA-256 and HMAC. HKDF is written out over Mac because
 * JDK 17 predates javax.crypto.KDF; it is RFC 5869 extract-then-expand and
 * nothing more. The raw<->key conversions matter: X25519 public keys are
 * LITTLE-endian u-coordinates while BigInteger is big-endian, and Ed25519 raw
 * encoding carries the x-sign in the top bit of the last byte. Getting either
 * backwards produces a key that is valid but wrong, which is exactly the class
 * of cross-language bug these vectors exist to catch.
 */
package com.lumos.protocol.conformance;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPrivateKeySpec;
import java.security.spec.XECPublicKeySpec;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** JCA-backed primitives matching ADR-001. */
public final class LumosCrypto {

    private LumosCrypto() {
        throw new AssertionError("no instances");
    }

    /** ChaCha20-Poly1305 key size in bytes. */
    public static final int AEAD_KEY_SIZE = 32;

    /** ChaCha20-Poly1305 (IETF) nonce size in bytes. */
    public static final int AEAD_NONCE_SIZE = 12;

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
        final Cipher c = Cipher.getInstance("ChaCha20-Poly1305");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce));
        c.updateAAD(aad);
        return c.doFinal(plaintext);
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
        final Cipher c = Cipher.getInstance("ChaCha20-Poly1305");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce));
        c.updateAAD(aad);
        return c.doFinal(ciphertext);
    }

    /**
     * Converts a raw 32-byte X25519 public key into a JCA key.
     *
     * @param raw little-endian u-coordinate
     * @return the public key
     * @throws GeneralSecurityException on malformed input
     */
    public static PublicKey x25519Public(final byte[] raw) throws GeneralSecurityException {
        if (raw.length != 32) {
            throw new GeneralSecurityException("X25519 public key must be 32 bytes");
        }
        // RFC 7748 masks the top bit of the final byte before use.
        final byte[] le = raw.clone();
        le[31] &= (byte) 0x7F;
        final byte[] be = reversed(le);
        final XECPublicKeySpec spec = new XECPublicKeySpec(NamedParameterSpec.X25519, new BigInteger(1, be));
        return KeyFactory.getInstance("XDH").generatePublic(spec);
    }

    /**
     * Converts a raw 32-byte X25519 scalar into a JCA private key.
     *
     * @param raw the scalar as stored in the vectors
     * @return the private key
     * @throws GeneralSecurityException on malformed input
     */
    public static PrivateKey x25519Private(final byte[] raw) throws GeneralSecurityException {
        if (raw.length != 32) {
            throw new GeneralSecurityException("X25519 private key must be 32 bytes");
        }
        return KeyFactory.getInstance("XDH").generatePrivate(
                new XECPrivateKeySpec(NamedParameterSpec.X25519, raw.clone()));
    }

    /**
     * Performs an X25519 agreement.
     *
     * @param priv local private key
     * @param pub remote public key
     * @return the 32-byte shared secret
     * @throws GeneralSecurityException on failure
     */
    public static byte[] x25519(final PrivateKey priv, final PublicKey pub) throws GeneralSecurityException {
        final KeyAgreement ka = KeyAgreement.getInstance("XDH");
        ka.init(priv);
        ka.doPhase(pub, true);
        return ka.generateSecret();
    }

    /**
     * Derives the raw public key for an X25519 private scalar, by agreeing with
     * the curve base point.
     *
     * @param rawPrivate the scalar
     * @return the raw 32-byte public key
     * @throws GeneralSecurityException on failure
     */
    public static byte[] x25519PublicFromPrivate(final byte[] rawPrivate) throws GeneralSecurityException {
        final byte[] basePoint = new byte[32];
        basePoint[0] = 9;
        return x25519(x25519Private(rawPrivate), x25519Public(basePoint));
    }

    /**
     * Converts a raw 32-byte Ed25519 public key into a JCA key.
     *
     * @param raw the RFC 8032 encoding
     * @return the public key
     * @throws GeneralSecurityException on malformed input
     */
    public static PublicKey ed25519Public(final byte[] raw) throws GeneralSecurityException {
        if (raw.length != 32) {
            throw new GeneralSecurityException("Ed25519 public key must be 32 bytes");
        }
        // RFC 8032: little-endian y, with the x-coordinate sign in the top bit.
        final byte[] le = raw.clone();
        final boolean xOdd = (le[31] & 0x80) != 0;
        le[31] &= (byte) 0x7F;
        final BigInteger y = new BigInteger(1, reversed(le));
        return KeyFactory.getInstance("Ed25519")
                .generatePublic(new EdECPublicKeySpec(NamedParameterSpec.ED25519, new EdECPoint(xOdd, y)));
    }

    /**
     * Verifies an Ed25519 signature.
     *
     * @param rawPublic raw 32-byte public key
     * @param message signed message
     * @param signature 64-byte signature
     * @return true when the signature verifies
     */
    public static boolean ed25519Verify(final byte[] rawPublic, final byte[] message, final byte[] signature) {
        try {
            final Signature s = Signature.getInstance("Ed25519");
            s.initVerify(ed25519Public(rawPublic));
            s.update(message);
            return s.verify(signature);
        } catch (GeneralSecurityException e) {
            // A malformed key or signature is a verification failure, not a
            // distinguishable error: surfacing which would be an oracle.
            return false;
        }
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
