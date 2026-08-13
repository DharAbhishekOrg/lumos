/*
 * Lumos - android-app/app / The wipe now covers the stores, keys first.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-SAF-010, TRS-LOC-007, SRS-NFR-PRI-012
 * ADRs: ADR-003, ADR-010
 * Threading: any
 * Notes: S1 proved the ORDER with synthetic stages. This proves the order over
 * real data: rows written through the real store, then a wipe whose content
 * stage is interrupted, and the assertion that what survives on disk is
 * unreadable anyway. That is the crypto-erasure claim (owner ruling
 * 2026-08-14) exercised end to end rather than argued.
 *
 * SRS-SAF-010 names more than these stores - queued packets, venue tokens,
 * cached objects - so the requirement stays not_started until those exist.
 * What grows here is the registered set, not the claim.
 */

package com.lumos.app.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.data.DataKeyHierarchy;
import com.lumos.app.keystore.SoftwareKekStore;
import com.lumos.app.wipe.KeysFirstWipe;
import java.security.GeneralSecurityException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The keys-first wipe extended over the local store. */
final class StoreWipeTest {

    @Test
    void wipeDestroysStoreKeysBeforeClearingRows() throws Exception {
        try (JdbcSqlDatabase database = JdbcSqlDatabase.inMemory()) {
            final SoftwareKekStore kek = SoftwareKekStore.create();
            final DataKeyHierarchy hierarchy = DataKeyHierarchy.create(kek);
            final LocalStore store = LocalStore.open(database,
                    hierarchy.deriveKey(DataKeyHierarchy.DataDomain.DATABASE),
                    () -> Long.MAX_VALUE, () -> 0L);
            store.put(RecordKind.LOCAL_PROFILE, null, LocalStore.payloadOf("private"), null);

            final List<String> order = new java.util.ArrayList<>();
            final KeysFirstWipe.WipeReport report = new KeysFirstWipe()
                    .registerContent("local-store rows", () -> {
                        order.add("rows");
                        store.deleteEverything();
                    })
                    .registerKeyMaterial("local-store keys", () -> {
                        order.add("store-keys");
                        store.destroyKeys();
                    })
                    .registerKeyMaterial("kek", () -> {
                        order.add("kek");
                        kek.destroy();
                    })
                    .execute();

            assertEquals(List.of("store-keys", "kek", "rows"), order,
                    "both key stages must precede the content stage whatever the registration order");
            assertTrue(report.fullyClean());
            assertEquals(0, store.count(RecordKind.LOCAL_PROFILE));
        }
    }

    @Test
    void rowsSurvivingAnInterruptedWipeAreUnreadable() throws Exception {
        try (JdbcSqlDatabase database = JdbcSqlDatabase.inMemory()) {
            final SoftwareKekStore kek = SoftwareKekStore.create();
            final DataKeyHierarchy hierarchy = DataKeyHierarchy.create(kek);
            final LocalStore store = LocalStore.open(database,
                    hierarchy.deriveKey(DataKeyHierarchy.DataDomain.DATABASE),
                    () -> Long.MAX_VALUE, () -> 0L);
            final String id = store.put(RecordKind.LOCAL_PROFILE, null,
                    LocalStore.payloadOf("private"), null);

            final KeysFirstWipe.WipeReport report = new KeysFirstWipe()
                    .registerKeyMaterial("local-store keys", store::destroyKeys)
                    .registerKeyMaterial("kek", kek::destroy)
                    .registerContent("local-store rows", () -> {
                        throw new java.security.GeneralSecurityException("storage detached mid-wipe");
                    })
                    .execute();

            assertFalse(report.fullyClean(), "the interrupted content stage must be reported");
            assertTrue(report.allKeyMaterialDestroyed(), "the key phase completed, so erasure holds");

            assertEquals(1, store.count(RecordKind.LOCAL_PROFILE),
                    "the row is still physically present - that is the point of the test");
            assertThrows(StoreException.class, () -> store.get(RecordKind.LOCAL_PROFILE, id),
                    "and it is ciphertext without a key, which is what makes the interruption survivable");
        }
    }

    @Test
    void everyRecordKindIsClearedByTheContentStage() throws Exception {
        try (JdbcSqlDatabase database = JdbcSqlDatabase.inMemory()) {
            final LocalStore store = LocalStore.open(database, freshKey(),
                    () -> Long.MAX_VALUE, () -> 0L);
            for (final RecordKind kind : RecordKind.values()) {
                final byte[] natural = kind.isLookedUpByTag()
                        ? "peer".getBytes(java.nio.charset.StandardCharsets.UTF_8) : null;
                store.put(kind, null, LocalStore.payloadOf("x"), natural);
            }
            store.deleteEverything();
            for (final RecordKind kind : RecordKind.values()) {
                assertEquals(0, store.count(kind),
                        kind + " must be cleared; a table missed here is data the user asked to destroy");
            }
        }
    }

    /**
     * Derives a database-domain key from a fresh KEK.
     *
     * @return the domain key
     * @throws GeneralSecurityException on key failure
     */
    private static byte[] freshKey() throws GeneralSecurityException {
        return DataKeyHierarchy.create(SoftwareKekStore.create())
                .deriveKey(DataKeyHierarchy.DataDomain.DATABASE);
    }
}
