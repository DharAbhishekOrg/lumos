/// Lumos - LumosAppShellTests / Unmatch and block end to end, and the route row's ending mark.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-MAT-010, SRS-RMT-016, SRS-SAF-001, SRS-SAF-002, SRS-NBY-013, TRS-LOC-001, TRS-TST-001
/// ADRs: ADR-001, ADR-003
/// Threading: test-only
/// Notes: The Swift side of the Android ConversationSafetyTest and
/// RelayRoutesTest, scenario for scenario, over the fake relay and real stores
/// on real files. Ending a conversation needs no network at the tap, survives
/// a kill before the next pass, and leaves the peer unable to put anything on
/// this device's screen - sent after the revoke or already waiting before it.

import Foundation
import LumosProtocol
import LumosProtocolGenerated
import XCTest
@testable import LumosAppShell

final class ConversationSafetyTests: XCTestCase {

    private let clock = TestClock(1_700_000_000_000)
    private lazy var relay = FakeRelay(clock: clock)

    private func device() throws -> RelayDevice {
        let d = try RelayDevice(clock: clock, transport: relay)
        XCTAssertNil(try d.sync.run().stoppedBy)
        return d
    }

    private func match(_ a: RelayDevice, _ b: RelayDevice) throws -> Data {
        let m = randomBytes(16)
        try a.chat.acceptConversation(matchID: m, peerIdentityEd: b.edPub)
        try b.chat.acceptConversation(matchID: m, peerIdentityEd: a.edPub)
        _ = try b.exchange.take(matchID: m, state: .matched,
                                payload: try XCTUnwrap(try a.exchange.offer(matchID: m, state: .matched)))
        _ = try a.exchange.take(matchID: m, state: .matched,
                                payload: try XCTUnwrap(try b.exchange.offer(matchID: m, state: .matched)))
        _ = try a.sync.run()
        _ = try b.sync.run()
        XCTAssertTrue(try a.sessions.start(matchID: m, peerIdentityEd: b.edPub))
        return m
    }

    private func text(_ s: String) throws -> RemoteText.TextEvent {
        try RemoteText.TextEvent(kind: .text, target: nil, body: Data(s.utf8))
    }

    /// Alice and Bob have talked; Bob has one reply still waiting to go.
    private func talked(_ alice: RelayDevice, _ bob: RelayDevice) throws -> Data {
        let m = try match(alice, bob)
        try alice.chat.send(matchID: m, event: try text("Hi Bob"))
        _ = try alice.sync.run()
        XCTAssertEqual(try bob.sync.run().shown, 1)
        relay.offline = true
        try bob.chat.send(matchID: m, event: try text("Hi"))
        relay.offline = false
        return m
    }

    private func outboxHolds(_ d: RelayDevice, _ m: Data) throws -> Bool {
        try d.chat.pendingOutbox().contains { $0.matchID == m }
    }

    func testUnmatchDeletesTheChatFromThisPhoneAndEndsTheRouteWithoutTheNetwork() throws {
        let alice = try device(), bob = try device()
        let m = try talked(alice, bob)
        XCTAssertEqual(try bob.chat.messages(matchID: m).count, 2)
        XCTAssertTrue(try outboxHolds(bob, m))
        XCTAssertEqual(try bob.store.count(kind: .ratchetSession), 1)

        relay.offline = true
        XCTAssertTrue(try bob.safety.end(matchID: m, how: .unmatch), "no network is needed to end it")

        XCTAssertTrue(try bob.chat.messages(matchID: m).isEmpty, "every message is gone")
        XCTAssertNil(try bob.chat.peerIdentity(matchID: m), "the conversation is gone")
        XCTAssertFalse(try outboxHolds(bob, m), "the unsent reply never goes")
        XCTAssertEqual(try bob.store.count(kind: .ratchetSession), 0, "the session keys are gone")
        XCTAssertTrue(try XCTUnwrap(try bob.routes.forMatch(m)).ending, "the route waits to be revoked")
        XCTAssertTrue(try bob.routes.byProof().isEmpty, "and accepts nothing meanwhile")
        XCTAssertFalse(try BlockedPeers(store: bob.store).isBlocked(peerKey: ConversationSafety.peerKey(of: alice.edPub)),
                       "an unmatch is not a block")
    }

    func testTheNextPassRevokesSoTheirLaterMessagesNeverReachThisDevice() throws {
        let alice = try device(), bob = try device()
        let m = try talked(alice, bob)
        XCTAssertTrue(try bob.safety.end(matchID: m, how: .unmatch))
        XCTAssertNil(try bob.sync.run().stoppedBy)
        XCTAssertNil(try bob.routes.forMatch(m), "revoked, then forgotten")

        try alice.chat.send(matchID: m, event: try text("Hello?"))
        XCTAssertEqual(try alice.sync.run().sent, 1, "the relay takes it: Alice is not told")
        XCTAssertEqual(relay.waiting(try bob.routingID()), 0, "and nothing reaches Bob (SRS-RMT-016)")
    }

