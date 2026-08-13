/*
 * Lumos - android-app/app / The narrow interface to the key-encryption key.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-NFR-SEC-003, TRS-ARCH-005, SRS-IDN-001
 * ADRs: ADR-003, ADR-010
 * Threading: serial-executor
 * Notes: TRS-ARCH-005 requires security-sensitive operations behind narrow
 * interfaces that never expose raw private keys. This interface IS that
 * boundary for the KEK: it can wrap, unwrap and destroy, and nothing on it
 * returns key material of any kind. KekStoreContractTest pins the method set by
 * reflection, so a convenience accessor cannot be added without failing a test
 * that names this requirement. Implementations are not thread-safe; the
 * persistence layer serialises access on its executor (TRS Sec. 24.2).
 */

package com.lumos.app.keystore;

import java.security.GeneralSecurityException;

/**
 * Wraps and unwraps data keys under the device's key-encryption key.
 *
 * <p>The KEK itself never crosses this interface in either direction. Per
 * ADR-010 the KEK carries no key-level authentication gating - background
 * decrypt requires it to be usable without a person present - and the
 * user-facing protections (App Lock, action-level device auth) sit above this
 * boundary, not inside it.
 */
public interface KekStore {

    /**
     * The protection tier the KEK actually obtained at creation.
     *
     * @return the recorded tier; never an assumption (threat model LT-05)
     */
    KeyProtection protection();

    /**
     * Encrypts a data key under the KEK.
     *
     * @param plaintext the data key to wrap; the caller zeroes its copy
     * @param aad domain-separation context bound into the authentication tag
     * @return a self-describing wrapped blob (see {@link KekWrapFormat})
     * @throws GeneralSecurityException if the KEK is destroyed or the cipher fails
     */
    byte[] wrap(byte[] plaintext, byte[] aad) throws GeneralSecurityException;

    /**
     * Decrypts a wrapped data key.
     *
     * @param wrapped a blob produced by {@link #wrap}
     * @param aad the same context the blob was wrapped under
     * @return the plaintext data key; the caller zeroes it after use
     * @throws GeneralSecurityException on tampering, a wrong AAD, an unknown
     *     format version, or a destroyed KEK - indistinguishably, so the failure
     *     is not an oracle (TRS-CRY-013)
     */
    byte[] unwrap(byte[] wrapped, byte[] aad) throws GeneralSecurityException;

    /**
     * Destroys the KEK irreversibly.
     *
     * <p>This is the first stage of the keys-first wipe order (owner ruling
     * 2026-08-14; threat model §0.2): once the KEK is gone, every blob it
     * wrapped is ciphertext without a key, whatever happens to the wipe after
     * this point.
     *
     * @throws GeneralSecurityException if the underlying store fails to delete
     */
    void destroy() throws GeneralSecurityException;

    /**
     * Reports whether {@link #destroy} has completed.
     *
     * @return true once the KEK no longer exists
     */
    boolean isDestroyed();
}
