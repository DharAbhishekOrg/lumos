/*
 * Lumos - android-app/app / Per-domain derivation and crypto-erasure tests.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-DAT-002, TRS-AND-004
 * ADRs: ADR-003, ADR-004
 * Threading: any
 * Notes: The properties that make one wrapped root safe to persist: domains
 * derive distinct keys, derivation is deterministic across restore, and KEK
 * destruction erases every domain at once - which is what lets the keys-first
 * wipe order treat content clearing as hygiene rather than the mechanism.
 */

package com.lumos.app.data;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lumos.app.keystore.SoftwareKekStore;
import java.security.GeneralSecurityException;
import org.junit.jupiter.api.Test;

/** Behaviour of the KEK-rooted data-key hierarchy. */
final class DataKeyHierarchyTest {

    @Test
    void domainsDeriveDistinctKeys() throws GeneralSecurityException {
        final SoftwareKekStore kek = SoftwareKekStore.create();
        final DataKeyHierarchy hierarchy = DataKeyHierarchy.create(kek);
        final byte[] db = hierarchy.deriveKey(DataKeyHierarchy.DataDomain.DATABASE);
        final byte[] media = hierarchy.deriveKey(DataKeyHierarchy.DataDomain.MEDIA_STORE);
        assertEquals(32, db.length);
        assertEquals(32, media.length);
        assertFalse(java.util.Arrays.equals(db, media),
                "domain separation must yield different keys for different stores");
    }

    @Test
    void restoreDerivesTheSameKeys() throws GeneralSecurityException {
        final SoftwareKekStore kek = SoftwareKekStore.create();
        final DataKeyHierarchy original = DataKeyHierarchy.create(kek);
        final byte[] before = original.deriveKey(DataKeyHierarchy.DataDomain.DATABASE);

        final DataKeyHierarchy restored = DataKeyHierarchy.restore(kek, original.wrappedRoot());
        assertArrayEquals(before, restored.deriveKey(DataKeyHierarchy.DataDomain.DATABASE),
                "a restart must reopen the same database key from the persisted wrapped root");
    }

    @Test
    void freshHierarchiesAreIndependent() throws GeneralSecurityException {
        final SoftwareKekStore kek = SoftwareKekStore.create();
        final byte[] first = DataKeyHierarchy.create(kek).deriveKey(DataKeyHierarchy.DataDomain.DATABASE);
        final byte[] second = DataKeyHierarchy.create(kek).deriveKey(DataKeyHierarchy.DataDomain.DATABASE);
        assertFalse(java.util.Arrays.equals(first, second),
                "each created hierarchy draws a fresh CSPRNG root");
    }

    @Test
    void kekDestructionErasesEveryDomainAtOnce() throws GeneralSecurityException {
        final SoftwareKekStore kek = SoftwareKekStore.create();
        final DataKeyHierarchy hierarchy = DataKeyHierarchy.create(kek);
        hierarchy.deriveKey(DataKeyHierarchy.DataDomain.DATABASE);
        kek.destroy();
        for (final DataKeyHierarchy.DataDomain domain : DataKeyHierarchy.DataDomain.values()) {
            assertThrows(GeneralSecurityException.class, () -> hierarchy.deriveKey(domain),
                    domain + " must be unrecoverable once the KEK is gone (crypto-erasure)");
        }
    }
}
