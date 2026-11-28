/// Lumos - ios-app/shell / Profile photos: add, choose the main one, reorder, remove.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-PRO-001, SRS-PRO-004, SRS-PRO-005, SRS-PRO-006, SRS-PRO-009
/// ADRs: ADR-003
/// Threading: serial-executor (the store's; the main actor in the app)
/// Notes: The Swift twin of Android's ProfilePhotos, step for step. A picked
/// picture walks one path only: the platform re-encoder draws it again as a
/// fresh, upright JPEG (no camera metadata survives a re-draw), the sanitiser
/// checks type, size and dimensions and strips every metadata container
/// again (SRS-PRO-005/006), the encrypted object store keeps the bytes
/// (SRS-PRO-004), and only then does the profile reference it. An object is
/// written before the profile save and deleted again if the save fails; a
/// removed photo's object is deleted only after the profile without it is
/// stored; sweep() deletes anything a crash left unreferenced, and nothing
/// when the profile cannot be read. Works offline (SRS-PRO-009). Kept free of
/// ImageIO so it builds and tests on Linux; the re-encoder is injected.

import Foundation

/// Draws a picked picture again as a fresh, bounded JPEG.
public protocol PhotoReencoder: Sendable {
    /// - Parameter picked: the bytes as the picker gave them
    /// - Returns: a freshly encoded JPEG within MediaSanitizer.maxImageBytes
    /// - Throws: ``MediaSanitizer/Rejection`` when it cannot be decoded safely
    func reencode(_ picked: Data) throws -> Data
}

/// Where photo bytes are kept, encrypted.
public protocol PhotoVault: AnyObject {
    /// Stores sanitised bytes and returns the new identifier.
    func put(_ sanitised: Data) throws -> String
    /// Opens one object.
    func get(_ id: String) throws -> Data
    /// Deletes one object; an absent one is success.
    func delete(_ id: String) throws
    /// Every stored identifier.
    func list() throws -> [String]
}

extension MediaObjectStore: PhotoVault {}

/// The profile-photo operations.
public final class ProfilePhotos {

    /// The most photos a profile holds: a named local constant (TRS-CODE-005),
    /// the same six as Android. The register bounds each image's bytes, not
    /// how many a profile keeps.
    public static let maxPhotos = 6

    /// What an operation did.
    public enum Outcome: Equatable, Sendable {
        /// Done and stored.
        case done
        /// Already ``maxPhotos`` photos.
        case full
        /// The picture failed a safety check and was not kept.
        case rejected
        /// The store refused; nothing changed.
        case notSaved
        /// No such photo on the profile.
        case unknown
    }

    private let reencoder: PhotoReencoder
    private let vault: PhotoVault
    private let repository: ProfileRepository

    /// - Parameters:
    ///   - reencoder: the platform re-encoder
    ///   - vault: the encrypted object store
    ///   - repository: the profile store
    public init(reencoder: PhotoReencoder, vault: PhotoVault, repository: ProfileRepository) {
        self.reencoder = reencoder
        self.vault = vault
        self.repository = repository
    }

    /// Adds a picked picture as the last photo (the main one when it is the first).
    /// - Parameters:
    ///   - picked: the picker's bytes
    ///   - profile: the profile on screen
    /// - Returns: what happened
    public func add(_ picked: Data, to profile: LocalProfile) -> Outcome {
        guard profile.media.count < Self.maxPhotos else { return .full }
        let image: MediaSanitizer.SanitizedImage
        do {
            image = try MediaSanitizer.sanitize(try reencoder.reencode(picked))
        } catch {
            return .rejected
        }
        let id: String
        do {
            id = try vault.put(image.bytes)
        } catch {
            return .notSaved
        }
        let media = ProfileMedia(objectId: id, contentType: image.type.rawValue, widthPixels: image.width,
                                 heightPixels: image.height, byteCount: image.bytes.count)
        do {
            try repository.save(profile.withMedia(profile.media + [media]))
            return .done
        } catch {
            try? vault.delete(id)
            return .notSaved
        }
    }

    /// Makes one photo the main one (moves it first).
    public func makePrimary(_ id: String, in profile: LocalProfile) -> Outcome {
        move(id, to: 0, in: profile)
    }

    /// Moves one photo to a new place, clamped to the list.
    public func move(_ id: String, to index: Int, in profile: LocalProfile) -> Outcome {
        var media = profile.media
        guard let from = media.firstIndex(where: { $0.objectId == id }) else { return .unknown }
        let moved = media.remove(at: from)
        media.insert(moved, at: max(0, min(index, media.count)))
        do {
            try repository.save(profile.withMedia(media))
            return .done
        } catch {
            return .notSaved
        }
    }

    /// Removes one photo: the profile without it is stored first, then its bytes are deleted.
    public func remove(_ id: String, from profile: LocalProfile) -> Outcome {
        guard profile.media.contains(where: { $0.objectId == id }) else { return .unknown }
        do {
            try repository.save(profile.withMedia(profile.media.filter { $0.objectId != id }))
        } catch {
            return .notSaved
        }
        // A delete that fails leaves an unreferenced object; sweep() takes it.
        try? vault.delete(id)
        return .done
    }

    /// One photo's bytes, for drawing it on this phone only.
    public func bytes(_ id: String) -> Data? {
        try? vault.get(id)
    }

    /// Deletes every stored object the stored profile does not reference;
    /// nothing when the profile cannot be read (it could reference anything).
    /// - Returns: how many objects were deleted
    @discardableResult
    public func sweep() -> Int {
        let stored: LocalProfile
        do {
            stored = try repository.load() ?? .empty
        } catch {
            return 0
        }
        let kept = Set(stored.media.map(\.objectId))
        guard let names = try? vault.list() else { return 0 }
        var deleted = 0
        for name in names where !kept.contains(name) {
            if (try? vault.delete(name)) != nil {
                deleted += 1
            }
        }
        return deleted
    }
}
