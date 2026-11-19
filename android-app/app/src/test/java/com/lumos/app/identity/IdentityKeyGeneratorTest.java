/*
 * Lumos - android-app/app / Identity generation, binding and crypto-erasure tests.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-IDN-001, SRS-NFR-SEC-002, SRS-NFR-SEC-003
 * ADRs: ADR-008, ADR-003, ADR-010
 * Threading: any
 * Notes: The software KEK stands in for the hardware one; every property here
 * is a property of the DESIGN (binding, fail-closed verification, wrapped-only
 * privates, crypto-erasure), not of hardware. StrongBox/Secure Enclave
 * behaviour on devices is owner-executed evidence and no test here claims it.
 */

package com.lumos.app.identity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.keystore.KeyProtection;
import com.lumos.app.keystore.SoftwareKekStore;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import org.junit.jupiter.api.Test;

/** Properties of the generated two-key identity (ADR-008). */
final class IdentityKeyGeneratorTest {

    @Test
    void bindingSignatureVerifies() throws GeneralSecurityException {
        final SoftwareKekStore kek = SoftwareKekStore.create();
        final IdentityKeyRecord record = IdentityKeyGenerator.generate(new JcaIdentityCrypto(), kek);
        assertEquals(32, record.ed25519Public().length);
        assertEquals(32, record.x25519Public().length);
        assertTrue(IdentityKeyGenerator.verifyBinding(new JcaIdentityCrypto(), 
                record.ed25519Public(), record.x25519Public(), record.bindingSignature()),
                "the ADR-008 binding must verify for a freshly generated identity");
    }

    @Test
    void verificationFailsClosedOnEveryTamperedInput() throws GeneralSecurityException {
        final SoftwareKekStore kek = SoftwareKekStore.create();
        final IdentityKeyRecord record = IdentityKeyGenerator.generate(new JcaIdentityCrypto(), kek);

        final byte[] bentEd = record.ed25519Public();
        bentEd[3] ^= 0x01;
        assertFalse(IdentityKeyGenerator.verifyBinding(new JcaIdentityCrypto(), bentEd, record.x25519Public(), record.bindingSignature()));

        final byte[] bentX = record.x25519Public();
        bentX[7] ^= 0x01;
        assertFalse(IdentityKeyGenerator.verifyBinding(new JcaIdentityCrypto(), record.ed25519Public(), bentX, record.bindingSignature()));

        final byte[] bentSig = record.bindingSignature();
        bentSig[11] ^= 0x01;
        assertFalse(IdentityKeyGenerator.verifyBinding(new JcaIdentityCrypto(), record.ed25519Public(), record.x25519Public(), bentSig));

        assertFalse(IdentityKeyGenerator.verifyBinding(new JcaIdentityCrypto(), new byte[5], record.x25519Public(),
                record.bindingSignature()), "a malformed key is a verification failure, not an exception");
    }

    @Test
    void twoIdentitiesAreDistinct() throws GeneralSecurityException {
        final SoftwareKekStore kek = SoftwareKekStore.create();
        final IdentityKeyRecord first = IdentityKeyGenerator.generate(new JcaIdentityCrypto(), kek);
        final IdentityKeyRecord second = IdentityKeyGenerator.generate(new JcaIdentityCrypto(), kek);
        assertFalse(java.util.Arrays.equals(first.ed25519Public(), second.ed25519Public()),
                "identity generation must draw from the CSPRNG, never repeat (SRS-NFR-SEC-002)");
        assertFalse(java.util.Arrays.equals(first.x25519Public(), second.x25519Public()));
    }

    @Test
    void privateKeysExistOnlyInsideTheWrappedEnvelope() throws GeneralSecurityException {
        final SoftwareKekStore kek = SoftwareKekStore.create();
        final IdentityKeyRecord record = IdentityKeyGenerator.generate(new JcaIdentityCrypto(), kek);

        // The envelope opens under the KEK with the right AAD, and its contents
        // are two well-formed PKCS#8 keys whose bytes appear NOWHERE in the
        // record's plaintext fields.
        final byte[] envelope = kek.unwrap(record.wrappedPrivateKeys(),
                IdentityKeyGenerator.ENVELOPE_AAD.getBytes(StandardCharsets.UTF_8));
        final PrivateKeyEnvelope.Keys keys = PrivateKeyEnvelope.decode(envelope);
        assertTrue(keys.ed25519Pkcs8().length > 32, "PKCS#8 must be the full encoding, not a raw seed");
        assertTrue(keys.x25519Pkcs8().length > 32);

        assertFalse(contains(record.wrappedPrivateKeys(), keys.ed25519Pkcs8()),
                "wrapped blob must not contain the plaintext Ed25519 key");
        assertFalse(contains(record.wrappedPrivateKeys(), keys.x25519Pkcs8()),
                "wrapped blob must not contain the plaintext X25519 key");
    }

    @Test
    void destroyingTheKekErasesTheIdentity() throws GeneralSecurityException {
        final SoftwareKekStore kek = SoftwareKekStore.create();
        final IdentityKeyRecord record = IdentityKeyGenerator.generate(new JcaIdentityCrypto(), kek);
        kek.destroy();
        assertThrows(GeneralSecurityException.class,
                () -> kek.unwrap(record.wrappedPrivateKeys(),
                        IdentityKeyGenerator.ENVELOPE_AAD.getBytes(StandardCharsets.UTF_8)),
                "crypto-erasure: without the KEK the identity's private half must be unrecoverable");
        assertEquals(KeyProtection.SOFTWARE_FALLBACK, record.protection(),
                "the record must carry the tier it was actually created under");
    }

    @Test
    void recordGettersAreDefensiveCopies() throws GeneralSecurityException {
        final SoftwareKekStore kek = SoftwareKekStore.create();
        final IdentityKeyRecord record = IdentityKeyGenerator.generate(new JcaIdentityCrypto(), kek);
        final byte[] mutated = record.ed25519Public();
        mutated[0] ^= 0x7F;
        assertArrayEquals(record.ed25519Public(), record.ed25519Public());
        assertFalse(java.util.Arrays.equals(mutated, record.ed25519Public()),
                "mutating a returned array must not reach the record's state");
    }

    /**
     * Reports whether needle occurs anywhere in haystack.
     *
     * @param haystack the searched bytes
     * @param needle the pattern
     * @return true when found
     */
    private static boolean contains(final byte[] haystack, final byte[] needle) {
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            boolean hit = true;
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    hit = false;
                    break;
                }
            }
            if (hit) {
                return true;
            }
        }
        return false;
    }
}
