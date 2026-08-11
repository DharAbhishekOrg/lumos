/// Lumos - LumosProtocolConformance / Crypto, adv, media, config and voucher conformance.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-TST-001, TRS-CRY-003, TRS-CRY-008, TRS-CRY-009, TRS-CRY-014, TRS-CRY-015, SRS-NFR-CMP-004, SRS-IDN-005
/// ADRs: ADR-001, ADR-002, ADR-005, ADR-008
/// Threading: any
/// Notes: Each test RECOMPUTES its result with CryptoKit from the recorded
/// inputs and compares against the committed bytes; it never reads the expected
/// value back. The recorded TESTONLY private halves make that possible from
/// outside the generating repository - a runner that re-derived the generator's
/// seeds would be agreeing with itself rather than with the protocol.

import XCTest
import Foundation
@testable import LumosProtocol
import LumosProtocolGenerated

final class CryptoConformanceTests: XCTestCase {

    private var root: URL { Vectors.repoRoot() }
    private let zeroSalt = Data(repeating: 0, count: 32)

    private func utf8(_ s: String) -> Data { Data(s.utf8) }

    /// Expands a message key into (key, nonce) as the register defines.
    private func messageKeyMaterial(_ messageKey: Data) -> (key: Data, nonce: Data) {
        let okm = LumosCrypto.hkdf(
            ikm: messageKey, salt: zeroSalt,
            info: utf8(LumosConstants.ratchetMessageKdfInfoValue),
            length: LumosCrypto.aeadKeySize + LumosCrypto.aeadNonceSize)
        return (Data(okm.prefix(LumosCrypto.aeadKeySize)),
                Data(okm.suffix(LumosCrypto.aeadNonceSize)))
    }

    func testAEADKnownAnswersAndTampers() throws {
        let file = try Vectors.load("test-vectors/crypto/aead_kat.json", root: root)

        for c in try Vectors.array(file, "positive") {
            let (key, nonce) = messageKeyMaterial(try Vectors.hex(c, "message_key_hex"))
            XCTAssertEqual(Hex.encode(key), try Vectors.string(c, "derived_key_hex"), "derived key")
            XCTAssertEqual(Hex.encode(nonce), try Vectors.string(c, "nonce_hex"), "derived nonce")

            let ct = try LumosCrypto.aeadSeal(
                key: key, nonce: nonce,
                plaintext: try Vectors.hex(c, "plaintext_hex"),
                aad: try Vectors.hex(c, "aad_hex"))
            XCTAssertEqual(Hex.encode(ct), try Vectors.string(c, "ciphertext_hex"), "ciphertext")

            let back = try LumosCrypto.aeadOpen(
                key: key, nonce: nonce,
                ciphertext: try Vectors.hex(c, "ciphertext_hex"),
                aad: try Vectors.hex(c, "aad_hex"))
            XCTAssertEqual(Hex.encode(back), try Vectors.string(c, "plaintext_hex"), "roundtrip")
        }

        var tampers = 0
        for c in try Vectors.array(file, "tamper") {
            let (key, _) = messageKeyMaterial(try Vectors.hex(c, "message_key_hex"))
            do {
                _ = try LumosCrypto.aeadOpen(
                    key: key, nonce: try Vectors.hex(c, "nonce_hex"),
                    ciphertext: try Vectors.hex(c, "ciphertext_hex"),
                    aad: try Vectors.hex(c, "aad_hex"))
                XCTFail("tamper case \(try Vectors.string(c, "name")) authenticated")
            } catch let e as LumosCrypto.WireError {
                XCTAssertEqual(e.code, try Vectors.string(c, "expected_error"))
            }
            tampers += 1
        }
        XCTAssertGreaterThanOrEqual(tampers, 3, "expected the header-AAD, ciphertext and tag tampers")
    }

