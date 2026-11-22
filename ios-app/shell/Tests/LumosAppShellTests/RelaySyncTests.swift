/// Lumos - LumosAppShellTests / Messages cross the relay, survive kills at every step, and are shown once.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-RMT-001, SRS-RMT-003, SRS-RMT-004, SRS-RMT-009, SRS-RMT-015, SRS-RMT-017, TRS-BCK-004, TRS-TST-006
/// ADRs: ADR-001, ADR-003
/// Threading: test-only
/// Notes: The Swift side of the Android RelaySyncTest, scenario for scenario.
/// Every device is a real store on a real file over the fake relay; "kill"
/// drops every object and reboots from the file.

import Foundation
import LumosProtocol
import LumosProtocolGenerated
import XCTest
@testable import LumosAppShell

final class RelaySyncTests: XCTestCase {

    private let clock = TestClock(1_700_000_000_000)
    private lazy var relay = FakeRelay(clock: clock)

    private func text(_ s: String) throws -> RemoteText.TextEvent {
        try RemoteText.TextEvent(kind: .text, target: nil, body: Data(s.utf8))
    }

    private func bodies(_ d: RelayDevice, _ match: Data) throws -> [String] {
        try d.chat.messages(matchID: match).map { String(decoding: $0.body, as: UTF8.self) }
    }

    private func device() throws -> RelayDevice {
        let d = try RelayDevice(clock: clock, transport: relay)
        XCTAssertNil(try d.sync.run().stoppedBy, "a first pass registers and publishes")
        return d
    }

    /// What the match and the RELAY_ROUTE exchange (CR-005) leave behind: both
    /// sides pinned, both routes swapped, both proofs allowed, and a session
    /// the initiator started from the responder's published bundle.
    private func match(_ initiator: RelayDevice, _ responder: RelayDevice) throws -> Data {
        let match = randomBytes(16)
        try initiator.chat.acceptConversation(matchID: match, peerIdentityEd: responder.edPub)
        try responder.chat.acceptConversation(matchID: match, peerIdentityEd: initiator.edPub)
        XCTAssertEqual(try responder.exchange.take(matchID: match, state: .matched,
                                                   payload: try XCTUnwrap(try initiator.exchange.offer(
                                                    matchID: match, state: .matched))), .stored)
        XCTAssertEqual(try initiator.exchange.take(matchID: match, state: .matched,
                                                   payload: try XCTUnwrap(try responder.exchange.offer(
                                                    matchID: match, state: .matched))), .stored)
        XCTAssertNil(try initiator.sync.run().stoppedBy)
        XCTAssertNil(try responder.sync.run().stoppedBy)
        XCTAssertTrue(try initiator.sessions.start(matchID: match, peerIdentityEd: responder.edPub))
        return match
    }

    private func assertDies(_ d: RelayDevice, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertThrowsError(try d.sync.run(), file: file, line: line) {
            XCTAssertTrue($0 is ProcessDeath, "the kill escapes the pass", file: file, line: line)
        }
    }

    func testAMessageCrossesTheRelayShowsOnceAndIsOneTickNotDelivered() throws {
        let alice = try device(), bob = try device()
        let stockBefore = relay.oneTimeLeft(try bob.routingID())
        let match = try match(alice, bob)
        XCTAssertEqual(relay.oneTimeLeft(try bob.routingID()), stockBefore - 1, "the claim took one one-time prekey")
        try alice.chat.send(matchID: match, event: try text("Hi! Lovely meeting you tonight."))
        XCTAssertEqual(try alice.sync.run().sent, 1)
        XCTAssertTrue(try alice.chat.pendingOutbox().isEmpty, "the relay took it, so the outbox is done")
        XCTAssertEqual(try alice.chat.messages(matchID: match).first?.state, .sent,
                       "the relay taking it is one tick, never delivered (owner ruling 2026-11-21)")
        let bobSecrets = try bob.store.count(kind: .prekeySecret)
        let got = try bob.sync.run()
        XCTAssertEqual(got.shown, 1)
        XCTAssertEqual(got.acknowledged, 1)
        XCTAssertEqual(relay.waiting(try bob.routingID()), 0, "acknowledged means deleted from the relay (SRS-RMT-015)")
        XCTAssertEqual(try bob.store.count(kind: .prekeySecret), bobSecrets - 1, "the used one-time prekey is gone")
        XCTAssertEqual(try bodies(bob, match), ["Hi! Lovely meeting you tonight."])
        try bob.chat.send(matchID: match, event: try text("Saturday coffee?"))
        _ = try bob.sync.run()
        XCTAssertEqual(try alice.sync.run().shown, 1, "the reply comes back the same way")
        XCTAssertEqual(try bodies(alice, match), ["Hi! Lovely meeting you tonight.", "Saturday coffee?"])
    }

