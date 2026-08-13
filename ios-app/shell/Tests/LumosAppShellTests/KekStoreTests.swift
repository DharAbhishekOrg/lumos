/// Lumos - ios-app/shell / Software KEK behaviour and wrap-format negatives.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-NFR-SEC-003, SRS-DAT-002, TRS-CRY-011, TRS-CRY-013
/// ADRs: ADR-003, ADR-010
/// Threading: any
/// Notes: These tests exercise the boundary CI can run - the software tier.
/// The Secure Enclave test runs only where an enclave exists, which no CI VM
/// has: it XCTSkips there, the skip is visible in the report, and nothing
/// about hardware is claimed. Enclave behaviour on devices is owner-executed
/// evidence (WP-02 kickoff), never simulated.

import XCTest
@testable import LumosAppShell

/// Behavioural contract of the KEK boundary, on the tier CI can execute.
final class KekStoreTests: XCTestCase {

    // Spelled out in words on purpose: a hex-shaped fixture reads exactly like
    // a leaked credential to a keyword-plus-entropy scanner (TRS-REP-005).
    private let dataKey = Data("lumos test fixture data key 0032".utf8)
    private let aad = Data("lumos/local/test/v1".utf8)

    func testWrapUnwrapRoundtrips() throws {
        let kek = SoftwareKekStore()
        let wrapped = try kek.wrap(dataKey, aad: aad)
        XCTAssertEqual(try kek.unwrap(wrapped, aad: aad), dataKey)
    }

    func testProtectionTierIsHonest() {
        let kek = SoftwareKekStore()
        XCTAssertEqual(kek.protection, .softwareFallback,
                       "the software tier must never report itself as hardware (threat model LT-05)")
        XCTAssertFalse(kek.protection.isHardwareBacked)
    }

    func testSameKeyWrappedTwiceProducesDifferentBlobs() throws {
        let kek = SoftwareKekStore()
        let first = try kek.wrap(dataKey, aad: aad)
        let second = try kek.wrap(dataKey, aad: aad)
        XCTAssertNotEqual(first, second,
                          "a repeated nonce under one key is the GCM failure mode TRS-CRY-011 forbids")
    }

    func testWrappedBlobDoesNotContainThePlaintext() throws {
        let kek = SoftwareKekStore()
        let wrapped = try kek.wrap(dataKey, aad: aad)
        XCTAssertNil(wrapped.range(of: dataKey),
                     "plaintext key bytes must not appear anywhere in the wrapped blob")
    }

    func testTamperingAnywhereIsRejected() throws {
        let kek = SoftwareKekStore()
        let wrapped = try kek.wrap(dataKey, aad: aad)
        for offset in [0, 5, wrapped.count / 2, wrapped.count - 1] {
            var bent = wrapped
            bent[bent.startIndex + offset] ^= 0x01
            XCTAssertThrowsError(try kek.unwrap(bent, aad: aad),
                                 "tampered byte at offset \(offset) must be rejected")
        }
    }

    func testWrongAADIsRejected() throws {
        let kek = SoftwareKekStore()
        let wrapped = try kek.wrap(dataKey, aad: aad)
        XCTAssertThrowsError(try kek.unwrap(wrapped, aad: Data("lumos/local/other/v1".utf8))) { error in
            // TRS-CRY-013: one indistinguishable failure for every cause.
            XCTAssertEqual(error as? KekError, KekError.unwrapFailed)
        }
    }

    func testDestroyedStoreRefusesEverything() throws {
        let kek = SoftwareKekStore()
        let wrapped = try kek.wrap(dataKey, aad: aad)
        kek.destroy()
        XCTAssertTrue(kek.isDestroyed)
        XCTAssertThrowsError(try kek.unwrap(wrapped, aad: aad),
                             "crypto-erasure: a destroyed KEK must make every blob it wrapped unopenable")
        XCTAssertThrowsError(try kek.wrap(dataKey, aad: aad))
    }

    func testUnknownFormatVersionFailsClosed() throws {
        let kek = SoftwareKekStore()
        var wrapped = try kek.wrap(dataKey, aad: aad)
        wrapped[wrapped.startIndex] = 2
        XCTAssertThrowsError(try kek.unwrap(wrapped, aad: aad),
                             "an unknown wrap-format version is refused, never guessed at")
    }

    /// Not an XCTSkip, deliberately. SwiftPM's xUnit reporter writes a skipped
    /// test as a bare passing testcase - the traceability collector cannot tell
    /// it from a pass, which is the WP-01 §5.1 evidence-fidelity defect in a
    /// new coat. So this test asserts a real property on EVERY host: where an
    /// enclave exists, the store round-trips; where none exists, the store must
    /// REFUSE TO EXIST rather than silently degrade to software - which is the
    /// capability-gate honesty rule (threat model LT-05) stated as a test.
    func testSecureEnclaveStoreEitherWorksOrRefusesToExist() throws {
        if SecureEnclaveKekStore.isAvailable {
            let kek = try SecureEnclaveKekStore()
            XCTAssertEqual(kek.protection, .secureEnclave)
            let wrapped = try kek.wrap(dataKey, aad: aad)
            XCTAssertEqual(try kek.unwrap(wrapped, aad: aad), dataKey)
            kek.destroy()
            XCTAssertThrowsError(try kek.unwrap(wrapped, aad: aad))
        } else {
            XCTAssertThrowsError(try SecureEnclaveKekStore(),
                                 "off-hardware the enclave store must fail loudly, never degrade to software")
        }
    }
}
