/// Lumos - ios-app/shell / Thin CoreBluetooth adapters: central, peripheral, restoration.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-IOS-003, TRS-IOS-009, TRS-PROTO-018
/// ADRs: ADR-002
/// Threading: serial-executor
/// Notes: Deliberately thin, like their Android siblings: lift what the
/// platform saw into a BleSighting, map manager states onto the typed failure
/// enums, advertise the service UUID alone (ADR-002), and keep every decision
/// above the seam where `swift test` executes it. State restoration
/// (TRS-IOS-003, TRS-IOS-009) is declared at construction with a stable
/// restoration identifier and surfaced through a callback - restoration
/// options and the willRestoreState delegate exist only on iOS, so both are
/// compiled behind os(iOS) and the macOS CI job builds the adapter without
/// them. CoreBluetooth calls back on the serial queue given at construction;
/// these classes are confined to it, which is the @unchecked Sendable
/// contract - the compiler cannot see queue confinement, the design provides
/// it. Radio truth is the G-01 owner campaign; this file claims compilation
/// and mapping.

import CoreBluetooth
import Foundation
import LumosProtocolGenerated

/// The Lumos service UUID from the register.
public enum LumosService {
    /// The 128-bit service UUID every Lumos peer advertises or scans for.
    public static let uuid = CBUUID(string: LumosConstants.serviceUUID)
}

/// BleScanning over a CBCentralManager.
public final class CoreBluetoothScanner: NSObject, BleScanning, CBCentralManagerDelegate,
    @unchecked Sendable {

    private let queue: DispatchQueue
    private let millisClock: () -> Int64
    private let onRestore: ([String]) -> Void
    private var manager: CBCentralManager?
    private var onSighting: ((BleSighting) -> Void)?
    private var onFailure: ((BleScanFailure) -> Void)?
    private var wantScanning = false

    /// Creates the scanner; the manager starts on first `start`.
    /// - Parameters:
    ///   - queue: the serial queue CoreBluetooth calls back on; every use of
    ///     this object must stay on it
    ///   - millisClock: the injected clock sightings are stamped with
    ///   - onRestore: receives the link tokens of peripherals iOS restored
    ///     after relaunching the app in the background (TRS-IOS-009); empty
    ///     use is correct on platforms without restoration
    public init(queue: DispatchQueue, millisClock: @escaping () -> Int64,
                onRestore: @escaping ([String]) -> Void) {
        self.queue = queue
        self.millisClock = millisClock
        self.onRestore = onRestore
    }

    /// Starts scanning for the Lumos service UUID.
    /// - Parameters:
    ///   - onSighting: where sightings go
    ///   - onFailure: where typed refusals go
    public func start(onSighting: @escaping (BleSighting) -> Void,
                      onFailure: @escaping (BleScanFailure) -> Void) {
        self.onSighting = onSighting
        self.onFailure = onFailure
        self.wantScanning = true
        if manager == nil {
            var options: [String: Any] = [:]
            #if os(iOS)
            // TRS-IOS-003/009: a stable identifier opts into relaunch
            // restoration; the OS hands back our scanning state in
            // willRestoreState below.
            options[CBCentralManagerOptionRestoreIdentifierKey] = "lumos.central"
            #endif
            manager = CBCentralManager(delegate: self, queue: queue, options: options)
            return
        }
        beginScanIfPoweredOn()
    }

    /// Stops scanning; the manager and its restoration identity survive.
    public func stop() {
        wantScanning = false
        manager?.stopScan()
    }

    /// CBCentralManagerDelegate: state changes drive scan start and failures.
    /// - Parameter central: the manager
    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            beginScanIfPoweredOn()
        case .poweredOff:
            onFailure?(.poweredOff)
        case .unauthorized:
            onFailure?(.unauthorized)
        case .unsupported:
            onFailure?(.unsupported)
        case .resetting:
            onFailure?(.resetting)
        case .unknown:
            break // A transient startup state, not a refusal.
        @unknown default:
            onFailure?(.undocumented)
        }
    }

    /// CBCentralManagerDelegate: one discovery becomes one sighting.
    /// - Parameters:
    ///   - central: the manager
    ///   - peripheral: the sighted peripheral
    ///   - advertisementData: the merged advertisement record
    ///   - RSSI: the received signal strength
    public func centralManager(_ central: CBCentralManager,
                               didDiscover peripheral: CBPeripheral,
                               advertisementData: [String: Any],
                               rssi RSSI: NSNumber) {
        let serviceData = (advertisementData[CBAdvertisementDataServiceDataKey]
            as? [CBUUID: Data])?[LumosService.uuid]
        onSighting?(BleSighting(linkToken: peripheral.identifier.uuidString,
                                serviceData: serviceData,
                                rssiDbm: RSSI.intValue,
                                atMillis: millisClock()))
    }

    #if os(iOS)
    /// CBCentralManagerDelegate: relaunch restoration (TRS-IOS-009).
    /// - Parameters:
    ///   - central: the restored manager
    ///   - dict: the state iOS preserved across the relaunch
    public func centralManager(_ central: CBCentralManager,
                               willRestoreState dict: [String: Any]) {
        let peripherals = dict[CBCentralManagerRestoredStatePeripheralsKey]
            as? [CBPeripheral] ?? []
        onRestore(peripherals.map { $0.identifier.uuidString })
    }
    #endif

    private func beginScanIfPoweredOn() {
        guard wantScanning, let manager, manager.state == .poweredOn else { return }
        manager.scanForPeripherals(withServices: [LumosService.uuid], options: nil)
    }
}

