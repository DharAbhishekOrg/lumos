/*
 * Lumos - android-app/app / Encryption at rest, asserted against the real file.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-DAT-002, TRS-LOC-002, SRS-NFR-SEC-013, SRS-NFR-PRI-012
 * ADRs: ADR-003
 * Threading: any
 * Notes: This is SRS-DAT-002's verification method - "storage encryption test" -
 * done the only way that means anything: write real records through the real
 * store to a real SQLite FILE, then read that file's bytes off the disk and
 * search them. An assertion that the codec round-trips would prove the codec
 * round-trips; only the file says what a forensic examiner would find
 * (threat model ADV-3, LT-10).
 *
 * The special-category values in the fixtures are the ones the privacy data map
 * classifies as Article 9 - orientation, gender identity, free-text bio. Those
 * are the strings that must not appear, and naming them here rather than using
 * "hello world" is deliberate: this test should fail loudly if the sealing ever
 * stops covering the fields that matter most.
 */

package com.lumos.app.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.data.DataKeyHierarchy;
import com.lumos.app.keystore.SoftwareKekStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Proves sensitive values never reach the database file in the clear. */
final class StorageEncryptionTest {

    /** Values the privacy data map classifies as special category. */
    private static final List<String> SPECIAL_CATEGORY = List.of(
            "queer, and not looking to explain it",
            "gender identity: non-binary",
            "orientation: bisexual");

    @Test
    void noSensitiveValueAppearsInTheDatabaseFile(@TempDir final Path directory)
            throws StoreException, GeneralSecurityException, IOException {
        final Path file = directory.resolve("lumos-local.db");

        try (JdbcSqlDatabase database = JdbcSqlDatabase.atPath(file.toString())) {
            final LocalStore store = openStore(database);
            for (final String value : SPECIAL_CATEGORY) {
                store.put(RecordKind.LOCAL_PROFILE, null, LocalStore.payloadOf(value), null);
            }
            // WAL/journal contents count as "the file" for this purpose: a
            // forensic reader takes the whole directory, not just the main db.
            database.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }

        final byte[] onDisk = readEveryByteIn(directory);
        assertTrue(onDisk.length > 0, "the test must actually have written a file to search");
        for (final String value : SPECIAL_CATEGORY) {
            assertFalse(contains(onDisk, value.getBytes(StandardCharsets.UTF_8)),
                    "special-category value found in plaintext on disk: " + value);
        }
    }

    @Test
    void aStoredRecordStillRoundTripsThroughTheStore(@TempDir final Path directory)
            throws StoreException, GeneralSecurityException {
        final Path file = directory.resolve("lumos-local.db");
        try (JdbcSqlDatabase database = JdbcSqlDatabase.atPath(file.toString())) {
            final LocalStore store = openStore(database);
            final String id = store.put(RecordKind.LOCAL_PROFILE, null,
                    LocalStore.payloadOf(SPECIAL_CATEGORY.get(0)), null);
            assertArrayEquals(LocalStore.payloadOf(SPECIAL_CATEGORY.get(0)),
                    store.get(RecordKind.LOCAL_PROFILE, id).orElseThrow(),
                    "encryption at rest is worth nothing if the record cannot be read back");
        }
    }

    @Test
    void aRowMovedToAnotherIdCannotBeOpened(@TempDir final Path directory)
            throws StoreException, GeneralSecurityException {
        try (JdbcSqlDatabase database = JdbcSqlDatabase.atPath(
                directory.resolve("db").toString())) {
            final LocalStore store = openStore(database);
            final String id = store.put(RecordKind.LOCAL_PROFILE, null,
                    LocalStore.payloadOf("private"), null);

            // An adversary with write access to the file relocates the sealed
            // payload to a new row - the AAD binding is what defeats this.
            final byte[] sealed = (byte[]) database.query(
                    "SELECT " + LocalSchema.COLUMN_PAYLOAD + " FROM " + RecordKind.LOCAL_PROFILE.table()
                            + " WHERE " + LocalSchema.COLUMN_ID + " = ?", List.of(id))
                    .get(0).get(LocalSchema.COLUMN_PAYLOAD);
            database.execute("INSERT INTO " + RecordKind.LOCAL_PROFILE.table() + " ("
                    + LocalSchema.COLUMN_ID + ", " + LocalSchema.COLUMN_PAYLOAD + ", "
                    + LocalSchema.COLUMN_CREATED_AT + ", " + LocalSchema.COLUMN_UPDATED_AT + ", "
                    + LocalSchema.COLUMN_PAYLOAD_VERSION + ") VALUES (?, ?, ?, ?, ?)",
                    List.of("relocated", sealed, 0L, 0L, LocalSchema.VERSION));

            final StoreException failure = assertThrows(StoreException.class,
                    () -> store.get(RecordKind.LOCAL_PROFILE, "relocated"));
            assertEquals(StoreException.Reason.UNREADABLE, failure.reason(),
                    "a relocated payload must fail exactly as a tampered one does");
        }
    }

