/*
 * Lumos - android-app/app / No surface is reachable before the gate.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-001, SRS-ONB-002, SRS-ONB-006
 * ADRs: -
 * Threading: any
 * Notes: Threat model LT-30, and the reason it is High: a profile surface
 * reachable before the age gate makes the gate decorative. The kickoff calls it
 * a trap because it is the kind of defect that arrives later, when somebody
 * adds a screen.
 *
 * So these tests iterate AppSurface.values() rather than naming screens. A
 * surface added in WP-03 with no thought given to onboarding is a surface these
 * tests already cover, and one added deliberately has to change
 * isPartOfOnboarding() to get through - which is a diff a reviewer sees.
 */

package com.lumos.app.onboarding;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** The surface gate over the onboarding state machine. */
final class OnboardingGateTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 19);
    private static final LocalDate ADULT = TODAY.minusYears(30);
    private static final LocalDate CHILD = TODAY.minusYears(12);

    private static AgeGate.Decision decisionFor(final LocalDate born) {
        return AgeGate.evaluate(born, TODAY);
    }

    @Test
    void aFreshDeviceCanReachTheAgeGateAndNothingElse() {
        final OnboardingMachine machine = new OnboardingMachine();
        for (final AppSurface surface : AppSurface.values()) {
            final boolean expected = surface == AppSurface.AGE_GATE;
            assertSame(expected, machine.isReachable(surface),
                    surface + " reachability before the age gate");
        }
    }

    @Test
    void everyApplicationSurfaceIsClosedUntilOnboardingCompletes() {
        final OnboardingState[] before = {
            OnboardingState.AGE_GATE, OnboardingState.LEGAL_CONSENT,
            OnboardingState.IDENTITY_CREATION, OnboardingState.BLOCKED_UNDER_AGE,
        };
        for (final OnboardingState state : before) {
            final OnboardingMachine machine = new OnboardingMachine(state);
            for (final AppSurface surface : AppSurface.values()) {
                if (surface.isPartOfOnboarding()) {
                    continue;
                }
                assertFalse(machine.isReachable(surface),
                        surface + " must not be reachable in " + state);
            }
        }
    }

    @Test
    void aBlockedUserReachesNothingAtAllIncludingTheOnboardingSteps() {
        final OnboardingMachine machine = new OnboardingMachine();
        machine.on(new OnboardingEvent.AgeDeclared(decisionFor(CHILD)));
        assertSame(OnboardingState.BLOCKED_UNDER_AGE, machine.state());
        for (final AppSurface surface : AppSurface.values()) {
            assertFalse(machine.isReachable(surface),
                    surface + " must be closed to a user the gate refused");
        }
    }

    @Test
    void aUserCannotSkipAheadToTheConsentScreen() {
        final OnboardingMachine machine = new OnboardingMachine();
        assertFalse(machine.isReachable(AppSurface.LEGAL_CONSENT),
                "the documents come after the gate, not instead of it");
        machine.on(new OnboardingEvent.AgeDeclared(decisionFor(ADULT)));
        assertTrue(machine.isReachable(AppSurface.LEGAL_CONSENT));
        assertFalse(machine.isReachable(AppSurface.AGE_GATE),
                "and the gate is behind them once passed");
    }

    @Test
    void everythingOpensOnlyWhenOnboardingIsComplete() {
        final OnboardingMachine machine = new OnboardingMachine();
        machine.on(new OnboardingEvent.AgeDeclared(decisionFor(ADULT)));
        machine.on(new OnboardingEvent.LegalDocumentsAccepted(everyDocumentAtVersionOne()));
        assertFalse(machine.isReachable(AppSurface.PROFILE_EDITOR),
                "not yet - the identity does not exist");
        machine.on(new OnboardingEvent.IdentityCreated());
        for (final AppSurface surface : AppSurface.values()) {
            assertTrue(machine.isReachable(surface), surface + " after completion");
        }
    }

    @Test
    void aNullSurfaceIsNotReachable() {
        assertFalse(new OnboardingMachine(OnboardingState.COMPLETE).isReachable(null));
    }

    /**
     * A complete acceptance map, for tests that care about the transition
     * rather than the versions.
     *
     * @return every document at a placeholder version
     */
    static java.util.Map<LegalDocument, String> everyDocumentAtVersionOne() {
        final java.util.Map<LegalDocument, String> versions =
                new java.util.EnumMap<>(LegalDocument.class);
        for (final LegalDocument document : LegalDocument.values()) {
            versions.put(document, "1");
        }
        return versions;
    }
}
