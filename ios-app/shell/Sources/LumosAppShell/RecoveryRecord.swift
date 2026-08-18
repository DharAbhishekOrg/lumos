/// Lumos - ios-app/shell / The ADR-003 content-only RecoveryRecord, on iOS.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-ONB-007, SRS-DAT-004, TRS-CRY-001, TRS-CRY-013
/// ADRs: ADR-001, ADR-003
/// Threading: any
/// Notes: The iOS half of the format the Go package in /protocol/crypto/recovery
/// defines, and the Java one in com.lumos.app.recovery implements. Byte-for-byte
/// identical output is a requirement rather than an aspiration: a record
/// exported on a phone has to open on the owner's next phone, which may be the
/// other platform entirely. test-vectors/crypto/recovery_record.json is what
/// holds the three together.
///
/// Two primitives, from two places, and the split is deliberate:
///
///   Argon2id            - libsodium, via swift-sodium's Clibsodium product.
///     CryptoKit has no Argon2 at any version, and hand-writing a memory-hard
///     KDF is not something anybody should do.
///   ChaCha20-Poly1305   - CryptoKit's ChaChaPoly, which is the IETF
///     construction with a 12-byte nonce, as it has been since WP-01. The threat
///     model's assumption 5 is that libsodium-EQUIVALENT primitives agree; the
///     golden vectors are what tests that, and they do.
///
/// THE PASSWORD IS REBOUND, NOT CONVERTED. crypto_pwhash takes `const char *`,
/// and the obvious Swift bridge - `passphrase.map(Int8.init)` - traps on any
/// byte above 0x7F. That is not a theoretical edge: it is every user whose
/// passphrase contains an accent. withMemoryRebound reinterprets the same bytes
/// and cannot trap, and non_ascii_passphrase in the vector family is the test
/// that keeps it that way.
///
/// The three design points of the format itself - the record key is independent
/// of the device keychain, the work factors travel with the record and are
/// authenticated, and the record carries content only - are stated in full in
/// the Go reference and are not repeated here.

import Clibsodium
import CryptoKit
import Foundation
import LumosProtocolGenerated

/// A recovery record could not be produced, or could not be opened.
public enum RecoveryError: Error, Equatable, Sendable {
    /// Every opening failure, indistinguishably (TRS-CRY-013).
    case openFailed
    /// The declared work factors are outside what this build accepts. Reported
    /// separately because it is a property of the FILE, readable without any
    /// passphrase attempt, so naming it discloses nothing.
    case paramsRejected(String)

    /// The stable register code this failure carries.
    public var code: String {
        switch self {
        case .openFailed: return LumosErrors.errCryptoRecoveryOpenFailed
        case .paramsRejected: return LumosErrors.errCryptoRecoveryParamsRejected
        }
    }
}

/// The Argon2id work factors of one record, and whether they are acceptable.
public struct RecoveryParams: Equatable, Sendable {
    /// The memory cost in KiB.
    public let memoryKiB: UInt32
    /// The time cost.
    public let iterations: UInt32
    /// The lane count.
    public let parallelism: UInt8

    /// The absolute memory ceiling, 1 GiB: two orders of magnitude above the
    /// register default and beyond any parameter set a real device would write.
    /// Deliberately NOT the register's maximum, which bounds what THIS build
    /// writes - a record from a later build with raised defaults must still open
    /// here or every upgrade strands its own backups.
    public static let maxAcceptableMemoryKiB: UInt32 = 1024 * 1024
    /// The absolute time-cost ceiling.
    public static let maxAcceptableIterations: UInt32 = 64
    /// The only Argon2id lane count this format admits.
    ///
    /// Portability, not cost. libsodium's `crypto_pwhash` fixes Argon2's lanes
    /// at one and exposes no multi-lane entry point, and libsodium is the
    /// binding on both shipping platforms. A two-lane record would open in the
    /// Go reference and nowhere a user could use it. Note this sits BELOW the
    /// register's parallelism_max of 4, unlike the two ceilings above.
    public static let supportedParallelism: UInt8 = 1

    /// The register's frozen work factors, which is what an export writes.
    public static let registerDefaults = RecoveryParams(
        memoryKiB: UInt32(LumosConstants.recoveryArgon2idMemoryKib),
        iterations: UInt32(LumosConstants.recoveryArgon2idIterations),
        parallelism: UInt8(LumosConstants.recoveryArgon2idParallelism))