    func testOfflineEverythingWaitsInOrderAndGoesWhenThePathReturns() throws {
        let alice = try device(), bob = try device()
        let match = try match(alice, bob)
        relay.offline = true
        for s in ["one", "two", "three"] {
            try alice.chat.send(matchID: match, event: try text(s))
        }
        let r = try alice.sync.run()
        XCTAssertEqual(r.stoppedBy?.kind, .offline)
        XCTAssertTrue(r.retryLater)
        XCTAssertEqual(try alice.chat.pendingOutbox().count, 3, "nothing is lost offline (SRS-RMT-003)")
        relay.offline = false
        XCTAssertEqual(try alice.sync.run().sent, 3, "it retries by itself when the path returns")
        _ = try bob.sync.run()
        XCTAssertEqual(try bodies(bob, match), ["one", "two", "three"])
    }

    func testKilledJustAfterTheRelayTookASendItGoesAgainAndIsStoredOnce() throws {
        let alice = try device(), bob = try device()
        let match = try match(alice, bob)
        try alice.chat.send(matchID: match, event: try text("Are you still at the venue?"))
        let storedBefore = relay.storedEver
        relay.fault("POST /v1/envelopes", .dieAfter)
        assertDies(alice)
        try alice.kill()
        XCTAssertEqual(try alice.chat.pendingOutbox().count, 1, "the outbox item survived the kill")
        XCTAssertEqual(try alice.sync.run().sent, 1, "the next launch sends the same id again")
        XCTAssertEqual(relay.storedEver, storedBefore + 1, "the relay stored it once (TRS-BCK-004)")
        XCTAssertEqual(try bob.sync.run().shown, 1)
        XCTAssertEqual(try bob.chat.messages(matchID: match).count, 1, "shown once (SRS-RMT-004)")
    }

    func testKilledJustBeforeTheRelayTookASendNothingIsLost() throws {
        let alice = try device(), bob = try device()
        let match = try match(alice, bob)
        try alice.chat.send(matchID: match, event: try text("See you there"))
        relay.fault("POST /v1/envelopes", .dieBefore)
        assertDies(alice)
        XCTAssertEqual(relay.waiting(try bob.routingID()), 0)
        try alice.kill()
        XCTAssertEqual(try alice.sync.run().sent, 1)
        XCTAssertEqual(try bob.sync.run().shown, 1)
    }

    func testKilledBetweenOpeningAndAcknowledgingItIsShownOnceAndNoKeyIsReused() throws {
        let alice = try device(), bob = try device()
        let match = try match(alice, bob)
        try alice.chat.send(matchID: match, event: try text("first"))
        _ = try alice.sync.run()
        relay.fault("POST /v1/envelopes/ack", .dieBefore)
        assertDies(bob)
        try bob.kill()
        XCTAssertEqual(relay.waiting(try bob.routingID()), 1, "the ack never reached the relay")
        XCTAssertEqual(try bodies(bob, match), ["first"], "the open had committed before the kill")
        let again = try bob.sync.run()
        XCTAssertEqual(again.shown, 0, "the refetched copy is a duplicate")
        XCTAssertEqual(again.acknowledged, 1, "and is acknowledged this time")
        XCTAssertEqual(try bodies(bob, match), ["first"], "shown once (SRS-RMT-004)")
        try alice.chat.send(matchID: match, event: try text("second"))
        _ = try alice.sync.run()
        XCTAssertEqual(try bob.sync.run().shown, 1, "the restored session opens the next message (SRS-RMT-017)")
        XCTAssertEqual(try bodies(bob, match), ["first", "second"])
    }

