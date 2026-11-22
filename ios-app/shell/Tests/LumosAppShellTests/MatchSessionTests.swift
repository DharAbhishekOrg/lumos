/// Lumos - LumosAppShellTests / Mutual consent over the wire, the RELAY_ROUTE swap, and the assembled runtime.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-MAT-002, SRS-MAT-004, SRS-MAT-006, SRS-MAT-007, SRS-MAT-009, SRS-MAT-014, SRS-RMT-018, TRS-TST-001
/// ADRs: ADR-002, ADR-003, ADR-010
/// Threading: test-only
/// Notes: The Swift side of the Android MutualConsentFlowTest,
/// NearbyRouteSwapTest, ChatRuntimeTest and RelayScheduleTest. Two people's
/// sessions talk through in-memory outboxes standing in for the BLE frame
/// channel; the route swap then lets them chat over the fake relay alone.

import Foundation
import LumosProtocol
import LumosProtocolGenerated
import XCTest
@testable import LumosAppShell

private let now: Int64 = 1_755_561_600_000

private func peerID(_ seed: UInt8) -> Data {
    Data(repeating: seed, count: LumosConstants.peerIDLenDefault)
}

private func forged(_ mc: MessageClass, _ payload: Data = Data("hi".utf8)) throws -> Data {
    try FrameCodec.encode(header: FrameHeader(type: mc.rawValue, sessionID: Data(count: 16),
                                              messageID: Data(count: 16), sequence: 99), payload: payload)
}

/// Collects one side's outbound frames.
private final class Outbox {
    var frames: [Data] = []
    func send(_ f: Data) { frames.append(f) }
}

final class MutualConsentFlowTests: XCTestCase {

    private let ashaPreview = Data("lumos-profile/1\nalias=Asha\n".utf8)
    private let raviPreview = Data("lumos-profile/1\nalias=Ravi\n".utf8)

    /// One person: their store, ledger, session and outbox.
    private final class Person {
        let store: LocalStore
        let ledger: InterestLedger
        let out = Outbox()
        var session: InterestSession!

        init(_ seed: UInt8) throws {
            store = try TestStores.openInMemory()
            ledger = InterestLedger(store: store, budget: PersistedBudget(store: store), ownPeerID: peerID(seed))
        }

        func talk(to peer: UInt8, preview: Data) {
            let out = self.out
            session = InterestSession(ledger: ledger, peerKey: Hex.encode(peerID(peer)), requestStagePreview: preview,
                                      outbox: { out.send($0) })
        }
    }

    @discardableResult
    private func deliver(_ from: Person, _ to: Person) throws -> Int {
        let frames = from.out.frames
        from.out.frames.removeAll()
        for f in frames {
            let inbound = try to.session.onFrame(f, nowMillis: now)
            XCTAssertTrue(inbound.delivered, "a legitimate frame must land: \(inbound)")
        }
        return frames.count
    }

