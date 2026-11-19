/*
 * Lumos - android-app/app / Visible Nearby is never on by itself.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-008, SRS-DAT-002
 * ADRs: ADR-002
 * Threading: any
 * Notes: SRS-ONB-008 names four triggers - installation, update, reboot and
 * permission grant - and each is realised here as the concrete thing it is
 * rather than as a mocked event:
 *
 *   installation      a fresh, empty database
 *   update            a version-1 database migrated to version 2
 *   reboot            the store closed and reopened over the same file
 *   permission grant  nothing at all, which is the point
 *
 * The update case is the one with teeth. Slice S4c adds the discovery_visibility
 * table in migration 1-to-2, and a migration that created the row while creating
 * the table - or a default that read "absent means unknown, so ask the radio" -
 * would turn an app update into a broadcast nobody asked for. That is threat
 * model LT-33, High severity, and this test is what stands between the two.
 */

package com.lumos.app.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.Fixtures;
import com.lumos.app.data.DataKeyHierarchy;
import com.lumos.app.keystore.SoftwareKekStore;
import com.lumos.app.store.JdbcSqlDatabase;
import com.lumos.app.store.LocalSchema;
import com.lumos.app.store.LocalStore;
import com.lumos.app.store.RecordKind;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The four ways Visible Nearby must not turn itself on. */
final class NoAutoVisibilityTest {

    private static final long NOW = 1_755_561_600_000L;

    @Test
    void afterInstallationNothingIsBroadcast() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            assertFalse(new DiscoveryVisibility(stack.store()).isVisibleNearby(NOW),
                    "a device that has never been told otherwise is not discoverable");
        }
    }

    @Test
    void afterAnUpdateThatAddsTheTableItIsStillOff() throws Exception {
        try (JdbcSqlDatabase database = JdbcSqlDatabase.inMemory()) {
            // A version-1 database: exactly the tables slice S2 created.
            for (final String sql : LocalSchema.migrationToVersion1()) {
                database.execute(sql);
            }
            database.execute("INSERT INTO " + LocalSchema.TABLE_META + " ("
                    + LocalSchema.META_KEY + ", " + LocalSchema.META_VALUE + ") VALUES (?, ?)",
                    List.of(LocalSchema.META_SCHEMA_VERSION, "1"));

            final SoftwareKekStore kek = SoftwareKekStore.create();
            final DataKeyHierarchy hierarchy = DataKeyHierarchy.create(kek);
            final LocalStore store = LocalStore.open(database,
                    hierarchy.deriveKey(DataKeyHierarchy.DataDomain.DATABASE),
                    () -> Long.MAX_VALUE, () -> NOW);

            assertEquals(0, store.count(RecordKind.DISCOVERY_VISIBILITY),
                    "the migration creates the table and writes no row into it");
            assertFalse(new DiscoveryVisibility(store).isVisibleNearby(NOW),
                    "updating the app must not make anyone discoverable");
        }
    }

    @Test
    void afterARebootTheStoredAnswerIsWhatTheUserLastChose(@TempDir final Path directory)
            throws Exception {
        final String file = directory.resolve("lumos.db").toString();
        final SoftwareKekStore kek = SoftwareKekStore.create();
        final DataKeyHierarchy hierarchy = DataKeyHierarchy.create(kek);
        final byte[] databaseKey = hierarchy.deriveKey(DataKeyHierarchy.DataDomain.DATABASE);

        try (JdbcSqlDatabase database = JdbcSqlDatabase.atPath(file)) {
            final LocalStore store = LocalStore.open(database, databaseKey,
                    () -> Long.MAX_VALUE, () -> NOW);
            assertFalse(new DiscoveryVisibility(store).isVisibleNearby(NOW));
        }

        // Reboot: the process is gone and the file is all that is left.
        try (JdbcSqlDatabase reopened = JdbcSqlDatabase.atPath(file)) {
            final LocalStore store = LocalStore.open(reopened, databaseKey,
                    () -> Long.MAX_VALUE, () -> NOW);
            final DiscoveryVisibility visibility = new DiscoveryVisibility(store);
            assertFalse(visibility.isVisibleNearby(NOW), "off survives a restart as off");

            visibility.enableAfterExplicitUserRequest(
                    DiscoveryVisibility.Selection.defaultSession(), NOW);
            assertTrue(visibility.isVisibleNearby(NOW));
        }

        try (JdbcSqlDatabase reopened = JdbcSqlDatabase.atPath(file)) {
            final LocalStore store = LocalStore.open(reopened, databaseKey,
                    () -> Long.MAX_VALUE, () -> NOW);
            assertTrue(new DiscoveryVisibility(store).isVisibleNearby(NOW),
                    "and on survives a restart as on, inside its window - a reboot is "
                            + "not a reason to change what the user chose, in either direction");
        }
    }

    @Test
    void grantingAPermissionCannotReachThisStateAtAll() {
        // The fourth trigger has no test fixture because it has no entry point.
        // There is no method here that a permission callback could call, and
        // this test pins the surface so one cannot be added without a diff that
        // says so out loud.
        final List<String> mutators = new ArrayList<>();
        for (final Method method : DiscoveryVisibility.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) {
                continue;
            }
            mutators.add(method.getName());
        }
        java.util.Collections.sort(mutators);
        assertEquals(List.of("disable", "enableAfterExplicitUserRequest", "endOnVenueExit",
                        "indicator", "isVisibleNearby"),
                mutators,
                "exactly one method may turn Visible Nearby on, and its name says who "
                        + "must have asked; endOnVenueExit and disable only turn it OFF, "
                        + "indicator and isVisibleNearby only read; anything else here is "
                        + "a way for an install, an update, a reboot or a permission "
                        + "grant to do it instead");
    }

    @Test
    void anUnreadableRecordReadsAsOff() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            stack.store().put(RecordKind.DISCOVERY_VISIBILITY, DiscoveryVisibility.VISIBILITY_ID,
                    "lumos-visibility/99\ndefault\non\n0\n0\n".getBytes(StandardCharsets.UTF_8),
                    null);
            assertFalse(new DiscoveryVisibility(stack.store()).isVisibleNearby(NOW),
                    "a record from a format this build does not know fails towards off");
        }
    }

    @Test
    void aPreviousFormatVersionReadsAsOffBecauseAnUpdateIsATrigger() throws Exception {
        // The S5 format change is itself the SRS-ONB-008 update case: a device
        // that was visible under the WP-02 format comes back NOT visible, and
        // the person chooses again with a duration (SRS-NBY-001).
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            stack.store().put(RecordKind.DISCOVERY_VISIBILITY, DiscoveryVisibility.VISIBILITY_ID,
                    "lumos-visibility/1\non\n0\n".getBytes(StandardCharsets.UTF_8), null);
            assertFalse(new DiscoveryVisibility(stack.store()).isVisibleNearby(NOW),
                    "visibility must not carry across an app update's format change");
        }
    }

    @Test
    void turningItOffTakesEffectImmediately() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final DiscoveryVisibility visibility = new DiscoveryVisibility(stack.store());
            visibility.enableAfterExplicitUserRequest(
                    DiscoveryVisibility.Selection.defaultSession(), NOW);
            assertTrue(visibility.isVisibleNearby(NOW));
            visibility.disable(NOW + 1);
            assertFalse(visibility.isVisibleNearby(NOW + 1));
        }
    }
}
