/*
 * Lumos - android-app/app / Store behaviour: transactions, blind index, capacity.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-LOC-003, TRS-LOC-006, TRS-LOC-010, SRS-DAT-002
 * ADRs: -
 * Threading: any
 * Notes: The behaviours a caller depends on and cannot see from the schema:
 * that a failed write leaves nothing, that the blind index finds a peer without
 * the peer's identifier being in the file, that a full disk refuses early, and
 * that identifiers and timestamps are what TRS-LOC-010 requires.
 */

package com.lumos.app.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.data.DataKeyHierarchy;
import com.lumos.app.keystore.SoftwareKekStore;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Behavioural contract of the local store. */
final class LocalStoreTest {

    /** A capacity seam reporting effectively unlimited room. */
    private static final StorageCapacity ROOMY = () -> Long.MAX_VALUE;

    /** A fixed UTC instant, so timestamp assertions are exact. */
    private static final long FIXED_NOW = 1_700_000_000_000L;

    @Test
    void recordsRoundTripPerKind() throws Exception {
        try (JdbcSqlDatabase database = JdbcSqlDatabase.inMemory()) {
            final LocalStore store = open(database, ROOMY);
            for (final RecordKind kind : RecordKind.values()) {
                final byte[] payload = LocalStore.payloadOf("content for " + kind);
                final byte[] natural = kind.isLookedUpByTag()
                        ? "peer-1".getBytes(StandardCharsets.UTF_8) : null;
                final String id = store.put(kind, null, payload, natural);
                assertArrayEquals(payload, store.get(kind, id).orElseThrow(), kind + " must round-trip");
            }
        }
    }

    @Test
    void mintedIdentifiersAreUuids() throws Exception {
        try (JdbcSqlDatabase database = JdbcSqlDatabase.inMemory()) {
            final LocalStore store = open(database, ROOMY);
            final String id = store.put(RecordKind.LOCAL_PROFILE, null,
                    LocalStore.payloadOf("x"), null);
            // TRS-LOC-010: stable UUIDs. Parsing is the assertion - a natural
            // key smuggled in as an id would fail here.
            assertEquals(id, UUID.fromString(id).toString());
        }
    }

    @Test
    void timestampsAreUtcMillisFromTheInjectedClock() throws Exception {
        try (JdbcSqlDatabase database = JdbcSqlDatabase.inMemory()) {
            final LocalStore store = open(database, ROOMY);
            final String id = store.put(RecordKind.LOCAL_PROFILE, null,
                    LocalStore.payloadOf("x"), null);
            final Object created = database.query("SELECT " + LocalSchema.COLUMN_CREATED_AT
                    + " FROM " + RecordKind.LOCAL_PROFILE.table() + " WHERE "
                    + LocalSchema.COLUMN_ID + " = ?", List.of(id))
                    .get(0).get(LocalSchema.COLUMN_CREATED_AT);
            assertEquals(FIXED_NOW, Long.parseLong(String.valueOf(created)),
                    "TRS-LOC-010: UTC milliseconds, from the clock the store was given");
        }
    }

    @Test
    void updateReplacesPayloadAndKeepsTheIdentifier() throws Exception {
        try (JdbcSqlDatabase database = JdbcSqlDatabase.inMemory()) {
            final LocalStore store = open(database, ROOMY);
            final String id = store.put(RecordKind.LOCAL_PROFILE, null, LocalStore.payloadOf("v1"), null);
            store.put(RecordKind.LOCAL_PROFILE, id, LocalStore.payloadOf("v2"), null);
            assertArrayEquals(LocalStore.payloadOf("v2"),
                    store.get(RecordKind.LOCAL_PROFILE, id).orElseThrow());
            assertEquals(1, store.count(RecordKind.LOCAL_PROFILE), "an update must not insert a second row");
        }
    }

    @Test
    void blockEntriesAreFoundByBlindIndexWithoutStoringThePeerIdentifier() throws Exception {
        try (JdbcSqlDatabase database = JdbcSqlDatabase.inMemory()) {
            final LocalStore store = open(database, ROOMY);
            final byte[] peer = "peer-identifier-that-must-not-be-stored".getBytes(StandardCharsets.UTF_8);
            store.put(RecordKind.BLOCK_ENTRY, null, LocalStore.payloadOf("blocked"), peer);

            final List<byte[]> found = store.findByNaturalKey(RecordKind.BLOCK_ENTRY, peer);
            assertEquals(1, found.size(), "the blind index must find the entry");
            assertArrayEquals(LocalStore.payloadOf("blocked"), found.get(0));

            final byte[] tag = (byte[]) database.query("SELECT " + LocalSchema.COLUMN_LOOKUP_TAG
                    + " FROM " + RecordKind.BLOCK_ENTRY.table(), List.of())
                    .get(0).get(LocalSchema.COLUMN_LOOKUP_TAG);
            assertFalse(java.util.Arrays.equals(tag, peer),
                    "the stored tag must not be the peer identifier itself");
            assertTrue(store.findByNaturalKey(RecordKind.BLOCK_ENTRY,
                    "some-other-peer".getBytes(StandardCharsets.UTF_8)).isEmpty(),
                    "an unrelated peer must not match");
        }
    }

