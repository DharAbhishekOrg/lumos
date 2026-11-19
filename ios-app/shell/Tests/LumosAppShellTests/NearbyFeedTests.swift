/// Lumos - ios-app/shell / The feed state machine, executed on CryptoKit.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-NBY-010, SRS-NBY-011, SRS-NBY-012, SRS-NBY-013, SRS-NBY-014,
///             SRS-NBY-015, SRS-NFR-PERF-009, TRS-ENV-003, TRS-TST-001
/// ADRs: ADR-002, ADR-003
/// Threading: test-only
/// Notes: The Android suite, assertion for assertion: real store over real
/// SQLite, real dual exchanges, scripted radio. The typed-failure mapping is
/// pinned over every BleScanFailure case; a hidden peer stays gone for a
/// fresh controller over the same store; a block keeps the identity out
/// across a rotation re-exchange; closing the view writes exactly one metric
/// record; and the warmed-cache refresh over 110 authenticated peers fits
/// the SRS-NFR-PERF-009 budget.

import CryptoKit
import Foundation
import LumosProtocol
import LumosProtocolGenerated
import XCTest
@testable import LumosAppShell

final class NearbyFeedTests: XCTestCase {

    private static let now: Int64 = 1_755_561_600_000

    /// One identity; fresh exchange objects per link.
    private struct Party {
        let fields: Hello.Fields
        let signing: Curve25519.Signing.PrivateKey

        init(index: Int, capabilityBits: Int) throws {
            let signing = Curve25519.Signing.PrivateKey()
            let agreement = Curve25519.KeyAgreement.PrivateKey()
            let xPublic = agreement.publicKey.rawRepresentation
            let binding = try signing.signature(
                for: Data(LumosConstants.x3dhIdentityBindingInfoValue.utf8) + xPublic)
            var eid = Data(count: Hello.eidLen)
            eid[0] = UInt8(index & 0xFF)
            eid[1] = UInt8((index >> 8) & 0xFF)
            self.signing = signing
            self.fields = Hello.Fields(
                protocolVersion: LumosConstants.protocolVersionEncoded,
                capabilityBits: capabilityBits, venueHint: 0, eid: eid,
                ed25519Public: signing.publicKey.rawRepresentation,
                x25519Public: xPublic, bindingSignature: binding,
                nonce: Data(count: Hello.nonceLen))
        }

        func freshExchange() -> HelloExchange {
            let key = signing
            return HelloExchange(signer: { try key.signature(for: $0) }, own: fields)
        }
    }

    /// Unlimited room, for tests that are not about capacity.
    private struct Roomy: StorageCapacity {
        var usableSpaceBytes: Int64 { Int64.max }
    }

    private var nonceCounter = 0

    private func openInMemory() throws -> LocalStore {
        let database = try Sqlite3Database(path: ":memory:")
        let key = try DataKeyHierarchy.create(kek: SoftwareKekStore()).deriveKey(for: .database)
        return try LocalStore.open(database: database, databaseDomainKey: key,
                                   capacity: Roomy(), now: { 0 })
    }

    private func controller(_ store: LocalStore, _ presence: PresenceTable)
        -> NearbyFeedController {
        NearbyFeedController(presence: presence, hidden: HiddenPeers(store: store),
                             blocked: BlockedPeers(store: store), ladder: RescanLadder(),
                             metric: DiscoverySuccessMetric(store: store))
    }

    @discardableResult
    private func authenticate(_ table: PresenceTable, me: Party, peer: Party,
                              nowMillis: Int64) throws -> String {
        nonceCounter += 1
        var nonce = Data(count: Hello.nonceLen)
        nonce[0] = UInt8(nonceCounter & 0xFF)
        nonce[1] = UInt8((nonceCounter >> 8) & 0xFF)
        let mine = me.freshExchange()
        let hello = mine.start(nonce: nonce)
        let ack = try peer.freshExchange().onHello(hello)
        return table.record(try mine.onAck(ack), nowMillis: nowMillis).peerKey
    }

    func testEveryTypedScanFailureFoldsToAStateTheUserCanActOn() throws {
        let store = try openInMemory()
        let feed = controller(store, PresenceTable())
        let cases: [(BleScanFailure, FeedState)] = [
            (.poweredOff, .radioOff),
            (.unauthorized, .permissionNeeded),
            (.resetting, .radioBusy),
            (.unsupported, .radioUnavailable),
            (.undocumented, .radioUnavailable)
        ]
        for (failure, expected) in cases {
            feed.scanFailed(failure)
            let got = try feed.state(nowMillis: Self.now)
            switch (got, expected) {
            case (.radioOff, .radioOff), (.permissionNeeded, .permissionNeeded),
                 (.radioBusy, .radioBusy), (.radioUnavailable, .radioUnavailable):
                break
            default:
                XCTFail("\(failure) folded to \(got), expected \(expected)")
            }
        }
        feed.scanRecovered()
        if case .scanningEmpty = try feed.state(nowMillis: Self.now) {} else {
            XCTFail("recovery returns the feed to the skeleton, not a stale error")
        }
    }

