/*
 * Lumos - android-app/app / The identity-crypto backend seam.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-IDN-001, SRS-NFR-SEC-002, TRS-CRY-001
 * ADRs: ADR-001, ADR-008
 * Threading: any
 * Notes: WP-02's closeout finding 5.1, turned into a type: Android cannot
 * generate an Ed25519 key at any API level, so the identity operations get a
 * backend seam with two implementations - JcaIdentityCrypto where the JCA has
 * the algorithms (the JVM: every unit test and CI job) and
 * LibsodiumIdentityCrypto everywhere the app actually ships (ADR-001 names
 * libsodium as the ONLY generation path on Android). The seam is an Adapter
 * chosen by constructor injection (TRS Sec. 24.2), never probed at call time:
 * a caller that guessed wrong fails loudly on first use instead of generating
 * on some devices and not others.
 *
 * The canonical shapes are fixed here so the two backends cannot drift apart:
 * private keys cross this boundary only as PKCS#8 (what PrivateKeyEnvelope
 * stores - RFC 8410's fixed-prefix encodings, so libsodium's raw seeds wrap
 * losslessly), publics only as the raw 32 bytes the wire uses, and both
 * backends are held to the committed identity_binding vector family, which is
 * what makes "two implementations" a redundancy rather than a fork.
 */

package com.lumos.app.identity;

import java.security.GeneralSecurityException;
import java.util.Arrays;

/** Backend seam for ADR-008 identity generation, binding and verification. */
public interface IdentityCrypto {

    /**
     * Generates a fresh Ed25519 signing pair.
     *
     * @return the pair; the caller zeroes the private half after use
     * @throws GeneralSecurityException on backend failure
     */
    KeyMaterial generateEd25519() throws GeneralSecurityException;

    /**
     * Generates a fresh X25519 agreement pair.
     *
     * @return the pair; the caller zeroes the private half after use
     * @throws GeneralSecurityException on backend failure
     */
    KeyMaterial generateX25519() throws GeneralSecurityException;

    /**
     * Signs a binding message under an Ed25519 private key.
     *
     * @param ed25519Pkcs8 the private key in PKCS#8 form
     * @param message the exact bytes to sign
     * @return the 64-byte RFC 8032 signature
     * @throws GeneralSecurityException on a malformed key or backend failure
     */
    byte[] signBinding(byte[] ed25519Pkcs8, byte[] message) throws GeneralSecurityException;

    /**
     * Verifies an Ed25519 signature, failing closed.
     *
     * <p>A malformed key or signature is a verification failure, not a
     * distinguishable error: surfacing which would be an oracle (TRS-CRY-013).
     *
     * @param ed25519Public the raw 32-byte public key
     * @param message the exact signed bytes
     * @param signature the claimed signature
     * @return true only when the signature verifies
     */
    boolean verifyBinding(byte[] ed25519Public, byte[] message, byte[] signature);

    /** One generated pair: a PKCS#8 private key and its raw public key. */
    final class KeyMaterial {

        private final byte[] pkcs8;
        private final byte[] rawPublic;

        /**
         * Wraps generated material; the arrays are adopted, not copied.
         *
         * @param pkcs8 the private key in PKCS#8 form
         * @param rawPublic the raw 32-byte public key
         */
        public KeyMaterial(final byte[] pkcs8, final byte[] rawPublic) {
            this.pkcs8 = pkcs8;
            this.rawPublic = rawPublic;
        }

        /**
         * The private key in PKCS#8 form.
         *
         * @return the live array; zero it through {@link #destroy()}
         */
        public byte[] pkcs8() {
            return pkcs8;
        }

        /**
         * The raw 32-byte public key.
         *
         * @return the live array
         */
        public byte[] rawPublic() {
            return rawPublic;
        }

        /** Zeroes the private half; the material is unusable afterwards. */
        public void destroy() {
            Arrays.fill(pkcs8, (byte) 0);
        }
    }
}
