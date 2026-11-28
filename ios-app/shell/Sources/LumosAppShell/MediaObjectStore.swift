/// Lumos - ios-app/shell / The encrypted media object store: one file per profile photo.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-PRO-004, SRS-DAT-012
/// ADRs: ADR-003
/// Threading: serial (the store's queue)
/// Notes: The Swift twin of Android's MediaObjectStore, with the same file
/// format: one version byte, then AES-256-GCM's nonce, ciphertext and tag. The
/// key is HKDF-SHA-256 of the MEDIA_STORE domain key under the same label,
/// and each object's AAD names its identifier, so a file renamed onto another
/// object's name does not open. Identifiers are 16 random bytes in lowercase
/// hex; anything else is refused before a path is built, so no identifier can
/// leave the directory. The directory is excluded from OS backups, like the
/// database. Nothing here is interoperable on purpose: the bytes never leave
/// the phone (SRS-PRO-004); the matching format only keeps the two apps easy
/// to review side by side.

import CryptoKit
import Foundation

/// Encrypted storage for sanitised media objects.
public final class MediaObjectStore: @unchecked Sendable {

    /// HKDF label of the object key, the same string as Android's.
    public static let objectKeyInfo = "lumos/local/media-object-key/v1"

    /// Why an object could not be stored, opened or deleted.
    public enum Failure: Error, Equatable, Sendable {
        /// The identifier is not one this store could have issued.
        case notAnIdentifier
        /// Missing, tampered, renamed or from an unknown format, indistinguishably.
        case unreadable
        /// The file system refused.
        case io(String)
    }

    private static let formatVersion: UInt8 = 1
    private static let aadPrefix = "lumos/media-object/1/"
    private static let idBytes = 16

    private let directory: URL
    private let key: SymmetricKey

    /// Opens the store in a directory, creating it and excluding it from backups.
    /// - Parameters:
    ///   - directory: the private directory the objects live in
    ///   - mediaDomainKey: the MEDIA_STORE domain key; the caller keeps ownership
    ///   - excludeFromBackup: marks the directory as never backed up
    /// - Throws: ``Failure/io(_:)`` when the directory cannot be made or marked
    public init(directory: URL, mediaDomainKey: Data,
                excludeFromBackup: (URL) throws -> Void = BackupExclusion.exclude(url:)) throws {
        self.directory = directory
        key = HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: mediaDomainKey),
                                     salt: Data(count: 32), info: Data(Self.objectKeyInfo.utf8),
                                     outputByteCount: 32)
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try excludeFromBackup(directory)
        } catch {
            throw Failure.io("media directory not ready")
        }
    }

    /// Stores one sanitised object.
    /// - Parameter sanitised: the bytes the sanitiser produced
    /// - Returns: the new object's random identifier
    /// - Throws: ``Failure``
    public func put(_ sanitised: Data) throws -> String {
        var raw = [UInt8](repeating: 0, count: Self.idBytes)
        for i in raw.indices { raw[i] = UInt8.random(in: 0...UInt8.max) }
        let id = raw.map { String(format: "%02x", $0) }.joined()
        let sealed: Data
        do {
            guard let combined = try AES.GCM.seal(sanitised, using: key,
                                                  authenticating: Data((Self.aadPrefix + id).utf8)).combined else {
                throw Failure.unreadable
            }
            sealed = combined
        } catch {
            throw Failure.unreadable
        }
        var file = Data([Self.formatVersion])
        file.append(sealed)
        do {
            try file.write(to: try path(id), options: [.atomic])
        } catch let failure as Failure {
            throw failure
        } catch {
            throw Failure.io("object not written")
        }
        return id
    }

    /// Opens one object.
    /// - Parameter id: the identifier
    /// - Returns: the plaintext bytes
    /// - Throws: ``Failure/unreadable`` for every kind of bad file, ``Failure/notAnIdentifier``
    public func get(_ id: String) throws -> Data {
        let file: Data
        do {
            file = try Data(contentsOf: try path(id))
        } catch let failure as Failure {
            throw failure
        } catch {
            throw Failure.unreadable
        }
        guard file.first == Self.formatVersion else { throw Failure.unreadable }
        do {
            let box = try AES.GCM.SealedBox(combined: file.dropFirst())
            return try AES.GCM.open(box, using: key, authenticating: Data((Self.aadPrefix + id).utf8))
        } catch {
            throw Failure.unreadable
        }
    }

    /// Deletes one object; an absent one is success.
    /// - Parameter id: the identifier
    /// - Throws: ``Failure``
    public func delete(_ id: String) throws {
        let url = try path(id)
        guard FileManager.default.fileExists(atPath: url.path) else { return }
        do {
            try FileManager.default.removeItem(at: url)
        } catch {
            throw Failure.io("object not deleted")
        }
    }

    /// The stored identifiers, in no particular order.
    /// - Returns: every file name in the directory
    /// - Throws: ``Failure/io(_:)``
    public func list() throws -> [String] {
        do {
            return try FileManager.default.contentsOfDirectory(atPath: directory.path)
        } catch {
            throw Failure.io("media directory not listed")
        }
    }

    /// Deletes every file: the wipe's content pass, after the keys are gone.
    /// - Returns: how many files were deleted
    /// - Throws: ``Failure/io(_:)``
    @discardableResult
    public func wipeAll() throws -> Int {
        var deleted = 0
        for name in try list() {
            do {
                try FileManager.default.removeItem(at: directory.appendingPathComponent(name))
                deleted += 1
            } catch {
                throw Failure.io("media file not deleted")
            }
        }
        return deleted
    }

    private func path(_ id: String) throws -> URL {
        guard id.count == Self.idBytes * 2,
              id.unicodeScalars.allSatisfy({ ("0"..."9").contains($0) || ("a"..."f").contains($0) }) else {
            throw Failure.notAnIdentifier
        }
        return directory.appendingPathComponent(id)
    }
}
