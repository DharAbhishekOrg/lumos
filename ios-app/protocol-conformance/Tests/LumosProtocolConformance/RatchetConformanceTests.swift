/// Lumos - LumosProtocolConformance / The Double Ratchet, replayed on Swift.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-CRY-002, TRS-CRY-006, TRS-CRY-007, TRS-CRY-013, TRS-TST-001
/// ADRs: ADR-001, ADR-008
/// Threading: test-only
/// Notes: The Java suite on this platform: every committed sequence decrypts
/// byte-for-byte using the RECORDED turn keys, the MAX_SKIP negative fails
/// with its exact code before storing anything, a used key is gone, a bent
/// header fails, and a skipped key is consumed once and expires.

import CryptoKit
import Foundation
import LumosProtocolGenerated
import XCTest
@testable import LumosProtocol

final class RatchetConformanceTests: XCTestCase {

    private var root: URL { Vectors.repoRoot() }
    private let path = "test-vectors/crypto/ratchet_sequences.json"
    private let now: Int64 = 1_700_000_000

    /// Replays a recorded list of turn scalars, as the Go consumer does.
    private final class Recorded {
        private var keys: [Data]
        init(_ keys: [Data]) { self.keys = keys }
        func next() throws -> Data {
            guard !keys.isEmpty else {
                throw LumosCrypto.WireError(LumosErrors.errCryptoAeadAuthFailed, "recorded turn keys exhausted")
            }
            return keys.removeFirst()
        }
    }

    private func recorded(_ file: [String: Any], _ field: String) throws -> Recorded {
        Recorded(try Vectors.stringArray(file, field).map { try XCTUnwrap(Hex.decode($0)) })
    }

    private func pair(_ file: [String: Any]) throws -> (DoubleRatchet, DoubleRatchet) {
        let sk = try Vectors.hex(file, "shared_secret_hex")
        let ad = try Vectors.hex(file, "associated_data_hex")
        let respPriv = try Vectors.hex(file, "testonly_responder_ratchet_priv_hex")
        let respPub = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: respPriv).publicKey.rawRepresentation
        let alice = try DoubleRatchet.initiator(
            sk: sk, ad: ad, remoteRatchetPub: respPub,
            ownRatchetPriv: try Vectors.hex(file, "testonly_initiator_ratchet_priv_hex"))
        let bob = try DoubleRatchet.responder(sk: sk, ad: ad, ownRatchetPriv: respPriv)
        return (alice, bob)
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

    func testCommittedSequencesDecryptByteForByte() throws {
        let file = try Vectors.load(path, root: root)
        let sequences = try Vectors.array(file, "sequences")
        XCTAssertFalse(sequences.isEmpty, "an empty sequence set proves nothing")
        for seq in sequences {
            let name = try Vectors.string(seq, "name")
            var (alice, bob) = try pair(file)
            let kpA = try recorded(file, "testonly_initiator_dh_turn_privs_hex")
            let kpB = try recorded(file, "testonly_responder_dh_turn_privs_hex")
            for msg in try Vectors.array(seq, "messages") {
                let h = try DoubleRatchet.Header.parse(try Vectors.hex(msg, "header_hex"))
                let ct = try Vectors.hex(msg, "ciphertext_hex")
                let pt: Data
                if try Vectors.string(msg, "direction") == "a2b" {
                    pt = try bob.decrypt(h, ct, now: now, nextKey: kpB.next)
                } else {
                    pt = try alice.decrypt(h, ct, now: now, nextKey: kpA.next)
                }
                XCTAssertEqual(Hex.encode(pt), try Vectors.string(msg, "plaintext_hex"), "\(name): plaintext")
            }
        }
    }

    func testTheMaxSkipNegativeFailsWithItsExactCode() throws {
        let file = try Vectors.load(path, root: root)
        for neg in try Vectors.array(file, "negatives") {
            var (alice, bob) = try pair(file)
            var last: (DoubleRatchet.Header, Data)?
            let gap = try Vectors.int(neg, "gap")
            for _ in 0...gap {
                last = try alice.encrypt(Data("x".utf8), now: now)
            }
            let (h, ct) = try XCTUnwrap(last)
            let kpB = try recorded(file, "testonly_responder_dh_turn_privs_hex")
            XCTAssertEqual(code { _ = try bob.decrypt(h, ct, now: now, nextKey: kpB.next) },
                           try Vectors.string(neg, "expected_error"))
            XCTAssertEqual(bob.skippedCount, 0, "refused before any key is stored")
        }
    }

    func testAUsedKeyIsGoneAndATamperedHeaderFails() throws {
        let file = try Vectors.load(path, root: root)
        var (alice, bob) = try pair(file)
        let kpB = try recorded(file, "testonly_responder_dh_turn_privs_hex")
        let (h1, c1) = try alice.encrypt(Data([1]), now: now)
        XCTAssertEqual(try bob.decrypt(h1, c1, now: now, nextKey: kpB.next), Data([1]))
        XCTAssertEqual(code { _ = try bob.decrypt(h1, c1, now: now, nextKey: kpB.next) },
                       LumosErrors.errCryptoAeadAuthFailed, "a consumed key never decrypts twice")
        let (h2, c2) = try alice.encrypt(Data([2]), now: now)
        let bent = DoubleRatchet.Header(dh: h2.dh, pn: h2.pn + 1, n: h2.n)
        XCTAssertEqual(code { _ = try bob.decrypt(bent, c2, now: now, nextKey: kpB.next) },
                       LumosErrors.errCryptoAeadAuthFailed, "the header is authenticated")
    }

    func testSkippedKeysAreConsumedOnceAndExpire() throws {
        let file = try Vectors.load(path, root: root)
        var (alice, bob) = try pair(file)
        let kpB = try recorded(file, "testonly_responder_dh_turn_privs_hex")
        let m0 = try alice.encrypt(Data([0]), now: now)
        let m1 = try alice.encrypt(Data([1]), now: now)
        let m2 = try alice.encrypt(Data([2]), now: now)
        _ = try bob.decrypt(m2.0, m2.1, now: now, nextKey: kpB.next)
        XCTAssertEqual(bob.skippedCount, 2, "keys for 0 and 1 are stored")
        XCTAssertEqual(try bob.decrypt(m1.0, m1.1, now: now, nextKey: kpB.next), Data([1]))
        XCTAssertEqual(bob.skippedCount, 1, "a used skipped key is deleted")
        let late = now + DoubleRatchet.Bounds().skippedTTLSec + 1
        XCTAssertEqual(code { _ = try bob.decrypt(m0.0, m0.1, now: late, nextKey: kpB.next) },
                       LumosErrors.errCryptoAeadAuthFailed, "an expired key is simply gone")
        XCTAssertEqual(bob.skippedCount, 0)
    }
}
