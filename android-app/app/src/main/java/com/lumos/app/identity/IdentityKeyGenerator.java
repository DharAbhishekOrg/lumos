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
 * WP-03 S1: the crypto moved behind the IdentityCrypto seam, because WP-02's
 * closeout finding 5.1 measured that Android cannot generate an Ed25519 key at
 * any API level - the JCA code this class used to call inline runs on the JVM
 * and on no Android runtime at all. Callers inject the backend: JcaIdentityCrypto
 * where the JCA has the algorithms (every unit test and CI job),
 * LibsodiumIdentityCrypto on every shipping Android device (ADR-001). This
 * class keeps what must not fork: the envelope discipline, the binding message
 * and the zeroing. PlatformKeyAlgorithmInstrumentedTest remains the tripwire
 * that fails the day the platform makes the seam unnecessary.
 */

package com.lumos.app.identity;

import com.lumos.app.keystore.KekStore;
import com.lumos.protocol.LumosConstants;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
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
     * <p>Key material comes from the injected backend's CSPRNG
     * (SRS-NFR-SEC-002); the private keys' only surviving form is the returned
     * record's wrapped envelope.
     *
     * @param crypto the identity backend - libsodium on Android, JCA on the JVM
     * @param kek the store that wraps the private-key envelope
     * @return the at-rest identity record
     * @throws GeneralSecurityException if generation, signing or wrapping fails
     */
    public static IdentityKeyRecord generate(final IdentityCrypto crypto, final KekStore kek)
            throws GeneralSecurityException {
        final IdentityCrypto.KeyMaterial ed = crypto.generateEd25519();
        final IdentityCrypto.KeyMaterial x = crypto.generateX25519();
        try {
            final byte[] binding = crypto.signBinding(ed.pkcs8(), bindingMessage(x.rawPublic()));
            final byte[] envelope = PrivateKeyEnvelope.encode(ed.pkcs8(), x.pkcs8());
            final byte[] wrapped;
            try {
                wrapped = kek.wrap(envelope, ENVELOPE_AAD.getBytes(StandardCharsets.UTF_8));
            } finally {
                // The plaintext envelope's only long-lived form is the wrapped one.
                Arrays.fill(envelope, (byte) 0);
            }
            return new IdentityKeyRecord(ed.rawPublic(), x.rawPublic(), binding, wrapped,
                    kek.protection());
        } finally {
            ed.destroy();
            x.destroy();
        }
    }

    /**
     * Verifies the ADR-008 binding signature, failing closed.
     *
     * <p>This is the check that must pass before the identity-DH key is ever
     * used in a DH computation (ADR-008 condition 2). A malformed key or
     * signature is a verification failure, not a distinguishable error:
     * surfacing which would be an oracle (TRS-CRY-013).
     *
     * @param crypto the identity backend that performs the verification
     * @param ed25519Public the raw Ed25519 identity public key
     * @param x25519Public the raw X25519 identity-DH public key
     * @param signature the claimed binding signature
     * @return true only when the signature verifies
     */
    public static boolean verifyBinding(final IdentityCrypto crypto, final byte[] ed25519Public,
            final byte[] x25519Public, final byte[] signature) {
        return crypto.verifyBinding(ed25519Public, bindingMessage(x25519Public), signature);
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

}
