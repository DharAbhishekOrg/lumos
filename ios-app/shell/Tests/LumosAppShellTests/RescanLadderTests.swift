/// Lumos - ios-app/shell / The rescan ladder, scripted step by step.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-ENV-003, SRS-NFR-BAT-001, TRS-TST-001
/// ADRs: ADR-002
/// Threading: test-only
/// Notes: The Android suite's assertions, computed FROM the register
/// constants rather than hard-coded beside them: escalation only inside the
/// viewing+visible gate, one step per trigger run, capped at max_steps, and
/// fully reversible the moment anything is sighted or the gate closes.

import LumosProtocolGenerated
import XCTest
@testable import LumosAppShell

final class RescanLadderTests: XCTestCase {

    private let trigger = LumosConstants.rescanLadderTriggerEmptyCycles
    private let maxSteps = LumosConstants.rescanLadderMaxSteps
    private let stepPct = LumosConstants.rescanLadderStepPCT

    private func armed() -> RescanLadder {
        let ladder = RescanLadder()
        ladder.nearbyViewStarted()
        ladder.visibilityChanged(visibleNearby: true)
        return ladder
    }

    private func emptyCycles(_ ladder: RescanLadder, _ n: Int) {
        for _ in 0..<n { ladder.cycleCompleted(sightingsInCycle: 0) }
    }

    func testOutsideTheGateNothingEscalates() {
        let ladder = RescanLadder()
        emptyCycles(ladder, trigger * 3)
        XCTAssertEqual(ladder.step, 0, "a closed Nearby view never climbs")
        XCTAssertEqual(ladder.cyclesRun, 0, "ungated cycles do not even count")

        ladder.nearbyViewStarted()
        ladder.visibilityChanged(visibleNearby: false)
        XCTAssertFalse(ladder.armed)
        emptyCycles(ladder, trigger * 3)
        XCTAssertEqual(ladder.step, 0, "invisible users are not owed extra radio")
    }

    func testTheConfiguredRunOfEmptyCyclesClimbsExactlyOneStep() {
        let ladder = armed()
        emptyCycles(ladder, trigger - 1)
        XCTAssertEqual(ladder.step, 0, "one cycle short of the trigger stays at base")
        emptyCycles(ladder, 1)
        XCTAssertEqual(ladder.step, 1, "the trigger run climbs one step, not a leap")
    }

    func testTheLadderIsCappedAndASightingDropsItToBase() {
        let ladder = armed()
        emptyCycles(ladder, trigger * (maxSteps + 3))
        XCTAssertEqual(ladder.step, maxSteps,
                       "escalation is bounded by the register, however long the drought")
        ladder.cycleCompleted(sightingsInCycle: 1)
        XCTAssertEqual(ladder.step, 0, "discovery working means no more leaning in")
        XCTAssertEqual(ladder.maxStepReached, maxSteps,
                       "the session high-water mark survives for the metric")
    }

    func testLeavingNearbyOrGoingInvisibleResetsTheStep() {
        var ladder = armed()
        emptyCycles(ladder, trigger)
        XCTAssertEqual(ladder.step, 1)
        ladder.nearbyViewEnded()
        XCTAssertEqual(ladder.step, 0)

        ladder = armed()
        emptyCycles(ladder, trigger)
        XCTAssertEqual(ladder.step, 1)
        ladder.visibilityChanged(visibleNearby: false)
        XCTAssertEqual(ladder.step, 0)
    }

    func testTheOnWindowGrowsLinearlyFromTheBase() {
        let ladder = armed()
        let baseOn = LumosConstants.scanWindowFgMSOn
        XCTAssertEqual(ladder.onMillis(baseOnMillis: baseOn), baseOn)
        for step in 1...maxSteps {
            emptyCycles(ladder, trigger)
            XCTAssertEqual(ladder.step, step)
            XCTAssertEqual(ladder.onMillis(baseOnMillis: baseOn),
                           baseOn * (100 + step * stepPct) / 100,
                           "each step adds step_pct percent of the BASE window, linearly")
        }
    }

    func testSessionCountersRestartWithTheView() {
        let ladder = armed()
        emptyCycles(ladder, trigger)
        XCTAssertEqual(ladder.cyclesRun, trigger)
        XCTAssertEqual(ladder.maxStepReached, 1)

        ladder.nearbyViewEnded()
        ladder.nearbyViewStarted()
        ladder.visibilityChanged(visibleNearby: true)
        XCTAssertEqual(ladder.cyclesRun, 0, "a new viewing session counts from zero")
        XCTAssertEqual(ladder.maxStepReached, 0)
    }
}
