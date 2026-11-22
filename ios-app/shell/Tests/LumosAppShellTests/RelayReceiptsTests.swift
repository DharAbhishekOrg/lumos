/// Lumos - LumosAppShellTests / Prekeys stay openable through kills, and ticks come only from the peer's receipts.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-CRY-005, SRS-RMT-005, SRS-RMT-014, SRS-RMT-016, SRS-MAT-005, SRS-MAT-006, TRS-TST-001
/// ADRs: ADR-001, ADR-002, ADR-008
/// Threading: test-only
/// Notes: The Swift side of the Android PrekeyPublisherTest and
/// RelayReceiptsTest, scenario for scenario, over the fake relay and real
/// stores on real files.

import Foundation
import LumosProtocol
import LumosProtocolGenerated
import XCTest
@testable import LumosAppShell

final class PrekeyPublisherTests: XCTestCase {

    private let clock = TestClock(1_700_000_000_000)
    private lazy var relay = FakeRelay(clock: clock)

    private func assertEveryPublicHasItsPrivateHalf(_ d: RelayDevice, _ ids: [Int64],
                                                    file: StaticString = #filePath, line: UInt = #line) throws {
        for id in ids {
            XCTAssertFalse(try d.store.findByNaturalKey(kind: .prekeySecret,
                                                        naturalKey: PrekeySecret.naturalKey(oneTime: true,
                                                                                            id: UInt32(id))).isEmpty,
                           "one-time prekey \(id) is at the registry but its private half is not on the device",
                           file: file, line: line)
        }
    }

    func testTheFirstPassPublishesASignedBundleAndTheDefaultStockWithEveryPrivateHalfStored() throws {
        let d = try RelayDevice(clock: clock, transport: relay)
        XCTAssertNil(try d.sync.run().stoppedBy)
        let ids = relay.oneTimeIDs(try d.routingID())
        XCTAssertEqual(ids.count, LumosConstants.oneTimePrekeysUploadedDefault)
        try assertEveryPublicHasItsPrivateHalf(d, ids)
        let peer = try RelayDevice(clock: clock, transport: relay)
        _ = try peer.sync.run()
        let b = try XCTUnwrap(try RelayClient.anonymous(relay).as(credential: try peer.credential())
            .claim(routingID: try d.routingID()))
        XCTAssertNoThrow(try X3DH.verifyBundle(b))
        XCTAssertEqual(b.identityEd, d.edPub, "the bundle is signed by the device's identity")
    }

    func testBelowTheThresholdTheStockIsToppedUpWithFreshIds() throws {
        let d = try RelayDevice(clock: clock, transport: relay)
        _ = try d.sync.run()
        let first = Set(relay.oneTimeIDs(try d.routingID()))
        let peer = try RelayDevice(clock: clock, transport: relay)
        _ = try peer.sync.run()
        let claimer = RelayClient.anonymous(relay).as(credential: try peer.credential())
        let take = LumosConstants.oneTimePrekeysUploadedDefault - LumosConstants.prekeyReplenishThresholdDefault + 1
        for _ in 0..<take {
            _ = try claimer.claim(routingID: try d.routingID())
        }
        let before = relay.oneTimeIDs(try d.routingID())
        XCTAssertLessThan(before.count, LumosConstants.prekeyReplenishThresholdDefault)
        _ = try d.sync.run()
        let after = relay.oneTimeIDs(try d.routingID())
        XCTAssertEqual(after.count, LumosConstants.oneTimePrekeysUploadedDefault, "topped back up to the default")
        for id in after[before.count...] {
            XCTAssertFalse(first.contains(id), "id \(id) was minted before (TRS-CRY-005)")
        }
        try assertEveryPublicHasItsPrivateHalf(d, after)
        XCTAssertEqual(try d.prekeys.ensure(client: claimer.as(credential: try d.credential()),
                                            registration: try XCTUnwrap(try d.account.current())), 0,
                       "a full stock needs nothing")
    }

