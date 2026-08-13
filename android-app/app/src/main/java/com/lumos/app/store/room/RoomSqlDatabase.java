/*
 * Lumos - android-app/app / SqlDatabase over Room's SupportSQLiteDatabase.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-AND-004, SRS-DAT-002, TRS-LOC-003
 * ADRs: ADR-003
 * Threading: serial-executor
 * Notes: The adapter that lets one generic store address seven tables while
 * Room owns the database underneath. It replaces AndroidSqlDatabase as the
 * production path; that class remains for the case where the store must run
 * without Room at all, and its tests still cover the same seam.
 *
 * Transactions delegate to Room's runInTransaction, so Room's own invariants -
 * including its migration bookkeeping - are respected rather than worked
 * around with raw BEGIN/COMMIT.
 */

package com.lumos.app.store.room;

import android.database.Cursor;
import androidx.sqlite.db.SupportSQLiteDatabase;
import com.lumos.app.store.SqlDatabase;
import com.lumos.app.store.StoreException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The SqlDatabase seam, implemented over Room. */
public final class RoomSqlDatabase implements SqlDatabase {

    private final LumosRoomDatabase room;

    private RoomSqlDatabase(final LumosRoomDatabase room) {
        this.room = room;
    }

    /**
     * Wraps an open Room database.
     *
     * @param room the database
     * @return the seam implementation
     */
    public static RoomSqlDatabase wrapping(final LumosRoomDatabase room) {
        return new RoomSqlDatabase(room);
    }

    /**
     * The underlying Room database, for the typed DAO path.
     *
     * @return the database
     */
    public LumosRoomDatabase room() {
        return room;
    }

    @Override
    public void execute(final String sql) throws StoreException {
        try {
            writable().execSQL(sql);
        } catch (android.database.SQLException e) {
            throw new StoreException(StoreException.Reason.ENGINE, "statement failed", e);
        }
    }

    @Override
    public void execute(final String sql, final List<Object> arguments) throws StoreException {
        try {
            writable().execSQL(sql, arguments.toArray());
        } catch (android.database.SQLException e) {
            throw new StoreException(StoreException.Reason.ENGINE, "statement failed", e);
        }
    }

    @Override
    public List<Map<String, Object>> query(final String sql, final List<Object> arguments)
            throws StoreException {
        try (Cursor cursor = writable().query(sql, arguments.toArray())) {
            final List<Map<String, Object>> rows = new ArrayList<>();
            while (cursor.moveToNext()) {
                final Map<String, Object> row = new LinkedHashMap<>();
                for (int c = 0; c < cursor.getColumnCount(); c++) {
                    final String name = cursor.getColumnName(c);
                    if (cursor.getType(c) == Cursor.FIELD_TYPE_BLOB) {
                        row.put(name, cursor.getBlob(c));
                    } else {
                        row.put(name, cursor.getString(c));
                    }
                }
                rows.add(row);
            }
            return rows;
        } catch (android.database.SQLException e) {
            throw new StoreException(StoreException.Reason.ENGINE, "query failed", e);
        }
    }

    /**
     * Carries a checked StoreException out through Room's Runnable-shaped
     * transaction API, which cannot declare one.
     *
     * <p>A dedicated type rather than a general RuntimeException: this must be
     * caught precisely, and catching something broader would also swallow a
     * genuine defect thrown by the body.
     */
    private static final class TransactionAbort extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /** Not transient: StoreException is serializable, and a deserialised
         * carrier with a null payload would lose the only thing it carries. */
        private final StoreException carried;

        TransactionAbort(final StoreException carried) {
            super(carried);
            this.carried = carried;
        }
    }

    @Override
    public <T> T inTransaction(final TransactionBody<T> body) throws StoreException {
        // Room's own transaction bookkeeping, not raw BEGIN/COMMIT: a
        // hand-rolled transaction here would sit outside Room's invariants.
        //
        // runInTransaction takes a Runnable and RETHROWS whatever escapes it,
        // so the checked failure has to ride out inside an unchecked carrier
        // and be unwrapped here. The first version of this method collected the
        // exception in a list and read it after the call - which never ran,
        // because the carrier propagated first. The emulator job caught it on
        // its first execution, which is precisely why that job exists.
        final List<T> result = new ArrayList<>(1);
        try {
            room.runInTransaction(() -> {
                try {
                    result.add(body.run());
                } catch (StoreException e) {
                    throw new TransactionAbort(e);
                }
            });
        } catch (TransactionAbort abort) {
            throw abort.carried;
        }
        return result.isEmpty() ? null : result.get(0);
    }

    @Override
    public void close() {
        room.close();
    }

    /**
     * The writable handle Room manages.
     *
     * @return the support database
     */
    private SupportSQLiteDatabase writable() {
        return room.getOpenHelper().getWritableDatabase();
    }
}
