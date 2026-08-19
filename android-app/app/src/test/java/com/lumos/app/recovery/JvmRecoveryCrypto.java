/*
 * Lumos - android-app/app / A JDK-only RecoveryCrypto, so the flow is testable on a host.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-007, SRS-DAT-004, TRS-TST-001
 * ADRs: ADR-001, ADR-003
 * Threading: any
 * Notes: READ THIS BEFORE TRUSTING A GREEN RUN HERE.
 *
 * This is NOT the shipping cryptography and must never be mistaken for it. The
 * shipping suite is ADR-001's - libsodium Argon2id and ChaCha20-Poly1305,
 * reached through LibsodiumRecoveryCrypto - and it is proven against the
 * cross-language golden vectors in Go, on an emulator and on macOS. None of
 * that runs on this Windows/JVM host, because Lazysodium ships a native .so.
 *
 * What this is: REAL cryptography from the JDK - PBKDF2-HMAC-SHA-256 and
 * AES-256-GCM - standing in for the same two roles. It is not a fake that
 * returns the input, and it is not a hand-written cipher; a wrong key still
 * fails to open, a tampered header still fails authentication, and a wrong
 * nonce still fails. So the S4c logic it exercises - assembling the bundle,
 * encoding it, sealing, opening, decoding, restoring, refusing - is genuinely
 * exercised, and the WP-01 closeout Sec. 5.2 trap of a component agreeing with
 * a fake written by the same author is avoided.
 *
 * What it cannot tell you: whether the RECORD FORMAT is right. That is S4b's
 * question, answered by the vectors, and RecoverySetupInstrumentedTest runs the
 * same flow through the real libsodium binding on a device so the answer covers
 * this slice too.
 *
 * The work factors are deliberately mapped down. Argon2id's memory cost has no
 * PBKDF2 equivalent, and a unit suite that spent 64 MiB and three passes per
 * test would be a suite people skip.
 */

package com.lumos.app.recovery;

import com.lumos.protocol.LumosConstants;
import java.security.GeneralSecurityException;
import java.security.spec.KeySpec;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** A host-runnable stand-in for the two primitives the record format uses. */
final class JvmRecoveryCrypto implements RecoveryCrypto {

    /** GCM tag length in bits, matching ChaCha20-Poly1305's 16-byte tag. */
    private static final int TAG_BITS = 128;

    /** A fixed, small PBKDF2 iteration count: this is a test seam, not a KDF choice. */
    private static final int PBKDF2_ITERATIONS = 1_000;

    @Override
    public byte[] argon2id(final byte[] passphrase, final byte[] salt, final RecoveryParams params)
            throws RecoveryException {
        try {
            // PBKDF2 takes chars. The shipping path takes bytes and must, because
            // swift-sodium's char conversion traps above 0x7F (S4b) - so the
            // bytes are widened here rather than decoded, which keeps a non-ASCII
            // passphrase meaningful without importing that bug into the test.
            final char[] chars = new char[passphrase.length];
            for (int i = 0; i < passphrase.length; i++) {
                chars[i] = (char) (passphrase[i] & 0xFF);
            }
            final KeySpec spec = new PBEKeySpec(chars, salt, PBKDF2_ITERATIONS,
                    LumosConstants.RECOVERY_ARGON2ID_KEY_BYTES * Byte.SIZE);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw RecoveryException.openFailed();
        }
    }

    @Override
    public byte[] seal(final byte[] key, final byte[] nonce, final byte[] plaintext,
            final byte[] aad) throws RecoveryException {
        try {
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw RecoveryException.openFailed();
        }
    }

    @Override
    public byte[] open(final byte[] key, final byte[] nonce, final byte[] ciphertext,
            final byte[] aad) throws RecoveryException {
        try {
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            // Indistinguishable, exactly as the shipping seam must be
            // (TRS-CRY-013): a wrong passphrase and a tampered record are the
            // same failure here too, or the tests would not exercise that.
            throw RecoveryException.openFailed();
        }
    }
}