    func testKilledWithTheUploadAnswerOnItsWayNoIDIsEverMintedTwice() throws {
        let d = try RelayDevice(clock: clock, transport: relay)
        relay.fault("PUT /v1/prekeys", .dieAfter)
        XCTAssertThrowsError(try d.sync.run()) { XCTAssertTrue($0 is ProcessDeath) }
        try d.kill()
        XCTAssertNil(try d.sync.run().stoppedBy, "no duplicate-id refusal: the retry minted fresh ids")
        let ids = relay.oneTimeIDs(try d.routingID())
        XCTAssertEqual(ids.count, 2 * LumosConstants.oneTimePrekeysUploadedDefault,
                       "both batches are at the registry, the lost-answer one included")
        XCTAssertEqual(Set(ids).count, ids.count, "every id is distinct")
        try assertEveryPublicHasItsPrivateHalf(d, ids)
    }

    func testOfflineNothingIsMinted() throws {
        relay.offline = true
        let d = try RelayDevice(clock: clock, transport: relay)
        XCTAssertEqual(try d.sync.run().stoppedBy?.kind, .offline)
        XCTAssertEqual(try d.store.count(kind: .prekeySecret), 0, "no keys are made for a registry it cannot reach")
        XCTAssertNil(try d.account.current())
    }
}

final class RelayReceiptsTests: XCTestCase {

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

    private func firstState(_ d: RelayDevice, _ m: Data) throws -> DeliveryMachine.State? {
        try d.chat.messages(matchID: m).first?.state
    }

    private func text(_ s: String) throws -> RemoteText.TextEvent {
        try RemoteText.TextEvent(kind: .text, target: nil, body: Data(s.utf8))
    }

    private func receiptsQueued(_ d: RelayDevice, _ kind: MessageClass) throws -> Int {
        try d.chat.pendingOutbox().filter { $0.messageClass == kind }.count
    }

    func testOneTickWhenTheRelayTakesItTwoOnlyFromTheDeliveredReceiptBlueOnlyFromTheRead() throws {
        let alice = try device(), bob = try device()
        let m = try match(alice, bob)
        try alice.chat.send(matchID: m, event: try text("See you at eight?"))
        _ = try alice.sync.run()
        XCTAssertEqual(try firstState(alice, m), .sent)
        _ = try bob.sync.run()
        XCTAssertEqual(try receiptsQueued(bob, .deliveryReceipt), 1,
                       "showing the message queued its delivered receipt in the same transaction")
        _ = try alice.sync.run()
        XCTAssertEqual(try firstState(alice, m), .sent, "no receipt has travelled yet")
        _ = try bob.sync.run()
        _ = try alice.sync.run()
        XCTAssertEqual(try firstState(alice, m), .delivered, "two grey ticks, from Bob's receipt")
        XCTAssertEqual(try bob.chat.markRead(matchID: m, readReceiptsEnabled: true), 1)
        _ = try bob.sync.run()
        _ = try alice.sync.run()
        XCTAssertEqual(try firstState(alice, m), .read, "two blue ticks, from Bob's read receipt")
        XCTAssertEqual(try bob.chat.markRead(matchID: m, readReceiptsEnabled: true), 0, "a message is read once")
    }

    func testWithReadReceiptsOffTheReadStaysOnTheDeviceAndDeliveredStillGoes() throws {
        let alice = try device(), bob = try device()
        let m = try match(alice, bob)
        try alice.chat.send(matchID: m, event: try text("Hello"))
        _ = try alice.sync.run()
        _ = try bob.sync.run()
        XCTAssertEqual(try bob.chat.markRead(matchID: m, readReceiptsEnabled: false), 1)
        XCTAssertEqual(try firstState(bob, m), .read, "read on Bob's side")
        XCTAssertEqual(try receiptsQueued(bob, .readReceipt), 0, "no read receipt leaves (SRS-RMT-014)")
        XCTAssertEqual(try receiptsQueued(bob, .deliveryReceipt), 1, "the delivered receipt still goes")
        _ = try bob.sync.run()
        _ = try alice.sync.run()
        XCTAssertEqual(try firstState(alice, m), .delivered, "Alice sees delivered, never read")
    }

