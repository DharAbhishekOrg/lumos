/*
 * Lumos - android-app/app / The rescan ladder, scripted step by step.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-ENV-003, SRS-NFR-BAT-001, TRS-TST-001
 * ADRs: ADR-002
 * Threading: test-only
 * Notes: Every number asserted here is computed FROM the register constants,
 * not hard-coded beside them, so a signed-config retune within bounds changes
 * nothing in this file. The two behaviours with teeth: escalation happens
 * only inside the viewing+visible gate (a background app never climbs, which
 * is how SRS-NFR-BAT-001 stays whole), and the ladder is capped and fully
 * reversible - any sighting, or leaving, drops it to base at once.
 */

package com.lumos.app.ble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.protocol.LumosConstants;
import org.junit.jupiter.api.Test;

/** RescanLadder semantics against the register shape. */
final class RescanLadderTest {

    private static final int TRIGGER = LumosConstants.RESCAN_LADDER_TRIGGER_EMPTY_CYCLES;
    private static final int MAX_STEPS = LumosConstants.RESCAN_LADDER_MAX_STEPS;
    private static final int STEP_PCT = LumosConstants.RESCAN_LADDER_STEP_PCT;

    private final RescanLadder ladder = new RescanLadder();

    private void arm() {
        ladder.nearbyViewStarted();
        ladder.visibilityChanged(true);
    }

    private void emptyCycles(final int n) {
        for (int i = 0; i < n; i++) {
            ladder.cycleCompleted(0);
        }
    }

    @Test
    void outsideTheGateNothingEscalates() {
        // Not viewing at all.
        emptyCycles(TRIGGER * 3);
        assertEquals(0, ladder.step(), "a closed Nearby view never climbs");
        assertEquals(0, ladder.cyclesRun(), "ungated cycles do not even count");

        // Viewing, but not visible: still shut.
        ladder.nearbyViewStarted();
        ladder.visibilityChanged(false);
        assertFalse(ladder.armed());
        emptyCycles(TRIGGER * 3);
        assertEquals(0, ladder.step(), "invisible users are not owed extra radio");
    }

    @Test
    void theConfiguredRunOfEmptyCyclesClimbsExactlyOneStep() {
        arm();
        emptyCycles(TRIGGER - 1);
        assertEquals(0, ladder.step(), "one cycle short of the trigger stays at base");
        emptyCycles(1);
        assertEquals(1, ladder.step(), "the trigger run climbs one step, not a leap");
    }

    @Test
    void theLadderIsCappedAtMaxSteps() {
        arm();
        emptyCycles(TRIGGER * (MAX_STEPS + 3));
        assertEquals(MAX_STEPS, ladder.step(),
                "escalation is bounded by the register, however long the drought");
        assertEquals(MAX_STEPS, ladder.maxStepReached());
    }

    @Test
    void aSightingDropsTheLadderStraightToBase() {
        arm();
        emptyCycles(TRIGGER * MAX_STEPS);
        assertTrue(ladder.step() > 0);
        ladder.cycleCompleted(1);
        assertEquals(0, ladder.step(), "discovery working means no more leaning in");
        assertEquals(MAX_STEPS, ladder.maxStepReached(),
                "the session high-water mark survives for the metric");
    }

    @Test
    void leavingNearbyOrGoingInvisibleResetsTheStep() {
        arm();
        emptyCycles(TRIGGER);
        assertEquals(1, ladder.step());
        ladder.nearbyViewEnded();
        assertEquals(0, ladder.step());

        arm();
        emptyCycles(TRIGGER);
        assertEquals(1, ladder.step());
        ladder.visibilityChanged(false);
        assertEquals(0, ladder.step());
    }

    @Test
    void theOnWindowGrowsLinearlyAndTheOffWindowNever() {
        arm();
        final ScanDutyCycle.Mode mode = ScanDutyCycle.Mode.FOREGROUND;
        final int baseOn = mode.onMillis();
        final int baseOff = mode.offMillis();
        assertEquals(baseOn, ladder.onMillis(mode));

        for (int step = 1; step <= MAX_STEPS; step++) {
            emptyCycles(TRIGGER);
            assertEquals(step, ladder.step());
            assertEquals(baseOn * (100 + step * STEP_PCT) / 100, ladder.onMillis(mode),
                    "each step adds step_pct percent of the BASE window, linearly");
            assertEquals(baseOff, ladder.offMillis(mode),
                    "escalation spends more radio, never less rest between failures");
        }
    }

    @Test
    void sessionCountersRestartWithTheView() {
        arm();
        emptyCycles(TRIGGER);
        assertEquals(TRIGGER, ladder.cyclesRun());
        assertEquals(1, ladder.maxStepReached());

        ladder.nearbyViewEnded();
        arm();
        assertEquals(0, ladder.cyclesRun(), "a new viewing session counts from zero");
        assertEquals(0, ladder.maxStepReached());
    }
}
