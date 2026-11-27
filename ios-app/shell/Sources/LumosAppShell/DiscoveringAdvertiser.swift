/// Lumos - ios-app/shell / Visible Nearby's radio: advertising and discovery start and stop together.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-NBY-003, SRS-NBY-005, SRS-NBY-017
/// ADRs: ADR-002, ADR-013
/// Threading: main-actor callers (VisibleNearbyController drives it on the main thread)
/// Notes: The Swift twin of the Android DiscoveringAdvertiser and the owner's
/// rule (2026-11-28): a person looks for and connects to others only while
/// they can be seen themselves and the app is open. VisibleNearbyController
/// already runs the advertiser exactly then - visible AND in the foreground -
/// so discovery rides on the same calls: whatever starts advertising starts
/// discovery, whatever stops it (off, the window ending, leaving the app, a
/// radio failure) stops discovery too.

import Foundation

/// What DiscoveringAdvertiser starts and stops (NearbyDiscovery in the app).
public protocol DiscoveryControl: AnyObject {
    /// Starts looking, connecting and answering.
    func start()
    /// Stops everything at once.
    func stop()
}

extension NearbyDiscovery: DiscoveryControl {}

/// A BleAdvertising that also runs discovery.
public final class DiscoveringAdvertiser: BleAdvertising {

    private let advertiser: BleAdvertising
    private let discovery: DiscoveryControl?

    /// - Parameters:
    ///   - advertiser: the platform advertiser
    ///   - discovery: the app's discovery, or nil when it could not be made
    public init(advertiser: BleAdvertising, discovery: DiscoveryControl?) {
        self.advertiser = advertiser
        self.discovery = discovery
    }

    /// Starts advertising, then discovery.
    /// - Parameter onFailure: where advertising failures go
    public func start(onFailure: @escaping (BleAdvertiseFailure) -> Void) {
        advertiser.start(onFailure: onFailure)
        discovery?.start()
    }

    /// Stops discovery, then advertising.
    public func stop() {
        discovery?.stop()
        advertiser.stop()
    }
}
