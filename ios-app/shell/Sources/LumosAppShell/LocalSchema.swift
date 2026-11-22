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
    /// Onboarding consent evidence: the age-gate affirmation and the accepted
    /// legal document versions (SRS-ONB-001, SRS-ONB-002). Added at version 2.
    /// The declared date of birth is not here and is not anywhere.
    case consentRecord = "consent_record"
    /// Whether the user has turned Visible Nearby on, and when (SRS-ONB-008).
    /// Added at version 2. Absence reads as off.
    case discoveryVisibility = "discovery_visibility"
    /// Whether the user has turned App Lock on (ADR-010). Added at version 3.
    /// Absence reads as not enabled; the record carries the choice, never
    /// anything that could unlock it.
    case appLock = "app_lock"
    /// Peers the user hid from the Nearby feed (SRS-NBY-012). Added at
    /// version 4. Keyed by authenticated identity, blind-indexed like the
    /// block list and for the same reason; hiding is silent.
    case hiddenPeer = "hidden_peer"
    /// Per-peer interest machine state (TRS Sec. 4.2). Added at version 5;
    /// blind-indexed - who the user is interested in, and who declined
    /// whom, is at least as private as who they blocked.
    case interestRecord = "interest_record"
    /// Established matches (SRS-MAT-005/007): the stable identifier, keyed
    /// by the identity's blind index. Added at version 5; unmatching
    /// changes its state, never silently deletes the fact (SRS-MAT-010).
    case matchRecord = "match_record"
    /// The single-row rolling-hour interest budget (SRS-MAT-011): sent-at
    /// timestamps only, persisted so a restart cannot reset the flood
    /// limit (SRS-SAF-006). Added at version 5.
    case interestBudget = "interest_budget"
    /// A conversation with a matched peer, keyed by the match id's blind
    /// index. Added at version 6 (WP-05 S7b).
    case conversation = "conversation"
    /// One message or applied event, keyed by the match id's blind index.
    /// Added at version 6.
    case chatMessage = "chat_message"
    /// A send waiting for its transport (TRS-PAT-004). Added at version 6.
    case outboxItem = "outbox_item"
    /// One conversation's Double Ratchet state. Added at version 6.
    case ratchetSession = "ratchet_session"
    /// A displayed message id, kept until it expires. Added at version 6.
    case messageDedup = "message_dedup"
    /// A private prekey scalar, keyed by its id's blind index. Added at version 6.
    case prekeySecret = "prekey_secret"
    /// This device's relay registration (routing id and credential), one
    /// sealed row. Added at version 7 (WP-05 S7c).
    case relayAccount = "relay_account"
    /// One conversation's relay route, keyed by the match id's blind index.
    /// Added at version 7.
    case relayRoute = "relay_route"

    /// The table name for this kind.
    public var table: String { rawValue }

    /// Whether this kind is looked up by a blind index over a natural key.
    ///
    /// The peer-keyed kinds: each must answer "what do we hold for this
    /// peer?" without the peer identifier being readable in the file.
    public var isLookedUpByTag: Bool {
        switch self {
        case .blockEntry, .hiddenPeer, .interestRecord, .matchRecord,
             .conversation, .chatMessage, .ratchetSession, .prekeySecret, .relayRoute: return true
        default: return false
        }
    }
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
    public static let version = 7

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

    /// The record kinds version 1 created, frozen as a literal list.
    ///
    /// A literal and not `RecordKind.allCases`, for the reason the Java side
    /// states at the same place: a past migration describes what a past build
    /// did, and deriving it from today's enum rewrites history. A kind added
    /// later joins a new step, never an existing one (TRS-LOC-003).
    private static let version1Kinds: [RecordKind] = [
        .identityKeyRecord, .recoveryRecord, .localProfile,
        .profileFieldPolicy, .profileMedia, .blockEntry, .diagnosticEvent
    ]

    /// The record kinds version 2 added (WP-02 slice S4c).
    private static let version2Kinds: [RecordKind] = [.consentRecord, .discoveryVisibility]

    /// The record kinds version 3 added (WP-03 slice S5b).
    private static let version3Kinds: [RecordKind] = [.appLock]

    /// The record kinds version 4 added (WP-04 slice S1a).
    private static let version4Kinds: [RecordKind] = [.hiddenPeer]

    /// The record kinds version 5 added (WP-04 slice S3).
    private static let version5Kinds: [RecordKind] = [
        .interestRecord, .matchRecord, .interestBudget
    ]

    /// The record kinds version 6 added (WP-05 S7b).
    private static let version6Kinds: [RecordKind] = [
        .conversation, .chatMessage, .outboxItem, .ratchetSession, .messageDedup, .prekeySecret
    ]

    /// The record kinds version 7 added (WP-05 S7c).
    private static let version7Kinds: [RecordKind] = [.relayAccount, .relayRoute]

    /// The complete statement list taking an empty database to version 1.
    public static var migrationToVersion1: [String] {
        ["CREATE TABLE \(tableMeta) ("
            + "\(metaKey) TEXT PRIMARY KEY NOT NULL, "
            + "\(metaValue) TEXT NOT NULL)"] + createTables(for: version1Kinds)
    }

    /// The statements taking a version-1 database to version 2.
    ///
    /// Two new tables and nothing else: no existing table is altered and no row
    /// is rewritten, so an interrupted upgrade leaves a version-1 database
    /// rather than a half-converted one.
    public static var migrationToVersion2: [String] { createTables(for: version2Kinds) }

    /// The statements taking a version-2 database to version 3: one new
    /// table, the same interruption-safe shape as the step before it.
    public static var migrationToVersion3: [String] { createTables(for: version3Kinds) }

    /// The statements taking a version-3 database to version 4: one new
    /// table, the interruption-safe shape every step of this ladder keeps.
    public static var migrationToVersion4: [String] { createTables(for: version4Kinds) }

    /// The statements taking a version-4 database to version 5: three new
    /// tables, the interruption-safe shape every step of this ladder keeps.
    public static var migrationToVersion5: [String] { createTables(for: version5Kinds) }

    /// The statements taking a version-5 database to version 6: the six chat
    /// tables, the interruption-safe shape every step of this ladder keeps.
    public static var migrationToVersion6: [String] { createTables(for: version6Kinds) }

    /// The statements taking a version-6 database to version 7: the relay
    /// account and relay route tables and nothing else.
    public static var migrationToVersion7: [String] { createTables(for: version7Kinds) }

    /// The create-table and create-index pair for each of the given kinds.
    /// - Parameter kinds: the kinds a migration step introduces
    /// - Returns: the ordered statements
    private static func createTables(for kinds: [RecordKind]) -> [String] {
        kinds.flatMap { [createRecordTable($0.table), createLookupIndex($0.table)] }
    }

    /// The forward-only migration ladder.
    ///
    /// There is no downgrade path and there will never be one: a downgrade
    /// would have to discard data the newer version wrote, which on this schema
    /// means discarding sealed payloads nobody can inspect first. A database
    /// from the future is refused, not "handled" (TRS-LOC-003).
    public static var migrations: [Migration] {
        [Migration(fromVersion: versionEmpty, toVersion: 1, statements: migrationToVersion1),
         Migration(fromVersion: 1, toVersion: 2, statements: migrationToVersion2),
         Migration(fromVersion: 2, toVersion: 3, statements: migrationToVersion3),
         Migration(fromVersion: 3, toVersion: 4, statements: migrationToVersion4),
         Migration(fromVersion: 4, toVersion: 5, statements: migrationToVersion5),
         Migration(fromVersion: 5, toVersion: 6, statements: migrationToVersion6),
         Migration(fromVersion: 6, toVersion: 7, statements: migrationToVersion7)]
    }
}
