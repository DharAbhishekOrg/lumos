/// Lumos - LumosProtocol / The remote-text wire and sessions: AMD-005 events, plaintext, envelope, X3DH + ratchet.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-RMT-001, SRS-RMT-004, SRS-RMT-009, SRS-RMT-011, TRS-PROTO-007, TRS-CRY-001, TRS-CRY-002
/// ADRs: ADR-001, ADR-008
/// Threading: serial-executor
/// Notes: protocol/chat/remote.go and session.go, proven against
/// test-vectors/chat/remote_text.json byte for byte.
///   text event = kind u8 | target[16] (edit, delete, reaction) | body_len u32 BE | body (text, edit, reaction)
///   plaintext  = message_id[16] | text event
///   envelope   = 0x01 PREKEY | identity_dh[32] | identity_dh_sig[64] | ephemeral[32] | spk_id u32 | opk_id u32
///                  | header[40] | ciphertext, or 0x02 NORMAL | header[40] | ciphertext
/// Every declared length is checked against what remains BEFORE anything is
/// copied (TRS-PROTO-007). Inputs are read as [UInt8] so a Data slice's
/// non-zero start index can never be mistaken for offset zero. open refuses an
/// envelope whose authenticated id differs from the id the relay delivered.

import Foundation
import LumosProtocolGenerated

/// Encoders and decoders for the remote-text wire.
public enum RemoteText {

    /// MESSAGE_ID_LEN_BYTES.
    public static let messageIDSize = LumosConstants.messageIDLenBytesDefault
    /// The PREKEY envelope format byte.
    public static let formatPrekey: UInt8 = 0x01
    /// The NORMAL envelope format byte.
    public static let formatNormal: UInt8 = 0x02

    private static let keySize = 32
    private static let sigSize = 64
    private static let u32Size = 4
    private static let prekeyFieldsSize = keySize + sigSize + keySize + u32Size + u32Size

    private static func malformed(_ detail: String) -> LumosCrypto.WireError {
        LumosCrypto.WireError(LumosErrors.errPayloadMalformed, detail)
    }

    private static func hasTarget(_ k: TextEventKind) -> Bool { k != .text }
    private static func hasBody(_ k: TextEventKind) -> Bool { k != .deleteForEveryone }

    private static func be32(_ b: [UInt8], _ at: Int) -> UInt32 {
        UInt32(b[at]) << 24 | UInt32(b[at + 1]) << 16 | UInt32(b[at + 2]) << 8 | UInt32(b[at + 3])
    }

    /// One AMD-005 inner event.
    public struct TextEvent: Equatable, Sendable {
        /// The event kind.
        public let kind: TextEventKind
        /// The target message id for edit, delete and reaction; nil for text.
        public let target: Data?
        /// The body for text, edit and reaction; nil for delete.
        public let body: Data?

        /// Builds an event whose shape fits its kind.
        /// - Throws: ERR_PAYLOAD_MALFORMED when target or body is wrong for the kind
        public init(kind: TextEventKind, target: Data?, body: Data?) throws {
            guard RemoteText.hasTarget(kind) == (target != nil), target.map({ $0.count == RemoteText.messageIDSize }) ?? true,
                  RemoteText.hasBody(kind) == (body != nil) else {
                throw RemoteText.malformed("target or body is wrong for the event kind")
            }
            self.kind = kind
            self.target = target
            self.body = body
        }

        /// The wire bytes.
        public var encoded: Data {
            var out = Data([kind.rawValue])
            if let target { out += target }
            if let body {
                out += LumosCrypto.be32(UInt32(body.count))
                out += body
            }
            return out
        }
    }

    /// Parses an event; trailing bytes are refused.
    /// - Throws: the stable code for what is wrong
    public static func decodeEvent(_ input: Data) throws -> TextEvent {
        let b = [UInt8](input)
        guard let first = b.first else { throw malformed("empty event") }
        guard let kind = TextEventKind(rawValue: first) else {
            throw LumosCrypto.WireError(LumosErrors.errTextUnknownEventKind, "unknown event kind")
        }
        var at = 1
        var target: Data?
        if hasTarget(kind) {
            guard b.count - at >= messageIDSize else {
                throw LumosCrypto.WireError(LumosErrors.errTextMissingTarget, "target_message_id missing")
            }
            target = Data(b[at..<at + messageIDSize])
            at += messageIDSize
        }
        var body: Data?
        if hasBody(kind) {
            guard b.count - at >= u32Size else { throw malformed("body length missing") }
            let n = be32(b, at)
            at += u32Size
            guard UInt64(n) == UInt64(b.count - at) else { throw malformed("body length disagrees with the event") }
            body = Data(b[at...])
            at = b.count
        }
        guard at == b.count else { throw malformed("trailing bytes after the event") }
        return try TextEvent(kind: kind, target: target, body: body)
    }

