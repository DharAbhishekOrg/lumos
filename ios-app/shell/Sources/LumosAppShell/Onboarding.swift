/// Lumos - ios-app/shell / The age gate, the onboarding machine and the surface gate.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-ONB-001, SRS-ONB-002, SRS-ONB-006, SRS-NFR-LEG-007
/// ADRs: ADR-004
/// Threading: serial-executor
/// Notes: The Android side of this is AppSurface, OnboardingState,
/// OnboardingEvent, OnboardingMachine and AgeGate; it is one file here because
/// Swift has no reason to split five small types across five files and
/// TRS-CODE-007 is about names agreeing, not file counts.
///
/// The two properties worth restating rather than looking up:
///
/// - The surface gate defaults CLOSED. `isReachable` asks the surface whether
///   it belongs to onboarding; it does not consult a list of blocked screens,
///   because a list is a thing somebody forgets to add to (threat model LT-30).
/// - The age gate returns a decision and never the date of birth. The privacy
///   data map Sec. 2.3 records an affirmation, and a date that is never carried
///   is a date that cannot leak (SRS-ONB-001).
///
/// Unexpected events are ignored rather than thrown, exactly as on Android: a
/// double tap or an event redelivered after a view reload must neither crash
/// onboarding nor advance it.

import Foundation
import LumosProtocolGenerated

/// The surfaces of the application, and whether each is part of onboarding.
public enum AppSurface: String, CaseIterable, Sendable {
    /// The adult-only age gate itself (SRS-ONB-001).
    case ageGate
    /// Terms, Privacy Notice, Community Rules and the radio disclosure (SRS-ONB-002).
    case legalConsent
    /// Creating the offline, no-account local identity (SRS-ONB-006).
    case identityCreation
    /// Creating or editing the profile (SRS-PRO-001).
    case profileEditor
    /// Nearby discovery and the cards it renders (WP-03).
    case nearbyDiscovery
    /// Interest, match and conversation surfaces (WP-04, WP-05).
    case conversations
    /// Venue rooms and venue content (WP-07).
    case venueRooms
    /// Settings, including privacy and visibility controls.
    case settings
    /// Blocking, reporting and the safety tools (WP-04).
    case safetyCentre
    /// Recovery setup and restore (SRS-ONB-007).
    case recovery
    /// Emergency wipe (SRS-SAF-009).
    case emergencyWipe

    /// Whether this surface is one of the onboarding steps themselves.
    ///
    /// Only these are reachable before onboarding completes, and only in the
    /// order the machine allows. Everything else is closed.
    public var isPartOfOnboarding: Bool {
        switch self {
        case .ageGate, .legalConsent, .identityCreation: return true
        default: return false
        }
    }
}

/// Where a user is in onboarding.
public enum OnboardingState: String, CaseIterable, Sendable {
    /// Nothing has happened yet: the age gate is the only surface.
    case ageGate
    /// Age affirmed; the legal documents must be shown and accepted.
    case legalConsent
    /// Documents accepted; the local identity is being created.
    case identityCreation
    /// Onboarding is done and the application's surfaces are open.
    case complete
    /// The declared age was below the adult minimum. Terminal until restarted.
    case blockedUnderAge

    /// Whether onboarding has finished.
    public var isComplete: Bool { self == .complete }
}

/// An input to `OnboardingMachine`.
public enum OnboardingEvent: Sendable {
    /// The user answered the age gate and `AgeGate` judged the answer.
    ///
    /// It carries the DECISION, not the date of birth: the affirmation is the
    /// evidence that is kept, and an event holding a date would put that date
    /// one field access away from the record that gets persisted.
    case ageDeclared(AgeGate.Decision)
    /// The user accepted the legal documents at the versions shown to them.
    case legalDocumentsAccepted([LegalDocument: String])
    /// The local cryptographic identity now exists (SRS-ONB-006).
    case identityCreated
    /// Onboarding starts again from the age gate.
    case restartRequested
}

/// Judges a declared date of birth against the adult minimum, and forgets it.
public enum AgeGate {

