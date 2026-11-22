/// Lumos - LumosProtocolConformance / RELAY_ROUTE and receipts, replayed on Swift.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-RMT-005, SRS-RMT-014, SRS-RMT-009, SRS-RMT-001, TRS-PROTO-007, TRS-TST-001
/// ADRs: ADR-001, ADR-002
/// Threading: test-only
/// Notes: chat/receipts.json (CR-005), the Java suite on this platform: the
/// codecs round-trip every case and refuse every negative with its code; the
/// recorded session replays byte for byte; and every relabel, tried on fresh
/// state replayed up to its open, fails with its code.

import Foundation
import LumosProtocolGenerated
import XCTest
@testable import LumosProtocol

final class ReceiptsConformanceTests: XCTestCase {

    private var root: URL { Vectors.repoRoot() }
    private let path = "test-vectors/chat/receipts.json"

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

    func testEveryRouteAndReceiptRoundTripsByteForByte() throws {
        let file = try Vectors.load(path, root: root)
        let routes = try Vectors.array(file, "routes")
        XCTAssertFalse(routes.isEmpty, "an empty section proves nothing")
        for c in routes {
            let encoded = try Vectors.hex(c, "encoded_hex")
            let r = try RelayRoute.decode(encoded)
            let rid = try Vectors.hex(c, "routing_id_hex")
            let cap = try Vectors.hex(c, "capability_hex")
            XCTAssertEqual(r.routingID, rid)
            XCTAssertEqual(r.capability, cap)
            XCTAssertEqual(r.encoded, encoded)
        }
        for c in try Vectors.array(file, "receipts") {
            let encoded = try Vectors.hex(c, "encoded_hex")
            let r = try Receipt.decode(encoded)
            let kind = try Vectors.int(c, "kind")
            let count = try Vectors.stringArray(c, "acked_hex").count
            let rid = try Vectors.hex(c, "receipt_id_hex")
            let again = try r.encode()
            XCTAssertEqual(Int(r.kind.rawValue), kind)
            XCTAssertEqual(r.acked.count, count)
            XCTAssertEqual(r.id, rid)
            XCTAssertEqual(again, encoded)
        }
    }

    func testEveryNegativeEarnsItsExactCode() throws {
        let file = try Vectors.load(path, root: root)
        for c in try Vectors.array(file, "route_negatives") {
            let bytes = try Vectors.hex(c, "encoded_hex")
            let want = try Vectors.string(c, "expected_error")
            let name = try Vectors.string(c, "name")
            XCTAssertEqual(code { _ = try RelayRoute.decode(bytes) }, want, name)
        }
        for c in try Vectors.array(file, "receipt_negatives") {
            let bytes = try Vectors.hex(c, "encoded_hex")
            let want = try Vectors.string(c, "expected_error")
            let name = try Vectors.string(c, "name")
            XCTAssertEqual(code { _ = try Receipt.decode(bytes) }, want, name)
        }
    }

    func testTheRecordedSessionReplaysAndEveryRelabelFails() throws {
        let s = try Vectors.object(try Vectors.load(path, root: root), "session")
        let steps = try Vectors.array(s, "steps")
        let full = try ReceiptReplay(s)
        try full.run(steps.count)
        let relabels = try Vectors.array(s, "relabels")
        XCTAssertFalse(relabels.isEmpty)
        for rl in relabels {
            let target = try Vectors.int(rl, "message")
            var stop = -1
            for (i, st) in steps.enumerated() where stop < 0 {
                if try Vectors.string(st, "op") == "open", try Vectors.int(st, "message") == target {
                    stop = i
                }
            }
            let r = try ReceiptReplay(s)
            try r.run(stop)
            let cls = try XCTUnwrap(MessageClass(rawValue: UInt8(try Vectors.int(rl, "delivered_class"))))
            let id = try Vectors.hex(rl, "delivered_id_hex")
            let want = try Vectors.string(rl, "expected_error")
            let name = try Vectors.string(rl, "name")
            let at = stop
            XCTAssertEqual(code { _ = try r.open(at, cls, id) }, want, name)
        }
    }
}

/// The recorded session on fresh state.
private final class ReceiptReplay {
    private let s: [String: Any]
    private let a: [String: Any]
    private let b: [String: Any]
    private let matchID: Data
    private let now: Int64
    private let messages: [[String: Any]]
    private let steps: [[String: Any]]
    private var alice: DoubleRatchet
    private let prekey: RemoteText.PrekeyFields
    private var bob: DoubleRatchet?
    private var heardBack = false
    private var aliceKeys: [Data]
    private var bobKeys: [Data]

