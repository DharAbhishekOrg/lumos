/// Lumos - ios-app/shell / Tests: the generated design tokens on iOS.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-UX-001, SRS-NFR-A11Y-002, SRS-RMT-005
/// ADRs: -
/// Threading: test-only
/// Notes: tools/tokensgen holds the token file to WCAG AA and the generated
/// files to the token file. This recomputes contrast here, in Swift, from the
/// values the app will actually read - a second, independent implementation,
/// so a bug in the generator's arithmetic cannot pass both.

import Foundation
import LumosProtocol
import XCTest
@testable import LumosAppShell

final class LumosTokensTests: XCTestCase {

    private func luminance(_ rgba: UInt32) -> Double {
        func channel(_ shift: UInt32) -> Double {
            let s = Double((rgba >> shift) & 0xFF) / 255
            return s <= 0.04045 ? s / 12.92 : pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(24) + 0.7152 * channel(16) + 0.0722 * channel(8)
    }

    private func contrast(_ a: UInt32, _ b: UInt32) -> Double {
        let (la, lb) = (luminance(a), luminance(b))
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private func assertReadable(_ fg: LumosTokens.ThemedColor, on bg: LumosTokens.ThemedColor,
                                min ratio: Double, _ what: String) {
        XCTAssertGreaterThanOrEqual(contrast(fg.midnight, bg.midnight), ratio, "Midnight: \(what)")
        XCTAssertGreaterThanOrEqual(contrast(fg.daylight, bg.daylight), ratio, "Daylight: \(what)")
    }

    func testTheBrandValuesAreTheOnesTheOwnerAccepted() {
        XCTAssertEqual(LumosTokens.Color.canvas, .init(midnight: 0x0A0D_1AFF, daylight: 0xF6F6_FBFF))
        XCTAssertEqual(LumosTokens.Color.violet, .init(midnight: 0x9D86_FFFF, daylight: 0x5A3B_E0FF))
        XCTAssertEqual(LumosTokens.Color.scrim.midnight & 0xFF, 0xA3, "the scrim keeps its alpha")
        XCTAssertEqual(LumosTokens.Color.tick, LumosTokens.Color.inkSubtle, "tick aliases ink-subtle")
        XCTAssertEqual(LumosTokens.Text.display.size, 34)
        XCTAssertTrue(LumosTokens.Text.display.display)
        XCTAssertFalse(LumosTokens.Text.body.display, "body text stays the platform face")
    }

    func testTextIsReadableOnEverySurfaceInBothThemes() {
        let color = LumosTokens.Color.self
        for (name, bg) in [("canvas", color.canvas), ("surface", color.surface), ("raised", color.surfaceRaised)] {
            assertReadable(color.ink, on: bg, min: 4.5, "ink on \(name)")
            assertReadable(color.inkMuted, on: bg, min: 4.5, "ink-muted on \(name)")
            assertReadable(color.inkSubtle, on: bg, min: 4.5, "ink-subtle on \(name)")
        }
        assertReadable(color.onViolet, on: color.violet, min: 4.5, "on-violet on violet")
        assertReadable(color.ink, on: color.bubbleOut, min: 4.5, "ink on my bubbles")
        assertReadable(color.ink, on: color.bubbleIn, min: 4.5, "ink on their bubbles")
        assertReadable(color.danger, on: color.surface, min: 4.5, "danger on surface")
    }

    func testEveryTickTintIsATokenVisibleOnBothBubbles() {
        for state in DeliveryMachine.State.allCases {
            let tint = DeliveryTickStyle.of(state).tint
            for bubble in [LumosTokens.Color.bubbleOut, LumosTokens.Color.bubbleIn] {
                assertReadable(tint.color, on: bubble, min: 3, "\(state) tick")
            }
        }
        XCTAssertEqual(DeliveryTickStyle.of(.read).tint.color, LumosTokens.Color.read)
        XCTAssertNotEqual(DeliveryTickStyle.of(.read).tint.color, DeliveryTickStyle.of(.delivered).tint.color,
                          "read and delivered differ in colour as well as in words")
    }
}