    func testSafetyNumberReproducesDigitsAndEmoji() throws {
        let file = try Vectors.load("test-vectors/crypto/safety_number.json", root: root)
        XCTAssertEqual(try Vectors.int(file, "alphabet_size"), 64)
        guard let alphabet = file["alphabet"] as? [String] else {
            XCTFail("no alphabet"); return
        }
        XCTAssertEqual(alphabet.count, 64)
        for (i, glyph) in alphabet.enumerated() {
            XCTAssertEqual(glyph, LumosConstants.safetyNumberEmojiAlphabetGlyphs[i],
                           "generated alphabet differs from the committed vector at \(i)")
        }

        for c in try Vectors.array(file, "cases") {
            let a = try Vectors.hex(c, "identity_a_ed_pub_hex")
            let b = try Vectors.hex(c, "identity_b_ed_pub_hex")
            let (lo, hi) = compare(a, b) <= 0 ? (a, b) : (b, a)

            XCTAssertEqual(digits(lo) + digits(hi), try Vectors.string(c, "digits"),
                           "digits for \(try Vectors.string(c, "name"))")

            let combined = LumosCrypto.sha256(
                utf8(LumosConstants.safetyNumberKdfInfoValue) + lo + hi)
            guard let indices = c["emoji_indices"] as? [Int],
                  let emoji = c["emoji"] as? [String] else {
                XCTFail("missing emoji fields"); continue
            }
            for i in 0..<6 {
                let idx = Int(combined[combined.startIndex + i]) % 64
                XCTAssertEqual(idx, indices[i], "emoji index \(i)")
                XCTAssertEqual(LumosConstants.safetyNumberEmojiAlphabetGlyphs[idx], emoji[i],
                               "emoji glyph \(i)")
            }
        }
    }

    private func compare(_ a: Data, _ b: Data) -> Int {
        for i in 0..<min(a.count, b.count) {
            let d = Int(a[a.startIndex + i]) - Int(b[b.startIndex + i])
            if d != 0 { return d }
        }
        return a.count - b.count
    }

    private func digits(_ pub: Data) -> String {
        var digest = LumosCrypto.sha256(utf8(LumosConstants.safetyNumberKdfInfoValue) + pub)
        for _ in 1..<LumosConstants.safetyNumberIterationsDefault {
            digest = LumosCrypto.sha256(digest + pub)
        }
        var out = ""
        for chunk in 0..<6 {
            var v: UInt64 = 0
            for k in 0..<5 {
                v = (v << 8) | UInt64(digest[digest.startIndex + chunk * 5 + k])
            }
            out += String(format: "%05d", v % 100000)
        }
        return out
    }

