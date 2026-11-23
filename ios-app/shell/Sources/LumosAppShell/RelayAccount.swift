/// Lumos - LumosAppShell / This device's relay registration and the conversations' relay routes.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-BCK-001, TRS-BCK-003, TRS-BCK-007, TRS-LOC-001, SRS-RMT-001, SRS-RMT-016
/// ADRs: ADR-001, ADR-003
/// Threading: serial-executor
/// Notes: The Swift port of the Android RelayAccount and RelayRoutes.
/// Registration needs no phone number and no email (TRS-BCK-007): the device
/// makes a fresh Ed25519 key, signs the registry's challenge under the
/// DEVICE_AUTH_SIGNING_INFO domain, and gets back a routing id and a bearer
/// credential. The key is not the identity key, so the registration cannot be
/// linked to the identity by key reuse; it is used once and dropped. The
/// routing id and credential are one sealed RELAY_ACCOUNT row, so the
/// credential never rests in the clear. forget() drops it after the relay
/// refuses it; the next ensure() registers afresh.
///
/// A route has two halves: this device's receive capability, minted for one
/// peer (the relay holds only its SHA-256, the proof), and the peer's routing
/// id plus the capability the peer minted for this device. Each peer gets its
/// own capability, so a fetched envelope's proof names the conversation and
/// revoking one stops exactly one peer (SRS-RMT-016). allowedFor records the
/// registration a proof was allowed under: after a fresh registration every
/// route reads as not yet allowed and is allowed again.
///
/// Ending a conversation (unmatch, block) marks its route ending in the same
/// transaction that removes the conversation. An ending route accepts nothing
/// and is never allowed again; the next relay pass revokes its proof and then
/// forgets it, so going offline at the moment of an unmatch cannot leave the
/// peer able to reach this device. Format 1 rows read as not ending.

import CryptoKit
import Foundation
import LumosProtocol
import LumosProtocolGenerated

/// Fresh random bytes from a cryptographically secure source.
public typealias RandomBytes = (Int) -> Data

/// The platform CSPRNG (SystemRandomNumberGenerator is cryptographically secure on Apple platforms and Linux).
public func secureRandomBytes(_ n: Int) -> Data {
    var g = SystemRandomNumberGenerator()
    return Data((0..<n).map { _ in UInt8.random(in: 0...255, using: &g) })
}

/// This device's relay registration.
public final class RelayAccount {

    static let row = "self"
    private static let format: UInt8 = 1
    private static let encodedSize = 1 + RelayClient.routingIDSize + RelayClient.keySize

    private let store: LocalStore

    public init(store: LocalStore) {
        self.store = store
    }

    static func authMessage(challenge: Data, devicePub: Data) -> Data {
        Data(LumosConstants.deviceAuthSigningInfoValue.utf8) + challenge + devicePub
    }

    /// The stored registration, if any.
    public func current() throws -> RelayTypes.Registration? {
        guard let b = try store.get(kind: .relayAccount, id: RelayAccount.row) else { return nil }
        guard b.count == RelayAccount.encodedSize, b.first == RelayAccount.format else {
            throw StoreError.engine("relay account row is unreadable")
        }
        let body = b.dropFirst()
        return RelayTypes.Registration(routingID: Data(body.prefix(RelayClient.routingIDSize)),
                                       credential: Data(body.suffix(RelayClient.keySize)))
    }

    /// The registration, registering first when there is none.
    /// - Throws: ``RelayError`` from the registry; ``StoreError`` on engine failure
    public func ensure(_ anonymous: RelayClient) throws -> RelayTypes.Registration {
        if let have = try current() { return have }
        let challenge = try anonymous.challenge()
        let key = Curve25519.Signing.PrivateKey()
        let devicePub = key.publicKey.rawRepresentation
        let signature = try key.signature(for: RelayAccount.authMessage(challenge: challenge, devicePub: devicePub))
        let reg = try anonymous.register(challenge: challenge, devicePub: devicePub, signature: signature)
        try store.put(kind: .relayAccount, id: RelayAccount.row,
                      plaintext: Data([RelayAccount.format]) + reg.routingID + reg.credential)
        return reg
    }

    /// Drops the registration, after the relay refused its credential.
    public func forget() throws {
        try store.delete(kind: .relayAccount, id: RelayAccount.row)
    }
}

/// The conversations' relay routes, one sealed row each.
public final class RelayRoutes {

    private static let formatV1: UInt8 = 1
    private static let format: UInt8 = 2
    private static let maxField = 64

    /// One conversation's route.
    public struct Route: Sendable, Equatable {
        public let matchID: Data
        /// The capability this device minted for the peer.
        public let receiveCapability: Data
        /// The peer's routing id, or empty until the peer's half arrives.
        public let peerRoutingID: Data
        /// The capability the peer minted for this device, or empty.
        public let sendCapability: Data
        /// The routing id this route's proof was allowed under, or empty.
        public let allowedFor: Data
        /// True once the conversation has ended: revoke, then forget.
        public var ending: Bool = false

