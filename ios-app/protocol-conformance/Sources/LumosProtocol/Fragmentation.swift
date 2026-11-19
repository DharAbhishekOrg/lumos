/// Lumos - protocol-conformance / Swift transport fragmentation per TRS-PROTO-010.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-PROTO-010, TRS-TRN-002, TRS-PROTO-007
/// ADRs: ADR-004
/// Threading: serial-per-link
/// Notes: The Swift port of protocol/wire/fragment.go, held to the Go reference
/// and the Java port by test-vectors/framing/fragmentation.json. The layer is
/// independent of semantic message framing: it carries opaque bytes and never
/// parses a frame. Layout per the constants register (transport.FRAG_HEADER_LEN
/// notes): byte 0 flags, byte 1 assembly_id, bytes 2-3 fragment_index,
/// big-endian; a FIRST fragment's content starts with a u32 total length so
/// bounds are validated BEFORE any reassembly allocation (TRS Sec. 9.4). The
/// reassembler is single-assembly (a serialized GATT link carries one message
/// at a time per direction) and its timeout is lazy, applied from the
/// caller-injected millisecond clock - which is what lets the golden vectors
/// script every timing behaviour.

import Foundation
import LumosProtocolGenerated

/// Splits opaque blobs into link fragments and rebuilds them (TRS-PROTO-010).
public enum Fragmentation {

    /// Fixed fragment header length.
    static let headerLen = LumosConstants.fragHeaderLenDefault

    /// u32 total-length prefix carried in a FIRST fragment.
    static let totalPrefixLen = LumosConstants.fragTotalPrefixLenDefault

    /// Derives the per-fragment content budget from a negotiated ATT MTU: the
    /// register rule negotiated_mtu_minus_7_floor_20.
    /// - Parameter negotiatedMTU: the ATT MTU this link negotiated
    /// - Returns: the content budget per fragment, excluding the 4-byte header
    public static func payloadCap(negotiatedMTU: Int) -> Int {
        max(negotiatedMTU - LumosConstants.bleChunkPayloadSubtract,
            LumosConstants.bleChunkPayloadFloor)
    }

    /// Fragments an opaque blob for one link. The blob is never parsed.
    /// - Parameters:
    ///   - blob: the bytes to carry
    ///   - assemblyID: the sender's per-link assembly identifier
    ///   - chunkPayload: the content budget per fragment excluding the header
    /// - Returns: the fragments, in send order
    /// - Throws: ``LumosCrypto/WireError`` with the exact stable code
    public static func split(_ blob: Data, assemblyID: UInt8, chunkPayload: Int) throws -> [Data] {
        guard chunkPayload >= LumosConstants.bleChunkPayloadFloor else {
            throw LumosCrypto.WireError(LumosErrors.errFragHeaderMalformed,
                                        "chunk payload below the register floor")
        }
        guard !blob.isEmpty else {
            throw LumosCrypto.WireError(LumosErrors.errFragHeaderMalformed, "nothing to fragment")
        }
        guard blob.count <= LumosConstants.fragReassemblyMaxBytesDefault else {
            throw LumosCrypto.WireError(LumosErrors.errFragTotalTooLarge,
                                        "blob exceeds FRAG_REASSEMBLY_MAX_BYTES")
        }

        var content = Data(capacity: totalPrefixLen + blob.count)
        let total = UInt32(blob.count)
        content.append(UInt8((total >> 24) & 0xFF))
        content.append(UInt8((total >> 16) & 0xFF))
        content.append(UInt8((total >> 8) & 0xFF))
        content.append(UInt8(total & 0xFF))
        content.append(blob)

        let count = (content.count + chunkPayload - 1) / chunkPayload
        guard count <= LumosConstants.fragMaxFragmentsDefault else {
            throw LumosCrypto.WireError(LumosErrors.errFragTotalTooLarge,
                                        "the transfer does not fit this link's FRAG_MAX_FRAGMENTS")
        }

        var fragments: [Data] = []
        fragments.reserveCapacity(count)
        for index in 0..<count {
            var flags = 0
            if index == 0 { flags |= LumosConstants.fragFlagFirstDefault }
            if index == count - 1 { flags |= LumosConstants.fragFlagLastDefault }
            let at = content.startIndex + index * chunkPayload
            let end = Swift.min(at + chunkPayload, content.endIndex)
            var fragment = Data(capacity: headerLen + (end - at))
            fragment.append(UInt8(flags))
            fragment.append(assemblyID)
            fragment.append(UInt8((index >> 8) & 0xFF))
            fragment.append(UInt8(index & 0xFF))
            fragment.append(content[at..<end])
            fragments.append(fragment)
        }
        return fragments
    }
}

