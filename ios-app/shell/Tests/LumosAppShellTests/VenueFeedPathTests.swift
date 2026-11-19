/// Lumos - ios-app/shell / 100+ synthetic peers through the whole feed path.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-ENV-001, TRS-ENV-002, SRS-NBY-006, SRS-NBY-007, TRS-TST-001
/// ADRs: ADR-002, ADR-008
/// Threading: test-only
/// Notes: The Android closing proof on the CryptoKit stack: 110 genuine
/// advertisers plus noise go sighting -> triage -> budgeted connect waves ->
/// real dual HELLO -> presence; every peer arrives while no wave exceeds the
/// register link budget; an EID rotation re-exchange grows nothing because
/// the card key is identity (SRS-NBY-006); noise dies structurally
/// (TRS-ENV-002); and the register age-out empties the venue (SRS-NBY-007).

import CryptoKit
import Foundation
import LumosProtocol
import LumosProtocolGenerated
import XCTest
@testable import LumosAppShell

final class VenueFeedPathTests: XCTestCase {

    private static let genuinePeers = LumosConstants.nearbySyntheticLoadTargetDefault + 10
    private static let noiseSightings = 12
    private static let rotatingPeers = 11
    private static let now: Int64 = 1_755_561_600_000

    /// A venue-goer: one identity, fresh exchange objects per link.
    private struct Party {
        let fields: Hello.Fields
        let signing: Curve25519.Signing.PrivateKey

        init(index: Int) throws {
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
                capabilityBits: 1, venueHint: 0, eid: eid,
                ed25519Public: signing.publicKey.rawRepresentation,
                x25519Public: xPublic, bindingSignature: binding,
                nonce: Data(count: Hello.nonceLen))
        }

        func freshExchange() -> HelloExchange {
            let key = signing
            return HelloExchange(signer: { message in try key.signature(for: message) },
                                 own: fields)
        }
    }

    private func kAdv(_ peer: Int) -> Data {
        Data((0..<32).map { UInt8(truncatingIfNeeded: peer &* 37 &+ $0 &+ 11) })
    }

    private func nonce(_ n: Int) -> Data {
        var data = Data(count: Hello.nonceLen)
        data[0] = UInt8(n & 0xFF)
        data[1] = UInt8((n >> 8) & 0xFF)
        data[2] = UInt8((n >> 16) & 0xFF)
        return data
    }

    /// One budgeted wave: one authenticated card per connect.
    private func connectWave(triage: DiscoveryTriage, presence: PresenceTable, me: Party,
                             byToken: [String: Party], nonceBase: Int) throws -> Int {
        let wave = triage.nextConnects(activeLinks: 0)
        XCTAssertLessThanOrEqual(wave.count, LumosConstants.maxConcurrentGATTLinksDefault,
                                 "no wave may exceed the register link budget (TRS-ENV-001)")
        for (offset, candidate) in wave.enumerated() {
            XCTAssertTrue(triage.remove(key: candidate.key),
                          "a spent candidate leaves rotation so the next wave moves on")
            let peer = try XCTUnwrap(byToken[candidate.linkToken])
            let mine = me.freshExchange()
            let hello = mine.start(nonce: nonce(nonceBase + offset))
            let ack = try peer.freshExchange().onHello(hello)
            presence.record(try mine.onAck(ack), nowMillis: Self.now)
        }
        return wave.count
    }

    func testAFullVenueFlowsSightingToTriageToHelloToPresenceUnderTheBudget() throws {
        let me = try Party(index: 9999)
        let triage = DiscoveryTriage()
        let presence = PresenceTable()
        var byToken: [String: Party] = [:]

        for peer in 0..<Self.genuinePeers {
            let token = "venue:\(peer)"
            byToken[token] = try Party(index: peer)
            let hint = try Adv.build(kAdv: kAdv(peer), modeFlags: 0, epoch: 1)
            _ = triage.ingest(BleSighting(linkToken: token, serviceData: hint,
                                          rssiDbm: -40 - (peer % 55), atMillis: Self.now))
        }
        for noise in 0..<Self.noiseSightings {
            _ = triage.ingest(BleSighting(linkToken: "noise:\(noise)",
                                          serviceData: Data([9, 9, 9]),
                                          rssiDbm: -35, atMillis: Self.now))
        }
        XCTAssertEqual(triage.candidates().count, Self.genuinePeers,
                       "every genuine peer triages, noise does not")

        var waves = 0
        var nonceBase = 0
        while presence.all().count < Self.genuinePeers {
            let connected = try connectWave(triage: triage, presence: presence, me: me,
                                            byToken: byToken, nonceBase: nonceBase)
            XCTAssertGreaterThan(connected, 0, "the pipeline must make progress every wave")
            nonceBase += connected
            waves += 1
        }
        XCTAssertEqual(presence.all().count, Self.genuinePeers,
                       "every genuine advertiser ends up as exactly one card")
        let budget = LumosConstants.maxConcurrentGATTLinksDefault
        XCTAssertGreaterThanOrEqual(waves, (Self.genuinePeers + budget - 1) / budget,
                                    "a bounded budget cannot beat the arithmetic")

        // EID rotation: same identities, fresh EIDs; presence must not grow.
        let keysBefore = Set(presence.all().map(\.peerKey))
        for peer in 0..<Self.rotatingPeers {
            let rotated = try Adv.build(kAdv: kAdv(peer), modeFlags: 0, epoch: 2)
            _ = triage.ingest(BleSighting(linkToken: "venue:\(peer)", serviceData: rotated,
                                          rssiDbm: -45, atMillis: Self.now + 1000))
        }
        XCTAssertEqual(triage.candidates().count, Self.rotatingPeers)
        while !triage.candidates().isEmpty {
            nonceBase += try connectWave(triage: triage, presence: presence, me: me,
                                         byToken: byToken, nonceBase: nonceBase)
        }
        XCTAssertEqual(presence.all().count, Self.genuinePeers,
                       "an EID rotation is the same person, never a second card (SRS-NBY-006)")
        XCTAssertEqual(Set(presence.all().map(\.peerKey)), keysBefore,
                       "the card keys are identities, not addresses")

        // The register age-out empties the venue at the bound and not before.
        let bound = Int64(LumosConstants.presenceAgeoutSecDefault) * 1000
        XCTAssertEqual(presence.prune(nowMillis: Self.now + bound), 0,
                       "the last millisecond inside keeps cards")
        XCTAssertEqual(presence.prune(nowMillis: Self.now + bound + 1001), Self.genuinePeers,
                       "past the bound every stale card goes (SRS-NBY-007)")
        XCTAssertTrue(presence.all().isEmpty)
    }
}
