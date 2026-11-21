/// Lumos - LumosProtocol / Double Ratchet session state.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-CRY-002, TRS-CRY-006, TRS-CRY-007, TRS-CRY-011, TRS-CRY-013
/// ADRs: ADR-001, ADR-008
/// Threading: serial-executor
/// Notes: A decision-for-decision port of protocol/crypto/ratchet/ratchet.go,
/// proven against test-vectors/crypto/ratchet_sequences.json. State is exactly
/// the TRS-CRY-002 inventory; a message key is dropped after one use and a
/// used skipped key is deleted (TRS-CRY-006) - Foundation's Data gives no
/// in-place zeroing guarantee, unlike the Java port's arrays; skipped keys are bounded by MAX_SKIP per chain, a session
/// cap and a TTL pruned against the CALLER's clock (TRS-CRY-007), so the
/// machine stays deterministic. Every failure carries a stable code
/// (TRS-CRY-013). A struct with mutating methods: one owner, one executor.

import CryptoKit
import Foundation
import LumosProtocolGenerated

/// One side of a Double Ratchet session.
public struct DoubleRatchet {

    /// The fixed header length (register RATCHET_HEADER_LAYOUT).
    public static let headerSize = LumosConstants.ratchetHeaderLayoutTotalLen

    /// The authenticated header: sender's ratchet key, previous chain length, number.
    public struct Header: Equatable, Sendable {
        /// The sender's current 32-byte ratchet public key.
        public let dh: Data
        /// The previous sending chain's length.
        public let pn: UInt32
        /// This message's number in its chain.
        public let n: UInt32

        /// Creates a header.
        public init(dh: Data, pn: UInt32, n: UInt32) {
            self.dh = dh
            self.pn = pn
            self.n = n
        }

        /// The canonical dh(32) | pn(4,BE) | n(4,BE) layout.
        public var bytes: Data { dh + LumosCrypto.be32(pn) + LumosCrypto.be32(n) }

        /// Parses exactly 40 bytes.
        /// - Throws: ERR_PAYLOAD_MALFORMED on any other length
        public static func parse(_ b: Data) throws -> Header {
            let bytes = [UInt8](b)
            guard bytes.count == DoubleRatchet.headerSize else {
                throw LumosCrypto.WireError(LumosErrors.errPayloadMalformed, "ratchet header must be 40 bytes")
            }
            func be32(_ at: Int) -> UInt32 {
                bytes[at..<at + 4].reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
            }
            return Header(dh: Data(bytes[0..<32]), pn: be32(32), n: be32(36))
        }
    }

    /// The register's denial-of-service limits; zero means the default.
    public struct Bounds: Sendable {
        /// Skipped keys allowed within one chain.
        public let maxSkip: Int
        /// Skipped keys allowed across the session.
        public let skippedCap: Int
        /// How long a skipped key lives, in seconds.
        public let skippedTTLSec: Int64

        /// Creates bounds; tests may narrow them, never widen.
        public init(maxSkip: Int = 0, skippedCap: Int = 0, skippedTTLSec: Int64 = 0) {
            self.maxSkip = maxSkip == 0 ? LumosConstants.maxSkipPerChainDefault : maxSkip
            self.skippedCap = skippedCap == 0 ? LumosConstants.skippedKeysPerSessionCapDefault : skippedCap
            self.skippedTTLSec = skippedTTLSec == 0
                ? Int64(LumosConstants.skippedKeyTTLDaysDefault) * 86_400 : skippedTTLSec
        }
    }

    private struct SkipKey: Hashable {
        let dh: Data
        let n: UInt32
    }

    private struct Skipped {
        var mk: Data
        let storedAt: Int64
    }

    private var dhsPriv: Data
    private var dhsPub: Data
    private var dhr: Data?
    private var rk: Data
    private var cks: Data?
    private var ckr: Data?
    private var ns: UInt32 = 0
    private var nr: UInt32 = 0
    private var pn: UInt32 = 0
    private let ad: Data
    private var skip: [SkipKey: Skipped] = [:]
    private let bounds: Bounds