    @Test
    void aKindNeedingATagRefusesAWriteWithout() throws Exception {
        try (JdbcSqlDatabase database = JdbcSqlDatabase.inMemory()) {
            final LocalStore store = open(database, ROOMY);
            assertThrows(StoreException.class,
                    () -> store.put(RecordKind.BLOCK_ENTRY, null, LocalStore.payloadOf("x"), null),
                    "a block entry with no blind index could never be found again");
        }
    }

    @Test
    void lowStorageRefusesTheWriteBeforeAnythingIsSealed() throws Exception {
        try (JdbcSqlDatabase database = JdbcSqlDatabase.inMemory()) {
            final StorageCapacity full = () -> StorageCapacity.WRITE_FLOOR_BYTES - 1;
            final LocalStore store = open(database, full);

            final StoreException failure = assertThrows(StoreException.class,
                    () -> store.put(RecordKind.LOCAL_PROFILE, null, LocalStore.payloadOf("x"), null));
            assertEquals(StoreException.Reason.LOW_STORAGE, failure.reason());
            assertEquals(0, store.count(RecordKind.LOCAL_PROFILE),
                    "a refused write must leave no row behind");
        }
    }

    @Test
    void exactlyTheFloorIsEnoughRoom() throws Exception {
        try (JdbcSqlDatabase database = JdbcSqlDatabase.inMemory()) {
            final LocalStore store = open(database, () -> StorageCapacity.WRITE_FLOOR_BYTES);
            assertNotNull(store.put(RecordKind.LOCAL_PROFILE, null, LocalStore.payloadOf("x"), null),
                    "the floor is a minimum, not an exclusive bound");
        }
    }

    @Test
    void aFailedTransactionLeavesNoPartialWrite() throws Exception {
        try (JdbcSqlDatabase database = JdbcSqlDatabase.inMemory()) {
            final LocalStore store = open(database, ROOMY);
            store.put(RecordKind.LOCAL_PROFILE, null, LocalStore.payloadOf("first"), null);

            assertThrows(StoreException.class, () -> database.inTransaction(() -> {
                database.execute("INSERT INTO " + RecordKind.LOCAL_PROFILE.table() + " ("
                        + LocalSchema.COLUMN_ID + ", " + LocalSchema.COLUMN_PAYLOAD + ", "
                        + LocalSchema.COLUMN_CREATED_AT + ", " + LocalSchema.COLUMN_UPDATED_AT + ", "
                        + LocalSchema.COLUMN_PAYLOAD_VERSION + ") VALUES (?, ?, ?, ?, ?)",
                        List.of("second", new byte[] {1, 2, 3}, 0L, 0L, LocalSchema.VERSION));
                throw new StoreException(StoreException.Reason.ENGINE, "simulated crash mid-transaction");
            }));

            assertEquals(1, store.count(RecordKind.LOCAL_PROFILE),
                    "crash consistency: the interrupted transaction must have left nothing");
        }
    }

    @Test
    void deleteRemovesTheRow() throws Exception {
        try (JdbcSqlDatabase database = JdbcSqlDatabase.inMemory()) {
            final LocalStore store = open(database, ROOMY);
            final String id = store.put(RecordKind.LOCAL_PROFILE, null, LocalStore.payloadOf("x"), null);
            store.delete(RecordKind.LOCAL_PROFILE, id);
            assertTrue(store.get(RecordKind.LOCAL_PROFILE, id).isEmpty());
        }
    }

    @Test
    void missingRowReadsAsEmptyRatherThanFailing() throws Exception {
        try (JdbcSqlDatabase database = JdbcSqlDatabase.inMemory()) {
            final LocalStore store = open(database, ROOMY);
            assertTrue(store.get(RecordKind.LOCAL_PROFILE, UUID.randomUUID().toString()).isEmpty(),
                    "absence is a state; only an unopenable row is a failure");
        }
    }

    /**
     * Opens a store with a fresh key and the fixed clock.
     *
     * @param database the engine
     * @param capacity the capacity seam
     * @return the store
     * @throws StoreException on migration failure
     * @throws GeneralSecurityException on key failure
     */
    private static LocalStore open(final SqlDatabase database, final StorageCapacity capacity)
            throws StoreException, GeneralSecurityException {
        final byte[] key = DataKeyHierarchy.create(SoftwareKekStore.create())
                .deriveKey(DataKeyHierarchy.DataDomain.DATABASE);
        return LocalStore.open(database, key, capacity, () -> FIXED_NOW);
    }
}
