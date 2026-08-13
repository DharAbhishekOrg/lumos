/*
 * Lumos - android-app/app / Android Keystore KEK with StrongBox capability probe.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-IDN-001, SRS-NFR-SEC-003, TRS-IOS-007
 * ADRs: ADR-003, ADR-010
 * Threading: serial-executor
 * Notes: The thin hardware adapter. Every piece of logic that can live off the
 * device does - the wrap framing is KekWrapFormat, the destroy-then-content
 * ordering is KeysFirstWipe, and both are CI-tested against SoftwareKekStore.
 * What remains here is only what Android Keystore itself does: key generation
 * inside the keystore, the StrongBox-then-TEE probe, and GCM with a
 * keystore-issued IV. None of it can execute on a JVM or a CI VM, so none of it
 * is claimed as tested there: hardware-backed behaviour is owner-verified on
 * physical devices and never simulated (WP-02 kickoff, owner-executed row).
 *
 * ADR-010 is load-bearing in generateKek: the KEK is created WITHOUT
 * setUserAuthenticationRequired, because background decrypt (WP-03 BLE, WP-05
 * push pull) must run with no person present. setUnlockedDeviceRequired(true)
 * is still set where the API exists - at-rest protection on a locked, rebooted
 * device without breaking unlocked background work.
 */

package com.lumos.app.keystore;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.StrongBoxUnavailableException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** The hardware KEK, held by Android Keystore and never extractable. */
public final class AndroidKekStore implements KekStore {

    /** The keystore provider name Android reserves for hardware-backed keys. */
    private static final String PROVIDER = "AndroidKeyStore";

    /** KEK size in bits. */
    private static final int KEY_BITS = 256;

    private final String alias;
    private final KeyProtection protection;
    private boolean destroyed;

    private AndroidKekStore(final String alias, final KeyProtection protection) {
        this.alias = alias;
        this.protection = protection;
    }

    /**
     * Creates the KEK inside Android Keystore, probing StrongBox first.
     *
     * <p>The probe is explicit and its result is the returned store's recorded
     * tier: StrongBox is attempted on API 28+, and
     * {@link StrongBoxUnavailableException} - the documented signal that the
     * device has no secure element - drops to the TEE keystore. The caller
     * persists the achieved tier (threat model LT-05); this class does not
     * assume it on a later run.
     *
     * @param alias the keystore alias for the KEK
     * @return a store whose {@link #protection()} reports the probed tier
     * @throws GeneralSecurityException if the keystore refuses both tiers
     */
    public static AndroidKekStore create(final String alias) throws GeneralSecurityException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                generateKek(alias, true);
                return new AndroidKekStore(alias, KeyProtection.STRONGBOX);
            } catch (StrongBoxUnavailableException e) {
                // The documented "no secure element on this device" signal; the
                // TEE keystore below is the correct next tier, not a failure.
                generateKek(alias, false);
                return new AndroidKekStore(alias, KeyProtection.TEE);
            }
        }
        generateKek(alias, false);
        return new AndroidKekStore(alias, KeyProtection.TEE);
    }

    /**
     * Reattaches to a KEK created earlier.
     *
     * @param alias the keystore alias
     * @param recordedProtection the tier persisted when the KEK was created
     * @return the store
     */
    public static AndroidKekStore open(final String alias, final KeyProtection recordedProtection) {
        return new AndroidKekStore(alias, recordedProtection);
    }

    /**
     * Generates the AES-GCM KEK inside the keystore.
     *
     * @param alias the keystore alias
     * @param strongBox whether to demand the secure element
     * @throws GeneralSecurityException on keystore refusal
     */
    private static void generateKek(final String alias, final boolean strongBox) throws GeneralSecurityException {
        final KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS);
        // ADR-010: deliberately NO setUserAuthenticationRequired - key-level
        // auth gating breaks background decrypt, and the user-facing protection
        // is App Lock plus action-level device auth, layered above the keys.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setUnlockedDeviceRequired(true);
            if (strongBox) {
                builder.setIsStrongBoxBacked(true);
            }
        }
        final KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER);
        generator.init(builder.build());
        generator.generateKey();
    }

    @Override
    public KeyProtection protection() {
        return protection;
    }

    @Override
    public byte[] wrap(final byte[] plaintext, final byte[] aad) throws GeneralSecurityException {
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        // The keystore issues the IV itself: passing one is rejected by design,
        // which is the keystore's own defence of nonce uniqueness (TRS-CRY-011).
        cipher.init(Cipher.ENCRYPT_MODE, kek());
        cipher.updateAAD(aad);
        final byte[] ciphertext = cipher.doFinal(plaintext);
        return KekWrapFormat.encode(cipher.getIV(), ciphertext);
    }

    @Override
    public byte[] unwrap(final byte[] wrapped, final byte[] aad) throws GeneralSecurityException {
        final KekWrapFormat.Parts parts = KekWrapFormat.decode(wrapped);
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, kek(), new GCMParameterSpec(KekWrapFormat.GCM_TAG_BITS, parts.iv()));
        cipher.updateAAD(aad);
        return cipher.doFinal(parts.ciphertext());
    }

    @Override
    public void destroy() throws GeneralSecurityException {
        // Keystore deletion is the real crypto-erasure: the key material lives
        // in hardware and deleting the entry is the only destruction there is.
        final KeyStore keyStore = loadKeyStore();
        try {
            keyStore.deleteEntry(alias);
        } catch (java.security.KeyStoreException e) {
            throw new GeneralSecurityException("KEK deletion failed", e);
        }
        destroyed = true;
    }

    @Override
    public boolean isDestroyed() {
        return destroyed;
    }

    /**
     * Fetches the KEK handle from the keystore.
     *
     * @return the keystore-held key handle; the material itself never leaves
     * @throws GeneralSecurityException if the entry is absent or destroyed
     */
    private SecretKey kek() throws GeneralSecurityException {
        if (destroyed) {
            throw new GeneralSecurityException("KEK destroyed");
        }
        final KeyStore.Entry entry = loadKeyStore().getEntry(alias, null);
        if (!(entry instanceof KeyStore.SecretKeyEntry)) {
            throw new GeneralSecurityException("KEK entry missing");
        }
        return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
    }

    /**
     * Loads the platform keystore.
     *
     * @return the loaded AndroidKeyStore instance
     * @throws GeneralSecurityException if the provider is unavailable
     */
    private static KeyStore loadKeyStore() throws GeneralSecurityException {
        try {
            final KeyStore keyStore = KeyStore.getInstance(PROVIDER);
            keyStore.load(null);
            return keyStore;
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException("AndroidKeyStore load failed", e);
        }
    }
}
