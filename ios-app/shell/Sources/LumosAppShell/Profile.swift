/// Lumos - ios-app/shell / The inclusive profile, its policy and the derived preview.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-PRO-001, SRS-PRO-002, SRS-PRO-003, SRS-PRO-007, SRS-PRO-008, SRS-PRO-009, SRS-PRO-010, SRS-PRO-011
/// ADRs: ADR-002
/// Threading: any
/// Notes: The same model as the Android side, field for field and rule for
/// rule, because one concept should have one shape on both platforms
/// (TRS-CODE-007). Three things here carry the requirements rather than merely
/// storing them:
///
/// 1. `FieldValue` has THREE states. SRS-PRO-002 wants inclusive values,
///    self-description and prefer-not-to-say, and a String cannot hold all
///    three: "" is ambiguous between "not answered" and "declined", and an enum
///    of options cannot carry a self-description at all. A decline is a
///    deliberate answer and must never render as blank.
///
/// 2. The policy DEFAULTS to the most private audience. A field with no
///    explicit rule is visible to matches only - so a field added by a later
///    work package is private until someone decides otherwise, rather than
///    broadcast until someone remembers to restrict it. Once broadcast is not
///    recoverable (SRS Sec. 2.4: the stricter reading wins).
///
/// 3. The preview is a PURE FUNCTION and there is no preview type to store.
///    The WP-02 kickoff names this trap by hand: "Profile preview must be
///    derived, never a second hand-maintained copy." Two copies diverge exactly
///    when it matters - after the user restricts a field.

import Foundation

/// Every field of the local profile (SRS-PRO-001).
public enum ProfileField: String, CaseIterable, Sendable {
    /// Display name.
    case alias
    /// Age or age band.
    case ageBand
    /// Pronouns.
    case pronouns
    /// Gender identity. GDPR Article 9.
    case genderIdentity
    /// Sexual orientation. GDPR Article 9.
    case orientation
    /// What the user is looking for.
    case relationshipIntent
    /// Free-text interests.
    case interests
    /// Free-text bio. Special category by assumption: the user decides.
    case bio
    /// Profile media. Special category by assumption, for the same reason.
    case media

    /// Whether this field is Article 9 special-category data (data map Sec. 2.2).
    public var isSpecialCategory: Bool {
        switch self {
        case .genderIdentity, .orientation, .bio, .media: return true
        default: return false
        }
    }

    /// Whether a profile without this field is policy-incomplete (SRS-PRO-010).
    ///
    /// Deliberately a short list: requiring orientation or gender identity
    /// would force disclosure of Article 9 data to use the product at all,
    /// which is what prefer-not-to-say exists to prevent.
    public var isRequiredForCompleteness: Bool {
        self == .alias || self == .ageBand
    }
}

/// A profile field's value: unset, declined, or stated.
public enum FieldValue: Equatable, Sendable {
    /// Never answered.
    case unset
    /// Answered with an explicit decline (SRS-PRO-002).
    case preferNotToSay
    /// Answered with free text.
    case stated(String)

    /// A stated value, collapsing blank text to `unset`.
    ///
    /// A field the user cleared is unanswered, not an empty statement.
    /// - Parameter text: the user's words
    /// - Returns: the value
    public static func statement(_ text: String) -> FieldValue {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? .unset : .stated(trimmed)
    }

    /// Whether the user has answered at all; a decline COUNTS as answered.
    public var isAnswered: Bool {
        self != .unset
    }

    /// The stated text, empty for the other two states.
    public var text: String {
        if case .stated(let value) = self { return value }
        return ""
    }
}

/// One sanitised profile image, by reference. The bytes live in the object store.
public struct ProfileMedia: Equatable, Sendable {
    /// The encrypted object store's identifier.
    public let objectId: String
    /// The type the SANITISER determined, not the one a file claimed.
    public let contentType: String
    /// Decoded width.
    public let widthPixels: Int
    /// Decoded height.
    public let heightPixels: Int
    /// Size of the sanitised bytes.
    public let byteCount: Int

