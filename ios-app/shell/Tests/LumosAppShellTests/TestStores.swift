/// Lumos - LumosAppShellTests / Shared in-memory store fixtures for the chat and relay tests.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-TST-001
/// ADRs: ADR-003
/// Threading: test-only
/// Notes: One opener instead of a private copy per test file. The capacity
/// type is named distinctly so it never collides with the older files'
/// file-private `UnboundedCapacity`.

import Foundation
@testable import LumosAppShell

/// Storage that never runs out, for tests that are not about low storage.
struct NoCapacityLimit: StorageCapacity {
    var usableSpaceBytes: Int64 { Int64.max }
}

enum TestStores {
    /// A migrated, sealed store over a fresh in-memory SQLite database.
    static func openInMemory(now: @escaping @Sendable () -> Int64 = { 0 }) throws -> LocalStore {
        let database = try Sqlite3Database(path: ":memory:")
        let key = try DataKeyHierarchy.create(kek: SoftwareKekStore()).deriveKey(for: .database)
        return try LocalStore.open(database: database, databaseDomainKey: key,
                                   capacity: NoCapacityLimit(), now: now)
    }

    /// A migrated, sealed store over a database FILE, so a test can drop every
    /// object and reopen it: the model of process death.
    static func openFile(_ path: String, key: Data, now: @escaping @Sendable () -> Int64 = { 0 }) throws -> LocalStore {
        try LocalStore.open(database: try Sqlite3Database(path: path), databaseDomainKey: key,
                            capacity: NoCapacityLimit(), now: now)
    }
}
