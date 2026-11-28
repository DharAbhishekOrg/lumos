/// Lumos - ios-app/shell / Profile photos: only clean, encrypted pictures join a profile, and nothing dangles.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-PRO-001, SRS-PRO-004, SRS-PRO-005, SRS-PRO-006, SRS-PRO-009, SRS-SAF-010, TRS-TST-001
/// ADRs: ADR-003
/// Threading: test-only
/// Notes: The same cases as Android's ProfilePhotosTest and MediaObjectStoreTest,
/// over the real profile store and the real encrypted object store in a temp
/// directory. The re-encoder passes bytes through untouched, so the planted
/// metadata reaches the sanitiser and the test proves the sanitiser keeps it
/// out of storage. No ImageIO here, so these run on every platform.

import Foundation
import XCTest
@testable import LumosAppShell

/// Passes bytes through, or refuses everything.
private struct Passthrough: PhotoReencoder {
    var refuse = false
    func reencode(_ picked: Data) throws -> Data {
        if refuse { throw MediaSanitizer.Rejection.badDimensions(width: 0, height: 0) }
        return picked
    }
}

final class ProfilePhotosTests: XCTestCase {

    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("lumos-media-\(UUID().uuidString)")
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    private func vault() throws -> MediaObjectStore {
        try MediaObjectStore(directory: directory,
                             mediaDomainKey: try DataKeyHierarchy.create(kek: SoftwareKekStore())
                                .deriveKey(for: .mediaStore),
                             excludeFromBackup: { _ in })
    }

    private static let named = LocalProfile.empty
        .with(.alias, .stated("Asha"))
        .with(.ageBand, .stated("25–34"))

    private func stored(_ repository: ProfileRepository) throws -> [String] {
        try XCTUnwrap(try repository.load()).media.map(\.objectId)
    }

    func testAnObjectRoundTripsAndItsFileHoldsNoPlaintext() throws {
        let store = try vault()
        let photo = Data(String(repeating: "SANITISED-JPEG-BYTES", count: 20).utf8)
        let id = try store.put(photo)
        XCTAssertEqual(try store.get(id), photo)
        let disk = try Data(contentsOf: directory.appendingPathComponent(id))
        XCTAssertNil(disk.range(of: Data("SANITISED-JPEG-BYTES".utf8)), "encrypted at rest (SRS-PRO-004)")
        XCTAssertEqual(id.count, 32)
    }

    func testARenamedTamperedOrForeignObjectDoesNotOpen() throws {
        let store = try vault()
        let first = try store.put(Data("one".utf8))
        let second = try store.put(Data("two".utf8))
        let firstURL = directory.appendingPathComponent(first)
        let secondURL = directory.appendingPathComponent(second)
        try FileManager.default.removeItem(at: secondURL)
        try FileManager.default.copyItem(at: firstURL, to: secondURL)
        XCTAssertThrowsError(try store.get(second), "the AAD names the identifier")
        var tampered = try Data(contentsOf: firstURL)
        tampered[tampered.count - 1] ^= 0x01
        try tampered.write(to: firstURL)
        XCTAssertThrowsError(try store.get(first))
        XCTAssertThrowsError(try store.get("../escape")) { error in
            XCTAssertEqual(error as? MediaObjectStore.Failure, .notAnIdentifier)
        }
        let other = try MediaObjectStore(directory: directory, mediaDomainKey: Data(count: 32),
                                         excludeFromBackup: { _ in })
        let foreign = try other.put(Data("three".utf8))
        XCTAssertThrowsError(try vault().get(foreign), "another key does not open it")
    }

    func testTheDirectoryIsExcludedFromBackups() throws {
        var excluded: [URL] = []
        _ = try MediaObjectStore(directory: directory, mediaDomainKey: Data(count: 32),
                                 excludeFromBackup: { excluded.append($0) })
        XCTAssertEqual(excluded, [directory])
    }

