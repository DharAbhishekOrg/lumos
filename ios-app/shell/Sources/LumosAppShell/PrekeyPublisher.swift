/// Lumos - LumosAppShell / Prekeys: mint, store the private halves, then publish; start sessions; swap routes.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-CRY-005, TRS-BCK-005, SRS-RMT-001, SRS-RMT-007, SRS-MAT-005, SRS-MAT-006
/// ADRs: ADR-001, ADR-002, ADR-008
/// Threading: serial-executor
/// Notes: The Swift port of the Android PrekeyPublisher, RelaySessions and
/// RouteExchange.
///
/// Prekeys: the order is the safety property. A batch's private halves and the
/// id counter are written in ONE transaction before anything is uploaded, so a
/// peer can never claim a prekey this device cannot open. If the upload fails,
/// or succeeds and the answer is lost, the next attempt mints a fresh batch
/// with fresh ids: the old private halves stay, because the registry may hold
/// their publics, and an id is never reused (TRS-CRY-005). The counters live
/// in their own RELAY_ACCOUNT row that forgetting a registration leaves alone,
/// so ids stay monotonic across registrations. A fresh registration is a
/// fresh, empty prekey store at the registry, so the bundle is republished
/// whenever the registration it was published under is not the current one.
///
/// Sessions: the initiator claims the peer's bundle and starts X3DH against
/// the identity the match pinned; a bundle for any other identity is refused
/// by ChatStore.startSession, so a lying registry gets nowhere.
///
/// Routes: after a match each side sends a RELAY_ROUTE (0x07) over the
/// authenticated nearby session, gated by the consent gate in both directions
/// (SRS-MAT-005/006), and the class is never put on the relay.

import Foundation
import LumosProtocol
import LumosProtocolGenerated

/// Keeps this device's prekeys published.
public final class PrekeyPublisher {

    static let row = "prekeys"
    private static let format: UInt8 = 1
    private static let stateBytes = 1 + 4 + 4 + 1 + RelayClient.routingIDSize

    /// Signs with the identity key.
    public typealias Signer = (Data) throws -> Data

    struct State: Equatable {
        let signedID: UInt32
        let nextOneTime: UInt32
        let publishedFor: Data

        var encoded: Data {
            var out = Data([PrekeyPublisher.format]) + LumosCrypto.be32(signedID) + LumosCrypto.be32(nextOneTime)
            out.append(UInt8(publishedFor.count))
            out += publishedFor + Data(count: RelayClient.routingIDSize - publishedFor.count)
            return out
        }

        static func decode(_ b: Data) throws -> State {
            let x = [UInt8](b)
            guard x.count == PrekeyPublisher.stateBytes, x[0] == PrekeyPublisher.format else {
                throw PrekeyPublisher.unreadable
            }
            func u32(_ at: Int) -> UInt32 { x[at..<at + 4].reduce(UInt32(0)) { $0 << 8 | UInt32($1) } }
            let n = Int(x[9])
            guard n == 0 || n == RelayClient.routingIDSize else { throw PrekeyPublisher.unreadable }
            return State(signedID: u32(1), nextOneTime: u32(5), publishedFor: n == 0 ? Data() : Data(x[10...]))
        }
    }

    private static let unreadable = StoreError.engine("prekey counters are unreadable")

    private let store: LocalStore
    private let chat: ChatStore
    private let own: RemoteSession.Identity
    private let signer: Signer
    private let random: RandomBytes

    public init(store: LocalStore, chat: ChatStore, own: RemoteSession.Identity, signer: @escaping Signer,
                random: @escaping RandomBytes = secureRandomBytes) {
        self.store = store
        self.chat = chat
        self.own = own
        self.signer = signer
        self.random = random
    }

    func state() throws -> State {
        try store.get(kind: .relayAccount, id: PrekeyPublisher.row).map(State.decode)
            ?? State(signedID: 0, nextOneTime: 1, publishedFor: Data())
    }

    /// Publishes a bundle under a new registration, or tops up one-time keys.
    /// - Returns: how many one-time keys were published
    @discardableResult
    public func ensure(client: RelayClient, registration: RelayTypes.Registration) throws -> Int {
        let s = try state()
        if s.publishedFor != registration.routingID {
            let (bundle, oneTime) = try mint(s, withBundle: true, count: LumosConstants.oneTimePrekeysUploadedDefault)
            try client.publishPrekeys(bundle: bundle, oneTime: oneTime)
            let after = try state()
            try store.put(kind: .relayAccount, id: PrekeyPublisher.row,
                          plaintext: State(signedID: after.signedID, nextOneTime: after.nextOneTime,
                                           publishedFor: registration.routingID).encoded)
            return oneTime.count
        }
        let status = try client.prekeyStatus()
        guard status.needsReplenish else { return 0 }
        let room = Int(max(0, Int64(LumosConstants.oneTimePrekeysUploadedDefault) - status.remaining))
        guard room > 0 else { return 0 }
        let (_, oneTime) = try mint(s, withBundle: false, count: room)
        try client.publishPrekeys(bundle: nil, oneTime: oneTime)
        return oneTime.count
    }

