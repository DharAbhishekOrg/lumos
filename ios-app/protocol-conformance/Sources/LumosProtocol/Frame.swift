/// Lumos - protocol-conformance / Swift frame codec per TRS Sec. 9.1.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-PROTO-001, TRS-PROTO-002, TRS-PROTO-004, TRS-PROTO-006, TRS-PROTO-007, TRS-PROTO-013
/// ADRs: ADR-004, ADR-007
/// Threading: any
/// Notes: A third INDEPENDENT reimplementation of the canonical frame, written
/// against TRS Sec. 9.1 and the generated constants. Go, Java and Swift agreeing
/// on the committed bytes is what makes the wire format specified rather than
/// merely implemented. The decode validation order matches the wire contract
/// because the negative vectors assert exact codes, and bounds are checked
/// before any payload allocation (TRS-PROTO-007).

import Foundation
import LumosProtocolGenerated

/// Decoded frame header fields.
public struct FrameHeader: Equatable, Sendable {
    /// Protocol version byte: high nibble major, low nibble minor.
    public var version: UInt8
    /// Canonical message class byte.
    public var type: UInt8
    /// Frame flags.
    public var flags: UInt16
    /// 16-byte session identifier.
    public var sessionID: Data
    /// 16-byte message identifier.
    public var messageID: Data
    /// Fragment/message sequence.
    public var sequence: UInt32
    /// Selective acknowledgement base.
    public var ackBase: UInt32
    /// Relative or canonical expiry policy.
    public var expirySeconds: UInt32
    /// Mesh forwarding ceiling.
    public var hopLimit: UInt8

    /// Creates a header.
    public init(
        version: UInt8 = UInt8(LumosConstants.protocolVersionEncoded),
        type: UInt8,
        flags: UInt16 = 0,
        sessionID: Data,
        messageID: Data,
        sequence: UInt32 = 0,
        ackBase: UInt32 = 0,
        expirySeconds: UInt32 = 0,
        hopLimit: UInt8 = 0
    ) {
        self.version = version
        self.type = type
        self.flags = flags
        self.sessionID = sessionID
        self.messageID = messageID
        self.sequence = sequence
        self.ackBase = ackBase
        self.expirySeconds = expirySeconds
        self.hopLimit = hopLimit
    }

    /// Whether the critical flag demands fail-closed handling.
    public var critical: Bool {
        flags & UInt16(LumosConstants.frameFlagCriticalDefault) != 0
    }

    /// The resolved message class, or nil when this version does not define it.
    public var messageClass: MessageClass? {
        MessageClass(rawValue: type)
    }
}

/// A decoded frame.
public struct Frame: Sendable {
    /// The header.
    public let header: FrameHeader
    /// The payload.
    public let payload: Data

    /// Creates a frame.
    public init(header: FrameHeader, payload: Data) {
        self.header = header
        self.payload = payload
    }
}

/// Canonical frame encoding and decoding.
public enum FrameCodec {

    /// Fixed authenticated header length for protocol v1 frames.
    public static let headerLen = LumosConstants.frameHeaderLenBytesDefault

    private static func putU16(_ out: inout Data, _ at: Int, _ value: UInt16) {
        out[at] = UInt8((value >> 8) & 0xFF)
        out[at + 1] = UInt8(value & 0xFF)
    }

    private static func putU32(_ out: inout Data, _ at: Int, _ value: UInt32) {
        out[at] = UInt8((value >> 24) & 0xFF)
        out[at + 1] = UInt8((value >> 16) & 0xFF)
        out[at + 2] = UInt8((value >> 8) & 0xFF)
        out[at + 3] = UInt8(value & 0xFF)
    }

    private static func u16(_ b: Data, _ at: Int) -> UInt16 {
        (UInt16(b[b.startIndex + at]) << 8) | UInt16(b[b.startIndex + at + 1])
    }

    private static func u32(_ b: Data, _ at: Int) -> UInt32 {
        (UInt32(b[b.startIndex + at]) << 24)
            | (UInt32(b[b.startIndex + at + 1]) << 16)
            | (UInt32(b[b.startIndex + at + 2]) << 8)
            | UInt32(b[b.startIndex + at + 3])
    }

    private static func slice(_ b: Data, _ from: Int, _ to: Int) -> Data {
        Data(b[(b.startIndex + from)..<(b.startIndex + to)])
    }