    func testX3DHSharedSecretsAndTranscripts() throws {
        let file = try Vectors.load("test-vectors/crypto/x3dh_kat.json", root: root)
        let matchID = try Vectors.hex(file, "match_id_hex")

        for c in try Vectors.array(file, "cases") {
            let name = try Vectors.string(c, "name")

            let transcript = LumosCrypto.sha256(
                utf8(LumosConstants.x3dhTranscriptInfoValue)
                + (try Vectors.hex(c, "initiator_identity_ed_pub_hex"))
                + (try Vectors.hex(c, "responder_identity_ed_pub_hex"))
                + (try Vectors.hex(c, "initiator_identity_dh_pub_hex"))
                + (try Vectors.hex(c, "responder_identity_dh_pub_hex"))
                + LumosCrypto.be32(UInt32(try Vectors.int(c, "signed_prekey_id")))
                + LumosCrypto.be32(UInt32(truncatingIfNeeded: try Vectors.int(c, "one_time_prekey_id")))
                + Data([UInt8(LumosConstants.protocolVersionEncoded)])
                + matchID)
            XCTAssertEqual(Hex.encode(transcript), try Vectors.string(c, "transcript_ad_hex"),
                           "transcript for \(name)")

            let respEd = try Vectors.hex(c, "responder_identity_ed_pub_hex")
            XCTAssertTrue(LumosCrypto.ed25519Verify(
                publicKeyRaw: respEd,
                message: utf8(LumosConstants.x3dhIdentityBindingInfoValue)
                    + (try Vectors.hex(c, "responder_identity_dh_pub_hex")),
                signature: try Vectors.hex(c, "identity_dh_sig_hex")),
                "identity-DH binding signature for \(name)")
            XCTAssertTrue(LumosCrypto.ed25519Verify(
                publicKeyRaw: respEd,
                message: utf8(LumosConstants.x3dhPrekeySigningInfoValue)
                    + LumosCrypto.be32(UInt32(try Vectors.int(c, "signed_prekey_id")))
                    + (try Vectors.hex(c, "responder_signed_prekey_pub_hex")),
                signature: try Vectors.hex(c, "signed_prekey_sig_hex")),
                "signed-prekey signature for \(name)")

            let initIDDH = try Vectors.hex(c, "testonly_initiator_identity_dh_priv_hex")
            let initEph = try Vectors.hex(c, "testonly_initiator_ephemeral_priv_hex")
            let respIDDH = try Vectors.hex(c, "responder_identity_dh_pub_hex")
            let respSPK = try Vectors.hex(c, "responder_signed_prekey_pub_hex")

            let dh1 = try LumosCrypto.x25519(privateKeyRaw: initIDDH, publicKeyRaw: respSPK)
            let dh2 = try LumosCrypto.x25519(privateKeyRaw: initEph, publicKeyRaw: respIDDH)
            let dh3 = try LumosCrypto.x25519(privateKeyRaw: initEph, publicKeyRaw: respSPK)
            var dh4 = Data()
            if Vectors.bool(c, "with_one_time_prekey") {
                dh4 = try LumosCrypto.x25519(
                    privateKeyRaw: initEph,
                    publicKeyRaw: try Vectors.hex(c, "responder_one_time_prekey_pub_hex"))
            }

            let f = Data(repeating: UInt8(LumosConstants.x3dhFPrefixFillByte),
                         count: LumosConstants.x3dhFPrefixLen)
            let sk = LumosCrypto.hkdf(
                ikm: f + dh1 + dh2 + dh3 + dh4, salt: zeroSalt,
                info: utf8(LumosConstants.x3dhKdfInfoValue), length: 32)
            XCTAssertEqual(Hex.encode(sk), try Vectors.string(c, "shared_secret_hex"),
                           "shared secret for \(name)")
        }

        var negatives = 0
        for c in try Vectors.array(file, "negatives") {
            let respEd = try Vectors.hex(c, "responder_identity_ed_pub_hex")
            let idOK = LumosCrypto.ed25519Verify(
                publicKeyRaw: respEd,
                message: utf8(LumosConstants.x3dhIdentityBindingInfoValue)
                    + (try Vectors.hex(c, "responder_identity_dh_pub_hex")),
                signature: try Vectors.hex(c, "identity_dh_sig_hex"))
            let spkOK = LumosCrypto.ed25519Verify(
                publicKeyRaw: respEd,
                message: utf8(LumosConstants.x3dhPrekeySigningInfoValue)
                    + LumosCrypto.be32(UInt32(try Vectors.int(c, "signed_prekey_id")))
                    + (try Vectors.hex(c, "responder_signed_prekey_pub_hex")),
                signature: try Vectors.hex(c, "signed_prekey_sig_hex"))
            XCTAssertTrue(!idOK || !spkOK,
                          "negative bundle \(try Vectors.string(c, "name")) verified; must fail closed")
            negatives += 1
        }
        XCTAssertGreaterThanOrEqual(negatives, 2, "expected both bundle-signature negatives")
    }

