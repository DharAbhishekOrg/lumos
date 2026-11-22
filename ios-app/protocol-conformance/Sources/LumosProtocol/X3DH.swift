/// Lumos - LumosProtocol / X3DH session agreement, for production use.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-CRY-001, TRS-CRY-005, TRS-CRY-014, TRS-CRY-015, SRS-RMT-007
/// ADRs: ADR-001, ADR-008
/// Threading: any
/// Notes: protocol/crypto/x3dh/x3dh.go decision for decision, proven by the
/// remote-text session vectors. Bundle signatures are verified BEFORE any DH and
/// fail closed with one stable code (ADR-008 condition 2). DH1..DH4 and the HKDF
/// over F | DH1 | DH2 | DH3 [| DH4] follow the X3DH specification; the transcript
/// becomes the session's associated data (TRS-CRY-014). Private keys arrive as
/// raw scalars: where they live is the app's business, not the protocol's.

import CryptoKit
import Foundation
import LumosProtocolGenerated

/// X3DH key agreement for remote-text sessions.
public enum X3DH {

    /// The derived shared-secret length.
    public static let skSize = 32

    /// A responder's public prekey bundle.
    public struct Bundle: Sendable {
        /// Ed25519 identity public key.
        public let identityEd: Data
        /// X25519 identity-DH public key (ADR-008).
        public let identityDH: Data
        /// Ed25519 signature binding identityDH.
        public let identityDHSig: Data
        /// Signed-prekey id.
        public let spkID: UInt32
        /// Signed-prekey public key.
        public let spk: Data
        /// Signature over the signed prekey.
        public let spkSig: Data
        /// One-time prekey id; ignored when opk is nil.
        public let opkID: UInt32
        /// One-time prekey public key, or nil when none was issued.
        public let opk: Data?

        /// Builds a bundle from its public parts.
        public init(identityEd: Data, identityDH: Data, identityDHSig: Data, spkID: UInt32,
                    spk: Data, spkSig: Data, opkID: UInt32, opk: Data?) {
            self.identityEd = identityEd
            self.identityDH = identityDH
            self.identityDHSig = identityDHSig
            self.spkID = spkID
            self.spk = spk
            self.spkSig = spkSig
            self.opkID = opkID
            self.opk = opk
        }
    }

    /// The agreed session material.
    public struct Result: Sendable {
        /// The 32-byte shared secret.
        public let sk: Data
        /// The transcript hash: the session's associated data.
        public let ad: Data
        /// Whether a one-time prekey entered the agreement.
        public let usedOPK: Bool
    }

    private static func utf8(_ s: String) -> Data { Data(s.utf8) }

    private static func rejected() -> LumosCrypto.WireError {
        LumosCrypto.WireError(LumosErrors.errCryptoPrekeySignatureInvalid, "bundle signature rejected")
    }

    /// Whether sig binds dh to the Ed25519 identity ed (ADR-008).
    public static func verifyIdentityBinding(ed: Data, dh: Data, sig: Data) -> Bool {
        LumosCrypto.ed25519Verify(publicKeyRaw: ed, message: utf8(LumosConstants.x3dhIdentityBindingInfoValue) + dh,
                                  signature: sig)
    }

    /// Checks both bundle signatures.
    /// - Throws: ERR_CRYPTO_PREKEY_SIGNATURE_INVALID when either fails
    public static func verifyBundle(_ b: Bundle) throws {
        guard b.identityEd.count == 32, b.identityDH.count == 32, b.spk.count == 32 else { throw rejected() }
        guard verifyIdentityBinding(ed: b.identityEd, dh: b.identityDH, sig: b.identityDHSig) else { throw rejected() }
        let prekey = utf8(LumosConstants.x3dhPrekeySigningInfoValue) + LumosCrypto.be32(b.spkID) + b.spk
        guard LumosCrypto.ed25519Verify(publicKeyRaw: b.identityEd, message: prekey, signature: b.spkSig) else {
            throw rejected()
        }
    }

    /// The four identity keys a transcript binds, initiator first.
    public struct Parties: Sendable {
        /// Initiator Ed25519 identity.
        public let initiatorEd: Data
        /// Responder Ed25519 identity.
        public let responderEd: Data
        /// Initiator identity-DH public key.
        public let initiatorDH: Data
        /// Responder identity-DH public key.
        public let responderDH: Data
    }

    /// The TRS-CRY-014 transcript hash, initiator-first.
    public static func transcript(_ p: Parties, spkID: UInt32, opkID: UInt32, matchID: Data) -> Data {
        LumosCrypto.sha256(utf8(LumosConstants.x3dhTranscriptInfoValue) + p.initiatorEd + p.responderEd
            + p.initiatorDH + p.responderDH + LumosCrypto.be32(spkID) + LumosCrypto.be32(opkID)
            + Data([UInt8(LumosConstants.protocolVersionEncoded)]) + matchID)
    }

