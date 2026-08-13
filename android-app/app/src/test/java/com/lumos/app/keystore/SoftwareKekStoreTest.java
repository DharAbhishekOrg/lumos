/*
 * Lumos - android-app/app / Software KEK behaviour and wrap-format negatives.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-NFR-SEC-003, SRS-DAT-002, TRS-CRY-011, TRS-CRY-013
 * ADRs: ADR-003, ADR-010
 * Threading: any
 * Notes: These tests exercise every behaviour the hardware store shares through
 * KekWrapFormat - framing, AAD binding, tamper rejection, destroy semantics -
 * against the software tier, which is the tier CI can run. What they do NOT
 * claim: anything about StrongBox, the TEE, or non-exportability of a
 * hardware-held key. That evidence is owner-executed on physical devices
 * (WP-02 kickoff), and no test here simulates it.
 */

package com.lumos.app.keystore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import org.junit.jupiter.api.Test;

/** Behavioural contract of the software KEK tier. */
final class SoftwareKekStoreTest {

    private static final byte[] DATA_KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AAD = "lumos/local/test/v1".getBytes(StandardCharsets.UTF_8);

    @Test
    void wrapUnwrapRoundtrips() throws GeneralSecurityException {
        final SoftwareKekStore kek = SoftwareKekStore.create();
        final byte[] wrapped = kek.wrap(DATA_KEY, AAD);
        assertArrayEquals(DATA_KEY, kek.unwrap(wrapped, AAD));
    }

    @Test
    void protectionTierIsHonest() throws GeneralSecurityException {
        final SoftwareKekStore kek = SoftwareKekStore.create();
        assertEquals(KeyProtection.SOFTWARE_FALLBACK, kek.protection(),
                "the software tier must never report itself as hardware (threat model LT-05)");
        assertFalse(kek.protection().isHardwareBacked());
    }

    @Test
    void sameKeyWrappedTwiceProducesDifferentBlobs() throws GeneralSecurityException {
        final SoftwareKekStore kek = SoftwareKekStore.create();
        final byte[] first = kek.wrap(DATA_KEY, AAD);
        final byte[] second = kek.wrap(DATA_KEY, AAD);
        assertFalse(java.util.Arrays.equals(first, second),
                "a repeated IV under one key is the GCM failure mode TRS-CRY-011 forbids");
    }

    @Test
    void wrappedBlobDoesNotContainThePlaintext() throws GeneralSecurityException {
        final SoftwareKekStore kek = SoftwareKekStore.create();
        final byte[] wrapped = kek.wrap(DATA_KEY, AAD);
        for (int i = 0; i + DATA_KEY.length <= wrapped.length; i++) {
            final byte[] window = java.util.Arrays.copyOfRange(wrapped, i, i + DATA_KEY.length);
            assertFalse(java.util.Arrays.equals(window, DATA_KEY),
                    "plaintext key bytes must not appear anywhere in the wrapped blob");
        }
    }

    @Test
    void tamperingAnywhereIsRejected() throws GeneralSecurityException {
        final SoftwareKekStore kek = SoftwareKekStore.create();
        final byte[] wrapped = kek.wrap(DATA_KEY, AAD);
        // Flip one bit in each region: version, IV, ciphertext, tag.
        final int[] offsets = {0, 5, wrapped.length / 2, wrapped.length - 1};
        for (final int at : offsets) {
            final byte[] bent = wrapped.clone();
            bent[at] ^= 0x01;
            assertThrows(GeneralSecurityException.class, () -> kek.unwrap(bent, AAD),
                    "tampered byte at offset " + at + " must be rejected");
        }
    }

    @Test
    void wrongAADIsRejectedIndistinguishably() throws GeneralSecurityException {
        final SoftwareKekStore kek = SoftwareKekStore.create();
        final byte[] wrapped = kek.wrap(DATA_KEY, AAD);
        final byte[] otherAad = "lumos/local/other/v1".getBytes(StandardCharsets.UTF_8);
        final GeneralSecurityException e = assertThrows(GeneralSecurityException.class,
                () -> kek.unwrap(wrapped, otherAad));
        // TRS-CRY-013: the failure must not disclose which check failed.
        assertFalse(String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT).contains("aad"),
                "the error must not name the AAD as the failing input");
    }

    @Test
    void destroyedStoreRefusesEverything() throws GeneralSecurityException {
        final SoftwareKekStore kek = SoftwareKekStore.create();
        final byte[] wrapped = kek.wrap(DATA_KEY, AAD);
        kek.destroy();
        assertTrue(kek.isDestroyed());
        assertThrows(GeneralSecurityException.class, () -> kek.unwrap(wrapped, AAD),
                "crypto-erasure: a destroyed KEK must make every blob it wrapped unopenable");
        assertThrows(GeneralSecurityException.class, () -> kek.wrap(DATA_KEY, AAD));
    }

    @Test
    void unknownFormatVersionFailsClosed() throws GeneralSecurityException {
        final SoftwareKekStore kek = SoftwareKekStore.create();
        final byte[] wrapped = kek.wrap(DATA_KEY, AAD);
        final byte[] future = wrapped.clone();
        future[0] = 2;
        assertThrows(GeneralSecurityException.class, () -> kek.unwrap(future, AAD),
                "an unknown wrap-format version is refused, never guessed at");
    }
}
