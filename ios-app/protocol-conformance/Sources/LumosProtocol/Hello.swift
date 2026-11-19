/// Lumos - protocol-conformance / The canonical trust point: HELLO and HELLO_ACK.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-PROTO-017, TRS-PROTO-018, TRS-ENV-002, SRS-IDN-002
/// ADRs: ADR-002, ADR-008
/// Threading: serial-per-link
/// Notes: The Swift port of protocol/hello, held to
/// test-vectors/session/hello_exchange.json beside the Go reference and the
/// Java port. Everything the advertisement hinted is re-established HERE,
/// authenticated, before any presence card exists (TRS-PROTO-017); a hintless
/// peer - which is every iOS peer - walks the same path with a zero EID
/// (TRS-PROTO-018). Verification is CryptoKit's; SIGNING goes through a seam
/// closure because CryptoKit randomises Ed25519 signatures: an ACK built here
/// verifies everywhere but cannot byte-match the committed one, the
/// identity_binding precedent. Presence is granted only to a peer whose ACK
/// answers one's OWN outstanding HELLO; the bare replay fails with the nonce
/// code before any cryptography runs (TRS-ENV-002).

import CryptoKit
import Foundation
import LumosProtocolGenerated

/// The hello_v1 codec: build, parse and authenticate (TRS-PROTO-017).
public enum Hello {

    /// The hello_v1 payload length.
    public static let helloLen = LumosConstants.helloV1LayoutTotalLen

    /// The hello_ack_v1 payload length.
    public static let ackLen = LumosConstants.helloAckV1LayoutTotalLen

    /// The rotating EID width inside the payload.
    public static let eidLen = LumosConstants.helloV1LayoutEIDLen

    /// The freshness nonce width.
    public static let nonceLen = LumosConstants.helloV1LayoutNonceLen

    /// The derived peer identifier width.
    public static let peerIdLen = LumosConstants.peerIDLenDefault

    static let helloV1 = 1

    /// The hello_v1 content HELLO and HELLO_ACK share.
    public struct Fields: Equatable, Sendable {
        /// The encoded protocol version byte.
        public let protocolVersion: Int
        /// The capability bits.
        public let capabilityBits: Int
        /// The venue hint.
        public let venueHint: Int64
        /// The current rotating EID; zeros when hintless.
        public let eid: Data
        /// The raw Ed25519 identity public key.
        public let ed25519Public: Data
        /// The raw X25519 identity-DH public key.
        public let x25519Public: Data
        /// The ADR-008 binding signature.
        public let bindingSignature: Data
        /// The freshness nonce.
        public let nonce: Data

        /// Creates the field set.
        /// - Parameters:
        ///   - protocolVersion: the encoded protocol version byte
        ///   - capabilityBits: the capability bits
        ///   - venueHint: the venue hint
        ///   - eid: the current rotating EID; zeros when hintless
        ///   - ed25519Public: the raw Ed25519 identity public key
        ///   - x25519Public: the raw X25519 identity-DH public key
        ///   - bindingSignature: the ADR-008 binding signature
        ///   - nonce: the freshness nonce
        public init(protocolVersion: Int, capabilityBits: Int, venueHint: Int64,
                    eid: Data, ed25519Public: Data, x25519Public: Data,
                    bindingSignature: Data, nonce: Data) {
            self.protocolVersion = protocolVersion
            self.capabilityBits = capabilityBits
            self.venueHint = venueHint
            self.eid = eid
            self.ed25519Public = ed25519Public
            self.x25519Public = x25519Public
            self.bindingSignature = bindingSignature
            self.nonce = nonce
        }

        /// This content with a different nonce.
        /// - Parameter freshNonce: the fresh nonce
        /// - Returns: a copy carrying freshNonce
        public func withNonce(_ freshNonce: Data) -> Fields {
            Fields(protocolVersion: protocolVersion, capabilityBits: capabilityBits,
                   venueHint: venueHint, eid: eid, ed25519Public: ed25519Public,
                   x25519Public: x25519Public, bindingSignature: bindingSignature,
                   nonce: freshNonce)
        }

