/// Lumos - LumosProtocolConformance / Swift conformance to the HELLO exchange vectors.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-PROTO-017, TRS-PROTO-015, TRS-ENV-002, SRS-IDN-002, TRS-TST-001
/// ADRs: ADR-002, ADR-008
/// Threading: any
/// Notes: The Swift quarter of the hello_exchange family, with the CryptoKit
/// split the identity_binding family set: HELLO payloads carry no fresh
/// signature, so they byte-compare; the committed ACKs VERIFY under the
/// committed identities; and a full dual exchange with CryptoKit signers
/// round-trips, because CryptoKit randomises Ed25519 signing and a byte-pin
/// of its ACK would be impossible by design. Every negative fails with its
/// exact stable code through the mode the file names - including the bare
/// replay, before any cryptography runs (TRS-ENV-002).

import XCTest
import Foundation
import CryptoKit
import LumosProtocolGenerated
@testable import LumosProtocol

final class HelloConformanceTests: XCTestCase {

    private var root: URL { Vectors.repoRoot() }
    private let path = "test-vectors/session/hello_exchange.json"

    /// A party rebuilt from its committed seeds, with a CryptoKit signer.
    private struct Party {
        let exchange: HelloExchange
        let edPublic: Data

        init(_ p: [String: Any]) throws {
            let signing = try Curve25519.Signing.PrivateKey(
                rawRepresentation: try Vectors.hex(p, "testonly_ed_seed_hex"))
            let edPublic = try Vectors.hex(p, "ed_public_hex")
            self.edPublic = edPublic
            let fields = Hello.Fields(
                protocolVersion: LumosConstants.protocolVersionEncoded,
                capabilityBits: try Vectors.int(p, "capability_bits"),
                venueHint: Int64(try Vectors.int(p, "venue_hint")),
                eid: try Vectors.hex(p, "eid_hex"),
                ed25519Public: edPublic,
                x25519Public: try Vectors.hex(p, "x_public_hex"),
                bindingSignature: try Vectors.hex(p, "binding_sig_hex"),
                nonce: Data(count: Hello.nonceLen))
            self.exchange = HelloExchange(
                signer: { message in try signing.signature(for: message) },
                own: fields)
        }
    }

    func testHellosByteMatchAndCommittedAcksVerify() throws {
        let file = try Vectors.load(path, root: root)
        let initiator = try Party(try Vectors.object(file, "initiator"))
        let ex = try Vectors.object(file, "exchange")

        // HELLO carries no fresh signature: it must byte-match everywhere.
        let iHello = initiator.exchange.start(
            nonce: try Vectors.hex(ex, "initiator_nonce_hex"))
        XCTAssertEqual(try Vectors.string(ex, "initiator_hello_hex"), Hex.encode(iHello),
                       "initiator hello must match the committed bytes")

        // The committed ACK was signed by Go and reproduced by the JCA and
        // libsodium; CryptoKit's part is to VERIFY it and grant the peer.
        let peer = try initiator.exchange.onAck(try Vectors.hex(ex, "responder_ack_hex"))
        XCTAssertEqual(try Vectors.string(try Vectors.object(file, "responder"), "peer_id_hex"),
                       Hex.encode(peer.id), "responder peer id")
        XCTAssertEqual(Data(count: Hello.eidLen), peer.fields.eid,
                       "the responder must be the hintless shape (TRS-PROTO-018)")
    }

    func testTheDualExchangeRoundTripsWithCryptoKitSigners() throws {
        let file = try Vectors.load(path, root: root)
        let initiator = try Party(try Vectors.object(file, "initiator"))
        let responder = try Party(try Vectors.object(file, "responder"))
        let ex = try Vectors.object(file, "exchange")

        let iHello = initiator.exchange.start(nonce: try Vectors.hex(ex, "initiator_nonce_hex"))
        let rAck = try responder.exchange.onHello(iHello)
        let peerOfInitiator = try initiator.exchange.onAck(rAck)
        XCTAssertEqual(responder.edPublic, peerOfInitiator.fields.ed25519Public)

        let rHello = responder.exchange.start(nonce: try Vectors.hex(ex, "responder_nonce_hex"))
        XCTAssertEqual(try Vectors.string(ex, "responder_hello_hex"), Hex.encode(rHello))
        let iAck = try initiator.exchange.onHello(rHello)
        let peerOfResponder = try responder.exchange.onAck(iAck)
        XCTAssertEqual(try Vectors.string(try Vectors.object(file, "initiator"), "peer_id_hex"),
                       Hex.encode(peerOfResponder.id))
    }

    func testEveryNegativeFailsWithItsStableCode() throws {
        let file = try Vectors.load(path, root: root)
        let negatives = try Vectors.array(file, "negatives")
        XCTAssertFalse(negatives.isEmpty, "a family without negatives proves nothing")

        for c in negatives {
            let name = try Vectors.string(c, "name")
            let payload = try Vectors.hex(c, "payload_hex")
            do {
                switch try Vectors.string(c, "mode") {
                case "parse_hello":
                    _ = try Hello.parseHello(payload)
                case "verify_ack":
                    _ = try Hello.verifyAck(
                        ownHelloBytes: try Vectors.hex(c, "own_hello_hex"),
                        ackBytes: payload)
                case "ack_without_outstanding":
                    _ = try HelloExchange(signer: { _ in Data(count: 64) },
                                          own: emptyFields()).onAck(payload)
                default:
                    XCTFail("\(name): unknown mode")
                }
                XCTFail("\(name): a payload that must be rejected authenticated")
            } catch let error as LumosCrypto.WireError {
                XCTAssertEqual(try Vectors.string(c, "expected_error"), error.code,
                               "\(name): stable code")
            }
        }
    }

    private func emptyFields() -> Hello.Fields {
        Hello.Fields(protocolVersion: LumosConstants.protocolVersionEncoded,
                     capabilityBits: 0, venueHint: 0,
                     eid: Data(count: Hello.eidLen),
                     ed25519Public: Data(count: 32), x25519Public: Data(count: 32),
                     bindingSignature: Data(count: 64), nonce: Data(count: Hello.nonceLen))
    }
}