    func testADeclineTransmitsNothingAndNoConversationCrossesBeforeAMatch() throws {
        let asha = try Person(0x11), ravi = try Person(0x22)
        asha.talk(to: 0x22, preview: ashaPreview)
        ravi.talk(to: 0x11, preview: raviPreview)
        try asha.session.local(.localRequest, nowMillis: now)
        XCTAssertEqual(try deliver(asha, ravi), 1, "one request frame on the air")
        XCTAssertEqual(ravi.session.lastReceivedPreview, ashaPreview,
                       "the request carries exactly the requester-stage preview (SRS-MAT-002)")
        // Pre-match: nothing conversational leaves either side...
        XCTAssertFalse(try asha.session.sendConversation(.textMessage, Data([0])))
        XCTAssertFalse(try ravi.session.sendConversation(.objectMeta, Data([0])))
        XCTAssertFalse(try ravi.session.sendConversation(.profileFull, Data([0])))
        XCTAssertFalse(try ravi.session.sendConversation(.relayRoute, Data([0])))
        XCTAssertTrue(asha.out.frames.isEmpty && ravi.out.frames.isEmpty)
        // ...and nothing conversational lands, even forged (SRS-MAT-006).
        for mc: MessageClass in [.textMessage, .profileFull, .objectChunk, .voiceMeta, .relayRoute] {
            let inbound = try ravi.session.onFrame(try forged(mc), nowMillis: now)
            XCTAssertFalse(inbound.delivered, "\(mc) must not land before a match")
            XCTAssertEqual(inbound.refusalCode, LumosErrors.errMatchNotMatched)
        }
        // Ravi declines: the outbox stays EMPTY - there is no frame to send.
        try ravi.session.local(.localDecline, nowMillis: now + 1)
        XCTAssertTrue(ravi.out.frames.isEmpty, "a decline transmits nothing (SRS-MAT-004)")
        XCTAssertEqual(try asha.ledger.stateOf(peerKey: Hex.encode(peerID(0x22))), .requestedOut,
                       "Asha's view after a decline is identical to no answer yet")
    }

    func testMutualAcceptanceOpensTheDoorWithOneIdentifierAndOneToken() throws {
        let asha = try Person(0x11), ravi = try Person(0x22)
        asha.talk(to: 0x22, preview: ashaPreview)
        ravi.talk(to: 0x11, preview: raviPreview)
        try asha.session.local(.localRequest, nowMillis: now)
        try deliver(asha, ravi)
        let ravis = try ravi.session.local(.localAccept, nowMillis: now + 1)
        XCTAssertEqual(ravi.out.frames.count, 1, "an acceptance is one frame")
        let acceptFrame = ravi.out.frames[0]
        try deliver(ravi, asha)
        XCTAssertEqual(try asha.ledger.stateOf(peerKey: Hex.encode(peerID(0x22))), .matched)
        XCTAssertEqual(try ravi.ledger.stateOf(peerKey: Hex.encode(peerID(0x11))), .matched)
        let matchHex = try XCTUnwrap(ravis.matchIDHex)
        XCTAssertTrue(try XCTUnwrap(try asha.ledger.matchRecordOf(peerKey: Hex.encode(peerID(0x22))))
            .contains(matchHex), "both stores hold the same match identifier (SRS-MAT-007)")
        XCTAssertFalse(try MatchToken.derive(matchID: try XCTUnwrap(Hex.decode(matchHex))).colorName.isEmpty,
                       "both screens can show the same token (SRS-MAT-008)")
        // The door is open both ways now.
        XCTAssertTrue(try asha.session.sendConversation(.textMessage, Data([0])))
        XCTAssertTrue(try ravi.session.onFrame(asha.out.frames.removeFirst(), nowMillis: now + 2).delivered)
        // A replayed acceptance absorbs: still one match, still matched.
        XCTAssertTrue(try asha.session.onFrame(acceptFrame, nowMillis: now + 3).delivered)
        XCTAssertEqual(try asha.store.count(kind: .matchRecord), 1, "SRS-MAT-014")
        // An unmatch is visible, and it closes the door again.
        try asha.session.local(.localUnmatch, nowMillis: now + 4)
        try deliver(asha, ravi)
        XCTAssertEqual(try ravi.ledger.stateOf(peerKey: Hex.encode(peerID(0x11))), .unmatched)
        XCTAssertFalse(try ravi.session.sendConversation(.textMessage, Data([0])),
                       "after an unmatch the conversation path is shut (SRS-MAT-009)")
        XCTAssertFalse(try ravi.session.onFrame(try forged(.textMessage), nowMillis: now + 5).delivered)
    }

