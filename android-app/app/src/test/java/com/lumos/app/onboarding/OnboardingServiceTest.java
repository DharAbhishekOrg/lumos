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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.identity.JcaIdentityCrypto;
import com.lumos.app.Fixtures;
import com.lumos.app.identity.IdentityKeyGenerator;
import com.lumos.app.identity.IdentityKeyRecord;
import com.lumos.app.identity.IdentityRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** Onboarding, run for real. */
final class OnboardingServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 19);
    private static final LocalDate ADULT = LocalDate.of(1991, 4, 7);
    private static final LocalDate CHILD = LocalDate.of(2015, 4, 7);
    private static final long NOW = 1_755_561_600_000L;

    private static OnboardingService serviceOver(final Fixtures.Stack stack) {
        return new OnboardingService(new OnboardingMachine(),
                new ConsentRepository(stack.store()),
                new IdentityRepository(stack.store()),
                stack.kek(), new JcaIdentityCrypto(), () -> NOW);
    }

    @Test
    void anAdultOnboardsWithNoAccountAndEndsWithAnIdentity() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final OnboardingService service = serviceOver(stack);
            final IdentityRepository identities = new IdentityRepository(stack.store());
            assertFalse(identities.exists(), "nothing exists before onboarding");

            assertSame(OnboardingState.LEGAL_CONSENT, service.declareAge(ADULT, TODAY));
            final LegalDocuments shipped = LegalDocuments.load(RepoFiles.shippedDocuments());
            assertSame(OnboardingState.IDENTITY_CREATION, service.acceptDocuments(shipped));

            final byte[] wrappedRoot = service.createIdentity();
            assertSame(OnboardingState.COMPLETE, service.machine().state());
            assertNotNull(wrappedRoot);
            assertTrue(wrappedRoot.length > 0);

            final IdentityKeyRecord identity = identities.load().orElseThrow();
            assertEquals(32, identity.ed25519Public().length);
            assertEquals(32, identity.x25519Public().length);
            assertTrue(IdentityKeyGenerator.verifyBinding(new JcaIdentityCrypto(), identity.ed25519Public(),
                    identity.x25519Public(), identity.bindingSignature()),
                    "the ADR-008 binding must verify on the identity onboarding created");
        }
    }

    @Test
    void theAcceptedVersionsRecordedAreTheOnesActuallyShipped() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final OnboardingService service = serviceOver(stack);
            final ConsentRepository consent = new ConsentRepository(stack.store());
            final LegalDocuments shipped = LegalDocuments.load(RepoFiles.shippedDocuments());

            service.declareAge(ADULT, TODAY);
            service.acceptDocuments(shipped);

            final ConsentRecord stored = consent.load().orElseThrow();
            assertEquals(shipped.versions(), stored.acceptedVersions(),
                    "what is recorded must be what was displayed");
            assertEquals(NOW, stored.affirmedAtUtcMillis());
            assertEquals(AgeGate.minimumAgeYears(), stored.minimumAgeYears());
            assertFalse(consent.needsConsent(shipped));
        }
    }

    @Test
    void aRefusedUserGetsNoIdentityAndNoConsentRecord() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final OnboardingService service = serviceOver(stack);
            assertSame(OnboardingState.BLOCKED_UNDER_AGE, service.declareAge(CHILD, TODAY));

            final LegalDocuments shipped = LegalDocuments.load(RepoFiles.shippedDocuments());
            service.acceptDocuments(shipped);
            assertSame(OnboardingState.BLOCKED_UNDER_AGE, service.machine().state(),
                    "the documents cannot rescue a refused user");

            assertThrows(IllegalStateException.class, service::createIdentity,
                    "no key material is generated for someone the gate refused");
            assertFalse(new IdentityRepository(stack.store()).exists());
        }
    }

    @Test
    void identityCreationRefusesToRunBeforeTheGate() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final OnboardingService service = serviceOver(stack);
            assertThrows(IllegalStateException.class, service::createIdentity);
            service.declareAge(ADULT, TODAY);
            assertThrows(IllegalStateException.class, service::createIdentity,
                    "and before the documents");
        }
    }

    @Test
    void completingOnboardingLeavesTheUserInvisible() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final OnboardingService service = serviceOver(stack);
            service.declareAge(ADULT, TODAY);
            service.acceptDocuments(LegalDocuments.load(RepoFiles.shippedDocuments()));
            service.createIdentity();

            assertSame(OnboardingState.COMPLETE, service.machine().state());
            assertFalse(new DiscoveryVisibility(stack.store()).isVisibleNearby(),
                    "SRS-ONB-008: finishing onboarding is an installation, and an "
                            + "installation does not make anybody discoverable");
        }
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