/// Rebuilds one link direction's blobs from fragments (TRS-PROTO-010).
public final class FragmentReassembler {

    /// What one offered fragment did to the assembly.
    public enum Outcome: Equatable {
        /// The fragment advanced an incomplete assembly.
        case accepted
        /// The fragment was already consumed and is suppressed.
        case duplicate
        /// The assembly finished; ``Offer/payload`` holds the blob.
        case complete
    }

    /// The report for one successful offer.
    public struct Offer: Equatable {
        /// What the fragment did.
        public let outcome: Outcome
        /// The reassembled blob when the outcome is complete.
        public let payload: Data?
        /// Whether a FIRST replaced a live, incomplete assembly.
        public let restarted: Bool
        /// Whether the open assembly aged out during this offer.
        public let expiredPrevious: Bool
    }

    private struct Open {
        var id: UInt8
        var next: Int
        var total: Int
        var buf: Data
        var lastAt: Int64
    }

    /// The parsed 4-byte fragment header.
    private struct Header {
        let flags: Int
        let id: UInt8
        let index: Int
    }

    private let timeoutMillis: Int64
    private var open: Open?
    private var completedID: UInt8?
    private var expiredID: UInt8?

    /// - Parameter timeoutMillis: idle time after which an assembly expires
    ///   (REASSEMBLY_TIMEOUT_SEC for the carrying link, in milliseconds)
    public init(timeoutMillis: Int64) {
        self.timeoutMillis = timeoutMillis
    }

    /// Feeds one received fragment at the caller's clock.
    /// - Parameters:
    ///   - fragment: the fragment bytes as received
    ///   - nowMillis: the caller's clock
    /// - Returns: what the fragment did
    /// - Throws: ``LumosCrypto/WireError`` with the exact stable code
    public func offer(_ fragment: Data, nowMillis: Int64) throws -> Offer {
        guard fragment.count >= Fragmentation.headerLen + 1 else {
            throw LumosCrypto.WireError(LumosErrors.errFragHeaderMalformed,
                                        "fragment shorter than header plus one content byte")
        }
        let bytes = [UInt8](fragment)
        let flags = Int(bytes[0])
        guard flags & LumosConstants.fragFlagsReservedMaskDefault == 0 else {
            throw LumosCrypto.WireError(LumosErrors.errFragHeaderMalformed, "reserved flag bit set")
        }
        let header = Header(flags: flags, id: bytes[1], index: Int(bytes[2]) << 8 | Int(bytes[3]))
        let content = Data(bytes[Fragmentation.headerLen...])

        let expiredNow = expireIfIdle(nowMillis: nowMillis)

        if flags & LumosConstants.fragFlagFirstDefault != 0 {
            return try offerFirst(header: header, content: content,
                                  nowMillis: nowMillis, expiredNow: expiredNow)
        }
        return try offerContinuation(header: header, content: content,
                                     nowMillis: nowMillis, expiredNow: expiredNow)
    }

    /// Lazily applies the reassembly timeout.
    private func expireIfIdle(nowMillis: Int64) -> Bool {
        guard let live = open, nowMillis - live.lastAt > timeoutMillis else { return false }
        expiredID = live.id
        open = nil
        return true
    }

