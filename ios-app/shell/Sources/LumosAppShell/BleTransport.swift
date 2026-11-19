/// Lumos - ios-app/shell / The BLE seams the Apple discovery pipeline runs on.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-IOS-003, TRS-PROTO-017, TRS-PROTO-018
/// ADRs: ADR-002
/// Threading: serial-executor
/// Notes: The same seam shape the Android side settled in S2b, so the two
/// pipelines stay structurally parallel and both execute without a radio: the
/// synthetic venue drives these protocols in `swift test`, and the thin
/// CoreBluetooth adapters behind them are radio glue whose truth is the G-01
/// owner campaign. An iOS peer ADVERTISES the service UUID alone (ADR-002);
/// as a CENTRAL it sees Android hints as service data, parsed structurally by
/// the triage and never treated as identity (TRS-PROTO-017). linkToken is the
/// transport's opaque connect handle (on CoreBluetooth, the peripheral
/// identifier UUID) - plumbing, never identity, never shown or persisted.

import Foundation

/// One advertisement sighting crossing the scanner seam.
public struct BleSighting: Sendable {
    /// The transport's opaque connect handle; never identity.
    public let linkToken: String
    /// The adv_v1 service data, or nil for a UUID-only peer.
    public let serviceData: Data?
    /// Received signal strength in dBm.
    public let rssiDbm: Int
    /// The sighting time on the caller's clock, milliseconds.
    public let atMillis: Int64

    /// Creates a sighting.
    /// - Parameters:
    ///   - linkToken: the transport's opaque connect handle
    ///   - serviceData: the adv_v1 service data, or nil for a hintless peer
    ///   - rssiDbm: received signal strength
    ///   - atMillis: the sighting time on the caller's clock
    public init(linkToken: String, serviceData: Data?, rssiDbm: Int, atMillis: Int64) {
        self.linkToken = linkToken
        self.serviceData = serviceData
        self.rssiDbm = rssiDbm
        self.atMillis = atMillis
    }
}

/// Typed scan failure states - the CoreBluetooth side of TRS-AND-005's table.
public enum BleScanFailure: Sendable {
    /// Bluetooth is powered off.
    case poweredOff
    /// The user has not authorized Bluetooth for this app.
    case unauthorized
    /// The device cannot do BLE at all.
    case unsupported
    /// The manager is resetting; the scan must be restarted after.
    case resetting
    /// A state outside the documented set.
    case undocumented
}

/// Typed advertise failure states for the peripheral role.
public enum BleAdvertiseFailure: Sendable {
    /// Bluetooth is powered off.
    case poweredOff
    /// The user has not authorized Bluetooth for this app.
    case unauthorized
    /// The device cannot advertise.
    case unsupported
    /// The manager is resetting.
    case resetting
    /// The platform refused the advertising payload or start.
    case refused
    /// A state outside the documented set.
    case undocumented
}

/// Starts and stops central-role scanning.
public protocol BleScanning: AnyObject {
    /// Starts scanning for Lumos peers.
    /// - Parameters:
    ///   - onSighting: where sightings go; may arrive at any rate
    ///   - onFailure: the typed failure that stopped or refused the scan
    func start(onSighting: @escaping (BleSighting) -> Void,
               onFailure: @escaping (BleScanFailure) -> Void)

    /// Stops scanning; a no-op when not scanning.
    func stop()
}

/// Starts and stops peripheral-role UUID-only advertising (ADR-002).
public protocol BleAdvertising: AnyObject {
    /// Starts advertising the Lumos service UUID alone.
    /// - Parameter onFailure: the typed failure that stopped or refused it
    func start(onFailure: @escaping (BleAdvertiseFailure) -> Void)

    /// Stops advertising; a no-op when not advertising.
    func stop()
}
