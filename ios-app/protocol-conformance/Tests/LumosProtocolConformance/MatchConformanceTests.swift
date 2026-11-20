/// Lumos - LumosProtocolConformance / The match family, replayed on Swift.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-MAT-004, SRS-MAT-005, SRS-MAT-007, SRS-MAT-014, TRS-PAT-002,
///             TRS-TST-001
/// ADRs: ADR-002, ADR-008
/// Threading: test-only
/// Notes: The Swift quarter of the match family: committed derivations
/// reproduce byte for byte, committed timelines replay step for step, and
/// the family-level silence and single-door scans run over the vectors as
/// data on this platform too - the same contract the Go reference and the
/// Java port answer to.

import Foundation
import XCTest
@testable import LumosProtocol

final class MatchConformanceTests: XCTestCase {

    private var root: URL { Vectors.repoRoot() }
    private let path = "test-vectors/matches/match_timelines.json"

    func testCommittedDerivationsReproduceByteForByte() throws {
        let file = try Vectors.load(path, root: root)
        let cases = try Vectors.array(file, "derivations")
        XCTAssertFalse(cases.isEmpty, "an empty derivation set proves nothing")
        for c in cases {
            let name = try Vectors.string(c, "name")
            let a = try Vectors.hex(c, "peer_a_hex")
            let b = try Vectors.hex(c, "peer_b_hex")
            XCTAssertEqual(try Vectors.string(c, "match_id_hex"),
                           Hex.encode(try MatchID.derive(a, b)),
                           "\(name): identifier")
            XCTAssertEqual(try Vectors.string(c, "match_id_hex"),
                           Hex.encode(try MatchID.derive(b, a)),
                           "\(name): argument order must not matter (SRS-MAT-007)")
            XCTAssertEqual(try Vectors.string(c, "initiator_on_race_hex"),
                           Hex.encode(MatchID.initiatorOnRace(a, b)),
                           "\(name): tiebreak")
        }
    }

    func testCommittedTimelinesReplayStepForStep() throws {
        let file = try Vectors.load(path, root: root)
        let scripts = try Vectors.array(file, "scripts")
        XCTAssertFalse(scripts.isEmpty, "an empty script set proves nothing")
        for script in scripts {
            let name = try Vectors.string(script, "name")
            var state = MatchMachine.State.none
            for (i, stepObj) in try Vectors.array(script, "steps").enumerated() {
                let eventName = try Vectors.string(stepObj, "event")
                let event = try XCTUnwrap(MatchMachine.Event(rawValue: eventName),
                                          "\(name) step \(i): unknown event \(eventName)")
                let result = MatchMachine.step(state, event)
                XCTAssertEqual(try Vectors.string(stepObj, "expect_state"),
                               result.state.rawValue, "\(name) step \(i): state")
                XCTAssertEqual(try Vectors.string(stepObj, "expect_effect"),
                               result.effect.rawValue, "\(name) step \(i): effect")
                state = result.state
            }
        }
    }

    func testTheFamilyKeepsTheSilenceAndTheSingleDoorOnThisPlatformToo() throws {
        let file = try Vectors.load(path, root: root)
        for script in try Vectors.array(file, "scripts") {
            for stepObj in try Vectors.array(script, "steps") {
                let event = try Vectors.string(stepObj, "event")
                let effect = try Vectors.string(stepObj, "expect_effect")
                if event == "local_decline" || event == "local_ignore" {
                    XCTAssertEqual(effect, "none",
                                   "a decline/ignore step carries an effect (SRS-MAT-004)")
                }
                if effect.contains("establish") {
                    XCTAssertTrue(event == "local_accept" || event == "remote_accept",
                                  "an establishing effect without an acceptance (SRS-MAT-005)")
                }
            }
        }
    }
}