    private static func deriveSK(_ dhs: [Data]) -> Data {
        var ikm = Data(repeating: UInt8(LumosConstants.x3dhFPrefixFillByte), count: LumosConstants.x3dhFPrefixLen)
        for d in dhs { ikm += d }
        return LumosCrypto.hkdf(ikm: ikm, salt: Data(count: 32), info: utf8(LumosConstants.x3dhKdfInfoValue),
                                length: skSize)
    }

    private static func dh(_ priv: Data, _ pub: Data) throws -> Data {
        do {
            return try LumosCrypto.x25519(privateKeyRaw: priv, publicKeyRaw: pub)
        } catch {
            throw LumosCrypto.WireError(LumosErrors.errCryptoAeadAuthFailed, "dh failed")
        }
    }

    /// The raw public key of an X25519 scalar.
    /// - Throws: ERR_CRYPTO_AEAD_AUTH_FAILED on a malformed scalar
    public static func publicKey(of priv: Data) throws -> Data {
        do {
            return try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: priv).publicKey.rawRepresentation
        } catch {
            throw LumosCrypto.WireError(LumosErrors.errCryptoAeadAuthFailed, "bad scalar")
        }
    }

    /// The initiator side, against a verified bundle.
    /// - Throws: a signature or agreement failure, with its stable code
    public static func initiate(identityEd: Data, identityDHPriv: Data, ephemeralPriv: Data,
                                bundle: Bundle, matchID: Data) throws -> Result {
        try verifyBundle(bundle)
        var dhs = [try dh(identityDHPriv, bundle.spk), try dh(ephemeralPriv, bundle.identityDH),
                   try dh(ephemeralPriv, bundle.spk)]
        if let opk = bundle.opk {
            dhs.append(try dh(ephemeralPriv, opk))
        }
        let opkID = bundle.opk == nil ? UInt32(LumosConstants.x3dhOpkIDNoneDefault) : bundle.opkID
        let parties = Parties(initiatorEd: identityEd, responderEd: bundle.identityEd,
                              initiatorDH: try publicKey(of: identityDHPriv), responderDH: bundle.identityDH)
        let ad = transcript(parties, spkID: bundle.spkID, opkID: opkID, matchID: matchID)
        return Result(sk: deriveSK(dhs), ad: ad, usedOPK: bundle.opk != nil)
    }

    /// The responder's own secrets for one agreement.
    public struct ResponderSecrets: Sendable {
        /// The responder's Ed25519 public identity.
        public let identityEd: Data
        /// The identity-DH scalar.
        public let identityDHPriv: Data
        /// The signed-prekey scalar.
        public let spkPriv: Data
        /// Its id.
        public let spkID: UInt32
        /// The consumed one-time prekey scalar, or nil.
        public let opkPriv: Data?
        /// Its id; ignored when opkPriv is nil.
        public let opkID: UInt32

        /// Assembles the secrets.
        public init(identityEd: Data, identityDHPriv: Data, spkPriv: Data, spkID: UInt32, opkPriv: Data?, opkID: UInt32) {
            self.identityEd = identityEd
            self.identityDHPriv = identityDHPriv
            self.spkPriv = spkPriv
            self.spkID = spkID
            self.opkPriv = opkPriv
            self.opkID = opkID
        }
    }

    /// What the initiator sent: its identity, identity-DH key and ephemeral key.
    public struct InitiatorKeys: Sendable {
        /// The initiator's Ed25519 identity, pinned by the match.
        public let identityEd: Data
        /// Its identity-DH public key.
        public let identityDH: Data
        /// Its ephemeral public key.
        public let ephemeral: Data

        /// Assembles the keys.
        public init(identityEd: Data, identityDH: Data, ephemeral: Data) {
            self.identityEd = identityEd
            self.identityDH = identityDH
            self.ephemeral = ephemeral
        }
    }

    /// The responder side, once the initiator's keys arrive.
    /// - Throws: an agreement failure, with its stable code
    public static func respond(_ own: ResponderSecrets, initiator: InitiatorKeys, matchID: Data) throws -> Result {
        var dhs = [try dh(own.spkPriv, initiator.identityDH), try dh(own.identityDHPriv, initiator.ephemeral),
                   try dh(own.spkPriv, initiator.ephemeral)]
        if let opkPriv = own.opkPriv {
            dhs.append(try dh(opkPriv, initiator.ephemeral))
        }
        let used = own.opkPriv == nil ? UInt32(LumosConstants.x3dhOpkIDNoneDefault) : own.opkID
        let parties = Parties(initiatorEd: initiator.identityEd, responderEd: own.identityEd,
                              initiatorDH: initiator.identityDH, responderDH: try publicKey(of: own.identityDHPriv))
        let ad = transcript(parties, spkID: own.spkID, opkID: used, matchID: matchID)
        return Result(sk: deriveSK(dhs), ad: ad, usedOPK: own.opkPriv != nil)
    }
}