    /// The gate's verdict: an answer and the rule it was judged by, never the date.
    public struct Decision: Sendable, Equatable {
        /// Whether the declared date of birth met the adult minimum.
        public let isAdult: Bool
        /// The minimum this decision was judged against, recorded with it.
        ///
        /// Stored alongside the affirmation so a consent record made under a
        /// future stricter minimum is distinguishable from one made under this
        /// one - which is what SRS-NFR-LEG-007's per-market tightening needs in
        /// order to know whose consent must be taken again.
        public let minimumAgeYears: Int
    }

    /// The adult minimum from the constants register (ADR-004).
    ///
    /// SRS-NFR-LEG-007 fixes it at 18 globally, and CR-001's sibling reasoning
    /// applies: the entry is not overridable, so a signed configuration cannot
    /// lower it.
    public static var minimumAgeYears: Int { LumosConstants.adultAgeMinimumYears }

    /// Judges a declared date of birth.
    ///
    /// The caller passes today's date rather than this reading a clock, so the
    /// boundary cases are testable: the day before an eighteenth birthday and
    /// the day of it have exact answers that do not depend on when the suite
    /// runs.
    ///
    /// A date in the future is not an adult. That is the fail-closed reading of
    /// a clock skew or a typo.
    ///
    /// - Parameters:
    ///   - dateOfBirth: the date the user declared; not retained
    ///   - today: the current date in the user's own calendar
    /// - Returns: the verdict
    public static func evaluate(dateOfBirth: Date, today: Date) -> Decision {
        guard dateOfBirth <= today else {
            return Decision(isAdult: false, minimumAgeYears: minimumAgeYears)
        }
        var calendar = Calendar(identifier: .gregorian)
        // A fixed calendar and time zone: the answer must not depend on where
        // the device happens to be when the question is asked.
        calendar.timeZone = TimeZone(secondsFromGMT: 0) ?? .current
        let years = calendar.dateComponents([.year], from: dateOfBirth, to: today).year ?? 0
        return Decision(isAdult: years >= minimumAgeYears, minimumAgeYears: minimumAgeYears)
    }
}

/// Tracks where onboarding is, and what that makes reachable.
public final class OnboardingMachine {

    /// The current state.
    public private(set) var state: OnboardingState

    /// Starts a machine, fresh or resumed from storage.
    /// - Parameter state: where onboarding is; defaults to the age gate
    public init(state: OnboardingState = .ageGate) {
        self.state = state
    }

    /// Applies an event, returning the state it produced.
    ///
    /// An event that does not belong to the current state leaves the state
    /// unchanged: silently ignoring is safer than either throwing or advancing.
    ///
    /// - Parameter event: the typed event
    /// - Returns: the state after the event
    @discardableResult
    public func on(_ event: OnboardingEvent) -> OnboardingState {
        switch (event, state) {
        case (.restartRequested, _):
            state = .ageGate
        case (.ageDeclared(let decision), .ageGate):
            state = decision.isAdult ? .legalConsent : .blockedUnderAge
        case (.legalDocumentsAccepted, .legalConsent):
            state = .identityCreation
        case (.identityCreated, .identityCreation):
            state = .complete
        default:
            break
        }
        return state
    }

    /// The onboarding surface this state expects, or nil when there is none.
    public var currentStep: AppSurface? {
        switch state {
        case .ageGate: return .ageGate
        case .legalConsent: return .legalConsent
        case .identityCreation: return .identityCreation
        case .complete, .blockedUnderAge: return nil
        }
    }

    /// Whether a surface may be shown right now (SRS-ONB-001, threat model LT-30).
    ///
    /// After onboarding completes, everything is reachable. Before it does,
    /// only the onboarding step the machine is actually on - so a user cannot
    /// skip ahead to the consent screen, and no application surface is
    /// reachable at all.
    ///
    /// - Parameter surface: the surface being asked for
    /// - Returns: true only when this surface may be shown in this state
    public func isReachable(_ surface: AppSurface) -> Bool {
        if state == .complete { return true }
        guard surface.isPartOfOnboarding else { return false }
        return surface == currentStep
    }
}
