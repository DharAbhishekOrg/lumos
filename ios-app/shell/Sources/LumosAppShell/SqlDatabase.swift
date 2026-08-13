/// Lumos - ios-app/shell / The SQL seam and its system-SQLite implementation.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-ARCH-003, TRS-LOC-003, SRS-DAT-002
/// ADRs: -
/// Threading: serial-executor
/// Notes: iOS is the easier half of this problem: SQLite ships in the OS, so
/// the implementation CI executes on macOS is the same code that runs on a
/// device - no adapter, no seam-only-in-production gap of the kind the Android
/// side has to live with. The seam exists anyway, because the store should not
/// know which engine it is talking to and because a fake is occasionally useful
/// for failure injection.
///
/// `inTransaction` takes a closure rather than exposing begin/commit: crash
/// consistency (TRS-LOC-003) depends on rollback being unskippable, and a
/// caller that could forget to commit eventually would.

import Foundation
import SQLite3

/// The transient SQLITE_TRANSIENT destructor, which tells SQLite to copy bound
/// bytes rather than borrow them. Swift cannot import the C macro.
private let sqliteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

/// Every way the local store can fail, in the granularity callers may see.
public enum StoreError: Error, Equatable, Sendable {
    /// The engine refused the statement, or the file is unusable.
    case engine(String)
    /// A row could not be opened: tampered, wrong key, wrong context.
    /// Deliberately carries no detail - which check failed is not a caller's
    /// business and would be an oracle (TRS-CRY-013).
    case unreadable
    /// Free space is below the floor a write needs (TRS-LOC-006).
    case lowStorage
    /// The database is newer than this build; forward-only means refuse.
    case schemaFromTheFuture(found: Int, understood: Int)
    /// The migration ladder itself is malformed.
    case migrationLadder(String)
}

/// A value bound into a statement.
public enum SqlValue: Sendable, Equatable {
    /// Text.
    case text(String)
    /// A 64-bit integer.
    case integer(Int64)
    /// A byte blob.
    case blob(Data)
    /// SQL NULL.
    case null
}

/// The minimum SQL surface the local store needs.
public protocol SqlDatabase: AnyObject {
    /// Executes a statement that returns no rows.
    /// - Parameters:
    ///   - sql: the statement, with `?` placeholders
    ///   - arguments: the bound values, in order
    /// - Throws: ``StoreError/engine(_:)`` on any engine failure
    func execute(_ sql: String, _ arguments: [SqlValue]) throws

    /// Runs a parameterised query.
    /// - Parameters:
    ///   - sql: the query, with `?` placeholders
    ///   - arguments: the bound values, in order
    /// - Returns: the result rows, each a column-name-to-value map
    /// - Throws: ``StoreError/engine(_:)`` on any engine failure
    func query(_ sql: String, _ arguments: [SqlValue]) throws -> [[String: SqlValue]]

    /// Runs work inside a transaction, committing on return and rolling back on
    /// any thrown error.
    /// - Parameter body: the unit of work
    /// - Returns: the work's result
    /// - Throws: whatever the body throws, after the rollback
    func inTransaction<T>(_ body: () throws -> T) throws -> T

    /// Closes the underlying database.
    func close()
}

/// SqlDatabase over the system SQLite.
public final class Sqlite3Database: SqlDatabase {

    private var handle: OpaquePointer?

    /// Opens or creates a database at a path.
    /// - Parameter path: the file path, or `":memory:"`
    /// - Throws: ``StoreError/engine(_:)`` if SQLite refuses
    public init(path: String) throws {
        var handle: OpaquePointer?
        guard sqlite3_open(path, &handle) == SQLITE_OK, handle != nil else {
            throw StoreError.engine("cannot open database")
        }
        self.handle = handle
    }

    deinit {
        if let handle {
            sqlite3_close(handle)
        }
    }

    public func execute(_ sql: String, _ arguments: [SqlValue] = []) throws {
        let statement = try prepare(sql, arguments)
        defer { sqlite3_finalize(statement) }
        let step = sqlite3_step(statement)
        guard step == SQLITE_DONE || step == SQLITE_ROW else {
            throw StoreError.engine("statement failed")
        }
    }

    public func query(_ sql: String, _ arguments: [SqlValue] = []) throws -> [[String: SqlValue]] {
        let statement = try prepare(sql, arguments)
        defer { sqlite3_finalize(statement) }

        var rows: [[String: SqlValue]] = []
        while true {
            let step = sqlite3_step(statement)
            if step == SQLITE_DONE { break }
            guard step == SQLITE_ROW else { throw StoreError.engine("query failed") }
            rows.append(readRow(statement))
        }
        return rows
    }

    public func inTransaction<T>(_ body: () throws -> T) throws -> T {
        try execute("BEGIN IMMEDIATE", [])
        do {
            let result = try body()
            try execute("COMMIT", [])
            return result
        } catch {
            // Rollback on ANY escape. A failed rollback must not mask the
            // original failure, so its own error is deliberately discarded.
            try? execute("ROLLBACK", [])
            throw error
        }
    }

    public func close() {
        if let handle {
            sqlite3_close(handle)
            self.handle = nil
        }
    }

    /// Prepares and binds a statement.
    /// - Parameters:
    ///   - sql: the statement text
    ///   - arguments: the bound values
    /// - Returns: the prepared statement, which the caller finalises
    /// - Throws: ``StoreError/engine(_:)`` if preparation fails
    private func prepare(_ sql: String, _ arguments: [SqlValue]) throws -> OpaquePointer? {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &statement, nil) == SQLITE_OK else {
            throw StoreError.engine("statement could not be prepared")
        }
        for (offset, value) in arguments.enumerated() {
            let index = Int32(offset + 1)
            switch value {
            case .text(let text):
                sqlite3_bind_text(statement, index, text, -1, sqliteTransient)
            case .integer(let number):
                sqlite3_bind_int64(statement, index, number)
            case .blob(let data):
                _ = data.withUnsafeBytes { buffer in
                    sqlite3_bind_blob(statement, index, buffer.baseAddress,
                                      Int32(buffer.count), sqliteTransient)
                }
            case .null:
                sqlite3_bind_null(statement, index)
            }
        }
        return statement
    }

    /// Materialises one row of a stepped statement.
    /// - Parameter statement: a statement positioned on a row
    /// - Returns: the row as a column-name-to-value map
    private func readRow(_ statement: OpaquePointer?) -> [String: SqlValue] {
        var row: [String: SqlValue] = [:]
        for column in 0..<sqlite3_column_count(statement) {
            let name = String(cString: sqlite3_column_name(statement, column))
            switch sqlite3_column_type(statement, column) {
            case SQLITE_INTEGER:
                row[name] = .integer(sqlite3_column_int64(statement, column))
            case SQLITE_BLOB:
                if let bytes = sqlite3_column_blob(statement, column) {
                    let count = Int(sqlite3_column_bytes(statement, column))
                    row[name] = .blob(Data(bytes: bytes, count: count))
                } else {
                    row[name] = .blob(Data())
                }
            case SQLITE_NULL:
                row[name] = .null
            default:
                if let text = sqlite3_column_text(statement, column) {
                    row[name] = .text(String(cString: text))
                } else {
                    row[name] = .null
                }
            }
        }
        return row
    }
}