    /// Records a sanitised object.
    /// - Parameters:
    ///   - objectId: the object store identifier
    ///   - contentType: the sanitiser-determined MIME type
    ///   - widthPixels: decoded width
    ///   - heightPixels: decoded height
    ///   - byteCount: sanitised size
    public init(objectId: String, contentType: String, widthPixels: Int,
                heightPixels: Int, byteCount: Int) {
        self.objectId = objectId
        self.contentType = contentType
        self.widthPixels = widthPixels
        self.heightPixels = heightPixels
        self.byteCount = byteCount
    }
}

/// The user's own profile.
public struct LocalProfile: Equatable, Sendable {

    /// Profile visibility lifecycle (SRS-PRO-011).
    ///
    /// A state rather than a pair of booleans, which would admit a fourth,
    /// meaningless combination. Permanent deletion is not a state here: it is
    /// the absence of the record, because a tombstone would retain the data the
    /// user asked to erase.
    public enum Lifecycle: String, Sendable {
        /// Eligible to be shown, subject to completeness.
        case active
        /// Paused by the user: never shown, retained intact.
        case paused
        /// Temporarily hidden: never shown, retained intact.
        case hidden
    }

    private var values: [ProfileField: FieldValue]
    /// The media list in display order; the first entry is primary (SRS-PRO-009).
    public private(set) var media: [ProfileMedia]
    /// The lifecycle state.
    public private(set) var lifecycle: Lifecycle

    /// An empty profile: every field unset, no media, active.
    public static var empty: LocalProfile {
        LocalProfile(values: [:], media: [], lifecycle: .active)
    }

    private init(values: [ProfileField: FieldValue], media: [ProfileMedia], lifecycle: Lifecycle) {
        self.values = values
        self.media = media
        self.lifecycle = lifecycle
    }

    /// One field's value.
    /// - Parameter field: the field
    /// - Returns: its value, `.unset` when never assigned
    public func value(_ field: ProfileField) -> FieldValue {
        values[field] ?? .unset
    }

    /// This profile with one field replaced.
    /// - Parameters:
    ///   - field: the field
    ///   - value: its new value
    /// - Returns: a new profile
    public func with(_ field: ProfileField, _ value: FieldValue) -> LocalProfile {
        var next = self
        next.values[field] = value
        return next
    }

    /// This profile with its media list replaced, in display order.
    /// - Parameter replacement: the new list
    /// - Returns: a new profile
    public func withMedia(_ replacement: [ProfileMedia]) -> LocalProfile {
        var next = self
        next.media = replacement
        return next
    }

    /// This profile in a different lifecycle state.
    /// - Parameter state: the state
    /// - Returns: a new profile
    public func withLifecycle(_ state: Lifecycle) -> LocalProfile {
        var next = self
        next.lifecycle = state
        return next
    }

    /// The primary photo, or nil when there is no media.
    public var primaryMedia: ProfileMedia? {
        media.first
    }

    /// The required fields this profile has not answered (SRS-PRO-010).
    ///
    /// An explicit prefer-not-to-say counts as answered, so declining a
    /// sensitive field never makes a profile incomplete.
    public var missingRequiredFields: [ProfileField] {
        ProfileField.allCases.filter { $0.isRequiredForCompleteness && !value($0).isAnswered }
    }

    /// Whether this profile may be shown to anyone at all.
    ///
    /// The conjunction of lifecycle and completeness: a paused profile is never
    /// shown however complete, and an incomplete one never shown however active.
    public var isShowable: Bool {
        lifecycle == .active && missingRequiredFields.isEmpty
    }
}

/// Which fields each audience may see (SRS-PRO-003).
public struct ProfileFieldPolicy: Equatable, Sendable {

