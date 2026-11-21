/// Lumos - ios-app/shell / How each delivery state looks and sounds.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-RMT-005, SRS-RMT-013
/// ADRs: ADR-002
/// Threading: main-safe
/// Notes: The Android DeliveryTickStyle decision for decision, per the owner's
/// WhatsApp-style ruling of 2026-11-21: the glyph from the vector-pinned tick
/// mapping, the tint (grey, blue for read, red for failed), and the spoken
/// label from the STATE. Delivered and read share the double tick and differ
/// in tint and in words, so colour is never the only signal. The style is a
/// plain value, tested on any host; the SwiftUI view is a thin renderer of it.

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

#if canImport(SwiftUI)
import SwiftUI

/// Renders a message's delivery ticks, WhatsApp-style.
@available(iOS 17.0, macOS 13.0, *)
public struct DeliveryTicksView: View {

    private let style: DeliveryTickStyle

    /// Draws the ticks for a state.
    public init(state: DeliveryMachine.State) {
        self.style = DeliveryTickStyle.of(state)
    }

    public var body: some View {
        HStack(spacing: -6) {
            ForEach(Array(style.symbols.enumerated()), id: \.offset) { _, name in
                Image(systemName: name).font(.caption2.weight(.semibold))
            }
        }
        .foregroundStyle(color)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(style.accessibilityLabel)
    }

    private var color: Color {
        switch style.tint {
        case .pending, .grey: return .secondary
        case .read: return .blue
        case .failed: return .red
        case .nearby: return .teal
        }
    }
}
#endif