    /// Serialises a frame in network byte order.
    /// - Parameters:
    ///   - header: the header
    ///   - payload: the payload bytes
    /// - Returns: the encoded frame
    /// - Throws: ``LumosCrypto/WireError`` for an unknown class or oversized payload
    public static func encode(header: FrameHeader, payload: Data) throws -> Data {
        guard let mc = MessageClass(rawValue: header.type) else {
            throw LumosCrypto.WireError(LumosErrors.errFrameUnknownType, "unknown message class")
        }
        guard let maxPayload = MessageClassLimits.maxPayloadBytes[mc] else {
            throw LumosCrypto.WireError(LumosErrors.errFrameUnknownType, "no payload bound")
        }
        guard payload.count <= maxPayload else {
            throw LumosCrypto.WireError(LumosErrors.errFramePayloadTooLarge, "payload exceeds class bound")
        }

        var out = Data(repeating: 0, count: headerLen + payload.count)
        putU16(&out, 0, UInt16(LumosConstants.frameMagicDefault))
        out[2] = header.version
        out[3] = header.type
        putU16(&out, 4, header.flags)
        putU16(&out, 6, UInt16(headerLen))
        out.replaceSubrange(8..<24, with: header.sessionID)
        out.replaceSubrange(24..<40, with: header.messageID)
        putU32(&out, 40, header.sequence)
        putU32(&out, 44, header.ackBase)
        putU32(&out, 48, UInt32(payload.count))
        putU32(&out, 52, header.expirySeconds)
        out[56] = header.hopLimit
        out[57] = 0
        if !payload.isEmpty {
            out.replaceSubrange(headerLen..<(headerLen + payload.count), with: payload)
        }
        return out
    }

    /// Returns the complete authenticated header - exactly the TRS-PROTO-003 AAD.
    /// - Parameter encoded: a full encoded frame
    /// - Returns: the header bytes
    /// - Throws: when the input is shorter than a header
    public static func headerBytes(_ encoded: Data) throws -> Data {
        guard encoded.count >= headerLen else {
            throw LumosCrypto.WireError(LumosErrors.errFrameTruncated, "shorter than a header")
        }
        return slice(encoded, 0, headerLen)
    }

    /// Parses one complete frame.
    /// - Parameter data: the encoded frame
    /// - Returns: the decoded frame
    /// - Throws: ``LumosCrypto/WireError`` with the exact stable code
    public static func decode(_ data: Data) throws -> Frame {
        guard data.count >= 2 else {
            throw LumosCrypto.WireError(LumosErrors.errFrameTruncated, "no magic")
        }
        guard u16(data, 0) == UInt16(LumosConstants.frameMagicDefault) else {
            throw LumosCrypto.WireError(LumosErrors.errFrameBadMagic, "magic")
        }
        guard data.count >= headerLen else {
            throw LumosCrypto.WireError(LumosErrors.errFrameTruncated, "header")
        }

        let version = data[data.startIndex + 2]
        let type = data[data.startIndex + 3]
        let flags = u16(data, 4)
        let declaredHeaderLen = u16(data, 6)
        let payloadLen = u32(data, 48)
        let reserved = data[data.startIndex + 57]

        let header = FrameHeader(
            version: version,
            type: type,
            flags: flags,
            sessionID: slice(data, 8, 24),
            messageID: slice(data, 24, 40),
            sequence: u32(data, 40),
            ackBase: u32(data, 44),
            expirySeconds: u32(data, 52),
            hopLimit: data[data.startIndex + 56]
        )

        guard Int(version >> 4) == LumosConstants.protocolVersionMajor else {
            throw LumosCrypto.WireError(LumosErrors.errFrameUnsupportedVersion, "major version")
        }
        guard Int(declaredHeaderLen) == headerLen else {
            throw LumosCrypto.WireError(LumosErrors.errFrameBadHeaderLength, "header length")
        }
        let critical = header.critical
        let mc = header.messageClass
        if mc == nil && critical {
            throw LumosCrypto.WireError(LumosErrors.errFrameUnknownType, "unknown critical class")
        }
        if flags & UInt16(LumosConstants.frameFlagsReservedMaskDefault) != 0 && critical {
            throw LumosCrypto.WireError(LumosErrors.errFrameReservedFlag, "reserved flag")
        }
        if reserved != 0 && critical {
            throw LumosCrypto.WireError(LumosErrors.errFrameReservedField, "reserved byte")
        }
        if let mc, let maxPayload = MessageClassLimits.maxPayloadBytes[mc] {
            // Checked before allocation (TRS-PROTO-007).
            guard Int(payloadLen) <= maxPayload else {
                throw LumosCrypto.WireError(LumosErrors.errFramePayloadTooLarge, "declared payload")
            }
        }
        let available = data.count - headerLen
        guard available >= Int(payloadLen) else {
            throw LumosCrypto.WireError(LumosErrors.errFrameTruncated, "payload")
        }
        guard available <= Int(payloadLen) else {
            throw LumosCrypto.WireError(LumosErrors.errPayloadMalformed, "trailing bytes")
        }

        return Frame(header: header, payload: slice(data, headerLen, headerLen + Int(payloadLen)))
    }
}
