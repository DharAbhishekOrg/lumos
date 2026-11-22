/// Lumos - LumosProtocol / RELAY_ROUTE and delivery/read receipts (CR-005).
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-RMT-005, SRS-RMT-014, SRS-RMT-009, SRS-RMT-001, SRS-RMT-016, TRS-PROTO-007
/// ADRs: ADR-001, ADR-002
/// Threading: serial-executor
/// Notes: protocol/chat/route.go and receipt.go, proven against
/// test-vectors/chat/receipts.json byte for byte.
///   relay route = version u8 = 1 | routing_id[16] | capability[32]   (49 bytes, no all-zero field)
///   receipt     = receipt_id[16] | kind u8 (0x11, 0x12) | count u8 | message_id[16] x count
/// A receipt is sealed in the chat's ratchet in the remote-text envelope; its
/// kind must equal the class the relay delivered it under and its id the
/// relay's id. Encoders send at most RECEIPT_IDS_PER_ENVELOPE.default ids,
/// decoders accept up to .max. Inputs are read as [UInt8].

import Foundation
import LumosProtocolGenerated

/// One RELAY_ROUTE payload.
public struct RelayRoute: Equatable, Sendable {
    /// A routing id's width.
    public static let routingIDSize = 16
    /// A capability's width.
    public static let capabilitySize = 32
    /// The encoded size.
    public static let size = 1 + routingIDSize + capabilitySize
    private static let version: UInt8 = 1

    /// The sender's routing id.
    public let routingID: Data
    /// The capability the sender minted for this peer.
    public let capability: Data

    /// - Throws: ERR_PAYLOAD_MALFORMED on a wrong width
    public init(routingID: Data, capability: Data) throws {
        guard routingID.count == Self.routingIDSize, capability.count == Self.capabilitySize else {
            throw LumosCrypto.WireError(LumosErrors.errPayloadMalformed, "relay route field width")
        }
        self.routingID = Data(routingID)
        self.capability = Data(capability)
    }

    /// The wire bytes.
    public var encoded: Data {
        var out = Data([Self.version])
        out += routingID
        out += capability
        return out
    }

    /// Parses a RELAY_ROUTE payload.
    /// - Throws: ERR_PAYLOAD_MALFORMED on any other length or version, or an all-zero field
    public static func decode(_ input: Data) throws -> RelayRoute {
        let b = [UInt8](input)
        guard b.count == size else { throw LumosCrypto.WireError(LumosErrors.errPayloadMalformed, "relay route is not 49 bytes") }
        guard b[0] == version else { throw LumosCrypto.WireError(LumosErrors.errPayloadMalformed, "unknown relay route version") }
        let rid = Data(b[1..<(1 + routingIDSize)])
        let cap = Data(b[(1 + routingIDSize)...])
        guard rid.contains(where: { $0 != 0 }), cap.contains(where: { $0 != 0 }) else {
            throw LumosCrypto.WireError(LumosErrors.errPayloadMalformed, "relay route field is all zero")
        }
        return try RelayRoute(routingID: rid, capability: cap)
    }
}

/// One delivery or read receipt.
public struct Receipt: Equatable, Sendable {
    /// The receipt's own id: the relay id it travels under.
    public let id: Data
    /// .deliveryReceipt or .readReceipt.
    public let kind: MessageClass
    /// The acknowledged message ids.
    public let acked: [Data]

    private static let idSize = RemoteText.messageIDSize
    private static let headerSize = idSize + 2

    private static func malformed(_ detail: String) -> LumosCrypto.WireError {
        LumosCrypto.WireError(LumosErrors.errPayloadMalformed, detail)
    }

    private static func isReceipt(_ k: MessageClass) -> Bool { k == .deliveryReceipt || k == .readReceipt }

    /// - Throws: ERR_PAYLOAD_MALFORMED on a wrong id width
    public init(id: Data, kind: MessageClass, acked: [Data]) throws {
        guard id.count == Self.idSize, acked.allSatisfy({ $0.count == Self.idSize }) else {
            throw Self.malformed("receipt id width")
        }
        self.id = Data(id)
        self.kind = kind
        self.acked = acked.map { Data($0) }
    }

    /// The plaintext.
    /// - Throws: ERR_PAYLOAD_MALFORMED when the kind is not a receipt or the count is out of range
    public func encode() throws -> Data {
        guard Self.isReceipt(kind) else { throw Self.malformed("not a receipt class") }
        guard !acked.isEmpty, acked.count <= LumosConstants.receiptIDSPerEnvelopeDefault else {
            throw Self.malformed("receipt id count out of range")
        }
        var out = Data(id)
        out.append(kind.rawValue)
        out.append(UInt8(acked.count))
        for a in acked { out += a }
        return out
    }

    /// Parses a receipt plaintext, refusing trailing bytes.
    /// - Throws: ERR_PAYLOAD_MALFORMED on anything outside the layout
    public static func decode(_ input: Data) throws -> Receipt {
        let b = [UInt8](input)
        guard b.count >= headerSize else { throw malformed("receipt shorter than its header") }
        guard let kind = MessageClass(rawValue: b[idSize]), isReceipt(kind) else { throw malformed("not a receipt class") }
        let n = Int(b[idSize + 1])
        guard n > 0, n <= LumosConstants.receiptIDSPerEnvelopeMax else { throw malformed("receipt id count out of range") }
        guard b.count - headerSize == n * idSize else { throw malformed("receipt length does not match its count") }
        let ids = (0..<n).map { i in Data(b[(headerSize + i * idSize)..<(headerSize + (i + 1) * idSize)]) }
        return try Receipt(id: Data(b[0..<idSize]), kind: kind, acked: ids)
    }

    /// Seals this receipt into an envelope, exactly as RemoteSession.seal does text.
    /// - Throws: on an encoding or encryption failure
    public func seal(_ ratchet: inout DoubleRatchet, prekey: RemoteText.PrekeyFields?, now: Int64) throws -> Data {
        let (header, ciphertext) = try ratchet.encrypt(try encode(), now: now)
        return RemoteText.Envelope(prekey: prekey, header: header, ciphertext: ciphertext).encoded
    }

    /// What the relay says it delivered: the id and class it handed over.
    public struct Delivered: Sendable {
        /// The relay message id.
        public let id: Data
        /// The class the relay delivered it under.
        public let messageClass: MessageClass

        /// Wraps the relay's labels.
        public init(id: Data, messageClass: MessageClass) {
            self.id = Data(id)
            self.messageClass = messageClass
        }
    }

    /// Opens one envelope; its authenticated id and kind must match what the relay delivered.
    /// - Throws: an authentication, replay, format or relabelling failure
    public static func open(_ ratchet: inout DoubleRatchet, _ envelope: RemoteText.Envelope, delivered: Delivered,
                            now: Int64, nextKey: () throws -> Data) throws -> Receipt {
        let pt = try ratchet.decrypt(envelope.header, envelope.ciphertext, now: now, nextKey: nextKey)
        let r = try decode(pt)
        guard r.id == delivered.id else { throw malformed("authenticated receipt id differs from the delivered id") }
        guard r.kind == delivered.messageClass else {
            throw malformed("authenticated receipt kind differs from the delivered class")
        }
        return r
    }
}
