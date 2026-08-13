/// Lumos - ios-app/shell / Keychain persistence for wrapped blobs, ThisDeviceOnly.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-IOS-007, SRS-DAT-012, SRS-NFR-SEC-003
/// ADRs: ADR-003, ADR-010
/// Threading: serial-executor
/// Notes: The thin platform adapter that puts wrapped key blobs - the enclave
/// key's encrypted representation, the wrapped identity envelope, the wrapped
/// data root - into the Keychain under the protections TRS-IOS-007 and
/// SRS-DAT-012 mandate: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly, so
/// items never migrate to a new device or an iCloud backup, and AfterFirstUnlock
/// rather than WhenUnlocked because ADR-010's whole point is that background
/// decrypt works with the device locked (the KEK material these items hold is
/// itself wrapped; the enclave key representation is useless off-device by
/// construction). kSecAttrSynchronizable is never set - these items must not
/// reach iCloud Keychain.
///
/// Like AndroidKekStore, this adapter is compile-verified in CI but its
/// runtime behaviour - restore exclusion, protection-class enforcement - is
/// device semantics: owner-verified on hardware, never simulated. CI test
/// processes lack the entitlements the data-protection keychain wants, so no
/// CI test claims this code ran.

import Foundation
import Security

/// Errors the Keychain adapter reports.
public enum KeychainBlobError: Error, Sendable {
    /// The item was not found.
    case notFound
    /// The Keychain refused the operation; carries the OSStatus.
    case failed(OSStatus)
}

/// Stores wrapped key blobs as ThisDeviceOnly generic-password items.
public enum KeychainBlobStore {

    /// The service namespace every Lumos keychain item lives under.
    public static let service = "com.lumos.app.keys"

    /// Writes a blob, replacing any previous value for the account.
    /// - Parameters:
    ///   - blob: the wrapped bytes; already ciphertext under the KEK
    ///   - account: the item's name within the Lumos service
    /// - Throws: ``KeychainBlobError/failed(_:)`` on a Keychain refusal
    public static func put(_ blob: Data, account: String) throws {
        // Replace-on-write: a previous value under the account is deleted
        // first, and its absence is not an error.
        _ = try? delete(account: account)
        var attributes = baseQuery(account: account)
        attributes[kSecValueData as String] = blob
        // ThisDeviceOnly: never migrated, never in an OS backup (SRS-DAT-012);
        // AfterFirstUnlock: available to background work per ADR-010.
        attributes[kSecAttrAccessible as String] =
            kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(attributes as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw KeychainBlobError.failed(status)
        }
    }

    /// Reads a blob back.
    /// - Parameter account: the item's name
    /// - Returns: the stored bytes
    /// - Throws: ``KeychainBlobError/notFound`` when absent
    public static func get(account: String) throws -> Data {
        var query = baseQuery(account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status != errSecItemNotFound else {
            throw KeychainBlobError.notFound
        }
        guard status == errSecSuccess, let data = result as? Data else {
            throw KeychainBlobError.failed(status)
        }
        return data
    }

    /// Deletes a blob - a key-material stage of the keys-first wipe.
    /// - Parameter account: the item's name
    /// - Throws: ``KeychainBlobError/failed(_:)`` on a refusal other than absence
    public static func delete(account: String) throws {
        let status = SecItemDelete(baseQuery(account: account) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainBlobError.failed(status)
        }
    }

    /// The query attributes shared by every operation.
    /// - Parameter account: the item's name
    /// - Returns: the base attribute dictionary
    private static func baseQuery(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            // The iOS-style data-protection keychain on every platform, so the
            // accessibility class above actually governs (macOS legacy keychain
            // ignores it).
            kSecUseDataProtectionKeychain as String: true
        ]
    }
}