    func testTheAcceptAcceptRaceOverTheWireYieldsOneMatch() throws {
        let asha = try Person(0x11), ravi = try Person(0x22)
        asha.talk(to: 0x22, preview: ashaPreview)
        ravi.talk(to: 0x11, preview: raviPreview)
        try asha.session.local(.localRequest, nowMillis: now)
        try ravi.session.local(.localRequest, nowMillis: now)
        try deliver(asha, ravi)
        try deliver(ravi, asha)
        let a = try asha.session.local(.localAccept, nowMillis: now + 1)
        let r = try ravi.session.local(.localAccept, nowMillis: now + 1)
        try deliver(asha, ravi)
        try deliver(ravi, asha)
        XCTAssertEqual(a.matchIDHex, r.matchIDHex, "both crossed acceptances, one identifier")
        XCTAssertEqual(try asha.ledger.stateOf(peerKey: Hex.encode(peerID(0x22))), .matched)
        XCTAssertEqual(try ravi.ledger.stateOf(peerKey: Hex.encode(peerID(0x11))), .matched)
    }

    func testAGarbledFrameIsRefusedWithItsCodeAndChangesNothing() throws {
        let ravi = try Person(0x22)
        ravi.talk(to: 0x11, preview: raviPreview)
        let inbound = try ravi.session.onFrame(try forged(.interestResponse, Data([1, 9])), nowMillis: now)
        XCTAssertFalse(inbound.delivered)
        XCTAssertEqual(inbound.refusalCode, LumosErrors.errMatchBadVerdict)
        XCTAssertEqual(inbound.state, MatchMachine.State.none, "a refused frame moves nothing")
        XCTAssertFalse(try ravi.session.onFrame(Data([1, 2, 3]), nowMillis: now).delivered, "not a frame at all")
    }
}

final class NearbyRouteSwapTests: XCTestCase {

    private let clock = TestClock(1_700_000_000_000)
    private lazy var relay = FakeRelay(clock: clock)

    /// One person: a relay device, an interest session with the other, and what it sent.
    private final class Person {
        let device: RelayDevice
        let out = Outbox()
        let session: InterestSession
        let swap: NearbyRouteSwap

        init(_ own: UInt8, _ peer: UInt8, matchID: Data, clock: TestClock, relay: FakeRelay) throws {
            device = try RelayDevice(clock: clock, transport: relay)
            XCTAssertNil(try device.sync.run().stoppedBy)
            let ledger = InterestLedger(store: device.store, budget: PersistedBudget(store: device.store),
                                        ownPeerID: peerID(own))
            let out = self.out
            session = InterestSession(ledger: ledger, peerKey: Hex.encode(peerID(peer)),
                                      requestStagePreview: Data("preview".utf8), outbox: { out.send($0) })
            swap = NearbyRouteSwap(session: session, exchange: device.exchange, matchID: matchID)
        }
    }

    private func deliver(_ from: Person, _ to: Person) throws {
        let frames = from.out.frames
        from.out.frames.removeAll()
        for f in frames {
            XCTAssertTrue(try to.session.onFrame(f, nowMillis: now).delivered)
        }
    }