    func testKilledBetweenShowingAndAcknowledgingOneDeliveredReceiptIsSent() throws {
        let alice = try device(), bob = try device()
        let m = try match(alice, bob)
        try alice.chat.send(matchID: m, event: try text("Are you home?"))
        _ = try alice.sync.run()
        relay.fault("POST /v1/envelopes/ack", .dieBefore)
        XCTAssertThrowsError(try bob.sync.run()) { XCTAssertTrue($0 is ProcessDeath) }
        try bob.kill()
        XCTAssertEqual(try receiptsQueued(bob, .deliveryReceipt), 1, "the receipt survived the kill with the message")
        _ = try bob.sync.run()
        XCTAssertEqual(try receiptsQueued(bob, .deliveryReceipt), 0, "sent, and the refetched copy queued none")
        _ = try alice.sync.run()
        XCTAssertEqual(try firstState(alice, m), .delivered)
    }

    func testARouteIsOfferedAndTakenOnlyAfterAMatchAndOnlyWellFormed() throws {
        let alice = try device()
        let unregistered = try RelayDevice(clock: clock, transport: relay)
        let m = randomBytes(16)
        let good = try XCTUnwrap(try alice.exchange.offer(matchID: m, state: .matched))
        // Every state but MATCHED (the Swift enum is not CaseIterable, so they are listed).
        let states: [MatchMachine.State] = [.none, .requestedOut, .requestedIn, .bothRequested, .declinedIn,
                                            .ignoredIn, .unmatched, .blocked]
        for s in states {
            XCTAssertNil(try alice.exchange.offer(matchID: m, state: s), "no route before a match: \(s)")
            XCTAssertEqual(try alice.exchange.take(matchID: m, state: s, payload: good), .notMatched,
                           "none taken before a match: \(s)")
        }
        XCTAssertNil(try unregistered.exchange.offer(matchID: m, state: .matched), "nothing to offer without a routing id")
        XCTAssertEqual(try alice.exchange.take(matchID: m, state: .matched, payload: randomBytes(49)), .malformed,
                       "random bytes are not a route")
        XCTAssertEqual(try alice.exchange.take(matchID: m, state: .matched, payload: Data()), .malformed)
    }

    func testAfterUnmatchThePeersSendsAreTakenByTheRelayButNeverDelivered() throws {
        let alice = try device(), bob = try device()
        let m = try match(alice, bob)
        relay.offline = true
        XCTAssertEqual(try bob.sync.forget(matchID: m)?.kind, .offline)
        XCTAssertNotNil(try bob.routes.forMatch(m), "kept, so the revoke can be retried")
        relay.offline = false
        XCTAssertNil(try bob.sync.forget(matchID: m))
        XCTAssertNil(try bob.routes.forMatch(m))
        try alice.chat.send(matchID: m, event: try text("Hello?"))
        XCTAssertEqual(try alice.sync.run().sent, 1, "accepted: Alice learns nothing (SRS-MAT-003)")
        XCTAssertEqual(relay.waiting(try bob.routingID()), 0, "and nothing reaches Bob (SRS-RMT-016)")
        XCTAssertEqual(try firstState(alice, m), .sent, "one tick, forever")
    }

    func testManyUnreadMessagesAreAcknowledgedInBatchesOfTheRegisterDefault() throws {
        let alice = try device(), bob = try device()
        let m = try match(alice, bob)
        let n = LumosConstants.receiptIDSPerEnvelopeDefault + 1
        for i in 0..<n {
            try alice.chat.send(matchID: m, event: try text("msg \(i)"))
        }
        _ = try alice.sync.run()
        while try bob.sync.run().moreWaiting {}
        XCTAssertEqual(try bob.chat.messages(matchID: m).count, n)
        try bob.chat.markRead(matchID: m, readReceiptsEnabled: true)
        XCTAssertEqual(try receiptsQueued(bob, .readReceipt), 2, "33 ids need two receipts")
        for _ in 0..<3 {
            _ = try bob.sync.run()
            _ = try alice.sync.run()
        }
        for msg in try alice.chat.messages(matchID: m) {
            XCTAssertEqual(msg.state, .read)
        }
    }
}