    private init(sk: Data, ad: Data, ownPriv: Data, bounds: Bounds) throws {
        self.rk = sk
        self.ad = ad
        self.dhsPriv = ownPriv
        self.dhsPub = try DoubleRatchet.publicKey(of: ownPriv)
        self.bounds = bounds
    }

    /// Builds the sender-first side from X3DH's SK.
    /// - Throws: ERR_CRYPTO_AEAD_AUTH_FAILED if the agreement fails
    public static func initiator(sk: Data, ad: Data, remoteRatchetPub: Data, ownRatchetPriv: Data,
                                 bounds: Bounds = Bounds()) throws -> DoubleRatchet {
        var s = try DoubleRatchet(sk: sk, ad: ad, ownPriv: ownRatchetPriv, bounds: bounds)
        s.dhr = remoteRatchetPub
        let (root, chain) = DoubleRatchet.kdfRK(s.rk, try s.agree())
        s.rk = root
        s.cks = chain
        return s
    }

    /// Builds the receiver-first side with its published ratchet key.
    /// - Throws: ERR_CRYPTO_AEAD_AUTH_FAILED on a malformed scalar
    public static func responder(sk: Data, ad: Data, ownRatchetPriv: Data,
                                 bounds: Bounds = Bounds()) throws -> DoubleRatchet {
        try DoubleRatchet(sk: sk, ad: ad, ownPriv: ownRatchetPriv, bounds: bounds)
    }

    /// How many out-of-order message keys are stored (TRS-CRY-006 proofs).
    public var skippedCount: Int { skip.count }

    /// Produces the next message; now is the caller's clock, used only to prune.
    /// - Throws: ERR_CRYPTO_AEAD_AUTH_FAILED with no sending chain yet
    public mutating func encrypt(_ plaintext: Data, now: Int64) throws -> (Header, Data) {
        pruneExpired(now)
        guard let chain = cks else {
            throw LumosCrypto.WireError(LumosErrors.errCryptoAeadAuthFailed, "no sending chain yet")
        }
        let (mk, next) = DoubleRatchet.kdfCK(chain)
        cks = next
        let h = Header(dh: dhsPub, pn: pn, n: ns)
        let ct = try seal(mk, h, plaintext)
        ns += 1
        return (h, ct)
    }

    /// Consumes one message. A skipped-key hit consumes and deletes the key; a
    /// new remote key turns the DH ratchet with a fresh scalar from nextKey.
    /// - Throws: a stable crypto code; terminal for the frame
    public mutating func decrypt(_ h: Header, _ ciphertext: Data, now: Int64,
                                 nextKey: () throws -> Data) throws -> Data {
        pruneExpired(now)
        if let hit = skip.removeValue(forKey: SkipKey(dh: h.dh, n: h.n)) {
            return try open(hit.mk, h, ciphertext)
        }
        if dhr != h.dh {
            try skipMessageKeys(until: h.pn, now: now)
            try dhRatchet(h, nextKey: nextKey)
        }
        try skipMessageKeys(until: h.n, now: now)
        guard let chain = ckr else {
            throw LumosCrypto.WireError(LumosErrors.errCryptoAeadAuthFailed, "no receiving chain")
        }
        let (mk, next) = DoubleRatchet.kdfCK(chain)
        ckr = next
        nr += 1
        return try open(mk, h, ciphertext)
    }

    private mutating func skipMessageKeys(until: UInt32, now: Int64) throws {
        guard var chain = ckr, let remote = dhr else { return }
        if until > nr && Int(until - nr) > bounds.maxSkip {
            throw LumosCrypto.WireError(LumosErrors.errCryptoMaxSkipExceeded, "gap exceeds MAX_SKIP_PER_CHAIN")
        }
        while nr < until {
            if skip.count >= bounds.skippedCap {
                ckr = chain
                throw LumosCrypto.WireError(LumosErrors.errCryptoSkippedKeyCap, "session skipped-key cap reached")
            }
            let (mk, next) = DoubleRatchet.kdfCK(chain)
            chain = next
            skip[SkipKey(dh: remote, n: nr)] = Skipped(mk: mk, storedAt: now)
            nr += 1
        }
        ckr = chain
    }

