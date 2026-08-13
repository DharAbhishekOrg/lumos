/// Lumos - ios-app/shell / Identity generation, binding and crypto-erasure tests.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-IDN-001, SRS-NFR-SEC-002, SRS-NFR-SEC-003
/// ADRs: ADR-008, ADR-003, ADR-010
/// Threading: any
/// Notes: The software KEK stands in for the enclave; every property here is a
/// property of the DESIGN (binding, fail-closed verification, wrapped-only
/// privates, crypto-erasure), not of hardware. The binding message is also
/// pinned structurally against the generated constant, so a drift from the Go
/// construction shows up here before the cross-language vectors would say so.

import CryptoKit
import Foundation
import LumosProtocolGenerated
import XCTest
@testable import LumosAppShell

/// Properties of the generated two-key identity (ADR-008).
final class IdentityKeyTests: XCTestCase {

    func testBindingSignatureVerifies() throws {
        let record = try IdentityKeyGenerator.generate(kek: SoftwareKekStore())
        XCTAssertEqual(record.ed25519Public.count, 32)
        XCTAssertEqual(record.x25519Public.count, 32)
        XCTAssertTrue(IdentityKeyGenerator.verifyBinding(
            ed25519Public: record.ed25519Public,
            x25519Public: record.x25519Public,
            signature: record.bindingSignature),
            "the ADR-008 binding must verify for a freshly generated identity")
    }

    func testVerificationFailsClosedOnEveryTamperedInput() throws {
        let record = try IdentityKeyGenerator.generate(kek: SoftwareKekStore())

        var bentEd = record.ed25519Public
        bentEd[bentEd.startIndex + 3] ^= 0x01
        XCTAssertFalse(IdentityKeyGenerator.verifyBinding(
            ed25519Public: bentEd, x25519Public: record.x25519Public,
            signature: record.bindingSignature))

        var bentX = record.x25519Public
        bentX[bentX.startIndex + 7] ^= 0x01
        XCTAssertFalse(IdentityKeyGenerator.verifyBinding(
            ed25519Public: record.ed25519Public, x25519Public: bentX,
            signature: record.bindingSignature))

        var bentSig = record.bindingSignature
        bentSig[bentSig.startIndex + 11] ^= 0x01
        XCTAssertFalse(IdentityKeyGenerator.verifyBinding(
            ed25519Public: record.ed25519Public, x25519Public: record.x25519Public,
            signature: bentSig))

        XCTAssertFalse(IdentityKeyGenerator.verifyBinding(
            ed25519Public: Data(count: 5), x25519Public: record.x25519Public,
            signature: record.bindingSignature),
            "a malformed key is a verification failure, not a crash")
    }

    func testBindingMessageIsInfoThenRawKey() {
        let dhPublic = Data(repeating: 0x42, count: 32)
        let message = IdentityKeyGenerator.bindingMessage(x25519Public: dhPublic)
        let info = Data(LumosConstants.x3dhIdentityBindingInfoValue.utf8)
        XCTAssertEqual(message.prefix(info.count), info,
                       "the domain-separation info must lead the message, as in the Go construction")
        XCTAssertEqual(message.suffix(32), dhPublic,
                       "the raw DH public key must follow immediately")
        XCTAssertEqual(message.count, info.count + 32, "nothing else may enter the message")
    }

    func testTwoIdentitiesAreDistinct() throws {
        let kek = SoftwareKekStore()
        let first = try IdentityKeyGenerator.generate(kek: kek)
        let second = try IdentityKeyGenerator.generate(kek: kek)
        XCTAssertNotEqual(first.ed25519Public, second.ed25519Public,
                          "identity generation must draw from the CSPRNG, never repeat (SRS-NFR-SEC-002)")
        XCTAssertNotEqual(first.x25519Public, second.x25519Public)
    }

    func testPrivateKeysExistOnlyInsideTheWrappedEnvelope() throws {
        let kek = SoftwareKekStore()
        let record = try IdentityKeyGenerator.generate(kek: kek)

        let envelope = try kek.unwrap(record.wrappedPrivateKeys,
                                      aad: Data(IdentityKeyGenerator.envelopeAad.utf8))
        let keys = try IdentityKeyGenerator.decodeEnvelope(envelope)
        XCTAssertEqual(keys.ed25519Raw.count, 32)
        XCTAssertEqual(keys.x25519Raw.count, 32)

        XCTAssertNil(record.wrappedPrivateKeys.range(of: keys.ed25519Raw),
                     "wrapped blob must not contain the plaintext Ed25519 key")
        XCTAssertNil(record.wrappedPrivateKeys.range(of: keys.x25519Raw),
                     "wrapped blob must not contain the plaintext X25519 key")

        // The envelope's keys must actually BE the identity: rebuild the
        // signing key and check it produces the recorded public half.
        let signing = try Curve25519.Signing.PrivateKey(rawRepresentation: keys.ed25519Raw)
        XCTAssertEqual(signing.publicKey.rawRepresentation, record.ed25519Public)
    }

    func testDestroyingTheKekErasesTheIdentity() throws {
        let kek = SoftwareKekStore()
        let record = try IdentityKeyGenerator.generate(kek: kek)
        kek.destroy()
        XCTAssertThrowsError(
            try kek.unwrap(record.wrappedPrivateKeys, aad: Data(IdentityKeyGenerator.envelopeAad.utf8)),
            "crypto-erasure: without the KEK the identity's private half must be unrecoverable")
        XCTAssertEqual(record.protection, .softwareFallback,
                       "the record must carry the tier it was actually created under")
    }

    func testEnvelopeDecodingFailsClosed() {
        XCTAssertThrowsError(try IdentityKeyGenerator.decodeEnvelope(Data([2, 0, 1, 0xAA])),
                             "an unknown envelope version is refused")
        XCTAssertThrowsError(try IdentityKeyGenerator.decodeEnvelope(Data([1, 0, 64, 0xAA])),
                             "a truncated field is refused")
        let valid = IdentityKeyGenerator.encodeEnvelope(
            ed25519Raw: Data(count: 32), x25519Raw: Data(count: 32))
        XCTAssertThrowsError(try IdentityKeyGenerator.decodeEnvelope(valid + Data([0x00])),
                             "trailing bytes are refused")
    }
}