/// BleAdvertising over a CBPeripheralManager: the service UUID, nothing else.
public final class CoreBluetoothAdvertiser: NSObject, BleAdvertising,
    CBPeripheralManagerDelegate, @unchecked Sendable {

    private let queue: DispatchQueue
    private var manager: CBPeripheralManager?
    private var onFailure: ((BleAdvertiseFailure) -> Void)?
    private var wantAdvertising = false

    /// Creates the advertiser; the manager starts on first `start`.
    /// - Parameter queue: the serial queue CoreBluetooth calls back on
    public init(queue: DispatchQueue) {
        self.queue = queue
    }

    /// Starts UUID-only advertising (ADR-002: iOS never advertises the hint).
    /// - Parameter onFailure: where typed refusals go
    public func start(onFailure: @escaping (BleAdvertiseFailure) -> Void) {
        self.onFailure = onFailure
        self.wantAdvertising = true
        if manager == nil {
            var options: [String: Any] = [:]
            #if os(iOS)
            options[CBPeripheralManagerOptionRestoreIdentifierKey] = "lumos.peripheral"
            #endif
            manager = CBPeripheralManager(delegate: self, queue: queue, options: options)
            return
        }
        beginAdvertisingIfPoweredOn()
    }

    /// Stops advertising; the manager and its restoration identity survive.
    public func stop() {
        wantAdvertising = false
        manager?.stopAdvertising()
    }

    /// CBPeripheralManagerDelegate: state changes drive start and failures.
    /// - Parameter peripheral: the manager
    public func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        switch peripheral.state {
        case .poweredOn:
            beginAdvertisingIfPoweredOn()
        case .poweredOff:
            onFailure?(.poweredOff)
        case .unauthorized:
            onFailure?(.unauthorized)
        case .unsupported:
            onFailure?(.unsupported)
        case .resetting:
            onFailure?(.resetting)
        case .unknown:
            break
        @unknown default:
            onFailure?(.undocumented)
        }
    }

    /// CBPeripheralManagerDelegate: a start refusal becomes a typed failure.
    /// - Parameters:
    ///   - peripheral: the manager
    ///   - error: the platform's refusal, when there was one
    public func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager,
                                                     error: Error?) {
        if error != nil {
            onFailure?(.refused)
        }
    }

    #if os(iOS)
    /// CBPeripheralManagerDelegate: relaunch restoration (TRS-IOS-009).
    /// - Parameters:
    ///   - peripheral: the restored manager
    ///   - dict: the state iOS preserved across the relaunch
    public func peripheralManager(_ peripheral: CBPeripheralManager,
                                  willRestoreState dict: [String: Any]) {
        // Advertising is re-declared below from our own intent rather than
        // trusted from the restored dictionary: what we advertise is a
        // constant (the UUID), so the honest restoration is to start again.
        beginAdvertisingIfPoweredOn()
    }
    #endif

    private func beginAdvertisingIfPoweredOn() {
        guard wantAdvertising, let manager, manager.state == .poweredOn else { return }
        manager.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [LumosService.uuid]])
    }
}
