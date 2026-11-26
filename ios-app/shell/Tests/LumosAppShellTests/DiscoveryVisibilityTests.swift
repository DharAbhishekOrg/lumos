/// Lumos - ios-app/shell / Visible Nearby's chosen while, on iOS.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-ONB-008, SRS-NBY-001, SRS-NBY-002, SRS-NBY-003
/// ADRs: ADR-002
/// Threading: any
/// Notes: The Swift half of the Android VisibleNearbyDurationTest, over a real
/// SQLite store, plus the format change: a version-1 "on" record written by
/// an earlier build reads as off, so an update never leaves anyone visible
/// without choosing again.

import Foundation
import XCTest
import LumosProtocolGenerated
@testable import LumosAppShell

final class DiscoveryVisibilityTests: XCTestCase {

    private let now: Int64 = 1_792_917_000_000
    private let defaultMillis = Int64(LumosConstants.visibleNearbyDurationSecDefault) * 1000
    private let clock = TestClock(1_792_917_000_000)
    private var file: URL!
    private var store: LocalStore!

    override func setUpWithError() throws {
        file = FileManager.default.temporaryDirectory.appendingPathComponent("lumos-vis-\(UUID().uuidString).db")
        let clock = self.clock
        store = try LocalStore.open(database: try Sqlite3Database(path: file.path),
                                    databaseDomainKey: randomBytes(32), capacity: NoCapacityLimit(),
                                    now: { clock.now() })
    }

    override func tearDown() {
        store = nil
        try? FileManager.default.removeItem(at: file)
    }

    func testTheDefaultSessionEndsExactlyOnTheRegisterDefault() throws {
        let visibility = DiscoveryVisibility(store: store)
        XCTAssertFalse(try visibility.isVisibleNearby(nowMillis: now), "off until chosen")
        try visibility.enableAfterExplicitUserRequest(.defaultSession, nowMillis: now)
        XCTAssertTrue(try visibility.isVisibleNearby(nowMillis: now + defaultMillis - 1))
        XCTAssertFalse(try visibility.isVisibleNearby(nowMillis: now + defaultMillis),
                       "off the millisecond the window ends")
        XCTAssertFalse(try visibility.isVisibleNearby(nowMillis: now + 2 * defaultMillis), "and never renews")
        XCTAssertEqual(LumosConstants.visibleNearbyDurationSecDefault, 3600, "one hour, from the register")
    }

    func testACustomDurationIsHeldToTheRegisterBounds() throws {
        XCTAssertNil(DiscoveryVisibility.Selection.custom(seconds: LumosConstants.visibleNearbyDurationSecMin - 1))
        XCTAssertNil(DiscoveryVisibility.Selection.custom(seconds: LumosConstants.visibleNearbyDurationSecMax + 1))
        let chosen = LumosConstants.visibleNearbyDurationSecMax
        let visibility = DiscoveryVisibility(store: store)
        try visibility.enableAfterExplicitUserRequest(try XCTUnwrap(.custom(seconds: chosen)), nowMillis: now)
        XCTAssertTrue(try visibility.isVisibleNearby(nowMillis: now + 1000 * Int64(chosen) - 1))
        XCTAssertFalse(try visibility.isVisibleNearby(nowMillis: now + 1000 * Int64(chosen)))
    }

    func testUntilVenueExitEndsOnTheVenueEventAndOnlyThen() throws {
        let visibility = DiscoveryVisibility(store: store)
        try visibility.enableAfterExplicitUserRequest(.untilVenueExit, nowMillis: now)
        XCTAssertTrue(try visibility.isVisibleNearby(nowMillis: now + 10 * defaultMillis), "no clock ends it")
        try visibility.endOnVenueExit(nowMillis: now + 10 * defaultMillis)
        XCTAssertFalse(try visibility.isVisibleNearby(nowMillis: now + 10 * defaultMillis + 1))
    }

    func testAVenueExitCannotEndATimedSession() throws {
        let visibility = DiscoveryVisibility(store: store)
        try visibility.enableAfterExplicitUserRequest(.defaultSession, nowMillis: now)
        try visibility.endOnVenueExit(nowMillis: now + 1)
        XCTAssertTrue(try visibility.isVisibleNearby(nowMillis: now + 2), "the person chose a time, not a place")
    }

    func testTheIndicatorReportsTheModeAndTheEnd() throws {
        let visibility = DiscoveryVisibility(store: store)
        XCTAssertEqual(try visibility.indicator(nowMillis: now), .off)
        try visibility.enableAfterExplicitUserRequest(.defaultSession, nowMillis: now)
        let on = try visibility.indicator(nowMillis: now)
        XCTAssertEqual(on, DiscoveryVisibility.Indicator(visible: true, mode: .defaultSession,
                                                         expiresAtMillis: now + defaultMillis))
        XCTAssertEqual(try visibility.indicator(nowMillis: now + defaultMillis), .off,
                       "the indicator and the radio share one evaluation")
        try visibility.enableAfterExplicitUserRequest(.defaultSession, nowMillis: now)
        try visibility.disable(nowMillis: now + 1)
        XCTAssertEqual(try visibility.indicator(nowMillis: now + 2), .off, "off is one tap and takes effect at once")
    }

