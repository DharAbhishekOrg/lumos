/// Lumos - LumosProtocol / The outgoing-message delivery machine, mirrored.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-RMT-005, SRS-RMT-014, SRS-TRN-008, TRS-PAT-002
/// ADRs: ADR-002
/// Threading: any
/// Notes: The Go reference row for row, held to it by the committed
/// chat/delivery_states.json vectors. Only the recipient's receipts reach
/// delivered or read; receipts never move a message backward; a recipient
/// receipt overrides a local failure. The tick mapping is the owner's
/// WhatsApp-style ruling of 2026-11-21.

import Foundation

/// The SRS-RMT-005 delivery machine and its ticks.
public enum DeliveryMachine {

    /// Where one outgoing message stands.
    public enum State: String, Sendable, CaseIterable {
        case queued
        case sending
        case sent
        case delivered
        case read
        case failed
        case localOnlyWaiting = "local_only_waiting"
    }

    /// One typed input.
    public enum Event: String, Sendable, CaseIterable {
        case sendStarted = "send_started"
        case transportAccepted = "transport_accepted"
        case transportRetryable = "transport_retryable"
        case attemptsExhausted = "attempts_exhausted"
        case deliveryReceipt = "delivery_receipt"
        case readReceipt = "read_receipt"
        case userRetry = "user_retry"
        case localPathUnavailable = "local_path_unavailable"
        case localPathAvailable = "local_path_available"
    }

    /// The glyph a state renders as (owner ruling 2026-11-21).
    public enum Tick: String, Sendable, CaseIterable {
        /// Queued or sending.
        case clock
        /// Sent: one grey tick.
        case single
        /// Delivered: two grey ticks.
        case double
        /// Read: two blue ticks.
        case doubleRead = "double_read"
        /// Failed: red mark, tap to retry.
        case failed
        /// Waiting for a nearby path.
        case nearby
    }

    private static let table: [State: [Event: State]] = [
        .queued: [.sendStarted: .sending, .localPathUnavailable: .localOnlyWaiting],
        .sending: [.transportAccepted: .sent, .transportRetryable: .queued,
                   .attemptsExhausted: .failed],
        .failed: [.userRetry: .queued],
        .localOnlyWaiting: [.localPathAvailable: .queued]
    ]

    /// Applies one event; unlisted pairs absorb.
    public static func step(_ state: State, _ event: Event) -> State {
        switch event {
        case .deliveryReceipt:
            return rank(.delivered) > rank(state) ? .delivered : state
        case .readReceipt:
            return rank(.read) > rank(state) ? .read : state
        default:
            return table[state]?[event] ?? state
        }
    }

    /// The glyph a state renders as.
    public static func tick(of state: State) -> Tick {
        switch state {
        case .sent: return .single
        case .delivered: return .double
        case .read: return .doubleRead
        case .failed: return .failed
        case .localOnlyWaiting: return .nearby
        case .queued, .sending: return .clock
        }
    }

    /// SRS-RMT-014: a delivery receipt always goes back; a read receipt only
    /// when the user allows them and has opened the message.
    public static func receiptsToSend(readReceiptsEnabled: Bool,
                                      opened: Bool) -> (delivery: Bool, read: Bool) {
        (true, readReceiptsEnabled && opened)
    }

    private static func rank(_ state: State) -> Int {
        switch state {
        case .sent: return 1
        case .delivered: return 2
        case .read: return 3
        default: return 0
        }
    }
}
