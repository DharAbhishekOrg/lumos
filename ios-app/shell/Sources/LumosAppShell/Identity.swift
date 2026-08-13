/// Lumos - ios-app/shell / IdentityKeyRecord and generation per ADR-008.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-IDN-001, SRS-IDN-002, SRS-NFR-SEC-002, SRS-NFR-SEC-003
/// ADRs: ADR-001, ADR-008, ADR-003, ADR-010
/// Threading: serial-executor
/// Notes: The two-key identity of ADR-008: a Curve25519 signing pair (Ed25519)
/// and a separate key-agreement pair (X25519), the DH public key signed by the
/// Ed25519 identity under X3DH_IDENTITY_BINDING_INFO. The binding message is
/// byte-identical to the Go implementation (UTF-8(info) || raw DH public key)
/// and to the Android one; the constant comes from LumosProtocolGenerated and
/// is never copied (ADR-004). Both private keys exist at rest only inside one
/// KEK-wrapped envelope; there is no API that returns them, and the tests
/// assert the envelope is unopenable once the KEK is destroyed. Key material
/// comes from CryptoKit's key initialisers, which draw from the platform
/// CSPRNG (SRS-NFR-SEC-002). Peer identifiers derive from this material and
/// nothing else (SRS-IDN-002).

import CryptoKit
import Foundation
import LumosProtocolGenerated

/// The device's long-term identity in its encrypted at-rest form.
public struct IdentityKeyRecord: Sendable {
    /// The raw 32-byte Ed25519 identity public key - the sole root of trust.
    public let ed25519Public: Data
    /// The raw 32-byte X25519 identity-DH public key.
    public let x25519Public: Data
    /// The ADR-008 Ed25519 signature binding the identity-DH key.
    public let bindingSignature: Data
    /// The KEK-wrapped envelope holding both private keys.
    public let wrappedPrivateKeys: Data
    /// The KEK's recorded protection tier (threat model LT-05).
    public let protection: KeyProtection
}

/// Errors identity generation reports.
public enum IdentityError: Error, Sendable {
    /// The private-key envelope failed to encode or decode.
    case envelopeMalformed
}

/// Generates and binds the two-key device identity of ADR-008.
public enum IdentityKeyGenerator {

    /// Domain-separation context for the private-key envelope's AAD, mirroring
    /// the Android constant of the same value (TRS-CODE-007: one concept, one
    /// name). A local storage label, not a Sec. 23 wire constant.
    public static let envelopeAad = "lumos/local/identity-envelope/v1"

    /// The envelope version this build reads and writes.
    static let envelopeVersion: UInt8 = 1

    /// Raw Curve25519 key length.
    static let rawKeyLength = 32

    /// Generates a fresh identity and seals both private keys under the KEK.
    /// - Parameter kek: the store that wraps the private-key envelope
    /// - Returns: the at-rest identity record
    /// - Throws: on wrap failure
    public static func generate(kek: KekStore) throws -> IdentityKeyRecord {
        let signing = Curve25519.Signing.PrivateKey()
        let agreement = Curve25519.KeyAgreement.PrivateKey()

        let dhPublic = agreement.publicKey.rawRepresentation
        let binding = try signing.signature(for: bindingMessage(x25519Public: dhPublic))

        let envelope = encodeEnvelope(
            ed25519Raw: signing.rawRepresentation,
            x25519Raw: agreement.rawRepresentation)
        let wrapped = try kek.wrap(envelope, aad: Data(envelopeAad.utf8))

        return IdentityKeyRecord(
            ed25519Public: signing.publicKey.rawRepresentation,
            x25519Public: dhPublic,
            bindingSignature: binding,
            wrappedPrivateKeys: wrapped,
            protection: kek.protection)
    }

    /// Verifies the ADR-008 binding signature, failing closed.
    ///
    /// This is the check that must pass before the identity-DH key is ever
    /// used in a DH computation (ADR-008 condition 2). A malformed key or
    /// signature is a verification failure, not a distinguishable error
    /// (TRS-CRY-013).
    /// - Parameters:
    ///   - ed25519Public: the raw Ed25519 identity public key
    ///   - x25519Public: the raw X25519 identity-DH public key
    ///   - signature: the claimed binding signature
    /// - Returns: true only when the signature verifies
    public static func verifyBinding(ed25519Public: Data, x25519Public: Data, signature: Data) -> Bool {
        guard let key = try? Curve25519.Signing.PublicKey(rawRepresentation: ed25519Public) else {
            return false
        }
        return key.isValidSignature(signature, for: bindingMessage(x25519Public: x25519Public))
    }

    /// Builds the binding message, byte-identical to the Go implementation.
    /// - Parameter x25519Public: the raw identity-DH public key
    /// - Returns: UTF-8(X3DH_IDENTITY_BINDING_INFO) || key
    static func bindingMessage(x25519Public: Data) -> Data {
        var message = Data(LumosConstants.x3dhIdentityBindingInfoValue.utf8)
        message.append(x25519Public)
        return message
    }

    /// Encodes both raw private keys into one length-prefixed envelope,
    /// mirroring the Android PrivateKeyEnvelope framing.
    /// - Parameters:
    ///   - ed25519Raw: the Ed25519 private key's raw representation
    ///   - x25519Raw: the X25519 private key's raw representation
    /// - Returns: the plaintext envelope; the caller wraps it immediately
    static func encodeEnvelope(ed25519Raw: Data, x25519Raw: Data) -> Data {
        var out = Data([envelopeVersion])
        appendLengthPrefixed(&out, ed25519Raw)
        appendLengthPrefixed(&out, x25519Raw)
        return out
    }

    /// Decodes an envelope, failing closed on any structural surprise.
    /// - Parameter envelope: the plaintext envelope, as unwrapped from the KEK
    /// - Returns: both raw private keys
    /// - Throws: ``IdentityError/envelopeMalformed`` on version, truncation or
    ///   trailing-byte problems
    static func decodeEnvelope(_ envelope: Data) throws -> (ed25519Raw: Data, x25519Raw: Data) {
        guard envelope.first == envelopeVersion else { throw IdentityError.envelopeMalformed }
        var rest = envelope.dropFirst()
        let ed = try readLengthPrefixed(&rest)
        let x = try readLengthPrefixed(&rest)
        guard rest.isEmpty else { throw IdentityError.envelopeMalformed }
        return (ed, x)
    }

    /// Appends one big-endian length-prefixed field.
    /// - Parameters:
    ///   - out: the destination
    ///   - value: the field bytes
    private static func appendLengthPrefixed(_ out: inout Data, _ value: Data) {
        out.append(UInt8((value.count >> 8) & 0xFF))
        out.append(UInt8(value.count & 0xFF))
        out.append(value)
    }

    /// Reads one length-prefixed field, consuming it from the slice.
    /// - Parameter rest: the remaining envelope bytes
    /// - Returns: the field bytes
    /// - Throws: ``IdentityError/envelopeMalformed`` on truncation
    private static func readLengthPrefixed(_ rest: inout Data.SubSequence) throws -> Data {
        guard rest.count >= 2 else { throw IdentityError.envelopeMalformed }
        let high = Int(rest[rest.startIndex])
        let low = Int(rest[rest.index(after: rest.startIndex)])
        let length = (high << 8) | low
        rest = rest.dropFirst(2)
        guard rest.count >= length else { throw IdentityError.envelopeMalformed }
        let value = Data(rest.prefix(length))
        rest = rest.dropFirst(length)
        return value
    }
}
