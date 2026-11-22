/*
 * Lumos - android-app/app / The encrypted local store and its migration runner.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-DAT-002, TRS-LOC-002, TRS-LOC-003, TRS-LOC-006, TRS-LOC-010
 * ADRs: ADR-003
 * Threading: serial-executor
 * Notes: Everything a record needs on its way to disk, in one place: the
 * low-storage guard, the seal, the transaction, the timestamps. The ordering
 * inside `put` is not arbitrary - capacity is checked BEFORE the payload is
 * sealed so a refusal costs nothing, and the write happens inside a
 * transaction so a crash between the row and its index leaves neither.
 *
 * `open` runs the migration ladder before returning, and refuses a database
 * newer than this build (forward-only, TRS-LOC-003). Each migration step is
 * itself transactional: a step that fails half way leaves the database at its
 * previous version, not at an invented one, which is what "crash-recoverable"
 * has to mean for a ladder that will one day be longer than a single rung.
 */

package com.lumos.app.store;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The encrypted record store over a SQL engine. */
public final class LocalStore {

    private final SqlDatabase database;
    private final SealedRecordCodec codec;
    private final StorageCapacity capacity;
    private final Clock clock;

    /** A UTC millisecond clock; a seam so timestamp behaviour is testable. */
    @FunctionalInterface
    public interface Clock {
        /**
         * The current time.
         *
         * @return UTC milliseconds since the epoch (TRS-LOC-010)
         */
        long nowUtcMillis();
    }

    private LocalStore(final SqlDatabase database, final SealedRecordCodec codec,
            final StorageCapacity capacity, final Clock clock) {
        this.database = database;
        this.codec = codec;
        this.capacity = capacity;
        this.clock = clock;
    }

    /**
     * Opens the store, migrating the database to {@link LocalSchema#VERSION}.
     *
     * @param database the engine seam
     * @param databaseDomainKey the S1 DATABASE domain key
     * @param capacity the low-storage seam
     * @param clock the UTC clock
     * @return the opened store
     * @throws StoreException if migration fails or the schema is from the future
     */
    public static LocalStore open(final SqlDatabase database, final byte[] databaseDomainKey,
            final StorageCapacity capacity, final Clock clock) throws StoreException {
        migrate(database);
        return new LocalStore(database, new SealedRecordCodec(databaseDomainKey), capacity, clock);
    }

    /**
     * Runs the forward-only migration ladder.
     *
     * @param database the engine seam
     * @throws StoreException on a malformed ladder, a future schema, or engine failure
     */
    public static void migrate(final SqlDatabase database) throws StoreException {
        final int current = readVersion(database);
        if (current > LocalSchema.VERSION) {
            throw new StoreException(StoreException.Reason.SCHEMA_FROM_THE_FUTURE,
                    "database is version " + current + ", this build understands " + LocalSchema.VERSION);
        }
        int at = current;
        for (final Migration step : LocalSchema.migrations()) {
            if (step.fromVersion() < at) {
                continue;
            }
            if (step.fromVersion() != at) {
                throw new StoreException(StoreException.Reason.MIGRATION_LADDER,
                        "ladder skips from " + at + " to " + step.fromVersion());
            }
            applyStep(database, step);
            at = step.toVersion();
        }
        if (at != LocalSchema.VERSION) {
            throw new StoreException(StoreException.Reason.MIGRATION_LADDER,
                    "ladder ends at " + at + ", schema version is " + LocalSchema.VERSION);
        }
    }

    /**
     * Applies one migration step inside a transaction.
     *
     * @param database the engine seam
     * @param step the step
     * @throws StoreException on engine failure; the step is rolled back whole
     */
    private static void applyStep(final SqlDatabase database, final Migration step) throws StoreException {
        database.inTransaction(() -> {
            for (final String sql : step.statements()) {
                database.execute(sql);
            }
            writeVersion(database, step.toVersion());
            return null;
        });
    }

