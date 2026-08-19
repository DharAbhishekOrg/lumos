/// Lumos - ios-app/shell / Recovery setup, restore, and the identity reset.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-ONB-007, SRS-DAT-004, SRS-IDN-002, SRS-IDN-003
/// ADRs: ADR-003, ADR-008, ADR-010
/// Threading: serial-executor
/// Notes: What the S4b record CARRIES, on top of the format S4b proved. The
/// format knows nothing about profiles and this knows nothing about Argon2id,
/// and the Android counterparts are RecoverySection, RecoveryBundle,
/// RecoveryBundleCodec, RecoverySetup, RecoveryRestore and IdentityReset.
///
/// The bundle format is shared across platforms and is written out in
/// `RecoveryBundleCodec` below, because a record exported on Android and
/// restored on iOS is the case a user hits when they change phone - which is
/// most of the point of having the feature.
///
/// Three carried-across decisions:
///
/// - The manifest lists EVERY section including the ones this build cannot
///   fill. "Your conversations were not in the file because the app could not
///   save them yet" and "your conversations were lost" must be distinguishable.
/// - Restore creates a NEW identity, in the same call, before writing content.
///   If content were written first and identity creation then failed, the device
///   would hold a restored profile under the OLD identity - the state ADR-003
///   exists to prevent.
/// - Nothing here reads the identity table. ADR-003 makes identity keys
///   non-exportable and never escrowed, so the export path has no line that
///   touches them.

import Foundation

/// One section of a recovery bundle.
public enum RecoverySection: String, CaseIterable, Sendable {
    /// The user's own profile content (SRS-PRO-001).
    case profile = "profile"
    /// Per-field visibility policy (SRS-PRO-003).
    case profileFieldPolicy = "profile_field_policy"
    /// Profile media metadata; the encrypted bytes are not in the bundle.
    case profileMedia = "profile_media"
    /// The block list - a safety asset, and the one users most need back.
    case blockList = "block_list"
    /// Preferences, which in WP-02 means the Visible Nearby state.
    case preferences = "preferences"
    /// Match roster metadata. The match domain is WP-04.
    case matchRoster = "match_roster"
    /// Locally stored conversation history. The message domain is WP-05.
    case conversationHistory = "conversation_history"

    /// The section's stable label in the bundle format.
    public var label: String { rawValue }

    /// The record kind this section reads and writes, if it exists yet.
    public var kind: RecordKind? {
        switch self {
        case .profile: return .localProfile
        case .profileFieldPolicy: return .profileFieldPolicy
        case .profileMedia: return .profileMedia
        case .blockList: return .blockEntry
        case .preferences: return .discoveryVisibility
        case .matchRoster, .conversationHistory: return nil
        }
    }

    /// Whether this section can be filled by the code that exists today.
    public var isAvailable: Bool { kind != nil }
}

/// The assembled, unsealed content of a recovery export.
public struct RecoveryBundle: Sendable {

    /// One exported row.
    public struct Item: Sendable, Equatable {
        /// The opaque row identifier (TRS-LOC-010).
        public let id: String
        /// The row's opened content.
        public let content: Data

        /// Wraps one row.
        /// - Parameters:
        ///   - id: the opaque row identifier
        ///   - content: the row's opened content
        public init(id: String, content: Data) {
            self.id = id
            self.content = content
        }
    }

    /// When the export was made, in UTC milliseconds.
    public let exportedAtUtcMillis: Int64
    /// The rows of each section that could be filled.
    public let sections: [RecoverySection: [Item]]

    /// Assembles a bundle.
    /// - Parameters:
    ///   - exportedAtUtcMillis: when the export was made
    ///   - sections: the rows of each filled section
    public init(exportedAtUtcMillis: Int64, sections: [RecoverySection: [Item]]) {
        self.exportedAtUtcMillis = exportedAtUtcMillis
        self.sections = sections
    }

    /// The rows of one section.
    /// - Parameter section: the section
    /// - Returns: its rows, empty when the section carried none
    public func items(_ section: RecoverySection) -> [Item] {
        sections[section] ?? []
    }

    /// The sections this bundle actually carries rows for, in declaration order.
    public var filledSections: [RecoverySection] {
        RecoverySection.allCases.filter { !items($0).isEmpty }
    }

