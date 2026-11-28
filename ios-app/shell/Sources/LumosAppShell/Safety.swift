/// Lumos - ios-app/shell / Deliberate confirmation, emergency wipe and its coverage.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-SAF-009, SRS-SAF-010, SRS-IDN-003, SRS-NFR-PRI-012
/// ADRs: ADR-003, ADR-010
/// Threading: serial-executor
/// Notes: The Android counterparts are DeliberateConfirmation, EmergencyWipe
/// and WipeCoverage, and the design decision worth restating is the same one:
/// the protection against accidental activation is a TYPE, not a dialog.
///
/// A `Confirmation` has a private initialiser, so no caller can make one. The
/// only way to obtain it is to issue a `Challenge` - which is what the
/// consequences screen does, and therefore the moment the warning is shown -
/// and then type the exact phrase inside the window. `EmergencyWipe.execute`
/// takes a Confirmation, so "wipe without showing the warning" is not a mistake
/// that can be made; it does not compile.
///
/// The comparison is deliberately not constant-time. There is no secret: the
/// phrase is printed on the screen the user is looking at, and treating it as a
/// password would be theatre that implies it is one.

import Foundation

/// The actions that require deliberate confirmation.
public enum ConfirmableAction: String, CaseIterable, Sendable {
    /// Destroy everything on the device (SRS-SAF-009).
    case emergencyWipe
    /// Discard this identity and create a new one (SRS-IDN-003).
    case identityReset
}

/// Why a confirmation attempt was refused.
public enum NotConfirmed: Error, Equatable, Sendable {
    /// The typed phrase did not match the required one.
    case phraseDoesNotMatch
    /// The window between issuing and confirming has passed.
    case windowHasPassed
    /// This challenge was already satisfied; one challenge, one action.
    case alreadyUsed
    /// A challenge needs an action and a non-empty phrase.
    case malformedChallenge
}

/// Turns a shown warning plus a typed phrase into a token irreversible actions demand.
public enum DeliberateConfirmation {

    /// How long a challenge stays satisfiable, in milliseconds.
    ///
    /// An interaction timeout, not a protocol or policy value, so it is a named
    /// local constant with its rationale rather than a register entry
    /// (TRS-CODE-005). Two minutes is the reading time of the consequences
    /// screen with room to spare, and it matches the Android value.
    public static let windowMillis: Int64 = 120_000

    /// Proof that the warning was shown and the phrase was typed. Unforgeable.
    public struct Confirmation: Sendable, Equatable {
        /// Which action this confirms; checked by the executor.
        public let action: ConfirmableAction
        /// When the user typed the phrase.
        public let confirmedAtUtcMillis: Int64

        fileprivate init(action: ConfirmableAction, confirmedAtUtcMillis: Int64) {
            self.action = action
            self.confirmedAtUtcMillis = confirmedAtUtcMillis
        }
    }

    /// An outstanding challenge: the warning is on screen and the phrase awaited.
    public final class Challenge {

        /// The phrase the user must type, exactly.
        public let requiredPhrase: String
        /// Which action this challenge guards.
        public let action: ConfirmableAction

        private let issuedAtUtcMillis: Int64
        private var spent = false

        fileprivate init(action: ConfirmableAction, requiredPhrase: String,
                         issuedAtUtcMillis: Int64) {
            self.action = action
            self.requiredPhrase = requiredPhrase
            self.issuedAtUtcMillis = issuedAtUtcMillis
        }

        /// Satisfies the challenge, producing the token the executor demands.
        ///
        /// Case and surrounding whitespace are forgiven; the words are not. A
        /// user typing carefully into a phone keyboard should not be defeated
        /// by an autocapitalised first letter, and nothing accidental produces
        /// the right words in the right order in any case.
        ///
        /// - Parameters:
        ///   - typed: what the user typed
        ///   - nowUtcMillis: the current time
        /// - Returns: the confirmation token
        /// - Throws: ``NotConfirmed`` on a wrong phrase, a passed window, or reuse
        public func confirm(typed: String, nowUtcMillis: Int64) throws -> Confirmation {
            guard !spent else { throw NotConfirmed.alreadyUsed }
            guard nowUtcMillis >= issuedAtUtcMillis,
                  nowUtcMillis - issuedAtUtcMillis <= DeliberateConfirmation.windowMillis else {
                throw NotConfirmed.windowHasPassed
            }
            guard normalise(typed) == normalise(requiredPhrase) else {
                throw NotConfirmed.phraseDoesNotMatch
            }
            spent = true
            return Confirmation(action: action, confirmedAtUtcMillis: nowUtcMillis)
        }

        private func normalise(_ text: String) -> String {
            text.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        }
    }

    /// Issues a challenge, which is what showing the consequences screen does.
    /// - Parameters:
    ///   - action: the irreversible action being proposed
    ///   - requiredPhrase: the phrase from the user-facing copy, in their language
    ///   - nowUtcMillis: the current time
    /// - Returns: the outstanding challenge
    /// - Throws: ``NotConfirmed/malformedChallenge`` on a blank phrase
    public static func issue(action: ConfirmableAction, requiredPhrase: String,
                             nowUtcMillis: Int64) throws -> Challenge {
        guard !requiredPhrase.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw NotConfirmed.malformedChallenge
        }
        return Challenge(action: action, requiredPhrase: requiredPhrase,
                         issuedAtUtcMillis: nowUtcMillis)
    }
}

/// One thing SRS-SAF-010 names, and this build's honest status for it.
public struct WipeCoverageItem: Sendable, Equatable {
    /// The data SRS-SAF-010 names.
    public let what: String
    /// What this build actually does about it.
    public let status: String
}