    private func offerFirst(header: Header, content: Data,
                            nowMillis: Int64, expiredNow: Bool) throws -> Offer {
        guard header.index == 0 else {
            throw LumosCrypto.WireError(LumosErrors.errFragHeaderMalformed,
                                        "FIRST fragment with a non-zero index")
        }
        guard content.count >= Fragmentation.totalPrefixLen + 1 else {
            throw LumosCrypto.WireError(LumosErrors.errFragHeaderMalformed,
                                        "FIRST fragment without a total prefix and a content byte")
        }
        let prefix = [UInt8](content.prefix(Fragmentation.totalPrefixLen))
        let total = Int(prefix[0]) << 24 | Int(prefix[1]) << 16 | Int(prefix[2]) << 8 | Int(prefix[3])
        guard total != 0 else {
            throw LumosCrypto.WireError(LumosErrors.errFragHeaderMalformed, "zero total length")
        }
        guard total <= LumosConstants.fragReassemblyMaxBytesDefault else {
            // Checked before any reassembly allocation (TRS Sec. 9.4).
            throw LumosCrypto.WireError(LumosErrors.errFragTotalTooLarge,
                                        "declared total exceeds FRAG_REASSEMBLY_MAX_BYTES")
        }
        let data = content.dropFirst(Fragmentation.totalPrefixLen)
        guard data.count <= total else {
            throw LumosCrypto.WireError(LumosErrors.errFragLengthMismatch,
                                        "FIRST carries more bytes than the declared total")
        }

        let restarted = open != nil
        var buf = Data(capacity: total)
        buf.append(data)
        open = Open(id: header.id, next: 1, total: total, buf: buf, lastAt: nowMillis)

        if header.flags & LumosConstants.fragFlagLastDefault != 0 {
            return try complete(restarted: restarted, expiredNow: expiredNow)
        }
        return Offer(outcome: .accepted, payload: nil, restarted: restarted, expiredPrevious: expiredNow)
    }

    private func offerContinuation(header: Header, content: Data,
                                   nowMillis: Int64, expiredNow: Bool) throws -> Offer {
        guard var live = open, header.id == live.id else {
            if let done = completedID, header.id == done {
                // A straggler of the completed assembly: a reconnect epoch
                // re-sent it. Suppressed, never an error (TRS-PROTO-010).
                return Offer(outcome: .duplicate, payload: nil,
                             restarted: false, expiredPrevious: expiredNow)
            }
            if let aged = expiredID, header.id == aged {
                throw LumosCrypto.WireError(LumosErrors.errFragReassemblyTimeout,
                                            "continuation of an assembly that aged out")
            }
            throw LumosCrypto.WireError(LumosErrors.errFragSequenceGap,
                                        "continuation of an assembly this receiver does not have")
        }

        if header.index < live.next {
            // Already consumed: a link-level retransmit. Suppressed without
            // refreshing the idle clock - a duplicate is not progress.
            return Offer(outcome: .duplicate, payload: nil,
                         restarted: false, expiredPrevious: expiredNow)
        }
        if header.index > live.next {
            open = nil
            throw LumosCrypto.WireError(LumosErrors.errFragSequenceGap,
                                        "a fragment index was skipped")
        }
        guard live.buf.count + content.count <= live.total else {
            open = nil
            throw LumosCrypto.WireError(LumosErrors.errFragLengthMismatch,
                                        "content overruns the declared total")
        }
        live.buf.append(content)
        live.next = header.index + 1
        live.lastAt = nowMillis
        open = live

        if header.flags & LumosConstants.fragFlagLastDefault != 0 {
            return try complete(restarted: false, expiredNow: expiredNow)
        }
        return Offer(outcome: .accepted, payload: nil, restarted: false, expiredPrevious: expiredNow)
    }

    private func complete(restarted: Bool, expiredNow: Bool) throws -> Offer {
        guard let live = open else {
            throw LumosCrypto.WireError(LumosErrors.errFragSequenceGap, "no open assembly to complete")
        }
        guard live.buf.count == live.total else {
            open = nil
            throw LumosCrypto.WireError(LumosErrors.errFragLengthMismatch,
                                        "LAST completed short of the declared total")
        }
        completedID = live.id
        open = nil
        return Offer(outcome: .complete, payload: live.buf,
                     restarted: restarted, expiredPrevious: expiredNow)
    }
}