        /// Whether both halves are known, so this device can send.
        public var canSend: Bool {
            peerRoutingID.count == RelayClient.routingIDSize && sendCapability.count == RelayClient.keySize
        }

        /// What the relay holds for the receive capability.
        public var proof: Data { LumosCrypto.sha256(receiveCapability) }

        var encoded: Data {
            var out = Data([RelayRoutes.format])
            for f in [matchID, receiveCapability, peerRoutingID, sendCapability, allowedFor] {
                out.append(UInt8(f.count))
                out += f
            }
            out.append(ending ? 1 : 0)
            return out
        }

        static func decode(_ b: Data) throws -> Route {
            let bytes = [UInt8](b)
            guard let format = bytes.first, format == RelayRoutes.format || format == RelayRoutes.formatV1 else {
                throw RelayRoutes.unreadable
            }
            var at = 1
            var fields: [Data] = []
            for _ in 0..<5 {
                guard at < bytes.count else { throw RelayRoutes.unreadable }
                let n = Int(bytes[at])
                at += 1
                guard n <= RelayRoutes.maxField, bytes.count - at >= n else { throw RelayRoutes.unreadable }
                fields.append(Data(bytes[at..<at + n]))
                at += n
            }
            var ending = false
            if format == RelayRoutes.format {
                guard at < bytes.count, bytes[at] <= 1 else { throw RelayRoutes.unreadable }
                ending = bytes[at] == 1
                at += 1
            }
            guard at == bytes.count else { throw RelayRoutes.unreadable }
            return Route(matchID: fields[0], receiveCapability: fields[1], peerRoutingID: fields[2],
                         sendCapability: fields[3], allowedFor: fields[4], ending: ending)
        }
    }

    private static let unreadable = StoreError.engine("relay route row is unreadable")

    private let store: LocalStore
    private let random: RandomBytes

    public init(store: LocalStore, random: @escaping RandomBytes = secureRandomBytes) {
        self.store = store
        self.random = random
    }

    /// This device's half for a match, minting it the first time.
    @discardableResult
    public func issue(matchID: Data) throws -> Route {
        if let have = try forMatch(matchID) { return have }
        let r = Route(matchID: matchID, receiveCapability: random(RelayClient.keySize), peerRoutingID: Data(),
                      sendCapability: Data(), allowedFor: Data())
        try save(r)
        return r
    }

    /// Stores the peer's half. An ending route stays as it is.
    /// - Throws: ``StoreError`` when no route was issued or the half is malformed
    public func setPeer(matchID: Data, peerRoutingID: Data, sendCapability: Data) throws {
        guard peerRoutingID.count == RelayClient.routingIDSize, sendCapability.count == RelayClient.keySize else {
            throw StoreError.engine("a peer half is a routing id and a capability")
        }
        guard let r = try forMatch(matchID) else { throw StoreError.engine("no route issued for this match") }
        guard !r.ending else { return }
        try save(Route(matchID: matchID, receiveCapability: r.receiveCapability, peerRoutingID: peerRoutingID,
                       sendCapability: sendCapability, allowedFor: r.allowedFor))
    }

    /// Records that the proof is allowed under a registration.
    public func markAllowed(matchID: Data, routingID: Data) throws {
        guard let x = try forMatch(matchID), !x.ending else { return }
        try save(Route(matchID: matchID, receiveCapability: x.receiveCapability, peerRoutingID: x.peerRoutingID,
                       sendCapability: x.sendCapability, allowedFor: routingID))
    }

    /// Marks a match's route ending: nothing arrives under it any more, and the
    /// next relay pass revokes and forgets it. Call inside the transaction that
    /// removes the conversation.
    public func markEnding(matchID: Data) throws {
        guard var x = try forMatch(matchID), !x.ending else { return }
        x.ending = true
        try save(x)
    }

    /// A match's route, if any.
    public func forMatch(_ matchID: Data) throws -> Route? {
        try store.findByNaturalKey(kind: .relayRoute, naturalKey: matchID).first.map(Route.decode)
    }

    /// Every route.
    public func all() throws -> [Route] {
        try store.allOf(kind: .relayRoute).map { try Route.decode($0.content) }
    }

    /// Proof (hex) to match id for every open route, naming fetched envelopes'
    /// conversations; ending routes are left out, so their envelopes are dropped.
    public func byProof() throws -> [String: Data] {
        Dictionary(try all().filter { !$0.ending }.map { (RelayHex.encode($0.proof), $0.matchID) },
                   uniquingKeysWith: { first, _ in first })
    }

    /// Forgets a match's route.
    public func remove(matchID: Data) throws {
        for e in try store.findEntriesByNaturalKey(kind: .relayRoute, naturalKey: matchID) {
            try store.delete(kind: .relayRoute, id: e.id)
        }
    }

    private func save(_ r: Route) throws {
        try store.putByNaturalKey(kind: .relayRoute, plaintext: r.encoded, naturalKey: r.matchID)
    }
}
