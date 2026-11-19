/// Lumos - ios-app/shell / Paced writes on canSendWriteWithoutResponse.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-IOS-003, TRS-TRN-004, TRS-TRN-002
/// ADRs: ADR-002
/// Threading: serial-executor
/// Notes: TRS-TRN-004 verbatim: iOS BLE shall queue writes and pause when
/// canSendWriteWithoutResponse is false. CoreBluetooth drops unacknowledged
/// writes silently when its internal buffer is full, so the pacing is a
/// correctness property, not a politeness. The queue drains while the gate
/// says yes, pauses the instant it says no, and resumes on the delegate's
/// peripheralIsReady signal - both injected closures, so every pacing
/// behaviour executes in `swift test` with a scripted gate. A connection
/// epoch mirrors the Android queue (TRS-TRN-003's sibling): a reconnect
/// discards everything queued against the old connection, because the new one
/// renegotiates its MTU and shares no state with it.

import Foundation

/// FIFO write queue gated on the link's readiness signal.
public final class PacedWriteQueue {

    private let write: (Data) -> Void
    private let canSend: () -> Bool
    private var pending: [Data] = []
    private var epochValue: Int64 = 0

    /// Creates a queue over a link's write path.
    /// - Parameters:
    ///   - canSend: the link's readiness gate (canSendWriteWithoutResponse)
    ///   - write: issues one write on the link
    public init(canSend: @escaping () -> Bool, write: @escaping (Data) -> Void) {
        self.canSend = canSend
        self.write = write
    }

    /// The current connection epoch.
    public var epoch: Int64 { epochValue }

    /// How many writes are queued and not yet issued.
    public var queued: Int { pending.count }

    /// Enqueues one write and drains as far as the gate allows.
    /// - Parameter data: the bytes to write
    public func enqueue(_ data: Data) {
        pending.append(data)
        drain()
    }

    /// The delegate's readiness signal: drains whatever the pause held back.
    public func resume() {
        drain()
    }

    /// Advances the connection epoch after a disconnect or reconnect,
    /// discarding everything queued against the old connection.
    /// - Returns: how many queued writes were discarded
    @discardableResult
    public func advanceEpoch() -> Int {
        epochValue += 1
        let dropped = pending.count
        pending.removeAll()
        return dropped
    }

    private func drain() {
        while !pending.isEmpty && canSend() {
            write(pending.removeFirst())
        }
    }
}
