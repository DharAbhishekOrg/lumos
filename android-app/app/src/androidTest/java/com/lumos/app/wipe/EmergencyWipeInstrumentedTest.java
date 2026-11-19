/*
 * Lumos - android-app/app / AT-019: emergency wipe on a real Android runtime.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-SAF-009, SRS-SAF-010, SRS-NFR-PRI-012
 * ADRs: ADR-003, ADR-010
 * Threading: any
 * Notes: The WP-02 closeout named this gap exactly: AT-019 "needs no key
 * generation, so unlike AT-002 it COULD run on an emulator; it is not
 * instrumented." This is that instrumentation - the wipe scenario end to end on
 * the runtime the app ships to: Room's real SQLite, the app's real database
 * directory, and the KEK in the REAL Android Keystore (AndroidKekStore probes
 * StrongBox and falls back to TEE; on the emulator that lands on the
 * software-emulated TEE tier). What the emulator proves is the integration
 * path - keystore-alias destruction wired into the keys-first order over the
 * device database. What it cannot prove, and this test does not claim, is
 * hardware-tier behaviour: StrongBox and real-TEE runs remain owner-executed
 * on physical devices, as recorded since slice S1.
 *
 * The post-wipe remnant scan mirrors PostWipeRemnantScanTest on the JVM, over
 * the device's own database directory, decoy self-test included - a scanner
 * that has never found anything proves nothing by finding nothing.
 */

package com.lumos.app.wipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.lumos.app.data.DataKeyHierarchy;
import com.lumos.app.keystore.AndroidKekStore;
import com.lumos.app.safety.DeliberateConfirmation;
import com.lumos.app.store.LocalStore;
import com.lumos.app.store.RecordKind;
import com.lumos.app.store.room.LumosRoomDatabase;
import com.lumos.app.store.room.RoomSqlDatabase;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** AT-019: the wipe a person in trouble runs, on the runtime they run it on. */
@RunWith(AndroidJUnit4.class)
public class EmergencyWipeInstrumentedTest {

    private static final String PHRASE = "erase everything";
    private static final long NOW = 1_763_500_000_000L;
    private static final String KEK_ALIAS = "lumos-at019-wipe-kek";

    /** Values the privacy data map classifies as special category. */
    private static final List<String> SPECIAL_CATEGORY = List.of(
            "queer, and not looking to explain it",
            "gender identity: non-binary",
            "orientation: bisexual");

    private LumosRoomDatabase room;
    private RoomSqlDatabase seam;
    private AndroidKekStore kek;
    private LocalStore store;

    @Before
    public void openDeviceStoreUnderTheRealKeystore() throws Exception {
        final Context context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase(LumosRoomDatabase.DATABASE_FILE);
        room = LumosRoomDatabase.open(context);
        seam = RoomSqlDatabase.wrapping(room);
        kek = AndroidKekStore.create(KEK_ALIAS);
        store = LocalStore.open(seam,
                DataKeyHierarchy.create(kek).deriveKey(DataKeyHierarchy.DataDomain.DATABASE),
                () -> Long.MAX_VALUE, () -> NOW);
    }

    @After
    public void closeAndCleanUp() {
        try {
            kek.destroy();
        } catch (final java.security.GeneralSecurityException alreadyGone) {
            // The wipe under test destroys the alias; this is for early exits.
        }
        room.close();
    }

    @Test
    public void at019TheWipeRunsEndToEndOnDeviceAndLeavesNoPlaintextRemnant() throws Exception {
        final Context context = ApplicationProvider.getApplicationContext();
        final File databaseDirectory =
                context.getDatabasePath(LumosRoomDatabase.DATABASE_FILE).getParentFile();
        final List<String> planted = plantedValues();

        for (final RecordKind kind : RecordKind.values()) {
            final String sentinel = "remnant-sentinel-" + kind.name().toLowerCase(Locale.ROOT);
            final byte[] payload = LocalStore.payloadOf(sentinel);
            store.put(kind, kind.name().toLowerCase(Locale.ROOT), payload,
                    kind.isLookedUpByTag() ? payload : null);
        }
        for (final String value : SPECIAL_CATEGORY) {
            store.put(RecordKind.LOCAL_PROFILE, null, LocalStore.payloadOf(value), null);
        }
        assertScannerCanActuallyFindARemnant(databaseDirectory);

        final EmergencyWipe.Outcome outcome =
                new EmergencyWipe(kek, store).execute(confirmation());

        assertTrue("the key phase must complete for crypto-erasure to hold",
                outcome.cryptoErasureHolds());
        assertTrue("every stage must report clean on an uninterrupted wipe",
                outcome.report().fullyClean());
        for (final RecordKind kind : RecordKind.values()) {
            assertEquals(kind + " survived the wipe on device", 0, store.count(kind));
        }
        // WAL and journal contents count as "the files"; fold them into the
        // main database before the directory is read. Android's framework
        // SQLite refuses a row-returning PRAGMA through execSQL ("Queries can
        // be performed using query or rawQuery methods only") while JDBC's
        // execute accepts the same statement - this line found that divergence
        // on the emulator's first AT-019 run, so it goes through query().
        seam.query("PRAGMA wal_checkpoint(TRUNCATE)", java.util.List.of());

        final byte[] onDisk = readEveryByteIn(databaseDirectory);
        assertTrue("the scan must have real files to search, or it proves nothing",
                onDisk.length > 0);
        for (final String value : planted) {
            assertFalse("plaintext remnant on device after a completed wipe: " + value,
                    contains(onDisk, value.getBytes(StandardCharsets.UTF_8)));
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
     * Plants a plaintext decoy, proves the scanner finds it, and removes it.
     *
     * @param directory the scanned tree
     * @throws IOException on a file failure
     */
    private static void assertScannerCanActuallyFindARemnant(final File directory)
            throws IOException {
        final File decoy = new File(directory, "decoy-remnant.txt");
        try (FileOutputStream out = new FileOutputStream(decoy)) {
            out.write(SPECIAL_CATEGORY.get(0).getBytes(StandardCharsets.UTF_8));
        }
        final boolean found = contains(readEveryByteIn(directory),
                SPECIAL_CATEGORY.get(0).getBytes(StandardCharsets.UTF_8));
        final boolean deleted = decoy.delete();
        assertTrue("the scanner failed to find a remnant that is demonstrably there; "
                + "its clean verdict below would be worthless", found);
        assertTrue("the decoy must not itself become a remnant", deleted);
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
     * Concatenates every byte of every regular file under a directory.
     *
     * <p>Streams in 8 KiB chunks rather than readAllBytes: that method is
     * API 33 and this file runs at minSdk on the API 30 image - the exact
     * lesson of WP-02 S4b, kept rather than relearned.
     *
     * @param directory the tree
     * @return all bytes, in no particular order
     * @throws IOException on a read failure
     */
    private static byte[] readEveryByteIn(final File directory) throws IOException {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        final File[] entries = directory.listFiles();
        if (entries == null) {
            return out.toByteArray();
        }
        for (final File entry : entries) {
            if (entry.isDirectory()) {
                out.write(readEveryByteIn(entry));
            } else if (entry.isFile()) {
                try (InputStream in = new java.io.FileInputStream(entry)) {
                    final byte[] chunk = new byte[8192];
                    int read = in.read(chunk);
                    while (read > 0) {
                        out.write(chunk, 0, read);
                        read = in.read(chunk);
                    }
                }
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
