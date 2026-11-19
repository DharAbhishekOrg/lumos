/// Lumos - ios-app/shell / Zero-false-positive presence on the Apple side.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-ENV-002, SRS-NBY-006, SRS-NBY-007, SRS-IDN-002, TRS-TST-001
/// ADRs: ADR-002, ADR-008
/// Threading: test-only
/// Notes: The same CI half the Android table proves, over the REAL exchange
/// with CryptoKit identities: presence only from verified ACKs to one's own
/// fresh HELLO, a replay throws before any Peer exists so no card can appear,
/// one identity stays one card across EID rotations, and stale cards age out
/// exactly on the register bound.

import XCTest
import CryptoKit
import Foundation
import LumosProtocol
import LumosProtocolGenerated
@testable import LumosAppShell

final class PresenceTableTests: XCTestCase {

    /// A CryptoKit identity with its exchange.
    private struct Party {
        let exchange: HelloExchange
        let fields: Hello.Fields
        let signing: Curve25519.Signing.PrivateKey

        init(eid: Data) throws {
            let signing = Curve25519.Signing.PrivateKey()
            let agreement = Curve25519.KeyAgreement.PrivateKey()
            let xPublic = agreement.publicKey.rawRepresentation
            let binding = try signing.signature(
                for: Data(LumosConstants.x3dhIdentityBindingInfoValue.utf8) + xPublic)
            let fields = Hello.Fields(
                protocolVersion: LumosConstants.protocolVersionEncoded,
                capabilityBits: 1, venueHint: 0, eid: eid,
                ed25519Public: signing.publicKey.rawRepresentation,
                x25519Public: xPublic, bindingSignature: binding,
                nonce: Data(count: Hello.nonceLen))
            self.signing = signing
            self.fields = fields
            self.exchange = HelloExchange(
                signer: { message in try signing.signature(for: message) }, own: fields)
        }
    }

    private let table = PresenceTable()

    private func nonce(_ b: UInt8) -> Data { Data(repeating: b, count: Hello.nonceLen) }

    func testPresenceExistsOnlyAfterACompletedExchange() throws {
        let alice = try Party(eid: Data(count: Hello.eidLen))
        let bob = try Party(eid: Data(count: Hello.eidLen))

        let hello = alice.exchange.start(nonce: nonce(1))
        let ack = try bob.exchange.onHello(hello)
        table.record(try alice.exchange.onAck(ack), nowMillis: 1000)

        XCTAssertEqual(1, table.count)
        XCTAssertEqual(1, table.all()[0].capabilityBits,
                       "the card carries the AUTHENTICATED capabilities")
    }

    func testAReplayedAckThrowsBeforeAnyCardCanExist() throws {
        let alice = try Party(eid: Data(count: Hello.eidLen))
        let bob = try Party(eid: Data(count: Hello.eidLen))
        let hello = alice.exchange.start(nonce: nonce(2))
        let ack = try bob.exchange.onHello(hello)
        table.record(try alice.exchange.onAck(ack), nowMillis: 1000)

        XCTAssertThrowsError(try alice.exchange.onAck(ack)) { error in
            XCTAssertEqual(LumosErrors.errHelloNonceMismatch,
                           (error as? LumosCrypto.WireError)?.code)
        }
        XCTAssertEqual(1, table.count)
    }

    func testOneIdentityStaysOneCardAcrossEidRotations() throws {
        let alice = try Party(eid: Data(count: Hello.eidLen))
        let bob = try Party(eid: Data(repeating: 1, count: Hello.eidLen))

        let h1 = alice.exchange.start(nonce: nonce(3))
        table.record(try alice.exchange.onAck(try bob.exchange.onHello(h1)), nowMillis: 1000)

        // Bob rotates his EID and answers a fresh HELLO (SRS-NBY-006).
        let rotatedFields = bob.fields.withNonce(Data(count: Hello.nonceLen))
        let rotated = Hello.Fields(
            protocolVersion: rotatedFields.protocolVersion,
            capabilityBits: rotatedFields.capabilityBits,
            venueHint: rotatedFields.venueHint,
            eid: Data(repeating: 2, count: Hello.eidLen),
            ed25519Public: rotatedFields.ed25519Public,
            x25519Public: rotatedFields.x25519Public,
            bindingSignature: rotatedFields.bindingSignature,
            nonce: rotatedFields.nonce)
        let h2 = alice.exchange.start(nonce: nonce(4))
        let signing = bob.signing
        let ack2 = try Hello.buildAck(responder: rotated, helloBytes: h2,
                                      signer: { try signing.signature(for: $0) })
        table.record(try alice.exchange.onAck(ack2), nowMillis: 2000)

        XCTAssertEqual(1, table.count, "the rotating EID must not mint a second card")
        XCTAssertEqual(2000, table.all()[0].confirmedAtMillis, "the card refreshed")
    }

    func testStaleCardsAgeOutOnTheRegisterBound() throws {
        let alice = try Party(eid: Data(count: Hello.eidLen))
        let bob = try Party(eid: Data(count: Hello.eidLen))
        let hello = alice.exchange.start(nonce: nonce(5))
        table.record(try alice.exchange.onAck(try bob.exchange.onHello(hello)), nowMillis: 0)

        let bound = Int64(LumosConstants.presenceAgeoutSecDefault) * 1000
        XCTAssertEqual(0, table.prune(nowMillis: bound), "exactly at the bound is still present")
        XCTAssertEqual(1, table.prune(nowMillis: bound + 1), "past the bound, the card goes")
        XCTAssertEqual(0, table.count)
    }
}