    private mutating func dhRatchet(_ h: Header, nextKey: () throws -> Data) throws {
        pn = ns
        ns = 0
        nr = 0
        dhr = h.dh
        let (root1, receiving) = DoubleRatchet.kdfRK(rk, try agree())
        rk = root1
        ckr = receiving
        do {
            dhsPriv = try nextKey()
            dhsPub = try DoubleRatchet.publicKey(of: dhsPriv)
        } catch {
            throw LumosCrypto.WireError(LumosErrors.errCryptoAeadAuthFailed, "keygen failed")
        }
        let (root2, sending) = DoubleRatchet.kdfRK(rk, try agree())
        rk = root2
        cks = sending
    }

    private func agree() throws -> Data {
        do {
            return try LumosCrypto.x25519(privateKeyRaw: dhsPriv, publicKeyRaw: dhr ?? Data())
        } catch {
            throw LumosCrypto.WireError(LumosErrors.errCryptoAeadAuthFailed, "dh failed")
        }
    }

    private mutating func pruneExpired(_ now: Int64) {
        skip = skip.filter { now - $0.value.storedAt <= bounds.skippedTTLSec }
    }

    private static func publicKey(of priv: Data) throws -> Data {
        do {
            return try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: priv).publicKey.rawRepresentation
        } catch {
            throw LumosCrypto.WireError(LumosErrors.errCryptoAeadAuthFailed, "bad ratchet key")
        }
    }

    /// KDF_RK: HKDF-SHA-256(salt=rk, ikm=dh_out) into (rk', ck).
    private static func kdfRK(_ rk: Data, _ dhOut: Data) -> (Data, Data) {
        let okm = LumosCrypto.hkdf(ikm: dhOut, salt: rk,
                                   info: Data(LumosConstants.ratchetRootKdfInfoValue.utf8), length: 64)
        return (Data(okm.prefix(32)), Data(okm.suffix(32)))
    }

    /// KDF_CK: mk = HMAC(ck, 0x01), ck' = HMAC(ck, 0x02).
    private static func kdfCK(_ ck: Data) -> (Data, Data) {
        (LumosCrypto.hmacSHA256(key: ck, data: Data([UInt8(LumosConstants.chainKeySeedsMessage)])),
         LumosCrypto.hmacSHA256(key: ck, data: Data([UInt8(LumosConstants.chainKeySeedsNext)])))
    }

    private func seal(_ mk: Data, _ h: Header, _ plaintext: Data) throws -> Data {
        let (key, nonce) = DoubleRatchet.expand(mk)
        do {
            return try LumosCrypto.aeadSeal(key: key, nonce: nonce, plaintext: plaintext, aad: ad + h.bytes)
        } catch {
            throw LumosCrypto.WireError(LumosErrors.errCryptoAeadAuthFailed, "seal failed")
        }
    }

    private func open(_ mk: Data, _ h: Header, _ ciphertext: Data) throws -> Data {
        let (key, nonce) = DoubleRatchet.expand(mk)
        return try LumosCrypto.aeadOpen(key: key, nonce: nonce, ciphertext: ciphertext, aad: ad + h.bytes)
    }

    /// The register's message-key expansion: HKDF(mk, zero salt, msg info) into key || nonce.
    private static func expand(_ mk: Data) -> (Data, Data) {
        let okm = LumosCrypto.hkdf(ikm: mk, salt: Data(count: 32),
                                   info: Data(LumosConstants.ratchetMessageKdfInfoValue.utf8),
                                   length: LumosCrypto.aeadKeySize + LumosCrypto.aeadNonceSize)
        return (Data(okm.prefix(LumosCrypto.aeadKeySize)), Data(okm.suffix(LumosCrypto.aeadNonceSize)))
    }
}