    /// Who is looking, ordered from least to most trusted.
    public enum Audience: Int, CaseIterable, Sendable, Comparable {
        /// Detected nearby (WP-03).
        case nearby
        /// Sent an interest request (WP-04).
        case requester
        /// A participant in an activated venue room (WP-08).
        case venue
        /// A mutual match (WP-04).
        case match

        public static func < (lhs: Audience, rhs: Audience) -> Bool {
            lhs.rawValue < rhs.rawValue
        }
    }

    private var minimumAudience: [ProfileField: Audience]

    /// The safe default: every field visible to matches only.
    public static var privateByDefault: ProfileFieldPolicy {
        ProfileFieldPolicy(minimumAudience: [:])
    }

    private init(minimumAudience: [ProfileField: Audience]) {
        self.minimumAudience = minimumAudience
    }

    /// This policy with one field's threshold changed.
    /// - Parameters:
    ///   - field: the field
    ///   - leastTrusted: the least-trusted audience that may see it
    /// - Returns: a new policy
    public func with(_ field: ProfileField, _ leastTrusted: Audience) -> ProfileFieldPolicy {
        var next = self
        next.minimumAudience[field] = leastTrusted
        return next
    }

    /// The least-trusted audience allowed to see a field, defaulting to `.match`.
    /// - Parameter field: the field
    /// - Returns: the threshold
    public func threshold(_ field: ProfileField) -> Audience {
        minimumAudience[field] ?? .match
    }

    /// Whether an audience may see a field.
    /// - Parameters:
    ///   - field: the field
    ///   - audience: who is looking
    /// - Returns: true when the audience is at or above the threshold
    public func isVisible(_ field: ProfileField, to audience: Audience) -> Bool {
        audience >= threshold(field)
    }

    /// Every field an audience may see, in declaration order.
    ///
    /// The single decision point: preview derivation, the SRS-PRO-008 privacy
    /// preview and every future sharing path call this rather than
    /// re-implementing the comparison.
    /// - Parameter audience: who is looking
    /// - Returns: the visible fields
    public func visibleFields(to audience: Audience) -> [ProfileField] {
        ProfileField.allCases.filter { isVisible($0, to: audience) }
    }
}

/// What one audience may see of a profile. Always derived, never stored.
public struct ProfilePreview: Equatable, Sendable {
    /// The audience this was derived for.
    public let audience: ProfileFieldPolicy.Audience
    /// The fields exposed, with their values.
    public let visibleValues: [ProfileField: FieldValue]
    /// The primary media object identifier, or nil.
    ///
    /// An identifier, never bytes: SRS-PRO-007 and SRS-NFR-PRI-002 forbid a
    /// photo in an advertisement, and retrieving the object needs an
    /// authenticated session (ADR-002).
    public let primaryMediaObjectId: String?

    /// Whether this preview carries anything at all.
    public var isEmpty: Bool {
        visibleValues.isEmpty && primaryMediaObjectId == nil
    }

    /// Derives what an audience may see. The one and only construction path.
    ///
    /// Two rules decide the result: a field appears only if the policy admits
    /// this audience, and an UNSET field never appears - a preview should not
    /// advertise the shape of what the user has not filled in. An explicit
    /// prefer-not-to-say DOES appear, because it is an answer.
    /// - Parameters:
    ///   - profile: the profile
    ///   - policy: the visibility policy
    ///   - audience: who is looking
    /// - Returns: the derived preview
    public static func derive(profile: LocalProfile, policy: ProfileFieldPolicy,
                              audience: ProfileFieldPolicy.Audience) -> ProfilePreview {
        var visible: [ProfileField: FieldValue] = [:]
        for field in policy.visibleFields(to: audience) where field != .media {
            let value = profile.value(field)
            if value.isAnswered {
                visible[field] = value
            }
        }
        let mediaId = policy.isVisible(.media, to: audience) ? profile.primaryMedia?.objectId : nil
        return ProfilePreview(audience: audience, visibleValues: visible,
                              primaryMediaObjectId: mediaId)
    }
}
