/// Lumos - ios-app/shell / Legal documents, consent evidence and Visible Nearby.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-ONB-002, SRS-ONB-008, SRS-DAT-002
/// ADRs: ADR-002
/// Threading: serial-executor
/// Notes: The Android counterparts are LegalDocument, LegalDocuments,
/// ConsentRecord, ConsentCodec, ConsentRepository and DiscoveryVisibility. Same
/// names, same payload formats, same defaults - the payload formats especially,
/// because `lumos-consent/1` and `lumos-visibility/1` are read by whichever
/// platform wrote them and a divergence would surface as a re-consent prompt on
/// one platform only.
///
/// Three things carried across deliberately:
///
/// - The version of each document is read from the document, on one
///   machine-readable line, not from a constant beside it. A constant could say
///   3 while the file said 2, and the consent record would then attest to a
///   version nobody displayed.
/// - A document that will not load is a refusal, not a default. There is no
///   fallback version.
/// - Visible Nearby is off unless a stored record says otherwise, and exactly
///   one method can turn it on (SRS-ONB-008, threat model LT-33). None of the
///   four triggers the requirement names has an entry point here.

import Foundation

/// A document the user must be shown and must accept before onboarding completes.
public enum LegalDocument: String, CaseIterable, Sendable, Comparable {
    /// The agreement itself.
    case termsOfUse = "TERMS_OF_USE"
    /// What is collected, what leaves the device, and what never does.
    case privacyNotice = "PRIVACY_NOTICE"
    /// How people are expected to behave, incorporated by the terms.
    case communityRules = "COMMUNITY_RULES"
    /// What the phone's Bluetooth radio transmits and listens for (ADR-002).
    case localRadioDisclosure = "LOCAL_RADIO_DISCLOSURE"

    /// The document's file name, under docs/legal and inside the app bundle.
    public var assetName: String {
        switch self {
        case .termsOfUse: return "terms-of-use.md"
        case .privacyNotice: return "privacy-notice.md"
        case .communityRules: return "community-rules.md"
        case .localRadioDisclosure: return "local-radio-disclosure.md"
        }
    }

    /// Enum-declaration order, so the two platforms iterate identically.
    public static func < (lhs: LegalDocument, rhs: LegalDocument) -> Bool {
        guard let left = allCases.firstIndex(of: lhs),
              let right = allCases.firstIndex(of: rhs) else { return false }
        return left < right
    }
}

/// Failures loading the shipped documents.
public enum LegalDocumentError: Error, Equatable, Sendable {
    /// The document could not be read.
    case unreadable(String)
    /// The document carries no `Document version:` line.
    case noVersionLine(String)
}

/// Loads the shipped legal documents and the version each declares.
public struct LegalDocuments: Sendable {

    /// The current version of every document.
    public let versions: [LegalDocument: String]

    /// Loads every document and extracts its declared version.
    ///
    /// A partial set is never returned: consent to three documents out of four
    /// is not consent.
    ///
    /// - Parameter read: supplies each document's text
    /// - Returns: the version of each of the four documents
    /// - Throws: ``LegalDocumentError`` if any is missing or declares no version
    public static func load(
        read: (LegalDocument) throws -> String
    ) throws -> LegalDocuments {
        var found: [LegalDocument: String] = [:]
        for document in LegalDocument.allCases {
            let text: String
            do {
                text = try read(document)
            } catch {
                throw LegalDocumentError.unreadable(document.assetName)
            }
            guard let version = declaredVersion(in: text) else {
                throw LegalDocumentError.noVersionLine(document.assetName)
            }
            found[document] = version
        }
        return LegalDocuments(versions: found)
    }

    /// The current version of one document.
    /// - Parameter document: the document
    /// - Returns: its declared version, or nil if it was not loaded
    public func version(of document: LegalDocument) -> String? {
        versions[document]
    }

    /// Extracts the `Document version:` declaration from a document's text.
    ///
    /// Only a line that IS the declaration counts, so a version mentioned in
    /// prose cannot be mistaken for it. Surrounding Markdown emphasis is
    /// tolerated because the documents bold the line.
    ///
    /// - Parameter text: the document
    /// - Returns: the version, or nil when there is no declaration
    static func declaredVersion(in text: String) -> String? {
        for rawLine in text.split(separator: "\n", omittingEmptySubsequences: false) {
            var line = rawLine.trimmingCharacters(in: .whitespaces)
            while line.hasPrefix("*") { line.removeFirst() }
            while line.hasSuffix("*") { line.removeLast() }
            let marker = "Document version:"
            guard line.hasPrefix(marker) else { continue }
            let version = line.dropFirst(marker.count).trimmingCharacters(in: .whitespaces)
            if !version.isEmpty { return version }
        }
        return nil
    }
}

