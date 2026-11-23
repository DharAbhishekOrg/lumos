/// Lumos - ios-app/shell / How each delivery state looks and sounds.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-RMT-005, SRS-RMT-013, TRS-UX-001
/// ADRs: ADR-002
/// Threading: main-safe
/// Notes: The Android DeliveryTickStyle decision for decision, per the owner's
/// WhatsApp-style ruling of 2026-11-21: the glyph from the vector-pinned tick
/// mapping, the tint (grey, blue for read, red for failed), and the spoken
/// label from the STATE. Delivered and read share the double tick and differ
/// in tint and in words, so colour is never the only signal. The style is a
/// plain value, tested on any host. The shell never imports SwiftUI (its
/// iPhone-triple CI pass has no UIKit), so the view that renders this value -
/// the symbols overlapped, tinted, one accessibility element - lands with the
/// app target. Each tint is a design token (CR-008), the same ones Android's
/// tick colours resolve to, so both apps draw a state in the same colour.

import Foundation
import LumosProtocol

/// One delivery state's presentation.
public struct DeliveryTickStyle: Equatable, Sendable {

    /// The tint family a tick is drawn in.
    public enum Tint: String, Sendable {
        /// Queued or sending.
        case pending
        /// Sent or delivered: grey.
        case grey
        /// Read: blue.
        case read
        /// Failed: red.
        case failed
        /// Waiting for a nearby path.
        case nearby

        /// The design token this tint is drawn in, per theme.
        public var color: LumosTokens.ThemedColor {
            switch self {
            case .pending, .grey: return LumosTokens.Color.tick
            case .read: return LumosTokens.Color.read
            case .failed: return LumosTokens.Color.danger
            case .nearby: return LumosTokens.Color.aqua
            }
        }
    }

    /// SF Symbol names, drawn overlapped left to right (two for a double tick).
    public let symbols: [String]
    /// The tint.
    public let tint: Tint
    /// The spoken label (VoiceOver), distinct per state.
    public let accessibilityLabel: String

    /// The presentation of a state.
    public static func of(_ state: DeliveryMachine.State) -> DeliveryTickStyle {
        let tick = DeliveryMachine.tick(of: state)
        return DeliveryTickStyle(symbols: symbols(for: tick), tint: tint(for: tick),
                                 accessibilityLabel: label(for: state))
    }

    static func symbols(for tick: DeliveryMachine.Tick) -> [String] {
        switch tick {
        case .clock: return ["clock"]
        case .single: return ["checkmark"]
        case .double, .doubleRead: return ["checkmark", "checkmark"]
        case .failed: return ["exclamationmark.circle.fill"]
        case .nearby: return ["dot.radiowaves.left.and.right"]
        }
    }

    static func tint(for tick: DeliveryMachine.Tick) -> Tint {
        switch tick {
        case .clock: return .pending
        case .single, .double: return .grey
        case .doubleRead: return .read
        case .failed: return .failed
        case .nearby: return .nearby
        }
    }

    static func label(for state: DeliveryMachine.State) -> String {
        switch state {
        case .queued: return "Waiting to send"
        case .sending: return "Sending"
        case .sent: return "Sent"
        case .delivered: return "Delivered"
        case .read: return "Read"
        case .failed: return "Not sent. Tap to retry."
        case .localOnlyWaiting: return "Sends when you're near each other"
        }
    }
}
