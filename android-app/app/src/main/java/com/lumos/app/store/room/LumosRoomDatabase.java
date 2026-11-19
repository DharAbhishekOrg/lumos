/*
 * Lumos - android-app/app / The Room database, and the seam it implements.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-AND-004, SRS-DAT-002, TRS-LOC-003, SRS-DAT-012
 * ADRs: ADR-003
 * Threading: serial-executor
 * Notes: Room owns the database, the schema and the migrations; the generic
 * store reaches it through RoomSqlDatabase, which implements the SqlDatabase
 * seam over Room's SupportSQLiteDatabase. That is the owner's 2026-08-14
 * ruling made concrete - the seam stays the cross-platform contract, Room is
 * the Android production store behind it.
 *
 * exportSchema is ON. It writes the schema Room derived from the entities into
 * android-app/app/schemas, which RoomSchemaParityTest compares against the
 * canonical LocalSchema DDL at build time, on any host, with no device. That
 * comparison is the whole reason adopting Room did not fork the schema: Room's
 * own compiler output is the cross-check.
 *
 * The database is created by an explicit CALLBACK running LocalSchema's own
 * migration statements, NOT by letting Room create tables from the entities.
 * The direction matters: the canonical DDL is the source and Room is the
 * consumer, so a divergence shows up as a parity failure rather than as Room
 * quietly winning.
 */

package com.lumos.app.store.room;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;
import com.lumos.app.store.LocalSchema;

/** The Room database holding every sealed-record table. */
@Database(
        entities = {
            SealedRecordEntity.IdentityKeyRecordRow.class,
            SealedRecordEntity.RecoveryRecordRow.class,
            SealedRecordEntity.LocalProfileRow.class,
            SealedRecordEntity.ProfileFieldPolicyRow.class,
            SealedRecordEntity.ProfileMediaRow.class,
            SealedRecordEntity.BlockEntryRow.class,
            SealedRecordEntity.DiagnosticEventRow.class,
            SealedRecordEntity.ConsentRecordRow.class,
            SealedRecordEntity.DiscoveryVisibilityRow.class,
            SealedRecordEntity.AppLockRow.class,
        },
        version = LocalSchema.VERSION,
        exportSchema = true)
public abstract class LumosRoomDatabase extends RoomDatabase {

    /** The database file name inside the application's private directory. */
    public static final String DATABASE_FILE = "lumos-local.db";

    /**
     * Typed access to the profile table.
     *
     * @return the DAO
     */
    public abstract ProfileDao profileDao();

    /**
     * Room's version of the canonical 1-to-2 step (WP-02 slice S4c).
     *
     * <p>It executes {@link LocalSchema#migrationToVersion2()} rather than SQL
     * of its own, and then advances the canonical version in the meta table.
     * Room tracks its own version in {@code room_master_table}; the meta row is
     * what the cross-platform seam and the iOS store read, so both have to move
     * or an upgraded database reports version 1 to everything except Room.
     */
    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull final SupportSQLiteDatabase database) {
            for (final String sql : LocalSchema.migrationToVersion2()) {
                database.execSQL(sql);
            }
            database.execSQL("INSERT OR REPLACE INTO " + LocalSchema.TABLE_META + " ("
                    + LocalSchema.META_KEY + ", " + LocalSchema.META_VALUE + ") VALUES (?, ?)",
                    new Object[] {LocalSchema.META_SCHEMA_VERSION, String.valueOf(2)});
        }
    };

    /**
     * Room's version of the canonical 2-to-3 step (WP-03 slice S5b), the same
     * shape as 1-to-2: the canonical DDL executes, then the meta row advances
     * so the cross-platform seam and Room agree on the version.
     */
    public static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull final SupportSQLiteDatabase database) {
            for (final String sql : LocalSchema.migrationToVersion3()) {
                database.execSQL(sql);
            }
            database.execSQL("INSERT OR REPLACE INTO " + LocalSchema.TABLE_META + " ("
                    + LocalSchema.META_KEY + ", " + LocalSchema.META_VALUE + ") VALUES (?, ?)",
                    new Object[] {LocalSchema.META_SCHEMA_VERSION, String.valueOf(3)});
        }
    };

    /**
     * Opens the database in the application's private storage.
     *
     * @param context the application context
     * @return the database
     */
    public static LumosRoomDatabase open(final Context context) {
        return Room.databaseBuilder(context, LumosRoomDatabase.class, DATABASE_FILE)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .addCallback(new Callback() {
                    @Override
                    public void onCreate(@NonNull final SupportSQLiteDatabase database) {
                        // The meta table is not a Room entity: it holds the
                        // canonical schema version the cross-platform store
                        // reads, and Room tracks its own version separately in
                        // room_master_table. Both exist on purpose - Room
                        // manages Room's migrations, LocalSchema's version is
                        // what the seam and the iOS store agree on.
                        database.execSQL("CREATE TABLE IF NOT EXISTS " + LocalSchema.TABLE_META + " ("
                                + LocalSchema.META_KEY + " TEXT PRIMARY KEY NOT NULL, "
                                + LocalSchema.META_VALUE + " TEXT NOT NULL)");
                        database.execSQL("INSERT OR REPLACE INTO " + LocalSchema.TABLE_META + " ("
                                + LocalSchema.META_KEY + ", " + LocalSchema.META_VALUE + ") VALUES (?, ?)",
                                new Object[] {LocalSchema.META_SCHEMA_VERSION,
                                    String.valueOf(LocalSchema.VERSION)});
                    }
                })
                // No fallbackToDestructiveMigration, ever. Destroying user data
                // to resolve a schema mismatch is the opposite of forward-only
                // (TRS-LOC-003) and would silently discard the profile this
                // work package exists to protect.
                .build();
    }
}
