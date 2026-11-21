/// Lumos - LumosProtocolConformance / The delivery family, replayed on Swift.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-RMT-005, SRS-RMT-014, TRS-PAT-002, TRS-TST-001
/// ADRs: ADR-002
/// Threading: test-only
/// Notes: The Java suite on this platform: ticks, timelines and receipt
/// policy from the committed file, and the guardrail walked over every
/// state and event.

import Foundation
import XCTest
@testable import LumosProtocol

final class DeliveryConformanceTests: XCTestCase {

    private var root: URL { Vectors.repoRoot() }
    private let path = "test-vectors/chat/delivery_states.json"

    func testCommittedTicksMatch() throws {
        let file = try Vectors.load(path, root: root)
        let cases = try Vectors.array(file, "ticks")
        XCTAssertFalse(cases.isEmpty, "an empty tick table proves nothing")
        for c in cases {
            let name = try Vectors.string(c, "state")
            let state = try XCTUnwrap(DeliveryMachine.State(rawValue: name), "unknown state \(name)")
            XCTAssertEqual(try Vectors.string(c, "tick"), DeliveryMachine.tick(of: state).rawValue,
                           "\(name): tick")
        }
    }

    func testCommittedTimelinesReplayStepForStep() throws {
        let file = try Vectors.load(path, root: root)
        let scripts = try Vectors.array(file, "scripts")
        XCTAssertFalse(scripts.isEmpty, "an empty script set proves nothing")
        for script in scripts {
            let name = try Vectors.string(script, "name")
            var state = DeliveryMachine.State.queued
            for (i, step) in try Vectors.array(script, "steps").enumerated() {
                let eventName = try Vectors.string(step, "event")
                let event = try XCTUnwrap(DeliveryMachine.Event(rawValue: eventName),
                                          "\(name) step \(i): unknown event \(eventName)")
                state = DeliveryMachine.step(state, event)
                XCTAssertEqual(try Vectors.string(step, "expect_state"), state.rawValue,
                               "\(name) step \(i): state")
                XCTAssertEqual(try Vectors.string(step, "expect_tick"),
                               DeliveryMachine.tick(of: state).rawValue, "\(name) step \(i): tick")
            }
        }
    }

    func testCommittedReceiptPolicyMatches() throws {
        let file = try Vectors.load(path, root: root)
        for c in try Vectors.array(file, "receipt_policy") {
            let enabled = try XCTUnwrap(c["read_receipts_enabled"] as? Bool)
            let opened = try XCTUnwrap(c["opened"] as? Bool)
            let receipts = DeliveryMachine.receiptsToSend(readReceiptsEnabled: enabled, opened: opened)
            XCTAssertEqual(receipts.delivery, try XCTUnwrap(c["send_delivery"] as? Bool))
            XCTAssertEqual(receipts.read, try XCTUnwrap(c["send_read"] as? Bool))
        }
    }

    func testOnlyTheRecipientCanProduceDeliveredOrReadOnThisPlatform() {
        for state in DeliveryMachine.State.allCases {
            for event in DeliveryMachine.Event.allCases {
                let next = DeliveryMachine.step(state, event)
                let entered = next != state && (next == .delivered || next == .read)
                if entered {
                    XCTAssertTrue(event == .deliveryReceipt || event == .readReceipt,
                                  "\(state) + \(event) reached \(next) without a recipient receipt")
                }
            }
        }
    }
}
