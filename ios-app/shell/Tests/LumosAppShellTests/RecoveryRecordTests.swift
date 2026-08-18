/// Lumos - ios-app/shell / The RecoveryRecord against the cross-language vectors.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-ONB-007, SRS-DAT-004, TRS-CRY-001, TRS-CRY-013, TRS-TST-001
/// ADRs: ADR-001, ADR-003
/// Threading: any
/// Notes: The iOS third of the recovery conformance contract, run on the macOS
/// job. It consumes the same committed file the Go and Java runners do, and it
/// consumes ALL of it: the derivation known-answers, the sealed records in both
/// directions, and every negative case with the code it must be refused under.
///
/// No XCTSkip anywhere, and not merely as a style preference: SwiftPM's xUnit
/// writer records a skip as a bare passing testcase, so a skipped capability
/// gate is invisible to the traceability collector and reads as evidence.
/// TestSwiftTestsDoNotUseXCTSkip enforces that; this note records why.
///
/// ONE WARNING FOR WHOEVER SEES THIS TIME OUT. The inflation cases assert that a
/// record claiming absurd work factors is refused from its header ALONE. If that
/// guard regresses this test does not fail - it HANGS, or the process is killed,
/// because libsodium will honestly try to allocate the 8 GiB the header asked
/// for. A timeout here is the symptom of a real denial-of-service hole.

import Foundation
import LumosProtocol
import LumosProtocolGenerated
import XCTest
@testable import LumosAppShell

final class RecoveryRecordTests: XCTestCase {

    private var root: URL { Vectors.repoRoot() }
    private let vectorPath = "test-vectors/crypto/recovery_record.json"

    /// The case name for assertion messages. Non-throwing, because XCTAssert
    /// message parameters are non-throwing autoclosures.
    private func caseName(_ c: [String: Any]) -> String {
        (c["name"] as? String) ?? "<unnamed case>"
    }

    private func vectorParams(_ c: [String: Any]) throws -> RecoveryParams {
        let p = try Vectors.object(c, "params")
        return RecoveryParams(
            memoryKiB: UInt32(try Vectors.int(p, "memory_kib")),
            iterations: UInt32(try Vectors.int(p, "iterations")),
            parallelism: UInt8(try Vectors.int(p, "parallelism")))
    }

    /// The layout the other two platforms hard-code. A platform can only agree
    /// with a layout it was told; asserting it here means a disagreement is
    /// reported as a disagreement rather than as an unopenable record.
    func testFormatConstantsMatchTheVectorFile() throws {
        let file = try Vectors.load(vectorPath, root: root)
        let format = try Vectors.object(file, "format")

        XCTAssertEqual(try Vectors.int(format, "format_version"), Int(RecoveryRecord.formatVersion))
        XCTAssertEqual(try Vectors.int(format, "header_bytes"), RecoveryRecord.headerBytes)
        XCTAssertEqual(try Vectors.int(format, "authenticated_header_bytes"),
                       RecoveryRecord.authenticatedHeaderBytes)
        XCTAssertEqual(try Vectors.int(format, "nonce_bytes"), RecoveryRecord.nonceBytes)
        XCTAssertEqual(try Vectors.int(format, "tag_bytes"), RecoveryRecord.tagBytes)
        XCTAssertEqual(try Vectors.int(format, "salt_bytes"), LumosConstants.recoveryArgon2idSaltBytes)
        XCTAssertEqual(try Vectors.int(format, "key_bytes"), LumosConstants.recoveryArgon2idKeyBytes)
    }

