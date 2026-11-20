/// Lumos - ios-app/shell / The persisted interest machine, end to end.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-MAT-001, SRS-MAT-003, SRS-MAT-005, SRS-MAT-007, SRS-MAT-010,
///             SRS-MAT-011, SRS-MAT-014, TRS-TST-001
/// ADRs: ADR-002, ADR-003, ADR-008
/// Threading: test-only
/// Notes: The Android suite assertion for assertion over the CryptoKit
/// stack: restart persistence with one row per peer, the race deriving one
/// identifier from two independent ledgers, the rate limit refusing before
/// the machine and surviving a restart, the match record flipping without
/// deletion, and absorbed replays writing nothing.

import Foundation
import LumosProtocol
import LumosProtocolGenerated
import XCTest
@testable import LumosAppShell

final class InterestLedgerTests: XCTestCase {

    private static let now: Int64 = 1_755_561_600_000

    private struct Roomy: StorageCapacity {
        var usableSpaceBytes: Int64 { Int64.max }
    }

    private func openInMemory() throws -> LocalStore {
        let database = try Sqlite3Database(path: ":memory:")
        let key = try DataKeyHierarchy.create(kek: SoftwareKekStore()).deriveKey(for: .database)
        return try LocalStore.open(database: database, databaseDomainKey: key,
                                   capacity: Roomy(), now: { 0 })
    }

    private func peerID(_ seed: UInt8) -> Data {
        Data(repeating: seed, count: LumosConstants.peerIDLenDefault)
    }

    private func ledger(_ store: LocalStore, own: UInt8) -> InterestLedger {
        InterestLedger(store: store, budget: PersistedBudget(store: store),
                       ownPeerID: peerID(own))
    }

    func testStateSurvivesARestartAndOnePeerKeepsOneRow() throws {
        let store = try openInMemory()
        let peer = Hex.encode(peerID(0x22))
        let first = ledger(store, own: 0x11)

        XCTAssertEqual(try first.stateOf(peerKey: peer), .none, "absence is none")
        _ = try first.apply(peerKey: peer, event: .remoteRequest, nowMillis: Self.now)
        _ = try first.apply(peerKey: peer, event: .localDecline, nowMillis: Self.now + 1)
        _ = try first.apply(peerKey: peer, event: .remoteRequest, nowMillis: Self.now + 2)

        let second = ledger(store, own: 0x11)
        XCTAssertEqual(try second.stateOf(peerKey: peer), .requestedIn,
                       "the machine state is in the record, not the object")
        XCTAssertEqual(try store.count(kind: .interestRecord), 1,
                       "three state changes, one row: the upsert holds")
    }

    func testBothSidesOfTheRaceDeriveTheSameMatchIdentifier() throws {
        let alice = ledger(try openInMemory(), own: 0x11)
        let bob = ledger(try openInMemory(), own: 0x22)
        let bobKey = Hex.encode(peerID(0x22))
        let aliceKey = Hex.encode(peerID(0x11))

        _ = try alice.apply(peerKey: bobKey, event: .localRequest, nowMillis: Self.now)
        _ = try bob.apply(peerKey: aliceKey, event: .localRequest, nowMillis: Self.now)
        _ = try alice.apply(peerKey: bobKey, event: .remoteRequest, nowMillis: Self.now + 1)
        _ = try bob.apply(peerKey: aliceKey, event: .remoteRequest, nowMillis: Self.now + 1)

        let fromAlice = try alice.apply(peerKey: bobKey, event: .localAccept,
                                        nowMillis: Self.now + 2)
        let fromBob = try bob.apply(peerKey: aliceKey, event: .remoteAccept,
                                    nowMillis: Self.now + 2)
        XCTAssertEqual(fromAlice.state, .matched)
        XCTAssertEqual(fromBob.state, .matched)
        XCTAssertNotNil(fromAlice.matchIDHex)
        XCTAssertEqual(fromAlice.matchIDHex, fromBob.matchIDHex,
                       "one race, one identifier, derived independently (SRS-MAT-007)")
    }

    func testARateLimitedRequestNeverReachesTheMachineAndSurvivesRestart() throws {
        let store = try openInMemory()
        let first = ledger(store, own: 0x11)
        let limit = LumosConstants.interestRateLimitPerHourDefault
        for i in 0..<limit {
            let outcome = try first.apply(peerKey: Hex.encode(peerID(UInt8(100 + i))),
                                          event: .localRequest,
                                          nowMillis: Self.now + Int64(i))
            XCTAssertEqual(outcome.effect, .sendRequest,
                           "send \(i + 1) of \(limit) is inside the budget")
        }

        let oneMore = Hex.encode(peerID(0x33))
        let refused = try first.apply(peerKey: oneMore, event: .localRequest,
                                      nowMillis: Self.now + Int64(limit))
        XCTAssertTrue(refused.rateLimited, "the budget-plus-one request is refused")
        XCTAssertEqual(refused.effect, MatchMachine.Effect.none)
        XCTAssertEqual(try first.stateOf(peerKey: oneMore), MatchMachine.State.none,
                       "no state pretends a request was sent that was not")

        let second = ledger(store, own: 0x11)
        XCTAssertTrue(try second.apply(peerKey: oneMore, event: .localRequest,
                                       nowMillis: Self.now + Int64(limit) + 1).rateLimited,
                      "killing the process must not reset the flood limit (SRS-SAF-006)")
    }

    func testUnmatchingFlipsTheMatchRecordWithoutDeletingIt() throws {
        let store = try openInMemory()
        let subject = ledger(store, own: 0x11)
        let peer = Hex.encode(peerID(0x44))

        _ = try subject.apply(peerKey: peer, event: .remoteRequest, nowMillis: Self.now)
        _ = try subject.apply(peerKey: peer, event: .localAccept, nowMillis: Self.now + 1)
        let matched = try XCTUnwrap(subject.matchRecordOf(peerKey: peer))
        XCTAssertTrue(matched.contains("\nmatched\n"))

        _ = try subject.apply(peerKey: peer, event: .localUnmatch, nowMillis: Self.now + 2)
        let unmatched = try XCTUnwrap(subject.matchRecordOf(peerKey: peer),
                                      "the fact of the match survives (SRS-MAT-010)")
        XCTAssertTrue(unmatched.contains("\nunmatched\n"))
        XCTAssertEqual(try store.count(kind: .matchRecord), 1,
                       "flipped, not duplicated and not deleted")
    }

    func testAbsorbedReplaysWriteNothing() throws {
        let store = try openInMemory()
        let subject = ledger(store, own: 0x11)
        let peer = Hex.encode(peerID(0x55))

        let echo = try subject.apply(peerKey: peer, event: .remoteAccept, nowMillis: Self.now)
        XCTAssertEqual(echo.state, MatchMachine.State.none)
        XCTAssertNil(echo.matchIDHex)
        XCTAssertFalse(echo.rateLimited)
        XCTAssertEqual(try store.count(kind: .interestRecord), 0,
                       "an absorbed event writes no row (SRS-MAT-014)")
    }
}
