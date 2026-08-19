/*
 * Lumos - android-app/app / Confirmation for actions that cannot be undone.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-SAF-009, SRS-IDN-003
 * ADRs: ADR-010
 * Threading: serial-executor
 * Notes: SRS-SAF-009 requires emergency wipe to be "protected against accidental
 * activation" and to "describe irreversible consequences". SRS-IDN-003 requires
 * identity reset to carry a clear warning. Both are the same problem, so both
 * go through this, and the protection is a TYPE rather than a dialog.
 *
 * A Confirmation cannot be constructed by a caller. The only way to obtain one
 * is to issue a Challenge - which is what the warning screen does, and which is
 * therefore the moment the consequences are shown - and then satisfy it by
 * typing the exact phrase inside the window. EmergencyWipe.execute and
 * IdentityReset.reset take a Confirmation as a parameter, so "wipe without
 * showing the warning" is not a mistake that can be made; it does not compile.
 *
 * Three properties, each earning its place:
 *
 * - EXACT PHRASE. A pocket, a toddler or a stray tap cannot type a word. This
 *   is the part that stops accidental activation, and it is why the phrase is
 *   user copy from strings.xml rather than a constant here - it must be in the
 *   user's language, or they will type it without reading it, which is the same
 *   as not having asked.
 * - A WINDOW. A challenge issued and abandoned must not sit in memory ready to
 *   be satisfied by an unrelated later action. Two minutes is long enough to
 *   read a screen of consequences and short enough that the warning was
 *   certainly on screen at the time.
 * - SINGLE USE. One challenge, one confirmation, one action. Without this a
 *   retry loop around a partially failed wipe could re-run it, and re-running a
 *   wipe is harmless while re-running a reset is not.
 *
 * The comparison is deliberately NOT constant-time. There is no secret here:
 * the phrase is printed on the screen the user is looking at, and treating it
 * as a password would be security theatre that implies it is one.
 */

package com.lumos.app.safety;

import java.util.Locale;

/** Turns a shown warning plus a typed phrase into a token irreversible actions demand. */
public final class DeliberateConfirmation {

    /**
     * How long a challenge stays satisfiable, in milliseconds.
     *
     * <p>A named local constant with its rationale, which TRS-CODE-005 permits:
     * it is an interaction timeout, not a protocol or policy value, and putting
     * it in the frozen register would mean a change request to adjust a
     * usability detail. Two minutes is the reading time of the consequences
     * screen with room to spare.
     */
    public static final long WINDOW_MILLIS = 120_000L;

    /** The actions that require deliberate confirmation. */
    public enum Action {

        /** Destroy everything on the device (SRS-SAF-009). */
        EMERGENCY_WIPE,

        /** Discard this identity and create a new one (SRS-IDN-003). */
        IDENTITY_RESET
    }

    /** Refused: the phrase, the window or the single-use rule was not satisfied. */
    public static final class NotConfirmedException extends Exception {

        private static final long serialVersionUID = 1L;

        /**
         * Names what was wrong, in terms a screen can show.
         *
         * @param detail why the confirmation was refused
         */
        NotConfirmedException(final String detail) {
            super(detail);
        }
    }

    /** Proof that the warning was shown and the phrase was typed. Unforgeable. */
    public static final class Confirmation {

        private final Action action;
        private final long confirmedAtUtcMillis;

        private Confirmation(final Action action, final long confirmedAtUtcMillis) {
            this.action = action;
            this.confirmedAtUtcMillis = confirmedAtUtcMillis;
        }

        /**
         * Which action this confirms.
         *
         * <p>Checked by the executor, so a reset confirmation cannot be handed
         * to a wipe.
         *
         * @return the confirmed action
         */
        public Action action() {
            return action;
        }

        /**
         * When the user typed the phrase.
         *
         * @return UTC milliseconds since the epoch
         */
        public long confirmedAtUtcMillis() {
            return confirmedAtUtcMillis;
        }
    }

    /** An outstanding challenge: the warning is on screen and the phrase is awaited. */
    public static final class Challenge {

        private final Action action;
        private final String requiredPhrase;
        private final long issuedAtUtcMillis;
        private boolean spent;

        private Challenge(final Action action, final String requiredPhrase,
                final long issuedAtUtcMillis) {
            this.action = action;
            this.requiredPhrase = requiredPhrase;
            this.issuedAtUtcMillis = issuedAtUtcMillis;
        }

        /**
         * The phrase the user must type, exactly.
         *
         * @return the phrase the warning screen displays
         */
        public String requiredPhrase() {
            return requiredPhrase;
        }

        /**
         * Which action this challenge guards.
         *
         * @return the action
         */
        public Action action() {
            return action;
        }

        /**
         * Satisfies the challenge, producing the token the executor demands.
         *
         * <p>Case and surrounding whitespace are forgiven; the words are not.
         * A user typing carefully into a phone keyboard should not be defeated
         * by an autocapitalised first letter, and nothing accidental produces
         * the right words in the right order in any case.
         *
         * @param typed what the user typed
         * @param nowUtcMillis the current time
         * @return the confirmation token
         * @throws NotConfirmedException if the phrase is wrong, the window has
         *     passed, or this challenge was already satisfied
         */
        public Confirmation confirm(final String typed, final long nowUtcMillis)
                throws NotConfirmedException {
            if (spent) {
                throw new NotConfirmedException("this confirmation was already used");
            }
            if (nowUtcMillis < issuedAtUtcMillis
                    || nowUtcMillis - issuedAtUtcMillis > WINDOW_MILLIS) {
                throw new NotConfirmedException("the confirmation window has passed");
            }
            if (typed == null || !normalise(typed).equals(normalise(requiredPhrase))) {
                throw new NotConfirmedException("the phrase does not match");
            }
            spent = true;
            return new Confirmation(action, nowUtcMillis);
        }
    }

    private DeliberateConfirmation() {
        throw new AssertionError("no instances");
    }

    /**
     * Issues a challenge, which is what showing the consequences screen does.
     *
     * @param action the irreversible action being proposed
     * @param requiredPhrase the phrase from the user-facing copy, in their language
     * @param nowUtcMillis the current time
     * @return the outstanding challenge
     */
    public static Challenge issue(final Action action, final String requiredPhrase,
            final long nowUtcMillis) {
        if (action == null || requiredPhrase == null || requiredPhrase.isBlank()) {
            throw new IllegalArgumentException("a challenge needs an action and a phrase");
        }
        return new Challenge(action, requiredPhrase, nowUtcMillis);
    }

    /**
     * Trims and lower-cases for comparison.
     *
     * @param text the phrase or the typed answer
     * @return the comparable form
     */
    private static String normalise(final String text) {
        return text.trim().toLowerCase(Locale.ROOT);
    }
}
