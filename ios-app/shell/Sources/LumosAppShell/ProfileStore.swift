/// Lumos - ios-app/shell / The profile and its policy, sealed in the store, byte-compatible with Android.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-PRO-001, SRS-PRO-002, SRS-PRO-003, SRS-PRO-004, SRS-PRO-008, TRS-LOC-002
/// ADRs: ADR-003
/// Threading: serial-executor (the store's)
/// Notes: The Swift twins of Android's ProfileCodec and ProfileRepository,
/// payload for payload: the same "lumos-profile/1" and "lumos-policy/1"
/// line formats with the same upper-snake names and the same percent
/// escaping, so a recovery export reads the same on either platform. The
/// three FieldValue states round-trip exactly (unset and prefer-not-to-say
/// stay different, SRS-PRO-002). An absent policy reads as the private
/// default, never as "no restrictions"; a policy that omits a field keeps it
/// private. An unknown payload format fails closed.

import Foundation

/// Encodes profiles and policies as sealed-record payloads (Android's ProfileCodec).
public enum ProfileCodec {

    private static let versionLine = "lumos-profile/1"
    private static let policyVersionLine = "lumos-policy/1"

    /// A payload in a format this build does not know.
    public struct UnrecognisedFormat: Error, Equatable, Sendable {}

    /// The Android enum name for a Swift case: camelCase to UPPER_SNAKE.
    static func wireName(_ swiftName: String) -> String {
        var out = ""
        for character in swiftName {
            if character.isUppercase {
                out.append("_")
            }
            out.append(contentsOf: character.uppercased())
        }
        return out
    }

    private static func kindName(_ value: FieldValue) -> String {
        switch value {
        case .unset: return "UNSET"
        case .preferNotToSay: return "PREFER_NOT_TO_SAY"
        case .stated: return "STATED"
        }
    }

    private static func escape(_ raw: String) -> String {
        raw.replacingOccurrences(of: "%", with: "%25")
            .replacingOccurrences(of: "\t", with: "%09")
            .replacingOccurrences(of: "\n", with: "%0A")
    }

    private static func unescape(_ escaped: String) -> String {
        escaped.replacingOccurrences(of: "%0A", with: "\n")
            .replacingOccurrences(of: "%09", with: "\t")
            .replacingOccurrences(of: "%25", with: "%")
    }

    /// - Parameter profile: the profile
    /// - Returns: the payload bytes, exactly as Android writes them
    public static func encode(_ profile: LocalProfile) -> Data {
        var out = versionLine + "\n"
        out += "lifecycle\t" + profile.lifecycle.rawValue.uppercased() + "\n"
        for field in ProfileField.allCases {
            let value = profile.value(field)
            out += "field\t\(wireName(field.rawValue))\t\(kindName(value))\t\(escape(value.text))\n"
        }
        for media in profile.media {
            out += "media\t\(escape(media.objectId))\t\(escape(media.contentType))\t"
                + "\(media.widthPixels)\t\(media.heightPixels)\t\(media.byteCount)\n"
        }
        return Data(out.utf8)
    }

    /// - Parameter payload: the payload bytes
    /// - Returns: the profile
    /// - Throws: ``UnrecognisedFormat`` for an unknown format or a name this build does not know
    public static func decodeProfile(_ payload: Data) throws -> LocalProfile {
        let lines = (String(bytes: payload, encoding: .utf8) ?? "").components(separatedBy: "\n")
        guard lines.first == versionLine else { throw UnrecognisedFormat() }
        let fields = Dictionary(uniqueKeysWithValues: ProfileField.allCases.map { (wireName($0.rawValue), $0) })
        var profile = LocalProfile.empty
        var media: [ProfileMedia] = []
        for line in lines.dropFirst() {
            let parts = line.components(separatedBy: "\t")
            guard parts.count >= 2 else { continue }
            switch parts[0] {
            case "lifecycle":
                guard let state = LocalProfile.Lifecycle(rawValue: parts[1].lowercased()) else {
                    throw UnrecognisedFormat()
                }
                profile = profile.withLifecycle(state)
            case "field":
                guard parts.count >= 4, let field = fields[parts[1]] else { throw UnrecognisedFormat() }
                switch parts[2] {
                case "STATED": profile = profile.with(field, .statement(unescape(parts[3])))
                case "PREFER_NOT_TO_SAY": profile = profile.with(field, .preferNotToSay)
                default: profile = profile.with(field, .unset)
                }
            case "media":
                guard parts.count >= 6, let width = Int(parts[3]), let height = Int(parts[4]),
                      let bytes = Int(parts[5]) else { throw UnrecognisedFormat() }
                media.append(ProfileMedia(objectId: unescape(parts[1]), contentType: unescape(parts[2]),
                                          widthPixels: width, heightPixels: height, byteCount: bytes))
            default:
                // A record type from a newer build: skipped, as on Android.
                continue
            }
        }
        return profile.withMedia(media)
    }