    /// What one ratchet message encrypts.
    public struct Plaintext: Equatable, Sendable {
        /// The authenticated message id.
        public let messageID: Data
        /// The event.
        public let event: TextEvent

        /// Pairs an id with its event.
        public init(messageID: Data, event: TextEvent) {
            self.messageID = messageID
            self.event = event
        }

        /// message_id | event.
        public var encoded: Data { messageID + event.encoded }
    }

    /// Parses message_id | event.
    /// - Throws: the stable code for what is wrong
    public static func decodePlaintext(_ input: Data) throws -> Plaintext {
        let b = [UInt8](input)
        guard b.count >= messageIDSize else { throw malformed("plaintext shorter than a message id") }
        return Plaintext(messageID: Data(b[0..<messageIDSize]), event: try decodeEvent(Data(b[messageIDSize...])))
    }

    /// The X3DH initial-message fields of a PREKEY envelope.
    public struct PrekeyFields: Equatable, Sendable {
        /// The initiator's identity-DH public key.
        public let initiatorIdentityDH: Data
        /// Its Ed25519 binding signature.
        public let initiatorIdentityDHSig: Data
        /// The initiator's ephemeral public key.
        public let ephemeral: Data
        /// The responder's signed-prekey id.
        public let spkID: UInt32
        /// The one-time prekey id, or X3DH_OPK_ID_NONE.
        public let opkID: UInt32
    }

    /// One TEXT_MESSAGE relay payload.
    public struct Envelope: Sendable {
        /// The PREKEY fields, or nil for NORMAL.
        public let prekey: PrekeyFields?
        /// The ratchet header.
        public let header: DoubleRatchet.Header
        /// The AEAD output.
        public let ciphertext: Data

        /// The wire bytes.
        public var encoded: Data {
            var out = Data()
            if let p = prekey {
                out.append(RemoteText.formatPrekey)
                out += p.initiatorIdentityDH + p.initiatorIdentityDHSig + p.ephemeral
                out += LumosCrypto.be32(p.spkID) + LumosCrypto.be32(p.opkID)
            } else {
                out.append(RemoteText.formatNormal)
            }
            return out + header.bytes + ciphertext
        }
    }

    /// Parses an envelope; the ciphertext must be non-empty.
    /// - Throws: ERR_PAYLOAD_MALFORMED when the bytes are not an envelope
    public static func decodeEnvelope(_ input: Data) throws -> Envelope {
        let b = [UInt8](input)
        guard let format = b.first else { throw malformed("empty envelope") }
        var at = 1
        var prekey: PrekeyFields?
        switch format {
        case formatPrekey:
            guard b.count - at >= prekeyFieldsSize else { throw malformed("prekey fields truncated") }
            let ids = at + 2 * keySize + sigSize
            prekey = PrekeyFields(initiatorIdentityDH: Data(b[at..<at + keySize]),
                                  initiatorIdentityDHSig: Data(b[at + keySize..<at + keySize + sigSize]),
                                  ephemeral: Data(b[at + keySize + sigSize..<ids]),
                                  spkID: be32(b, ids), opkID: be32(b, ids + u32Size))
            at += prekeyFieldsSize
        case formatNormal:
            break
        default:
            throw malformed("unknown envelope format")
        }
        guard b.count - at > DoubleRatchet.headerSize else { throw malformed("ratchet header or ciphertext missing") }
        let header: DoubleRatchet.Header
        do {
            header = try DoubleRatchet.Header.parse(Data(b[at..<at + DoubleRatchet.headerSize]))
        } catch {
            throw malformed("ratchet header invalid")
        }
        return Envelope(prekey: prekey, header: header, ciphertext: Data(b[(at + DoubleRatchet.headerSize)...]))
    }
}

/// Starts, accepts and uses remote-text sessions.
public enum RemoteSession {

    /// The device's own identity, as the protocol needs it.
    public struct Identity: Sendable {
        /// The Ed25519 public identity.
        public let identityEd: Data
        /// The identity-DH scalar.
        public let identityDHPriv: Data
        /// The published binding signature over the identity-DH key.
        public let identityDHSig: Data

        /// Assembles an identity.
        public init(identityEd: Data, identityDHPriv: Data, identityDHSig: Data) {
            self.identityEd = identityEd
            self.identityDHPriv = identityDHPriv
            self.identityDHSig = identityDHSig
        }
    }

    /// The responder's secret prekeys the PREKEY fields name.
    public struct ResponderKeys: Sendable {
        /// The signed-prekey scalar.
        public let spkPriv: Data
        /// Its id.
        public let spkID: UInt32
        /// The one-time prekey scalar, or nil when the envelope names none.
        public let opkPriv: Data?
        /// Its id.
        public let opkID: UInt32

