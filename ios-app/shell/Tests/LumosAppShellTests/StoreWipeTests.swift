/// Lumos - ios-app/shell / The wipe now covers the store, keys first.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-SAF-010, TRS-LOC-007, SRS-NFR-PRI-012
/// ADRs: ADR-003, ADR-010
/// Threading: any
/// Notes: S1 proved the ORDER with synthetic stages; this proves it over real
/// rows in a real database. The second test is the one that matters: after an
/// interrupted content stage the row is still physically present, and the
/// assertion is that it is nonetheless unreadable - which is the whole
/// crypto-erasure claim the owner confirmed on 2026-08-14, exercised rather
/// than argued.

import Foundation
import XCTest
@testable import LumosAppShell

/// Unlimited room, for tests that are not about capacity.
private struct UnlimitedCapacity: StorageCapacity {
    var usableSpaceBytes: Int64 { Int64.max }
}

/// A test error for simulated stage failures.
private struct StageFailure: Error {}

/// The keys-first wipe extended over the local store.
final class StoreWipeTests: XCTestCase {

    func testWipeDestroysStoreKeysBeforeClearingRows() throws {
        let database = try Sqlite3Database(path: ":memory:")
        defer { database.close() }
        let kek = SoftwareKekStore()
        let hierarchy = try DataKeyHierarchy.create(kek: kek)
        let store = try LocalStore.open(database: database,
                                        databaseDomainKey: hierarchy.deriveKey(for: .database),
                                        capacity: UnlimitedCapacity(), now: { 0 })
        try store.put(kind: .localProfile, plaintext: Data("private".utf8))

        var order: [String] = []
        let report = KeysFirstWipe()
            .registerContent("local-store rows") {
                order.append("rows")
                try store.deleteEverything()
            }
            .registerKeyMaterial("local-store keys") {
                order.append("store-keys")
                store.destroyKeys()
            }
            .registerKeyMaterial("kek") {
                order.append("kek")
                kek.destroy()
            }
            .execute()

        XCTAssertEqual(order, ["store-keys", "kek", "rows"],
                       "both key stages must precede the content stage whatever the registration order")
        XCTAssertTrue(report.fullyClean)
        XCTAssertEqual(try store.count(kind: .localProfile), 0)
    }

    func testRowsSurvivingAnInterruptedWipeAreUnreadable() throws {
        let database = try Sqlite3Database(path: ":memory:")
        defer { database.close() }
        let kek = SoftwareKekStore()
        let hierarchy = try DataKeyHierarchy.create(kek: kek)
        let store = try LocalStore.open(database: database,
                                        databaseDomainKey: hierarchy.deriveKey(for: .database),
                                        capacity: UnlimitedCapacity(), now: { 0 })
        let id = try store.put(kind: .localProfile, plaintext: Data("private".utf8))

        let report = KeysFirstWipe()
            .registerKeyMaterial("local-store keys") { store.destroyKeys() }
            .registerKeyMaterial("kek") { kek.destroy() }
            .registerContent("local-store rows") { throw StageFailure() }
            .execute()

        XCTAssertFalse(report.fullyClean, "the interrupted content stage must be reported")
        XCTAssertTrue(report.allKeyMaterialDestroyed, "the key phase completed, so erasure holds")

        XCTAssertEqual(try store.count(kind: .localProfile), 1,
                       "the row is still physically present - that is the point of the test")
        XCTAssertThrowsError(try store.get(kind: .localProfile, id: id),
                             "and it is ciphertext without a key, which makes the interruption survivable")
    }

    func testEveryRecordKindIsClearedByTheContentStage() throws {
        let database = try Sqlite3Database(path: ":memory:")
        defer { database.close() }
        let key = try DataKeyHierarchy.create(kek: SoftwareKekStore()).deriveKey(for: .database)
        let store = try LocalStore.open(database: database, databaseDomainKey: key,
                                        capacity: UnlimitedCapacity(), now: { 0 })
        for kind in RecordKind.allCases {
            let natural = kind.isLookedUpByTag ? Data("peer".utf8) : nil
            try store.put(kind: kind, plaintext: Data("x".utf8), naturalKey: natural)
        }

        try store.deleteEverything()

        for kind in RecordKind.allCases {
            XCTAssertEqual(try store.count(kind: kind), 0,
                           "\(kind.table) must be cleared; a table missed here is data the user asked to destroy")
        }
    }
}