    func testAdvertisementScheduleAndNegatives() throws {
        let file = try Vectors.load("test-vectors/adv/ble_advertisement.json", root: root)
        let kAdv = try Vectors.hex(file, "k_adv_hex")
        let rotation = try Vectors.int(file, "eid_rotation_sec")

        for c in try Vectors.array(file, "schedule") {
            let epoch = try Vectors.int(c, "epoch")
            XCTAssertEqual(epoch, try Vectors.int(c, "unix_seconds") / rotation, "epoch arithmetic")

            let eid = LumosCrypto.hkdf(
                ikm: kAdv, salt: zeroSalt,
                info: utf8(LumosConstants.advEIDKdfInfoValue) + LumosCrypto.be64(UInt64(epoch)),
                length: LumosConstants.advV1LayoutEIDLen)
            XCTAssertEqual(Hex.encode(eid), try Vectors.string(c, "eid_hex"), "EID")

            let modeFlags = UInt8(try Vectors.int(c, "mode_flags"))
            let mac = LumosCrypto.hmacSHA256(
                key: kAdv,
                data: utf8(LumosConstants.advAuthTagInfoValue)
                    + Data([1, modeFlags]) + eid + LumosCrypto.be64(UInt64(epoch)))
            let tag = Data(mac.prefix(LumosConstants.advAuthTagLenDefault))
            XCTAssertEqual(Hex.encode(tag), try Vectors.string(c, "auth_tag_hex"), "authTag")

            let advV1 = Data([1, modeFlags]) + eid + tag
            XCTAssertEqual(advV1.count, LumosConstants.advV1LayoutTotalLen, "adv_v1 width")
            XCTAssertEqual(Hex.encode(advV1), try Vectors.string(c, "adv_v1_hex"), "adv_v1")
        }

        var negatives = 0
        for c in try Vectors.array(file, "negatives") {
            let payload = try Vectors.hex(c, "adv_v1_hex")
            let epoch = try Vectors.int(c, "epoch")
            XCTAssertFalse(advVerifies(kAdv: kAdv, payload: payload, epoch: epoch),
                           "negative advertisement \(try Vectors.string(c, "name")) verified")
            negatives += 1
        }
        XCTAssertGreaterThanOrEqual(negatives, 5, "expected the full adv negative set")
    }

    private func advVerifies(kAdv: Data, payload: Data, epoch: Int) -> Bool {
        guard payload.count == LumosConstants.advV1LayoutTotalLen else { return false }
        let version = payload[payload.startIndex]
        let modeFlags = payload[payload.startIndex + 1]
        guard version == 1, modeFlags & 0xFE == 0 else { return false }
        let eidLen = LumosConstants.advV1LayoutEIDLen
        let eid = Data(payload[(payload.startIndex + 2)..<(payload.startIndex + 2 + eidLen)])
        let got = Data(payload[(payload.startIndex + 2 + eidLen)...])
        let mac = LumosCrypto.hmacSHA256(
            key: kAdv,
            data: utf8(LumosConstants.advAuthTagInfoValue)
                + Data([version, modeFlags]) + eid + LumosCrypto.be64(UInt64(epoch)))
        return Data(mac.prefix(LumosConstants.advAuthTagLenDefault)) == got
    }