    private func mint(_ s: State, withBundle: Bool, count: Int) throws -> (X3DH.Bundle?, [RelayTypes.OneTimeKey]) {
        let signedID64 = Int64(s.signedID) + (withBundle ? 1 : 0)
        let next64 = Int64(s.nextOneTime) + Int64(count)
        guard let signedID = UInt32(exactly: signedID64), UInt32(exactly: next64) != nil else {
            throw StoreError.engine("prekey ids exhausted")
        }
        var secrets: [PrekeySecret] = []
        var publics: [RelayTypes.OneTimeKey] = []
        var bundle: X3DH.Bundle?
        if withBundle {
            let spkPriv = random(32)
            secrets.append(try PrekeySecret(oneTime: false, id: signedID, privateKey: spkPriv))
            let spkPub = try X3DH.publicKey(of: spkPriv)
            let signed = Data(LumosConstants.x3dhPrekeySigningInfoValue.utf8) + LumosCrypto.be32(signedID) + spkPub
            bundle = X3DH.Bundle(identityEd: own.identityEd, identityDH: try X3DH.publicKey(of: own.identityDHPriv),
                                 identityDHSig: own.identityDHSig, spkID: signedID, spk: spkPub,
                                 spkSig: try signer(signed), opkID: 0, opk: nil)
        }
        for k in 0..<count {
            let id = s.nextOneTime + UInt32(k)
            let priv = random(32)
            secrets.append(try PrekeySecret(oneTime: true, id: id, privateKey: priv))
            publics.append(RelayTypes.OneTimeKey(id: id, publicKey: try X3DH.publicKey(of: priv)))
        }
        let next = State(signedID: signedID, nextOneTime: UInt32(next64), publishedFor: s.publishedFor)
        try store.transaction {
            for p in secrets {
                try chat.putPrekeySecret(p)
            }
            try store.put(kind: .relayAccount, id: PrekeyPublisher.row, plaintext: next.encoded)
        }
        return (bundle, publics)
    }
}

/// Starts X3DH sessions from claimed bundles.
public final class RelaySessions {
    private let chat: ChatStore
    private let routes: RelayRoutes
    private let account: RelayAccount
    private let anonymous: RelayClient
    private let random: RandomBytes

    public init(chat: ChatStore, routes: RelayRoutes, account: RelayAccount, anonymous: RelayClient,
                random: @escaping RandomBytes = secureRandomBytes) {
        self.chat = chat
        self.routes = routes
        self.account = account
        self.anonymous = anonymous
        self.random = random
    }

    /// Starts a session with a matched peer if there is none yet.
    /// - Returns: true when a session exists afterwards; false while there is no route or no bundle
    public func start(matchID: Data, peerIdentityEd: Data) throws -> Bool {
        if try chat.hasSession(matchID: matchID) { return true }
        guard let route = try routes.forMatch(matchID), route.canSend else { return false }
        let reg = try account.ensure(anonymous)
        guard let bundle = try anonymous.as(credential: reg.credential).claim(routingID: route.peerRoutingID) else {
            return false
        }
        try chat.startSession(matchID: matchID, peerIdentityEd: peerIdentityEd, bundle: bundle,
                              ephemeralPriv: random(32), ratchetPriv: random(32))
        return true
    }
}

/// The RELAY_ROUTE swap over the nearby session.
public final class RouteExchange {

    /// What taking a peer's route did.
    public enum Taken: Sendable, Equatable {
        case stored
        case notMatched
        case malformed
    }

    private let routes: RelayRoutes
    private let account: RelayAccount

    public init(routes: RelayRoutes, account: RelayAccount) {
        self.routes = routes
        self.account = account
    }

    /// This device's RELAY_ROUTE payload for a match, or nil before mutual
    /// acceptance or before this device has a registration.
    public func offer(matchID: Data, state: MatchMachine.State) throws -> Data? {
        guard ConsentGate.permits(.relayRoute, state), let reg = try account.current() else { return nil }
        let own = try routes.issue(matchID: matchID)
        return try RelayRoute(routingID: reg.routingID, capability: own.receiveCapability).encoded
    }

    /// Stores a peer's RELAY_ROUTE, minting this device's half if needed.
    public func take(matchID: Data, state: MatchMachine.State, payload: Data) throws -> Taken {
        guard ConsentGate.permits(.relayRoute, state) else { return .notMatched }
        guard let peer = try? RelayRoute.decode(payload) else { return .malformed }
        try routes.issue(matchID: matchID)
        try routes.setPeer(matchID: matchID, peerRoutingID: peer.routingID, sendCapability: peer.capability)
        return .stored
    }
}
