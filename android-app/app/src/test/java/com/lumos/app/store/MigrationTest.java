/*
 * Lumos - android-app/app / The migration suite, executed on a real engine.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-LOC-003, TRS-LOC-010
 * ADRs: -
 * Threading: any
 * Notes: TRS-LOC-003 wants migrations versioned, transactional, backward-tested
 * and crash-recoverable. Each of those is a separate assertion here, and the
 * one that carries the most weight is transactional: a failing step must leave
 * the database at its PREVIOUS version, because a database stuck at a version
 * that never existed is unrecoverable by any later migration.
 */

package com.lumos.app.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Executes the forward-only ladder against SQLite. */
final class MigrationTest {

    @Test
    void emptyDatabaseMigratesToCurrentVersion() throws StoreException {
        try (JdbcSqlDatabase database = JdbcSqlDatabase.inMemory()) {
            LocalStore.migrate(database);
            assertEquals(LocalSchema.VERSION, storedVersion(database),
                    "a v0 database must arrive at the current schema version");
        }
    }

    @Test
    void everyDeclaredTableExistsAfterMigration() throws StoreException {
        try (JdbcSqlDatabase database = JdbcSqlDatabase.inMemory()) {
            LocalStore.migrate(database);
            for (final RecordKind kind : RecordKind.values()) {
                final List<Map<String, Object>> rows = database.query(
                        "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
                        List.of(kind.table()));
                assertFalse(rows.isEmpty(), kind.table() + " must exist after migration");
            }
        }
    }

    @Test
    void everyRecordTableCarriesTheCanonicalColumns() throws StoreException {
        try (JdbcSqlDatabase database = JdbcSqlDatabase.inMemory()) {
            LocalStore.migrate(database);
            for (final RecordKind kind : RecordKind.values()) {
                final List<Map<String, Object>> columns =
                        database.query("PRAGMA table_info(" + kind.table() + ")", List.of());
                final List<String> names = columns.stream()
                        .map(c -> String.valueOf(c.get("name"))).toList();
                assertEquals(List.of(LocalSchema.COLUMN_ID, LocalSchema.COLUMN_PAYLOAD,
                                LocalSchema.COLUMN_LOOKUP_TAG, LocalSchema.COLUMN_CREATED_AT,
                                LocalSchema.COLUMN_UPDATED_AT, LocalSchema.COLUMN_PAYLOAD_VERSION),
                        names, kind.table() + " must have exactly the canonical column set");
            }
        }
    }

    @Test
    void migrationIsIdempotentAcrossReopen() throws StoreException {
        try (JdbcSqlDatabase database = JdbcSqlDatabase.inMemory()) {
            LocalStore.migrate(database);
            LocalStore.migrate(database);
            assertEquals(LocalSchema.VERSION, storedVersion(database),
                    "re-running the ladder on an up-to-date database must be a no-op, not an error");
        }
    }

    @Test
    void aDatabaseFromTheFutureIsRefusedNotDowngraded() throws StoreException {
        try (JdbcSqlDatabase database = JdbcSqlDatabase.inMemory()) {
            LocalStore.migrate(database);
            database.execute("UPDATE " + LocalSchema.TABLE_META + " SET " + LocalSchema.META_VALUE
                    + " = ? WHERE " + LocalSchema.META_KEY + " = ?",
                    List.of(String.valueOf(LocalSchema.VERSION + 1), LocalSchema.META_SCHEMA_VERSION));

            final StoreException failure = assertThrows(StoreException.class,
                    () -> LocalStore.migrate(database));
            assertEquals(StoreException.Reason.SCHEMA_FROM_THE_FUTURE, failure.reason(),
                    "forward-only means a newer database is refused, never silently downgraded");
        }
    }

    @Test
    void aFailingStepLeavesThePreviousVersionIntact() throws StoreException {
        try (JdbcSqlDatabase database = JdbcSqlDatabase.inMemory()) {
            LocalStore.migrate(database);
            final int before = storedVersion(database);

            // A step whose second statement cannot execute: the table it creates
            // must not survive, and the version must not move.
            assertThrows(StoreException.class, () -> database.inTransaction(() -> {
                database.execute("CREATE TABLE half_migrated (x INTEGER)");
                database.execute("THIS IS NOT SQL");
                return null;
            }));

            assertEquals(before, storedVersion(database), "a failed step must not move the version");
            final List<Map<String, Object>> leftovers = database.query(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
                    List.of("half_migrated"));
            assertTrue(leftovers.isEmpty(),
                    "the failed step's table must have been rolled back with it");
        }
    }

    @Test
    void theLadderIsContiguousAndForwardOnly() {
        int expected = LocalSchema.VERSION_EMPTY;
        for (final Migration step : LocalSchema.migrations()) {
            assertEquals(expected, step.fromVersion(), "the ladder must not skip a version");
            assertTrue(step.toVersion() > step.fromVersion(), "every step must move forward");
            assertFalse(step.statements().isEmpty(), "an empty step would be a version bump with no schema change");
            expected = step.toVersion();
        }
        assertEquals(LocalSchema.VERSION, expected, "the ladder must end at the declared schema version");
    }

    /**
     * Reads the stored schema version.
     *
     * @param database the database
     * @return the version recorded in the meta table
     * @throws StoreException on engine failure
     */
    private static int storedVersion(final SqlDatabase database) throws StoreException {
        final List<Map<String, Object>> rows = database.query(
                "SELECT " + LocalSchema.META_VALUE + " FROM " + LocalSchema.TABLE_META
                        + " WHERE " + LocalSchema.META_KEY + " = ?",
                List.of(LocalSchema.META_SCHEMA_VERSION));
        return Integer.parseInt(String.valueOf(rows.get(0).get(LocalSchema.META_VALUE)));
    }
}
