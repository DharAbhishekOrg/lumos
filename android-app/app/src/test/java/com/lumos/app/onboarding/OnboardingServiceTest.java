/*
 * Lumos - android-app/app / Onboarding end to end, offline, with no account.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-001, SRS-ONB-002, SRS-ONB-006, SRS-ONB-008
 * ADRs: ADR-008, ADR-010
 * Threading: any
 * Notes: The real thing: a real KEK, a real SQLite database, real Ed25519 and
 * X25519 generation through the platform JCA, the real documents from
 * docs/legal. Nothing is stubbed, so what passes here is the flow a device runs.
 *
 * theOnboardingPathTouchesNoNetworkOrAccountApi is a source-level assertion and
 * is worth the oddity. Threat model LT-34 is about a requirement that cannot be
 * tested by exercising the happy path - "does not require a phone number" is
 * proven by the absence of code, and absence is what the import list shows.
 */

package com.lumos.app.onboarding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.data.DataKeyHierarchy;
import com.lumos.app.data.KeyRootFile;
import com.lumos.app.identity.IdentityKeyGenerator;
import com.lumos.app.identity.IdentityKeyRecord;
import com.lumos.app.identity.IdentityRepository;
import com.lumos.app.identity.JcaIdentityCrypto;
import com.lumos.app.keystore.KekStore;
import com.lumos.app.keystore.SoftwareKekStore;
import com.lumos.app.store.JdbcSqlDatabase;
import com.lumos.app.store.LocalStore;
import com.lumos.app.store.StoreException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Onboarding, run for real. */
final class OnboardingServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 19);
    private static final LocalDate ADULT = LocalDate.of(1991, 4, 7);
    private static final LocalDate CHILD = LocalDate.of(2015, 4, 7);
    private static final long NOW = 1_755_561_600_000L;

    @TempDir
    Path dir;

    private JdbcSqlDatabase database;
    private SoftwareKekStore kek;
    private boolean failOpen;

    /** The device's installation, with the software KEK in the Keystore's place. */
    private final OnboardingService.Installation install = new OnboardingService.Installation() {
        @Override
        public KekStore createKek() throws GeneralSecurityException {
            kek = SoftwareKekStore.create();
            return kek;
        }

        @Override
        public void persistRoot(final KeyRootFile.Root root) throws IOException {
            KeyRootFile.write(rootFile().toFile(), root);
        }

        @Override
        public LocalStore openStore(final DataKeyHierarchy keys) throws StoreException, GeneralSecurityException {
            if (failOpen) {
                throw new StoreException(StoreException.Reason.ENGINE, "scripted");
            }
            return LocalStore.open(database, keys.deriveKey(DataKeyHierarchy.DataDomain.DATABASE),
                    () -> Long.MAX_VALUE, () -> NOW);
        }
    };

    @BeforeEach
    void openDatabase() throws StoreException {
        database = JdbcSqlDatabase.inMemory();
    }

    @AfterEach
    void closeDatabase() throws StoreException {
        database.close();
    }

    private Path rootFile() {
        return dir.resolve(KeyRootFile.NAME);
    }

    private static OnboardingService service() {
        return new OnboardingService(new OnboardingMachine(), new JcaIdentityCrypto(), () -> NOW);
    }

    /** Reopens the store the way a boot does: from the key root file and the KEK. */
    private LocalStore relaunch() throws Exception {
        final KeyRootFile.Root root = KeyRootFile.read(rootFile().toFile()).orElseThrow();
        return LocalStore.open(database, DataKeyHierarchy.restore(kek, root.wrappedRoot())
                .deriveKey(DataKeyHierarchy.DataDomain.DATABASE), () -> Long.MAX_VALUE, () -> NOW);
    }

    @Test
    void anAdultOnboardsWithNoAccountAndARelaunchFindsTheIdentityAndTheConsent() throws Exception {
        final OnboardingService service = service();
        assertSame(OnboardingState.LEGAL_CONSENT, service.declareAge(ADULT, TODAY));
        final LegalDocuments shipped = LegalDocuments.load(RepoFiles.shippedDocuments());
        assertSame(OnboardingState.IDENTITY_CREATION, service.acceptDocuments(shipped));
        assertFalse(Files.exists(rootFile()), "nothing is written before the last step");

        service.createIdentity(install);
        assertSame(OnboardingState.COMPLETE, service.machine().state());

        final LocalStore store = relaunch();
        final IdentityKeyRecord identity = new IdentityRepository(store).load().orElseThrow();
        assertEquals(32, identity.ed25519Public().length);
        assertTrue(IdentityKeyGenerator.verifyBinding(new JcaIdentityCrypto(), identity.ed25519Public(),
                identity.x25519Public(), identity.bindingSignature()),
                "the ADR-008 binding must verify on the identity onboarding created");
        final ConsentRecord consent = new ConsentRepository(store).load().orElseThrow();
        assertEquals(shipped.versions(), consent.acceptedVersions(), "what is recorded is what was displayed");
        assertEquals(NOW, consent.affirmedAtUtcMillis());
        assertEquals(AgeGate.minimumAgeYears(), consent.minimumAgeYears());
        assertFalse(new ConsentRepository(store).needsConsent(shipped));
        assertEquals(kek.protection(), KeyRootFile.read(rootFile().toFile()).orElseThrow().protection());
    }

    @Test
    void aRefusedUserGetsNoKeyNoRootAndNoStore() throws Exception {
        final OnboardingService service = service();
        assertSame(OnboardingState.BLOCKED_UNDER_AGE, service.declareAge(CHILD, TODAY));
        assertSame(OnboardingState.BLOCKED_UNDER_AGE,
                service.acceptDocuments(LegalDocuments.load(RepoFiles.shippedDocuments())),
                "the documents cannot rescue a refused user");
        assertThrows(IllegalStateException.class, () -> service.createIdentity(install),
                "no key material is generated for someone the gate refused");
        assertNull(kek, "no KEK was made");
        assertFalse(Files.exists(rootFile()), "no key root was written");
    }

    @Test
    void identityCreationRefusesToRunBeforeTheGateAndTheDocuments() throws Exception {
        final OnboardingService service = service();
        assertThrows(IllegalStateException.class, () -> service.createIdentity(install));
        service.declareAge(ADULT, TODAY);
        assertThrows(IllegalStateException.class, () -> service.createIdentity(install), "and before the documents");
        assertNull(kek);
    }

    @Test
    void aFailedLastStepChangesNothingAndCanBeRetried() throws Exception {
        final OnboardingService service = service();
        service.declareAge(ADULT, TODAY);
        service.acceptDocuments(LegalDocuments.load(RepoFiles.shippedDocuments()));
        failOpen = true;
        assertThrows(StoreException.class, () -> service.createIdentity(install));
        assertSame(OnboardingState.IDENTITY_CREATION, service.machine().state(), "a failure does not complete it");
        failOpen = false;
        service.createIdentity(install);
        assertSame(OnboardingState.COMPLETE, service.machine().state());
        assertTrue(new ConsentRepository(relaunch()).load().isPresent(), "the held consent survives the failed try");
    }

    @Test
    void goingBackToTheGateDropsTheHeldConsent() throws Exception {
        final OnboardingService service = service();
        service.declareAge(ADULT, TODAY);
        service.acceptDocuments(LegalDocuments.load(RepoFiles.shippedDocuments()));
        service.machine().on(new OnboardingEvent.RestartRequested());
        assertThrows(IllegalStateException.class, () -> service.createIdentity(install));
        assertSame(OnboardingState.BLOCKED_UNDER_AGE, service.declareAge(CHILD, TODAY));
        assertThrows(IllegalStateException.class, () -> service.createIdentity(install),
                "a minor re-entry gets no key");
        assertNull(kek);
    }

    @Test
    void onboardingCannotRunTwice() throws Exception {
        final OnboardingService service = service();
        service.declareAge(ADULT, TODAY);
        service.acceptDocuments(LegalDocuments.load(RepoFiles.shippedDocuments()));
        service.createIdentity(install);
        final byte[] first = Files.readAllBytes(rootFile());
        assertThrows(IllegalStateException.class, () -> service.createIdentity(install));
        assertArrayEquals(first, Files.readAllBytes(rootFile()), "the key root is not replaced");
    }

    @Test
    void completingOnboardingLeavesTheUserInvisible() throws Exception {
        final OnboardingService service = service();
        service.declareAge(ADULT, TODAY);
        service.acceptDocuments(LegalDocuments.load(RepoFiles.shippedDocuments()));
        final LocalStore store = service.createIdentity(install);
        assertFalse(new DiscoveryVisibility(store).isVisibleNearby(NOW),
                "SRS-ONB-008: finishing onboarding is an installation, and an "
                        + "installation does not make anybody discoverable");
    }

    @Test
    void theOnboardingPathTouchesNoNetworkOrAccountApi() throws Exception {
        final Path source = RepoFiles.repoRoot()
                .resolve("android-app/app/src/main/java/com/lumos/app/onboarding");
        final List<String> forbidden = List.of(
                "java.net", "okhttp", "retrofit", "android.accounts",
                "com.google.android.gms.auth", "firebase", "javax.net", "java.rmi");
        try (java.util.stream.Stream<Path> files = Files.list(source)) {
            for (final Path file : files.toList()) {
                final String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                for (final String line : text.split("\n", -1)) {
                    if (!line.startsWith("import ")) {
                        continue;
                    }
                    final String imported = line.toLowerCase(Locale.ROOT);
                    for (final String banned : forbidden) {
                        assertFalse(imported.contains(banned),
                                file.getFileName() + " imports " + banned + "; SRS-ONB-006 "
                                        + "onboarding is offline and account-free, and threat "
                                        + "model LT-34 is about exactly this line existing");
                    }
                }
            }
        }
    }
}
