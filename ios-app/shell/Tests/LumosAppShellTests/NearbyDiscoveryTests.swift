/// Lumos - ios-app/shell / Live discovery end to end on iOS: two phones, real queues, real stores.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-NBY-003, SRS-NBY-005, SRS-NBY-013, SRS-NBY-017, SRS-SAF-002, TRS-PROTO-017
/// ADRs: ADR-002, ADR-013
/// Threading: test-only
/// Notes: The Android NearbyDiscoveryTest, assertion for assertion:
/// NearbyDiscovery with only the radio and scanner replaced by an in-memory
/// air. Each phone has its own link queue, its own store and its own store
/// queue, and a NearbyIdentity over a real identity record; frames hop
/// between phones' queues as the radio would. The owner's rule is checked
/// both ways: a phone that is not visible is neither found nor finding.

import CryptoKit
import Foundation
import LumosProtocol
import LumosProtocolGenerated
import XCTest
@testable import LumosAppShell

/// The radio and the scanner of every phone in one room.
private final class Room: @unchecked Sendable {
    private let lock = NSLock()
    private var phones: [String: Phone] = [:]

    func add(_ phone: Phone) {
        lock.lock()
        phones[phone.token] = phone
        lock.unlock()
    }

    func phone(_ token: String) -> Phone? {
        lock.lock()
        defer { lock.unlock() }
        return phones[token]
    }

    var all: [Phone] {
        lock.lock()
        defer { lock.unlock() }
        return Array(phones.values)
    }

    /// Every scanning phone sees every serving phone.
    func scanRound() {
        for scanner in all {
            guard let listener = scanner.scanningListener else { continue }
            for advertiser in all where advertiser !== scanner && advertiser.isServing {
                listener(BleSighting(linkToken: advertiser.token, serviceData: nil, rssiDbm: -50,
                                     atMillis: Int64(Date().timeIntervalSince1970 * 1000)))
            }
        }
        Thread.sleep(forTimeInterval: 0.1)
    }
}

private struct Roomy: StorageCapacity {
    var usableSpaceBytes: Int64 { Int64.max }
}

private func nowMillis() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

/// One phone: radio, scanner, queues, store and discovery.
private final class Phone: LinkRadio, BleScanning, @unchecked Sendable {
    let token: String
    let linkQueue: DispatchQueue
    let storage: DispatchQueue
    let store: LocalStore
    let edPublic: Data
    unowned let room: Room
    var discovery: NearbyDiscovery!

    private let lock = NSLock()
    private var serving = false
    private var listener: ((BleSighting) -> Void)?
    private var routes: [String: (String, String)] = [:]
    private var serverHandles = 0
    private(set) var connects = 0
    private var engine: DiscoveryEngine?

    init(_ token: String, room: Room) throws {
        self.token = token
        self.room = room
        linkQueue = DispatchQueue(label: "link-\(token)")
        storage = DispatchQueue(label: "store-\(token)")
        let database = try Sqlite3Database(path: ":memory:")
        let key = try DataKeyHierarchy.create(kek: SoftwareKekStore()).deriveKey(for: .database)
        store = try LocalStore.open(database: database, databaseDomainKey: key, capacity: Roomy(), now: { 0 })
        let signing = Curve25519.Signing.PrivateKey()
        let xPublic = Curve25519.KeyAgreement.PrivateKey().publicKey.rawRepresentation
        let binding = try signing.signature(for: Data(LumosConstants.x3dhIdentityBindingInfoValue.utf8) + xPublic)
        edPublic = signing.publicKey.rawRepresentation
        let record = IdentityKeyRecord(ed25519Public: edPublic, x25519Public: xPublic, bindingSignature: binding,
                                       wrappedPrivateKeys: Data(), protection: .softwareFallback)
        let identity = NearbyIdentity(record: record, signer: { try signing.signature(for: $0) })
        discovery = NearbyDiscovery(link: self, scanner: self, identity: identity, store: store,
                                    linkQueue: linkQueue, storage: storage, clock: nowMillis, changed: {})
        room.add(self)
    }

    var key: String { PresenceTable.keyFor(peerId: Hello.peerId(edPublic)) }

    var isServing: Bool {
        lock.lock()
        defer { lock.unlock() }
        return serving
    }

    var scanningListener: ((BleSighting) -> Void)? {
        lock.lock()
        defer { lock.unlock() }
        return listener
    }

