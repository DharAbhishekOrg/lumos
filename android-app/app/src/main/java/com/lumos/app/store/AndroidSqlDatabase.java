/*
 * Lumos - android-app/app / The platform SQLite adapter.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-AND-004, SRS-DAT-002, TRS-LOC-003
 * ADRs: ADR-003
 * Threading: serial-executor
 * Notes: The thin end of the seam, and deliberately the only class in the store
 * package that cannot run on a JVM. Everything with a decision in it - the DDL,
 * the migration ladder, the sealing, the low-storage guard - lives on the other
 * side and is executed in CI against a real SQLite engine. What remains here is
 * argument marshalling and android.database.sqlite's transaction API, so there
 * is very little room for this file to be wrong in a way the tested side would
 * not also be wrong.
 *
 * The database file itself carries no application-layer secret: every sensitive
 * value is already sealed by SealedRecordCodec before it arrives. Platform
 * data protection (SRS-DAT-002's other half) comes from the file living in the
 * app's private storage, which android:allowBackup=false and the backup rules
 * keep out of every OS backup (ADR-003, TRS-LOC-009).
 */

package com.lumos.app.store;

import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SqlDatabase over android.database.sqlite. */
public final class AndroidSqlDatabase implements SqlDatabase {

    /** The database file name inside the application's private directory. */
    public static final String DATABASE_FILE = "lumos-local.db";

    private final SQLiteDatabase database;

    private AndroidSqlDatabase(final SQLiteDatabase database) {
        this.database = database;
    }

    /**
     * Opens or creates the database in the application's private storage.
     *
     * @param context the application context
     * @return the adapter
     */
    public static AndroidSqlDatabase open(final Context context) {
        final File file = context.getDatabasePath(DATABASE_FILE);
        final File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("cannot create the database directory");
        }
        return new AndroidSqlDatabase(
                SQLiteDatabase.openOrCreateDatabase(file, null));
    }

    /**
     * The database file, for the wipe's content stage and capacity checks.
     *
     * @param context the application context
     * @return the file, which need not exist yet
     */
    public static File databaseFile(final Context context) {
        return context.getDatabasePath(DATABASE_FILE);
    }

    @Override
    public void execute(final String sql) throws StoreException {
        try {
            database.execSQL(sql);
        } catch (SQLException e) {
            throw new StoreException(StoreException.Reason.ENGINE, "statement failed", e);
        }
    }

    @Override
    public void execute(final String sql, final List<Object> arguments) throws StoreException {
        try {
            database.execSQL(sql, arguments.toArray());
        } catch (SQLException e) {
            throw new StoreException(StoreException.Reason.ENGINE, "statement failed", e);
        }
    }

    @Override
    public List<Map<String, Object>> query(final String sql, final List<Object> arguments)
            throws StoreException {
        final String[] bound = new String[arguments.size()];
        for (int i = 0; i < arguments.size(); i++) {
            bound[i] = String.valueOf(arguments.get(i));
        }
        try (Cursor cursor = database.rawQuery(sql, bound)) {
            final List<Map<String, Object>> rows = new ArrayList<>();
            while (cursor.moveToNext()) {
                rows.add(readRow(cursor));
            }
            return rows;
        } catch (SQLException e) {
            throw new StoreException(StoreException.Reason.ENGINE, "query failed", e);
        }
    }

    /**
     * Materialises one cursor row.
     *
     * @param cursor a cursor positioned on a row
     * @return the row as a column-name-to-value map
     */
    private static Map<String, Object> readRow(final Cursor cursor) {
        final Map<String, Object> row = new LinkedHashMap<>();
        for (int c = 0; c < cursor.getColumnCount(); c++) {
            final String name = cursor.getColumnName(c);
            if (cursor.getType(c) == Cursor.FIELD_TYPE_BLOB) {
                row.put(name, cursor.getBlob(c));
            } else {
                row.put(name, cursor.getString(c));
            }
        }
        return row;
    }

    @Override
    public <T> T inTransaction(final TransactionBody<T> body) throws StoreException {
        database.beginTransaction();
        try {
            final T result = body.run();
            database.setTransactionSuccessful();
            return result;
        } finally {
            // endTransaction rolls back unless setTransactionSuccessful ran, so
            // an exception from the body leaves nothing behind.
            database.endTransaction();
        }
    }

    @Override
    public void close() {
        database.close();
    }
}
