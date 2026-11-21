/// Lumos - ios-app/shell / The ticks look and sound the way the owner ruled.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-RMT-005, SRS-RMT-013, TRS-TST-001
/// ADRs: ADR-002
/// Threading: test-only
/// Notes: The Android DeliveryTickStyleTest on this platform: the
/// WhatsApp-style glyph and tint per state, a distinct spoken label per
/// state, and no plumbing vocabulary in any label.

import Foundation
import LumosProtocol
import XCTest
@testable import LumosAppShell

final class DeliveryTicksTests: XCTestCase {

    func testTheTicksFollowTheOwnersWhatsAppStyleRuling() {
        XCTAssertEqual(DeliveryTickStyle.of(.queued).symbols, ["clock"])
        XCTAssertEqual(DeliveryTickStyle.of(.sending).symbols, ["clock"])
        XCTAssertEqual(DeliveryTickStyle.of(.sent).symbols, ["checkmark"])
        XCTAssertEqual(DeliveryTickStyle.of(.delivered).symbols, ["checkmark", "checkmark"])
        XCTAssertEqual(DeliveryTickStyle.of(.read).symbols, ["checkmark", "checkmark"])
        XCTAssertEqual(DeliveryTickStyle.of(.delivered).tint, .grey, "delivered is two GREY ticks")
        XCTAssertEqual(DeliveryTickStyle.of(.read).tint, .read, "read is two BLUE ticks")
        XCTAssertEqual(DeliveryTickStyle.of(.failed).tint, .failed)
        XCTAssertEqual(DeliveryTickStyle.of(.localOnlyWaiting).tint, .nearby)
    }

    func testEveryStateHasItsOwnSpokenLabel() {
        let labels = DeliveryMachine.State.allCases.map { DeliveryTickStyle.of($0).accessibilityLabel }
        XCTAssertEqual(Set(labels).count, labels.count, "each state says something different")
        XCTAssertNotEqual(DeliveryTickStyle.of(.delivered).accessibilityLabel,
                          DeliveryTickStyle.of(.read).accessibilityLabel,
                          "delivered and read must differ in words, not only in colour")
    }

    func testNoLabelTellsTheUserHowTheMessageTravelled() throws {
        let banned = ["relay", "server", "upload", "ack", "packet", "envelope", "bluetooth"]
        for state in DeliveryMachine.State.allCases {
            let label = DeliveryTickStyle.of(state).accessibilityLabel.lowercased()
            XCTAssertFalse(label.isEmpty)
            for word in banned {
                let pattern = try NSRegularExpression(pattern: "\\b\(word)\\b")
                let range = NSRange(label.startIndex..., in: label)
                XCTAssertNil(pattern.firstMatch(in: label, range: range),
                             "\(state) says '\(word)': labels say where a message stands")
            }
        }
    }
}
