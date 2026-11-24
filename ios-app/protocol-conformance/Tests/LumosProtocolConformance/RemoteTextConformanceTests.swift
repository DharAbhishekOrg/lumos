/// Lumos - LumosProtocolConformance / The remote-text wire and sessions, replayed on Swift.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-RMT-001, SRS-RMT-004, SRS-RMT-009, SRS-RMT-011, TRS-CRY-001, TRS-CRY-002, TRS-TST-001
/// ADRs: ADR-001, ADR-008
/// Threading: test-only
/// Notes: The Java suite on this platform. Every text event decodes and
/// re-encodes byte for byte, every negative earns its exact code, and the
/// scripted session runs both sides from the recorded keys: each seal must
/// reproduce the Go envelope exactly, each open must yield the Go plaintext.
/// Bob's bundle is built from the recorded PUBLIC fields and signatures, so the
/// Swift X3DH verifies real Go signatures.

import Foundation
import LumosProtocolGenerated
import XCTest
@testable import LumosProtocol

final class RemoteTextConformanceTests: XCTestCase {

    private var root: URL { Vectors.repoRoot() }
    private let path = "test-vectors/chat/remote_text.json"

    /// Replays a recorded list of ratchet scalars.
    private final class Recorded {
        private var keys: [Data]
        init(_ keys: [Data]) { self.keys = keys }
        func next() throws -> Data {
            guard !keys.isEmpty else {
                throw LumosCrypto.WireError(LumosErrors.errCryptoAeadAuthFailed, "recorded ratchet keys exhausted")
            }
            return keys.removeFirst()
        }
    }

    private func recorded(_ party: [String: Any]) throws -> Recorded {
        Recorded(try Vectors.stringArray(party, "testonly_ratchet_turn_privs_hex").map { try XCTUnwrap(Hex.decode($0)) })
    }

    private func code(_ body: () throws -> Void) -> String? {
        do {
            try body()
            return nil
        } catch let e as LumosCrypto.WireError {
            return e.code
        } catch {
            return "\(error)"
        }
    }

    func testEveryTextEventDecodesAndReencodesByteForByte() throws {
        let file = try Vectors.load(path, root: root)
        let cases = try Vectors.array(file, "text_events")
        XCTAssertFalse(cases.isEmpty, "an empty section proves nothing")
        for c in cases {
            let name = try Vectors.string(c, "name")
            let encoded = try Vectors.hex(c, "encoded_hex")
            let e = try RemoteText.decodeEvent(encoded)
            XCTAssertEqual(Int(e.kind.rawValue), try Vectors.int(c, "kind"), name)
            if c["body_hex"] != nil {
                XCTAssertEqual(e.body, try Vectors.hex(c, "body_hex"), name)
            }
            if c["target_message_id_hex"] != nil {
                XCTAssertEqual(e.target, try Vectors.hex(c, "target_message_id_hex"), name)
            }
            XCTAssertEqual(e.encoded, encoded, "re-encoding \(name)")
        }
    }

    func testEveryNegativeEarnsItsExactCode() throws {
        let file = try Vectors.load(path, root: root)
        for c in try Vectors.array(file, "text_event_negatives") {
            let bytes = try Vectors.hex(c, "encoded_hex")
            let want = try Vectors.string(c, "expected_error")
            let name = try Vectors.string(c, "name")
            XCTAssertEqual(code { _ = try RemoteText.decodeEvent(bytes) }, want, name)
        }
        for c in try Vectors.array(file, "envelope_negatives") {
            let bytes = try Vectors.hex(c, "encoded_hex")
            let want = try Vectors.string(c, "expected_error")
            let name = try Vectors.string(c, "name")
            XCTAssertEqual(code { _ = try RemoteText.decodeEnvelope(bytes) }, want, name)
        }
    }

    private func bundle(_ b: [String: Any]) throws -> X3DH.Bundle {
        X3DH.Bundle(identityEd: try Vectors.hex(b, "identity_ed_pub_hex"),
                    identityDH: try Vectors.hex(b, "identity_dh_pub_hex"),
                    identityDHSig: try Vectors.hex(b, "identity_dh_sig_hex"),
                    spkID: UInt32(try Vectors.int(b, "spk_id")),
                    spk: try Vectors.hex(b, "spk_pub_hex"), spkSig: try Vectors.hex(b, "spk_sig_hex"),
                    opkID: UInt32(try Vectors.int(b, "opk_id")), opk: try Vectors.hex(b, "opk_pub_hex"))
    }

    private func identity(_ p: [String: Any]) throws -> RemoteSession.Identity {
        RemoteSession.Identity(identityEd: try Vectors.hex(p, "identity_ed_pub_hex"),
                               identityDHPriv: try Vectors.hex(p, "testonly_identity_dh_priv_hex"),
                               identityDHSig: try Vectors.hex(p, "identity_dh_sig_hex"))
    }

    private func responderKeys(_ b: [String: Any]) throws -> RemoteSession.ResponderKeys {
        RemoteSession.ResponderKeys(spkPriv: try Vectors.hex(b, "testonly_spk_priv_hex"),
                                    spkID: UInt32(try Vectors.int(b, "spk_id")),
                                    opkPriv: try Vectors.hex(b, "testonly_opk_priv_hex"),
                                    opkID: UInt32(try Vectors.int(b, "opk_id")))
    }

