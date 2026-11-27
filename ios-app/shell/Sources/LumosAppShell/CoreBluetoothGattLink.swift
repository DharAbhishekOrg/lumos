/// Lumos - ios-app/shell / The CoreBluetooth central and peripheral that carry discovery links.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-TRN-002, TRS-TRN-004, TRS-PROTO-017, TRS-IOS-003, SRS-NBY-005
/// ADRs: ADR-002, ADR-013
/// Threading: serial-executor (CoreBluetooth calls back on the link queue the engine runs on)
/// Notes: ADR-013's layout on iOS, as NearbyDiscovery's LinkRadio - the
/// Swift sibling of Android's AndroidGattLink, with CR-010's identifiers.
///
/// - Central (this phone connects): the sighting's link token is the
///   peripheral's identifier, retrieved from this link's own central manager;
///   connect, discover the Lumos service and its two characteristics, turn on
///   LINK_OUT notifications, then report the link ready. Frames go out as
///   LINK_IN writes without response, paced on canSendWriteWithoutResponse
///   (TRS-TRN-004), and come in as LINK_OUT notifications.
/// - Peripheral (a phone connects here): this link's own peripheral manager
///   hosts the service (advertising stays with the Visible Nearby advertiser);
///   a central subscribing to LINK_OUT opens a link in the engine. Frames come
///   in as LINK_IN writes and go out as notifications; when CoreBluetooth's
///   transmit queue is full the fragment is kept and resent on the
///   ready-to-update callback, so nothing is lost or reordered. A peripheral
///   cannot disconnect a central: closing such a link stops answering and
///   forgets it, and the central's own handshake timer ends its side.
///
/// Every frame is fragmented by FrameChannel at the link's MTU. Both managers
/// are created on, and call back on, the one link queue, which is also where
/// NearbyDiscovery runs the engine - so this class needs no locks; that queue
/// confinement is the @unchecked Sendable contract. Nothing logs, stores or
/// shows an identifier or a payload byte. Radio truth is the owner's two-phone
/// test and G-01; this file claims compilation and the layout.

import CoreBluetooth
import Foundation
import LumosProtocol
import LumosProtocolGenerated

