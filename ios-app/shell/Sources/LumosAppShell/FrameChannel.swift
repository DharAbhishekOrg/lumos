/// Lumos - ios-app/shell / Blobs over the paced, fragmenting Apple link.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-TRN-002, TRS-TRN-004, TRS-PROTO-010
/// ADRs: ADR-002, ADR-004
/// Threading: serial-executor
/// Notes: The Apple sibling of Android's FrameLink, over the SAME
/// vector-pinned fragmentation layer (LumosProtocol's S2a code - the bytes
/// the conformance suite holds green are the bytes this channel sends). An
/// outbound blob splits at the link's MTU budget and rides the paced queue
/// one write at a time; blobs are enqueued whole and the queue is FIFO, so
/// two sends can never interleave their fragments - which the single-assembly
/// receiver would treat as a restart. Inbound fragments the reassembler
/// rejects drop silently with their stable code counted: the sender's retry
/// budget is the recovery path (TRS Sec. 9.4).

import Foundation
import LumosProtocol

/// Sends and receives opaque blobs over one fragmenting link direction.
public final class FrameChannel {

    private let queue: PacedWriteQueue
    private let reassembler: FragmentReassembler
    private let onBlob: (Data) -> Void
    private var dropCounts: [String: Int] = [:]
    private var assemblyId: UInt8 = 0

    /// Creates a channel over a paced link.
    /// - Parameters:
    ///   - queue: the paced write queue (TRS-TRN-004)
    ///   - reassembler: the inbound reassembler, timeout per carrying link
    ///   - onBlob: where completed inbound blobs go
    public init(queue: PacedWriteQueue, reassembler: FragmentReassembler,
                onBlob: @escaping (Data) -> Void) {
        self.queue = queue
        self.reassembler = reassembler
        self.onBlob = onBlob
    }

    /// Sends one blob: split at the MTU budget, fragments enqueued in order.
    /// - Parameters:
    ///   - blob: the bytes to carry; never parsed here
    ///   - negotiatedMtu: the link's negotiated ATT MTU
    /// - Throws: ``LumosCrypto/WireError`` when the blob cannot be split
    public func send(_ blob: Data, negotiatedMtu: Int) throws {
        let fragments = try Fragmentation.split(
            blob, assemblyID: nextAssemblyId(),
            chunkPayload: Fragmentation.payloadCap(negotiatedMTU: negotiatedMtu))
        for fragment in fragments {
            queue.enqueue(fragment)
        }
    }

    /// Feeds one received fragment.
    /// - Parameters:
    ///   - fragment: the fragment bytes as received
    ///   - nowMillis: the caller's clock
    public func onFragmentReceived(_ fragment: Data, nowMillis: Int64) {
        do {
            let offer = try reassembler.offer(fragment, nowMillis: nowMillis)
            if offer.outcome == .complete, let payload = offer.payload {
                onBlob(payload)
            }
        } catch let error as LumosCrypto.WireError {
            // Silent by design: the sender's retry budget recovers, and the
            // count is how tests and diagnostics see the path run.
            dropCounts[error.code, default: 0] += 1
        } catch {
            dropCounts["unexpected", default: 0] += 1
        }
    }

    /// How many inbound fragments were rejected under a stable code.
    /// - Parameter code: the stable error code
    /// - Returns: the rejection count for that code
    public func drops(_ code: String) -> Int {
        dropCounts[code, default: 0]
    }

    private func nextAssemblyId() -> UInt8 {
        let id = assemblyId
        assemblyId = assemblyId &+ 1
        return id
    }
}
