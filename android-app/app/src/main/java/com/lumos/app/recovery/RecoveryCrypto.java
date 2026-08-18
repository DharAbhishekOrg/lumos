/*
 * Lumos - android-app/app / The primitives a recovery record needs, as a seam.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-CRY-001, TRS-ARCH-005, TRS-PAT-001
 * ADRs: ADR-001, ADR-003
 * Threading: any
 * Notes: An Adapter (TRS Sec. 24.2) over exactly two primitives, and it exists
 * for a licence reason as much as an architectural one.
 *
 * Lazysodium is MPL-2.0 - the first copyleft licence this project SHIPS - and the
 * owner's approval is conditional on unmodified use. A seam means precisely one
 * implementation file imports it, so "which of our code touches the MPL-2.0
 * dependency" has a one-word answer that a reviewer can check rather than trust.
 *
 * The surface is narrow on purpose: it returns bytes and reports failure, and it
 * knows nothing about the record format. Anything that had to decide something
 * belongs in RecoveryRecord, where it is testable without a device.
 */

package com.lumos.app.recovery;

/** Argon2id and ChaCha20-Poly1305, the two primitives the record format uses. */
public interface RecoveryCrypto {

    /**
     * Derives the record key from a passphrase.
     *
     * @param passphrase the user's passphrase, UTF-8 encoded; may be empty
     * @param salt the record's salt, exactly RECOVERY_ARGON2ID_SALT_BYTES long
     * @param params the work factors, already validated by the caller
     * @return the derived key, RECOVERY_ARGON2ID_KEY_BYTES long
     * @throws RecoveryException if the derivation itself fails
     */
    byte[] argon2id(byte[] passphrase, byte[] salt, RecoveryParams params) throws RecoveryException;

    /**
     * Seals with ChaCha20-Poly1305 (IETF, 12-byte nonce).
     *
     * @param key the record key
     * @param nonce the 12-byte nonce
     * @param plaintext the content; may be empty
     * @param aad the authenticated header
     * @return ciphertext followed by the 16-byte tag
     * @throws RecoveryException if the cipher fails
     */
    byte[] seal(byte[] key, byte[] nonce, byte[] plaintext, byte[] aad) throws RecoveryException;

    /**
     * Opens a ChaCha20-Poly1305 (IETF) ciphertext.
     *
     * @param key the record key
     * @param nonce the 12-byte nonce
     * @param ciphertext ciphertext followed by its tag
     * @param aad the authenticated header
     * @return the plaintext
     * @throws RecoveryException with ERR_CRYPTO_RECOVERY_OPEN_FAILED for every
     *     cause, indistinguishably (TRS-CRY-013)
     */
    byte[] open(byte[] key, byte[] nonce, byte[] ciphertext, byte[] aad) throws RecoveryException;
}
