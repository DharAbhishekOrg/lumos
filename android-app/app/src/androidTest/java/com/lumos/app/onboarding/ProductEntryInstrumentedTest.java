/*
 * Lumos - android-app/app / AT-001: a fresh install blocks product entry, on device.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-001, SRS-ONB-002, SRS-ONB-008
 * ADRs: ADR-003
 * Threading: any
 * Notes: AT-001's criterion verbatim: "fresh install blocks product entry
 * until age affirmation and current legal versions are accepted." AT-002 (in
 * OnboardingInstrumentedTest) proves the happy path completes; THIS test is
 * the blocking half, run over the real pieces on a real Android runtime - a
 * fresh Room database, the Android Keystore KEK, the shipped legal documents
 * from the APK's assets - and over EVERY surface the application has, by
 * walking the AppSurface enum rather than a hand-kept list, so the surface
 * added next year is born blocked.
 */

package com.lumos.app.onboarding;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.lumos.app.data.KeyRootFile;
import com.lumos.app.identity.LibsodiumIdentityCrypto;
import com.lumos.app.keystore.AndroidKekStore;
import com.lumos.app.store.LocalStore;
import com.lumos.app.store.room.LumosRoomDatabase;
import java.io.File;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** The blocking half of AT-001, over every surface, on device. */
@RunWith(AndroidJUnit4.class)
public class ProductEntryInstrumentedTest {

    private static final String KEK_ALIAS = "lumos-at001-entry-kek";
    private static final long NOW = 1_763_600_000_000L;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);

    private LumosRoomDatabase room;
    private AndroidKekStore kek;

    @Before
    public void openFreshDeviceStore() {
        final Context context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase(LumosRoomDatabase.DATABASE_FILE);
        room = LumosRoomDatabase.open(context);
    }

    @After
    public void cleanUp() {
        if (kek != null) {
            try {
                kek.destroy();
            } catch (final GeneralSecurityException alreadyGone) {
                // Nothing to clean.
            }
        }
        room.close();
    }

    @Test
    public void at001AFreshInstallBlocksEveryProductSurfaceUntilConsent() throws Exception {
        final Context app = ApplicationProvider.getApplicationContext();
        final File rootFile = new File(app.getCacheDir(), "at001-" + KeyRootFile.NAME);
        rootFile.delete();
        final TestInstallation install = new TestInstallation(KEK_ALIAS, room, rootFile, NOW);
        final OnboardingService service = new OnboardingService(new OnboardingMachine(),
                LibsodiumIdentityCrypto.load(), () -> NOW);

        // Fresh install: every surface that is not onboarding itself is
        // blocked - the AppSurface walk means ALL of them, forever.
        for (final AppSurface surface : AppSurface.values()) {
            if (surface.isPartOfOnboarding()) {
                continue;
            }
            assertFalse("fresh install must block " + surface + " (AT-001)",
                    service.machine().isReachable(surface));
        }

        // Age affirmation alone is not entry (the legal versions are the
        // other half of AT-001's condition).
        service.declareAge(TODAY.minusYears(30), TODAY);
        for (final AppSurface surface : AppSurface.values()) {
            if (!surface.isPartOfOnboarding()) {
                assertFalse("age alone must not open " + surface,
                        service.machine().isReachable(surface));
            }
        }

        // Accepting the CURRENT legal documents - the shipped bytes from the
        // APK's own assets - and creating the identity completes entry.
        service.acceptDocuments(LegalDocuments.load(document ->
                app.getAssets().open(document.assetName())));
        final LocalStore store = service.createIdentity(install);
        kek = install.kek();

        for (final AppSurface surface : AppSurface.values()) {
            assertTrue("after onboarding, " + surface + " is reachable",
                    service.machine().isReachable(surface));
        }

        // And becoming reachable made nobody discoverable (SRS-ONB-008):
        // product entry and radio visibility are different consents.
        assertFalse(new DiscoveryVisibility(store).isVisibleNearby(NOW));
    }
}