    /// - Parameters:
    ///   - memoryKiB: the memory cost in KiB
    ///   - iterations: the time cost
    ///   - parallelism: the lane count
    public init(memoryKiB: UInt32, iterations: UInt32, parallelism: UInt8) {
        self.memoryKiB = memoryKiB
        self.iterations = iterations
        self.parallelism = parallelism
    }

    /// Refuses work factors outside the ceilings, outside the single-lane rule,
    /// or below the register's minima - in that order, so nothing absurd is ever
    /// measured against a minimum first.
    /// - Throws: ``RecoveryError/paramsRejected(_:)``
    public func validate() throws {
        if memoryKiB > Self.maxAcceptableMemoryKiB {
            throw RecoveryError.paramsRejected("memory cost beyond the acceptable ceiling")
        }
        if iterations > Self.maxAcceptableIterations {
            throw RecoveryError.paramsRejected("time cost beyond the acceptable ceiling")
        }
        if parallelism != Self.supportedParallelism {
            throw RecoveryError.paramsRejected("only a single Argon2id lane is executable on every platform")
        }
        if memoryKiB < UInt32(LumosConstants.recoveryArgon2idMemoryKibMin) {
            throw RecoveryError.paramsRejected("memory cost below the register minimum")
        }
        if iterations < UInt32(LumosConstants.recoveryArgon2idIterationsMin) {
            throw RecoveryError.paramsRejected("time cost below the register minimum")
        }
        // No separate parallelism minimum: the single-lane rule above is
        // stricter than the register's parallelism_min for as long as that
        // minimum is 1, and RecoveryRecordTests fires if the register moves it.
    }
}

/// Seals content under a passphrase, and opens what it sealed.
public enum RecoveryRecord {

    /// The only record layout this build writes or reads.
    public static let formatVersion: UInt8 = 1
    /// ChaCha20-Poly1305 (IETF) nonce length in bytes.
    public static let nonceBytes = 12
    /// Poly1305 tag length in bytes.
    public static let tagBytes = 16

    /// Magic: eight bytes, so a file-type sniff is unambiguous and a truncated
    /// record cannot be mistaken for a short one.
    private static let magic = Data("LUMOSREC".utf8)

    private static let versionOffset = 8
    private static let memoryOffset = versionOffset + 1
    private static let iterationsOffset = memoryOffset + 4
    private static let parallelismOffset = iterationsOffset + 4
    private static let saltOffset = parallelismOffset + 1

    /// Everything the AEAD authenticates: magic, version, work factors, salt.
    public static let authenticatedHeaderBytes = saltOffset + LumosConstants.recoveryArgon2idSaltBytes
    /// Everything before the ciphertext: the authenticated header plus the nonce.
    public static let headerBytes = authenticatedHeaderBytes + nonceBytes

    /// libsodium wants one initialisation before use. A `static let` is lazy and
    /// runs exactly once, which is what the C API asks for.
    private static let sodiumReady: Bool = sodium_init() >= 0

    /// Seals content under a passphrase, at the register's frozen work factors.
    ///
    /// The salt and nonce are drawn fresh, so exporting the same content twice
    /// with the same passphrase produces different bytes. That is required: a
    /// repeated (key, nonce) pair under ChaCha20-Poly1305 is a total break, and
    /// two identical exports would also be linkable to each other.
    ///
    /// - Parameters:
    ///   - passphrase: the user's passphrase, UTF-8 encoded
    ///   - content: the bytes to seal; the caller decides what they are
    /// - Returns: the complete record
    /// - Throws: ``RecoveryError`` if the seal fails
    public static func export(passphrase: Data, content: Data) throws -> Data {
        return try exportWith(
            passphrase: passphrase, content: content,
            salt: randomBytes(LumosConstants.recoveryArgon2idSaltBytes),
            nonce: randomBytes(nonceBytes),
            params: .registerDefaults)
    }