    func testMediaObjectPipeline() throws {
        let file = try Vectors.load("test-vectors/media/object_pipeline.json", root: root)
        let sessionKey = try Vectors.hex(file, "session_key_hex")
        let objectKey = try Vectors.hex(file, "object_key_hex")
        let objectID = try Vectors.hex(file, "object_id_hex")
        let plaintext = try Vectors.hex(file, "plaintext_hex")
        let chunkSize = try Vectors.int(file, "chunk_size")

        XCTAssertEqual(Hex.encode(LumosCrypto.sha256(plaintext)),
                       try Vectors.string(file, "plaintext_sha256_hex"), "plaintext digest")

        let objOKM = LumosCrypto.hkdf(
            ikm: objectKey, salt: zeroSalt,
            info: utf8(LumosConstants.mediaObjectKeyInfoValue),
            length: LumosCrypto.aeadKeySize + LumosCrypto.aeadNonceSize)
        let objKey = Data(objOKM.prefix(LumosCrypto.aeadKeySize))
        let objNonce = Data(objOKM.suffix(LumosCrypto.aeadNonceSize))

        let ct = try LumosCrypto.aeadSeal(key: objKey, nonce: objNonce,
                                          plaintext: plaintext, aad: objectID)
        XCTAssertEqual(Hex.encode(ct), try Vectors.string(file, "ciphertext_hex"), "object ciphertext")

        let wrapOKM = LumosCrypto.hkdf(
            ikm: sessionKey, salt: zeroSalt,
            info: utf8(LumosConstants.mediaObjectKeyWrapInfoValue) + objectID,
            length: LumosCrypto.aeadKeySize + LumosCrypto.aeadNonceSize)
        let wrapped = try LumosCrypto.aeadSeal(
            key: Data(wrapOKM.prefix(LumosCrypto.aeadKeySize)),
            nonce: Data(wrapOKM.suffix(LumosCrypto.aeadNonceSize)),
            plaintext: objectKey, aad: objectID)
        XCTAssertEqual(Hex.encode(wrapped), try Vectors.string(file, "wrapped_object_key_hex"),
                       "wrapped object key")

        let chunkCount = try Vectors.int(file, "chunk_count")
        XCTAssertEqual((ct.count + chunkSize - 1) / chunkSize, chunkCount, "chunk plan")

        var parts = [Int: Data]()
        for c in try Vectors.array(file, "chunks") {
            parts[try Vectors.int(c, "index")] = try Vectors.hex(c, "hex")
        }
        XCTAssertEqual(parts.count, chunkCount, "chunk count")
        var reassembled = Data()
        for i in 0..<chunkCount {
            guard let part = parts[i] else { XCTFail("missing chunk \(i)"); return }
            reassembled += part
        }
        XCTAssertEqual(Hex.encode(reassembled), Hex.encode(ct), "reassembly differs from the seal")

        let back = try LumosCrypto.aeadOpen(key: objKey, nonce: objNonce,
                                            ciphertext: reassembled, aad: objectID)
        XCTAssertEqual(Hex.encode(back), Hex.encode(plaintext), "decrypted object")
        XCTAssertEqual(Hex.encode(LumosCrypto.sha256(back)),
                       try Vectors.string(file, "plaintext_sha256_hex"), "digest after reassembly")

        for c in try Vectors.array(file, "ack_cases") {
            guard let received = c["received"] as? [Int] else { XCTFail("no received"); continue }
            var bitmap = Data(repeating: 0, count: (chunkCount + 7) / 8)
            for i in received {
                bitmap[i / 8] |= UInt8(1 << (i % 8))
            }
            XCTAssertEqual(Hex.encode(bitmap), try Vectors.string(c, "bitmap_hex"),
                           "bitmap for \(try Vectors.string(c, "name"))")
        }
    }

