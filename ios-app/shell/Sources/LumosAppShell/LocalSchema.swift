/// Lumos - ios-app/shell / Canonical local schema and its forward-only migrations.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-LOC-002, TRS-LOC-003, TRS-LOC-010, SRS-DAT-002
/// ADRs: ADR-003
/// Threading: any
/// Notes: The same schema the Android side declares, statement for statement.
/// It is written out twice rather than generated once because neither platform
/// can consume the other's build output - and duplication that nothing checks
/// is duplication that drifts, so tools/schemacheck parses both files and fails
/// the build if the tables, columns, version or DDL text ever diverge.
///
/// Every table has the same shape on purpose: an opaque id, an AEAD-sealed
/// payload, an optional blind-index tag and UTC timestamps. The schema carries
/// no content, because the schema is readable by anyone holding the file
/// (threat model LT-16).

import Foundation

/// The record kinds the WP-02 local store holds, one table each.
public enum RecordKind: String, CaseIterable, Sendable {
    /// The device identity: public halves and the KEK-wrapped private envelope.
    case identityKeyRecord = "identity_key_record"
    /// Content-only recovery material per ADR-003; never key material.
    case recoveryRecord = "recovery_record"
    /// The user's own profile content.
    case localProfile = "local_profile"
    /// Per-field visibility policy (SRS-PRO-003).
    case profileFieldPolicy = "profile_field_policy"
    /// Profile media metadata; the bytes live in the object store.
    case profileMedia = "profile_media"
    /// Blocked peers. Looked up by a blind index over the peer identifier.
    case blockEntry = "block_entry"
    /// Local diagnostic events, redaction-checked before they are written.
    case diagnosticEvent = "diagnostic_event"

    /// The table name for this kind.
    public var table: String { rawValue }

    /// Whether this kind is looked up by a blind index over a natural key.
    ///
    /// Only `blockEntry` is today: a block list must answer "is this peer
    /// blocked?" without the peer identifier being readable in the file.
    public var isLookedUpByTag: Bool { self == .blockEntry }
}

/// One step of the forward-only migration ladder.
public struct Migration: Sendable {
    /// The version this step upgrades from.
    public let fromVersion: Int
    /// The version this step produces.
    public let toVersion: Int
    /// The ordered SQL statements.
    public let statements: [String]
}

/// The local database schema: table names, DDL and the migration ladder.
public enum LocalSchema {

    /// The schema version this build writes and expects.
    public static let version = 1

    /// The version of an absent or empty database, before any migration runs.
    public static let versionEmpty = 0

    /// Table holding the schema version and nothing else.
    public static let tableMeta = "lumos_meta"

    /// The meta table's single-row key column.
    public static let metaKey = "meta_key"

    /// The meta table's value column.
    public static let metaValue = "meta_value"

    /// Meta key under which the schema version is stored.
    public static let metaSchemaVersion = "schema_version"

    /// Opaque stable row identifier (TRS-LOC-010: a UUID, never a natural key).
    public static let columnId = "id"

    /// The AEAD-sealed record payload.
    public static let columnPayload = "payload"

    /// Blind-index tag for lookups by a natural key that must not be stored.
    public static let columnLookupTag = "lookup_tag"

    /// Creation time, UTC milliseconds since the epoch (TRS-LOC-010).
    public static let columnCreatedAt = "created_at"

    /// Last-update time, UTC milliseconds since the epoch (TRS-LOC-010).
    public static let columnUpdatedAt = "updated_at"

    /// The schema version that sealed this row's payload, for forward reads.
    public static let columnPayloadVersion = "payload_version"

    /// The DDL creating one record table.
    /// - Parameter table: the table name
    /// - Returns: the CREATE TABLE statement
    public static func createRecordTable(_ table: String) -> String {
        "CREATE TABLE \(table) ("
            + "\(columnId) TEXT PRIMARY KEY NOT NULL, "
            + "\(columnPayload) BLOB NOT NULL, "
            + "\(columnLookupTag) BLOB, "
            + "\(columnCreatedAt) INTEGER NOT NULL, "
            + "\(columnUpdatedAt) INTEGER NOT NULL, "
            + "\(columnPayloadVersion) INTEGER NOT NULL)"
    }

    /// The DDL creating one table's blind-index lookup index.
    /// - Parameter table: the table name
    /// - Returns: the CREATE INDEX statement
    public static func createLookupIndex(_ table: String) -> String {
        "CREATE INDEX idx_\(table)_lookup ON \(table) (\(columnLookupTag))"
    }

    /// The complete statement list taking an empty database to version 1.
    public static var migrationToVersion1: [String] {
        var statements = ["CREATE TABLE \(tableMeta) ("
            + "\(metaKey) TEXT PRIMARY KEY NOT NULL, "
            + "\(metaValue) TEXT NOT NULL)"]
        for kind in RecordKind.allCases {
            statements.append(createRecordTable(kind.table))
            statements.append(createLookupIndex(kind.table))
        }
        return statements
    }

    /// The forward-only migration ladder.
    ///
    /// There is no downgrade path and there will never be one: a downgrade
    /// would have to discard data the newer version wrote, which on this schema
    /// means discarding sealed payloads nobody can inspect first. A database
    /// from the future is refused, not "handled" (TRS-LOC-003).
    public static var migrations: [Migration] {
        [Migration(fromVersion: versionEmpty, toVersion: 1, statements: migrationToVersion1)]
    }
}
