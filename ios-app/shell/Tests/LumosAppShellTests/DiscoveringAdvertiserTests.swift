/// Lumos - ios-app/shell / Discovery runs exactly while Visible Nearby's radio runs.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-NBY-003, SRS-NBY-005, SRS-NBY-017
/// ADRs: ADR-002, ADR-013
/// Threading: test-only
/// Notes: The owner's rule through the real VisibleNearbyController and
/// DiscoveryVisibility: off means no discovery; on and in the foreground
/// means discovery; leaving the app, turning off or a radio refusal each
/// stop it at once.

import Foundation
import XCTest
@testable import LumosAppShell

private final class Radio: BleAdvertising {
    var running = false
    func start(onFailure: @escaping (BleAdvertiseFailure) -> Void) { running = true }
    func stop() { running = false }
}

private final class Discovery: DiscoveryControl {
    var running = false
    func start() { running = true }
    func stop() { running = false }
}

private struct Roomy: StorageCapacity {
    var usableSpaceBytes: Int64 { Int64.max }
}

final class DiscoveringAdvertiserTests: XCTestCase {

    private let radio = Radio()
    private let discovery = Discovery()
    private var controller: VisibleNearbyController!

    override func setUpWithError() throws {
        let database = try Sqlite3Database(path: ":memory:")
        let key = try DataKeyHierarchy.create(kek: SoftwareKekStore()).deriveKey(for: .database)
        let store = try LocalStore.open(database: database, databaseDomainKey: key, capacity: Roomy(), now: { 0 })
        controller = VisibleNearbyController(
            visibility: DiscoveryVisibility(store: store),
            advertiser: DiscoveringAdvertiser(advertiser: radio, discovery: discovery),
            clock: { Int64(Date().timeIntervalSince1970 * 1000) }, failureHop: { _ in })
    }

    func testDiscoveryRunsExactlyWhileVisibleAndOpen() {
        controller.refresh()
        XCTAssertFalse(discovery.running, "off: nobody is looked for")

        _ = controller.turnOn(.defaultSession)
        XCTAssertTrue(radio.running && discovery.running, "visible and open: seen and seeing")

        controller.setForeground(false)
        XCTAssertFalse(radio.running || discovery.running, "leaving the app stops both at once")
        controller.setForeground(true)
        XCTAssertTrue(discovery.running, "and coming back resumes both")

        controller.turnOff()
        XCTAssertFalse(radio.running || discovery.running, "Turn off stops both at once")
    }

    func testARadioRefusalStopsDiscoveryToo() {
        _ = controller.turnOn(.defaultSession)
        XCTAssertTrue(discovery.running)
        controller.radioFailed(.unauthorized)
        XCTAssertFalse(discovery.running, "no advertising, no looking")
    }
}
