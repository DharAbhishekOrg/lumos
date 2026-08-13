/// Lumos - ios-app/shell / Per-domain data keys derived under the KEK-wrapped root.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-DAT-002, TRS-IOS-007, SRS-NFR-SEC-002
/// ADRs: ADR-003, ADR-004
/// Threading: serial-executor
/// Notes: One random root secret, wrapped by the KEK; every store's key is an
/// HKDF-SHA-256 derivation from that root under a domain label. Destroying the
/// KEK erases every store at once - the crypto-erasure the keys-first wipe
/// order relies on (owner ruling 2026-08-14). The domain labels match the
/// Android hierarchy's for naming consistency (TRS-CODE-007), not for
/// interoperability: each platform's derived keys protect its own local
/// stores and never cross a device boundary.

import CryptoKit
import Foundation

/// Derives per-domain data keys from one KEK-wrapped root secret.
public final class DataKeyHierarchy {

    /// The local stores that receive derived keys.
    public enum DataDomain: String, CaseIterable, Sendable {
        /// The encrypted SQLite database.
        case database = "lumos/local/db-key/v1"
        /// The encrypted media object store (SRS-PRO-004).
        case mediaStore = "lumos/local/media-key/v1"
    }

    /// AAD binding the wrapped root to its role, mirroring Android's.
    public static let rootAad = "lumos/local/data-root/v1"

    /// Root-secret and derived-key length in bytes.
    static let keyLength = 32

    private let kek: KekStore
    private let wrappedRootSecret: Data

    private init(kek: KekStore, wrappedRootSecret: Data) {
        self.kek = kek
        self.wrappedRootSecret = wrappedRootSecret
    }

    /// Creates a fresh hierarchy: a CSPRNG root secret sealed under the KEK.
    /// - Parameter kek: the wrapping store
    /// - Returns: the hierarchy
    /// - Throws: on wrap failure
    public static func create(kek: KekStore) throws -> DataKeyHierarchy {
        let root = SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) }
        let wrapped = try kek.wrap(root, aad: Data(rootAad.utf8))
        return DataKeyHierarchy(kek: kek, wrappedRootSecret: wrapped)
    }

    /// Reattaches to a hierarchy whose wrapped root was persisted earlier.
    /// - Parameters:
    ///   - kek: the same KEK the root was wrapped under
    ///   - wrappedRoot: the persisted wrapped root
    /// - Returns: the hierarchy
    public static func restore(kek: KekStore, wrappedRoot: Data) -> DataKeyHierarchy {
        DataKeyHierarchy(kek: kek, wrappedRootSecret: wrappedRoot)
    }

    /// The wrapped root for persistence.
    public var wrappedRoot: Data {
        wrappedRootSecret
    }

    /// Derives one domain's data key.
    ///
    /// After ``KekStore/destroy()`` this can only fail, which is the
    /// crypto-erasure property the wipe tests pin.
    /// - Parameter domain: the requesting store
    /// - Returns: the 32-byte domain key
    /// - Throws: ``KekError`` once the KEK is destroyed or on tamper
    public func deriveKey(for domain: DataDomain) throws -> Data {
        let root = try kek.unwrap(wrappedRootSecret, aad: Data(Self.rootAad.utf8))
        let derived = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: root),
            salt: Data(count: Self.keyLength),
            info: Data(domain.rawValue.utf8),
            outputByteCount: Self.keyLength)
        return derived.withUnsafeBytes { Data($0) }
    }
}
