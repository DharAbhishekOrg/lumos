/// Lumos - ios-app/shell / Onboarding end to end: gate, documents, identity.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-ONB-001, SRS-ONB-002, SRS-ONB-006, SRS-ONB-008
/// ADRs: ADR-008, ADR-010
/// Threading: any
/// Notes: Runs the service over a real SQLite file and an in-memory vault, so
/// "no key exists for someone the gate refused" is checked where it matters:
/// the vault record. The store is reopened the way AppBoot reopens it, so a
/// pass here means a relaunch would find the identity.

import Foundation
import XCTest
@testable import LumosAppShell

/// A fixed date, so the age boundary does not depend on when this runs.
private let onboardingToday = ISO8601DateFormatter().date(from: "2026-11-25T00:00:00Z")!

/// A date of birth some whole years before ``onboardingToday``.
private func born(yearsAgo: Int) -> Date {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(secondsFromGMT: 0)!
    return calendar.date(byAdding: .year, value: -yearsAgo, to: onboardingToday)!
}

/// A complete set of shipped documents at one version.
private func shipped(_ version: String) throws -> LegalDocuments {
    try LegalDocuments.load { _ in "# Document\n\nDocument version: \(version)\n" }
}

private struct OpenFailed: Error {}

final class OnboardingServiceTests: XCTestCase {

    private let clock = TestClock(1_764_028_800_000)
    private var file: URL!
    private var vault: MemoryVault!

