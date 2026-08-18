/// Lumos - ios-app/shell / Record sealing, the blind index and the store.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-DAT-002, TRS-LOC-002, TRS-LOC-003, TRS-LOC-006, TRS-LOC-010
/// ADRs: ADR-001, ADR-003
/// Threading: serial-executor
/// Notes: The iOS half of the same design as the Android store, down to the
/// HKDF labels and the AAD construction - not for interoperability (nothing
/// here crosses a device boundary) but because one concept should have one
/// shape on both platforms (TRS-CODE-007), and because a divergence would then
/// be a visible difference between two files rather than a subtle behavioural
/// gap nobody notices.
///
/// The AAD is the row's identity: table, id and payload version. Without it a
/// sealed payload could be MOVED between rows or tables by anyone with write
/// access to the file - which threat model LT-10 grants ADV-3 and ADV-5 - and
/// would still open. With it, a relocated row fails exactly as a tampered one.
///
/// The blind index is a KEYED hash: an unkeyed hash of a peer identifier is
/// reversible by anyone who can guess candidates, and for a block list that is
/// precisely the adversary who matters (ADV-2, the intimate-partner case).

import CryptoKit
import Foundation

/// Decides whether there is room to write.
public protocol StorageCapacity: Sendable {
    /// Free bytes available to this application.
    var usableSpaceBytes: Int64 { get }
}

extension StorageCapacity {
    /// The free-space floor below which writes are refused, in bytes.
    ///
    /// 16 MiB: more than the largest transaction this schema can produce, with
    /// headroom for SQLite's journal and the OS during a commit; small enough
    /// that the app is not refusing writes on a device the user still finds
    /// usable. A named constant with rationale, which TRS-CODE-005 permits for
    /// a value with no Sec. 23 register entry - a device-local storage floor is
    /// neither protocol nor signed-config material.
    public static var writeFloorBytes: Int64 { 16 * 1024 * 1024 }

    /// Refuses the caller when free space is below the floor.
    /// - Throws: ``StoreError/lowStorage``
    public func requireRoomToWrite() throws {
        if usableSpaceBytes < Self.writeFloorBytes {
            throw StoreError.lowStorage
        }
    }
}

/// Free space as reported by the filesystem at a path.
public struct FileSystemCapacity: StorageCapacity {
    private let path: String

    /// Reports capacity at a directory.
    /// - Parameter path: the directory holding the database
    public init(path: String) {
        self.path = path
    }

    public var usableSpaceBytes: Int64 {
        let attributes = try? FileManager.default.attributesOfFileSystem(forPath: path)
        return (attributes?[.systemFreeSize] as? NSNumber)?.int64Value ?? 0
    }
}

/// Seals record payloads and computes blind-index tags.
struct SealedRecordCodec {
    /// HKDF context for the payload-encryption key.
    static let recordKeyInfo = "lumos/local/record-key/v1"
    /// HKDF context for the blind-index key.
    static let lookupKeyInfo = "lumos/local/lookup-key/v1"
    /// Blind-index tag length; collisions cost a decrypt-and-compare and the
    /// caller-side check that follows a lookup makes them harmless.
    static let lookupTagBytes = 16

    private var recordKey: SymmetricKey
    private var lookupKey: SymmetricKey

    /// Derives both record keys from the S1 database-domain key.
    /// - Parameter databaseDomainKey: the key from `DataKeyHierarchy.deriveKey(for: .database)`
    init(databaseDomainKey: Data) {
        let material = SymmetricKey(data: databaseDomainKey)
        let salt = Data(count: 32)
        recordKey = HKDF<SHA256>.deriveKey(inputKeyMaterial: material, salt: salt,
                                           info: Data(Self.recordKeyInfo.utf8), outputByteCount: 32)
        lookupKey = HKDF<SHA256>.deriveKey(inputKeyMaterial: material, salt: salt,
                                           info: Data(Self.lookupKeyInfo.utf8), outputByteCount: 32)
    }