    /**
     * Reads the stored schema version, treating an absent meta table as empty.
     *
     * @param database the engine seam
     * @return the current version, or {@link LocalSchema#VERSION_EMPTY}
     * @throws StoreException on engine failure other than a missing table
     */
    private static int readVersion(final SqlDatabase database) throws StoreException {
        final List<Map<String, Object>> rows;
        try {
            rows = database.query("SELECT " + LocalSchema.META_VALUE + " FROM " + LocalSchema.TABLE_META
                    + " WHERE " + LocalSchema.META_KEY + " = ?",
                    List.of(LocalSchema.META_SCHEMA_VERSION));
        } catch (StoreException e) {
            // No meta table is the empty database, which is a state and not a
            // failure. Any other engine error still surfaces from the migration.
            return LocalSchema.VERSION_EMPTY;
        }
        if (rows.isEmpty()) {
            return LocalSchema.VERSION_EMPTY;
        }
        return Integer.parseInt(String.valueOf(rows.get(0).get(LocalSchema.META_VALUE)));
    }

    /**
     * Records the schema version.
     *
     * @param database the engine seam
     * @param version the version reached
     * @throws StoreException on engine failure
     */
    private static void writeVersion(final SqlDatabase database, final int version) throws StoreException {
        database.execute("INSERT INTO " + LocalSchema.TABLE_META + " (" + LocalSchema.META_KEY + ", "
                + LocalSchema.META_VALUE + ") VALUES (?, ?) ON CONFLICT(" + LocalSchema.META_KEY
                + ") DO UPDATE SET " + LocalSchema.META_VALUE + " = excluded." + LocalSchema.META_VALUE,
                List.of(LocalSchema.META_SCHEMA_VERSION, String.valueOf(version)));
    }

    /**
     * Runs several store operations as ONE transaction: all of them land or
     * none does (TRS Sec. 8.3). Operations inside may call {@link #put} and
     * {@link #delete} freely; their own transactions join this one.
     *
     * @param <T> the work's result type
     * @param body the work
     * @return the work's result
     * @throws StoreException if any operation fails; nothing is written then
     */
    public <T> T transaction(final SqlDatabase.TransactionBody<T> body) throws StoreException {
        return database.inTransaction(body);
    }

    /**
     * Writes a record, sealing its payload.
     *
     * @param kind the record's table
     * @param id the row identifier, or null to mint a fresh UUID
     * @param plaintext the record content
     * @param naturalKey the blind-index input, or null when the kind has none
     * @return the row identifier written
     * @throws StoreException on low storage, engine failure, or a missing tag
     */
    public String put(final RecordKind kind, final String id, final byte[] plaintext,
            final byte[] naturalKey) throws StoreException {
        capacity.requireRoomToWrite();
        if (kind.isLookedUpByTag() && naturalKey == null) {
            throw new StoreException(StoreException.Reason.ENGINE,
                    kind + " is looked up by tag and needs a natural key");
        }
        final String rowId = id == null ? UUID.randomUUID().toString() : id;
        final byte[] sealed = codec.seal(kind, rowId, plaintext);
        final byte[] tag = naturalKey == null ? null : codec.lookupTag(kind, naturalKey);
        final long now = clock.nowUtcMillis();

        return database.inTransaction(() -> {
            database.execute("INSERT INTO " + kind.table() + " (" + LocalSchema.COLUMN_ID + ", "
                    + LocalSchema.COLUMN_PAYLOAD + ", " + LocalSchema.COLUMN_LOOKUP_TAG + ", "
                    + LocalSchema.COLUMN_CREATED_AT + ", " + LocalSchema.COLUMN_UPDATED_AT + ", "
                    + LocalSchema.COLUMN_PAYLOAD_VERSION + ") VALUES (?, ?, ?, ?, ?, ?) "
                    + "ON CONFLICT(" + LocalSchema.COLUMN_ID + ") DO UPDATE SET "
                    + LocalSchema.COLUMN_PAYLOAD + " = excluded." + LocalSchema.COLUMN_PAYLOAD + ", "
                    + LocalSchema.COLUMN_LOOKUP_TAG + " = excluded." + LocalSchema.COLUMN_LOOKUP_TAG + ", "
                    + LocalSchema.COLUMN_UPDATED_AT + " = excluded." + LocalSchema.COLUMN_UPDATED_AT,
                    java.util.Arrays.asList(rowId, sealed, tag, now, now, LocalSchema.VERSION));
            return rowId;
        });
    }

