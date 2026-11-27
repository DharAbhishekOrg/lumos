/// Lumos - LumosAppShellTests / The person's read-receipt choice persists, and fails toward privacy.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-RMT-014, TRS-LOC-001
/// ADRs: ADR-003
/// Threading: test-only
/// Notes: The Android ChatSettingsTest, assertion for assertion, over the real
/// store: no row is the product default; the choice survives a fresh reader;
/// a row this build cannot read is off; the payload is the choice and its time.

import Foundation
import XCTest
@testable import LumosAppShell

final class ChatSettingsTests: XCTestCase {

    private static let now: Int64 = 1_755_561_600_000

    private struct Roomy: StorageCapacity {
        var usableSpaceBytes: Int64 { Int64.max }
    }

    private func openInMemory() throws -> LocalStore {
        let database = try Sqlite3Database(path: ":memory:")
        let key = try DataKeyHierarchy.create(kek: SoftwareKekStore()).deriveKey(for: .database)
        return try LocalStore.open(database: database, databaseDomainKey: key, capacity: Roomy(), now: { 0 })
    }

    func testNoStoredChoiceIsTheProductDefault() throws {
        XCTAssertEqual(try ChatSettings(store: try openInMemory()).readReceipts(), ChatSettings.readReceiptsDefault)
    }

    func testTheChoicePersistsBothWays() throws {
        let store = try openInMemory()
        try ChatSettings(store: store).setReadReceipts(false, atUtcMillis: Self.now)
        XCTAssertFalse(try ChatSettings(store: store).readReceipts(), "off survives a fresh reader")
        try ChatSettings(store: store).setReadReceipts(true, atUtcMillis: Self.now + 1)
        XCTAssertTrue(try ChatSettings(store: store).readReceipts(), "and so does on")
    }

    func testAnUnreadableRowIsOffNeverBackOn() throws {
        let store = try openInMemory()
        for payload in ["lumos-chat-settings/2\nread_receipts=on\n", "garbage", "lumos-chat-settings/1\n",
                        "lumos-chat-settings/1\nread_receipts=yes\n"] {
            try store.put(kind: .chatSettings, id: ChatSettings.settingsID, plaintext: Data(payload.utf8))
            XCTAssertFalse(try ChatSettings(store: store).readReceipts(), payload)
        }
    }

    func testThePayloadIsTheChoiceAndItsTimeOnly() throws {
        let store = try openInMemory()
        try ChatSettings(store: store).setReadReceipts(false, atUtcMillis: Self.now)
        let raw = try XCTUnwrap(try store.get(kind: .chatSettings, id: ChatSettings.settingsID))
        XCTAssertEqual(String(bytes: raw, encoding: .utf8), "lumos-chat-settings/1\nread_receipts=off\n\(Self.now)\n")
    }
}
