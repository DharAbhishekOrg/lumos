/*
 * Lumos - android-app/protocol-java / The curve and AEAD primitives, behind one seam.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-CRY-001, TRS-CRY-003
 * ADRs: ADR-001
 * Threading: any
 * Notes: These five are exactly the operations Android's JCA lacks below
 * API 33 (XDH, the XEC and EdEC key specs, Ed25519 verification) or below
 * API 28 (ChaCha20-Poly1305), while the app runs from API 26. Everything else
 * the protocol uses - SHA-256, HMAC-SHA-256, HKDF - is on every level and
 * stays in LumosCrypto. On the JVM the JCA backend serves them; the Android
 * app installs its libsodium backend (ADR-001's own suite) before any
 * protocol code runs. Both are held to the same vector families, so which
 * one runs is invisible on the wire.
 *
 * Every method takes and returns raw bytes - no key objects - so no backend's
 * types leak into the protocol code above it. Failure semantics are part of
 * the contract: an agreement whose result is all zero (a low-order peer key)
 * throws; a signature that does not verify, for any reason including a
 * malformed key, is false and never an exception (TRS-CRY-013); an AEAD open
 * that fails for any reason throws one undistinguished exception.
 */

package com.lumos.protocol.wire;

import java.security.GeneralSecurityException;

/** X25519, Ed25519 verification and ChaCha20-Poly1305 (IETF), as raw bytes. */
public interface CryptoBackend {

    /**
     * X25519 agreement (RFC 7748).
     *
     * @param privateScalar this side's 32-byte scalar (clamped by the function)
     * @param peerPublic the peer's 32-byte u-coordinate (top bit ignored)
     * @return the 32-byte shared secret
     * @throws GeneralSecurityException on a malformed input or an all-zero result
     */
    byte[] x25519(byte[] privateScalar, byte[] peerPublic) throws GeneralSecurityException;

    /**
     * The X25519 public key of a scalar.
     *
     * @param privateScalar the 32-byte scalar
     * @return the 32-byte public key
     * @throws GeneralSecurityException on a malformed input
     */
    byte[] x25519Base(byte[] privateScalar) throws GeneralSecurityException;

    /**
     * Verifies an Ed25519 signature (RFC 8032), failing closed.
     *
     * @param publicKey the raw 32-byte public key
     * @param message the signed bytes
     * @param signature the 64-byte signature
     * @return true only when it verifies
     */
    boolean ed25519Verify(byte[] publicKey, byte[] message, byte[] signature);

    /**
     * Seals with ChaCha20-Poly1305 (IETF, RFC 8439).
     *
     * @param key 32-byte key
     * @param nonce 12-byte nonce
     * @param plaintext the data
     * @param aad associated data
     * @return ciphertext with the 16-byte tag appended
     * @throws GeneralSecurityException on a malformed input
     */
    byte[] aeadSeal(byte[] key, byte[] nonce, byte[] plaintext, byte[] aad) throws GeneralSecurityException;

    /**
     * Opens a ChaCha20-Poly1305 (IETF) ciphertext.
     *
     * @param key 32-byte key
     * @param nonce 12-byte nonce
     * @param ciphertext ciphertext with tag
     * @param aad associated data
     * @return the plaintext
     * @throws GeneralSecurityException when it does not authenticate, for any reason
     */
    byte[] aeadOpen(byte[] key, byte[] nonce, byte[] ciphertext, byte[] aad) throws GeneralSecurityException;
}