    func testTheRoutesCrossOnceAfterTheMatchAndTheyChatOverTheRelay() throws {
        let matchID = peerID(0x5A)
        let asha = try Person(0x11, 0x22, matchID: matchID, clock: clock, relay: relay)
        let ravi = try Person(0x22, 0x11, matchID: matchID, clock: clock, relay: relay)
        try asha.session.local(.localRequest, nowMillis: now)
        XCTAssertFalse(try asha.swap.offerIfMatched(), "no route while only requested")
        try deliver(asha, ravi)
        XCTAssertFalse(try ravi.swap.offerIfMatched(), "no route before accepting")
        try ravi.session.local(.localAccept, nowMillis: now)
        XCTAssertTrue(try ravi.swap.offerIfMatched(), "Ravi offers his route the moment he accepts")
        XCTAssertFalse(try ravi.swap.offerIfMatched(), "and not again while nothing changed")
        try deliver(ravi, asha)
        XCTAssertEqual(try asha.session.state(), .matched)
        XCTAssertEqual(asha.swap.lastTaken, .stored)
        XCTAssertTrue(try asha.swap.offerIfMatched())
        try deliver(asha, ravi)
        XCTAssertEqual(ravi.swap.lastTaken, .stored)
        // The radio is gone; everything from here is the relay alone (SRS-RMT-018).
        try asha.device.chat.acceptConversation(matchID: matchID, peerIdentityEd: ravi.device.edPub)
        try ravi.device.chat.acceptConversation(matchID: matchID, peerIdentityEd: asha.device.edPub)
        _ = try asha.device.sync.run()
        _ = try ravi.device.sync.run()
        XCTAssertTrue(try asha.device.sessions.start(matchID: matchID, peerIdentityEd: ravi.device.edPub))
        try asha.device.chat.send(matchID: matchID, event: try RemoteText.TextEvent(kind: .text, target: nil,
                                                                                    body: Data("Made it home".utf8)))
        XCTAssertEqual(try asha.device.sync.run().sent, 1)
        XCTAssertEqual(try ravi.device.sync.run().shown, 1)
    }

    func testAForgedRouteBeforeAMatchIsRefusedByTheGateAndNeverStored() throws {
        let matchID = peerID(0x5B)
        let asha = try Person(0x11, 0x22, matchID: matchID, clock: clock, relay: relay)
        let mallory = try Person(0x22, 0x11, matchID: matchID, clock: clock, relay: relay)
        let route = try XCTUnwrap(try mallory.device.exchange.offer(matchID: matchID, state: .matched))
        let inbound = try asha.session.onFrame(try forged(.relayRoute, route), nowMillis: now)
        XCTAssertFalse(inbound.delivered, "a conversation frame before a match is refused (SRS-MAT-006)")
        XCTAssertNil(asha.swap.lastTaken, "and never reaches the route store")
        XCTAssertNil(try asha.device.routes.forMatch(matchID))
    }
}

final class ChatRuntimeTests: XCTestCase {

    func testOnboardingsIdentitiesChatThroughTheAssembledRuntime() throws {
        let clock = TestClock(1_700_000_000_000)
        let relay = FakeRelay(clock: clock)
        func install() throws -> (IdentityKeyRecord, ChatRuntime) {
            let kek = SoftwareKekStore()
            let record = try IdentityKeyGenerator.generate(kek: kek)
            let identity = try ChatIdentity.unlock(record: record, kek: kek)
            let store = try TestStores.openInMemory(now: { clock.now() })
            return (record, ChatRuntime.assemble(store: store, identity: identity, transport: relay,
                                                 clock: { clock.now() }))
        }
        let (ashaRecord, asha) = try install()
        let (raviRecord, ravi) = try install()
        XCTAssertNil(try asha.sync.run().stoppedBy)
        XCTAssertNil(try ravi.sync.run().stoppedBy)
        let match = randomBytes(16)
        try asha.chat.acceptConversation(matchID: match, peerIdentityEd: raviRecord.ed25519Public)
        try ravi.chat.acceptConversation(matchID: match, peerIdentityEd: ashaRecord.ed25519Public)
        _ = try ravi.exchange.take(matchID: match, state: .matched,
                                   payload: try XCTUnwrap(try asha.exchange.offer(matchID: match, state: .matched)))
        _ = try asha.exchange.take(matchID: match, state: .matched,
                                   payload: try XCTUnwrap(try ravi.exchange.offer(matchID: match, state: .matched)))
        _ = try asha.sync.run()
        _ = try ravi.sync.run()
        XCTAssertNotNil(try asha.send(matchID: match, peerIdentityEd: raviRecord.ed25519Public,
                                      event: try RemoteText.TextEvent(kind: .text, target: nil, body: Data("hi".utf8))),
                        "the session starts on demand from Ravi's published bundle")
        XCTAssertEqual(try asha.sync.run().sent, 1)
        XCTAssertEqual(try ravi.sync.run().shown, 1)
        _ = try ravi.sync.run()
        _ = try asha.sync.run()
        XCTAssertEqual(try asha.chat.messages(matchID: match).first?.state, .delivered)
    }