    /// The deterministic core of ``export(passphrase:content:)``, with the salt,
    /// nonce and work factors supplied rather than generated.
    ///
    /// Public for the conformance tests, which cannot pin a record whose salt
    /// and nonce are fresh per call. Application code wants ``export``: a caller
    /// that chooses its own nonce can repeat one.
    ///
    /// - Parameters:
    ///   - passphrase: the user's passphrase, UTF-8 encoded
    ///   - content: the bytes to seal
    ///   - salt: exactly `recoveryArgon2idSaltBytes` of salt
    ///   - nonce: exactly ``nonceBytes`` of nonce
    ///   - params: the work factors to write into the header
    /// - Returns: the complete record
    /// - Throws: ``RecoveryError`` if the inputs are malformed or the seal fails
    public static func exportWith(passphrase: Data, content: Data, salt: Data,
                                  nonce: Data, params: RecoveryParams) throws -> Data {
        guard salt.count == LumosConstants.recoveryArgon2idSaltBytes, nonce.count == nonceBytes else {
            throw RecoveryError.openFailed
        }
        let header = authenticatedHeader(params: params, salt: salt)
        let key = try deriveKey(passphrase: passphrase, salt: salt, params: params)
        do {
            let sealed = try ChaChaPoly.seal(content, using: SymmetricKey(data: key),
                                             nonce: try ChaChaPoly.Nonce(data: nonce),
                                             authenticating: header)
            return header + nonce + sealed.ciphertext + sealed.tag
        } catch {
            throw RecoveryError.openFailed
        }
    }

    /// Recovers the content, refusing anything it cannot fully authenticate.
    ///
    /// Every opening failure reports the same case: a wrong passphrase, a
    /// tampered header and a corrupted ciphertext are one answer, because
    /// telling them apart is an oracle over the passphrase (TRS-CRY-013).
    ///
    /// - Parameters:
    ///   - passphrase: the user's passphrase, UTF-8 encoded
    ///   - record: the bytes read from the file
    /// - Returns: the sealed content
    /// - Throws: ``RecoveryError`` on any failure
    public static func open(passphrase: Data, record: Data) throws -> Data {
        try requireWellFormedHeader(record)

        // Validated BEFORE anything is derived. A record claiming 16 GiB must be
        // refused, not attempted: allocating what a hostile header asked for is
        // a denial of service for anyone who can hand the user a file.
        let declared = try params(of: record)
        try declared.validate()

        let bytes = [UInt8](record)
        let salt = Data(bytes[saltOffset..<authenticatedHeaderBytes])
        let nonce = Data(bytes[authenticatedHeaderBytes..<headerBytes])
        let header = Data(bytes[0..<authenticatedHeaderBytes])
        let sealed = Data(bytes[headerBytes...])
        guard sealed.count >= tagBytes else { throw RecoveryError.openFailed }

        let key = try deriveKey(passphrase: passphrase, salt: salt, params: declared)
        do {
            let box = try ChaChaPoly.SealedBox(
                nonce: try ChaChaPoly.Nonce(data: nonce),
                ciphertext: sealed.prefix(sealed.count - tagBytes),
                tag: sealed.suffix(tagBytes))
            return try ChaChaPoly.open(box, using: SymmetricKey(data: key), authenticating: header)
        } catch {
            throw RecoveryError.openFailed
        }
    }

    /// Reads a record's declared work factors without opening it, so a caller
    /// can warn about an old weak export before asking for a passphrase.
    /// - Parameter record: the bytes read from the file
    /// - Returns: the declared work factors, not yet validated
    /// - Throws: ``RecoveryError/openFailed`` if the record is too short
    public static func params(of record: Data) throws -> RecoveryParams {
        guard record.count >= headerBytes else { throw RecoveryError.openFailed }
        let bytes = [UInt8](record)
        return RecoveryParams(
            memoryKiB: bigEndian32(bytes, at: memoryOffset),
            iterations: bigEndian32(bytes, at: iterationsOffset),
            parallelism: bytes[parallelismOffset])
    }

    /// Renders a record's header for diagnostics. It never touches the
    /// ciphertext, so it is safe to log.
    /// - Parameter record: the bytes read from the file
    /// - Returns: a one-line description
    public static func describe(_ record: Data) -> String {
        guard let declared = try? params(of: record) else { return "unreadable recovery record" }
        return "recovery record: argon2id m=\(declared.memoryKiB) KiB "
            + "t=\(declared.iterations) p=\(declared.parallelism)"
    }

