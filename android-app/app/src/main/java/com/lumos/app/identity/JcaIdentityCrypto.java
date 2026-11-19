/*
 * Lumos - android-app/app / The JCA identity backend, for runtimes that have one.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-IDN-001, SRS-NFR-SEC-002, TRS-CRY-001
 * ADRs: ADR-001, ADR-008
 * Threading: any
 * Notes: The code that used to live inline in IdentityKeyGenerator, behind the
 * seam it should always have been behind. It runs where the JCA has Ed25519
 * and XDH: the JVM (every unit test, every CI job) and nothing the app ships
 * to - Android has signing from API 33 and key GENERATION never (closeout
 * finding 5.1). Production Android wiring uses LibsodiumIdentityCrypto;
 * constructing this backend there fails on first use with the JCA's own
 * NoSuchAlgorithmException, which is the loud failure the seam wants.
 *
 * Key material comes from the platform CSPRNG via the JCA generators
 * (SRS-NFR-SEC-002). Stateless and immutable: a shared instance is a
 * convenience, not a singleton with state (TRS Sec. 24.2).
 */

package com.lumos.app.identity;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;

/** Ed25519/X25519 generation, signing and verification through the JCA. */
public final class JcaIdentityCrypto implements IdentityCrypto {

    /** Raw public-key length for both Ed25519 and X25519. */
    private static final int RAW_KEY_BYTES = 32;

    @Override
    public KeyMaterial generateEd25519() throws GeneralSecurityException {
        return generate("Ed25519");
    }

    @Override
    public KeyMaterial generateX25519() throws GeneralSecurityException {
        return generate("XDH");
    }

    @Override
    public byte[] signBinding(final byte[] ed25519Pkcs8, final byte[] message)
            throws GeneralSecurityException {
        final Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(KeyFactory.getInstance("Ed25519")
                .generatePrivate(new PKCS8EncodedKeySpec(ed25519Pkcs8)));
        signer.update(message);
        return signer.sign();
    }

    @Override
    public boolean verifyBinding(final byte[] ed25519Public, final byte[] message,
            final byte[] signature) {
        try {
            final Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(RawKeys.ed25519Public(ed25519Public));
            verifier.update(message);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /**
     * Generates one pair and converts it to the seam's canonical shapes.
     *
     * @param algorithm the JCA algorithm name
     * @return PKCS#8 private plus raw public
     * @throws GeneralSecurityException on generation failure
     */
    private static KeyMaterial generate(final String algorithm) throws GeneralSecurityException {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
        generator.initialize(255, new SecureRandom());
        final KeyPair pair = generator.generateKeyPair();
        return new KeyMaterial(pair.getPrivate().getEncoded(), rawPublic(pair));
    }

    /**
     * Extracts the raw 32-byte public key from a generated pair.
     *
     * <p>For both Ed25519 and X25519 the X.509 SubjectPublicKeyInfo encoding is
     * a fixed DER prefix followed by the raw key, so the raw form is the final
     * 32 bytes. The length check makes a malformed encoding loud rather than
     * silently truncated.
     *
     * @param pair the generated pair
     * @return the raw public key
     * @throws GeneralSecurityException on an unexpectedly short encoding
     */
    private static byte[] rawPublic(final KeyPair pair) throws GeneralSecurityException {
        final byte[] spki = pair.getPublic().getEncoded();
        if (spki == null || spki.length < RAW_KEY_BYTES + 1) {
            throw new GeneralSecurityException("unexpected public-key encoding");
        }
        return Arrays.copyOfRange(spki, spki.length - RAW_KEY_BYTES, spki.length);
    }
}