    /**
     * Reads and opens a record.
     *
     * <p>Absence is an {@link Optional#empty()}, not a null and not a
     * zero-length array: "no such row" and "a row whose content is empty" are
     * different answers, and a caller that cannot tell them apart will
     * eventually treat a wiped profile as an empty one.
     *
     * @param kind the record's table
     * @param id the row identifier
     * @return the record content, or empty when no such row exists
     * @throws StoreException if the row exists but cannot be opened
     */
    public Optional<byte[]> get(final RecordKind kind, final String id) throws StoreException {
        final List<Map<String, Object>> rows = database.query(
                "SELECT " + LocalSchema.COLUMN_PAYLOAD + " FROM " + kind.table()
                        + " WHERE " + LocalSchema.COLUMN_ID + " = ?", List.of(id));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(codec.open(kind, id, (byte[]) rows.get(0).get(LocalSchema.COLUMN_PAYLOAD)));
    }

    /**
     * Writes THE record for a natural key: replaces the existing row when
     * one carries this key's tag, inserts a fresh one otherwise. The upsert
     * the per-peer kinds need - without it every state change would mint a
     * new row and "the peer's record" would quietly become "the oldest of
     * several" (WP-04 S3b). The row identifier stays an opaque UUID either
     * way (TRS-LOC-010): deriving one from the key would hand anyone with a
     * candidate identifier an unkeyed way to confirm the relationship that
     * the blind index exists to prevent.
     *
     * @param kind the record's table
     * @param plaintext the record content
     * @param naturalKey the blind-index input
     * @return the row identifier written
     * @throws StoreException on low storage or engine failure
     */
    public String putByNaturalKey(final RecordKind kind, final byte[] plaintext,
            final byte[] naturalKey) throws StoreException {
        final List<Map<String, Object>> rows = database.query(
                "SELECT " + LocalSchema.COLUMN_ID + " FROM " + kind.table()
                        + " WHERE " + LocalSchema.COLUMN_LOOKUP_TAG + " = ?",
                List.of((Object) codec.lookupTag(kind, naturalKey)));
        final String id = rows.isEmpty()
                ? null : String.valueOf(rows.get(0).get(LocalSchema.COLUMN_ID));
        return put(kind, id, plaintext, naturalKey);
    }

    /**
     * Finds records by their blind-index tag.
     *
     * @param kind the record's table
     * @param naturalKey the value being looked up
     * @return every matching record's content, opened
     * @throws StoreException on engine failure or an unopenable row
     */
    public List<byte[]> findByNaturalKey(final RecordKind kind, final byte[] naturalKey)
            throws StoreException {
        final List<Map<String, Object>> rows = database.query(
                "SELECT " + LocalSchema.COLUMN_ID + ", " + LocalSchema.COLUMN_PAYLOAD + " FROM "
                        + kind.table() + " WHERE " + LocalSchema.COLUMN_LOOKUP_TAG + " = ?",
                List.of((Object) codec.lookupTag(kind, naturalKey)));
        final List<byte[]> out = new ArrayList<>(rows.size());
        for (final Map<String, Object> row : rows) {
            out.add(codec.open(kind, String.valueOf(row.get(LocalSchema.COLUMN_ID)),
                    (byte[]) row.get(LocalSchema.COLUMN_PAYLOAD)));
        }
        return out;
    }

