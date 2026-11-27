/// Lumos - ios-app/shell / What discovery finds, through the feed's vetoes, on iOS.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-NBY-005, SRS-NBY-012, SRS-NBY-013, SRS-SAF-002, TRS-ENV-001
/// ADRs: ADR-002, ADR-013
/// Threading: test-only
/// Notes: The Android DiscoveryFeedTest, assertion for assertion: two
/// DiscoveryEngines on a back-to-back radio, the first writing into the
/// presence table its Nearby feed reads. A peer found over a real HELLO
/// exchange is a card; a hidden or blocked identity found again over a fresh
/// exchange is not - the engine finds, the feed decides what the person sees.

import CryptoKit
import Foundation
import LumosProtocol
import LumosProtocolGenerated
import XCTest
@testable import LumosAppShell

/// One end of a back-to-back radio; the other phone's engine is the far end.
private final class End: DiscoveryRadio {
    weak var other: End?
    var engine: DiscoveryEngine!
    let token: String
    var now: Int64
    let air: Air
    var connects = 0

    init(token: String, now: Int64, air: Air) {
        self.token = token
        self.now = now
        self.air = air
    }

    private func theirs(_ handle: String) -> String {
        handle.hasPrefix("in-") ? String(handle.dropFirst(3)) : "in-" + handle
    }

    func connect(handle: String, linkToken: String) {
        connects += 1
        air.queue.append { [unowned self] in
            other?.engine.onCentralSubscribed(handle: "in-" + handle, nowMillis: now)
            engine.onLinkReady(handle: handle, nowMillis: now)
        }
    }

    func send(handle: String, frame: Data) {
        let far = theirs(handle)
        air.queue.append { [unowned self] in other?.engine.onFrame(handle: far, frame: frame, nowMillis: now) }
    }

    func disconnect(handle: String) {
        let far = theirs(handle)
        air.queue.append { [unowned self] in other?.engine.onLinkClosed(handle: far) }
    }
}

private final class Air {
    var queue: [() -> Void] = []
}

private struct Identity {
    let signing = Curve25519.Signing.PrivateKey()
    let fields: Hello.Fields

    init(index: Int) throws {
        let xPublic = Curve25519.KeyAgreement.PrivateKey().publicKey.rawRepresentation
        let binding = try signing.signature(for: Data(LumosConstants.x3dhIdentityBindingInfoValue.utf8) + xPublic)
        var eid = Data(count: Hello.eidLen)
        eid[0] = UInt8(index & 0xFF)
        fields = Hello.Fields(protocolVersion: LumosConstants.protocolVersionEncoded, capabilityBits: 1,
                              venueHint: 0, eid: eid, ed25519Public: signing.publicKey.rawRepresentation,
                              x25519Public: xPublic, bindingSignature: binding,
                              nonce: Data(count: Hello.nonceLen))
    }

    func freshExchange() -> HelloExchange {
        let key = signing
        return HelloExchange(signer: { try key.signature(for: $0) }, own: fields)
    }
}

private struct Roomy: StorageCapacity {
    var usableSpaceBytes: Int64 { Int64.max }
}

final class DiscoveryFeedTests: XCTestCase {

    private static let now: Int64 = 1_755_561_600_000
    private static let ageOut = Int64(LumosConstants.presenceAgeoutSecDefault) * 1000
    private let air = Air()
    private var ends: [End] = []

    private func nonce() -> Data {
        Data((0..<Hello.nonceLen).map { _ in UInt8.random(in: 0...255) })
    }

    private func pair(_ mine: PresenceTable, refuses: @escaping (String) -> Bool = { _ in false }) throws -> End {
        let me = End(token: "me", now: Self.now, air: air)
        let them = End(token: "them", now: Self.now, air: air)
        me.other = them
        them.other = me
        let myIdentity = try Identity(index: 1)
        let theirIdentity = try Identity(index: 2)
        me.engine = DiscoveryEngine(radio: me, freshExchange: myIdentity.freshExchange, nonces: nonce,
                                    handshakeTimeoutMillis: 10_000, presence: mine, refuses: refuses)
        them.engine = DiscoveryEngine(radio: them, freshExchange: theirIdentity.freshExchange, nonces: nonce,
                                      handshakeTimeoutMillis: 10_000)
        me.engine.start()
        them.engine.start()
        ends = [me, them]
        return me
    }