    func testAnEndingSurvivesBeingKilledOfflineAndAMessageAlreadyWaitingIsNeverShown() throws {
        let alice = try device(), bob = try device()
        let m = try talked(alice, bob)
        try alice.chat.send(matchID: m, event: try text("Are you there?"))
        XCTAssertEqual(try alice.sync.run().sent, 1)
        XCTAssertEqual(relay.waiting(try bob.routingID()), 1, "already in Bob's mailbox before he blocks")

        relay.offline = true
        XCTAssertTrue(try bob.safety.end(matchID: m, how: .block))
        XCTAssertEqual(try bob.sync.run().stoppedBy?.kind, .offline, "offline: the pass stops and will retry")
        XCTAssertTrue(try XCTUnwrap(try bob.routes.forMatch(m)).ending, "kept, so the revoke is retried")
        try bob.kill()
        XCTAssertTrue(try XCTUnwrap(try bob.routes.forMatch(m)).ending, "the mark is on disk, not in memory")

        relay.offline = false
        let r = try bob.sync.run()
        XCTAssertNil(r.stoppedBy)
        XCTAssertEqual(r.shown, 0, "the waiting message is dropped unread")
        XCTAssertEqual(r.acknowledged, 1, "and deleted from the relay")
        XCTAssertNil(try bob.routes.forMatch(m))
        XCTAssertTrue(try bob.chat.messages(matchID: m).isEmpty)

        try alice.chat.send(matchID: m, event: try text("Hello?"))
        _ = try alice.sync.run()
        XCTAssertEqual(relay.waiting(try bob.routingID()), 0)
    }

    func testBlockPutsTheIdentityOnTheListTheNearbyFeedReads() throws {
        let alice = try device(), bob = try device()
        let m = try talked(alice, bob)
        XCTAssertTrue(try bob.safety.end(matchID: m, how: .block))
        let presenceKey = RelayHex.encode(Hello.peerId(alice.edPub))
        XCTAssertEqual(ConversationSafety.peerKey(of: alice.edPub), presenceKey,
                       "the key a presence card carries for the same identity")
        XCTAssertTrue(try BlockedPeers(store: bob.store).isBlocked(peerKey: presenceKey))
        XCTAssertEqual(try bob.store.count(kind: .blockEntry), 1)
        try BlockedPeers(store: bob.store).block(peerKey: presenceKey, atUtcMillis: clock.now())
        XCTAssertEqual(try bob.store.count(kind: .blockEntry), 1, "blocking twice leaves one entry")
    }

    func testEndingAConversationThatDoesNotExistChangesNothing() throws {
        let alice = try device(), bob = try device()
        let m = try talked(alice, bob)
        XCTAssertFalse(try bob.safety.end(matchID: randomBytes(16), how: .block))
        XCTAssertEqual(try bob.chat.messages(matchID: m).count, 2)
        XCTAssertFalse(try XCTUnwrap(try bob.routes.forMatch(m)).ending)
        XCTAssertEqual(try bob.store.count(kind: .blockEntry), 0)
    }

    func testTheOtherConversationsAreUntouched() throws {
        let alice = try device(), bob = try device(), cara = try device()
        let withAlice = try talked(alice, bob)
        let withCara = try match(cara, bob)
        try cara.chat.send(matchID: withCara, event: try text("Hey"))
        _ = try cara.sync.run()
        XCTAssertEqual(try bob.sync.run().shown, 1)

        XCTAssertTrue(try bob.safety.end(matchID: withAlice, how: .block))
        XCTAssertNil(try bob.sync.run().stoppedBy)
        XCTAssertEqual(try bob.chat.messages(matchID: withCara).count, 1)
        try cara.chat.send(matchID: withCara, event: try text("Still here"))
        _ = try cara.sync.run()
        XCTAssertEqual(try bob.sync.run().shown, 1, "Cara still gets through")
    }

    // MARK: the route row

    func testAFormatOneRowReadsAsAnOpenRoute() throws {
        let match = randomBytes(16), capability = randomBytes(RelayClient.keySize)
        var row = Data([1])
        for f in [match, capability, Data(), Data(), Data()] {
            row.append(UInt8(f.count))
            row += f
        }
        let r = try RelayRoutes.Route.decode(row)
        XCTAssertEqual(r.matchID, match)
        XCTAssertEqual(r.receiveCapability, capability)
        XCTAssertFalse(r.ending)
    }

    func testARowRoundTripsWithItsMarkAndAnUnknownMarkIsUnreadable() throws {
        let r = RelayRoutes.Route(matchID: randomBytes(16), receiveCapability: randomBytes(RelayClient.keySize),
                                  peerRoutingID: randomBytes(RelayClient.routingIDSize),
                                  sendCapability: randomBytes(RelayClient.keySize), allowedFor: Data(), ending: true)
        var row = r.encoded
        XCTAssertTrue(try RelayRoutes.Route.decode(row).ending)
        row[row.count - 1] = 2
        XCTAssertThrowsError(try RelayRoutes.Route.decode(row))
    }

    func testTheEndingMarkSticksAndTheRouteAcceptsNothing() throws {
        let d = try RelayDevice(clock: clock, transport: relay)
        let match = randomBytes(16)
        try d.routes.issue(matchID: match)
        XCTAssertEqual(try d.routes.byProof().count, 1)
        try d.routes.markEnding(matchID: match)
        try d.routes.setPeer(matchID: match, peerRoutingID: randomBytes(RelayClient.routingIDSize),
                             sendCapability: randomBytes(RelayClient.keySize))
        try d.routes.markAllowed(matchID: match, routingID: randomBytes(RelayClient.routingIDSize))
        let r = try XCTUnwrap(try d.routes.forMatch(match))
        XCTAssertTrue(r.ending, "neither the peer's half nor an allowance clears it")
        XCTAssertFalse(r.canSend, "the late peer half was not taken")
        XCTAssertTrue(try d.routes.byProof().isEmpty, "nothing arrives under an ending route")
        try d.routes.markEnding(matchID: randomBytes(16))
        XCTAssertEqual(try d.routes.all().count, 1, "marking an unknown match creates nothing")
    }
}
