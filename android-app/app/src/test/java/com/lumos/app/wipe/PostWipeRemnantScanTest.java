/*
 * Lumos - android-app/app / Post-wipe remnant scan over the real files.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-NFR-PRI-012, SRS-SAF-009, SRS-SAF-010
 * ADRs: ADR-003
 * Threading: any
 * Notes: The WP-02 closeout said plainly what was missing: EmergencyWipeTest
 * proves surviving rows CANNOT BE OPENED, which is crypto-erasure, and that is
 * a different claim from "no remnants". This is the remnant half, owed to
 * SRS-NFR-PRI-012's own words - "storage scans after wipe show no remnants" -
 * and it reads the directory the way a forensic examiner would: every byte of
 * every file, WAL and journal included, not the store's API.
 *
 * The scanner proves it can fail before its pass is believed: a plaintext decoy
 * is planted, found, and removed. A scan that has only ever seen a clean
 * directory is indistinguishable from one that always returns clean - the same
 * negative-half rule as the vector completeness gate (WP-01 S4c).
 *
 * The documented limit stands and is not weakened here: SQLite free pages and
 * unerased flash blocks may hold CIPHERTEXT remnants (WipeCoverage, threat
 * model LT-14). What this test forbids is any PLAINTEXT remnant of a stored
 * value - which is exactly what the keys-first order plus the sealed-row
 * schema promise, so a failure here is a defect in one of them.
 */

package com.lumos.app.wipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.data.DataKeyHierarchy;
import com.lumos.app.keystore.SoftwareKekStore;
import com.lumos.app.safety.DeliberateConfirmation;
import com.lumos.app.store.JdbcSqlDatabase;
import com.lumos.app.store.LocalStore;
import com.lumos.app.store.RecordKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The scan SRS-NFR-PRI-012 names, over a wiped store's actual files. */
final class PostWipeRemnantScanTest {

    private static final String PHRASE = "erase everything";
    private static final long NOW = 1_763_500_000_000L;

    /** Values the privacy data map classifies as special category. */
    private static final List<String> SPECIAL_CATEGORY = List.of(
            "queer, and not looking to explain it",
            "gender identity: non-binary",
            "orientation: bisexual");

    @Test
    void aCompletedWipeLeavesNoPlaintextRemnantOfAnyStoredValue(@TempDir final Path directory)
            throws Exception {
        final Path file = directory.resolve("lumos-local.db");
        final SoftwareKekStore kek = SoftwareKekStore.create();
        final byte[] domainKey = DataKeyHierarchy.create(kek)
                .deriveKey(DataKeyHierarchy.DataDomain.DATABASE);
        final List<String> planted = plantedValues();

        try (JdbcSqlDatabase database = JdbcSqlDatabase.atPath(file.toString())) {
            final LocalStore store =
                    LocalStore.open(database, domainKey, () -> Long.MAX_VALUE, () -> NOW);
            writeEveryKind(store, planted);
            assertScannerCanActuallyFindARemnant(directory);

            final EmergencyWipe.Outcome outcome =
                    new EmergencyWipe(kek, store).execute(confirmation());
            assertTrue(outcome.cryptoErasureHolds(),
                    "the scan is only meaningful over a wipe whose key phase completed");
            for (final RecordKind kind : RecordKind.values()) {
                assertEquals(0, store.count(kind), kind + " survived the wipe");
            }
            // WAL and journal contents count as "the files" for this purpose;
            // fold them into the main database before the directory is read.
            database.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }

        final byte[] onDisk = readEveryByteIn(directory);
        assertTrue(onDisk.length > 0,
                "the scan must have real files to search, or it proves nothing");
        for (final String value : planted) {
            assertFalse(contains(onDisk, value.getBytes(StandardCharsets.UTF_8)),
                    "plaintext remnant found on disk after a completed wipe: " + value);
        }
    }

    /**
     * One recognisable value per record kind, plus the special-category trio.
     *
     * @return every plaintext the scan must not find afterwards
     */
    private static List<String> plantedValues() {
        final List<String> planted = new ArrayList<>(SPECIAL_CATEGORY);
        for (final RecordKind kind : RecordKind.values()) {
            planted.add("remnant-sentinel-" + kind.name().toLowerCase(Locale.ROOT));
        }
        return planted;
    }

    /**
     * Fills every record table with its sentinel and the special-category trio.
     *
     * @param store the opened store
     * @param planted the values from {@link #plantedValues()}
     * @throws Exception on store failure
     */
    private static void writeEveryKind(final LocalStore store, final List<String> planted)
            throws Exception {
        for (final RecordKind kind : RecordKind.values()) {
            final String sentinel = "remnant-sentinel-" + kind.name().toLowerCase(Locale.ROOT);
            final byte[] payload = LocalStore.payloadOf(sentinel);
            store.put(kind, kind.name().toLowerCase(Locale.ROOT), payload,
                    kind.isLookedUpByTag() ? payload : null);
        }
        for (final String value : SPECIAL_CATEGORY) {
            store.put(RecordKind.LOCAL_PROFILE, null, LocalStore.payloadOf(value), null);
        }
    }

    /**
     * Plants a plaintext decoy, proves the scanner finds it, and removes it.
     *
     * @param directory the scanned tree
     * @throws IOException on a file failure
     */
    private static void assertScannerCanActuallyFindARemnant(final Path directory)
            throws IOException {
        final Path decoy = directory.resolve("decoy-remnant.txt");
        Files.write(decoy, SPECIAL_CATEGORY.get(0).getBytes(StandardCharsets.UTF_8));
        assertTrue(
                contains(readEveryByteIn(directory),
                        SPECIAL_CATEGORY.get(0).getBytes(StandardCharsets.UTF_8)),
                "the scanner failed to find a remnant that is demonstrably there; "
                        + "its clean verdict below would be worthless");
        Files.delete(decoy);
    }

    /**
     * Issues and satisfies the wipe challenge.
     *
     * @return a spendable confirmation
     * @throws Exception on challenge failure
     */
    private static DeliberateConfirmation.Confirmation confirmation() throws Exception {
        return DeliberateConfirmation
                .issue(DeliberateConfirmation.Action.EMERGENCY_WIPE, PHRASE, NOW)
                .confirm(PHRASE, NOW + 1);
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