    func testAcceptedBoundsMatchTheVectorFile() throws {
        let file = try Vectors.load(vectorPath, root: root)
        let limits = try Vectors.object(file, "limits")

        XCTAssertEqual(try Vectors.int(limits, "max_acceptable_memory_kib"),
                       Int(RecoveryParams.maxAcceptableMemoryKiB))
        XCTAssertEqual(try Vectors.int(limits, "max_acceptable_iterations"),
                       Int(RecoveryParams.maxAcceptableIterations))
        XCTAssertEqual(try Vectors.int(limits, "supported_parallelism"),
                       Int(RecoveryParams.supportedParallelism))
        // The single-lane rule replaces the register's parallelism minimum. That
        // is only safe while the register's minimum IS one lane; if it ever
        // moves, every record becomes unopenable and this is what says so.
        XCTAssertEqual(LumosConstants.recoveryArgon2idParallelismMin,
                       Int(RecoveryParams.supportedParallelism),
                       "the register's parallelism minimum is no longer the supported lane count")
    }

    /// Argon2id on its own. When a record will not open and nobody can say why,
    /// this separates "the KDF disagrees" from "the framing disagrees" - and it
    /// is the case that would catch a binding taking memory in the wrong unit,
    /// since crypto_pwhash wants bytes where the header carries KiB.
    ///
    /// non_ascii_passphrase is in this set on purpose. swift-sodium's own
    /// PWHash.hash would TRAP on it rather than fail, which is why this file's
    /// implementation calls crypto_pwhash directly.
    func testArgon2idAgreesWithTheReferenceDerivation() throws {
        let file = try Vectors.load(vectorPath, root: root)
        let cases = try Vectors.array(file, "derivation")
        XCTAssertFalse(cases.isEmpty, "no derivation cases")

        for c in cases {
            let derived = try RecoveryRecord.deriveKey(
                passphrase: try Vectors.hex(c, "passphrase_hex"),
                salt: try Vectors.hex(c, "salt_hex"),
                params: try vectorParams(c))
            XCTAssertEqual(Hex.encode(derived), try Vectors.string(c, "derived_key_hex"),
                           "derived key for \(caseName(c))")
        }
    }

    /// Both directions. Opening the committed record proves this build can read
    /// what the reference wrote; re-sealing the same inputs and reproducing the
    /// same bytes proves it can WRITE something the reference could read, which
    /// opening alone never shows.
    func testEveryCommittedRecordOpensAndReSeals() throws {
        let file = try Vectors.load(vectorPath, root: root)
        let cases = try Vectors.array(file, "positive")
        XCTAssertFalse(cases.isEmpty, "no positive cases")

        for c in cases {
            let passphrase = try Vectors.hex(c, "passphrase_hex")
            let expected = try Vectors.hex(c, "record_hex")
            let content = try Vectors.hex(c, "content_hex")

            XCTAssertEqual(try RecoveryRecord.open(passphrase: passphrase, record: expected),
                           content, "opened content for \(caseName(c))")

            let resealed = try RecoveryRecord.exportWith(
                passphrase: passphrase, content: content,
                salt: try Vectors.hex(c, "salt_hex"),
                nonce: try Vectors.hex(c, "nonce_hex"),
                params: try vectorParams(c))
            XCTAssertEqual(Hex.encode(resealed), try Vectors.string(c, "record_hex"),
                           "re-sealed record for \(caseName(c))")
            XCTAssertEqual(Hex.encode(resealed.prefix(RecoveryRecord.authenticatedHeaderBytes)),
                           try Vectors.string(c, "authenticated_header_hex"),
                           "authenticated header for \(caseName(c))")
            XCTAssertEqual(try RecoveryRecord.params(of: expected), try vectorParams(c),
                           "header parameters for \(caseName(c))")
        }
    }

