/// Lumos - LumosProtocolConformance / A Double Ratchet session survives process death without reusing a key.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-RMT-017, TRS-CRY-002, TRS-CRY-006, TRS-CRY-007, TRS-TST-001
/// ADRs: ADR-001
/// Threading: test-only
/// Notes: The Swift side of the Java RatchetPersistenceTest, property for
/// property. "Process death" is export, drop the value, restore. A restored
/// session is indistinguishable from one that never restarted (same
/// ciphertexts for the same inputs and turn keys); a used message key stays
/// gone; skipped keys survive and are still used once; and bytes that are not
/// a state are refused with a stable code, never a crash.

import Foundation
import LumosProtocolGenerated
import XCTest
@testable import LumosProtocol

final class RatchetPersistenceTests: XCTestCase {

    private let now: Int64 = 1_700_000_000

    private func random32() -> Data {
        Data((0..<32).map { _ in UInt8.random(in: 0...255) })
    }

    private func utf8(_ s: String) -> Data {
        Data(s.utf8)
    }

    /// Replays a fixed list of turn keys, so twins can be compared.
    private final class Fixed {
        private var keys: [Data]
        init(_ keys: [Data]) { self.keys = keys }
        func next() -> Data { keys.removeFirst() }
    }

    private func pair(_ sk: Data, _ ad: Data, _ alicePriv: Data, _ bobPriv: Data) throws -> [DoubleRatchet] {
        [try DoubleRatchet.initiator(sk: sk, ad: ad, remoteRatchetPub: try X3DH.publicKey(of: bobPriv),
                                     ownRatchetPriv: alicePriv),
         try DoubleRatchet.responder(sk: sk, ad: ad, ownRatchetPriv: bobPriv)]
    }

    private func reborn(_ r: DoubleRatchet) throws -> DoubleRatchet {
        try DoubleRatchet.restoreState(r.exportState())
    }

    func testARestoredSessionIsIndistinguishableFromOneThatNeverRestarted() throws {
        let sk = random32(), ad = random32(), alicePriv = random32(), bobPriv = random32()
        let turns = [random32(), random32(), random32(), random32()]
        var steady = try pair(sk, ad, alicePriv, bobPriv)
        var restarted = try pair(sk, ad, alicePriv, bobPriv)
        let steadyBob = Fixed(turns), restartedBob = Fixed(turns)
        let steadyAlice = Fixed(Array(turns[2...])), restartedAlice = Fixed(Array(turns[2...]))

        let msg = utf8("round 0")
        // Alice -> Bob, with the restarted pair dying and coming back at every step.
        let a = try steady[0].encrypt(msg, now: now)
        let b = try restarted[0].encrypt(msg, now: now)
        XCTAssertEqual(a.1, b.1, "a restored sender seals exactly as before")
        restarted[0] = try reborn(restarted[0])
        XCTAssertEqual(msg, try steady[1].decrypt(a.0, a.1, now: now, nextKey: steadyBob.next))
        XCTAssertEqual(msg, try restarted[1].decrypt(b.0, b.1, now: now, nextKey: restartedBob.next))
        restarted[1] = try reborn(restarted[1])
        // Bob -> Alice, turning the DH ratchet.
        let c = try steady[1].encrypt(msg, now: now)
        let d = try restarted[1].encrypt(msg, now: now)
        XCTAssertEqual(c.1, d.1, "a restored responder seals exactly as before")
        restarted[1] = try reborn(restarted[1])
        XCTAssertEqual(msg, try steady[0].decrypt(c.0, c.1, now: now, nextKey: steadyAlice.next))
        XCTAssertEqual(msg, try restarted[0].decrypt(d.0, d.1, now: now, nextKey: restartedAlice.next))
        XCTAssertEqual(try reborn(restarted[0]).exportState(), restarted[0].exportState(),
                       "export -> restore -> export is the identity")
    }

    func testAKeyAlreadyUsedStaysGoneAfterARestart() throws {
        var p = try pair(random32(), random32(), random32(), random32())
        let first = try p[0].encrypt(utf8("once"), now: now)
        _ = try p[1].decrypt(first.0, first.1, now: now, nextKey: random32)
        var bobAfterDeath = try reborn(p[1])
        XCTAssertThrowsError(try bobAfterDeath.decrypt(first.0, first.1, now: now, nextKey: random32)) {
            XCTAssertEqual(($0 as? LumosCrypto.WireError)?.code, LumosErrors.errCryptoAeadAuthFailed,
                           "a used message key must not come back (SRS-RMT-017)")
        }
    }

    func testSkippedKeysSurviveARestartAndAreStillUsedOnce() throws {
        var p = try pair(random32(), random32(), random32(), random32())
        let m0 = try p[0].encrypt(utf8("zero"), now: now)
        let m1 = try p[0].encrypt(utf8("one"), now: now)
        let m2 = try p[0].encrypt(utf8("two"), now: now)
        XCTAssertEqual(utf8("two"), try p[1].decrypt(m2.0, m2.1, now: now, nextKey: random32))
        XCTAssertEqual(p[1].skippedCount, 2)
        var bob = try reborn(p[1])
        XCTAssertEqual(bob.skippedCount, 2, "skipped keys are part of the state")
        XCTAssertEqual(utf8("zero"), try bob.decrypt(m0.0, m0.1, now: now, nextKey: random32))
        var again = try reborn(bob)
        XCTAssertEqual(utf8("one"), try again.decrypt(m1.0, m1.1, now: now, nextKey: random32))
        XCTAssertEqual(again.skippedCount, 0)
        var last = try reborn(again)
        XCTAssertThrowsError(try last.decrypt(m0.0, m0.1, now: now, nextKey: random32))
    }

    func testBytesThatAreNotAStateAreRefusedWithAStableCode() throws {
        var p = try pair(random32(), random32(), random32(), random32())
        _ = try p[0].encrypt(utf8("x"), now: now)
        let good = p[0].exportState()
        var flag = good
        flag[1 + 32] = 7 // the dhr presence flag
        let bad: [Data] = [Data(), Data([9]), good.dropLast(), good + Data([0]), flag]
        for b in bad {
            XCTAssertThrowsError(try DoubleRatchet.restoreState(Data(b))) {
                XCTAssertEqual(($0 as? LumosCrypto.WireError)?.code, LumosErrors.errPayloadMalformed)
            }
        }
    }
}