    func testAnAddedPhotoIsStrippedEncryptedAndReferenced() throws {
        let store = try vault()
        let repository = ProfileRepository(store: try TestStores.openInMemory())
        let photos = ProfilePhotos(reencoder: Passthrough(), vault: store, repository: repository)

        XCTAssertEqual(photos.add(DirtyImages.jpegWithEverything(width: 640, height: 480), to: Self.named), .done)

        let media = try XCTUnwrap(try repository.load()?.media.first)
        XCTAssertEqual(media.contentType, "image/jpeg")
        XCTAssertEqual(media.widthPixels, 640)
        XCTAssertEqual(media.heightPixels, 480)
        let kept = try store.get(media.objectId)
        XCTAssertEqual(kept.count, media.byteCount)
        for secret in DirtyImages.plantedSecrets {
            XCTAssertNil(kept.range(of: Data(secret.utf8)), "stored photo still carries: \(secret)")
        }
    }

    func testThePrimaryCanBeChosenAndTheOrderChangedOffline() throws {
        let repository = ProfileRepository(store: try TestStores.openInMemory())
        let photos = ProfilePhotos(reencoder: Passthrough(), vault: try vault(), repository: repository)
        XCTAssertEqual(photos.add(DirtyImages.jpegWithEverything(width: 10, height: 10), to: Self.named), .done)
        XCTAssertEqual(photos.add(DirtyImages.pngWithEverything(width: 20, height: 20),
                                  to: try XCTUnwrap(try repository.load())), .done)
        XCTAssertEqual(photos.add(DirtyImages.jpegWithEverything(width: 30, height: 30),
                                  to: try XCTUnwrap(try repository.load())), .done)
        let added = try stored(repository)

        XCTAssertEqual(photos.makePrimary(added[2], in: try XCTUnwrap(try repository.load())), .done)
        XCTAssertEqual(try stored(repository), [added[2], added[0], added[1]])
        XCTAssertEqual(photos.move(added[2], to: 99, in: try XCTUnwrap(try repository.load())), .done)
        XCTAssertEqual(try stored(repository), added, "clamped to the end")
        XCTAssertEqual(photos.makePrimary(String(repeating: "0", count: 32),
                                          in: try XCTUnwrap(try repository.load())), .unknown)
        XCTAssertEqual(try stored(repository), added)
    }

    func testRemovingStoresTheProfileThenDeletesTheBytes() throws {
        let store = try vault()
        let repository = ProfileRepository(store: try TestStores.openInMemory())
        let photos = ProfilePhotos(reencoder: Passthrough(), vault: store, repository: repository)
        XCTAssertEqual(photos.add(DirtyImages.jpegWithEverything(width: 10, height: 10), to: Self.named), .done)
        let id = try stored(repository)[0]

        XCTAssertEqual(photos.remove(id, from: try XCTUnwrap(try repository.load())), .done)

        XCTAssertEqual(try stored(repository), [])
        XCTAssertEqual(try store.list(), [], "the encrypted bytes are gone too")
    }

    func testAFullProfileTakesNoMoreAndAnUnsafePictureIsNeverStored() throws {
        let store = try vault()
        let repository = ProfileRepository(store: try TestStores.openInMemory())
        let photos = ProfilePhotos(reencoder: Passthrough(), vault: store, repository: repository)
        var profile = Self.named
        for _ in 0..<ProfilePhotos.maxPhotos {
            XCTAssertEqual(photos.add(DirtyImages.jpegWithEverything(width: 8, height: 8), to: profile), .done)
            profile = try XCTUnwrap(try repository.load())
        }
        XCTAssertEqual(photos.add(DirtyImages.jpegWithEverything(width: 8, height: 8), to: profile), .full)
        XCTAssertEqual(try store.list().count, ProfilePhotos.maxPhotos)

        XCTAssertEqual(photos.add(Data("<svg onload=alert(1)>".utf8), to: Self.named), .rejected)
        XCTAssertEqual(ProfilePhotos(reencoder: Passthrough(refuse: true), vault: store, repository: repository)
            .add(DirtyImages.jpegWithEverything(width: 8, height: 8), to: Self.named), .rejected)
        XCTAssertEqual(try store.list().count, ProfilePhotos.maxPhotos, "nothing unsafe was written")
    }