        /// Serialises the fields in register layout order.
        /// - Returns: the hello_v1 bytes
        public func encode() -> Data {
            var out = Data(capacity: Hello.helloLen)
            out.append(UInt8(protocolVersion & 0xFF))
            out.append(UInt8(Hello.helloV1))
            out.append(UInt8((capabilityBits >> 8) & 0xFF))
            out.append(UInt8(capabilityBits & 0xFF))
            out.append(UInt8((venueHint >> 24) & 0xFF))
            out.append(UInt8((venueHint >> 16) & 0xFF))
            out.append(UInt8((venueHint >> 8) & 0xFF))
            out.append(UInt8(venueHint & 0xFF))
            out.append(eid)
            out.append(ed25519Public)
            out.append(x25519Public)
            out.append(bindingSignature)
            out.append(nonce)
            return out
        }
    }

    /// Derives the public peer identifier from identity material alone
    /// (SRS-IDN-002): peerIdLen bytes of SHA-256(PEER_ID_KDF_INFO | edPublic).
    /// - Parameter ed25519Public: the raw Ed25519 identity public key
    /// - Returns: the identifier, stable across EID rotations (SRS-NBY-006)
    public static func peerId(_ ed25519Public: Data) -> Data {
        LumosCrypto.sha256(Data(LumosConstants.peerIDKdfInfoValue.utf8) + ed25519Public)
            .prefix(peerIdLen)
    }

    /// Decodes and authenticates a received HELLO: structure, version and the
    /// ADR-008 binding. A valid HELLO proves an identity EXISTS, never that it
    /// is live on this link.
    /// - Parameter data: the payload bytes
    /// - Returns: the decoded fields
    /// - Throws: ``LumosCrypto/WireError`` with the exact stable code
    public static func parseHello(_ data: Data) throws -> Fields {
        guard data.count == helloLen else {
            throw LumosCrypto.WireError(LumosErrors.errHelloMalformed, "hello length")
        }
        let f = try parseFields(data)
        try verifyBinding(f)
        return f
    }

    /// The exact byte string the ACK signature covers.
    /// - Parameters:
    ///   - helloBytes: the initiator's exact HELLO payload bytes
    ///   - ackFields: the ACK's field bytes, before the signature
    /// - Returns: the message to sign or verify
    public static func ackAuthMessage(helloBytes: Data, ackFields: Data) -> Data {
        Data(LumosConstants.helloAuthInfoValue.utf8)
            + LumosCrypto.sha256(helloBytes) + ackFields
    }

    /// Answers a received HELLO with this side's fields, signed by its identity.
    /// - Parameters:
    ///   - responder: this side's fields
    ///   - helloBytes: the initiator's exact HELLO payload bytes
    ///   - signer: the Ed25519 identity signer seam
    /// - Returns: the hello_ack_v1 bytes
    /// - Throws: whatever the signing seam throws
    public static func buildAck(responder: Fields, helloBytes: Data,
                                signer: (Data) throws -> Data) rethrows -> Data {
        let fields = responder.encode()
        return fields + (try signer(ackAuthMessage(helloBytes: helloBytes, ackFields: fields)))
    }

    /// Authenticates a received HELLO_ACK against one's OWN sent HELLO bytes.
    /// On success the returned fields are an authenticated, LIVE peer - the
    /// only thing presence may be built from (TRS-ENV-002).
    /// - Parameters:
    ///   - ownHelloBytes: the HELLO this side actually sent
    ///   - ackBytes: the received ACK
    /// - Returns: the authenticated fields
    /// - Throws: ``LumosCrypto/WireError`` with the exact stable code
    public static func verifyAck(ownHelloBytes: Data, ackBytes: Data) throws -> Fields {
        guard ackBytes.count == ackLen else {
            throw LumosCrypto.WireError(LumosErrors.errHelloMalformed, "ack length")
        }
        let bytes = [UInt8](ackBytes)
        let fieldBytes = Data(bytes[0..<helloLen])
        let f = try parseFields(fieldBytes)
        try verifyBinding(f)
        guard LumosCrypto.ed25519Verify(
            publicKeyRaw: f.ed25519Public,
            message: ackAuthMessage(helloBytes: ownHelloBytes, ackFields: fieldBytes),
            signature: Data(bytes[helloLen..<ackLen])) else {
            throw LumosCrypto.WireError(LumosErrors.errHelloSignatureInvalid, "ack auth signature")
        }
        return f
    }