    /// Runs Argon2id over a passphrase and salt.
    ///
    /// Exported because the cross-language vectors pin the derivation itself,
    /// not only the sealed record: a platform whose binding disagreed here would
    /// otherwise fail with an indistinguishable authentication error and no clue
    /// which half was wrong.
    ///
    /// - Parameters:
    ///   - passphrase: the user's passphrase, UTF-8 encoded; may be empty
    ///   - salt: exactly `recoveryArgon2idSaltBytes` of salt
    ///   - params: the work factors; the caller validates them
    /// - Returns: the derived key
    /// - Throws: ``RecoveryError/openFailed`` if the derivation fails
    public static func deriveKey(passphrase: Data, salt: Data, params: RecoveryParams) throws -> Data {
        guard sodiumReady, salt.count == LumosConstants.recoveryArgon2idSaltBytes else {
            throw RecoveryError.openFailed
        }
        let (memoryBytes, overflowed) = Int(params.memoryKiB).multipliedReportingOverflow(by: 1024)
        guard !overflowed else { throw RecoveryError.openFailed }

        var key = [UInt8](repeating: 0, count: LumosConstants.recoveryArgon2idKeyBytes)
        // An empty passphrase still needs a non-nil pointer to hand to C; the
        // length stays zero, so the byte is never read.
        var password = [UInt8](passphrase)
        let passwordLength = password.count
        if password.isEmpty { password = [0] }
        let saltBytes = [UInt8](salt)

        let status: Int32 = password.withUnsafeBufferPointer { raw in
            raw.withMemoryRebound(to: CChar.self) { chars in
                // Never nil: `password` was substituted with a single byte above
                // if the passphrase was empty, precisely so this buffer always
                // has an address to hand to C. crypto_pwhash imports its passwd
                // parameter as non-optional, so the guard is what satisfies the
                // type rather than what handles a real case.
                guard let passwordStart = chars.baseAddress else { return Int32(-1) }
                return crypto_pwhash(&key, UInt64(key.count),
                                     passwordStart, UInt64(passwordLength),
                                     saltBytes,
                                     UInt64(params.iterations), memoryBytes,
                                     crypto_pwhash_alg_argon2id13())
            }
        }
        guard status == 0 else { throw RecoveryError.openFailed }
        return Data(key)
    }

    /// - Parameter record: the bytes read from the file
    /// - Throws: ``RecoveryError/openFailed`` if this is not a layout we know
    private static func requireWellFormedHeader(_ record: Data) throws {
        guard record.count >= headerBytes else { throw RecoveryError.openFailed }
        let bytes = [UInt8](record)
        guard Data(bytes[0..<magic.count]) == magic else { throw RecoveryError.openFailed }
        guard bytes[versionOffset] == formatVersion else { throw RecoveryError.openFailed }
    }

    /// The AEAD associated data. Binding the work factors is what makes a
    /// downgrade edit fail authentication instead of producing a cheaper key.
    /// - Parameters:
    ///   - params: the work factors
    ///   - salt: the record's salt
    /// - Returns: the authenticated header bytes
    private static func authenticatedHeader(params: RecoveryParams, salt: Data) -> Data {
        var header = Data(capacity: authenticatedHeaderBytes)
        header.append(magic)
        header.append(formatVersion)
        header.append(contentsOf: bigEndianBytes(params.memoryKiB))
        header.append(contentsOf: bigEndianBytes(params.iterations))
        header.append(params.parallelism)
        header.append(salt)
        return header
    }

    /// Draws cryptographically secure bytes.
    ///
    /// From CryptoKit rather than from libsodium's randombytes_buf, for the same
    /// reason the rest of this shell does: the platform CSPRNG is the one Apple
    /// keeps seeded across process and device state, and it is what every other
    /// key in LumosAppShell already comes from.
    ///
    /// - Parameter count: how many bytes
    /// - Returns: that many random bytes
    private static func randomBytes(_ count: Int) -> Data {
        SymmetricKey(size: SymmetricKeySize(bitCount: count * 8)).withUnsafeBytes { Data($0) }
    }

    /// - Parameters:
    ///   - bytes: the buffer
    ///   - at: where the field starts
    /// - Returns: the big-endian unsigned 32-bit field
    private static func bigEndian32(_ bytes: [UInt8], at: Int) -> UInt32 {
        (UInt32(bytes[at]) << 24) | (UInt32(bytes[at + 1]) << 16)
            | (UInt32(bytes[at + 2]) << 8) | UInt32(bytes[at + 3])
    }

    /// - Parameter value: the value to encode
    /// - Returns: its four big-endian bytes
    private static func bigEndianBytes(_ value: UInt32) -> [UInt8] {
        [UInt8(truncatingIfNeeded: value >> 24), UInt8(truncatingIfNeeded: value >> 16),
         UInt8(truncatingIfNeeded: value >> 8), UInt8(truncatingIfNeeded: value)]
    }
}
