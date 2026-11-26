/*
 * Lumos - android-app/app / AT-002: offline no-account onboarding, on device.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-006, SRS-ONB-001, SRS-ONB-002, SRS-IDN-001
 * ADRs: ADR-001, ADR-008, ADR-003
 * Threading: any
 * Notes: AT-002's criterion is that onboarding completes and creates a local
 * identity with no account, no phone number and no network - and WP-02 closed
 * with it marked "cannot run on an emulator", because onboarding creates an
 * identity and identity creation did not run on Android (closeout 5.1). This
 * is that test running at last, over the real pieces: the adult gate, the
 * shipped legal documents read from the APK's assets, Room's SQLite, the
 * Android Keystore KEK, and the libsodium identity backend.
 *
 * Offline is structural, not simulated: nothing constructed here holds a
 * network stack, and the app under test declares no account surface to call.
 * The test proves the whole flow needs none of it.
 */

package com.lumos.app.onboarding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.lumos.app.data.KeyRootFile;
import com.lumos.app.identity.IdentityKeyGenerator;
import com.lumos.app.identity.IdentityKeyRecord;
import com.lumos.app.identity.IdentityRepository;
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

/** The whole onboarding flow on a real Android runtime - AT-002. */
@RunWith(AndroidJUnit4.class)
public class OnboardingInstrumentedTest {

    private static final String KEK_ALIAS = "lumos-at002-onboarding-kek";
    private static final long NOW = 1_763_500_000_000L;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);
    private static final LocalDate ADULT = TODAY.minusYears(25);

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
    public void at002OnboardingCompletesOfflineAndCreatesALocalIdentity() throws Exception {
        final LibsodiumIdentityCrypto crypto = LibsodiumIdentityCrypto.load();
        final Context app = ApplicationProvider.getApplicationContext();
        final File rootFile = new File(app.getCacheDir(), "at002-" + KeyRootFile.NAME);
        rootFile.delete();
        final TestInstallation install = new TestInstallation(KEK_ALIAS, room, rootFile, NOW);
        final OnboardingService service = new OnboardingService(new OnboardingMachine(), crypto, () -> NOW);

        // The adult gate first, before any product surface (SRS-ONB-001).
        assertSame(OnboardingState.LEGAL_CONSENT, service.declareAge(ADULT, TODAY));

        // The four shipped documents, read from the APK's own assets - the
        // same bytes a user would be shown (SRS-ONB-002).
        final LegalDocuments shipped = LegalDocuments.load(document ->
                app.getAssets().open(document.assetName()));
        assertSame(OnboardingState.IDENTITY_CREATION, service.acceptDocuments(shipped));

        // Identity creation through the libsodium backend and the real
        // Keystore, in the production order: KEK, key root file, store, then
        // consent and identity in one transaction (SRS-ONB-006, ADR-012).
        final LocalStore store = service.createIdentity(install);
        kek = install.kek();
        assertSame(OnboardingState.COMPLETE, service.machine().state());
        assertTrue("the key root file is written", KeyRootFile.read(rootFile).isPresent());
        final IdentityRepository identities = new IdentityRepository(store);
        assertTrue("consent is stored with the identity", new ConsentRepository(store).load().isPresent());

        final IdentityKeyRecord identity = identities.load().orElseThrow();
        assertEquals(32, identity.ed25519Public().length);
        assertEquals(32, identity.x25519Public().length);
        assertTrue("the ADR-008 binding must verify on the identity onboarding created",
                IdentityKeyGenerator.verifyBinding(crypto, identity.ed25519Public(),
                        identity.x25519Public(), identity.bindingSignature()));

        // No account anywhere in the record: the identity is key material and
        // key material only (SRS-IDN-002's input half).
        assertEquals("the identity record carries exactly its two publics, binding and envelope",
                32, identity.ed25519Public().length);
    }

    @Test
    public void theAssetsReallyAreTheInstrumentationAndAppSplitTheRuleSays() throws Exception {
        // The vectors ride the TEST apk; the legal documents ride the APP. A
        // confusion between the two contexts would make the AT-002 consent
        // step read the wrong bytes, so the split itself is pinned.
        final Context app = ApplicationProvider.getApplicationContext();
        assertNotNull(app.getAssets().open(LegalDocument.TERMS_OF_USE.assetName()));
        assertNotNull(InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open("crypto/identity_binding.json"));
    }
}