/// LinkRadio over CoreBluetooth's central and peripheral managers.
public final class CoreBluetoothGattLink: NSObject, LinkRadio, CBCentralManagerDelegate, CBPeripheralDelegate,
    CBPeripheralManagerDelegate, @unchecked Sendable {

    /// The ATT header bytes on top of a write or notification payload.
    static let attHeader = 3

    private static var linkIn: CBUUID { CBUUID(string: LumosConstants.gattLinkInCharUUIDValue) }
    private static var linkOut: CBUUID { CBUUID(string: LumosConstants.gattLinkOutCharUUIDValue) }

    /// One link in either role.
    private final class Link {
        let channel: FrameChannel
        let queue: PacedWriteQueue
        var peripheral: CBPeripheral?
        var remoteIn: CBCharacteristic?
        var central: CBCentral?
        var mtu = Int(LumosConstants.bleTargetAttMTUDefault)
        var retry: Data?

        init(channel: FrameChannel, queue: PacedWriteQueue) {
            self.channel = channel
            self.queue = queue
        }
    }

    private let queue: DispatchQueue
    private let clock: () -> Int64
    private var engine: DiscoveryEngine?
    private var centralManager: CBCentralManager?
    private var peripheralManager: CBPeripheralManager?
    private var serverOut: CBMutableCharacteristic?
    private var wantServer = false
    private var serverReady = true
    private var links: [String: Link] = [:]
    private var handleByPeripheral: [UUID: String] = [:]
    private var handleByCentral: [UUID: String] = [:]
    private var nextPeripheralHandle = 0

    /// - Parameters:
    ///   - queue: the serial link queue; both managers call back on it
    ///   - clock: the millisecond clock
    public init(queue: DispatchQueue, clock: @escaping () -> Int64) {
        self.queue = queue
        self.clock = clock
        super.init()
    }

    // MARK: LinkRadio, on the link queue

    public func bind(_ engine: DiscoveryEngine) {
        self.engine = engine
    }

    /// Opens both managers on first use - never earlier, because creating a
    /// CoreBluetooth manager is what makes iOS ask for Bluetooth, and that
    /// question belongs to the moment the person turns Visible Nearby on
    /// (SRS-ONB-004), not to launch.
    public func openServer() -> Bool {
        wantServer = true
        if centralManager == nil {
            centralManager = CBCentralManager(delegate: self, queue: queue)
        }
        if peripheralManager == nil {
            peripheralManager = CBPeripheralManager(delegate: self, queue: queue)
        } else {
            addServiceIfPoweredOn()
        }
        return true
    }

    public func close() {
        for handle in Array(links.keys) {
            disconnect(handle: handle)
        }
        wantServer = false
        peripheralManager?.removeAllServices()
        serverOut = nil
    }

    public func connect(handle: String, linkToken: String) {
        guard let central = centralManager, central.state == .poweredOn,
              let uuid = UUID(uuidString: linkToken),
              let peripheral = central.retrievePeripherals(withIdentifiers: [uuid]).first else {
            queue.async { self.engine?.onLinkClosed(handle: handle) }
            return
        }
        let link = makeLink(handle) { [unowned self] fragment in self.writeToPeripheral(handle, fragment) }
        link.peripheral = peripheral
        links[handle] = link
        handleByPeripheral[peripheral.identifier] = handle
        peripheral.delegate = self
        central.connect(peripheral, options: nil)
    }

    public func send(handle: String, frame: Data) {
        guard let link = links[handle] else { return }
        do {
            try link.channel.send(frame, negotiatedMtu: link.mtu)
        } catch {
            disconnect(handle: handle)
            engine?.onLinkClosed(handle: handle)
        }
    }

    public func disconnect(handle: String) {
        guard let link = links.removeValue(forKey: handle) else { return }
        link.queue.advanceEpoch()
        if let peripheral = link.peripheral {
            handleByPeripheral[peripheral.identifier] = nil
            centralManager?.cancelPeripheralConnection(peripheral)
        }
        if let central = link.central {
            handleByCentral[central.identifier] = nil
        }
    }

    // MARK: shared

    private func makeLink(_ handle: String, write: @escaping (Data) -> Void) -> Link {
        var gate: (() -> Bool)?
        let paced = PacedWriteQueue(canSend: { gate?() ?? false }, write: write)
        let channel = FrameChannel(
            queue: paced,
            reassembler: FragmentReassembler(timeoutMillis: Int64(LumosConstants.reassemblyTimeoutSecBLE) * 1000),
            onBlob: { [unowned self] blob in
                if self.links[handle] != nil {
                    self.engine?.onFrame(handle: handle, frame: blob, nowMillis: self.clock())
                }
            })
        let link = Link(channel: channel, queue: paced)
        gate = { [unowned self, unowned link] in self.canSend(link) }
        return link
    }

    private func canSend(_ link: Link) -> Bool {
        if let peripheral = link.peripheral {
            return link.remoteIn != nil && peripheral.canSendWriteWithoutResponse
        }
        return link.central != nil && serverReady && link.retry == nil
    }

    private func dropped(_ handle: String) {
        guard links[handle] != nil else { return }
        disconnect(handle: handle)
        engine?.onLinkClosed(handle: handle)
    }

    // MARK: central role

    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state != .poweredOn {
            for (handle, link) in links where link.peripheral != nil {
                dropped(handle)
            }
        }
    }

    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard handleByPeripheral[peripheral.identifier] != nil else { return }
        peripheral.discoverServices([LumosService.uuid])
    }

    public func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral,
                               error: Error?) {
        if let handle = handleByPeripheral[peripheral.identifier] {
            dropped(handle)
        }
    }

    public func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral,
                               error: Error?) {
        if let handle = handleByPeripheral[peripheral.identifier] {
            dropped(handle)
        }
    }

    public func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let handle = handleByPeripheral[peripheral.identifier] else { return }
        guard error == nil,
              let service = peripheral.services?.first(where: { $0.uuid == LumosService.uuid }) else {
            dropped(handle)
            return
        }
        peripheral.discoverCharacteristics([Self.linkIn, Self.linkOut], for: service)
    }

    public func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService,
                           error: Error?) {
        guard let handle = handleByPeripheral[peripheral.identifier], let link = links[handle] else { return }
        let found = service.characteristics ?? []
        guard error == nil,
              let remoteIn = found.first(where: { $0.uuid == Self.linkIn }),
              let remoteOut = found.first(where: { $0.uuid == Self.linkOut }) else {
            // Not a Lumos link layout: nothing to say to it.
            dropped(handle)
            return
        }
        link.remoteIn = remoteIn
        link.mtu = peripheral.maximumWriteValueLength(for: .withoutResponse) + Self.attHeader
        peripheral.setNotifyValue(true, for: remoteOut)
    }

    public func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic,
                           error: Error?) {
        guard let handle = handleByPeripheral[peripheral.identifier], links[handle] != nil else { return }
        guard error == nil, characteristic.uuid == Self.linkOut, characteristic.isNotifying else {
            dropped(handle)
            return
        }
        engine?.onLinkReady(handle: handle, nowMillis: clock())
    }

    public func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic,
                           error: Error?) {
        guard error == nil, characteristic.uuid == Self.linkOut, let value = characteristic.value,
              let handle = handleByPeripheral[peripheral.identifier], let link = links[handle] else { return }
        link.channel.onFragmentReceived(value, nowMillis: clock())
    }

    public func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
        guard let handle = handleByPeripheral[peripheral.identifier] else { return }
        links[handle]?.queue.resume()
    }

    private func writeToPeripheral(_ handle: String, _ fragment: Data) {
        guard let link = links[handle], let peripheral = link.peripheral, let remoteIn = link.remoteIn else {
            return
        }
        peripheral.writeValue(fragment, for: remoteIn, type: .withoutResponse)
    }

    // MARK: peripheral role

    public func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        if peripheral.state == .poweredOn {
            addServiceIfPoweredOn()
        } else {
            serverOut = nil
            for (handle, link) in links where link.central != nil {
                dropped(handle)
            }
        }
    }

    private func addServiceIfPoweredOn() {
        guard wantServer, serverOut == nil, let manager = peripheralManager, manager.state == .poweredOn else {
            return
        }
        let incoming = CBMutableCharacteristic(type: Self.linkIn, properties: [.write, .writeWithoutResponse],
                                               value: nil, permissions: [.writeable])
        let outgoing = CBMutableCharacteristic(type: Self.linkOut, properties: [.notify], value: nil,
                                               permissions: [.readable])
        let service = CBMutableService(type: LumosService.uuid, primary: true)
        service.characteristics = [incoming, outgoing]
        manager.add(service)
        serverOut = outgoing
    }

    public func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral,
                                  didSubscribeTo characteristic: CBCharacteristic) {
        guard characteristic.uuid == Self.linkOut, handleByCentral[central.identifier] == nil,
              let engine else { return }
        nextPeripheralHandle += 1
        let handle = "p\(nextPeripheralHandle)"
        let link = makeLink(handle) { [unowned self] fragment in self.notifyCentral(handle, fragment) }
        link.central = central
        link.mtu = central.maximumUpdateValueLength + Self.attHeader
        links[handle] = link
        handleByCentral[central.identifier] = handle
        engine.onCentralSubscribed(handle: handle, nowMillis: clock())
    }

    public func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral,
                                  didUnsubscribeFrom characteristic: CBCharacteristic) {
        if let handle = handleByCentral[central.identifier] {
            dropped(handle)
        }
    }

    public func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for request in requests where request.characteristic.uuid == Self.linkIn {
            guard let value = request.value, let handle = handleByCentral[request.central.identifier],
                  let link = links[handle] else { continue }
            link.channel.onFragmentReceived(value, nowMillis: clock())
        }
        if let first = requests.first {
            peripheral.respond(to: first, withResult: .success)
        }
    }

    public func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        serverReady = true
        for link in links.values where link.central != nil {
            if let pending = link.retry, let central = link.central, let out = serverOut {
                guard peripheral.updateValue(pending, for: out, onSubscribedCentrals: [central]) else {
                    serverReady = false
                    return
                }
                link.retry = nil
            }
            link.queue.resume()
        }
    }

    private func notifyCentral(_ handle: String, _ fragment: Data) {
        guard let link = links[handle], let central = link.central, let out = serverOut,
              let manager = peripheralManager else { return }
        if !manager.updateValue(fragment, for: out, onSubscribedCentrals: [central]) {
            // CoreBluetooth's transmit queue is full: keep this fragment and
            // pause until peripheralManagerIsReady(toUpdateSubscribers:).
            link.retry = fragment
            serverReady = false
        }
    }
}
