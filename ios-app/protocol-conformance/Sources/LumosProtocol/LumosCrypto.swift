/// Lumos - protocol-conformance / CryptoKit primitives for the conformance runner.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-CRY-001, TRS-CRY-003, TRS-CRY-011, TRS-TST-001
/// ADRs: ADR-001
/// Threading: any
/// Notes: Everything comes from CryptoKit - SHA-256, HMAC, HKDF, ChaChaPoly,
/// Curve25519 key agreement and signing - which is exactly the ADR-001 suite on
/// Apple platforms. CryptoKit's ChaChaPoly SealedBox keeps nonce, ciphertext and
/// tag separate, so the combined ciphertext||tag form the vectors record is
/// assembled explicitly here rather than relying on `.combined`, which prefixes
/// the nonce and would not match.

import CryptoKit
import Foundation
import LumosProtocolGenerated

/// CryptoKit-backed primitives matching ADR-001.
public enum LumosCrypto {

    /// ChaCha20-Poly1305 key size in bytes.
    public static let aeadKeySize = 32

    /// ChaCha20-Poly1305 (IETF) nonce size in bytes.
    public static let aeadNonceSize = 12

    /// A failure carrying a stable machine-readable code.
    public struct WireError: Error, Equatable, Sendable {
        /// The stable code from the generated registry.
        public let code: String
        /// Non-oracle detail for humans.
        public let detail: String

        /// Creates a wire error.
        /// - Parameters:
        ///   - code: a stable registry code
        ///   - detail: human-facing detail that must not distinguish causes
        public init(_ code: String, _ detail: String) {
            self.code = code
            self.detail = detail
        }
    }

    /// - Parameter data: input bytes
    /// - Returns: the SHA-256 digest
    public static func sha256(_ data: Data) -> Data {
        Data(SHA256.hash(data: data))
    }

    /// - Parameters:
    ///   - key: HMAC key
    ///   - data: message
    /// - Returns: the HMAC-SHA-256 tag
    public static func hmacSHA256(key: Data, data: Data) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: data, using: SymmetricKey(data: key)))
    }

    /// RFC 5869 HKDF-SHA-256.
    /// - Parameters:
    ///   - ikm: input keying material
    ///   - salt: salt; the Lumos KDFs use a zero-filled 32-byte salt
    ///   - info: context string
    ///   - length: output length in bytes
    /// - Returns: derived key material
    public static func hkdf(ikm: Data, salt: Data, info: Data, length: Int) -> Data {
        let key = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: ikm),
            salt: salt,
            info: info,
            outputByteCount: length
        )
        return key.withUnsafeBytes { Data($0) }
    }

    /// Seals with ChaCha20-Poly1305 (IETF), returning ciphertext||tag.
    /// - Parameters:
    ///   - key: 32-byte key
    ///   - nonce: 12-byte nonce
    ///   - plaintext: data to encrypt
    ///   - aad: associated data
    /// - Returns: ciphertext with the tag appended
    /// - Throws: on any cipher failure
    public static func aeadSeal(key: Data, nonce: Data, plaintext: Data, aad: Data) throws -> Data {
        let box = try ChaChaPoly.seal(
            plaintext,
            using: SymmetricKey(data: key),
            nonce: try ChaChaPoly.Nonce(data: nonce),
            authenticating: aad
        )
        return box.ciphertext + box.tag
    }

    /// Opens a ChaCha20-Poly1305 (IETF) ciphertext||tag.
    /// - Parameters:
    ///   - key: 32-byte key
    ///   - nonce: 12-byte nonce
    ///   - ciphertext: ciphertext with the tag appended
    ///   - aad: associated data
    /// - Returns: the plaintext
    /// - Throws: ``WireError`` with one stable code for every failure mode
    public static func aeadOpen(key: Data, nonce: Data, ciphertext: Data, aad: Data) throws -> Data {
        guard ciphertext.count >= 16 else {
            throw WireError(LumosErrors.errCryptoAeadAuthFailed, "aead rejected")
        }
        let split = ciphertext.count - 16
        let ct = ciphertext.prefix(split)
        let tag = ciphertext.suffix(16)
        do {
            let box = try ChaChaPoly.SealedBox(
                nonce: try ChaChaPoly.Nonce(data: nonce),
                ciphertext: ct,
                tag: tag
            )
            return try ChaChaPoly.open(box, using: SymmetricKey(data: key), authenticating: aad)
        } catch {
            // One code for every failure: distinguishing them would be an
            // oracle (TRS-CRY-013).
            throw WireError(LumosErrors.errCryptoAeadAuthFailed, "aead rejected")
        }
    }

    /// Performs an X25519 agreement.
    /// - Parameters:
    ///   - privateKeyRaw: raw 32-byte scalar
    ///   - publicKeyRaw: raw 32-byte peer public key
    /// - Returns: the 32-byte shared secret
    /// - Throws: on malformed key material
    public static func x25519(privateKeyRaw: Data, publicKeyRaw: Data) throws -> Data {
        let priv = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: privateKeyRaw)
        let pub = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: publicKeyRaw)
        let secret = try priv.sharedSecretFromKeyAgreement(with: pub)
        return secret.withUnsafeBytes { Data($0) }
    }

    /// Verifies an Ed25519 signature.
    /// - Parameters:
    ///   - publicKeyRaw: raw 32-byte public key
    ///   - message: the signed message
    ///   - signature: the 64-byte signature
    /// - Returns: whether the signature verifies
    public static func ed25519Verify(publicKeyRaw: Data, message: Data, signature: Data) -> Bool {
        guard let key = try? Curve25519.Signing.PublicKey(rawRepresentation: publicKeyRaw) else {
            // A malformed key is a verification failure, not a distinguishable
            // error.
            return false
        }
        return key.isValidSignature(signature, for: message)
    }

    /// - Parameter value: a big-endian value
    /// - Returns: four big-endian bytes
    public static func be32(_ value: UInt32) -> Data {
        Data([
            UInt8((value >> 24) & 0xFF), UInt8((value >> 16) & 0xFF),
            UInt8((value >> 8) & 0xFF), UInt8(value & 0xFF)
        ])
    }

    /// - Parameter value: a big-endian value
    /// - Returns: eight big-endian bytes
    public static func be64(_ value: UInt64) -> Data {
        var out = Data(capacity: 8)
        for shift in stride(from: 56, through: 0, by: -8) {
            out.append(UInt8((value >> UInt64(shift)) & 0xFF))
        }
        return out
    }
}

/// Hex helpers shared by the conformance tests.
public enum Hex {
    /// Decodes a lowercase hex string.
    /// - Parameter string: the hex text
    /// - Returns: the bytes, or nil when malformed
    public static func decode(_ string: String) -> Data? {
        guard string.count % 2 == 0 else { return nil }
        var out = Data(capacity: string.count / 2)
        var index = string.startIndex
        while index < string.endIndex {
            let next = string.index(index, offsetBy: 2)
            guard let byte = UInt8(string[index..<next], radix: 16) else { return nil }
            out.append(byte)
            index = next
        }
        return out
    }

    /// Encodes bytes as lowercase hex.
    /// - Parameter data: the bytes
    /// - Returns: the hex text
    public static func encode(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }
}