    func testHiddenStaysGoneAndBlockedNeverReappearsUnderRotation() throws {
        let store = try openInMemory()
        let presence = PresenceTable()
        let me = try Party(index: 9999, capabilityBits: 1)
        let feed = controller(store, presence)

        let shy = try Party(index: 1, capabilityBits: 1)
        let rude = try Party(index: 2, capabilityBits: 1)
        let shyKey = try authenticate(presence, me: me, peer: shy, nowMillis: Self.now)
        let rudeKey = try authenticate(presence, me: me, peer: rude, nowMillis: Self.now)
        XCTAssertEqual(try feed.state(nowMillis: Self.now).cardList.count, 2)

        try feed.hideCard(peerKey: shyKey, atUtcMillis: Self.now)
        try store.put(kind: .blockEntry, plaintext: Data("blocked".utf8),
                      naturalKey: HiddenPeers.naturalKey(rudeKey))

        // A fresh controller over the same store: both choices persisted.
        let fresh = controller(store, presence)
        if case .scanningEmpty = try fresh.state(nowMillis: Self.now) {} else {
            XCTFail("hidden and blocked are stored choices, not controller memory")
        }

        // The blocked peer re-exchanges - the rotation path. Same identity,
        // same verdict (SRS-NBY-013).
        let again = try authenticate(presence, me: me, peer: rude, nowMillis: Self.now + 1_000)
        XCTAssertEqual(rudeKey, again, "rotation does not change the identity key")
        let after = controller(store, presence)
        if case .scanningEmpty = try after.state(nowMillis: Self.now + 1_000) {} else {
            XCTFail("blocked identities never reappear under EID rotation")
        }

        // The hidden record carries no readable identifier.
        for payload in try store.findByNaturalKey(kind: .hiddenPeer,
                                                  naturalKey: HiddenPeers.naturalKey(shyKey)) {
            let text = try XCTUnwrap(String(bytes: payload, encoding: .utf8))
            XCTAssertFalse(text.contains(shyKey),
                           "the payload must not spell the identity the blind index hides")
        }
    }

    func testTheInterestFilterIsLocalAndOptInAndCardsArriveNewestFirst() throws {
        let store = try openInMemory()
        let presence = PresenceTable()
        let me = try Party(index: 9999, capabilityBits: 1)
        let feed = controller(store, presence)

        try authenticate(presence, me: me,
                         peer: Party(index: 3, capabilityBits: 0), nowMillis: Self.now)
        let social = try authenticate(presence, me: me,
                                      peer: Party(index: 4, capabilityBits: 0b01),
                                      nowMillis: Self.now + 1_000)
        let dating = try authenticate(presence, me: me,
                                      peer: Party(index: 5, capabilityBits: 0b10),
                                      nowMillis: Self.now + 2_000)

        let all = try feed.state(nowMillis: Self.now + 2_000).cardList
        XCTAssertEqual(all.count, 3, "unfiltered means everyone, even zero-bit peers")
        XCTAssertEqual(all.first?.peerKey, dating, "newest confirmation leads the feed")
        XCTAssertEqual(all[1].peerKey, social)

        feed.setInterestMask(0b10)
        let filtered = try feed.state(nowMillis: Self.now + 2_000).cardList
        XCTAssertEqual(filtered.map(\.peerKey), [dating],
                       "the mask keeps only compatible AUTHENTICATED bits")

        feed.setInterestMask(NearbyFeedController.acceptAll)
        XCTAssertEqual(try feed.state(nowMillis: Self.now + 2_000).cardList.count, 3)
    }

    func testClosingTheViewWritesExactlyOneMetricRecord() throws {
        let store = try openInMemory()
        let presence = PresenceTable()
        let me = try Party(index: 9999, capabilityBits: 1)
        let feed = controller(store, presence)

        feed.viewStarted()
        feed.visibilityChanged(visibleNearby: true)
        feed.scanCycleCompleted(sightingsInCycle: 0)
        try authenticate(presence, me: me,
                         peer: Party(index: 6, capabilityBits: 1), nowMillis: Self.now)
        feed.scanCycleCompleted(sightingsInCycle: 1)
        try feed.viewEnded(nowMillis: Self.now)

        XCTAssertEqual(try store.count(kind: .diagnosticEvent), 1, "one session, one record")
        try feed.viewEnded(nowMillis: Self.now)
        XCTAssertEqual(try store.count(kind: .diagnosticEvent), 1,
                       "a second close without a second open writes nothing")
    }

    func testAHundredRefreshesOverAHundredPlusPeersFitTheStallBudget() throws {
        let store = try openInMemory()
        let presence = PresenceTable()
        let me = try Party(index: 9999, capabilityBits: 1)
        let feed = controller(store, presence)
        let peers = LumosConstants.nearbySyntheticLoadTargetDefault + 10

        for index in 0..<peers {
            try authenticate(presence, me: me,
                             peer: Party(index: index, capabilityBits: 1 + (index % 3)),
                             nowMillis: Self.now)
        }
        XCTAssertEqual(try feed.state(nowMillis: Self.now).cardList.count, peers)

        let start = DispatchTime.now()
        for _ in 0..<100 {
            XCTAssertEqual(try feed.state(nowMillis: Self.now).cardList.count, peers)
        }
        let elapsedMillis = (DispatchTime.now().uptimeNanoseconds
            - start.uptimeNanoseconds) / 1_000_000
        XCTAssertLessThan(elapsedMillis, 1_000,
                          "100 refreshes over \(peers) peers took \(elapsedMillis) ms; at "
                          + "that rate a refresh breaches the 100 ms stall rule")
    }
}
