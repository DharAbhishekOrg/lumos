/// Lumos - ios-app/shell / The iPhone Profile tab's logic over the real store.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-PRO-001, SRS-PRO-002, SRS-PRO-003, SRS-PRO-008, SRS-PRO-010
/// ADRs: ADR-003
/// Threading: test-only
/// Notes: The Android ProfilePanelInstrumentedTest's assertions, run against
/// ProfileEditor: private by default, completion by name and age, a decline
/// kept as itself and never shown to others, each audience seeing exactly
/// what was chosen, and an editor with no store saying so.

import Foundation
import XCTest
@testable import LumosAppShell

final class ProfileEditorTests: XCTestCase {

    private struct Roomy: StorageCapacity {
        var usableSpaceBytes: Int64 { Int64.max }
    }

    private func editor() throws -> ProfileEditor {
        let database = try Sqlite3Database(path: ":memory:")
        let key = try DataKeyHierarchy.create(kek: SoftwareKekStore()).deriveKey(for: .database)
        let store = try LocalStore.open(database: database, databaseDomainKey: key, capacity: Roomy(), now: { 0 })
        return ProfileEditor(repository: ProfileRepository(store: store))
    }

    func testAnEmptyProfileIsPrivateAndCannotBeSeenYet() throws {
        let editor = try editor()
        XCTAssertFalse(editor.unreadable)
        XCTAssertFalse(editor.isShowable)
        for field in ProfileEditor.edited {
            XCTAssertEqual(editor.policy.threshold(field), .match, "\(field) private by default")
            XCTAssertEqual(ProfileEditor.shown(editor.profile.value(field)), "Not added")
        }
    }

    func testNameAndAgeCompleteItAndADeclineIsKeptAsItself() throws {
        let editor = try editor()
        editor.set(.alias, .statement("  Asha "))
        XCTAssertFalse(editor.isShowable, "a name alone is not enough")
        editor.set(.ageBand, .statement(ProfileEditor.ageBands[1]))
        editor.set(.orientation, .preferNotToSay)
        XCTAssertTrue(editor.isShowable)
        XCTAssertFalse(editor.notSaved)
        XCTAssertEqual(editor.profile.value(.alias), .stated("Asha"))
        XCTAssertEqual(ProfileEditor.shown(editor.profile.value(.orientation)), "Prefer not to say")
    }

    func testEachAudienceSeesExactlyWhatThePersonChose() throws {
        let editor = try editor()
        editor.set(.alias, .statement("Asha"))
        editor.set(.ageBand, .statement("25–34"))
        editor.set(.orientation, .statement("Queer"))
        editor.set(.pronouns, .preferNotToSay)
        editor.setAudience(.alias, .nearby)
        editor.setAudience(.ageBand, .requester)

        XCTAssertEqual(editor.previewLines(for: .nearby), ["Name: Asha"])
        XCTAssertEqual(editor.previewLines(for: .requester), ["Name: Asha", "Age: 25–34"])
        let match = editor.previewLines(for: .match)
        XCTAssertTrue(match.contains("Sexuality: Queer"))
        XCTAssertFalse(match.contains { $0.contains("Prefer not to say") }, "a decline is never shown as a value")
    }

    func testAnEditorWithNoStoreSaysSoAndSavesNothing() {
        let editor = ProfileEditor(repository: nil)
        XCTAssertTrue(editor.unreadable)
        editor.set(.alias, .statement("Asha"))
        XCTAssertTrue(editor.notSaved)
        XCTAssertFalse(editor.isShowable)
    }
}
