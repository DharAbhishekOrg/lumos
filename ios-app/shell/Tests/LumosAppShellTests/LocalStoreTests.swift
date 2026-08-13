/// Lumos - ios-app/shell / Store, migration and encryption-at-rest tests.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-DAT-002, TRS-LOC-002, TRS-LOC-003, TRS-LOC-006, TRS-LOC-010
/// ADRs: ADR-003
/// Threading: any
/// Notes: These run against the system SQLite - the same engine that runs on a
/// device - so unlike most iOS coverage in this repository they are not a proxy
/// for device behaviour, they ARE it. The headline is
/// `testNoSensitiveValueAppearsInTheDatabaseFile`: SRS-DAT-002's verification
/// method done the only way that means anything, by reading the bytes SQLite
/// actually wrote and searching them for the special-category strings the
/// privacy data map classifies as Article 9.

import CryptoKit
import Foundation
import XCTest
@testable import LumosAppShell

/// Unlimited room, for tests that are not about capacity.
private struct RoomyCapacity: StorageCapacity {
    var usableSpaceBytes: Int64 { Int64.max }
}

/// A disk one byte below the write floor.
private struct FullCapacity: StorageCapacity {
    var usableSpaceBytes: Int64 { Int64(16 * 1024 * 1024) - 1 }
}

/// A fixed UTC instant, so timestamp assertions are exact.
///
/// File-level rather than a stored property: the store's clock is a `@Sendable`
/// closure, and reading an instance property inside one would capture the
/// XCTestCase - which Swift 6 strict concurrency rejects, correctly, since the
/// test case is not Sendable.
private let fixedNow: Int64 = 1_700_000_000_000

/// Store behaviour over real SQLite.
final class LocalStoreTests: XCTestCase {

    /// Values the privacy data map classifies as special category.
    private let specialCategory = [
        "queer, and not looking to explain it",
        "gender identity: non-binary",
        "orientation: bisexual"
    ]

    // MARK: migrations

    func testEmptyDatabaseMigratesToCurrentVersion() throws {
        let database = try Sqlite3Database(path: ":memory:")
        defer { database.close() }
        try LocalStore.migrate(database: database)
        XCTAssertEqual(LocalStore.storedVersion(database: database), LocalSchema.version)
    }

    func testEveryDeclaredTableExistsWithTheCanonicalColumns() throws {
        let database = try Sqlite3Database(path: ":memory:")
        defer { database.close() }
        try LocalStore.migrate(database: database)

        for kind in RecordKind.allCases {
            let columns = try database.query("PRAGMA table_info(\(kind.table))", [])
            let names: [String] = columns.compactMap {
                if case .text(let name)? = $0["name"] { return name }
                return nil
            }
            XCTAssertEqual(names, [LocalSchema.columnId, LocalSchema.columnPayload,
                                   LocalSchema.columnLookupTag, LocalSchema.columnCreatedAt,
                                   LocalSchema.columnUpdatedAt, LocalSchema.columnPayloadVersion],
                           "\(kind.table) must have exactly the canonical column set")
        }
    }

    func testMigrationIsIdempotentAcrossReopen() throws {
        let database = try Sqlite3Database(path: ":memory:")
        defer { database.close() }
        try LocalStore.migrate(database: database)
        try LocalStore.migrate(database: database)
        XCTAssertEqual(LocalStore.storedVersion(database: database), LocalSchema.version)
    }

    func testDatabaseFromTheFutureIsRefusedNotDowngraded() throws {
        let database = try Sqlite3Database(path: ":memory:")
        defer { database.close() }
        try LocalStore.migrate(database: database)
        try database.execute(
            "UPDATE \(LocalSchema.tableMeta) SET \(LocalSchema.metaValue) = ? "
                + "WHERE \(LocalSchema.metaKey) = ?",
            [.text(String(LocalSchema.version + 1)), .text(LocalSchema.metaSchemaVersion)])

        XCTAssertThrowsError(try LocalStore.migrate(database: database)) { error in
            guard case StoreError.schemaFromTheFuture = error else {
                return XCTFail("forward-only means refuse, never downgrade; got \(error)")
            }
        }
    }

