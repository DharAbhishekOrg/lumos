/*
 * Lumos - android-app/app / The bounded escalating rescan ladder (TRS-ENV-003).
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-ENV-003, SRS-NBY-005, SRS-NFR-BAT-001
 * ADRs: ADR-002
 * Threading: serial-executor
 * Notes: The false-negative mitigation: when the user is LOOKING at Nearby
 * with visibility on and cycles keep coming back empty, scanning leans in
 * instead of shrugging. The ladder is a pure state machine - the cycle owner
 * reports each completed cycle's sighting count and asks what the next
 * on-window should be - so every escalation is a function of scripted inputs
 * (TRS-TST-001). The register freezes the whole shape (TRS Sec. 19):
 * RESCAN_LADDER.trigger_empty_cycles consecutive empty cycles advance one
 * step, each step lengthens the on-window by step_pct percent of the base
 * (linear, off-window untouched), and max_steps caps it. Any sighting, or
 * leaving Nearby, or visibility going off, drops straight back to the base
 * cycle - the battery cost is bounded by the user's own attention, which is
 * why this gate never applies in the background (SRS-NFR-BAT-001 stays whole).
 */

package com.lumos.app.ble;

import com.lumos.protocol.LumosConstants;

/** Escalates the scan-on window while Nearby stares at an empty venue. */
public final class RescanLadder {

    private boolean viewing;
    private boolean visible;
    private int step;
    private int emptyRun;
    private int cyclesRun;
    private int maxStepReached;

    /** Builds a ladder, refusing register values outside their own bounds. */
    public RescanLadder() {
        if (LumosConstants.RESCAN_LADDER_STEP_PCT < LumosConstants.RESCAN_LADDER_STEP_PCT_MIN
                || LumosConstants.RESCAN_LADDER_STEP_PCT
                        > LumosConstants.RESCAN_LADDER_STEP_PCT_MAX
                || LumosConstants.RESCAN_LADDER_TRIGGER_EMPTY_CYCLES
                        < LumosConstants.RESCAN_LADDER_TRIGGER_EMPTY_CYCLES_MIN
                || LumosConstants.RESCAN_LADDER_TRIGGER_EMPTY_CYCLES
                        > LumosConstants.RESCAN_LADDER_TRIGGER_EMPTY_CYCLES_MAX
                || LumosConstants.RESCAN_LADDER_MAX_STEPS
                        < LumosConstants.RESCAN_LADDER_MAX_STEPS_MIN
                || LumosConstants.RESCAN_LADDER_MAX_STEPS
                        > LumosConstants.RESCAN_LADDER_MAX_STEPS_MAX) {
            throw new IllegalStateException("rescan ladder outside the register bounds");
        }
    }

    /** The user opened Nearby; session counters start from zero. */
    public void nearbyViewStarted() {
        viewing = true;
        cyclesRun = 0;
        maxStepReached = 0;
        reset();
    }

    /** The user left Nearby; the ladder drops to base. */
    public void nearbyViewEnded() {
        viewing = false;
        reset();
    }

    /**
     * Visibility changed. Off drops the ladder to base: a user who is not
     * discoverable is not owed extra radio spend hunting for peers.
     *
     * @param visibleNearby whether Visible Nearby is on
     */
    public void visibilityChanged(final boolean visibleNearby) {
        this.visible = visibleNearby;
        if (!visibleNearby) {
            reset();
        }
    }

    /** @return whether the TRS-ENV-003 gate is open: viewing Nearby, visible */
    public boolean armed() {
        return viewing && visible;
    }

    /**
     * A scan cycle finished. Only cycles inside the gate count; a sighting
     * resets the ladder, and the configured run of empty cycles climbs it.
     *
     * @param sightingsInCycle how many Lumos-shaped sightings the cycle saw
     */
    public void cycleCompleted(final int sightingsInCycle) {
        if (!armed()) {
            return;
        }
        cyclesRun++;
        if (sightingsInCycle > 0) {
            reset();
            return;
        }
        emptyRun++;
        if (emptyRun >= LumosConstants.RESCAN_LADDER_TRIGGER_EMPTY_CYCLES
                && step < LumosConstants.RESCAN_LADDER_MAX_STEPS) {
            step++;
            emptyRun = 0;
            if (step > maxStepReached) {
                maxStepReached = step;
            }
        }
    }

    /** @return the current ladder step, 0 at base */
    public int step() {
        return step;
    }

    /** @return cycles completed inside the gate since the view opened */
    public int cyclesRun() {
        return cyclesRun;
    }

    /** @return the highest step this viewing session reached */
    public int maxStepReached() {
        return maxStepReached;
    }

    /**
     * The scan-on window at the current step: the base window plus step_pct
     * percent of it per step, linearly.
     *
     * @param mode the calibrated base profile
     * @return the on-window in milliseconds
     */
    public int onMillis(final ScanDutyCycle.Mode mode) {
        return mode.onMillis()
                * (100 + step * LumosConstants.RESCAN_LADDER_STEP_PCT) / 100;
    }

    /**
     * The off-window, which the ladder never touches: escalation spends more
     * radio, never less rest between failures.
     *
     * @param mode the calibrated base profile
     * @return the off-window in milliseconds
     */
    public int offMillis(final ScanDutyCycle.Mode mode) {
        return mode.offMillis();
    }

    private void reset() {
        step = 0;
        emptyRun = 0;
    }
}
