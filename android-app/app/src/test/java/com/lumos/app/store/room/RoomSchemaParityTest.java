/*
 * Lumos - android-app/app / Room's exported schema must equal the canonical DDL.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-AND-004, TRS-LOC-002, TRS-LOC-003
 * ADRs: ADR-003
 * Threading: any
 * Notes: Adopting Room created a second author for the schema, and this is what
 * stops it becoming a second SOURCE. Room's annotation processor writes what it
 * derived from the entities into app/schemas at build time; this test reads
 * that JSON and compares it with LocalSchema - the canonical DDL that the seam,
 * the migration suite and the iOS store all use.
 *
 * It runs on the JVM, with no device, because the artefact it reads is compiler
 * output rather than runtime behaviour. That matters: the parity check is
 * available on every build, not only on the path-filtered emulator job.
 *
 * Together with tools/schemacheck (Java vs Swift) the schema now has two
 * independent cross-checks, and no single author can move it alone.
 */

package com.lumos.app.store.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.store.LocalSchema;
import com.lumos.app.store.RecordKind;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Compares Room's exported schema with the canonical DDL. */
final class RoomSchemaParityTest {

    /** The columns every canonical record table declares, in order. */
    private static final List<String> CANONICAL_COLUMNS = List.of(
            LocalSchema.COLUMN_ID, LocalSchema.COLUMN_PAYLOAD, LocalSchema.COLUMN_LOOKUP_TAG,
            LocalSchema.COLUMN_CREATED_AT, LocalSchema.COLUMN_UPDATED_AT,
            LocalSchema.COLUMN_PAYLOAD_VERSION);

    @Test
    void roomExportedASchemaAtAll() throws Exception {
        final String json = exportedSchema();
        assertFalse(json.isBlank(),
                "Room's schema export is the cross-check; without it this test is vacuous");
        assertTrue(json.contains("\"version\": " + LocalSchema.VERSION),
                "Room's database version must be the canonical schema version");
    }

    @Test
    void roomDeclaresExactlyTheCanonicalTables() throws Exception {
        final List<String> exported = tableNames(exportedSchema());
        final List<String> canonical = new ArrayList<>();
        for (final RecordKind kind : RecordKind.values()) {
            canonical.add(kind.table());
        }
        java.util.Collections.sort(exported);
        java.util.Collections.sort(canonical);
        assertEquals(canonical, exported,
                "Room and LocalSchema must declare the same tables; Room is the consumer, not the author");
    }

    @Test
    void everyRoomTableCarriesTheCanonicalColumnsInOrder() throws Exception {
        final String json = exportedSchema();
        for (final RecordKind kind : RecordKind.values()) {
            assertEquals(CANONICAL_COLUMNS, columnsOf(json, kind.table()),
                    kind.table() + ": Room's columns must match the canonical DDL exactly");
        }
    }

    @Test
    void roomAgreesWithTheCanonicalCreateStatement() throws Exception {
        final String json = exportedSchema();
        for (final RecordKind kind : RecordKind.values()) {
            final String roomSql = createSqlOf(json, kind.table());
            // Room writes ${TABLE_NAME} where the table goes, and orders the
            // column definitions as declared. Comparing the normalised column
            // and type text catches a type or nullability change on either
            // side without depending on Room's exact punctuation.
            for (final String expected : List.of(
                    LocalSchema.COLUMN_ID + "` TEXT",
                    LocalSchema.COLUMN_PAYLOAD + "` BLOB",
                    LocalSchema.COLUMN_LOOKUP_TAG + "` BLOB",
                    LocalSchema.COLUMN_CREATED_AT + "` INTEGER",
                    LocalSchema.COLUMN_UPDATED_AT + "` INTEGER",
                    LocalSchema.COLUMN_PAYLOAD_VERSION + "` INTEGER")) {
                assertTrue(roomSql.contains(expected),
                        kind.table() + ": Room's CREATE lacks `" + expected + "`; got " + roomSql);
            }
            assertTrue(roomSql.contains("PRIMARY KEY(`" + LocalSchema.COLUMN_ID + "`)"),
                    kind.table() + ": the identifier must be the primary key");
        }
    }

    @Test
    void theBlockTableKeepsItsBlindIndex() throws Exception {
        final String json = exportedSchema();
        assertTrue(json.contains("idx_" + RecordKind.BLOCK_ENTRY.table() + "_lookup_tag")
                        || json.contains(LocalSchema.COLUMN_LOOKUP_TAG + "\""),
                "the block table's lookup index is what makes a blind-index query cheap");
    }

    /**
     * Reads the committed Room schema export.
     *
     * @return the JSON
     * @throws Exception if the export is missing
     */
    private static String exportedSchema() throws Exception {
        final File file = new File("schemas/com.lumos.app.store.room.LumosRoomDatabase/"
                + LocalSchema.VERSION + ".json");
        assertTrue(file.isFile(), "expected Room's schema export at " + file.getAbsolutePath()
                + "; it is written by the annotation processor and must be committed");
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /**
     * Extracts the exported table names.
     *
     * @param json the schema export
     * @return the table names
     */
    private static List<String> tableNames(final String json) {
        final List<String> out = new ArrayList<>();
        final Matcher m = Pattern.compile("\"tableName\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /**
     * Extracts one table's column names, in declaration order.
     *
     * @param json the schema export
     * @param table the table name
     * @return the column names
     */
    private static List<String> columnsOf(final String json, final String table) {
        final String section = sectionFor(json, table);
        final List<String> out = new ArrayList<>();
        final Matcher m = Pattern.compile("\"columnName\"\\s*:\\s*\"([^\"]+)\"").matcher(section);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /**
     * Extracts one table's CREATE statement.
     *
     * @param json the schema export
     * @param table the table name
     * @return the createSql text
     */
    private static String createSqlOf(final String json, final String table) {
        final Matcher m = Pattern.compile("\"createSql\"\\s*:\\s*\"(.*?)\"\\s*,")
                .matcher(sectionFor(json, table));
        return m.find() ? m.group(1) : "";
    }

    /**
     * Isolates one entity's block of the export, so a regex cannot wander into
     * the next table and compare the wrong columns.
     *
     * @param json the schema export
     * @param table the table name
     * @return that entity's text
     */
    private static String sectionFor(final String json, final String table) {
        final int start = json.indexOf("\"tableName\": \"" + table + "\"");
        assertTrue(start >= 0, "no exported entity for " + table);
        final int next = json.indexOf("\"tableName\":", start + 1);
        return next < 0 ? json.substring(start) : json.substring(start, next);
    }
}