    /// Seals a payload, binding it to the row it belongs in.
    /// - Parameters:
    ///   - kind: the record's table
    ///   - id: the row identifier
    ///   - plaintext: the record content
    /// - Returns: the sealed payload
    /// - Throws: ``StoreError/engine(_:)`` on cipher failure
    func seal(kind: RecordKind, id: String, plaintext: Data) throws -> Data {
        do {
            let sealed = try AES.GCM.seal(plaintext, using: recordKey,
                                          authenticating: Self.rowContext(kind: kind, id: id))
            guard let combined = sealed.combined else { throw StoreError.engine("record could not be sealed") }
            return combined
        } catch let error as StoreError {
            throw error
        } catch {
            throw StoreError.engine("record could not be sealed")
        }
    }

    /// Opens a sealed payload, refusing one that belongs to a different row.
    /// - Parameters:
    ///   - kind: the record's table
    ///   - id: the row identifier
    ///   - sealed: the stored payload
    /// - Returns: the record content
    /// - Throws: ``StoreError/unreadable`` for every cause, indistinguishably
    func open(kind: RecordKind, id: String, sealed: Data) throws -> Data {
        do {
            let box = try AES.GCM.SealedBox(combined: sealed)
            return try AES.GCM.open(box, using: recordKey,
                                    authenticating: Self.rowContext(kind: kind, id: id))
        } catch {
            throw StoreError.unreadable
        }
    }

    /// Computes the blind-index tag for a natural key.
    /// - Parameters:
    ///   - kind: the record's table, so a tag cannot match across kinds
    ///   - naturalKey: the value being indexed
    /// - Returns: the truncated keyed hash stored in `lookup_tag`
    func lookupTag(kind: RecordKind, naturalKey: Data) -> Data {
        var message = Data(kind.table.utf8)
        message.append(0)
        message.append(naturalKey)
        let mac = HMAC<SHA256>.authenticationCode(for: message, using: lookupKey)
        return Data(mac).prefix(Self.lookupTagBytes)
    }

    /// Replaces both derived keys with zeroes.
    ///
    /// A key-material stage of the keys-first wipe: afterwards the codec can
    /// only produce garbage, which is the intended state.
    mutating func destroy() {
        recordKey = SymmetricKey(data: Data(count: 32))
        lookupKey = SymmetricKey(data: Data(count: 32))
    }

    /// The AEAD associated data: the row's identity.
    /// - Parameters:
    ///   - kind: the record's table
    ///   - id: the row identifier
    /// - Returns: the context bytes bound into every tag
    private static func rowContext(kind: RecordKind, id: String) -> Data {
        Data("\(kind.table) \(id) \(LocalSchema.version)".utf8)
    }
}

/// The encrypted record store over a SQL engine.
public final class LocalStore {

    private let database: SqlDatabase
    private var codec: SealedRecordCodec
    private let capacity: StorageCapacity
    private let now: @Sendable () -> Int64

    private init(database: SqlDatabase, codec: SealedRecordCodec,
                 capacity: StorageCapacity, now: @escaping @Sendable () -> Int64) {
        self.database = database
        self.codec = codec
        self.capacity = capacity
        self.now = now
    }

    /// Opens the store, migrating the database to ``LocalSchema/version``.
    /// - Parameters:
    ///   - database: the engine seam
    ///   - databaseDomainKey: the S1 DATABASE domain key
    ///   - capacity: the low-storage seam
    ///   - now: a UTC-millisecond clock (TRS-LOC-010)
    /// - Returns: the opened store
    /// - Throws: ``StoreError`` if migration fails or the schema is from the future
    public static func open(database: SqlDatabase, databaseDomainKey: Data,
                            capacity: StorageCapacity,
                            now: @escaping @Sendable () -> Int64) throws -> LocalStore {
        try migrate(database: database)
        return LocalStore(database: database,
                          codec: SealedRecordCodec(databaseDomainKey: databaseDomainKey),
                          capacity: capacity, now: now)
    }