    /**
     * Finds records by their blind-index tag, with their row identifiers, so
     * a caller can update or delete the rows it found.
     *
     * @param kind the record's table
     * @param naturalKey the value being looked up
     * @return every matching row, opened
     * @throws StoreException on engine failure or an unopenable row
     */
    public List<Entry> findEntriesByNaturalKey(final RecordKind kind, final byte[] naturalKey)
            throws StoreException {
        final List<Map<String, Object>> rows = database.query(
                "SELECT " + LocalSchema.COLUMN_ID + ", " + LocalSchema.COLUMN_PAYLOAD + " FROM "
                        + kind.table() + " WHERE " + LocalSchema.COLUMN_LOOKUP_TAG + " = ?",
                List.of((Object) codec.lookupTag(kind, naturalKey)));
        final List<Entry> out = new ArrayList<>(rows.size());
        for (final Map<String, Object> row : rows) {
            final String id = String.valueOf(row.get(LocalSchema.COLUMN_ID));
            out.add(new Entry(id, codec.open(kind, id, (byte[]) row.get(LocalSchema.COLUMN_PAYLOAD))));
        }
        return out;
    }

    /** One row of a kind: its identifier and its opened content. */
    public static final class Entry {

        private final String id;
        private final byte[] content;

        private Entry(final String id, final byte[] content) {
            this.id = id;
            this.content = content;
        }

        /**
         * The row identifier.
         *
         * @return the opaque id
         */
        public String id() {
            return id;
        }

        /**
         * The opened record content.
         *
         * @return a copy of the plaintext
         */
        public byte[] content() {
            return content.clone();
        }
    }

    /**
     * Reads and opens every row of a kind, in identifier order.
     *
     * <p>Added for the recovery export, which has to enumerate what it is
     * exporting rather than ask for identifiers it already knows. Ordering by
     * the identifier makes the export deterministic for a given store, which is
     * what lets a test compare two exports of the same content.
     *
     * @param kind the record's table
     * @return every row, opened
     * @throws StoreException on engine failure or an unopenable row
     */
    public List<Entry> allOf(final RecordKind kind) throws StoreException {
        final List<Map<String, Object>> rows = database.query(
                "SELECT " + LocalSchema.COLUMN_ID + ", " + LocalSchema.COLUMN_PAYLOAD + " FROM "
                        + kind.table() + " ORDER BY " + LocalSchema.COLUMN_ID, List.of());
        final List<Entry> out = new ArrayList<>(rows.size());
        for (final Map<String, Object> row : rows) {
            final String id = String.valueOf(row.get(LocalSchema.COLUMN_ID));
            out.add(new Entry(id, codec.open(kind, id, (byte[]) row.get(LocalSchema.COLUMN_PAYLOAD))));
        }
        return out;
    }

    /**
     * Deletes a record.
     *
     * @param kind the record's table
     * @param id the row identifier
     * @throws StoreException on engine failure
     */
    public void delete(final RecordKind kind, final String id) throws StoreException {
        database.execute("DELETE FROM " + kind.table() + " WHERE " + LocalSchema.COLUMN_ID + " = ?",
                List.of(id));
    }

    /**
     * Deletes every row of every record table, in one transaction.
     *
     * <p>The content stage of the keys-first wipe. It runs AFTER the key
     * material is gone, at which point everything it deletes is already
     * ciphertext nobody can open - which is why a failure here degrades the
     * wipe's tidiness and not its guarantee (threat model Sec. 0.2).
     *
     * @throws StoreException on engine failure
     */
    public void deleteEverything() throws StoreException {
        database.inTransaction(() -> {
            for (final RecordKind kind : RecordKind.values()) {
                database.execute("DELETE FROM " + kind.table());
            }
            return null;
        });
    }

    /**
     * Destroys the store's derived keys.
     *
     * <p>The key-material stage: after this the rows are unopenable whatever
     * happens to the file.
     */
    public void destroyKeys() {
        codec.destroy();
    }

    /**
     * Counts rows of a kind, without opening any of them.
     *
     * @param kind the record's table
     * @return the row count
     * @throws StoreException on engine failure
     */
    public int count(final RecordKind kind) throws StoreException {
        final List<Map<String, Object>> rows =
                database.query("SELECT COUNT(*) AS n FROM " + kind.table(), List.of());
        return Integer.parseInt(String.valueOf(rows.get(0).get("n")));
    }

    /**
     * Encodes text as a record payload.
     *
     * @param text the content
     * @return its UTF-8 bytes
     */
    public static byte[] payloadOf(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
