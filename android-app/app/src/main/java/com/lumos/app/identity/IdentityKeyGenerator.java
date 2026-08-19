/*
 * Lumos - android-app/app / Device identity generation per ADR-008.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-IDN-001, SRS-NFR-SEC-002, SRS-ONB-006
 * ADRs: ADR-001, ADR-008, ADR-003
 * Threading: serial-executor
 * Notes: Generates the two-key identity of ADR-008 - an Ed25519 signing pair
 * and a separate X25519 identity-DH pair, the DH public key signed by the
 * Ed25519 identity under X3DH_IDENTITY_BINDING_INFO. The binding message is
 * byte-identical to protocol/crypto/x3dh.SignIdentityDH: UTF-8(info) || raw DH
 * public key. Both privates leave this class only inside one KEK-wrapped
 * envelope; no API returns them, and IdentityKeyGeneratorTest asserts the
 * envelope is unopenable once the KEK is destroyed (crypto-erasure).
 *
 * PROVIDER AVAILABILITY, MEASURED RATHER THAN ASSUMED - and the measurement
 * corrects what this comment used to say. Slice S1 recorded that Ed25519 and
 * XDH "resolve on JDK 17 and on Android API 33+"; slice S4c ran generation on
 * an emulator for the first time and only half of that held:
 *
 *                                            JDK   API 30   API 34
 *   KeyPairGenerator.getInstance("XDH")      yes   no       yes
 *   Signature.getInstance("Ed25519")         yes   no       yes
 *   KeyPairGenerator.getInstance("Ed25519")  yes   no       NO
 *
 * Android gained Ed25519 for signing and verifying at API 33 and has never
 * gained it for key GENERATION. So generate() runs on the JDK - every unit test
 * and every JVM job in CI - and on no Android runtime at all, while
 * verifyBinding() works on API 33+. The boundary is not an API level; it is
 * Android.
 *
 * The ADR-001 answer is libsodium, which is already in the tree as Lazysodium
 * for the RecoveryRecord. The owner ruling of 2026-08-14 parks that wiring for
 * WP-03 device work rather than taking it inside WP-02, and
 * PlatformKeyAlgorithmInstrumentedTest is the tripwire that fails the day the
 * platform makes it unnecessary.
 */

package com.lumos.app.identity;

import com.lumos.app.keystore.KekStore;
import com.lumos.protocol.LumosConstants;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Arrays;

/** Generates and binds the two-key device identity of ADR-008. */
public final class IdentityKeyGenerator {

    /** Raw public-key length for both Ed25519 and X25519. */
    private static final int RAW_KEY_BYTES = 32;

    /**
     * Domain-separation context for the private-key envelope's AAD, so an
     * identity envelope cannot be replayed to the KEK as some other blob kind.
     * Named local constant per TRS-CODE-005; it is a local storage label, not a
     * Sec. 23 wire constant.
     */
    public static final String ENVELOPE_AAD = "lumos/local/identity-envelope/v1";

    private IdentityKeyGenerator() {
        throw new AssertionError("no instances");
    }

    /**
     * Generates a fresh identity and seals both private keys under the KEK.
     *
     * <p>Key material comes from the platform CSPRNG via the JCA generators
     * (SRS-NFR-SEC-002); the private keys' only surviving form is the returned
     * record's wrapped envelope.
     *
     * @param kek the store that wraps the private-key envelope
     * @return the at-rest identity record
     * @throws GeneralSecurityException if generation, signing or wrapping fails
     */
    public static IdentityKeyRecord generate(final KekStore kek) throws GeneralSecurityException {
        final SecureRandom random = new SecureRandom();
        final KeyPairGenerator edGenerator = KeyPairGenerator.getInstance("Ed25519");
        edGenerator.initialize(255, random);
        final KeyPair ed = edGenerator.generateKeyPair();

        final KeyPairGenerator xGenerator = KeyPairGenerator.getInstance("XDH");
        xGenerator.initialize(255, random);
        final KeyPair x = xGenerator.generateKeyPair();

        final byte[] edPublic = rawPublic(ed);
        final byte[] xPublic = rawPublic(x);

        final Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(ed.getPrivate());
        signer.update(bindingMessage(xPublic));
        final byte[] binding = signer.sign();

        final byte[] envelope = PrivateKeyEnvelope.encode(
                ed.getPrivate().getEncoded(), x.getPrivate().getEncoded());
        final byte[] wrapped;
        try {
            wrapped = kek.wrap(envelope, ENVELOPE_AAD.getBytes(StandardCharsets.UTF_8));
        } finally {
            // The plaintext envelope's only long-lived form is the wrapped one.
            Arrays.fill(envelope, (byte) 0);
        }
        return new IdentityKeyRecord(edPublic, xPublic, binding, wrapped, kek.protection());
    }

    /**
     * Verifies the ADR-008 binding signature, failing closed.
     *
     * <p>This is the check that must pass before the identity-DH key is ever
     * used in a DH computation (ADR-008 condition 2). A malformed key or
     * signature is a verification failure, not a distinguishable error:
     * surfacing which would be an oracle (TRS-CRY-013).
     *
     * @param ed25519Public the raw Ed25519 identity public key
     * @param x25519Public the raw X25519 identity-DH public key
     * @param signature the claimed binding signature
     * @return true only when the signature verifies
     */
    public static boolean verifyBinding(final byte[] ed25519Public, final byte[] x25519Public,
            final byte[] signature) {
        try {
            final Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(RawKeys.ed25519Public(ed25519Public));
            verifier.update(bindingMessage(x25519Public));
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /**
     * Builds the binding message, byte-identical to the Go implementation.
     *
     * @param x25519Public the raw identity-DH public key
     * @return UTF-8(X3DH_IDENTITY_BINDING_INFO) || key
     */
    private static byte[] bindingMessage(final byte[] x25519Public) {
        final byte[] info = LumosConstants.X3DH_IDENTITY_BINDING_INFO_VALUE.getBytes(StandardCharsets.UTF_8);
        final byte[] message = new byte[info.length + x25519Public.length];
        System.arraycopy(info, 0, message, 0, info.length);
        System.arraycopy(x25519Public, 0, message, info.length, x25519Public.length);
        return message;
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
