/*
 * Lumos - android-app/app / The age gate, at its edges.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-001, SRS-NFR-LEG-007
 * ADRs: ADR-004
 * Threading: any
 * Notes: The interesting cases are all boundaries, and they are only testable
 * because AgeGate takes "today" rather than reading a clock: the day before an
 * eighteenth birthday and the day of it are one day apart and must be different
 * answers, and a suite that read the real clock could only assert one of them.
 *
 * The last test is the requirement that is easiest to lose. SRS-ONB-001 asks
 * for a gate; the privacy data map Sec. 2.3 asks for the AFFIRMATION to be what
 * is kept. Those are different things, and only the second one survives a
 * refactor by accident, so it is asserted against the bytes that reach disk in
 * ConsentTest rather than only described here.
 */

package com.lumos.app.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.protocol.LumosConstants;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** The adult-only gate. */
final class AgeGateTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 19);

    @Test
    void theMinimumComesFromTheRegisterAndIsEighteen() {
        assertEquals(LumosConstants.ADULT_AGE_MINIMUM_YEARS, AgeGate.minimumAgeYears(),
                "the gate must read the register, not a literal");
        // SRS-NFR-LEG-007 states the global floor. Transcribed by hand here on
        // purpose: reading it back from the same constant the code reads would
        // prove only that the code agrees with itself.
        assertEquals(18, AgeGate.minimumAgeYears());
    }

    @Test
    void theDayBeforeAnEighteenthBirthdayIsRefused() {
        final LocalDate birthday = TODAY.minusYears(18).plusDays(1);
        assertFalse(AgeGate.evaluate(birthday, TODAY).isAdult(),
                "one day short of eighteen is not eighteen");
    }

    @Test
    void anEighteenthBirthdayItselfIsAccepted() {
        assertTrue(AgeGate.evaluate(TODAY.minusYears(18), TODAY).isAdult(),
                "the gate opens on the birthday, not the day after");
    }

    @Test
    void aLeapDayBirthIsResolvedByTheCalendarAndFallsOnTheLaterSide() {
        // Born 29 February 2008, and 2026 has no 29 February. Jurisdictions
        // genuinely differ on whether such a person turns eighteen on 28
        // February or 1 March, and the documents do not settle it - so the
        // behaviour is whatever java.time's calendar arithmetic gives, which is
        // 1 March, and it is pinned here so it is a decision rather than an
        // accident. That is also the stricter side of the ambiguity (SRS Sec.
        // 2.4): it admits nobody a day early.
        final LocalDate leapling = LocalDate.of(2008, 2, 29);
        assertFalse(AgeGate.evaluate(leapling, LocalDate.of(2026, 2, 28)).isAdult(),
                "28 February is one day short by java.time's reckoning");
        assertTrue(AgeGate.evaluate(leapling, LocalDate.of(2026, 3, 1)).isAdult());
        // And on a leap year the birthday itself works normally.
        assertTrue(AgeGate.evaluate(leapling, LocalDate.of(2028, 2, 29)).isAdult());
    }

    @Test
    void aDateInTheFutureIsNotAnAdult() {
        assertFalse(AgeGate.evaluate(TODAY.plusDays(1), TODAY).isAdult(),
                "a clock skew or a typo must fail closed, not produce a negative age");
    }

    @Test
    void aMissingDateIsNotAnAdult() {
        assertFalse(AgeGate.evaluate(null, TODAY).isAdult());
        assertFalse(AgeGate.evaluate(TODAY.minusYears(30), null).isAdult());
    }

    @Test
    void theDecisionRecordsWhatItWasJudgedAgainst() {
        final AgeGate.Decision decision = AgeGate.evaluate(TODAY.minusYears(30), TODAY);
        assertTrue(decision.isAdult());
        assertEquals(AgeGate.minimumAgeYears(), decision.minimumAgeYears(),
                "a consent record made under a future stricter minimum must be "
                        + "distinguishable from one made under this one");
    }

    @Test
    void theDecisionCarriesNoWayToRecoverTheDateOfBirth() {
        final AgeGate.Decision decision = AgeGate.evaluate(LocalDate.of(1990, 3, 14), TODAY);
        // Two booleans-worth of information and nothing else. If an accessor for
        // the date is ever added, this fails and the data map Sec. 2.3 entry
        // "Age-gate affirmation + timestamp" has silently become "date of birth".
        final java.util.List<String> methods = new java.util.ArrayList<>();
        for (final java.lang.reflect.Method method : AgeGate.Decision.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                methods.add(method.getName());
            }
        }
        java.util.Collections.sort(methods);
        assertEquals(java.util.List.of("isAdult", "minimumAgeYears"), methods,
                "the gate's verdict may expose the answer and the rule, nothing more");
        assertTrue(decision.isAdult());
    }
}