    func testAnOnRecordFromTheEarlierFormatReadsAsOff() throws {
        try store.put(kind: .discoveryVisibility, id: DiscoveryVisibility.visibilityId,
                      plaintext: Data("lumos-visibility/1\non\n\(now)\n".utf8))
        XCTAssertFalse(try DiscoveryVisibility(store: store).isVisibleNearby(nowMillis: now),
                       "an update never leaves anyone visible without choosing again")
    }
}

/// A radio that records what it was told, and fails when scripted to.
private final class ScriptedAdvertiser: BleAdvertising {
    var running = false
    var starts = 0
    var onFailure: ((BleAdvertiseFailure) -> Void)?

    func start(onFailure: @escaping (BleAdvertiseFailure) -> Void) {
        running = true
        starts += 1
        self.onFailure = onFailure
    }

    func stop() { running = false }
}

final class VisibleNearbyControllerTests: XCTestCase {

    private let clock = TestClock(1_792_917_000_000)
    private let radio = ScriptedAdvertiser()
    private var file: URL!
    private var controller: VisibleNearbyController!
    private var hopped: [BleAdvertiseFailure] = []

    override func setUpWithError() throws {
        file = FileManager.default.temporaryDirectory.appendingPathComponent("lumos-vnc-\(UUID().uuidString).db")
        let clock = self.clock
        let store = try LocalStore.open(database: try Sqlite3Database(path: file.path),
                                        databaseDomainKey: randomBytes(32), capacity: NoCapacityLimit(),
                                        now: { clock.now() })
        controller = VisibleNearbyController(visibility: DiscoveryVisibility(store: store), advertiser: radio,
                                             clock: { clock.now() }, failureHop: { _ in })
    }

    override func tearDown() {
        controller = nil
        try? FileManager.default.removeItem(at: file)
    }

    private var hour: Int64 { Int64(LumosConstants.visibleNearbyDurationSecDefault) * 1000 }

    func testTheRadioStaysSilentUntilThePersonTurnsItOn() {
        controller.refresh()
        controller.setForeground(true)
        XCTAssertEqual(radio.starts, 0, "no launch, refresh or foregrounding starts the radio (or asks for Bluetooth)")
        controller.turnOn(.defaultSession)
        XCTAssertTrue(radio.running, "the person's own tap starts it")
        XCTAssertTrue(controller.indicator.visible)
    }

    func testTheRadioStopsTheMomentTheWindowEndsAndSaysSoOnce() {
        controller.turnOn(.defaultSession)
        clock.advance(hour - 1)
        controller.refresh()
        XCTAssertTrue(radio.running)
        clock.advance(1)
        controller.refresh()
        XCTAssertFalse(radio.running, "off on time, by evaluation")
        XCTAssertFalse(controller.indicator.visible)
        XCTAssertTrue(controller.endedOnItsOwn, "the screen can say it turned itself off")
        controller.turnOn(.defaultSession)
        XCTAssertFalse(controller.endedOnItsOwn)
    }

    func testTurningOffAndLeavingTheScreenBothStopTheRadio() {
        controller.turnOn(.defaultSession)
        controller.setForeground(false)
        XCTAssertFalse(radio.running, "no advertising while the app is off screen")
        XCTAssertTrue(controller.indicator.visible, "the chosen window keeps running")
        controller.setForeground(true)
        XCTAssertTrue(radio.running, "back on screen inside the window: on again")
        controller.turnOff()
        XCTAssertFalse(radio.running)
        XCTAssertFalse(controller.endedOnItsOwn, "turned off by the person, not by itself")
    }

    func testBluetoothNotAllowedTurnsVisibilityOffAndSaysWhy() {
        controller.turnOn(.defaultSession)
        controller.radioFailed(.unauthorized)
        XCTAssertEqual(controller.problem, .notAllowed)
        XCTAssertFalse(controller.indicator.visible, "never shown as visible when the radio cannot be")
        XCTAssertFalse(radio.running)
        XCTAssertFalse(controller.endedOnItsOwn)
        controller.turnOn(.defaultSession)
        XCTAssertNil(controller.problem, "trying again clears the old reason")
    }

    func testBluetoothSwitchedOffKeepsTheWindowAndSaysNobodyCanSeeYou() {
        controller.turnOn(.defaultSession)
        controller.radioFailed(.poweredOff)
        XCTAssertEqual(controller.problem, .bluetoothOff)
        XCTAssertTrue(controller.indicator.visible, "the person's choice stands; the screen says why it is silent")
        for failure in [BleAdvertiseFailure.unsupported, .refused, .undocumented] {
            controller.turnOn(.defaultSession)
            controller.radioFailed(failure)
            XCTAssertFalse(controller.indicator.visible, "\(failure) turns it off")
        }
    }
}