    func testConfigPackagesAndVouchers() throws {
        let valid = try Vectors.load("test-vectors/config/package_valid.json", root: root)
        let anchor = try Vectors.hex(valid, "trust_anchor_ed25519_pub_hex")

        for c in try Vectors.array(valid, "cases") {
            guard let payload = c["payload"] as? [String: Any] else { XCTFail("no payload"); continue }
            let canonical = try canonicalPackage(payload)
            XCTAssertEqual(canonical, try Vectors.string(c, "canonical_bytes"),
                           "canonical bytes for \(try Vectors.string(c, "name"))")
            XCTAssertEqual("sha256:" + Hex.encode(LumosCrypto.sha256(utf8(canonical))),
                           try Vectors.string(c, "payload_hash"), "payload hash")
            XCTAssertTrue(LumosCrypto.ed25519Verify(
                publicKeyRaw: anchor,
                message: utf8(LumosConstants.configSigningInfoValue) + utf8(canonical),
                signature: try Vectors.hex(c, "signature_hex")),
                "signature for \(try Vectors.string(c, "name"))")
        }

        let invalid = try Vectors.load("test-vectors/config/package_invalid.json", root: root)
        let anchor2 = try Vectors.hex(invalid, "trust_anchor_ed25519_pub_hex")
        var signatureNegatives = 0
        for c in try Vectors.array(invalid, "cases") {
            guard try Vectors.string(c, "expected_error") == "ERR_CONFIG_SIGNATURE_INVALID" else { continue }
            guard let payload = c["payload"] as? [String: Any] else { XCTFail("no payload"); continue }
            let canonical = try canonicalPackage(payload)
            XCTAssertFalse(LumosCrypto.ed25519Verify(
                publicKeyRaw: anchor2,
                message: utf8(LumosConstants.configSigningInfoValue) + utf8(canonical),
                signature: try Vectors.hex(c, "signature_hex")),
                "negative \(try Vectors.string(c, "name")) verified; must fail closed")
            signatureNegatives += 1
        }
        XCTAssertGreaterThanOrEqual(signatureNegatives, 3, "expected the signature negatives")

        let vouchers = try Vectors.load("test-vectors/voucher/voucher_tokens.json", root: root)
        let vAnchor = try Vectors.hex(vouchers, "trust_anchor_ed25519_pub_hex")
        var sawPositive = false
        var sawNegative = false
        for c in try Vectors.array(vouchers, "cases") {
            guard let payload = c["payload"] as? [String: Any] else { XCTFail("no payload"); continue }
            let canonical = try canonicalVoucher(payload)
            XCTAssertEqual(canonical, try Vectors.string(c, "canonical_bytes"),
                           "voucher canonical bytes for \(try Vectors.string(c, "name"))")
            let sigOK = LumosCrypto.ed25519Verify(
                publicKeyRaw: vAnchor,
                message: utf8(LumosConstants.voucherSigningInfoValue) + utf8(canonical),
                signature: try Vectors.hex(c, "signature_hex"))
            let expected = try Vectors.string(c, "expected_error")
            if expected.isEmpty {
                XCTAssertTrue(sigOK, "redeemable voucher failed verification")
                sawPositive = true
            } else {
                sawNegative = true
                if expected == "ERR_CONFIG_SIGNATURE_INVALID" {
                    XCTAssertFalse(sigOK, "tampered voucher verified")
                }
            }
        }
        XCTAssertTrue(sawPositive && sawNegative, "voucher vectors need both outcomes")
    }

    /// Rebuilds the canonical signing form: sorted key=value lines, newline-terminated.
    private func canonicalPackage(_ p: [String: Any]) throws -> String {
        var lines = [
            "constants_hash=" + (try Vectors.string(p, "constants_hash")),
            "environment=" + (try Vectors.string(p, "environment")),
            "valid_from=\(try Vectors.int(p, "valid_from"))",
            "valid_until=\(try Vectors.int(p, "valid_until"))",
            "version=\(try Vectors.int(p, "version"))",
        ]
        if let overrides = p["overrides"] as? [String: Any] {
            for (k, v) in overrides {
                lines.append("overrides.\(k)=\((v as? NSNumber)?.int64Value ?? 0)")
            }
        }
        if let switches = p["kill_switches"] as? [String: Any] {
            for (k, v) in switches {
                lines.append("kill_switches.\(k)=\(((v as? NSNumber)?.boolValue ?? false))")
            }
        }
        lines.sort()
        return lines.joined(separator: "\n") + "\n"
    }

    /// Rebuilds the voucher canonical signing form.
    private func canonicalVoucher(_ v: [String: Any]) throws -> String {
        var lines = [
            "nonce=" + (try Vectors.string(v, "nonce")),
            "perk_id=" + (try Vectors.string(v, "perk_id")),
            "revocable=\(Vectors.bool(v, "revocable"))",
            "valid_from=\(try Vectors.int(v, "valid_from"))",
            "valid_until=\(try Vectors.int(v, "valid_until"))",
            "venue_id=" + (try Vectors.string(v, "venue_id")),
            "voucher_id=" + (try Vectors.string(v, "voucher_id")),
        ]
        lines.sort()
        return lines.joined(separator: "\n") + "\n"
    }
}