    func testAFullMailboxHoldsThatConversationInOrderWhileAnotherFlows() throws {
        let alice = try device(), bob = try device(), carol = try device()
        let withBob = try match(alice, bob)
        let withCarol = try match(alice, carol)
        relay.quota(try bob.routingID(), envelopes: 1)
        try alice.chat.send(matchID: withBob, event: try text("b1"))
        try alice.chat.send(matchID: withBob, event: try text("b2"))
        try alice.chat.send(matchID: withCarol, event: try text("c1"))
        let r = try alice.sync.run()
        XCTAssertNil(r.stoppedBy, "a full mailbox is one peer's problem, not the pass's")
        XCTAssertEqual(r.sent, 2, "b1 and c1 went; b2 waits behind the full mailbox")
        XCTAssertEqual(try alice.chat.pendingOutbox().count, 1)
        XCTAssertEqual(try carol.sync.run().shown, 1)
        _ = try bob.sync.run()
        XCTAssertEqual(try alice.sync.run().sent, 1, "b2 goes once bob made room")
        _ = try bob.sync.run()
        XCTAssertEqual(try bodies(bob, withBob), ["b1", "b2"], "and in order")
    }

    func testRateLimitedThePassStopsAndKeepsEverything() throws {
        let alice = try device(), bob = try device()
        let match = try match(alice, bob)
        try alice.chat.send(matchID: match, event: try text("hello"))
        relay.rateLimitNextSend(retryAfter: 30)
        let r = try alice.sync.run()
        XCTAssertEqual(r.stoppedBy?.kind, .busy)
        XCTAssertEqual(r.stoppedBy?.retryAfterSeconds, 30, "the server's Retry-After reaches the scheduler")
        XCTAssertEqual(try alice.chat.pendingOutbox().count, 1)
        XCTAssertEqual(try alice.sync.run().sent, 1)
    }

    func testARefusedCredentialRegistersAfreshAndRebuildsTheMailbox() throws {
        let alice = try device(), bob = try device()
        let match = try match(alice, bob)
        let oldRoute = try bob.routingID()
        relay.revokeDevice(credential: try bob.credential())
        XCTAssertEqual(try bob.sync.run().stoppedBy?.kind, .auth)
        XCTAssertNil(try bob.account.current(), "the dead credential is dropped")
        XCTAssertNil(try bob.sync.run().stoppedBy, "the next pass registers again")
        let newRoute = try bob.routingID()
        XCTAssertNotEqual(oldRoute, newRoute, "a new registration is a new mailbox")
        XCTAssertEqual(relay.oneTimeLeft(newRoute), LumosConstants.oneTimePrekeysUploadedDefault,
                       "prekeys were published again under it")
        XCTAssertEqual(try bob.routes.forMatch(match)?.allowedFor, newRoute,
                       "and every conversation's proof was allowed again")
    }

