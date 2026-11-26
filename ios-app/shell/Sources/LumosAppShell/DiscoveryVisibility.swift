/// Lumos - LumosAppShell / Visible Nearby: off by default, on only for a chosen while.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-ONB-008, SRS-NBY-001, SRS-NBY-002, SRS-NBY-003, SRS-DAT-002
/// ADRs: ADR-002
/// Threading: serial-executor
/// Notes: The Swift port of the Android DiscoveryVisibility (payload format
/// lumos-visibility/2), replacing the on/off-only version 1. SRS-ONB-008 is a
/// P0 and threat model LT-33 is High: Visible Nearby is never enabled by
/// installation, update, reboot or permission grant. Absence of the record
/// reads as off, and exactly one method can turn it on. A record from an
/// earlier format also reads as off: an update is one of the four triggers,
/// so visibility never carries across a format change without the person
/// choosing again.
///
/// Enabling means choosing a while (SRS-NBY-001): the register's default
/// session, a custom duration inside the register bounds, or until venue
/// exit. The state expires at the chosen time by evaluation, not by timer:
/// it reads off the millisecond the window ends, and nothing ever rewrites
/// the record to extend it (SRS-NBY-002, no silent renew). The indicator a
/// screen draws (SRS-NBY-003) comes from the same single evaluation, so the
/// person and the radio cannot disagree about whether they are visible.

import Foundation
import LumosProtocolGenerated

/// Whether the person is visible to nearby devices, and until when.
public struct DiscoveryVisibility {

    /// The single-row identifier for the visibility state.
    public static let visibilityId = "self"

    /// Payload format version; any other reads as off.
    static let versionLine = "lumos-visibility/2"

    /// How long the person chose to be visible.
    public enum Mode: String, Sendable, Equatable {
        /// The register's default session (one hour).
        case defaultSession = "default"
        /// A custom duration inside the register bounds.
        case custom
        /// Until the person leaves the venue.
        case untilVenueExit = "venue"
    }

    /// A choice the person made when turning Visible Nearby on.
    public struct Selection: Sendable, Equatable {
        /// The mode chosen.
        public let mode: Mode
        /// The window in seconds; 0 for until venue exit.
        public let durationSeconds: Int

        /// The register's default session.
        public static let defaultSession = Selection(mode: .defaultSession,
                                                     durationSeconds: LumosConstants.visibleNearbyDurationSecDefault)

        /// Until the person leaves the venue.
        public static let untilVenueExit = Selection(mode: .untilVenueExit, durationSeconds: 0)

        /// The shortest custom while, from the register (15 minutes).
        public static let minimumSeconds = LumosConstants.visibleNearbyDurationSecMin
        /// The longest custom while, from the register (8 hours).
        public static let maximumSeconds = LumosConstants.visibleNearbyDurationSecMax

        /// A custom duration.
        /// - Parameter seconds: inside the register's bounds
        /// - Returns: the selection, or nil outside the bounds
        public static func custom(seconds: Int) -> Selection? {
            guard seconds >= minimumSeconds, seconds <= maximumSeconds else { return nil }
            return Selection(mode: .custom, durationSeconds: seconds)
        }
    }

    /// What a screen shows: visible or not, how, and until when.
    public struct Indicator: Sendable, Equatable {
        /// True while visible.
        public let visible: Bool
        /// The mode, when visible.
        public let mode: Mode?
        /// When the window ends, UTC; 0 when off or until venue exit.
        public let expiresAtMillis: Int64

        /// Off.
        public static let off = Indicator(visible: false, mode: nil, expiresAtMillis: 0)
    }

    private let store: LocalStore

    /// Binds the state to a store.
    /// - Parameter store: the opened local store
    public init(store: LocalStore) {
        self.store = store
    }

    /// Whether the person is visible now.
    /// - Parameter nowMillis: now, UTC
    /// - Returns: true only inside a window the person chose
    /// - Throws: ``StoreError`` on engine failure
    public func isVisibleNearby(nowMillis: Int64) throws -> Bool {
        try indicator(nowMillis: nowMillis).visible
    }

    /// The one evaluation the radio and the screen both use.
    /// - Parameter nowMillis: now, UTC
    /// - Returns: the indicator; off for a missing, unreadable, older-format or ended record
    /// - Throws: ``StoreError`` on engine failure
    public func indicator(nowMillis: Int64) throws -> Indicator {
        guard let payload = try store.get(kind: .discoveryVisibility, id: Self.visibilityId) else {
            return .off
        }
        let lines = (String(bytes: payload, encoding: .utf8) ?? "")
            .split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        guard lines.count >= 4, lines[0] == Self.versionLine,
              let mode = Mode(rawValue: lines[1]), lines[2] == "on" else { return .off }
        if mode == .untilVenueExit {
            return Indicator(visible: true, mode: mode, expiresAtMillis: 0)
        }
        // SRS-NBY-002: the window ended, so visibility ended - by evaluation,
        // with nothing to renew and nothing having run.
        guard let expiresAt = Int64(lines[3]), nowMillis < expiresAt else { return .off }
        return Indicator(visible: true, mode: mode, expiresAtMillis: expiresAt)
    }

    /// Turns Visible Nearby on for the chosen while. The only method in the
    /// application that can; the long name is the contract (SRS-ONB-008).
    /// - Parameters:
    ///   - selection: what the person chose
    ///   - nowMillis: when they chose it, UTC
    /// - Throws: ``StoreError`` on low storage or engine failure
    public func enableAfterExplicitUserRequest(_ selection: Selection, nowMillis: Int64) throws {
        let expiresAt = selection.mode == .untilVenueExit ? 0 : nowMillis + 1000 * Int64(selection.durationSeconds)
        try write(mode: selection.mode, state: "on", expiresAt: expiresAt, atUtcMillis: nowMillis)
    }

    /// Turns Visible Nearby off.
    /// - Parameter nowMillis: when, UTC
    /// - Throws: ``StoreError`` on engine failure
    public func disable(nowMillis: Int64) throws {
        try write(mode: .defaultSession, state: "off", expiresAt: 0, atUtcMillis: nowMillis)
    }

    /// Ends an until-venue-exit window when the person leaves; any other
    /// window is left to run out on its own.
    /// - Parameter nowMillis: when, UTC
    /// - Throws: ``StoreError`` on engine failure
    public func endOnVenueExit(nowMillis: Int64) throws {
        let current = try indicator(nowMillis: nowMillis)
        if current.visible && current.mode == .untilVenueExit {
            try disable(nowMillis: nowMillis)
        }
    }

    private func write(mode: Mode, state: String, expiresAt: Int64, atUtcMillis: Int64) throws {
        let payload = "\(Self.versionLine)\n\(mode.rawValue)\n\(state)\n\(expiresAt)\n\(atUtcMillis)\n"
        try store.put(kind: .discoveryVisibility, id: Self.visibilityId, plaintext: Data(payload.utf8))
    }
}