/// What the user affirmed, and to which versions.
public struct ConsentRecord: Sendable, Equatable {

    /// When the user completed the gate and accepted the documents.
    public let affirmedAtUtcMillis: Int64
    /// The adult minimum in force at that moment.
    public let minimumAgeYears: Int
    /// The accepted version of each document.
    public let acceptedVersions: [LegalDocument: String]

    /// Assembles a record.
    /// - Parameters:
    ///   - affirmedAtUtcMillis: when consent was given
    ///   - minimumAgeYears: the adult minimum it was judged against
    ///   - acceptedVersions: the version accepted for each document
    public init(affirmedAtUtcMillis: Int64, minimumAgeYears: Int,
                acceptedVersions: [LegalDocument: String]) {
        self.affirmedAtUtcMillis = affirmedAtUtcMillis
        self.minimumAgeYears = minimumAgeYears
        self.acceptedVersions = acceptedVersions
    }

    /// The documents whose current version differs from what was accepted.
    ///
    /// A document missing from this record counts as outdated: consent that was
    /// never given is not consent that is still current.
    ///
    /// - Parameter current: the versions the shipped documents declare now
    /// - Returns: the documents needing acceptance again, in declaration order
    public func outdated(against current: [LegalDocument: String]) -> [LegalDocument] {
        LegalDocument.allCases.filter { document in
            guard let accepted = acceptedVersions[document],
                  let now = current[document] else { return true }
            return accepted != now
        }
    }
}

/// Encodes and decodes the stored consent record.
public enum ConsentCodec {

    /// Payload format version; an unknown one fails closed.
    static let versionLine = "lumos-consent/1"

    /// Encodes a consent record.
    /// - Parameter record: the record
    /// - Returns: the payload bytes
    public static func encode(_ record: ConsentRecord) -> Data {
        var out = versionLine + "\n"
        out += "affirmed_at\t\(record.affirmedAtUtcMillis)\n"
        out += "minimum_age_years\t\(record.minimumAgeYears)\n"
        for document in LegalDocument.allCases {
            if let version = record.acceptedVersions[document] {
                out += "accepted\t\(document.rawValue)\t\(version)\n"
            }
        }
        return Data(out.utf8)
    }

    /// Decodes a consent record.
    ///
    /// An unknown line tag is a refusal rather than a skip: a reader that
    /// skipped what it did not understand would quietly accept a record
    /// carrying more than this format allows.
    ///
    /// - Parameter payload: the payload bytes
    /// - Returns: the record
    /// - Throws: ``StoreError/unreadable`` on an unknown version or a bad line
    public static func decode(_ payload: Data) throws -> ConsentRecord {
        // String(bytes:encoding:) rather than String(decoding:as:): SwiftLint's
        // optional_data_string_conversion prefers the failable initialiser, and
        // the failure direction is right here anyway - bytes that are not UTF-8
        // become an empty string, which fails the version check below rather
        // than being silently repaired into something parseable.
        let lines = (String(bytes: payload, encoding: .utf8) ?? "")
            .split(separator: "\n", omittingEmptySubsequences: false)
        guard lines.first == versionLine[...] else { throw StoreError.unreadable }

        var affirmedAt: Int64 = 0
        var minimumAge = 0
        var accepted: [LegalDocument: String] = [:]
        for line in lines.dropFirst() where !line.isEmpty {
            let parts = line.split(separator: "\t", omittingEmptySubsequences: false)
            switch String(parts[0]) {
            case "affirmed_at":
                guard parts.count == 2, let value = Int64(parts[1]) else {
                    throw StoreError.unreadable
                }
                affirmedAt = value
            case "minimum_age_years":
                guard parts.count == 2, let value = Int(parts[1]) else {
                    throw StoreError.unreadable
                }
                minimumAge = value
            case "accepted":
                guard parts.count == 3,
                      let document = LegalDocument(rawValue: String(parts[1])) else {
                    throw StoreError.unreadable
                }
                accepted[document] = String(parts[2])
            default:
                throw StoreError.unreadable
            }
        }
        return ConsentRecord(affirmedAtUtcMillis: affirmedAt, minimumAgeYears: minimumAge,
                             acceptedVersions: accepted)
    }
}

