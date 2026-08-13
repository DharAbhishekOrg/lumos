/*
 * Lumos - android-app/app / Room on a real Android runtime.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-AND-004, SRS-DAT-002, TRS-LOC-002, TRS-LOC-003, SRS-PRO-001
 * ADRs: ADR-003
 * Threading: any
 * Notes: The tests that need an actual device or emulator, and therefore the
 * ones the JVM suite cannot stand in for: Room creating its schema, the
 * generated DAO executing its compile-verified queries, and the generic seam
 * addressing all seven tables through Room. Everything with a decision in it is
 * still tested on the JVM against SQLite; these prove the Room half is real.
 *
 * They run in a path-filtered emulator job (owner ruling 2026-08-14). If that
 * job is ever skipped, these identifiers simply do not appear in the executed
 * set - and the tightened linked-test-did-not-run gate treats a runner that
 * produced nothing as a skipped job rather than as drift, which is exactly the
 * behaviour this arrangement needs.
 */

package com.lumos.app.store.room;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.lumos.app.data.DataKeyHierarchy;
import com.lumos.app.keystore.SoftwareKekStore;
import com.lumos.app.store.LocalSchema;
import com.lumos.app.store.LocalStore;
import com.lumos.app.store.RecordKind;
import com.lumos.app.store.StorageCapacity;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Room, its DAO and the seam over it, on a real Android runtime. */
@RunWith(AndroidJUnit4.class)
public class RoomStoreInstrumentedTest {

    private LumosRoomDatabase room;
    private RoomSqlDatabase seam;

    @Before
    public void openDatabase() {
        final Context context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase(LumosRoomDatabase.DATABASE_FILE);
        room = LumosRoomDatabase.open(context);
        seam = RoomSqlDatabase.wrapping(room);
    }

    @After
    public void closeDatabase() {
        room.close();
    }

    @Test
    public void roomCreatesEveryCanonicalTableWithTheCanonicalColumns() throws Exception {
        for (final RecordKind kind : RecordKind.values()) {
            final List<Map<String, Object>> columns =
                    seam.query("PRAGMA table_info(" + kind.table() + ")", List.of());
            assertEquals(kind.table() + " must have the canonical column count", 6, columns.size());

            final StringBuilder names = new StringBuilder();
            for (final Map<String, Object> column : columns) {
                names.append(column.get("name")).append(' ');
            }
            final String actual = names.toString().trim();
            assertEquals(kind.table() + " column order",
                    String.join(" ", LocalSchema.COLUMN_ID, LocalSchema.COLUMN_PAYLOAD,
                            LocalSchema.COLUMN_LOOKUP_TAG, LocalSchema.COLUMN_CREATED_AT,
                            LocalSchema.COLUMN_UPDATED_AT, LocalSchema.COLUMN_PAYLOAD_VERSION),
                    actual);
        }
    }

    @Test
    public void roomRecordsTheCanonicalSchemaVersion() throws Exception {
        final List<Map<String, Object>> rows = seam.query(
                "SELECT " + LocalSchema.META_VALUE + " FROM " + LocalSchema.TABLE_META
                        + " WHERE " + LocalSchema.META_KEY + " = ?",
                List.of(LocalSchema.META_SCHEMA_VERSION));
        assertEquals("Room's onCreate must write the version the seam and iOS agree on",
                String.valueOf(LocalSchema.VERSION),
                String.valueOf(rows.get(0).get(LocalSchema.META_VALUE)));
    }

    @Test
    public void theGeneratedDaoRoundTripsASealedRow() {
        final ProfileDao dao = room.profileDao();
        final SealedRecordEntity.LocalProfileRow row = new SealedRecordEntity.LocalProfileRow();
        row.id = "row-1";
        row.payload = new byte[] {1, 2, 3, 4};
        row.createdAt = 1_700_000_000_000L;
        row.updatedAt = 1_700_000_000_000L;
        row.payloadVersion = LocalSchema.VERSION;

        dao.upsert(row);
        assertEquals(1, dao.count());

        final SealedRecordEntity.LocalProfileRow read = dao.byId("row-1");
        assertNotNull(read);
        assertArrayEquals(new byte[] {1, 2, 3, 4}, read.payload);
        assertEquals(1_700_000_000_000L, read.createdAt);

        dao.deleteById("row-1");
        assertNull(dao.byId("row-1"));
        assertEquals(0, dao.count());
    }

    @Test
    public void theDaoUpsertReplacesRatherThanDuplicating() {
        final ProfileDao dao = room.profileDao();
        final SealedRecordEntity.LocalProfileRow row = new SealedRecordEntity.LocalProfileRow();
        row.id = "row-1";
        row.payload = new byte[] {1};
        row.payloadVersion = LocalSchema.VERSION;
        dao.upsert(row);

        row.payload = new byte[] {2};
        dao.upsert(row);

        assertEquals("an update must not insert a second row", 1, dao.count());
        assertArrayEquals(new byte[] {2}, dao.byId("row-1").payload);
    }

    @Test
    public void theGenericStoreWorksOverRoomForEveryKind() throws Exception {
        final byte[] key = DataKeyHierarchy.create(SoftwareKekStore.create())
                .deriveKey(DataKeyHierarchy.DataDomain.DATABASE);
        final StorageCapacity roomy = () -> Long.MAX_VALUE;
        final LocalStore store = LocalStore.open(seam, key, roomy, () -> 1_700_000_000_000L);

        for (final RecordKind kind : RecordKind.values()) {
            final byte[] payload = LocalStore.payloadOf("content for " + kind);
            final byte[] natural = kind.isLookedUpByTag()
                    ? "peer".getBytes(java.nio.charset.StandardCharsets.UTF_8) : null;
            final String id = store.put(kind, null, payload, natural);
            assertArrayEquals(kind + " must round-trip through Room",
                    payload, store.get(kind, id).orElseThrow());
        }
    }

    @Test
    public void aFailedTransactionOverRoomLeavesNothing() throws Exception {
        final byte[] key = DataKeyHierarchy.create(SoftwareKekStore.create())
                .deriveKey(DataKeyHierarchy.DataDomain.DATABASE);
        final LocalStore store = LocalStore.open(seam, key, () -> Long.MAX_VALUE, () -> 0L);
        store.put(RecordKind.LOCAL_PROFILE, null, LocalStore.payloadOf("first"), null);

        boolean threw = false;
        try {
            seam.inTransaction(() -> {
                seam.execute("INSERT INTO " + RecordKind.LOCAL_PROFILE.table() + " ("
                        + LocalSchema.COLUMN_ID + ", " + LocalSchema.COLUMN_PAYLOAD + ", "
                        + LocalSchema.COLUMN_CREATED_AT + ", " + LocalSchema.COLUMN_UPDATED_AT + ", "
                        + LocalSchema.COLUMN_PAYLOAD_VERSION + ") VALUES (?, ?, ?, ?, ?)",
                        List.of("second", new byte[] {9}, 0L, 0L, LocalSchema.VERSION));
                throw new com.lumos.app.store.StoreException(
                        com.lumos.app.store.StoreException.Reason.ENGINE, "simulated crash");
            });
        } catch (com.lumos.app.store.StoreException expected) {
            threw = true;
        }
        assertTrue("the transaction body's failure must surface", threw);
        assertEquals("crash consistency over Room's transaction API",
                1, store.count(RecordKind.LOCAL_PROFILE));
    }
}
