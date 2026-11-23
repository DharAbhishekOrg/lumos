/// Lumos - LumosAppShell / The iOS key root: the KEK and the wrapped data root, kept in one Keychain item.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-LOC-002, TRS-IOS-007, SRS-DAT-002, SRS-DAT-012
/// ADRs: ADR-003, ADR-010, ADR-012
/// Threading: serial-executor
/// Notes: ADR-012's iOS half. The store is sealed under keys derived from a
/// data root wrapped by the KEK; to reopen anything after a relaunch the app
/// needs the KEK back and the wrapped root. Both go in ONE generic-password
/// Keychain item (account `lumos.key-root.v1`), written through
/// KeychainBlobStore, so AfterFirstUnlockThisDeviceOnly applies: the item never
/// reaches a backup, iCloud Keychain or another device, and a restore to a new
/// phone is a fresh install (ADR-003). AfterFirstUnlock, not WhenUnlocked,
/// because background decrypt runs with the device locked (ADR-010).
///
/// Record: `version u8 = 1 | tier u8 | kek_len u16 | kek | root_len u16 | wrapped_root`.
/// For the Secure Enclave tier `kek` is the enclave key's encrypted data
/// representation, usable only by this device's enclave. For the software
/// fallback (a device or simulator without an enclave) it is the raw KEK, and
/// the tier says so: that is the honest cost of having no hardware, exactly as
/// the Android software tier records it. The vault is a protocol so the record
/// logic is tested on any host; the Keychain behaviour itself (protection
/// class, backup exclusion) is device semantics, owner-verified on hardware.

import Foundation

/// Where the key root record lives.
public protocol KeyRootVault: AnyObject {
    /// The stored record, or nil when this install never finished onboarding.
    func load() throws -> Data?
    /// Replaces the stored record.
    func save(_ record: Data) throws
    /// Removes the record (wipe).
    func delete() throws
}

/// The opened key root.
public struct KeyRoot {

    static let version: UInt8 = 1
    /// The Keychain account the record lives under.
    public static let keychainAccount = "lumos.key-root.v1"

    /// The reopened KEK.
    public let kek: KekStore
    /// The data hierarchy under it.
    public let hierarchy: DataKeyHierarchy

    /// The key root is unreadable: corrupt, from a newer build, or its KEK is gone.
    public struct Unreadable: Error, Equatable, Sendable {}

    /// Creates a fresh KEK and data root and stores them: the last step of onboarding.
    /// - Parameter preferHardware: use the Secure Enclave when the device has one
    /// - Returns: the opened root
    public static func create(in vault: KeyRootVault, preferHardware: Bool = true) throws -> KeyRoot {
        let kek = try makeKek(preferHardware: preferHardware)
        let hierarchy = try DataKeyHierarchy.create(kek: kek)
        try vault.save(try encode(kek: kek, wrappedRoot: hierarchy.wrappedRoot))
        return KeyRoot(kek: kek, hierarchy: hierarchy)
    }

    /// Reopens the stored root.
    /// - Returns: the opened root, or nil when there is none (not onboarded on this install)
    /// - Throws: ``Unreadable`` when a record exists but cannot be opened
    public static func open(from vault: KeyRootVault) throws -> KeyRoot? {
        guard let record = try vault.load() else { return nil }
        let parsed = try decode(record)
        let wrappedRoot = parsed.wrappedRoot
        let kek: KekStore
        do {
            kek = try reopenKek(tier: parsed.tier, bytes: parsed.kek)
        } catch {
            throw Unreadable()
        }
        let hierarchy = DataKeyHierarchy.restore(kek: kek, wrappedRoot: wrappedRoot)
        // Prove the KEK opens the root now, not at the first store access.
        do {
            _ = try hierarchy.deriveKey(for: .database)
        } catch {
            throw Unreadable()
        }
        return KeyRoot(kek: kek, hierarchy: hierarchy)
    }

    private static func makeKek(preferHardware: Bool) throws -> KekStore {
        #if !os(Linux)
        if preferHardware && SecureEnclaveKekStore.isAvailable {
            return try SecureEnclaveKekStore()
        }
        #endif
        return SoftwareKekStore()
    }

    private static func reopenKek(tier: KeyProtection, bytes: Data) throws -> KekStore {
        switch tier {
        case .softwareFallback:
            return try SoftwareKekStore(rawKey: bytes)
        case .secureEnclave:
            #if !os(Linux)
            return try SecureEnclaveKekStore(dataRepresentation: bytes)
            #else
            throw Unreadable()
            #endif
        }
    }

    // MARK: the record

    static func tierByte(_ t: KeyProtection) -> UInt8 {
        t == .secureEnclave ? 1 : 2
    }

    static func encode(kek: KekStore, wrappedRoot: Data) throws -> Data {
        let material: Data?
        switch kek {
        case let s as SoftwareKekStore: material = s.rawKeyForPersistence
        #if !os(Linux)
        case let e as SecureEnclaveKekStore: material = e.dataRepresentation
        #endif
        default: material = nil
        }
        guard let material, material.count <= 0xFFFF, wrappedRoot.count <= 0xFFFF else { throw KekError.destroyed }
        return encode(tier: kek.protection, kekBytes: material, wrappedRoot: wrappedRoot)
    }

    static func encode(tier: KeyProtection, kekBytes: Data, wrappedRoot: Data) -> Data {
        func u16(_ n: Int) -> Data { Data([UInt8(n >> 8 & 0xFF), UInt8(n & 0xFF)]) }
        return Data([version, tierByte(tier)]) + u16(kekBytes.count) + kekBytes + u16(wrappedRoot.count) + wrappedRoot
    }

    /// A parsed record.
    struct Record: Equatable {
        let tier: KeyProtection
        let kek: Data
        let wrappedRoot: Data
    }

    static func decode(_ record: Data) throws -> Record {
        let b = [UInt8](record)
        guard b.count >= 6, b[0] == version else { throw Unreadable() }
        let tier: KeyProtection
        switch b[1] {
        case 1: tier = .secureEnclave
        case 2: tier = .softwareFallback
        default: throw Unreadable()
        }
        let kekLen = Int(b[2]) << 8 | Int(b[3])
        guard kekLen > 0, b.count >= 4 + kekLen + 2 else { throw Unreadable() }
        let rootAt = 4 + kekLen
        let rootLen = Int(b[rootAt]) << 8 | Int(b[rootAt + 1])
        guard rootLen > 0, b.count == rootAt + 2 + rootLen else { throw Unreadable() }
        return Record(tier: tier, kek: Data(b[4..<rootAt]), wrappedRoot: Data(b[(rootAt + 2)...]))
    }
}

#if canImport(Security)
/// The production vault: one ThisDeviceOnly, AfterFirstUnlock Keychain item.
public final class KeychainKeyRootVault: KeyRootVault {
    public init() {}

    public func load() throws -> Data? {
        do {
            return try KeychainBlobStore.get(account: KeyRoot.keychainAccount)
        } catch KeychainBlobError.notFound {
            return nil
        }
    }

    public func save(_ record: Data) throws {
        try KeychainBlobStore.put(record, account: KeyRoot.keychainAccount)
    }

    public func delete() throws {
        do {
            try KeychainBlobStore.delete(account: KeyRoot.keychainAccount)
        } catch KeychainBlobError.notFound {
            return
        }
    }
}
#endif