/// Reads and writes the stored consent record (threat model LT-31).
public struct ConsentRepository {

    /// The single-row identifier for the consent record.
    public static let consentId = "self"

    private let store: LocalStore

    /// Binds a repository to a store.
    /// - Parameter store: the opened local store
    public init(store: LocalStore) {
        self.store = store
    }

    /// Records consent, replacing any previous record.
    /// - Parameter record: what was affirmed and accepted
    /// - Throws: ``StoreError`` on low storage or engine failure
    public func save(_ record: ConsentRecord) throws {
        try store.put(kind: .consentRecord, id: Self.consentId,
                      plaintext: ConsentCodec.encode(record))
    }

    /// Loads the consent record.
    /// - Returns: the record, or nil when consent has never been given
    /// - Throws: ``StoreError`` if the record exists but cannot be opened
    public func load() throws -> ConsentRecord? {
        guard let payload = try store.get(kind: .consentRecord, id: Self.consentId) else {
            return nil
        }
        return try ConsentCodec.decode(payload)
    }

    /// Whether the user must be shown the documents again (SRS-ONB-002).
    ///
    /// True when no consent exists, and true when any shipped version differs
    /// from the accepted one. Absence and staleness are the same answer to the
    /// only question the caller is asking.
    ///
    /// - Parameter current: the versions the shipped documents declare now
    /// - Returns: true when consent must be taken
    /// - Throws: ``StoreError`` on engine failure
    public func needsConsent(current: LegalDocuments) throws -> Bool {
        guard let stored = try load() else { return true }
        return !stored.outdated(against: current.versions).isEmpty
    }
}

/// Whether the user has turned Visible Nearby on, persisted across restarts.
public struct DiscoveryVisibility {

    /// The single-row identifier for the visibility state.
    public static let visibilityId = "self"

    /// Payload format version; an unknown one fails closed, which means off.
    static let versionLine = "lumos-visibility/1"

    private let store: LocalStore

    /// Binds the state to a store.
    /// - Parameter store: the opened local store
    public init(store: LocalStore) {
        self.store = store
    }

    /// Whether the user is currently discoverable by nearby devices.
    ///
    /// False unless a stored record explicitly says on. A missing record, an
    /// unparseable one and a record from a format this build does not know all
    /// read as off - the failure direction that cannot expose anybody.
    ///
    /// - Returns: true only when the user turned it on and has not turned it off
    /// - Throws: ``StoreError`` on engine failure
    public func isVisibleNearby() throws -> Bool {
        guard let payload = try store.get(kind: .discoveryVisibility, id: Self.visibilityId) else {
            return false
        }
        let lines = (String(bytes: payload, encoding: .utf8) ?? "")
            .split(separator: "\n", omittingEmptySubsequences: false)
        guard lines.count >= 2, lines[0] == versionLine[...] else { return false }
        return lines[1] == "on"
    }

    /// Turns Visible Nearby on. The only method in the application that can.
    ///
    /// The name is long because the name is the contract: SRS-ONB-008 permits
    /// this state to change only in response to a person deciding it should, so
    /// a call site that reads oddly is a call site that is wrong.
    ///
    /// - Parameter atUtcMillis: when the user asked
    /// - Throws: ``StoreError`` on low storage or engine failure
    public func enableAfterExplicitUserRequest(atUtcMillis: Int64) throws {
        try write(state: "on", atUtcMillis: atUtcMillis)
    }

    /// Turns Visible Nearby off.
    ///
    /// Unlike enabling, this needs no ceremony: anything that can make a user
    /// less exposed should be as easy to call as possible.
    ///
    /// - Parameter atUtcMillis: when it was turned off
    /// - Throws: ``StoreError`` on low storage or engine failure
    public func disable(atUtcMillis: Int64) throws {
        try write(state: "off", atUtcMillis: atUtcMillis)
    }

    /// Writes the state record.
    private func write(state: String, atUtcMillis: Int64) throws {
        let payload = "\(Self.versionLine)\n\(state)\n\(atUtcMillis)\n"
        try store.put(kind: .discoveryVisibility, id: Self.visibilityId,
                      plaintext: Data(payload.utf8))
    }
}
