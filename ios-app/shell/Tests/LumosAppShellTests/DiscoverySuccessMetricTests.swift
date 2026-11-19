/// Lumos - ios-app/shell / The metric's closed grammar, enforced.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-ENV-003, SRS-NFR-SEC-013, SRS-DAT-002
/// ADRs: ADR-002
/// Threading: test-only
/// Notes: The Android suite, byte for byte: the whole payload must match one
/// regular expression (a WHITELIST, so no field can be added quietly), the
/// buckets destroy the exact count, and success means presence, not effort.

import Foundation
import XCTest
@testable import LumosAppShell

final class DiscoverySuccessMetricTests: XCTestCase {

    private func grammar() throws -> NSRegularExpression {
        try NSRegularExpression(pattern:
            "^lumos-discovery-metric/1\\n"
            + "cycles=\\d+\\n"
            + "max_step=\\d+\\n"
            + "peers=(none|one|few|some|many)\\n"
            + "success=(true|false)\\n$")
    }

    private func openInMemory() throws -> LocalStore {
        let database = try Sqlite3Database(path: ":memory:")
        let key = try DataKeyHierarchy.create(kek: SoftwareKekStore()).deriveKey(for: .database)
        return try LocalStore.open(database: database, databaseDomainKey: key,
                                   capacity: UnboundedCapacity(), now: { 0 })
    }

    func testTheWholePayloadMatchesTheClosedGrammar() throws {
        let store = try openInMemory()
        let metric = DiscoverySuccessMetric(store: store)
        for session in [(0, 0, 0), (5, 1, 1), (12, 3, 4), (40, 2, 17), (3, 0, 150)] {
            let id = try metric.record(cyclesRun: session.0, maxStepReached: session.1,
                                       peersPresent: session.2)
            let data = try XCTUnwrap(store.get(kind: .diagnosticEvent, id: id))
            let payload = try XCTUnwrap(String(bytes: data, encoding: .utf8))
            let range = NSRange(payload.startIndex..., in: payload)
            XCTAssertNotNil(try grammar().firstMatch(in: payload, range: range),
                            "every byte of the record comes from the closed grammar: \(payload)")
        }
    }

    func testTheBucketsDestroyTheExactCount() {
        XCTAssertEqual(DiscoverySuccessMetric.bucket(peersPresent: 0), "none")
        XCTAssertEqual(DiscoverySuccessMetric.bucket(peersPresent: 1), "one")
        XCTAssertEqual(DiscoverySuccessMetric.bucket(peersPresent: 2), "few")
        XCTAssertEqual(DiscoverySuccessMetric.bucket(peersPresent: 5), "few")
        XCTAssertEqual(DiscoverySuccessMetric.bucket(peersPresent: 6), "some")
        XCTAssertEqual(DiscoverySuccessMetric.bucket(peersPresent: 20), "some")
        XCTAssertEqual(DiscoverySuccessMetric.bucket(peersPresent: 21), "many")
        XCTAssertEqual(DiscoverySuccessMetric.bucket(peersPresent: 500), "many",
                       "a packed venue and a festival look the same on disk")
    }

    func testSuccessIsPresenceNotEffort() throws {
        let store = try openInMemory()
        let metric = DiscoverySuccessMetric(store: store)
        let empty = try metric.record(cyclesRun: 30, maxStepReached: 3, peersPresent: 0)
        let found = try metric.record(cyclesRun: 1, maxStepReached: 0, peersPresent: 1)
        let emptyPayload = try XCTUnwrap(String(
            bytes: XCTUnwrap(store.get(kind: .diagnosticEvent, id: empty)), encoding: .utf8))
        let foundPayload = try XCTUnwrap(String(
            bytes: XCTUnwrap(store.get(kind: .diagnosticEvent, id: found)), encoding: .utf8))
        XCTAssertTrue(emptyPayload.contains("success=false"),
                      "thirty cycles of trying is still a false negative to measure")
        XCTAssertTrue(foundPayload.contains("success=true"),
                      "one peer on the first cycle is the success the field rate counts")
    }
}

/// Unlimited room, for tests that are not about capacity.
private struct UnboundedCapacity: StorageCapacity {
    var usableSpaceBytes: Int64 { Int64.max }
}