    private func find(_ me: End, _ now: Int64) {
        me.now = now
        me.other?.now = now
        me.engine.onSighting(BleSighting(linkToken: "them", serviceData: nil, rssiDbm: -50, atMillis: now))
        me.engine.tick(nowMillis: now)
        while !air.queue.isEmpty {
            air.queue.removeFirst()()
        }
    }

    private func openInMemory() throws -> LocalStore {
        let database = try Sqlite3Database(path: ":memory:")
        let key = try DataKeyHierarchy.create(kek: SoftwareKekStore()).deriveKey(for: .database)
        return try LocalStore.open(database: database, databaseDomainKey: key, capacity: Roomy(), now: { 0 })
    }

    private func feed(_ store: LocalStore, _ presence: PresenceTable) -> NearbyFeedController {
        NearbyFeedController(presence: presence, hidden: HiddenPeers(store: store),
                             blocked: BlockedPeers(store: store), ladder: RescanLadder(),
                             metric: DiscoverySuccessMetric(store: store))
    }

    private func isEmptyScan(_ state: FeedState) -> Bool {
        if case .scanningEmpty = state { return true }
        return false
    }

    func testAPeerFoundOverTheRadioIsACardOnTheFeed() throws {
        let store = try openInMemory()
        let presence = PresenceTable()
        let me = try pair(presence)
        let screen = feed(store, presence)
        XCTAssertTrue(isEmptyScan(try screen.state(nowMillis: Self.now)))

        find(me, Self.now)
        let cards = try screen.state(nowMillis: Self.now).cardList
        XCTAssertEqual(cards.count, 1, "discovery feeds the screen")
        XCTAssertEqual(cards.first?.peerKey, me.engine.cards.first?.peerKey)
    }

    func testABlockedPeerIsNotAnsweredAndNotDialledAgain() throws {
        let store = try openInMemory()
        let blocked = BlockedPeers(store: store)
        let presence = PresenceTable()
        let me = try pair(presence, refuses: { key in (try? blocked.isBlocked(peerKey: key)) ?? true })
        find(me, Self.now)
        let key = try XCTUnwrap(me.engine.cards.first?.peerKey)
        try blocked.block(peerKey: key, atUtcMillis: Self.now)

        let later = Self.now + Self.ageOut + 1
        me.engine.tick(nowMillis: later)
        me.other?.engine.tick(nowMillis: later)
        let before = me.connects
        find(me, later)
        XCTAssertEqual(me.connects, before + 1, "one link, to learn who it is")
        XCTAssertTrue(me.engine.cards.isEmpty, "a blocked identity is never recorded")
        XCTAssertEqual(me.other?.engine.cards.count, 0, "and gets no ACK, so no card of this person (SRS-SAF-002)")
        XCTAssertEqual(me.engine.activeLinks, 0, "the refused link is closed at once")

        find(me, later + 1000)
        XCTAssertEqual(me.connects, before + 1, "not dialled again while the refusal stands")
        find(me, later + Self.ageOut + 1)
        XCTAssertEqual(me.connects, before + 2, "the refusal memory ages out like presence")
        XCTAssertEqual(me.other?.engine.cards.count, 0)
    }

    func testAHiddenOrBlockedPeerFoundAgainStaysOffTheFeed() throws {
        let store = try openInMemory()
        let presence = PresenceTable()
        let me = try pair(presence)
        let screen = feed(store, presence)
        find(me, Self.now)
        let key = try XCTUnwrap(me.engine.cards.first?.peerKey)

        try screen.hideCard(peerKey: key, atUtcMillis: Self.now)
        XCTAssertTrue(isEmptyScan(try screen.state(nowMillis: Self.now)), "hidden at once")

        let later = Self.now + Self.ageOut + 1
        _ = try screen.state(nowMillis: later)
        find(me, later)
        XCTAssertEqual(me.engine.cards.map(\.peerKey), [key], "found again, same identity under fresh nonces")
        XCTAssertTrue(isEmptyScan(try feed(store, presence).state(nowMillis: later)),
                      "a hidden identity stays hidden when rediscovered (SRS-NBY-012)")

        try BlockedPeers(store: store).block(peerKey: key, atUtcMillis: later)
        let again = later + Self.ageOut + 1
        _ = try screen.state(nowMillis: again)
        find(me, again)
        XCTAssertTrue(isEmptyScan(try feed(store, presence).state(nowMillis: again)),
                      "a blocked identity never comes back through discovery (SRS-NBY-013)")
    }
}
