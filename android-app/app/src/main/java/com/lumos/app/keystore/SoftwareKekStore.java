/*
 * Lumos - android-app/app / Software KEK: the probed fallback and the CI tier.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-NFR-SEC-002, SRS-NFR-SEC-003, SRS-DAT-002
 * ADRs: ADR-003, ADR-010
 * Threading: serial-executor
 * Notes: Two jobs, one honest label. On a device whose probe finds no hardware
 * keystore this is the fallback tier, and it reports SOFTWARE_FALLBACK so
 * nothing upstream can mistake it for hardware (threat model LT-05). In CI and
 * JVM tests it is the only tier that exists, which is exactly why the wrap
 * format and the destroy semantics live here and in KekWrapFormat rather than
 * in the Android adapter: everything testable without hardware is tested
 * without hardware, and the hardware adapter stays too thin to hide logic.
 */

package com.lumos.app.keystore;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** An in-process AES-256-GCM KEK with no hardware binding. */
public final class SoftwareKekStore implements KekStore {

    /** KEK size in bits. */
    private static final int KEY_BITS = 256;

    private final SecureRandom random;
    private SecretKey kek;

    private SoftwareKekStore(final SecretKey kek, final SecureRandom random) {
        this.kek = kek;
        this.random = random;
    }

    /**
     * Generates a fresh software KEK from the platform CSPRNG (SRS-NFR-SEC-002).
     *
     * @return the store
     * @throws GeneralSecurityException if AES is unavailable
     */
    public static SoftwareKekStore create() throws GeneralSecurityException {
        final SecureRandom random = new SecureRandom();
        final KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(KEY_BITS, random);
        return new SoftwareKekStore(generator.generateKey(), random);
    }

    @Override
    public KeyProtection protection() {
        return KeyProtection.SOFTWARE_FALLBACK;
    }

    @Override
    public byte[] wrap(final byte[] plaintext, final byte[] aad) throws GeneralSecurityException {
        final SecretKey key = liveKek();
        final byte[] iv = new byte[KekWrapFormat.GCM_IV_BYTES];
        random.nextBytes(iv);
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(KekWrapFormat.GCM_TAG_BITS, iv));
        cipher.updateAAD(aad);
        return KekWrapFormat.encode(iv, cipher.doFinal(plaintext));
    }

    @Override
    public byte[] unwrap(final byte[] wrapped, final byte[] aad) throws GeneralSecurityException {
        final SecretKey key = liveKek();
        final KekWrapFormat.Parts parts = KekWrapFormat.decode(wrapped);
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(KekWrapFormat.GCM_TAG_BITS, parts.iv()));
        cipher.updateAAD(aad);
        return cipher.doFinal(parts.ciphertext());
    }

    @Override
    public void destroy() {
        // SecretKeySpec offers no reliable zeroisation on the JVM; dropping the
        // only reference is the strongest destruction available at this tier,
        // and is itself part of why this tier reports SOFTWARE_FALLBACK.
        kek = null;
    }

    @Override
    public boolean isDestroyed() {
        return kek == null;
    }

    /**
     * Returns the KEK or refuses because it was destroyed.
     *
     * @return the live KEK
     * @throws GeneralSecurityException after {@link #destroy}
     */
    private SecretKey liveKek() throws GeneralSecurityException {
        final SecretKey key = kek;
        if (key == null) {
            throw new GeneralSecurityException("KEK destroyed");
        }
        return key;
    }
}
