/// Lumos - ios-app/shell / KEK stores: Secure Enclave capability-gated, software fallback.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-NFR-SEC-003, TRS-IOS-007, TRS-ARCH-005, SRS-NFR-SEC-002
/// ADRs: ADR-003, ADR-010
/// Threading: serial-executor
/// Notes: TRS-IOS-007: keys the enclave cannot host natively are wrapped at
/// rest by a hardware-protected KEK. The enclave hosts P-256 only, so the KEK
/// here IS an enclave P-256 key and wrapping is ECIES-shaped: ephemeral ECDH
/// against the enclave key, HKDF-SHA-256 to a symmetric key, AES-GCM over the
/// payload. Capability is PROBED (`SecureEnclave.isAvailable`), never assumed
/// (threat model LT-05): CI VMs have no enclave, so CI exercises the software
/// fallback and the enclave path is owner-verified on physical devices, never
/// simulated. Per ADR-010 neither store demands per-operation user
/// authentication - background decrypt requires exactly that - and the blob
/// framing mirrors the Android KekWrapFormat so the two platforms cannot
/// drift in how a wrapped blob is laid out.

import CryptoKit
import Foundation

/// The protection tier the KEK actually obtained, recorded at creation.
public enum KeyProtection: String, Sendable, Equatable {
    /// Apple Secure Enclave. The hardware tier.
    case secureEnclave = "SECURE_ENCLAVE"
    /// In-process software key: the probed fallback, and the only CI tier.
    case softwareFallback = "SOFTWARE_FALLBACK"

    /// Whether this tier is backed by hardware the OS cannot bypass.
    public var isHardwareBacked: Bool {
        self == .secureEnclave
    }
}

/// Errors the KEK boundary reports. Deliberately coarse: a tampered blob, a
/// wrong AAD and an unknown version are indistinguishable (TRS-CRY-013).
public enum KekError: Error, Sendable {
    /// The wrapped blob failed to open or parse.
    case unwrapFailed
    /// The store was destroyed; crypto-erasure has already happened.
    case destroyed
    /// The wrap operation itself failed.
    case wrapFailed
}

/// The narrow boundary of TRS-ARCH-005: wrap, unwrap, destroy - and nothing
/// that returns key material. KekStoreContractTests pins the surface.
public protocol KekStore: AnyObject {
    /// The recorded protection tier; never an assumption (threat model LT-05).
    var protection: KeyProtection { get }
    /// Whether `destroy()` has completed.
    var isDestroyed: Bool { get }
    /// Encrypts a data key under the KEK.
    /// - Parameters:
    ///   - plaintext: the data key; the caller discards its copy
    ///   - aad: domain-separation context bound into the tag
    /// - Returns: a self-describing wrapped blob
    /// - Throws: ``KekError`` on a destroyed store or cipher failure
    func wrap(_ plaintext: Data, aad: Data) throws -> Data
    /// Decrypts a wrapped data key.
    /// - Parameters:
    ///   - wrapped: a blob produced by `wrap`
    ///   - aad: the same context the blob was wrapped under
    /// - Returns: the plaintext data key
    /// - Throws: ``KekError/unwrapFailed`` on any tamper, indistinguishably
    func unwrap(_ wrapped: Data, aad: Data) throws -> Data
    /// Destroys the KEK irreversibly - the first stage of the keys-first wipe
    /// order (owner ruling 2026-08-14).
    func destroy()
}

/// Shared blob framing: `[version:1][AES.GCM combined: nonce(12)||ct||tag(16)]`.
enum KekWrapFormat {
    /// The only wrap-format version this build reads or writes.
    static let version: UInt8 = 1
    /// The smallest meaningful blob: version, nonce and a bare tag.
    static let minimumLength = 1 + 12 + 16

    /// Frames a sealed box.
    /// - Parameter combined: the AES-GCM combined representation
    /// - Returns: the framed blob
    static func encode(combined: Data) -> Data {
        var out = Data([version])
        out.append(combined)
        return out
    }

    /// Unframes a blob, failing closed on anything unexpected.
    /// - Parameter wrapped: the framed blob
    /// - Returns: the combined sealed-box bytes
    /// - Throws: ``KekError/unwrapFailed`` on a short blob or unknown version
    static func decode(_ wrapped: Data) throws -> Data {
        guard wrapped.count >= minimumLength, wrapped.first == version else {
            throw KekError.unwrapFailed
        }
        return wrapped.dropFirst()
    }
}

/// An in-process AES-256-GCM KEK with no hardware binding.
public final class SoftwareKekStore: KekStore {
    private var kek: SymmetricKey?

    /// Generates a fresh software KEK from the platform CSPRNG (SRS-NFR-SEC-002).
    public init() {
        self.kek = SymmetricKey(size: .bits256)
    }

    public var protection: KeyProtection { .softwareFallback }

    public var isDestroyed: Bool { kek == nil }