    /// Runs the forward-only migration ladder.
    /// - Parameter database: the engine seam
    /// - Throws: ``StoreError`` on a malformed ladder, a future schema or engine failure
    public static func migrate(database: SqlDatabase) throws {
        let current = storedVersion(database: database)
        guard current <= LocalSchema.version else {
            throw StoreError.schemaFromTheFuture(found: current, understood: LocalSchema.version)
        }
        var at = current
        for step in LocalSchema.migrations where step.fromVersion >= at {
            guard step.fromVersion == at else {
                throw StoreError.migrationLadder("ladder skips from \(at) to \(step.fromVersion)")
            }
            try database.inTransaction {
                for sql in step.statements {
                    try database.execute(sql, [])
                }
                try database.execute(
                    "INSERT INTO \(LocalSchema.tableMeta) (\(LocalSchema.metaKey), "
                        + "\(LocalSchema.metaValue)) VALUES (?, ?) "
                        + "ON CONFLICT(\(LocalSchema.metaKey)) DO UPDATE SET "
                        + "\(LocalSchema.metaValue) = excluded.\(LocalSchema.metaValue)",
                    [.text(LocalSchema.metaSchemaVersion), .text(String(step.toVersion))])
            }
            at = step.toVersion
        }
        guard at == LocalSchema.version else {
            throw StoreError.migrationLadder("ladder ends at \(at), schema version is \(LocalSchema.version)")
        }
    }

    /// Reads the stored schema version, treating an absent meta table as empty.
    /// - Parameter database: the engine seam
    /// - Returns: the current version, or ``LocalSchema/versionEmpty``
    static func storedVersion(database: SqlDatabase) -> Int {
        guard let rows = try? database.query(
            "SELECT \(LocalSchema.metaValue) FROM \(LocalSchema.tableMeta) "
                + "WHERE \(LocalSchema.metaKey) = ?",
            [.text(LocalSchema.metaSchemaVersion)]),
            case .text(let value)? = rows.first?[LocalSchema.metaValue],
            let version = Int(value) else {
            // No meta table is the empty database: a state, not a failure.
            return LocalSchema.versionEmpty
        }
        return version
    }

    /// Writes a record, sealing its payload.
    /// - Parameters:
    ///   - kind: the record's table
    ///   - id: the row identifier, or nil to mint a fresh UUID
    ///   - plaintext: the record content
    ///   - naturalKey: the blind-index input, or nil when the kind has none
    /// - Returns: the row identifier written
    /// - Throws: ``StoreError`` on low storage, engine failure or a missing tag
    @discardableResult
    public func put(kind: RecordKind, id: String? = nil, plaintext: Data,
                    naturalKey: Data? = nil) throws -> String {
        try capacity.requireRoomToWrite()
        if kind.isLookedUpByTag && naturalKey == nil {
            throw StoreError.engine("\(kind.table) is looked up by tag and needs a natural key")
        }
        let rowId = id ?? UUID().uuidString
        let sealed = try codec.seal(kind: kind, id: rowId, plaintext: plaintext)
        let tag: SqlValue = naturalKey.map { .blob(codec.lookupTag(kind: kind, naturalKey: $0)) } ?? .null
        let timestamp = now()

        try database.inTransaction {
            try database.execute(
                "INSERT INTO \(kind.table) (\(LocalSchema.columnId), \(LocalSchema.columnPayload), "
                    + "\(LocalSchema.columnLookupTag), \(LocalSchema.columnCreatedAt), "
                    + "\(LocalSchema.columnUpdatedAt), \(LocalSchema.columnPayloadVersion)) "
                    + "VALUES (?, ?, ?, ?, ?, ?) "
                    + "ON CONFLICT(\(LocalSchema.columnId)) DO UPDATE SET "
                    + "\(LocalSchema.columnPayload) = excluded.\(LocalSchema.columnPayload), "
                    + "\(LocalSchema.columnLookupTag) = excluded.\(LocalSchema.columnLookupTag), "
                    + "\(LocalSchema.columnUpdatedAt) = excluded.\(LocalSchema.columnUpdatedAt)",
                [.text(rowId), .blob(sealed), tag, .integer(timestamp), .integer(timestamp),
                 .integer(Int64(LocalSchema.version))])
        }
        return rowId
    }