    init(_ s: [String: Any]) throws {
        self.s = s
        a = try Vectors.object(s, "alice")
        b = try Vectors.object(s, "bob")
        matchID = try Vectors.hex(s, "match_id_hex")
        now = Int64(try Vectors.int(s, "now"))
        messages = try Vectors.array(s, "messages")
        steps = try Vectors.array(s, "steps")
        aliceKeys = try Vectors.stringArray(a, "testonly_ratchet_turn_privs_hex").map { try XCTUnwrap(Hex.decode($0)) }
        bobKeys = try Vectors.stringArray(b, "testonly_ratchet_turn_privs_hex").map { try XCTUnwrap(Hex.decode($0)) }
        let bundle = X3DH.Bundle(identityEd: try Vectors.hex(b, "identity_ed_pub_hex"),
                                 identityDH: try Vectors.hex(b, "identity_dh_pub_hex"),
                                 identityDHSig: try Vectors.hex(b, "identity_dh_sig_hex"),
                                 spkID: UInt32(try Vectors.int(b, "spk_id")),
                                 spk: try Vectors.hex(b, "spk_pub_hex"), spkSig: try Vectors.hex(b, "spk_sig_hex"),
                                 opkID: UInt32(try Vectors.int(b, "opk_id")), opk: try Vectors.hex(b, "opk_pub_hex"))
        let started = try RemoteSession.initiate(
            own: try Self.identity(a), bundle: bundle, matchID: matchID,
            ephemeralPriv: try Vectors.hex(a, "testonly_ephemeral_priv_hex"),
            ratchetPriv: try Vectors.hex(a, "testonly_first_ratchet_priv_hex"))
        alice = started.0
        prekey = try XCTUnwrap(started.1)
    }

    private static func identity(_ p: [String: Any]) throws -> RemoteSession.Identity {
        RemoteSession.Identity(identityEd: try Vectors.hex(p, "identity_ed_pub_hex"),
                               identityDHPriv: try Vectors.hex(p, "testonly_identity_dh_priv_hex"),
                               identityDHSig: try Vectors.hex(p, "identity_dh_sig_hex"))
    }

    private func nextAlice() throws -> Data {
        guard !aliceKeys.isEmpty else { throw LumosCrypto.WireError(LumosErrors.errCryptoAeadAuthFailed, "exhausted") }
        return aliceKeys.removeFirst()
    }

    private func nextBob() throws -> Data {
        guard !bobKeys.isEmpty else { throw LumosCrypto.WireError(LumosErrors.errCryptoAeadAuthFailed, "exhausted") }
        return bobKeys.removeFirst()
    }

    private func seal(_ i: Int) throws {
        let m = messages[try Vectors.int(steps[i], "message")]
        let pt = try Vectors.hex(m, "plaintext_hex")
        let env: Data
        if try Vectors.int(m, "class") == Int(MessageClass.textMessage.rawValue) {
            env = try RemoteSession.seal(&alice, try RemoteText.decodePlaintext(pt), prekey: heardBack ? nil : prekey,
                                         now: now)
        } else {
            var state = try XCTUnwrap(bob)
            env = try Receipt.decode(pt).seal(&state, prekey: nil, now: now)
            bob = state
        }
        let want = try Vectors.string(m, "envelope_hex")
        XCTAssertEqual(Hex.encode(env), want, "seal must reproduce the Go envelope")
    }

    func open(_ i: Int, _ cls: MessageClass, _ id: Data) throws -> Data {
        let m = messages[try Vectors.int(steps[i], "message")]
        let e = try RemoteText.decodeEnvelope(try Vectors.hex(m, "envelope_hex"))
        if try Vectors.string(steps[i], "actor") == "bob" {
            var state: DoubleRatchet
            if let existing = bob {
                state = existing
            } else {
                state = try RemoteSession.respond(
                    own: try Self.identity(b),
                    keys: RemoteSession.ResponderKeys(spkPriv: try Vectors.hex(b, "testonly_spk_priv_hex"),
                                                      spkID: UInt32(try Vectors.int(b, "spk_id")),
                                                      opkPriv: try Vectors.hex(b, "testonly_opk_priv_hex"),
                                                      opkID: UInt32(try Vectors.int(b, "opk_id"))),
                    initiatorEd: try Vectors.hex(a, "identity_ed_pub_hex"), prekey: try XCTUnwrap(e.prekey),
                    matchID: matchID)
            }
            let p = try RemoteSession.open(&state, e, outerID: id, now: now, nextKey: nextBob)
            bob = state
            return p.encoded
        }
        heardBack = true
        if cls == .textMessage {
            return try RemoteSession.open(&alice, e, outerID: id, now: now, nextKey: nextAlice).encoded
        }
        return try Receipt.open(&alice, e, delivered: Receipt.Delivered(id: id, messageClass: cls), now: now,
                                nextKey: nextAlice).encode()
    }

    func run(_ stop: Int) throws {
        for i in 0..<stop {
            if try Vectors.string(steps[i], "op") == "seal" {
                try seal(i)
                continue
            }
            let m = messages[try Vectors.int(steps[i], "message")]
            let cls = try XCTUnwrap(MessageClass(rawValue: UInt8(try Vectors.int(m, "class"))))
            let got = try open(i, cls, try Vectors.hex(m, "message_id_hex"))
            let want = try Vectors.hex(m, "plaintext_hex")
            XCTAssertEqual(got, want, "open must yield the Go plaintext")
        }
    }
}