    /// - Parameter policy: the policy
    /// - Returns: the payload bytes, exactly as Android writes them
    public static func encode(_ policy: ProfileFieldPolicy) -> Data {
        var out = policyVersionLine + "\n"
        for field in ProfileField.allCases {
            out += "\(wireName(field.rawValue))\t\(audienceName(policy.threshold(field)))\n"
        }
        return Data(out.utf8)
    }

    /// - Parameter payload: the payload bytes
    /// - Returns: the policy; a field the payload omits keeps the private default
    /// - Throws: ``UnrecognisedFormat``
    public static func decodePolicy(_ payload: Data) throws -> ProfileFieldPolicy {
        let lines = (String(bytes: payload, encoding: .utf8) ?? "").components(separatedBy: "\n")
        guard lines.first == policyVersionLine else { throw UnrecognisedFormat() }
        let fields = Dictionary(uniqueKeysWithValues: ProfileField.allCases.map { (wireName($0.rawValue), $0) })
        let audiences = Dictionary(uniqueKeysWithValues: ProfileFieldPolicy.Audience.allCases
            .map { (audienceName($0), $0) })
        var policy = ProfileFieldPolicy.privateByDefault
        for line in lines.dropFirst() {
            let parts = line.components(separatedBy: "\t")
            guard parts.count >= 2, !parts[0].isEmpty else { continue }
            guard let field = fields[parts[0]], let audience = audiences[parts[1]] else {
                throw UnrecognisedFormat()
            }
            policy = policy.with(field, audience)
        }
        return policy
    }

    private static func audienceName(_ audience: ProfileFieldPolicy.Audience) -> String {
        switch audience {
        case .nearby: return "NEARBY"
        case .requester: return "REQUESTER"
        case .venue: return "VENUE"
        case .match: return "MATCH"
        }
    }
}

/// Reads and writes the profile and its policy (Android's ProfileRepository).
public final class ProfileRepository {

    /// The single-row identifier for the profile and for its policy.
    public static let rowID = "self"

    private let store: LocalStore

    /// - Parameter store: the opened local store
    public init(store: LocalStore) {
        self.store = store
    }

    /// - Returns: the profile, or nil when none has been created
    public func load() throws -> LocalProfile? {
        try store.get(kind: .localProfile, id: Self.rowID).map(ProfileCodec.decodeProfile)
    }

    /// - Parameter profile: the profile, replacing any previous version
    public func save(_ profile: LocalProfile) throws {
        try store.put(kind: .localProfile, id: Self.rowID, plaintext: ProfileCodec.encode(profile))
    }

    /// - Returns: the stored policy, or the private default when none is stored
    public func loadPolicy() throws -> ProfileFieldPolicy {
        try store.get(kind: .profileFieldPolicy, id: Self.rowID).map(ProfileCodec.decodePolicy)
            ?? .privateByDefault
    }

    /// - Parameter policy: the policy
    public func savePolicy(_ policy: ProfileFieldPolicy) throws {
        try store.put(kind: .profileFieldPolicy, id: Self.rowID, plaintext: ProfileCodec.encode(policy))
    }

    /// Whether the stored profile may be shown at all (SRS-PRO-010); false when unreadable.
    public var isShowable: Bool {
        ((try? load()) ?? nil)?.isShowable ?? false
    }
}
