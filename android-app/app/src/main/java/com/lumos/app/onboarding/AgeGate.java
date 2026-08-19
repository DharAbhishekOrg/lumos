/*
 * Lumos - android-app/app / The adult-only age gate.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-001, SRS-NFR-LEG-007
 * ADRs: ADR-004
 * Threading: any
 * Notes: The date of birth goes in and does not come out. This class computes
 * one boolean and the minimum it was compared against, and returns those; there
 * is no accessor for the date, nothing stores it, and AgeGateTest asserts that
 * the persisted consent record's bytes do not contain it. The privacy data map
 * Sec. 2.3 records exactly this: "Age-gate affirmation + timestamp", not a
 * birth date. Keeping a birth date to prove someone is over eighteen is keeping
 * a stronger identifier than the fact being proved.
 *
 * The minimum comes from the register (ADR-004). SRS-NFR-LEG-007 fixes it at 18
 * globally; the entry is not overridable, so a signed configuration cannot lower
 * it - see the register's note on ADULT_AGE_MINIMUM for why that direction is
 * the only one this project will accept.
 *
 * A date in the future is not an adult. That is the fail-closed reading of a
 * clock skew or a typo, and it is also what stops "born tomorrow" from
 * arithmetically producing a negative age that compares however the platform
 * feels like comparing it.
 */

package com.lumos.app.onboarding;

import com.lumos.protocol.LumosConstants;
import java.time.LocalDate;
import java.time.Period;

/** Judges a declared date of birth against the adult minimum, and forgets it. */
public final class AgeGate {

    private AgeGate() {
        throw new AssertionError("no instances");
    }

    /** The gate's verdict: an answer and the rule it was judged by, never the date. */
    public static final class Decision {

        private final boolean adult;
        private final int minimumAgeYears;

        private Decision(final boolean adult, final int minimumAgeYears) {
            this.adult = adult;
            this.minimumAgeYears = minimumAgeYears;
        }

        /**
         * Whether the declared date of birth met the adult minimum.
         *
         * @return true when the user may proceed
         */
        public boolean isAdult() {
            return adult;
        }

        /**
         * The minimum this decision was judged against, recorded with it.
         *
         * <p>Stored alongside the affirmation so a consent record made under a
         * future stricter minimum is distinguishable from one made under this
         * one - which is what SRS-NFR-LEG-007's per-market tightening will need
         * in order to know whose consent must be taken again.
         *
         * @return the minimum age in whole years
         */
        public int minimumAgeYears() {
            return minimumAgeYears;
        }
    }

    /**
     * Judges a declared date of birth.
     *
     * <p>The caller passes today's date rather than this class reading a clock,
     * so the boundary cases are testable: the day before an eighteenth birthday,
     * the day of it, and a leap-day birth in a non-leap year all have exact
     * answers and no dependence on when the suite runs.
     *
     * @param dateOfBirth the date the user declared; not retained
     * @param today the current date in the user's own calendar
     * @return the verdict
     */
    public static Decision evaluate(final LocalDate dateOfBirth, final LocalDate today) {
        if (dateOfBirth == null || today == null) {
            return new Decision(false, minimumAgeYears());
        }
        if (dateOfBirth.isAfter(today)) {
            return new Decision(false, minimumAgeYears());
        }
        final int years = Period.between(dateOfBirth, today).getYears();
        return new Decision(years >= minimumAgeYears(), minimumAgeYears());
    }

    /**
     * The adult minimum from the constants register.
     *
     * @return ADULT_AGE_MINIMUM.years
     */
    public static int minimumAgeYears() {
        return LumosConstants.ADULT_AGE_MINIMUM_YEARS;
    }
}