    /// Reads and opens a record.
    ///
    /// Absence is nil; only an unopenable row throws. The two are different
    /// answers, and a caller that cannot tell them apart will eventually treat
    /// a wiped profile as an empty one.
    /// - Parameters:
    ///   - kind: the record's table
    ///   - id: the row identifier
    /// - Returns: the record content, or nil when no such row exists
    /// - Throws: ``StoreError/unreadable`` if the row exists but cannot be opened
    public func get(kind: RecordKind, id: String) throws -> Data? {
        let rows = try database.query(
            "SELECT \(LocalSchema.columnPayload) FROM \(kind.table) "
                + "WHERE \(LocalSchema.columnId) = ?", [.text(id)])
        guard case .blob(let sealed)? = rows.first?[LocalSchema.columnPayload] else {
            return nil
        }
        return try codec.open(kind: kind, id: id, sealed: sealed)
    }

    /// Finds records by their blind-index tag.
    /// - Parameters:
    ///   - kind: the record's table
    ///   - naturalKey: the value being looked up
    /// - Returns: every matching record's content, opened
    /// - Throws: ``StoreError`` on engine failure or an unopenable row
    public func findByNaturalKey(kind: RecordKind, naturalKey: Data) throws -> [Data] {
        let rows = try database.query(
            "SELECT \(LocalSchema.columnId), \(LocalSchema.columnPayload) FROM \(kind.table) "
                + "WHERE \(LocalSchema.columnLookupTag) = ?",
            [.blob(codec.lookupTag(kind: kind, naturalKey: naturalKey))])
        return try rows.compactMap { row in
            guard case .text(let id)? = row[LocalSchema.columnId],
                  case .blob(let sealed)? = row[LocalSchema.columnPayload] else { return nil }
            return try codec.open(kind: kind, id: id, sealed: sealed)
        }
    }

    /// One row of a kind: its identifier and its opened content.
    public struct Entry: Sendable, Equatable {
        /// The opaque row identifier.
        public let id: String
        /// The opened record content.
        public let content: Data
    }

    /// Reads and opens every row of a kind, in identifier order.
    ///
    /// Added for the recovery export, which has to enumerate what it is
    /// exporting rather than ask for identifiers it already knows. Ordering by
    /// the identifier makes the export deterministic for a given store, which
    /// is what lets a test compare two exports of the same content.
    ///
    /// - Parameter kind: the record's table
    /// - Returns: every row, opened
    /// - Throws: ``StoreError`` on engine failure or an unopenable row
    public func allOf(kind: RecordKind) throws -> [Entry] {
        let rows = try database.query(
            "SELECT \(LocalSchema.columnId), \(LocalSchema.columnPayload) FROM \(kind.table) "
                + "ORDER BY \(LocalSchema.columnId)", [])
        return try rows.compactMap { row in
            guard case .text(let id)? = row[LocalSchema.columnId],
                  case .blob(let sealed)? = row[LocalSchema.columnPayload] else { return nil }
            return Entry(id: id, content: try codec.open(kind: kind, id: id, sealed: sealed))
        }
    }

    /// Deletes a record.
    /// - Parameters:
    ///   - kind: the record's table
    ///   - id: the row identifier
    /// - Throws: ``StoreError`` on engine failure
    public func delete(kind: RecordKind, id: String) throws {
        try database.execute("DELETE FROM \(kind.table) WHERE \(LocalSchema.columnId) = ?", [.text(id)])
    }

    /// Deletes every row of every record table, in one transaction.
    ///
    /// The content stage of the keys-first wipe. It runs after the key material
    /// is gone, at which point everything it deletes is already ciphertext
    /// nobody can open - which is why a failure here costs the wipe its
    /// tidiness and not its guarantee.
    /// - Throws: ``StoreError`` on engine failure
    public func deleteEverything() throws {
        try database.inTransaction {
            for kind in RecordKind.allCases {
                try database.execute("DELETE FROM \(kind.table)", [])
            }
        }
    }

    /// Destroys the store's derived keys.
    public func destroyKeys() {
        codec.destroy()
    }

    /// Counts rows of a kind, without opening any of them.
    /// - Parameter kind: the record's table
    /// - Returns: the row count
    /// - Throws: ``StoreError`` on engine failure
    public func count(kind: RecordKind) throws -> Int {
        let rows = try database.query("SELECT COUNT(*) AS n FROM \(kind.table)", [])
        guard case .integer(let n)? = rows.first?["n"] else { return 0 }
        return Int(n)
    }
}