    func testTheSweepClearsLeftoversButNeverGuessesOverAnUnreadableProfile() throws {
        let store = try vault()
        let local = try TestStores.openInMemory()
        let repository = ProfileRepository(store: local)
        try repository.save(Self.named)
        let photos = ProfilePhotos(reencoder: Passthrough(), vault: store, repository: repository)
        let leftover = try store.put(Data([1, 2, 3]))
        XCTAssertEqual(photos.sweep(), 1)
        XCTAssertFalse(try store.list().contains(leftover))

        let kept = try store.put(Data([4, 5, 6]))
        try local.put(kind: .localProfile, id: ProfileRepository.rowID, plaintext: Data("not a profile".utf8))
        XCTAssertEqual(photos.sweep(), 0, "an unreadable profile could reference anything")
        XCTAssertEqual(try store.get(kept), Data([4, 5, 6]))
    }

    func testTheEmergencyWipeDeletesThePhotoFilesAfterTheKeys() throws {
        let store = try vault()
        _ = try store.put(Data("a photo".utf8))
        let wipe = EmergencyWipe(kek: SoftwareKekStore(), store: try TestStores.openInMemory(), media: store)
        XCTAssertTrue(wipe.coverage.covered.contains { $0.what == "profile photos" })
        let confirmation = try DeliberateConfirmation
            .issue(action: .emergencyWipe, requiredPhrase: "erase everything", nowUtcMillis: 0)
            .confirm(typed: "erase everything", nowUtcMillis: 1)

        let outcome = try wipe.execute(confirmation: confirmation)

        XCTAssertTrue(outcome.cryptoErasureHolds)
        XCTAssertEqual(try store.list(), [])
        XCTAssertEqual(outcome.report.results.map(\.name), [EmergencyWipe.stageKek, EmergencyWipe.stageStoreKeys,
                                                           EmergencyWipe.stageLocalRecords,
                                                           EmergencyWipe.stageMediaObjects])
    }

    func testTheEditorAddsPhotosKeepsThemPrivateAndSaysWhatWentWrong() throws {
        let repository = ProfileRepository(store: try TestStores.openInMemory())
        try repository.save(Self.named)
        let editor = ProfileEditor(repository: repository,
                                   photos: ProfilePhotos(reencoder: Passthrough(), vault: try vault(),
                                                         repository: repository))
        XCTAssertTrue(editor.photosAvailable)

        editor.addPhoto(DirtyImages.jpegWithEverything(width: 8, height: 8))

        XCTAssertNil(editor.photoNotice)
        XCTAssertEqual(editor.profile.media.count, 1)
        XCTAssertEqual(editor.policy.threshold(.media), .match, "private to matches by default")
        XCTAssertTrue(editor.previewLines(for: .match).contains("Your main photo"))
        XCTAssertFalse(editor.previewLines(for: .requester).contains("Your main photo"))
        XCTAssertFalse(editor.previewLines(for: .nearby).contains("Your main photo"))
        XCTAssertNotNil(editor.photoBytes(editor.profile.media[0].objectId))

        editor.addPhoto(Data("not a picture".utf8))
        XCTAssertEqual(editor.photoNotice, .rejected)
        XCTAssertEqual(editor.profile.media.count, 1)

        editor.removePhoto(editor.profile.media[0].objectId)
        XCTAssertNil(editor.photoNotice)
        XCTAssertTrue(editor.profile.media.isEmpty)

        let without = ProfileEditor(repository: repository)
        XCTAssertFalse(without.photosAvailable)
        without.addPhoto(DirtyImages.jpegWithEverything(width: 8, height: 8))
        XCTAssertEqual(without.photoNotice, .unavailable)
        XCTAssertEqual(ProfileEditor.photoDescription(place: 0, count: 2), "Photo 1 of 2, your main photo")
        XCTAssertEqual(ProfileEditor.photoDescription(place: 1, count: 2), "Photo 2 of 2")
    }
}