    /// The sections no build could fill at export time (SRS-ONB-007 honesty).
    public var unavailableSections: [RecoverySection] {
        RecoverySection.allCases.filter { !$0.isAvailable }
    }

    /// The total number of rows carried.
    public var rowCount: Int {
        RecoverySection.allCases.reduce(0) { $0 + items($1).count }
    }
}

/// Encodes and decodes the plaintext a recovery record seals.
///
/// The format, written out because these bytes end up in a file the user keeps
/// for a year and opens on a build nobody has written yet:
///
///     lumos-recovery-bundle/1
///     exported_at<TAB>1755561600000
///     unavailable<TAB>match_roster
///     row<TAB>profile<TAB><id><TAB><base64 content>
///
/// Unknown line tags are a REFUSAL, not a skip. A later build's bundle read by
/// an older one must fail loudly: silently dropping a section the reader does
/// not recognise would restore a partial profile and report success.
public enum RecoveryBundleCodec {

    /// Bundle format version; an unknown one fails closed.
    static let versionLine = "lumos-recovery-bundle/1"

    /// Encodes a bundle.
    /// - Parameter bundle: the assembled content
    /// - Returns: the plaintext to seal
    public static func encode(_ bundle: RecoveryBundle) -> Data {
        var out = versionLine + "\n"
        out += "exported_at\t\(bundle.exportedAtUtcMillis)\n"
        for section in bundle.unavailableSections {
            out += "unavailable\t\(section.label)\n"
        }
        for section in RecoverySection.allCases {
            for item in bundle.items(section) {
                out += "row\t\(section.label)\t\(item.id)\t\(item.content.base64EncodedString())\n"
            }
        }
        return Data(out.utf8)
    }

    /// Decodes a bundle.
    /// - Parameter plaintext: the opened record content
    /// - Returns: the bundle
    /// - Throws: ``StoreError/unreadable`` on an unknown version, line tag or
    ///   section label, or a malformed line
    public static func decode(_ plaintext: Data) throws -> RecoveryBundle {
        // String(bytes:encoding:) rather than String(decoding:as:): SwiftLint's
        // optional_data_string_conversion prefers the failable initialiser, and
        // the failure direction is right here anyway - bytes that are not UTF-8
        // become an empty string, which fails the version check below rather
        // than being silently repaired into something parseable.
        let lines = (String(bytes: plaintext, encoding: .utf8) ?? "")
            .split(separator: "\n", omittingEmptySubsequences: false)
        guard lines.first == versionLine[...] else { throw StoreError.unreadable }

        var exportedAt: Int64 = 0
        var sections: [RecoverySection: [RecoveryBundle.Item]] = [:]
        for line in lines.dropFirst() where !line.isEmpty {
            let parts = line.split(separator: "\t", omittingEmptySubsequences: false)
            switch String(parts[0]) {
            case "exported_at":
                guard parts.count == 2, let value = Int64(parts[1]) else {
                    throw StoreError.unreadable
                }
                exportedAt = value
            case "unavailable":
                guard parts.count == 2, section(labelled: String(parts[1])) != nil else {
                    throw StoreError.unreadable
                }
            case "row":
                guard parts.count == 4,
                      let section = section(labelled: String(parts[1])),
                      let content = Data(base64Encoded: String(parts[3])) else {
                    throw StoreError.unreadable
                }
                sections[section, default: []]
                    .append(RecoveryBundle.Item(id: String(parts[2]), content: content))
            default:
                throw StoreError.unreadable
            }
        }
        return RecoveryBundle(exportedAtUtcMillis: exportedAt, sections: sections)
    }

    /// Resolves a section label written by some build of this format.
    private static func section(labelled label: String) -> RecoverySection? {
        RecoverySection.allCases.first { $0.label == label }
    }
}

/// Builds a content-only recovery export from the local store.
///
/// The destination is the CALLER'S problem, deliberately. Threat model LT-25:
/// an export written to shared storage or a cloud drive is High severity, the
/// destination is the user's choice and outside our control, and the UI must say
/// so. This returns bytes; it does not write a file, so it cannot write one to
/// the wrong place.
///
/// ADR-010 requires action-level device authentication before an export. That
/// prompt belongs to the presentation layer that calls this - there is no app
/// target in WP-02 to host it - and the trace matrix says so.
public struct RecoverySetup {

    private let store: LocalStore
    private let now: @Sendable () -> Int64