/// The documented limit of what emergency wipe covers on this build.
///
/// SRS-SAF-010 ends with "to the documented platform limit", and threat model
/// Sec. 7.3 turns that into an obligation: what cannot be reached is named in
/// the UI rather than implied to be gone. Two kinds of gap, kept separate
/// because they are different in kind - things with no store yet, and things no
/// application can reach on either platform.
public struct WipeCoverage: Sendable {

    /// What this build genuinely destroys.
    public let covered: [WipeCoverageItem]
    /// What SRS-SAF-010 names that has no store in this build yet.
    public let notYetBuilt: [WipeCoverageItem]
    /// What no application can reach, and why that is survivable here.
    public let beyondReach: [WipeCoverageItem]

    /// This statement with the profile photos among what is destroyed.
    public var withProfilePhotos: WipeCoverage {
        WipeCoverage(covered: covered + [WipeCoverageItem(
                        what: "profile photos",
                        status: "the key is destroyed first, then every encrypted photo file is deleted")],
                     notYetBuilt: notYetBuilt, beyondReach: beyondReach)
    }

    /// The coverage of the stores that exist after WP-02 slice S4c.
    public static let forWpTwo = WipeCoverage(
        covered: [
            WipeCoverageItem(
                what: "app cryptographic state",
                status: "the key-encryption key is destroyed first, so everything it "
                    + "protected becomes unreadable at that moment"),
            WipeCoverageItem(
                what: "the local profile, its visibility policy and its media metadata",
                status: "every row is deleted after the keys are gone"),
            WipeCoverageItem(
                what: "the block list",
                status: "every row is deleted after the keys are gone"),
            WipeCoverageItem(
                what: "onboarding consent evidence and the Visible Nearby setting",
                status: "every row is deleted after the keys are gone"),
            WipeCoverageItem(
                what: "local diagnostic events",
                status: "every row is deleted after the keys are gone")
        ],
        notYetBuilt: [
            WipeCoverageItem(what: "conversations", status: "no message store exists yet - WP-05"),
            WipeCoverageItem(what: "cached objects", status: "no object cache exists yet - WP-05"),
            WipeCoverageItem(what: "queued packets", status: "no outbox exists yet - WP-03"),
            WipeCoverageItem(what: "venue tokens", status: "no venue store exists yet - WP-07")
        ],
        beyondReach: [
            WipeCoverageItem(
                what: "deleted rows in database free pages",
                status: "they are ciphertext and the key is already destroyed; no "
                    + "application can overwrite them on either platform"),
            WipeCoverageItem(
                what: "flash blocks the storage controller has not yet erased",
                status: "the same: unreadable, not absent, and outside any app's control")
        ])
}

/// Wires every store that exists into a keys-first wipe, behind a confirmation.
public struct EmergencyWipe {

    /// The stage name for destroying the key-encryption key.
    public static let stageKek = "key-encryption key"
    /// The stage name for destroying the local store's derived keys.
    public static let stageStoreKeys = "local store derived keys"
    /// The stage name for clearing every record table.
    public static let stageLocalRecords = "local records"
    /// The stage name for deleting every encrypted media file (profile photos).
    public static let stageMediaObjects = "media objects"

    /// Report of one executed wipe, with the coverage it actually had.
    public struct Outcome: Sendable {
        /// What each stage did, in execution order.
        public let report: KeysFirstWipe.WipeReport
        /// What this build could and could not reach.
        public let coverage: WipeCoverage

        /// Whether every key-material stage completed.
        ///
        /// The property that matters. When true, everything the keys protected
        /// is unreadable whatever the content phase managed.
        public var cryptoErasureHolds: Bool { report.allKeyMaterialDestroyed }
    }

    private let kek: KekStore
    private let store: LocalStore
    private let media: MediaObjectStore?

    /// Assembles the wipe from the stores it must clear.
    ///
    /// Photos are unreadable once the KEK is gone (their key derives from the
    /// root it wraps); deleting their files afterwards is the content pass.
    /// - Parameters:
    ///   - kek: the key-encryption key, destroyed first
    ///   - store: the encrypted record store
    ///   - media: the encrypted media store, or nil when none is open
    public init(kek: KekStore, store: LocalStore, media: MediaObjectStore? = nil) {
        self.kek = kek
        self.store = store
        self.media = media
    }

    /// The consequences a user must be shown before confirming (SRS-SAF-009).
    public var coverage: WipeCoverage {
        media == nil ? WipeCoverage.forWpTwo : WipeCoverage.forWpTwo.withProfilePhotos
    }

    /// Runs the wipe (SRS-SAF-010).
    ///
    /// Registration order does not matter - the executor runs every
    /// key-material stage before any content stage regardless - but it is
    /// written in execution order anyway, because a reader should not have to
    /// know that to follow it.
    ///
    /// - Parameter confirmation: proof the warning was shown and phrase typed
    /// - Returns: the report and the coverage
    /// - Throws: ``NotConfirmed/malformedChallenge`` if the confirmation is for
    ///   another action
    public func execute(confirmation: DeliberateConfirmation.Confirmation) throws -> Outcome {
        guard confirmation.action == .emergencyWipe else {
            throw NotConfirmed.malformedChallenge
        }
        var wipe = KeysFirstWipe()
            .registerKeyMaterial(Self.stageKek) { kek.destroy() }
            .registerKeyMaterial(Self.stageStoreKeys) { store.destroyKeys() }
            .registerContent(Self.stageLocalRecords) { try store.deleteEverything() }
        if let media {
            wipe = wipe.registerContent(Self.stageMediaObjects) { _ = try media.wipeAll() }
        }
        let report = wipe.execute()
        return Outcome(report: report, coverage: coverage)
    }
}