    override func setUp() {
        file = FileManager.default.temporaryDirectory.appendingPathComponent("lumos-onb-\(UUID().uuidString).db")
        vault = MemoryVault()
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: file)
    }

    private func service() -> OnboardingService {
        let clock = self.clock
        return OnboardingService(clock: { clock.now() })
    }

    /// Opens the store under a root exactly as AppBoot does.
    private func open(_ root: KeyRoot) throws -> LocalStore {
        let clock = self.clock
        return try LocalStore.open(database: try Sqlite3Database(path: file.path),
                                   databaseDomainKey: try root.hierarchy.deriveKey(for: .database),
                                   capacity: NoCapacityLimit(), now: { clock.now() })
    }

    private func createIdentity(_ onboarding: OnboardingService) throws -> LocalStore {
        try onboarding.createIdentity(vault: vault, preferHardware: false, openStore: open)
    }

    func testTheIdentityCannotBeCreatedBeforeTheGateAndTheDocuments() throws {
        let onboarding = service()
        XCTAssertThrowsError(try createIdentity(onboarding)) { XCTAssertEqual($0 as? OnboardingService.OutOfOrder, .init()) }
        XCTAssertEqual(onboarding.declareAge(dateOfBirth: born(yearsAgo: 30), today: onboardingToday), .legalConsent)
        XCTAssertThrowsError(try createIdentity(onboarding), "the documents come before the identity")
        XCTAssertNil(vault.record, "no key root exists until the last step")
        XCTAssertEqual(onboarding.machine.state, .legalConsent)
    }

    func testSomeoneUnderAgeIsBlockedAndNoKeyIsEverMade() throws {
        let onboarding = service()
        XCTAssertEqual(onboarding.declareAge(dateOfBirth: born(yearsAgo: 17), today: onboardingToday),
                       .blockedUnderAge)
        XCTAssertEqual(onboarding.acceptDocuments(try shipped("2026-08-19.1")), .blockedUnderAge,
                       "the documents cannot be accepted past the gate")
        XCTAssertThrowsError(try createIdentity(onboarding))
        XCTAssertNil(vault.record, "no key root for someone the gate refused")
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.path), "and no store either")
        XCTAssertEqual(onboarding.machine.state, .blockedUnderAge)
    }

    func testTheEighteenthBirthdayPassesTheGate() {
        XCTAssertEqual(service().declareAge(dateOfBirth: born(yearsAgo: 18), today: onboardingToday), .legalConsent)
    }

    func testCompletingOnboardingWritesConsentAndIdentityThatARelaunchFinds() throws {
        let onboarding = service()
        onboarding.declareAge(dateOfBirth: born(yearsAgo: 25), today: onboardingToday)
        let documents = try shipped("2026-08-19.1")
        XCTAssertEqual(onboarding.acceptDocuments(documents), .identityCreation)
        clock.advance(5_000)
        _ = try createIdentity(onboarding)
        XCTAssertEqual(onboarding.machine.state, .complete)

        let root = try XCTUnwrap(try KeyRoot.open(from: vault), "the root is in the vault")
        let reopened = try open(root)
        let consent = try XCTUnwrap(try ConsentRepository(store: reopened).load())
        XCTAssertEqual(consent, ConsentRecord(affirmedAtUtcMillis: 1_764_028_800_000,
                                              minimumAgeYears: AgeGate.minimumAgeYears,
                                              acceptedVersions: documents.versions),
                       "consent is stamped when the documents were accepted, not when the key was made")
        XCTAssertFalse(try ConsentRepository(store: reopened).needsConsent(current: documents))
        let identity = try XCTUnwrap(try IdentityRepository(store: reopened).load())
        XCTAssertNoThrow(try ChatIdentity.unlock(record: identity, kek: root.kek),
                         "the identity opens under the root it was made with")
        XCTAssertFalse(try DiscoveryVisibility(store: reopened).isVisibleNearby(),
                       "finishing onboarding does not turn Visible Nearby on")
    }

    func testAFailedLastStepChangesNothingAndCanBeRetried() throws {
        let onboarding = service()
        onboarding.declareAge(dateOfBirth: born(yearsAgo: 40), today: onboardingToday)
        onboarding.acceptDocuments(try shipped("2026-08-19.1"))
        XCTAssertThrowsError(try onboarding.createIdentity(vault: vault, preferHardware: false) { _ in throw OpenFailed() })
        XCTAssertEqual(onboarding.machine.state, .identityCreation, "a failure does not complete onboarding")

        _ = try createIdentity(onboarding)
        XCTAssertEqual(onboarding.machine.state, .complete)
        let reopened = try open(try XCTUnwrap(try KeyRoot.open(from: vault)))
        XCTAssertNotNil(try ConsentRepository(store: reopened).load(), "the held consent survives the failed try")
        XCTAssertTrue(try IdentityRepository(store: reopened).exists())
    }

    func testOnlyTheDocumentsAcceptedAtTheConsentStepAreRecorded() throws {
        let onboarding = service()
        onboarding.declareAge(dateOfBirth: born(yearsAgo: 30), today: onboardingToday)
        let accepted = try shipped("2026-08-19.1")
        onboarding.acceptDocuments(accepted)
        clock.advance(1_000)
        XCTAssertEqual(onboarding.acceptDocuments(try shipped("2027-01-01.1")), .identityCreation,
                       "a second acceptance is not an event at this step")
        _ = try createIdentity(onboarding)
        let reopened = try open(try XCTUnwrap(try KeyRoot.open(from: vault)))
        let consent = try XCTUnwrap(try ConsentRepository(store: reopened).load())
        XCTAssertEqual(consent.acceptedVersions, accepted.versions, "what was shown at the step is what is recorded")
        XCTAssertEqual(consent.affirmedAtUtcMillis, 1_764_028_800_000)
    }

    func testGoingBackToTheGateDropsTheHeldConsent() throws {
        let onboarding = service()
        onboarding.declareAge(dateOfBirth: born(yearsAgo: 30), today: onboardingToday)
        onboarding.acceptDocuments(try shipped("2026-08-19.1"))
        onboarding.machine.on(.restartRequested)
        XCTAssertThrowsError(try createIdentity(onboarding), "consent held from before a restart is not used")
        XCTAssertEqual(onboarding.declareAge(dateOfBirth: born(yearsAgo: 16), today: onboardingToday),
                       .blockedUnderAge)
        XCTAssertThrowsError(try createIdentity(onboarding), "nor after the gate then refuses")
        XCTAssertNil(vault.record)
    }

    func testOnboardingCannotRunTwice() throws {
        let onboarding = service()
        onboarding.declareAge(dateOfBirth: born(yearsAgo: 30), today: onboardingToday)
        onboarding.acceptDocuments(try shipped("2026-08-19.1"))
        _ = try createIdentity(onboarding)
        let first = vault.record
        XCTAssertThrowsError(try createIdentity(onboarding), "a finished onboarding does not mint a second identity")
        XCTAssertEqual(vault.record, first, "and does not replace the key root")
    }
}