    func testASendWithNoRouteYetQueuesNothing() throws {
        let clock = TestClock(1_700_000_000_000)
        let kek = SoftwareKekStore()
        let record = try IdentityKeyGenerator.generate(kek: kek)
        let runtime = ChatRuntime.assemble(store: try TestStores.openInMemory(),
                                           identity: try ChatIdentity.unlock(record: record, kek: kek),
                                           transport: FakeRelay(clock: clock), clock: { clock.now() })
        XCTAssertNil(try runtime.send(matchID: randomBytes(16), peerIdentityEd: randomBytes(32),
                                      event: try RemoteText.TextEvent(kind: .text, target: nil, body: Data("x".utf8))))
        XCTAssertTrue(try runtime.chat.pendingOutbox().isEmpty)
    }

    func testTheIdentityUnlocksOnlyUnderItsOwnKekAndSignsUntilClosed() throws {
        let kek = SoftwareKekStore()
        let record = try IdentityKeyGenerator.generate(kek: kek)
        XCTAssertThrowsError(try ChatIdentity.unlock(record: record, kek: SoftwareKekStore()),
                             "another install's KEK cannot open it")
        let identity = try ChatIdentity.unlock(record: record, kek: kek)
        let message = Data("prekey".utf8)
        XCTAssertTrue(LumosCrypto.ed25519Verify(publicKeyRaw: record.ed25519Public, message: message,
                                                signature: try identity.sign(message)))
        XCTAssertEqual(try X3DH.publicKey(of: identity.identity.identityDHPriv), record.x25519Public)
        identity.close()
        XCTAssertThrowsError(try identity.sign(message), "closed means no more signatures")
    }
}

final class RelayScheduleTests: XCTestCase {

    private func stopped(_ kind: RelayError.Kind, _ retryAfter: Int64) -> RelaySync.Report {
        var r = RelaySync.Report()
        r.stoppedBy = RelaySync.Stop(RelayError(kind: kind, code: "", retryAfterSeconds: retryAfter))
        return r
    }

    func testEveryOutcomeHasOneNextStep() {
        var sent = RelaySync.Report()
        sent.sent = 1
        XCTAssertEqual(RelaySchedule.after(sent, passesSoFar: 1), .done)
        var more = RelaySync.Report()
        more.moreWaiting = true
        XCTAssertEqual(RelaySchedule.after(more, passesSoFar: 1), .again, "more waiting in the mailbox: again at once")
        XCTAssertEqual(RelaySchedule.after(more, passesSoFar: RelaySchedule.maxPassesPerJob), .done,
                       "but never more than the cap in one job")
        XCTAssertEqual(RelaySchedule.after(stopped(.offline, -1), passesSoFar: 1), .whenOnline,
                       "offline waits for a network, not a timer (SRS-RMT-003)")
        XCTAssertEqual(RelaySchedule.after(stopped(.busy, 30), passesSoFar: 1), .afterDelay(millis: 30_000),
                       "the server's Retry-After, to the millisecond")
        XCTAssertEqual(RelaySchedule.after(stopped(.busy, -1), passesSoFar: 1), .backoff)
        XCTAssertEqual(RelaySchedule.after(stopped(.auth, -1), passesSoFar: 1), .again,
                       "a dropped credential re-registers on the next pass")
        XCTAssertEqual(RelaySchedule.after(stopped(.auth, -1), passesSoFar: RelaySchedule.maxPassesPerJob), .backoff)
        XCTAssertEqual(RelaySchedule.after(stopped(.refused, -1), passesSoFar: 1), .backoff)
        XCTAssertEqual(RelaySchedule.after(stopped(.notFound, -1), passesSoFar: 1), .backoff)
    }
}