    func testTheScriptedSessionReplaysByteForByte() throws {
        let s = try Vectors.object(try Vectors.load(path, root: root), "session")
        let a = try Vectors.object(s, "alice")
        let b = try Vectors.object(s, "bob")
        let matchID = try Vectors.hex(s, "match_id_hex")
        let now = Int64(try Vectors.int(s, "now"))
        let messages = try Vectors.array(s, "messages")
        let aliceKeys = try recorded(a)
        let bobKeys = try recorded(b)
        let started = try RemoteSession.initiate(
            own: try identity(a), bundle: try bundle(b), matchID: matchID,
            ephemeralPriv: try Vectors.hex(a, "testonly_ephemeral_priv_hex"),
            ratchetPriv: try Vectors.hex(a, "testonly_first_ratchet_priv_hex"))
        var aliceState = started.0
        let prekey = started.1
        var bobState: DoubleRatchet?
        var heardBack = false
        var kinds = Set<UInt8>()
        for step in try Vectors.array(s, "steps") {
            let m = messages[try Vectors.int(step, "message")]
            let byBob = try Vectors.string(step, "actor") == "bob"
            let envelope = try Vectors.hex(m, "envelope_hex")
            let plaintext = try Vectors.hex(m, "plaintext_hex")
            if try Vectors.string(step, "op") == "seal" {
                let p = try RemoteText.decodePlaintext(plaintext)
                kinds.insert(p.event.kind.rawValue)
                let sealed: Data
                if byBob {
                    var state = try XCTUnwrap(bobState)
                    sealed = try RemoteSession.seal(&state, p, prekey: nil, now: now)
                    bobState = state
                } else {
                    sealed = try RemoteSession.seal(&aliceState, p, prekey: heardBack ? nil : prekey, now: now)
                }
                XCTAssertEqual(Hex.encode(sealed), Hex.encode(envelope), "seal must reproduce the Go envelope")
                continue
            }
            let e = try RemoteText.decodeEnvelope(envelope)
            let outerID = try Vectors.hex(m, "message_id_hex")
            let got: RemoteText.Plaintext
            if byBob {
                var state: DoubleRatchet
                if let existing = bobState {
                    state = existing
                } else {
                    let fields = try XCTUnwrap(e.prekey, "first contact is a PREKEY envelope")
                    state = try RemoteSession.respond(own: try identity(b), keys: try responderKeys(b),
                                                      initiatorEd: try Vectors.hex(a, "identity_ed_pub_hex"),
                                                      prekey: fields, matchID: matchID)
                }
                got = try RemoteSession.open(&state, e, outerID: outerID, now: now, nextKey: bobKeys.next)
                bobState = state
            } else {
                got = try RemoteSession.open(&aliceState, e, outerID: outerID, now: now, nextKey: aliceKeys.next)
                heardBack = true
            }
            XCTAssertEqual(got.encoded, plaintext, "open must yield the Go plaintext")
        }
        XCTAssertEqual(kinds.count, TextEventKind.allCases.count,
                       "the session exercises every event kind (REPLY since CR-009)")
    }

    func testStoredPrekeyFieldsAreTheEnvelopesOwnBytes() throws {
        let s = try Vectors.object(try Vectors.load(path, root: root), "session")
        var checked = 0
        for m in try Vectors.array(s, "messages") {
            let envelope = try Vectors.hex(m, "envelope_hex")
            guard let fields = try RemoteText.decodeEnvelope(envelope).prekey else { continue }
            XCTAssertEqual(fields.encoded, envelope.dropFirst().prefix(fields.encoded.count),
                           "the stored fields are the PREKEY envelope's bytes after its format byte")
            XCTAssertEqual(try RemoteText.decodePrekeyFields(fields.encoded), fields)
            XCTAssertThrowsError(try RemoteText.decodePrekeyFields(fields.encoded.dropLast())) {
                XCTAssertEqual(($0 as? LumosCrypto.WireError)?.code, LumosErrors.errPayloadMalformed)
            }
            checked += 1
        }
        XCTAssertGreaterThan(checked, 0, "the session vectors include a PREKEY envelope")
    }

    func testARelabelledOrMisattributedFirstMessageIsRefused() throws {
        let s = try Vectors.object(try Vectors.load(path, root: root), "session")
        let a = try Vectors.object(s, "alice")
        let b = try Vectors.object(s, "bob")
        let matchID = try Vectors.hex(s, "match_id_hex")
        let messages = try Vectors.array(s, "messages")
        let e = try RemoteText.decodeEnvelope(try Vectors.hex(messages[0], "envelope_hex"))
        let fields = try XCTUnwrap(e.prekey)
        // Bob's own identity stands in for a stranger who is not the matched peer.
        XCTAssertEqual(code {
            _ = try RemoteSession.respond(own: try identity(b), keys: try responderKeys(b),
                                          initiatorEd: try Vectors.hex(b, "identity_ed_pub_hex"),
                                          prekey: fields, matchID: matchID)
        }, LumosErrors.errCryptoPrekeySignatureInvalid)
        var state = try RemoteSession.respond(own: try identity(b), keys: try responderKeys(b),
                                              initiatorEd: try Vectors.hex(a, "identity_ed_pub_hex"),
                                              prekey: fields, matchID: matchID)
        let wrongID = try Vectors.hex(messages[1], "message_id_hex")
        let keys = try recorded(b)
        XCTAssertEqual(code {
            _ = try RemoteSession.open(&state, e, outerID: wrongID, now: Int64(try Vectors.int(s, "now")), nextKey: keys.next)
        }, LumosErrors.errPayloadMalformed)
    }
}