    func testFailingStepRollsBackWholeAndLeavesTheVersion() throws {
        let database = try Sqlite3Database(path: ":memory:")
        defer { database.close() }
        try LocalStore.migrate(database: database)
        let before = LocalStore.storedVersion(database: database)

        XCTAssertThrowsError(try database.inTransaction {
            try database.execute("CREATE TABLE half_migrated (x INTEGER)", [])
            try database.execute("THIS IS NOT SQL", [])
        })

        XCTAssertEqual(LocalStore.storedVersion(database: database), before)
        let leftovers = try database.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
            [.text("half_migrated")])
        XCTAssertTrue(leftovers.isEmpty, "the failed step's table must have rolled back with it")
    }

    func testLadderIsContiguousAndForwardOnly() {
        var expected = LocalSchema.versionEmpty
        for step in LocalSchema.migrations {
            XCTAssertEqual(step.fromVersion, expected, "the ladder must not skip a version")
            XCTAssertGreaterThan(step.toVersion, step.fromVersion, "every step must move forward")
            XCTAssertFalse(step.statements.isEmpty, "an empty step is a version bump with no schema change")
            expected = step.toVersion
        }
        XCTAssertEqual(expected, LocalSchema.version)
    }

    // MARK: store behaviour

    func testRecordsRoundTripPerKind() throws {
        let store = try openInMemory()
        for kind in RecordKind.allCases {
            let payload = Data("content for \(kind.table)".utf8)
            let natural = kind.isLookedUpByTag ? Data("peer-1".utf8) : nil
            let id = try store.put(kind: kind, plaintext: payload, naturalKey: natural)
            XCTAssertEqual(try store.get(kind: kind, id: id), payload)
        }
    }

    func testMintedIdentifiersAreUuidsAndTimestampsAreFromTheClock() throws {
        let database = try Sqlite3Database(path: ":memory:")
        defer { database.close() }
        let store = try LocalStore.open(database: database, databaseDomainKey: freshDomainKey(),
                                        capacity: RoomyCapacity(), now: { fixedNow })
        let id = try store.put(kind: .localProfile, plaintext: Data("x".utf8))
        XCTAssertNotNil(UUID(uuidString: id), "TRS-LOC-010: identifiers are stable UUIDs")

        let rows = try database.query(
            "SELECT \(LocalSchema.columnCreatedAt) FROM \(RecordKind.localProfile.table) "
                + "WHERE \(LocalSchema.columnId) = ?", [.text(id)])
        XCTAssertEqual(rows.first?[LocalSchema.columnCreatedAt], .integer(fixedNow),
                       "TRS-LOC-010: UTC milliseconds from the injected clock")
    }

    func testBlockEntriesAreFoundByBlindIndexWithoutStoringThePeerIdentifier() throws {
        let database = try Sqlite3Database(path: ":memory:")
        defer { database.close() }
        let store = try LocalStore.open(database: database, databaseDomainKey: freshDomainKey(),
                                        capacity: RoomyCapacity(), now: { 0 })
        let peer = Data("peer-identifier-that-must-not-be-stored".utf8)
        try store.put(kind: .blockEntry, plaintext: Data("blocked".utf8), naturalKey: peer)

        XCTAssertEqual(try store.findByNaturalKey(kind: .blockEntry, naturalKey: peer),
                       [Data("blocked".utf8)])
        XCTAssertTrue(try store.findByNaturalKey(kind: .blockEntry,
                                                 naturalKey: Data("some-other-peer".utf8)).isEmpty)

        let rows = try database.query(
            "SELECT \(LocalSchema.columnLookupTag) FROM \(RecordKind.blockEntry.table)", [])
        XCTAssertNotEqual(rows.first?[LocalSchema.columnLookupTag], .blob(peer),
                          "the stored tag must not be the peer identifier itself")
    }

    func testKindNeedingATagRefusesAWriteWithout() throws {
        let store = try openInMemory()
        XCTAssertThrowsError(try store.put(kind: .blockEntry, plaintext: Data("x".utf8)),
                             "a block entry with no blind index could never be found again")
    }

    func testLowStorageRefusesTheWriteAndLeavesNothing() throws {
        let database = try Sqlite3Database(path: ":memory:")
        defer { database.close() }
        let store = try LocalStore.open(database: database, databaseDomainKey: freshDomainKey(),
                                        capacity: FullCapacity(), now: { 0 })
        XCTAssertThrowsError(try store.put(kind: .localProfile, plaintext: Data("x".utf8))) { error in
            XCTAssertEqual(error as? StoreError, .lowStorage)
        }
        XCTAssertEqual(try store.count(kind: .localProfile), 0)
    }

    func testFailedTransactionLeavesNoPartialWrite() throws {
        let database = try Sqlite3Database(path: ":memory:")
        defer { database.close() }
        let store = try LocalStore.open(database: database, databaseDomainKey: freshDomainKey(),
                                        capacity: RoomyCapacity(), now: { 0 })
        try store.put(kind: .localProfile, plaintext: Data("first".utf8))

        XCTAssertThrowsError(try database.inTransaction {
            try database.execute(
                "INSERT INTO \(RecordKind.localProfile.table) (\(LocalSchema.columnId), "
                    + "\(LocalSchema.columnPayload), \(LocalSchema.columnCreatedAt), "
                    + "\(LocalSchema.columnUpdatedAt), \(LocalSchema.columnPayloadVersion)) "
                    + "VALUES (?, ?, ?, ?, ?)",
                [.text("second"), .blob(Data([1, 2, 3])), .integer(0), .integer(0), .integer(1)])
            throw StoreError.engine("simulated crash mid-transaction")
        })

        XCTAssertEqual(try store.count(kind: .localProfile), 1,
                       "crash consistency: the interrupted transaction must have left nothing")
    }

    func testMissingRowReadsAsNilRatherThanFailing() throws {
        let store = try openInMemory()
        XCTAssertNil(try store.get(kind: .localProfile, id: UUID().uuidString),
                     "absence is a state; only an unopenable row is a failure")
    }

    // MARK: encryption at rest

    func testNoSensitiveValueAppearsInTheDatabaseFile() throws {
        let directory = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appendingPathComponent("lumos-local.db")

        let database = try Sqlite3Database(path: file.path)
        let store = try LocalStore.open(database: database, databaseDomainKey: freshDomainKey(),
                                        capacity: RoomyCapacity(), now: { 0 })
        for value in specialCategory {
            try store.put(kind: .localProfile, plaintext: Data(value.utf8))
        }
        database.close()

        let onDisk = try everyByteIn(directory: directory)
        XCTAssertGreaterThan(onDisk.count, 0, "the test must actually have written a file to search")
        for value in specialCategory {
            XCTAssertNil(onDisk.range(of: Data(value.utf8)),
                         "special-category value found in plaintext on disk: \(value)")
        }
    }

    func testRelocatedRowCannotBeOpened() throws {
        let database = try Sqlite3Database(path: ":memory:")
        defer { database.close() }
        let store = try LocalStore.open(database: database, databaseDomainKey: freshDomainKey(),
                                        capacity: RoomyCapacity(), now: { 0 })
        let id = try store.put(kind: .localProfile, plaintext: Data("private".utf8))

        let rows = try database.query(
            "SELECT \(LocalSchema.columnPayload) FROM \(RecordKind.localProfile.table) "
                + "WHERE \(LocalSchema.columnId) = ?", [.text(id)])
        guard case .blob(let sealed)? = rows.first?[LocalSchema.columnPayload] else {
            return XCTFail("expected a sealed payload")
        }
        try database.execute(
            "INSERT INTO \(RecordKind.localProfile.table) (\(LocalSchema.columnId), "
                + "\(LocalSchema.columnPayload), \(LocalSchema.columnCreatedAt), "
                + "\(LocalSchema.columnUpdatedAt), \(LocalSchema.columnPayloadVersion)) "
                + "VALUES (?, ?, ?, ?, ?)",
            [.text("relocated"), .blob(sealed), .integer(0), .integer(0), .integer(1)])

        XCTAssertThrowsError(try store.get(kind: .localProfile, id: "relocated")) { error in
            XCTAssertEqual(error as? StoreError, .unreadable,
                           "a relocated payload must fail exactly as a tampered one does")
        }
    }

    func testADifferentKeyCannotOpenTheRecords() throws {
        let database = try Sqlite3Database(path: ":memory:")
        defer { database.close() }
        let store = try LocalStore.open(database: database, databaseDomainKey: freshDomainKey(),
                                        capacity: RoomyCapacity(), now: { 0 })
        let id = try store.put(kind: .localProfile, plaintext: Data("private".utf8))

        let other = try LocalStore.open(database: database, databaseDomainKey: freshDomainKey(),
                                        capacity: RoomyCapacity(), now: { 0 })
        XCTAssertThrowsError(try other.get(kind: .localProfile, id: id),
                             "records must be unreadable under any key but the one that sealed them")
    }

    // MARK: helpers

    private func openInMemory() throws -> LocalStore {
        let database = try Sqlite3Database(path: ":memory:")
        return try LocalStore.open(database: database, databaseDomainKey: freshDomainKey(),
                                   capacity: RoomyCapacity(), now: { 0 })
    }

    private func freshDomainKey() throws -> Data {
        try DataKeyHierarchy.create(kek: SoftwareKekStore()).deriveKey(for: .database)
    }

    private func temporaryDirectory() throws -> URL {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("lumos-store-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    private func everyByteIn(directory: URL) throws -> Data {
        var all = Data()
        for url in try FileManager.default.contentsOfDirectory(at: directory,
                                                               includingPropertiesForKeys: nil) {
            all.append(try Data(contentsOf: url))
        }
        return all
    }
}
