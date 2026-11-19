/*
 * Lumos - android-app/app / Visibility windows: chosen, bounded, self-ending.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-NBY-001, SRS-NBY-002, SRS-NBY-003, TRS-TST-001
 * ADRs: ADR-002, ADR-004
 * Threading: test-only
 * Notes: SRS-NBY-001/002 executed over the real store: enabling means
 * choosing a register-bounded duration or until-venue-exit, the window ends
 * by EVALUATION at exactly the chosen time with nothing able to renew it,
 * and the SRS-NBY-003 indicator reports the same single truth the radio
 * would read - at the boundary millisecond included, because "roughly an
 * hour" is how two components end up disagreeing about whether a person is
 * exposed.
 */

package com.lumos.app.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.Fixtures;
import com.lumos.protocol.LumosConstants;
import org.junit.jupiter.api.Test;

/** The duration semantics of Visible Nearby. */
final class VisibleNearbyDurationTest {

    private static final long NOW = 1_763_600_000_000L;
    private static final long DEFAULT_MILLIS =
            1000L * LumosConstants.VISIBLE_NEARBY_DURATION_SEC_DEFAULT;

    @Test
    void theDefaultSessionEndsExactlyOnTheRegisterDefault() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final DiscoveryVisibility visibility = new DiscoveryVisibility(stack.store());
            visibility.enableAfterExplicitUserRequest(
                    DiscoveryVisibility.Selection.defaultSession(), NOW);

            assertTrue(visibility.isVisibleNearby(NOW + DEFAULT_MILLIS - 1),
                    "the last millisecond of the window is inside it");
            assertFalse(visibility.isVisibleNearby(NOW + DEFAULT_MILLIS),
                    "the window's end is the end (SRS-NBY-002)");
            assertFalse(visibility.isVisibleNearby(NOW + DEFAULT_MILLIS + DEFAULT_MILLIS),
                    "and nothing renews it later, silently or otherwise");
        }
    }

    @Test
    void aCustomDurationIsHeldToTheRegisterBounds() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final DiscoveryVisibility visibility = new DiscoveryVisibility(stack.store());

            assertThrows(IllegalArgumentException.class, () ->
                    DiscoveryVisibility.Selection.customDuration(
                            LumosConstants.VISIBLE_NEARBY_DURATION_SEC_MIN - 1));
            assertThrows(IllegalArgumentException.class, () ->
                    DiscoveryVisibility.Selection.customDuration(
                            LumosConstants.VISIBLE_NEARBY_DURATION_SEC_MAX + 1));

            final int chosen = LumosConstants.VISIBLE_NEARBY_DURATION_SEC_MIN;
            visibility.enableAfterExplicitUserRequest(
                    DiscoveryVisibility.Selection.customDuration(chosen), NOW);
            assertTrue(visibility.isVisibleNearby(NOW + 1000L * chosen - 1));
            assertFalse(visibility.isVisibleNearby(NOW + 1000L * chosen));
        }
    }

    @Test
    void untilVenueExitEndsOnTheVenueEventAndOnlyThen() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final DiscoveryVisibility visibility = new DiscoveryVisibility(stack.store());
            visibility.enableAfterExplicitUserRequest(
                    DiscoveryVisibility.Selection.untilVenueExit(), NOW);

            assertTrue(visibility.isVisibleNearby(NOW + 10 * DEFAULT_MILLIS),
                    "until-venue-exit has no clock; it ends on the event");
            visibility.endOnVenueExit(NOW + 10 * DEFAULT_MILLIS + 1);
            assertFalse(visibility.isVisibleNearby(NOW + 10 * DEFAULT_MILLIS + 1));
        }
    }

    @Test
    void aVenueExitCannotEndATimedSession() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final DiscoveryVisibility visibility = new DiscoveryVisibility(stack.store());
            visibility.enableAfterExplicitUserRequest(
                    DiscoveryVisibility.Selection.defaultSession(), NOW);
            visibility.endOnVenueExit(NOW + 1);
            assertTrue(visibility.isVisibleNearby(NOW + 2),
                    "a stray venue event must not end a window the user chose by time");
        }
    }

    @Test
    void theIndicatorReportsTheModeAndTheEnd() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final DiscoveryVisibility visibility = new DiscoveryVisibility(stack.store());

            DiscoveryVisibility.Indicator off = visibility.indicator(NOW);
            assertFalse(off.visible());
            assertNull(off.mode());

            visibility.enableAfterExplicitUserRequest(
                    DiscoveryVisibility.Selection.defaultSession(), NOW);
            final DiscoveryVisibility.Indicator on = visibility.indicator(NOW);
            assertTrue(on.visible());
            assertEquals(DiscoveryVisibility.Mode.DEFAULT_SESSION, on.mode());
            assertEquals(NOW + DEFAULT_MILLIS, on.expiresAtMillis(),
                    "the indicator shows WHEN it ends, which is what makes it "
                            + "understandable (SRS-NBY-003)");

            off = visibility.indicator(NOW + DEFAULT_MILLIS);
            assertFalse(off.visible(), "the indicator and the radio share one evaluation");
        }
    }
}