    /// Every record that must be refused, with the code it must be refused
    /// under. See the file header before concluding a timeout here is flakiness.
    func testEveryNegativeVectorIsRefusedWithTheRightCode() throws {
        let file = try Vectors.load(vectorPath, root: root)
        let cases = try Vectors.array(file, "negative")
        XCTAssertFalse(cases.isEmpty, "no negative cases")
        var families = Set<String>()

        for c in cases {
            families.insert(try Vectors.string(c, "family"))
            let expected = try Vectors.string(c, "expected_error")
            let passphrase = try Vectors.hex(c, "passphrase_hex")
            let record = try Vectors.hex(c, "record_hex")
            do {
                _ = try RecoveryRecord.open(passphrase: passphrase, record: record)
                XCTFail("the record was ACCEPTED: \(caseName(c))")
            } catch let error as RecoveryError {
                XCTAssertEqual(error.code, expected, "error code for \(caseName(c))")
            }
        }

        // Both attacks the format is shaped around must actually be present. A
        // vector file that lost its inflation cases would leave this green while
        // testing half of what the record defends against.
        XCTAssertTrue(families.contains("downgrade"), "the vector family carries no downgrade cases")
        XCTAssertTrue(families.contains("inflation"), "the vector family carries no inflation cases")
    }

    /// TRS-CRY-013 as a property of the whole set rather than of one pair. On
    /// this platform the property is structural: `openFailed` carries no
    /// associated value, so there is nothing for a caller to read. The
    /// assertion is that every case which reaches authentication produces
    /// exactly that case, and never one that says more.
    func testOpeningFailuresCarryNoDistinguishingDetail() throws {
        let file = try Vectors.load(vectorPath, root: root)
        var seen = Set<String>()

        let openFailures = try Vectors.array(file, "negative").filter {
            (try? Vectors.string($0, "expected_error")) == LumosErrors.errCryptoRecoveryOpenFailed
        }
        for c in openFailures {
            do {
                _ = try RecoveryRecord.open(passphrase: try Vectors.hex(c, "passphrase_hex"),
                                            record: try Vectors.hex(c, "record_hex"))
                XCTFail("the record was ACCEPTED: \(caseName(c))")
            } catch let error as RecoveryError {
                XCTAssertEqual(error, RecoveryError.openFailed,
                               "\(caseName(c)) reported more than the uniform failure")
                seen.insert(String(describing: error))
            }
        }
        XCTAssertEqual(seen.count, 1, "opening failures are distinguishable: \(seen)")
    }

    /// ADR-003 as a scan rather than as a claim: export content, then look for
    /// the passphrase and the plaintext in the whole blob. That is what a person
    /// holding the file could do, so it is what this does.
    func testAnExportedRecordLeaksNeitherPassphraseNorContent() throws {
        let passphrase = Data("TESTONLY-shell-passphrase".utf8)
        let content = Data("TESTONLY-shell-content-alias-and-block-list".utf8)
        let exported = try RecoveryRecord.export(passphrase: passphrase, content: content)

        XCTAssertNil(exported.range(of: passphrase), "the record contains the passphrase")
        XCTAssertNil(exported.range(of: content), "the record contains the plaintext content")
        XCTAssertEqual(try RecoveryRecord.open(passphrase: passphrase, record: exported), content)
    }

    /// A fresh salt and nonce per export are what stop two exports of the same
    /// content being linkable - and, far more seriously, what stop a
    /// (key, nonce) pair from ever repeating.
    func testTwoExportsOfTheSameContentDiffer() throws {
        let passphrase = Data("TESTONLY-shell-passphrase".utf8)
        let content = Data("TESTONLY-same-content-twice".utf8)
        XCTAssertNotEqual(try RecoveryRecord.export(passphrase: passphrase, content: content),
                          try RecoveryRecord.export(passphrase: passphrase, content: content))
    }

    /// The header is readable without a passphrase, which is what lets the UI
    /// warn about an old weak export before asking for one.
    func testDescribeReadsTheHeaderWithoutThePassphrase() throws {
        let exported = try RecoveryRecord.export(passphrase: Data("TESTONLY-shell-passphrase".utf8),
                                                 content: Data("TESTONLY-content".utf8))
        XCTAssertTrue(RecoveryRecord.describe(exported).contains("argon2id"))
        XCTAssertEqual(try RecoveryRecord.params(of: exported), RecoveryParams.registerDefaults)
        XCTAssertEqual(RecoveryRecord.describe(Data("not a record".utf8)), "unreadable recovery record")
    }
}