        /// Assembles the keys.
        public init(spkPriv: Data, spkID: UInt32, opkPriv: Data?, opkID: UInt32) {
            self.spkPriv = spkPriv
            self.spkID = spkID
            self.opkPriv = opkPriv
            self.opkID = opkID
        }
    }

    private static func rejected(_ detail: String) -> LumosCrypto.WireError {
        LumosCrypto.WireError(LumosErrors.errCryptoPrekeySignatureInvalid, detail)
    }

    /// Starts a session with a matched peer from its fetched bundle.
    /// - Throws: a bundle or agreement failure
    public static func initiate(own: Identity, bundle: X3DH.Bundle, matchID: Data, ephemeralPriv: Data,
                                ratchetPriv: Data) throws -> (DoubleRatchet, RemoteText.PrekeyFields) {
        let res = try X3DH.initiate(identityEd: own.identityEd, identityDHPriv: own.identityDHPriv,
                                    ephemeralPriv: ephemeralPriv, bundle: bundle, matchID: matchID)
        let ratchet = try DoubleRatchet.initiator(sk: res.sk, ad: res.ad, remoteRatchetPub: bundle.spk,
                                                  ownRatchetPriv: ratchetPriv)
        let opkID = bundle.opk == nil ? UInt32(LumosConstants.x3dhOpkIDNoneDefault) : bundle.opkID
        let fields = RemoteText.PrekeyFields(initiatorIdentityDH: try X3DH.publicKey(of: own.identityDHPriv),
                                             initiatorIdentityDHSig: own.identityDHSig,
                                             ephemeral: try X3DH.publicKey(of: ephemeralPriv),
                                             spkID: bundle.spkID, opkID: opkID)
        return (ratchet, fields)
    }

    /// Accepts a PREKEY envelope from the identity the match pinned. The caller
    /// deletes the consumed one-time prekey once the first message opens.
    /// - Throws: ERR_CRYPTO_PREKEY_SIGNATURE_INVALID when anything does not check out
    public static func respond(own: Identity, keys: ResponderKeys, initiatorEd: Data,
                               prekey p: RemoteText.PrekeyFields, matchID: Data) throws -> DoubleRatchet {
        guard X3DH.verifyIdentityBinding(ed: initiatorEd, dh: p.initiatorIdentityDH, sig: p.initiatorIdentityDHSig) else {
            throw rejected("initiator identity binding rejected")
        }
        guard p.spkID == keys.spkID else { throw rejected("signed prekey id unknown") }
        let namesNone = p.opkID == UInt32(LumosConstants.x3dhOpkIDNoneDefault)
        guard namesNone == (keys.opkPriv == nil), keys.opkPriv == nil || p.opkID == keys.opkID else {
            throw rejected("one-time prekey id mismatch")
        }
        let secrets = X3DH.ResponderSecrets(identityEd: own.identityEd, identityDHPriv: own.identityDHPriv,
                                            spkPriv: keys.spkPriv, spkID: keys.spkID,
                                            opkPriv: keys.opkPriv, opkID: keys.opkID)
        let initiator = X3DH.InitiatorKeys(identityEd: initiatorEd, identityDH: p.initiatorIdentityDH, ephemeral: p.ephemeral)
        let res = try X3DH.respond(secrets, initiator: initiator, matchID: matchID)
        return try DoubleRatchet.responder(sk: res.sk, ad: res.ad, ownRatchetPriv: keys.spkPriv)
    }

    /// Encrypts one plaintext; prekey is non-nil while the peer has not replied.
    /// - Throws: on an encryption failure
    public static func seal(_ ratchet: inout DoubleRatchet, _ plaintext: RemoteText.Plaintext,
                            prekey: RemoteText.PrekeyFields?, now: Int64) throws -> Data {
        let (header, ciphertext) = try ratchet.encrypt(plaintext.encoded, now: now)
        return RemoteText.Envelope(prekey: prekey, header: header, ciphertext: ciphertext).encoded
    }

    /// Decrypts one envelope delivered under outerID.
    /// - Throws: an authentication, replay or format failure
    public static func open(_ ratchet: inout DoubleRatchet, _ envelope: RemoteText.Envelope, outerID: Data,
                            now: Int64, nextKey: () throws -> Data) throws -> RemoteText.Plaintext {
        let pt = try ratchet.decrypt(envelope.header, envelope.ciphertext, now: now, nextKey: nextKey)
        let p = try RemoteText.decodePlaintext(pt)
        guard p.messageID == outerID else {
            throw LumosCrypto.WireError(LumosErrors.errPayloadMalformed, "authenticated id differs from delivered id")
        }
        return p
    }
}
