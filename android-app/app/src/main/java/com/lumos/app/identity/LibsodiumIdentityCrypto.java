/*
 * Lumos - android-app/app / The libsodium identity backend - the one that ships.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-IDN-001, SRS-NFR-SEC-002, TRS-CRY-001
 * ADRs: ADR-001, ADR-008
 * Threading: any
 * Notes: Closes closeout finding 5.1. Android's Keystore and JCA cannot
 * generate an Ed25519 key at ANY API level, and on API 26-32 cannot sign or
 * verify one either; ADR-001 names libsodium as the answer, and after WP-02
 * S4b it is already loaded in-process for the RecoveryRecord. This backend is
 * therefore the production identity path on every Android device, not a
 * fallback for old ones - the ADR-008 ratification note of 2026-08-19 records
 * that as architectural fact.
 *
 * Key material comes from libsodium's own CSPRNG (randombytes under the
 * keypair calls - SRS-NFR-SEC-002). Shapes at the seam are the canonical ones:
 * libsodium's 64-byte Ed25519 secret is seed||public, so the PKCS#8 crossing
 * the boundary carries only the 32-byte seed under the RFC 8410 prefix, and
 * signing regenerates the expanded key from the seed via cryptoSignSeedKeypair
 * - deterministically, which is what lets the identity_binding vector family
 * hold this backend and the JCA one to byte-identical signatures.
 *
 * SodiumAndroid loads a native library in its constructor, so an instance of
 * this class is not free. Construct one and pass it around (constructor
 * injection, TRS Sec. 24.2) - the same rule as LibsodiumRecoveryCrypto, and
 * the two can share a LazySodiumAndroid.
 */

package com.lumos.app.identity;

import com.goterl.lazysodium.LazySodiumAndroid;
import com.goterl.lazysodium.SodiumAndroid;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/** Ed25519/X25519 generation, signing and verification through libsodium. */
public final class LibsodiumIdentityCrypto implements IdentityCrypto {

    /** Raw key length for seeds, scalars and publics. */
    private static final int RAW_KEY_BYTES = 32;

    /** libsodium's expanded Ed25519 secret key: seed || public. */
    private static final int ED25519_SECRET_BYTES = 64;

    /** Detached Ed25519 signature length. */
    private static final int SIGNATURE_BYTES = 64;

    private final LazySodiumAndroid sodium;

    /**
     * @param sodium the loaded binding; construct one per process
     */
    public LibsodiumIdentityCrypto(final LazySodiumAndroid sodium) {
        this.sodium = sodium;
    }

    /**
     * Loads libsodium and wraps it.
     *
     * @return an adapter over a freshly loaded binding
     */
    public static LibsodiumIdentityCrypto load() {
        return new LibsodiumIdentityCrypto(new LazySodiumAndroid(new SodiumAndroid()));
    }

    @Override
    public KeyMaterial generateEd25519() throws GeneralSecurityException {
        final byte[] publicKey = new byte[RAW_KEY_BYTES];
        final byte[] secret = new byte[ED25519_SECRET_BYTES];
        try {
            if (!sodium.cryptoSignKeypair(publicKey, secret)) {
                throw new GeneralSecurityException("Ed25519 generation failed");
            }
            final byte[] seed = Arrays.copyOfRange(secret, 0, RAW_KEY_BYTES);
            try {
                return new KeyMaterial(RawKeys.ed25519PrivatePkcs8(seed), publicKey);
            } finally {
                Arrays.fill(seed, (byte) 0);
            }
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    @Override
    public KeyMaterial generateX25519() throws GeneralSecurityException {
        final byte[] publicKey = new byte[RAW_KEY_BYTES];
        final byte[] scalar = new byte[RAW_KEY_BYTES];
        try {
            if (!sodium.cryptoBoxKeypair(publicKey, scalar)) {
                throw new GeneralSecurityException("X25519 generation failed");
            }
            return new KeyMaterial(RawKeys.x25519PrivatePkcs8(scalar), publicKey);
        } finally {
            Arrays.fill(scalar, (byte) 0);
        }
    }

    @Override
    public byte[] signBinding(final byte[] ed25519Pkcs8, final byte[] message)
            throws GeneralSecurityException {
        final byte[] seed = RawKeys.rawPrivateFromPkcs8(ed25519Pkcs8, RawKeys.ED25519_PKCS8_PREFIX);
        final byte[] publicKey = new byte[RAW_KEY_BYTES];
        final byte[] secret = new byte[ED25519_SECRET_BYTES];
        try {
            if (!sodium.cryptoSignSeedKeypair(publicKey, secret, seed)) {
                throw new GeneralSecurityException("Ed25519 seed expansion failed");
            }
            final byte[] signature = new byte[SIGNATURE_BYTES];
            if (!sodium.cryptoSignDetached(signature, message, message.length, secret)) {
                throw new GeneralSecurityException("Ed25519 signing failed");
            }
            return signature;
        } finally {
            Arrays.fill(seed, (byte) 0);
            Arrays.fill(secret, (byte) 0);
        }
    }

    @Override
    public boolean verifyBinding(final byte[] ed25519Public, final byte[] message,
            final byte[] signature) {
        if (ed25519Public == null || signature == null || message == null
                || ed25519Public.length != RAW_KEY_BYTES
                || signature.length != SIGNATURE_BYTES) {
            return false;
        }
        return sodium.cryptoSignVerifyDetached(signature, message, message.length, ed25519Public);
    }
}
