/// Lumos - LumosProtocolConformance / The CryptoKit quarter of identity_binding.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-CRY-001, TRS-TST-001, SRS-IDN-001
/// ADRs: ADR-001, ADR-008
/// Threading: any
/// Notes: WP-03 S1 gives Android a libsodium identity backend, and this file is
/// what keeps Apple's side of ADR-008 in the same scheme: the committed family
/// is consumed by Go (generator language), the JCA (JVM), Lazysodium (the
/// emulator job) and - here - CryptoKit.
///
/// One platform honesty note, recorded where it bites: CryptoKit RANDOMISES
/// Ed25519 signing, so unlike the other three consumers this one cannot hold
/// its own signatures to the committed bytes. What it proves instead is every
/// property verification can see: the publics derive to the same bytes, the
/// committed signature verifies under the committed public, a CryptoKit-made
/// signature over the same message verifies too (round-trip), and every
/// negative is rejected. Byte-equality of signatures is already pinned three
/// ways; a fourth would add nothing CryptoKit is able to give.

import XCTest
import Foundation
import CryptoKit
@testable import LumosProtocol

final class IdentityBindingConformanceTests: XCTestCase {

    private var root: URL { Vectors.repoRoot() }

    /// The case name for assertion messages; non-throwing for autoclosures.
    private func caseName(_ c: [String: Any]) -> String {
        (c["name"] as? String) ?? "<unnamed case>"
    }

    func testPositivesDeriveVerifyAndRoundTrip() throws {
        let file = try Vectors.load("test-vectors/crypto/identity_binding.json", root: root)
        let positives = try Vectors.array(file, "positive")
        XCTAssertFalse(positives.isEmpty, "an empty family proves nothing")

        for c in positives {
            let name = caseName(c)
            let seed = try Vectors.hex(c, "testonly_ed_seed_hex")
            let scalar = try Vectors.hex(c, "testonly_x_scalar_hex")
            let message = try Vectors.hex(c, "binding_message_hex")
            let committedSig = try Vectors.hex(c, "binding_signature_hex")

            let signing = try Curve25519.Signing.PrivateKey(rawRepresentation: seed)
            XCTAssertEqual(Hex.encode(signing.publicKey.rawRepresentation),
                           try Vectors.string(c, "ed_public_hex"),
                           "\(name): ed_public must derive to the committed bytes")

            let agreement = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: scalar)
            XCTAssertEqual(Hex.encode(agreement.publicKey.rawRepresentation),
                           try Vectors.string(c, "x_public_hex"),
                           "\(name): x_public must derive to the committed bytes")

            XCTAssertTrue(signing.publicKey.isValidSignature(committedSig, for: message),
                          "\(name): the committed signature must verify under CryptoKit")

            // CryptoKit's randomised signing: round-trip rather than byte-pin.
            let own = try signing.signature(for: message)
            XCTAssertTrue(signing.publicKey.isValidSignature(own, for: message),
                          "\(name): CryptoKit must verify its own signature")
        }
    }

    func testEveryNegativeIsRejected() throws {
        let file = try Vectors.load("test-vectors/crypto/identity_binding.json", root: root)
        let info = Data((try Vectors.string(file, "binding_info")).utf8)
        let negatives = try Vectors.array(file, "negative")
        XCTAssertFalse(negatives.isEmpty, "a family without negatives proves nothing")

        for c in negatives {
            let name = caseName(c)
            let xPublic = try Vectors.hex(c, "x_public_hex")
            let message = info + xPublic
            let publicKey = try Curve25519.Signing.PublicKey(
                rawRepresentation: try Vectors.hex(c, "ed_public_hex"))
            XCTAssertFalse(
                publicKey.isValidSignature(try Vectors.hex(c, "binding_signature_hex"),
                                           for: message),
                "\(name): a forged or transplanted binding verified")
        }
    }
}