    private static func parseFields(_ data: Data) throws -> Fields {
        let bytes = [UInt8](data)
        guard Int(bytes[1]) == helloV1 else {
            throw LumosCrypto.WireError(LumosErrors.errHelloMalformed, "unsupported hello version")
        }
        let protocolVersion = Int(bytes[0])
        guard protocolVersion >> 4 == LumosConstants.protocolVersionMajor else {
            // The TRS-PROTO-017 in-band re-confirmation, same rule as the
            // frame header (ADR-007): a differing MAJOR nibble fails closed.
            throw LumosCrypto.WireError(LumosErrors.errHelloVersionUnsupported, "major version")
        }
        let venue = Int64(bytes[4]) << 24 | Int64(bytes[5]) << 16
            | Int64(bytes[6]) << 8 | Int64(bytes[7])
        return Fields(protocolVersion: protocolVersion,
                      capabilityBits: Int(bytes[2]) << 8 | Int(bytes[3]),
                      venueHint: venue,
                      eid: Data(bytes[8..<20]),
                      ed25519Public: Data(bytes[20..<52]),
                      x25519Public: Data(bytes[52..<84]),
                      bindingSignature: Data(bytes[84..<148]),
                      nonce: Data(bytes[148..<helloLen]))
    }

    private static func verifyBinding(_ f: Fields) throws {
        let message = Data(LumosConstants.x3dhIdentityBindingInfoValue.utf8) + f.x25519Public
        guard LumosCrypto.ed25519Verify(publicKeyRaw: f.ed25519Public, message: message,
                                        signature: f.bindingSignature) else {
            throw LumosCrypto.WireError(LumosErrors.errHelloBindingInvalid, "identity binding")
        }
    }
}

/// Runs one link direction's HELLO/HELLO_ACK (TRS-PROTO-017).
public final class HelloExchange {

    /// An authenticated, live peer: the only product of a completed exchange.
    public struct Peer: Sendable {
        /// The authenticated hello fields.
        public let fields: Hello.Fields
        /// The SRS-IDN-002 identifier, stable across EID rotations.
        public let id: Data
    }

    private let signer: (Data) throws -> Data
    private let own: Hello.Fields
    private var outstanding: Data?

    /// Creates an exchange for one link direction.
    /// - Parameters:
    ///   - signer: this device's Ed25519 identity signer seam
    ///   - own: this device's hello fields template
    public init(signer: @escaping (Data) throws -> Data, own: Hello.Fields) {
        self.signer = signer
        self.own = own
    }

    /// Emits this side's HELLO with a fresh nonce and remembers it.
    /// - Parameter nonce: the fresh nonce; the source is the caller's
    /// - Returns: the HELLO payload bytes
    public func start(nonce: Data) -> Data {
        let hello = own.withNonce(nonce).encode()
        outstanding = hello
        return hello
    }

    /// Answers the peer's HELLO as the responder; the ACK grants nothing here.
    /// - Parameter helloBytes: the received HELLO payload
    /// - Returns: the ACK payload bytes
    /// - Throws: ``LumosCrypto/WireError`` or whatever the signer throws
    public func onHello(_ helloBytes: Data) throws -> Data {
        _ = try Hello.parseHello(helloBytes)
        return try Hello.buildAck(responder: own, helloBytes: helloBytes, signer: signer)
    }

    /// Completes this side's exchange: the ACK must answer the HELLO this
    /// side actually has outstanding (TRS-ENV-002).
    /// - Parameter ackBytes: the received ACK payload
    /// - Returns: the authenticated, live peer
    /// - Throws: ``LumosCrypto/WireError`` with the exact stable code
    public func onAck(_ ackBytes: Data) throws -> Peer {
        guard let own = outstanding else {
            throw LumosCrypto.WireError(LumosErrors.errHelloNonceMismatch, "no outstanding hello")
        }
        let f = try Hello.verifyAck(ownHelloBytes: own, ackBytes: ackBytes)
        guard f.ed25519Public != self.own.ed25519Public else {
            // A mirror of our own identity is not a peer.
            throw LumosCrypto.WireError(LumosErrors.errHelloSignatureInvalid, "own identity echoed")
        }
        outstanding = nil
        return Peer(fields: f, id: Hello.peerId(f.ed25519Public))
    }
}
