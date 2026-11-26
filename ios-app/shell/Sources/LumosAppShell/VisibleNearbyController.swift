/// Lumos - LumosAppShell / Drives the advertiser from Visible Nearby's one evaluation.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-ONB-004, SRS-ONB-008, SRS-NBY-001, SRS-NBY-002, SRS-NBY-003, TRS-IOS-003
/// ADRs: ADR-002
/// Threading: serial-executor
/// Notes: The rules the Nearby tab's radio follows, kept here so `swift test`
/// runs them with a scripted advertiser:
/// - the advertiser runs exactly while the person is visible AND the app is
///   on screen; it stops the moment the window ends, they turn it off, or the
///   app leaves the screen (background advertising waits for a slice that can
///   also end a window on time while suspended, SRS-NBY-002);
/// - the advertiser is first started by the person's own tap, so the
///   Bluetooth permission prompt appears then and never at launch
///   (SRS-ONB-004);
/// - if Bluetooth is not allowed, not supported or refuses, visibility turns
///   OFF and the reason is kept for the screen; if Bluetooth is merely off,
///   the chosen window keeps running and the screen says nobody can see them
///   until it is on;
/// - a window that ran out is reported once, so the screen can say Visible
///   Nearby turned itself off.

import Foundation

/// Visible Nearby's radio and indicator, together.
public final class VisibleNearbyController {

    /// Why the radio is not doing what the switch says.
    public enum RadioProblem: Sendable, Equatable {
        /// Bluetooth is switched off; the window keeps running.
        case bluetoothOff
        /// Lumos is not allowed to use Bluetooth; visibility was turned off.
        case notAllowed
        /// This device cannot advertise; visibility was turned off.
        case unsupported
        /// Bluetooth refused; visibility was turned off.
        case refused
    }

    /// The indicator from the last evaluation.
    public private(set) var indicator = DiscoveryVisibility.Indicator.off
    /// The radio's problem, if any.
    public private(set) var problem: RadioProblem?
    /// True after a window ran out on its own, until the person acts again.
    public private(set) var endedOnItsOwn = false
    /// True while the advertiser has been asked to run.
    public private(set) var advertising = false

    private let visibility: DiscoveryVisibility
    private let advertiser: BleAdvertising
    private let clock: () -> Int64
    private let onFailure: @Sendable (BleAdvertiseFailure) -> Void
    private var foreground = true

    /// - Parameters:
    ///   - visibility: the persisted state
    ///   - advertiser: the radio
    ///   - clock: UTC milliseconds
    ///   - failureHop: carries an advertiser failure, which arrives on the
    ///     radio's own queue, back to this controller's executor (the app
    ///     hops to the main actor) and calls ``radioFailed(_:)`` there
    public init(visibility: DiscoveryVisibility, advertiser: BleAdvertising, clock: @escaping () -> Int64,
                failureHop: @escaping @Sendable (BleAdvertiseFailure) -> Void) {
        self.visibility = visibility
        self.advertiser = advertiser
        self.clock = clock
        self.onFailure = failureHop
    }

    /// Re-evaluates, and starts or stops the radio to match.
    public func refresh() {
        let was = indicator.visible
        indicator = (try? visibility.indicator(nowMillis: clock())) ?? .off
        if was && !indicator.visible && problem == nil {
            endedOnItsOwn = true
        }
        syncRadio()
    }

    /// The person chose to be visible for this while.
    /// - Returns: false when the state could not be saved (nothing changed)
    @discardableResult
    public func turnOn(_ selection: DiscoveryVisibility.Selection) -> Bool {
        problem = nil
        endedOnItsOwn = false
        let saved = (try? visibility.enableAfterExplicitUserRequest(selection, nowMillis: clock())) != nil
        refresh()
        return saved
    }

    /// Off, at once.
    public func turnOff() {
        try? visibility.disable(nowMillis: clock())
        refresh()
        // Turned off by the person (or by a radio failure), not by the clock:
        // set after the evaluation, which would otherwise read it as expiry.
        endedOnItsOwn = false
    }

    /// The app came on screen or left it.
    public func setForeground(_ active: Bool) {
        foreground = active
        refresh()
    }

    /// An advertiser failure, already back on this controller's executor.
    public func radioFailed(_ failure: BleAdvertiseFailure) {
        switch failure {
        case .poweredOff, .resetting:
            problem = .bluetoothOff
        case .unauthorized:
            problem = .notAllowed
            turnOff()
        case .unsupported:
            problem = .unsupported
            turnOff()
        case .refused, .undocumented:
            problem = .refused
            turnOff()
        }
    }

    private func syncRadio() {
        let want = indicator.visible && foreground
        if want && !advertising {
            advertising = true
            advertiser.start(onFailure: onFailure)
        } else if !want && advertising {
            advertising = false
            advertiser.stop()
        }
    }
}
