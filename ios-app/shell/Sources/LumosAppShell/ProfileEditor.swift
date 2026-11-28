/// Lumos - ios-app/shell / What the iPhone Profile tab does, without SwiftUI: edit, choose who sees, preview.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-PRO-001, SRS-PRO-002, SRS-PRO-003, SRS-PRO-008, SRS-PRO-010, SRS-PRO-012
/// ADRs: ADR-003
/// Threading: serial-executor (the store's; the main actor in the app)
/// Notes: The Swift twin of Android's ProfilePanel logic and copy, kept out of
/// the view so it runs on Linux: the eight edited fields in SRS-PRO-001
/// order (photos join with the media slice), the same labels, hints, age
/// bands and audience names as Android word for word, "Prefer not to say"
/// on every optional field (SRS-PRO-002), every field private to matches
/// until the person says otherwise (SRS-PRO-003), and the preview each
/// audience sees from the same ProfilePreview.derive the sharing path uses
/// (SRS-PRO-008). A save that fails leaves the editor on what is stored.

import Foundation

/// The person's profile, being edited.
public final class ProfileEditor {

    /// The fields the tab edits, in SRS-PRO-001 order (media joins later).
    public static let edited: [ProfileField] = [.alias, .ageBand, .pronouns, .genderIdentity, .orientation,
                                                .relationshipIntent, .interests, .bio]
    /// The audiences a person can choose today, least to most private.
    public static let choosable: [ProfileFieldPolicy.Audience] = [.nearby, .requester, .match]
    /// The age bands, as Android offers them.
    public static let ageBands = ["18–24", "25–34", "35–44", "45–54", "55–64", "65 or over"]
    /// Longest answer for a one-line field.
    public static let shortMaxCharacters = 60
    /// Longest answer for interests and the bio.
    public static let longMaxCharacters = 500

    private let repository: ProfileRepository?
    /// The profile as stored.
    public private(set) var profile = LocalProfile.empty
    /// Who may see each field, as stored.
    public private(set) var policy = ProfileFieldPolicy.privateByDefault
    /// True when the store could not be read (the tab says so instead of a blank form).
    public private(set) var unreadable = false
    /// True when the last change was not stored.
    public private(set) var notSaved = false

    /// - Parameter repository: the profile store, or nil when the store did not open
    public init(repository: ProfileRepository?) {
        self.repository = repository
        reload()
    }

    /// Re-reads the stored profile and policy.
    public func reload() {
        guard let repository else {
            unreadable = true
            return
        }
        do {
            profile = try repository.load() ?? .empty
            policy = try repository.loadPolicy()
            unreadable = false
        } catch {
            unreadable = true
        }
    }

    /// Stores one field's new value (Save or Prefer not to say).
    public func set(_ field: ProfileField, _ value: FieldValue) {
        store(profile.with(field, value), policy)
    }

    /// Stores who may see one field.
    public func setAudience(_ field: ProfileField, _ audience: ProfileFieldPolicy.Audience) {
        store(profile, policy.with(field, audience))
    }

    private func store(_ next: LocalProfile, _ nextPolicy: ProfileFieldPolicy) {
        do {
            guard let repository else { throw StoreUnavailable() }
            try repository.savePolicy(nextPolicy)
            try repository.save(next)
            notSaved = false
        } catch {
            notSaved = true
        }
        reload()
    }

    private struct StoreUnavailable: Error {}

    /// Whether this profile may be shown at all (SRS-PRO-010).
    public var isShowable: Bool { profile.isShowable }

    /// One "Label: value" line per answered field this audience may see; a
    /// decline is never shown as a value.
    public func previewLines(for audience: ProfileFieldPolicy.Audience) -> [String] {
        let visible = ProfilePreview.derive(profile: profile, policy: policy, audience: audience).visibleValues
        return Self.edited.compactMap { field in
            guard case .stated(let text) = visible[field] ?? .unset else { return nil }
            return "\(Self.label(field)): \(text)"
        }
    }

    /// What the person sees as a field's value.
    public static func shown(_ value: FieldValue) -> String {
        switch value {
        case .stated(let text): return text
        case .preferNotToSay: return "Prefer not to say"
        case .unset: return "Not added"
        }
    }

    /// A field's name on screen (Android's strings).
    public static func label(_ field: ProfileField) -> String {
        switch field {
        case .alias: return "Name"
        case .ageBand: return "Age"
        case .pronouns: return "Pronouns"
        case .genderIdentity: return "Gender"
        case .orientation: return "Sexuality"
        case .relationshipIntent: return "Looking for"
        case .interests: return "Interests"
        case .bio: return "About you"
        case .media: return "Photos"
        }
    }

    /// A field's example text (Android's strings).
    public static func hint(_ field: ProfileField) -> String {
        switch field {
        case .alias: return "The name people see, like a first name or nickname"
        case .pronouns: return "For example: she/her, he/him, they/them"
        case .genderIdentity: return "In your own words, for example: woman, man, non-binary, trans, genderqueer"
        case .orientation: return "In your own words, for example: gay, lesbian, bi, pan, queer, asexual, straight"
        case .relationshipIntent: return "For example: friends, dating, something long-term"
        case .interests: return "A few things you enjoy"
        default: return "A few lines about you"
        }
    }

    /// An audience's name on screen (Android's strings).
    public static func audienceName(_ audience: ProfileFieldPolicy.Audience) -> String {
        switch audience {
        case .nearby: return "Anyone near you on Lumos"
        case .requester: return "People you show interest in"
        case .venue: return "People in a venue room"
        case .match: return "Only your matches"
        }
    }
}
