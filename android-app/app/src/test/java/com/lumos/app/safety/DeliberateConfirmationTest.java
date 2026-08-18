/*
 * Lumos - android-app/app / Confirmation cannot happen by accident.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-SAF-009, SRS-IDN-003
 * ADRs: ADR-010
 * Threading: any
 * Notes: SRS-SAF-009's "protected against accidental activation" is a claim
 * about what CANNOT happen, so most of these tests assert refusals. The last
 * one is different in kind: it asserts that no caller can manufacture the token
 * at all, which is the property the other refusals rest on - a Confirmation
 * that could be constructed would make every check above it decorative.
 */

package com.lumos.app.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/** The gate in front of the two irreversible actions. */
final class DeliberateConfirmationTest {

    private static final String PHRASE = "erase everything";
    private static final long ISSUED = 1_755_561_600_000L;

    private static DeliberateConfirmation.Challenge wipeChallenge() {
        return DeliberateConfirmation.issue(
                DeliberateConfirmation.Action.EMERGENCY_WIPE, PHRASE, ISSUED);
    }

    @Test
    void theRightPhraseInTimeConfirms() throws Exception {
        final DeliberateConfirmation.Confirmation confirmation =
                wipeChallenge().confirm(PHRASE, ISSUED + 1_000);
        assertSame(DeliberateConfirmation.Action.EMERGENCY_WIPE, confirmation.action());
        assertEquals(ISSUED + 1_000, confirmation.confirmedAtUtcMillis());
    }

    @Test
    void caseAndSurroundingSpaceAreForgiven() throws Exception {
        wipeChallenge().confirm("  Erase Everything  ", ISSUED + 1);
    }

    @Test
    void theWrongWordsAreRefused() {
        for (final String typed : new String[] {"", " ", "erase", "erase everythin",
            "eraseeverything", "delete everything", "yes"}) {
            final DeliberateConfirmation.NotConfirmedException refusal =
                    assertThrows(DeliberateConfirmation.NotConfirmedException.class,
                            () -> wipeChallenge().confirm(typed, ISSUED + 1),
                            "'" + typed + "' must not confirm a wipe");
            assertTrue(refusal.getMessage().contains("phrase"));
        }
    }

    @Test
    void nothingTypedAtAllIsRefused() {
        assertThrows(DeliberateConfirmation.NotConfirmedException.class,
                () -> wipeChallenge().confirm(null, ISSUED + 1));
    }

    @Test
    void anAbandonedChallengeExpires() {
        final DeliberateConfirmation.Challenge challenge = wipeChallenge();
        assertThrows(DeliberateConfirmation.NotConfirmedException.class,
                () -> challenge.confirm(PHRASE,
                        ISSUED + DeliberateConfirmation.WINDOW_MILLIS + 1),
                "a challenge left on a screen must not be satisfiable an hour later");
    }

    @Test
    void theLastMillisecondOfTheWindowStillWorks() throws Exception {
        wipeChallenge().confirm(PHRASE, ISSUED + DeliberateConfirmation.WINDOW_MILLIS);
    }

    @Test
    void aClockThatWentBackwardsIsRefusedRatherThanTrusted() {
        assertThrows(DeliberateConfirmation.NotConfirmedException.class,
                () -> wipeChallenge().confirm(PHRASE, ISSUED - 1));
    }

    @Test
    void aChallengeIsSingleUse() throws Exception {
        final DeliberateConfirmation.Challenge challenge = wipeChallenge();
        challenge.confirm(PHRASE, ISSUED + 1);
        final DeliberateConfirmation.NotConfirmedException second =
                assertThrows(DeliberateConfirmation.NotConfirmedException.class,
                        () -> challenge.confirm(PHRASE, ISSUED + 2));
        assertTrue(second.getMessage().contains("already used"));
    }

    @Test
    void aChallengeNeedsAnActionAndAPhrase() {
        assertThrows(IllegalArgumentException.class,
                () -> DeliberateConfirmation.issue(null, PHRASE, ISSUED));
        assertThrows(IllegalArgumentException.class, () -> DeliberateConfirmation.issue(
                DeliberateConfirmation.Action.EMERGENCY_WIPE, "   ", ISSUED));
        assertThrows(IllegalArgumentException.class, () -> DeliberateConfirmation.issue(
                DeliberateConfirmation.Action.EMERGENCY_WIPE, null, ISSUED));
    }

    @Test
    void aConfirmationCannotBeManufactured() {
        for (final Constructor<?> constructor
                : DeliberateConfirmation.Confirmation.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(constructor.getModifiers()),
                    "a Confirmation any caller could construct would make every "
                            + "check in this class decorative");
        }
        // And there is no factory either: the only public members are accessors.
        for (final java.lang.reflect.Method method
                : DeliberateConfirmation.Confirmation.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) {
                continue;
            }
            assertTrue(method.getReturnType() != DeliberateConfirmation.Confirmation.class,
                    method.getName() + " hands out a Confirmation");
        }
    }
}
