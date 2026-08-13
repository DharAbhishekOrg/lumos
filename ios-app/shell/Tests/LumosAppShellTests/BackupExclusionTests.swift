/// Lumos - ios-app/shell / The do-not-back-up attribute is actually set.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-DAT-012, TRS-LOC-009
/// ADRs: ADR-003
/// Threading: any
/// Notes: This is the rare platform behaviour that CI can verify honestly: the
/// isExcludedFromBackup resource value round-trips on macOS, so these tests
/// assert what was SET rather than asserting an API was called. What they still
/// cannot prove is that iOS honours it during a real iCloud backup - residual
/// risk RR-4, owner-executed on a real account and real device.
///
/// The sidecar test is the one that earns its place. A database excluded from
/// backup whose write-ahead log is not excluded is a database whose most recent
/// writes are in the backup, which is the failure mode nobody would notice
/// until it mattered.

import Foundation
import XCTest
@testable import LumosAppShell

/// Asserts store files carry the do-not-back-up attribute.
final class BackupExclusionTests: XCTestCase {

    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("lumos-backup-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    func testAFileIsNotExcludedUntilItIsMarked() throws {
        let file = directory.appendingPathComponent("plain.db")
        try Data("x".utf8).write(to: file)
        XCTAssertFalse(BackupExclusion.isExcluded(url: file),
                       "the default is INCLUDED; this test is what makes the next one meaningful")
    }

    func testMarkingAFileExcludesItFromBackup() throws {
        let file = directory.appendingPathComponent("marked.db")
        try Data("x".utf8).write(to: file)
        try BackupExclusion.exclude(url: file)
        XCTAssertTrue(BackupExclusion.isExcluded(url: file))
    }

    func testSqliteSidecarsAreExcludedAlongsideTheDatabase() throws {
        let database = directory.appendingPathComponent("lumos-local.db")
        try Data("main".utf8).write(to: database)
        for suffix in BackupExclusion.sqliteSidecarSuffixes {
            try Data("sidecar".utf8).write(to: URL(fileURLWithPath: database.path + suffix))
        }

        try BackupExclusion.excludeDatabaseFamily(databaseURL: database)

        XCTAssertTrue(BackupExclusion.isExcluded(url: database))
        for suffix in BackupExclusion.sqliteSidecarSuffixes {
            XCTAssertTrue(
                BackupExclusion.isExcluded(url: URL(fileURLWithPath: database.path + suffix)),
                "\(suffix) holds committed rows; excluding the main file alone leaks the newest writes")
        }
    }

    func testAbsentSidecarsAreNotAnError() throws {
        let database = directory.appendingPathComponent("solo.db")
        try Data("main".utf8).write(to: database)
        // No -wal, no -shm: the common case before the first write.
        XCTAssertNoThrow(try BackupExclusion.excludeDatabaseFamily(databaseURL: database))
        XCTAssertTrue(BackupExclusion.isExcluded(url: database))
    }

    func testARealStoreDatabaseCanBeExcluded() throws {
        let file = directory.appendingPathComponent("lumos-local.db")
        let database = try Sqlite3Database(path: file.path)
        let key = try DataKeyHierarchy.create(kek: SoftwareKekStore()).deriveKey(for: .database)
        let store = try LocalStore.open(database: database, databaseDomainKey: key,
                                        capacity: UnlimitedCapacity(), now: { 0 })
        try store.put(kind: .localProfile, plaintext: Data("content".utf8))
        database.close()

        try BackupExclusion.excludeDatabaseFamily(databaseURL: file)
        XCTAssertTrue(BackupExclusion.isExcluded(url: file),
                      "the exclusion must apply to the file the store actually created")
    }
}

/// Unlimited room, for tests that are not about capacity.
private struct UnlimitedCapacity: StorageCapacity {
    var usableSpaceBytes: Int64 { Int64.max }
}
