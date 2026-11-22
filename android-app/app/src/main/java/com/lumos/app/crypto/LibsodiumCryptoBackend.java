/*
 * Lumos - android-app/app / The libsodium backend for the protocol's curves and AEAD - the one that ships.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-CRY-001, TRS-CRY-003, SRS-RMT-007
 * ADRs: ADR-001
 * Threading: any
 * Notes: Android's JCA has XDH and Ed25519 only from API 33 and
 * ChaCha20-Poly1305 only from API 28; the app runs from API 26. ADR-001
 * names libsodium, which the app already ships (Lazysodium) for the identity
 * and the recovery record, so X3DH, the Double Ratchet, remote sessions and
 * HELLO verification all run on it on every supported Android level. It is
 * installed into LumosCrypto by LumosApplication before any protocol code
 * runs, and held to the same vector families as the JCA backend on the CI
 * emulator, which runs API 30 - a level where the JCA path cannot run at all.
 *
 * Semantics match the CryptoBackend contract exactly: crypto_scalarmult
 * returns -1 for an all-zero result (a low-order peer key), which is thrown;
 * verification is false for any malformed input, never an exception; an
 * AEAD open that fails is one undistinguished exception. libsodium's
 * Ed25519 verification is the stricter of the two (it also refuses
 * small-order and non-canonical public keys); that can only refuse a forged
 * key, never accept one.
 */

package com.lumos.app.crypto;

import com.goterl.lazysodium.LazySodiumAndroid;
import com.goterl.lazysodium.SodiumAndroid;
import com.lumos.protocol.wire.CryptoBackend;
import java.security.GeneralSecurityException;

/** X25519, Ed25519 verification and ChaCha20-Poly1305 through libsodium. */
public final class LibsodiumCryptoBackend implements CryptoBackend {

    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BYTES = 16;
    private static final int SIGNATURE_BYTES = 64;

    private final LazySodiumAndroid sodium;

    private LibsodiumCryptoBackend(final LazySodiumAndroid sodium) {
        this.sodium = sodium;
    }

    /**
     * Wraps a loaded binding.
     *
     * @param sodium the binding; one per process
     * @return the backend
     */
    public static LibsodiumCryptoBackend over(final LazySodiumAndroid sodium) {
        return new LibsodiumCryptoBackend(sodium);
    }

    /**
     * Loads libsodium and wraps it.
     *
     * @return the backend
     */
    public static LibsodiumCryptoBackend load() {
        return over(new LazySodiumAndroid(new SodiumAndroid()));
    }

    private static void requireLength(final byte[] b, final int n, final String what)
            throws GeneralSecurityException {
        if (b == null || b.length != n) {
            throw new GeneralSecurityException(what + " has the wrong length");
        }
    }

    @Override
    public byte[] x25519(final byte[] privateScalar, final byte[] peerPublic) throws GeneralSecurityException {
        requireLength(privateScalar, KEY_BYTES, "X25519 scalar");
        requireLength(peerPublic, KEY_BYTES, "X25519 public key");
        final byte[] shared = new byte[KEY_BYTES];
        if (!sodium.cryptoScalarMult(shared, privateScalar, peerPublic)) {
            throw new GeneralSecurityException("X25519 agreement refused");
        }
        return shared;
    }

    @Override
    public byte[] x25519Base(final byte[] privateScalar) throws GeneralSecurityException {
        requireLength(privateScalar, KEY_BYTES, "X25519 scalar");
        final byte[] pub = new byte[KEY_BYTES];
        if (!sodium.cryptoScalarMultBase(pub, privateScalar)) {
            throw new GeneralSecurityException("X25519 public key derivation refused");
        }
        return pub;
    }

    @Override
    public boolean ed25519Verify(final byte[] publicKey, final byte[] message, final byte[] signature) {
        if (publicKey == null || message == null || signature == null || publicKey.length != KEY_BYTES
                || signature.length != SIGNATURE_BYTES) {
            return false;
        }
        return sodium.cryptoSignVerifyDetached(signature, message, message.length, publicKey);
    }

    @Override
    public byte[] aeadSeal(final byte[] key, final byte[] nonce, final byte[] plaintext, final byte[] aad)
            throws GeneralSecurityException {
        requireLength(key, KEY_BYTES, "AEAD key");
        requireLength(nonce, NONCE_BYTES, "AEAD nonce");
        final byte[] out = new byte[plaintext.length + TAG_BYTES];
        final long[] outLen = new long[1];
        if (!sodium.cryptoAeadChaCha20Poly1305IetfEncrypt(out, outLen, plaintext, plaintext.length, aad, aad.length,
                null, nonce, key) || outLen[0] != out.length) {
            throw new GeneralSecurityException("AEAD seal failed");
        }
        return out;
    }

    @Override
    public byte[] aeadOpen(final byte[] key, final byte[] nonce, final byte[] ciphertext, final byte[] aad)
            throws GeneralSecurityException {
        requireLength(key, KEY_BYTES, "AEAD key");
        requireLength(nonce, NONCE_BYTES, "AEAD nonce");
        if (ciphertext.length < TAG_BYTES) {
            throw new GeneralSecurityException("AEAD open failed");
        }
        final byte[] out = new byte[ciphertext.length - TAG_BYTES];
        final long[] outLen = new long[1];
        if (!sodium.cryptoAeadChaCha20Poly1305IetfDecrypt(out, outLen, null, ciphertext, ciphertext.length, aad,
                aad.length, nonce, key) || outLen[0] != out.length) {
            throw new GeneralSecurityException("AEAD open failed");
        }
        return out;
    }
}