    func testBadEnvelopesAreAcknowledgedNeverShownAndTheSessionCarriesOn() throws {
        let alice = try device(), bob = try device()
        let match = try match(alice, bob)
        let proof = try XCTUnwrap(try bob.routes.forMatch(match)).proof
        let text = MessageClass.textMessage.rawValue
        relay.inject(recipient: try bob.routingID(), messageID: randomBytes(16), senderProof: randomBytes(32),
                     cls: text, payload: randomBytes(80))
        relay.inject(recipient: try bob.routingID(), messageID: randomBytes(16), senderProof: proof, cls: text,
                     payload: randomBytes(80))
        relay.inject(recipient: try bob.routingID(), messageID: randomBytes(16), senderProof: proof,
                     cls: MessageClass.deliveryReceipt.rawValue, payload: randomBytes(80))
        let r = try bob.sync.run()
        XCTAssertEqual(r.shown, 0, "unknown sender, forged envelope and unexpected class show nothing (SRS-RMT-009)")
        XCTAssertEqual(r.acknowledged, 3, "and are acknowledged, so they are not fetched forever")
        XCTAssertEqual(relay.waiting(try bob.routingID()), 0)
        try alice.chat.send(matchID: match, event: try self.text("still here"))
        _ = try alice.sync.run()
        relay.serveExpired(true)
        clock.advance((FakeRelay.ttlSeconds + 1) * 1000)
        let late = try bob.sync.run()
        XCTAssertEqual(late.shown, 0, "an envelope past its expiry is not shown, even if a relay serves it")
        XCTAssertEqual(late.acknowledged, 1)
        relay.serveExpired(false)
        try alice.chat.send(matchID: match, event: try self.text("and now?"))
        _ = try alice.sync.run()
        XCTAssertEqual(try bob.sync.run().shown, 1, "the session survived all of it")
    }

    func testASendRefusedForGoodShowsFailedAndTheNextOneStillGoes() throws {
        let alice = try device(), bob = try device()
        let match = try match(alice, bob)
        let id = try alice.chat.send(matchID: match, event: try text("this one is refused"))
        // Another envelope already holds this id at the relay: the replay rule refuses ours for good.
        relay.inject(recipient: try bob.routingID(), messageID: id,
                     senderProof: try XCTUnwrap(try bob.routes.forMatch(match)).proof,
                     cls: MessageClass.textMessage.rawValue, payload: randomBytes(40))
        let r = try alice.sync.run()
        XCTAssertEqual(r.failed, 1)
        XCTAssertEqual(try alice.chat.messages(matchID: match).first?.state, .failed, "the red mark")
        XCTAssertTrue(try alice.chat.pendingOutbox().isEmpty)
        try alice.chat.send(matchID: match, event: try text("this one goes"))
        XCTAssertEqual(try alice.sync.run().sent, 1)
        _ = try bob.sync.run()
        XCTAssertTrue(try bodies(bob, match).contains("this one goes"), "the refused message is only a skipped key")
    }

    func testASessionStartsOnlyOnceTheRouteAndTheBundleAreThereAndOnlyForThePinnedPeer() throws {
        let alice = try device(), bob = try device(), carol = try device()
        let match = randomBytes(16)
        try alice.chat.acceptConversation(matchID: match, peerIdentityEd: bob.edPub)
        try alice.routes.issue(matchID: match)
        XCTAssertFalse(try alice.sessions.start(matchID: match, peerIdentityEd: bob.edPub), "no route to bob yet")
        try alice.routes.setPeer(matchID: match, peerRoutingID: randomBytes(16), sendCapability: randomBytes(32))
        XCTAssertFalse(try alice.sessions.start(matchID: match, peerIdentityEd: bob.edPub),
                       "an unknown routing id has no bundle")
        try alice.routes.setPeer(matchID: match, peerRoutingID: try bob.routingID(), sendCapability: randomBytes(32))
        XCTAssertThrowsError(try alice.sessions.start(matchID: match, peerIdentityEd: carol.edPub),
                             "a bundle that is not the pinned peer's is refused") {
            XCTAssertTrue($0 is LumosCrypto.WireError)
        }
        XCTAssertFalse(try alice.chat.hasSession(matchID: match))
        XCTAssertTrue(try alice.sessions.start(matchID: match, peerIdentityEd: bob.edPub))
        XCTAssertTrue(try alice.sessions.start(matchID: match, peerIdentityEd: bob.edPub), "starting again is a no-op")
        XCTAssertTrue(try alice.chat.hasSession(matchID: match))
    }
}