    /// Assembles the setup flow.
    /// - Parameters:
    ///   - store: the opened local store
    ///   - now: the UTC millisecond clock
    public init(store: LocalStore, now: @escaping @Sendable () -> Int64) {
        self.store = store
        self.now = now
    }

    /// Reads every available section out of the store.
    ///
    /// Exposed separately from `export` so a preview screen can tell the user
    /// what is about to be in the file before they choose a passphrase.
    ///
    /// - Returns: the assembled content
    /// - Throws: ``StoreError`` on engine failure or an unopenable row
    public func assemble() throws -> RecoveryBundle {
        var sections: [RecoverySection: [RecoveryBundle.Item]] = [:]
        for section in RecoverySection.allCases {
            guard let kind = section.kind else { continue }
            sections[section] = try store.allOf(kind: kind)
                .map { RecoveryBundle.Item(id: $0.id, content: $0.content) }
        }
        return RecoveryBundle(exportedAtUtcMillis: now(), sections: sections)
    }

    /// Assembles and seals an export (SRS-ONB-007, SRS-DAT-004).
    /// - Parameter passphrase: the user's passphrase, UTF-8
    /// - Returns: the sealed record, for the user to store where they choose
    /// - Throws: ``StoreError`` or ``RecoveryError``
    public func export(passphrase: Data) throws -> Data {
        try RecoveryRecord.export(passphrase: passphrase,
                                  content: RecoveryBundleCodec.encode(try assemble()))
    }
}

/// Opens a recovery export and restores its content under a new identity.
public struct RecoveryRestore {

    /// What a restore produced, including the proof that the identity is new.
    public struct RestoreResult: Sendable {
        /// The identity the device had, if there was one.
        public let previousEd25519Public: Data?
        /// The identity the device has now.
        public let newEd25519Public: Data
        /// How many rows came back.
        public let restoredRows: Int
        /// Sections the export could not contain, for the user to be told about.
        public let sectionsNotInTheFile: [RecoverySection]

        /// Whether the identity actually changed.
        ///
        /// On a device that had no identity this is true: there is now one that
        /// did not exist, and no peer relationship survived.
        public var identityIsNew: Bool {
            previousEd25519Public != newEd25519Public
        }
    }

    private let store: LocalStore
    private let identities: IdentityRepository
    private let kek: KekStore

    /// Assembles the restore flow.
    /// - Parameters:
    ///   - store: the opened local store the content is written into
    ///   - identities: where the device identity is stored
    ///   - kek: the key-encryption key the new identity is wrapped under
    public init(store: LocalStore, identities: IdentityRepository, kek: KekStore) {
        self.store = store
        self.identities = identities
        self.kek = kek
    }

    /// Opens an export and restores it onto a brand-new identity (SRS-ONB-007).
    /// - Parameters:
    ///   - passphrase: the user's passphrase, UTF-8
    ///   - sealed: the recovery record bytes the user supplied
    /// - Returns: what was restored, and the proof the identity changed
    /// - Throws: ``RecoveryError`` indistinguishably on a wrong passphrase or a
    ///   file this build cannot open (TRS-CRY-013), or ``StoreError``
    public func restore(passphrase: Data, sealed: Data) throws -> RestoreResult {
        let bundle = try RecoveryBundleCodec.decode(
            try RecoveryRecord.open(passphrase: passphrase, record: sealed))

        let before = try identities.load()
        let fresh = try IdentityKeyGenerator.generate(kek: kek)
        try identities.replace(fresh)

        var rows = 0
        for section in RecoverySection.allCases {
            guard let kind = section.kind else { continue }
            for item in bundle.items(section) {
                // The blind-index input is re-derived on THIS device from the
                // row content rather than carried in the file: a tag is a
                // stable function of a peer identifier, and a file full of them
                // would let anyone holding two exports tell whether the same
                // person appears on both block lists.
                let naturalKey = kind.isLookedUpByTag ? item.content : nil
                try store.put(kind: kind, id: item.id, plaintext: item.content,
                              naturalKey: naturalKey)
                rows += 1
            }
        }
        return RestoreResult(previousEd25519Public: before?.ed25519Public,
                             newEd25519Public: fresh.ed25519Public,
                             restoredRows: rows,
                             sectionsNotInTheFile: bundle.unavailableSections)
    }
}
