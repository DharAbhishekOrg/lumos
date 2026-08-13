/// Lumos - ios-app/shell / The wipe ORDER is the property under test.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-SAF-010, SRS-NFR-PRI-012
/// ADRs: ADR-003, ADR-010
/// Threading: any
/// Notes: The owner confirmed keys-first as normative on crypto-erasure
/// rationale (2026-08-14). These tests assert the ORDER and the interruption
/// property, not merely the end state - a wipe that finishes clean in the
/// happy path can still leave plaintext recoverable when interrupted, and
/// that difference is invisible to an end-state assertion.

import Foundation
import XCTest
@testable import LumosAppShell

/// A test error for simulated stage failures.
private struct WipeStageFailure: Error, CustomStringConvertible {
    let description: String
}

/// Order and interruption behaviour of the keys-first wipe.
final class KeysFirstWipeTests: XCTestCase {

    func testKeyStagesRunBeforeContentRegardlessOfRegistrationOrder() {
        var order: [String] = []
        let report = KeysFirstWipe()
            .registerContent("profile") { order.append("profile") }
            .registerKeyMaterial("kek") { order.append("kek") }
            .registerContent("media") { order.append("media") }
            .registerKeyMaterial("envelopes") { order.append("envelopes") }
            .execute()

        XCTAssertEqual(order, ["kek", "envelopes", "profile", "media"],
                       "every key-material stage must run before any content stage")
        XCTAssertTrue(report.fullyClean)
        XCTAssertTrue(report.allKeyMaterialDestroyed)
    }

    func testContentFailureAfterKeyPhaseStillLeavesCryptoErasure() throws {
        let kek = SoftwareKekStore()
        let hierarchy = try DataKeyHierarchy.create(kek: kek)

        let report = KeysFirstWipe()
            .registerKeyMaterial("kek") { kek.destroy() }
            .registerContent("profile") { throw WipeStageFailure(description: "store unavailable mid-wipe") }
            .execute()

        XCTAssertFalse(report.fullyClean, "the content failure must be reported, not swallowed")
        XCTAssertTrue(report.allKeyMaterialDestroyed,
                      "the guarantee: key material was destroyed before content was attempted")
        XCTAssertTrue(kek.isDestroyed)
        XCTAssertThrowsError(try hierarchy.deriveKey(for: .database),
                             "whatever the interrupted content phase left behind is ciphertext without a key")
    }

    func testAFailingKeyStageDoesNotStopTheOtherKeyStages() {
        var order: [String] = []
        let report = KeysFirstWipe()
            .registerKeyMaterial("first") { throw WipeStageFailure(description: "refused") }
            .registerKeyMaterial("second") { order.append("second") }
            .registerContent("content") { order.append("content") }
            .execute()

        XCTAssertEqual(order, ["second", "content"],
                       "as much key material as possible is destroyed even when one stage fails")
        XCTAssertFalse(report.allKeyMaterialDestroyed,
                       "a failed key stage must be visible: the crypto-erasure guarantee does NOT hold")
        XCTAssertEqual(report.results.first?.failure, "refused")
    }

    func testReportRecordsPhaseNameAndOutcomePerStage() {
        let report = KeysFirstWipe()
            .registerKeyMaterial("kek") {}
            .registerContent("cache") {}
            .execute()
        XCTAssertEqual(report.results.count, 2)
        XCTAssertEqual(report.results[0].phase, .keyMaterial)
        XCTAssertEqual(report.results[0].name, "kek")
        XCTAssertTrue(report.results[0].succeeded)
        XCTAssertEqual(report.results[1].phase, .content)
    }
}