    public func wrap(_ plaintext: Data, aad: Data) throws -> Data {
        guard let key = kek else { throw KekError.destroyed }
        do {
            let sealed = try AES.GCM.seal(plaintext, using: key, authenticating: aad)
            guard let combined = sealed.combined else { throw KekError.wrapFailed }
            return KekWrapFormat.encode(combined: combined)
        } catch let error as KekError {
            throw error
        } catch {
            throw KekError.wrapFailed
        }
    }

    public func unwrap(_ wrapped: Data, aad: Data) throws -> Data {
        guard let key = kek else { throw KekError.destroyed }
        do {
            let box = try AES.GCM.SealedBox(combined: KekWrapFormat.decode(wrapped))
            return try AES.GCM.open(box, using: key, authenticating: aad)
        } catch {
            // One failure for every cause: tamper, wrong AAD, bad version. An
            // error that named the failing check would be an oracle (TRS-CRY-013).
            throw KekError.unwrapFailed
        }
    }

    public func destroy() {
        // SymmetricKey offers no zeroisation hook; dropping the only reference
        // is the strongest destruction at this tier, and is part of why this
        // tier honestly reports softwareFallback.
        kek = nil
    }
}

/// The Secure Enclave KEK: an enclave-resident P-256 key, ECIES-style wrapping.
public final class SecureEnclaveKekStore: KekStore {
    /// Domain-separation label for the per-wrap HKDF derivation.
    static let wrapInfo = "lumos/local/kek-wrap/v1"

    private var enclaveKey: SecureEnclave.P256.KeyAgreement.PrivateKey?

    /// Whether this device has a Secure Enclave. CI VMs answer false; the
    /// enclave path is owner-verified on hardware, never simulated.
    public static var isAvailable: Bool {
        SecureEnclave.isAvailable
    }

    /// Creates the enclave-resident KEK.
    ///
    /// Per ADR-010 the key is created WITHOUT per-operation user-presence
    /// access control: background decrypt (WP-03 BLE, WP-05 push pull) must
    /// run with no person present, and the user-facing protections are App
    /// Lock plus action-level device auth, layered above this boundary.
    /// - Throws: whatever the enclave reports when unavailable or refusing
    public init() throws {
        self.enclaveKey = try SecureEnclave.P256.KeyAgreement.PrivateKey()
    }

    public var protection: KeyProtection { .secureEnclave }

    public var isDestroyed: Bool { enclaveKey == nil }

    public func wrap(_ plaintext: Data, aad: Data) throws -> Data {
        guard let key = enclaveKey else { throw KekError.destroyed }
        do {
            let ephemeral = P256.KeyAgreement.PrivateKey()
            let symmetric = try Self.wrappingKey(shared: ephemeral.sharedSecretFromKeyAgreement(
                with: key.publicKey))
            let sealed = try AES.GCM.seal(plaintext, using: symmetric, authenticating: aad)
            guard let combined = sealed.combined else { throw KekError.wrapFailed }
            var out = Data([KekWrapFormat.version])
            out.append(ephemeral.publicKey.x963Representation)
            out.append(combined)
            return out
        } catch let error as KekError {
            throw error
        } catch {
            throw KekError.wrapFailed
        }
    }

    public func unwrap(_ wrapped: Data, aad: Data) throws -> Data {
        guard let key = enclaveKey else { throw KekError.destroyed }
        // version(1) + x963 P-256 public(65) + nonce(12) + tag(16) minimum.
        let ephemeralLength = 65
        guard wrapped.count >= 1 + ephemeralLength + 12 + 16,
            wrapped.first == KekWrapFormat.version else {
            throw KekError.unwrapFailed
        }
        do {
            let body = wrapped.dropFirst()
            let ephemeralPublic = try P256.KeyAgreement.PublicKey(
                x963Representation: body.prefix(ephemeralLength))
            let symmetric = try Self.wrappingKey(shared: key.sharedSecretFromKeyAgreement(
                with: ephemeralPublic))
            let box = try AES.GCM.SealedBox(combined: body.dropFirst(ephemeralLength))
            return try AES.GCM.open(box, using: symmetric, authenticating: aad)
        } catch {
            throw KekError.unwrapFailed
        }
    }

    public func destroy() {
        // Dropping the reference discards the enclave key's encrypted data
        // representation; without it the enclave-resident key is unreachable,
        // which is the enclave's form of crypto-erasure. Durable copies in the
        // Keychain are deleted by the wipe's key-material stage.
        enclaveKey = nil
    }

    /// Derives the per-wrap AES key from an ECDH shared secret.
    /// - Parameter shared: the agreement output
    /// - Returns: the wrapping key
    private static func wrappingKey(shared: SharedSecret) -> SymmetricKey {
        shared.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data(count: 32),
            sharedInfo: Data(wrapInfo.utf8),
            outputByteCount: 32)
    }
}
