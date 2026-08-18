/*
 * Lumos - android-app/app / The onboarding transitions.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-001, SRS-ONB-002, SRS-ONB-006, SRS-IDN-003
 * ADRs: -
 * Threading: any
 * Notes: The transitions are the easy half. The half worth testing is what the
 * machine does with events it did not expect - a double tap, a screen
 * redelivering after rotation, a stale event from a cancelled flow - because
 * that is where a state machine either holds or quietly advances someone past
 * a gate they did not answer.
 */

package com.lumos.app.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Transitions, and the events that must not cause one. */
final class OnboardingMachineTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 19);

    private static OnboardingEvent.AgeDeclared adult() {
        return new OnboardingEvent.AgeDeclared(AgeGate.evaluate(TODAY.minusYears(25), TODAY));
    }

    private static OnboardingEvent.AgeDeclared child() {
        return new OnboardingEvent.AgeDeclared(AgeGate.evaluate(TODAY.minusYears(9), TODAY));
    }

    private static OnboardingEvent.LegalDocumentsAccepted accepted() {
        return new OnboardingEvent.LegalDocumentsAccepted(
                OnboardingGateTest.everyDocumentAtVersionOne());
    }

    @Test
    void theHappyPathRunsGateThenDocumentsThenIdentity() {
        final OnboardingMachine machine = new OnboardingMachine();
        assertSame(OnboardingState.AGE_GATE, machine.state());
        assertSame(OnboardingState.LEGAL_CONSENT, machine.on(adult()));
        assertSame(OnboardingState.IDENTITY_CREATION, machine.on(accepted()));
        assertSame(OnboardingState.COMPLETE, machine.on(new OnboardingEvent.IdentityCreated()));
        assertEquals(true, machine.state().isComplete());
    }

    @Test
    void anUnderAgeAnswerIsATerminalStateNotAReturnedValue() {
        final OnboardingMachine machine = new OnboardingMachine();
        assertSame(OnboardingState.BLOCKED_UNDER_AGE, machine.on(child()));
        assertSame(OnboardingState.BLOCKED_UNDER_AGE, machine.on(accepted()),
                "accepting documents must not rescue a refused user");
        assertSame(OnboardingState.BLOCKED_UNDER_AGE,
                machine.on(new OnboardingEvent.IdentityCreated()),
                "and neither must an identity appearing from somewhere");
    }

    @Test
    void documentsCannotBeAcceptedBeforeTheGate() {
        final OnboardingMachine machine = new OnboardingMachine();
        assertSame(OnboardingState.AGE_GATE, machine.on(accepted()));
    }

    @Test
    void anIdentityCannotBeCreatedBeforeTheDocuments() {
        final OnboardingMachine machine = new OnboardingMachine();
        machine.on(adult());
        assertSame(OnboardingState.LEGAL_CONSENT,
                machine.on(new OnboardingEvent.IdentityCreated()));
    }

    @Test
    void aRepeatedEventDoesNotAdvanceTwice() {
        final OnboardingMachine machine = new OnboardingMachine();
        machine.on(adult());
        assertSame(OnboardingState.LEGAL_CONSENT, machine.on(adult()),
                "a second age answer after the gate has passed changes nothing");
        machine.on(accepted());
        assertSame(OnboardingState.IDENTITY_CREATION, machine.on(accepted()));
    }

    @Test
    void restartReturnsToTheGateFromAnywhereIncludingBlocked() {
        for (final OnboardingState state : OnboardingState.values()) {
            final OnboardingMachine machine = new OnboardingMachine(state);
            assertSame(OnboardingState.AGE_GATE,
                    machine.on(new OnboardingEvent.RestartRequested()),
                    "restart from " + state);
        }
    }

    @Test
    void anEventWithoutItsDataIsRefusedAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new OnboardingEvent.AgeDeclared(null));
        assertThrows(IllegalArgumentException.class,
                () -> new OnboardingEvent.LegalDocumentsAccepted(null));
    }

    @Test
    void partialConsentIsRefusedRatherThanRecordedAsWeakerConsent() {
        final Map<LegalDocument, String> three = new EnumMap<>(LegalDocument.class);
        three.put(LegalDocument.TERMS_OF_USE, "1");
        three.put(LegalDocument.PRIVACY_NOTICE, "1");
        three.put(LegalDocument.COMMUNITY_RULES, "1");
        assertThrows(IllegalArgumentException.class,
                () -> new OnboardingEvent.LegalDocumentsAccepted(three),
                "three documents out of four is an unknown consent, not a partial one");
    }

    @Test
    void anAcceptanceEventCannotBeMutatedAfterTheFact() {
        final Map<LegalDocument, String> versions = OnboardingGateTest.everyDocumentAtVersionOne();
        final OnboardingEvent.LegalDocumentsAccepted event =
                new OnboardingEvent.LegalDocumentsAccepted(versions);
        versions.put(LegalDocument.TERMS_OF_USE, "tampered");
        assertEquals("1", event.versions().get(LegalDocument.TERMS_OF_USE),
                "the event copied the map; the caller cannot rewrite what was accepted");
        assertThrows(UnsupportedOperationException.class,
                () -> event.versions().put(LegalDocument.TERMS_OF_USE, "tampered"));
    }

    @Test
    void aMachineNeedsAState() {
        assertThrows(IllegalArgumentException.class, () -> new OnboardingMachine(null));
    }
}
