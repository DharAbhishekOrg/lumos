/// Lumos - ios-app/shell / What the iPhone Profile tab does, without SwiftUI: edit, choose who sees, preview.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-PRO-001, SRS-PRO-002, SRS-PRO-003, SRS-PRO-008, SRS-PRO-009, SRS-PRO-010, SRS-PRO-012
/// ADRs: ADR-003
/// Threading: serial-executor (the store's; the main actor in the app)
/// Notes: The Swift twin of Android's ProfilePanel logic and copy, kept out of
/// the view so it runs on Linux: the photos (through ProfilePhotos, when the
/// media store is open), then the eight edited fields in SRS-PRO-001
/// order, the same labels, hints, age
/// bands and audience names as Android word for word, "Prefer not to say"
/// on every optional field (SRS-PRO-002), every field private to matches
/// until the person says otherwise (SRS-PRO-003), and the preview each
/// audience sees from the same ProfilePreview.derive the sharing path uses
/// (SRS-PRO-008). A save that fails leaves the editor on what is stored.

import Foundation

/// The person's profile, being edited.
public final class ProfileEditor {

    /// The fields the tab edits as words, in SRS-PRO-001 order (photos have their own row).
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

    /// What the last photo change could not do, in the tab's words (Android's strings).
    public enum PhotoNotice: Equatable, Sendable {
        /// Already the most photos a profile can hold.
        case full
        /// The picture failed a safety check.
        case rejected
        /// The media store is not open.
        case unavailable
        /// The store refused the change.
        case notSaved

        /// The sentence the tab shows.
        public var text: String {
            switch self {
            case .full: return "You have the most photos a profile can hold. Remove one to add another."
            case .rejected: return "That picture could not be used. Please choose another photo."
            case .unavailable: return "Photos cannot be opened just now. Nothing was changed."
            case .notSaved: return "That change was not saved. Please try again."
            }
        }
    }

    private let repository: ProfileRepository?
    private let photos: ProfilePhotos?
    /// What the last photo change could not do, or nil.
    public private(set) var photoNotice: PhotoNotice?
    /// The profile as stored.
    public private(set) var profile = LocalProfile.empty
    /// Who may see each field, as stored.
    public private(set) var policy = ProfileFieldPolicy.privateByDefault
    /// True when the store could not be read (the tab says so instead of a blank form).
    public private(set) var unreadable = false
    /// True when the last change was not stored.
    public private(set) var notSaved = false

    /// - Parameters:
    ///   - repository: the profile store, or nil when the store did not open
    ///   - photos: the photo operations, or nil when the media store did not open
    public init(repository: ProfileRepository?, photos: ProfilePhotos? = nil) {
        self.repository = repository
        self.photos = photos
        reload()
    }

    /// Whether the photo row is offered at all (the media store is open).
    public var photosAvailable: Bool { photos != nil }

    /// Adds a picked picture (re-drawn, sanitised and encrypted first).
    public func addPhoto(_ picked: Data) {
        guard profile.media.count < ProfilePhotos.maxPhotos else {
            photoNotice = .full
            return
        }
        photoChange { $0.add(picked, to: profile) }
    }

    /// Makes one photo the main one.
    public func makePrimaryPhoto(_ id: String) {
        photoChange { $0.makePrimary(id, in: profile) }
    }

    /// Moves one photo to a new place in the order.
    public func movePhoto(_ id: String, to index: Int) {
        photoChange { $0.move(id, to: index, in: profile) }
    }

    /// Removes one photo and deletes its bytes from this phone.
    public func removePhoto(_ id: String) {
        photoChange { $0.remove(id, from: profile) }
    }

    /// One photo's bytes, to draw it on this phone.
    public func photoBytes(_ id: String) -> Data? {
        photos?.bytes(id)
    }

    private func photoChange(_ operation: (ProfilePhotos) -> ProfilePhotos.Outcome) {
        guard let photos else {
            photoNotice = .unavailable
            return
        }
        switch operation(photos) {
        case .done, .unknown: photoNotice = nil
        case .full: photoNotice = .full
        case .rejected: photoNotice = .rejected
        case .notSaved: photoNotice = .notSaved
        }
        reload()
    }

    /// A photo's name for screen readers (Android's strings).
    /// - Parameters:
    ///   - place: its index, from 0
    ///   - count: how many photos there are
    /// - Returns: "Photo 1 of 3, your main photo" for the first, "Photo 2 of 3" otherwise
    public static func photoDescription(place: Int, count: Int) -> String {
        place == 0 ? "Photo \(place + 1) of \(count), your main photo" : "Photo \(place + 1) of \(count)"
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
        let preview = ProfilePreview.derive(profile: profile, policy: policy, audience: audience)
        let lines: [String] = Self.edited.compactMap { field in
            guard case .stated(let text) = preview.visibleValues[field] ?? .unset else { return nil }
            return "\(Self.label(field)): \(text)"
        }
        return preview.primaryMediaObjectId == nil ? lines : lines + ["Your main photo"]
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
