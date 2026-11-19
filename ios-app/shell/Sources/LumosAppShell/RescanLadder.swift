/// Lumos - ios-app/shell / Bounded rescan escalation for Nearby (TRS-ENV-003).
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-ENV-003, SRS-NBY-005, SRS-NFR-BAT-001
/// ADRs: ADR-002
/// Threading: serial-queue
/// Notes: Pure state machine, the Android sibling decision for decision; the
/// register freezes the shape (TRS Sec. 19). Base windows are injected so the
/// shell's CoreBluetooth pacing supplies them; escalation only ever lengthens
/// the on-window, linearly, and only inside the viewing+visible gate.

import Foundation
import LumosProtocolGenerated

/// Escalation state for the Nearby scan cycle.
public final class RescanLadder {

    private var viewing = false
    private var visible = false
    private var stepValue = 0
    private var emptyRun = 0
    private var cycles = 0
    private var maxStep = 0

    /// Refuses register values outside their own bounds.
    public init() {
        precondition(
            (LumosConstants.rescanLadderStepPCTMin...LumosConstants.rescanLadderStepPCTMax)
                .contains(LumosConstants.rescanLadderStepPCT)
                && (LumosConstants.rescanLadderTriggerEmptyCyclesMin
                    ... LumosConstants.rescanLadderTriggerEmptyCyclesMax)
                .contains(LumosConstants.rescanLadderTriggerEmptyCycles)
                && (LumosConstants.rescanLadderMaxStepsMin...LumosConstants.rescanLadderMaxStepsMax)
                .contains(LumosConstants.rescanLadderMaxSteps),
            "rescan ladder outside the register bounds")
    }

    /// Session counters restart when the user opens Nearby.
    public func nearbyViewStarted() {
        viewing = true
        cycles = 0
        maxStep = 0
        reset()
    }

    /// Leaving Nearby drops to base.
    public func nearbyViewEnded() {
        viewing = false
        reset()
    }

    /// Visibility off drops to base: a user who is not discoverable is not
    /// owed extra radio spend hunting for peers.
    public func visibilityChanged(visibleNearby: Bool) {
        visible = visibleNearby
        if !visibleNearby { reset() }
    }

    /// Whether the TRS-ENV-003 gate is open: viewing Nearby, visible.
    public var armed: Bool { viewing && visible }

    /// A cycle finished. Counts only inside the gate; a sighting resets, the
    /// configured run of empty cycles climbs one step, max_steps caps it.
    public func cycleCompleted(sightingsInCycle: Int) {
        guard armed else { return }
        cycles += 1
        if sightingsInCycle > 0 {
            reset()
            return
        }
        emptyRun += 1
        if emptyRun >= LumosConstants.rescanLadderTriggerEmptyCycles,
           stepValue < LumosConstants.rescanLadderMaxSteps {
            stepValue += 1
            emptyRun = 0
            maxStep = max(maxStep, stepValue)
        }
    }

    /// Current ladder step, 0 at base.
    public var step: Int { stepValue }

    /// Cycles completed inside the gate since the view opened.
    public var cyclesRun: Int { cycles }

    /// Highest step this viewing session reached.
    public var maxStepReached: Int { maxStep }

    /// The on-window at the current step: base plus step_pct percent of base
    /// per step, linearly. The off-window is never touched by the ladder.
    public func onMillis(baseOnMillis: Int) -> Int {
        // lumoslint:allow-literal -- percent arithmetic, not a register value
        baseOnMillis * (100 + stepValue * LumosConstants.rescanLadderStepPCT) / 100
    }

    private func reset() {
        stepValue = 0
        emptyRun = 0
    }
}