    @Test
    void aRowMovedToAnotherTableCannotBeOpened(@TempDir final Path directory)
            throws StoreException, GeneralSecurityException {
        try (JdbcSqlDatabase database = JdbcSqlDatabase.atPath(
                directory.resolve("db").toString())) {
            final LocalStore store = openStore(database);
            final String id = store.put(RecordKind.LOCAL_PROFILE, null,
                    LocalStore.payloadOf("private"), null);
            final byte[] sealed = (byte[]) database.query(
                    "SELECT " + LocalSchema.COLUMN_PAYLOAD + " FROM " + RecordKind.LOCAL_PROFILE.table()
                            + " WHERE " + LocalSchema.COLUMN_ID + " = ?", List.of(id))
                    .get(0).get(LocalSchema.COLUMN_PAYLOAD);
            database.execute("INSERT INTO " + RecordKind.DIAGNOSTIC_EVENT.table() + " ("
                    + LocalSchema.COLUMN_ID + ", " + LocalSchema.COLUMN_PAYLOAD + ", "
                    + LocalSchema.COLUMN_CREATED_AT + ", " + LocalSchema.COLUMN_UPDATED_AT + ", "
                    + LocalSchema.COLUMN_PAYLOAD_VERSION + ") VALUES (?, ?, ?, ?, ?)",
                    List.of(id, sealed, 0L, 0L, LocalSchema.VERSION));

            assertThrows(StoreException.class, () -> store.get(RecordKind.DIAGNOSTIC_EVENT, id),
                    "a payload moved between tables must not open in its new home");
        }
    }

    @Test
    void aDifferentDatabaseKeyCannotOpenTheRecords(@TempDir final Path directory)
            throws StoreException, GeneralSecurityException {
        try (JdbcSqlDatabase database = JdbcSqlDatabase.atPath(
                directory.resolve("db").toString())) {
            final LocalStore store = openStore(database);
            final String id = store.put(RecordKind.LOCAL_PROFILE, null,
                    LocalStore.payloadOf("private"), null);

            // A second hierarchy from a second KEK: the file is the same, the
            // key is not. This is the forensic-copy case (threat model ADV-3).
            final LocalStore other = LocalStore.open(database, freshDomainKey(),
                    () -> Long.MAX_VALUE, () -> 0L);
            assertThrows(StoreException.class, () -> other.get(RecordKind.LOCAL_PROFILE, id),
                    "records must be unreadable under any key but the one that sealed them");
        }
    }

    /**
     * Opens a store over a fresh S1 key hierarchy.
     *
     * @param database the engine
     * @return the store
     * @throws StoreException on migration failure
     * @throws GeneralSecurityException on key failure
     */
    private static LocalStore openStore(final SqlDatabase database)
            throws StoreException, GeneralSecurityException {
        return LocalStore.open(database, freshDomainKey(), () -> Long.MAX_VALUE, () -> 1_700_000_000_000L);
    }

    /**
     * Derives a database-domain key from a fresh KEK.
     *
     * @return the domain key
     * @throws GeneralSecurityException on key failure
     */
    private static byte[] freshDomainKey() throws GeneralSecurityException {
        return DataKeyHierarchy.create(SoftwareKekStore.create())
                .deriveKey(DataKeyHierarchy.DataDomain.DATABASE);
    }

    /**
     * Concatenates every byte of every file in a directory tree.
     *
     * @param directory the tree
     * @return all bytes, in no particular order
     * @throws IOException on a read failure
     */
    private static byte[] readEveryByteIn(final Path directory) throws IOException {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.stream.Stream<Path> files = Files.walk(directory)) {
            for (final Path path : files.filter(Files::isRegularFile).toList()) {
                out.writeBytes(Files.readAllBytes(path));
            }
        }
        return out.toByteArray();
    }

    /**
     * Reports whether needle occurs in haystack.
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