    var routeCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return routes.count
    }

    func block(_ peerKey: String) throws {
        try storage.sync { try BlockedPeers(store: store).block(peerKey: peerKey, atUtcMillis: nowMillis()) }
    }

    // LinkRadio, on this phone's link queue.

    func bind(_ engine: DiscoveryEngine) {
        lock.lock()
        self.engine = engine
        lock.unlock()
    }

    func openServer() -> Bool {
        lock.lock()
        serving = true
        lock.unlock()
        return true
    }

    func close() {
        lock.lock()
        serving = false
        let handles = Array(routes.keys)
        lock.unlock()
        handles.forEach(disconnect)
    }

    func connect(handle: String, linkToken: String) {
        lock.lock()
        connects += 1
        lock.unlock()
        guard let target = room.phone(linkToken), target.isServing, target.boundEngine != nil else {
            linkQueue.async { self.boundEngine?.onLinkClosed(handle: handle) }
            return
        }
        lock.lock()
        serverHandles += 1
        let server = "s\(token)\(serverHandles)"
        routes[handle] = (linkToken, server)
        lock.unlock()
        target.addRoute(server, to: (token, handle))
        target.linkQueue.async { target.boundEngine?.onCentralSubscribed(handle: server, nowMillis: nowMillis()) }
        linkQueue.async { self.boundEngine?.onLinkReady(handle: handle, nowMillis: nowMillis()) }
    }

    func send(handle: String, frame: Data) {
        lock.lock()
        let route = routes[handle]
        lock.unlock()
        guard let route, let other = room.phone(route.0) else { return }
        let theirHandle = route.1
        other.linkQueue.async { other.boundEngine?.onFrame(handle: theirHandle, frame: frame, nowMillis: nowMillis()) }
    }

    func disconnect(handle: String) {
        lock.lock()
        let route = routes.removeValue(forKey: handle)
        lock.unlock()
        guard let route, let other = room.phone(route.0), other.removeRoute(route.1) else { return }
        let theirHandle = route.1
        other.linkQueue.async { other.boundEngine?.onLinkClosed(handle: theirHandle) }
    }

    var boundEngine: DiscoveryEngine? {
        lock.lock()
        defer { lock.unlock() }
        return engine
    }

    func addRoute(_ handle: String, to route: (String, String)) {
        lock.lock()
        routes[handle] = route
        lock.unlock()
    }

    func removeRoute(_ handle: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return routes.removeValue(forKey: handle) != nil
    }

    // BleScanning.

    func start(onSighting: @escaping (BleSighting) -> Void, onFailure: @escaping (BleScanFailure) -> Void) {
        lock.lock()
        listener = onSighting
        lock.unlock()
    }

    func stop() {
        lock.lock()
        listener = nil
        lock.unlock()
    }
}

final class NearbyDiscoveryTests: XCTestCase {

    private let room = Room()

    override func tearDown() {
        room.all.forEach { $0.discovery.stop() }
        super.tearDown()
    }

    private func until(_ what: String, _ done: () -> Bool) {
        let deadline = Date().addingTimeInterval(20)
        while !done() {
            XCTAssertLessThan(Date(), deadline, "timed out waiting: \(what)")
            if Date() >= deadline { return }
            room.scanRound()
        }
    }

    private func showsOne(_ phone: Phone) -> Bool {
        phone.discovery.state.cardList.count == 1
    }

    private func isNotVisible(_ phone: Phone) -> Bool {
        if case .notVisible = phone.discovery.state { return true }
        return false
    }

    func testTwoVisiblePhonesSeeEachOtherAndStopForgetsAtOnce() throws {
        let a = try Phone("a", room: room)
        let b = try Phone("b", room: room)
        XCTAssertTrue(isNotVisible(a), "not started: not visible")
        a.discovery.start()
        b.discovery.start()
        until("both phones show one card") { showsOne(a) && showsOne(b) }
        XCTAssertEqual(a.discovery.state.cardList.first?.peerKey, b.key, "a sees b's real identity")
        XCTAssertEqual(b.discovery.state.cardList.first?.peerKey, a.key, "b sees a's")

        a.discovery.stop()
        XCTAssertTrue(isNotVisible(a), "stop empties the screen at once")
        a.linkQueue.sync {}
        XCTAssertEqual(a.routeCount, 0, "and every link is closed")
        XCTAssertFalse(a.isServing, "and nothing serves")
        XCTAssertNil(a.scanningListener, "or scans")
    }

    func testAPhoneThatIsNotVisibleIsNeitherFoundNorFinding() throws {
        let a = try Phone("a", room: room)
        let hidden = try Phone("h", room: room)
        a.discovery.start()
        for _ in 0..<30 { room.scanRound() }
        XCTAssertTrue(a.discovery.state.cardList.isEmpty, "a hidden phone is not found")
        XCTAssertEqual(hidden.connects, 0, "and it looks for no one")
        XCTAssertTrue(isNotVisible(hidden))
    }

    func testABlockedPersonIsNotShownAndGetsNoCardBack() throws {
        let a = try Phone("a", room: room)
        let b = try Phone("b", room: room)
        try a.block(b.key)
        a.discovery.start()
        b.discovery.start()
        for _ in 0..<40 { room.scanRound() }
        XCTAssertTrue(a.discovery.state.cardList.isEmpty, "a never shows the person they blocked")
        XCTAssertTrue(b.discovery.state.cardList.isEmpty,
                      "and the blocked person never gets a card of a (SRS-SAF-002)")
    }
}
