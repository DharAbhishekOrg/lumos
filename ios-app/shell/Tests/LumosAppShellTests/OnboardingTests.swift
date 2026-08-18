/// Lumos - ios-app/shell / Onboarding, consent, visibility, wipe and reset.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-ONB-001, SRS-ONB-002, SRS-ONB-006, SRS-ONB-008, SRS-IDN-003, SRS-SAF-009, SRS-SAF-010
/// ADRs: ADR-003, ADR-008, ADR-010
/// Threading: any
/// Notes: The iOS half of slice S4c, mirroring the Android suite's strongest
/// assertions rather than all of them - the two platforms share the payload
/// formats and the defaults, and those are what a divergence would break.
///
/// The surface-gate tests iterate `AppSurface.allCases` for the reason the
/// Android ones do: threat model LT-30 is about the screen somebody adds later,
/// not the screens that exist today.

import Foundation
import LumosProtocolGenerated
import XCTest
@testable import LumosAppShell

/// Unlimited room, for tests that are not about capacity.
private struct UnlimitedRoom: StorageCapacity {
    var usableSpaceBytes: Int64 { Int64.max }
}

/// A fixed date, so the age-gate boundaries do not depend on when this runs.
private let today = ISO8601DateFormatter().date(from: "2026-08-19T00:00:00Z")!

/// Builds a date at midnight UTC.
private func day(_ year: Int, _ month: Int, _ dayOfMonth: Int) -> Date {
    var components = DateComponents()
    components.year = year
    components.month = month
    components.day = dayOfMonth
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(secondsFromGMT: 0)!
    return calendar.date(from: components)!
}

/// A fixed export timestamp. File level, not a stored property: the store's
/// clock is a `@Sendable` closure, and reading `self.something` inside one
/// captures the non-Sendable XCTestCase, which Swift 6 refuses.
private let affirmedAtMillis: Int64 = 1_755_561_600_000

/// A complete acceptance map at a placeholder version.
private func everyDocument(at version: String) -> [LegalDocument: String] {
    Dictionary(uniqueKeysWithValues: LegalDocument.allCases.map { ($0, version) })
}

/// Slice S4c on iOS.
final class OnboardingTests: XCTestCase {

    private let affirmedAt = affirmedAtMillis

    /// A standing store over an in-memory engine.
    private func openStack() throws -> (LocalStore, SoftwareKekStore, Sqlite3Database) {
        let database = try Sqlite3Database(path: ":memory:")
        let kek = SoftwareKekStore()
        let hierarchy = try DataKeyHierarchy.create(kek: kek)
        let store = try LocalStore.open(database: database,
                                        databaseDomainKey: hierarchy.deriveKey(for: .database),
                                        capacity: UnlimitedRoom(), now: { 0 })
        return (store, kek, database)
    }

    // MARK: - Age gate (SRS-ONB-001)

    func testTheMinimumComesFromTheRegisterAndIsEighteen() {
        XCTAssertEqual(AgeGate.minimumAgeYears, LumosConstants.adultAgeMinimumYears)
        // SRS-NFR-LEG-007's global floor, transcribed by hand: reading it back
        // from the same constant the code reads would prove only self-agreement.
        XCTAssertEqual(AgeGate.minimumAgeYears, 18)
    }

    func testTheDayBeforeAnEighteenthBirthdayIsRefused() {
        XCTAssertFalse(AgeGate.evaluate(dateOfBirth: day(2008, 8, 20), today: today).isAdult)
    }

    func testAnEighteenthBirthdayItselfIsAccepted() {
        XCTAssertTrue(AgeGate.evaluate(dateOfBirth: day(2008, 8, 19), today: today).isAdult)
    }

    func testADateInTheFutureIsNotAnAdult() {
        XCTAssertFalse(AgeGate.evaluate(dateOfBirth: day(2027, 1, 1), today: today).isAdult,
                       "a clock skew or a typo must fail closed")
    }

    func testTheDecisionRecordsWhatItWasJudgedAgainst() {
        let decision = AgeGate.evaluate(dateOfBirth: day(1991, 4, 7), today: today)
        XCTAssertTrue(decision.isAdult)
        XCTAssertEqual(decision.minimumAgeYears, AgeGate.minimumAgeYears)
    }

    // MARK: - Surface gate (threat model LT-30)

    func testAFreshDeviceCanReachTheAgeGateAndNothingElse() {
        let machine = OnboardingMachine()
        for surface in AppSurface.allCases {
            XCTAssertEqual(machine.isReachable(surface), surface == .ageGate,
                           "\(surface) reachability before the age gate")
        }
    }

    func testABlockedUserReachesNothingAtAll() {
        let machine = OnboardingMachine()
        machine.on(.ageDeclared(AgeGate.evaluate(dateOfBirth: day(2015, 4, 7), today: today)))
        XCTAssertEqual(machine.state, .blockedUnderAge)
        for surface in AppSurface.allCases {
            XCTAssertFalse(machine.isReachable(surface),
                           "\(surface) must be closed to a user the gate refused")
        }
    }

    func testEverythingOpensOnlyWhenOnboardingIsComplete() {
        let machine = OnboardingMachine()
        machine.on(.ageDeclared(AgeGate.evaluate(dateOfBirth: day(1991, 4, 7), today: today)))
        machine.on(.legalDocumentsAccepted(everyDocument(at: "1")))
        XCTAssertFalse(machine.isReachable(.profileEditor), "the identity does not exist yet")
        machine.on(.identityCreated)
        for surface in AppSurface.allCases {
            XCTAssertTrue(machine.isReachable(surface), "\(surface) after completion")
        }
    }

    func testUnexpectedEventsAreIgnoredRatherThanAdvancing() {
        let machine = OnboardingMachine()
        XCTAssertEqual(machine.on(.legalDocumentsAccepted(everyDocument(at: "1"))), .ageGate)
        XCTAssertEqual(machine.on(.identityCreated), .ageGate)
        machine.on(.ageDeclared(AgeGate.evaluate(dateOfBirth: day(1991, 4, 7), today: today)))
        XCTAssertEqual(machine.on(.identityCreated), .legalConsent)
    }

    func testRestartReturnsToTheGateFromEveryState() {
        for state in OnboardingState.allCases {
            XCTAssertEqual(OnboardingMachine(state: state).on(.restartRequested), .ageGate,
                           "restart from \(state)")
        }
    }

    // MARK: - Consent (SRS-ONB-002)

    func testAConsentRecordRoundTripsThroughTheSharedFormat() throws {
        let record = ConsentRecord(affirmedAtUtcMillis: affirmedAt, minimumAgeYears: 18,
                                   acceptedVersions: everyDocument(at: "2026-08-19.1"))
        let back = try ConsentCodec.decode(ConsentCodec.encode(record))
        XCTAssertEqual(back, record)
    }

    func testTheEncodingIsTheOneAndroidWrites() {
        let record = ConsentRecord(affirmedAtUtcMillis: affirmedAt, minimumAgeYears: 18,
                                   acceptedVersions: [.termsOfUse: "1"])
        let text = String(decoding: ConsentCodec.encode(record), as: UTF8.self)
        // Byte-for-byte the Android format, because a database written by one
        // platform is read by the other.
        XCTAssertEqual(text, "lumos-consent/1\naffirmed_at\t\(affirmedAt)\n"
                       + "minimum_age_years\t18\naccepted\tTERMS_OF_USE\t1\n")
    }

    func testAnUnknownLineTagIsRefusedRatherThanSkipped() {
        let odd = Data("lumos-consent/1\naffirmed_at\t1\ndate_of_birth\t1991-04-07\n".utf8)
        XCTAssertThrowsError(try ConsentCodec.decode(odd))
    }

    func testAnUnknownPayloadVersionFailsClosed() {
        XCTAssertThrowsError(try ConsentCodec.decode(Data("lumos-consent/9\n".utf8)))
    }

    func testADocumentNeverAcceptedCountsAsOutdated() {
        let record = ConsentRecord(affirmedAtUtcMillis: affirmedAt, minimumAgeYears: 18,
                                   acceptedVersions: [.termsOfUse: "1"])
        XCTAssertEqual(record.outdated(against: everyDocument(at: "1")).count, 3)
    }

    func testTheVersionIsReadFromTheDeclarationRatherThanFromProse() throws {
        let documents = try LegalDocuments.load { _ in
            "# Terms\n\nWe used to have a Document version: 0.9 but not any more.\n\n"
                + "**Document version: 7.3**\n"
        }
        XCTAssertEqual(documents.version(of: .termsOfUse), "7.3")
    }

    func testADocumentWithNoVersionLineIsARefusalNotADefault() {
        XCTAssertThrowsError(try LegalDocuments.load { _ in "# Terms\n\nNothing here.\n" }) {
            XCTAssertEqual($0 as? LegalDocumentError,
                           .noVersionLine(LegalDocument.termsOfUse.assetName))
        }
    }

    func testConsentIsStoredAndReAskedForWhenAVersionMoves() throws {
        let (store, _, database) = try openStack()
        defer { database.close() }
        let repository = ConsentRepository(store: store)
        let shipped = try LegalDocuments.load { _ in "Document version: 2026-08-19.1\n" }

        XCTAssertTrue(try repository.needsConsent(current: shipped))
        try repository.save(ConsentRecord(affirmedAtUtcMillis: affirmedAt, minimumAgeYears: 18,
                                          acceptedVersions: shipped.versions))
        XCTAssertFalse(try repository.needsConsent(current: shipped))

        let moved = try LegalDocuments.load { _ in "Document version: 2027-01-01.1\n" }
        XCTAssertTrue(try repository.needsConsent(current: moved))
    }

    // MARK: - Visible Nearby (SRS-ONB-008)

    func testVisibleNearbyIsOffOnAFreshDevice() throws {
        let (store, _, database) = try openStack()
        defer { database.close() }
        XCTAssertFalse(try DiscoveryVisibility(store: store).isVisibleNearby(),
                       "a device that has never been told otherwise is not discoverable")
    }

    func testOnlyAnExplicitRequestTurnsVisibleNearbyOn() throws {
        let (store, _, database) = try openStack()
        defer { database.close() }
        let visibility = DiscoveryVisibility(store: store)
        try visibility.enableAfterExplicitUserRequest(atUtcMillis: affirmedAt)
        XCTAssertTrue(try visibility.isVisibleNearby())
        try visibility.disable(atUtcMillis: affirmedAt + 1)
        XCTAssertFalse(try visibility.isVisibleNearby())
    }

    func testAnUnreadableVisibilityRecordReadsAsOff() throws {
        let (store, _, database) = try openStack()
        defer { database.close() }
        try store.put(kind: .discoveryVisibility, id: DiscoveryVisibility.visibilityId,
                      plaintext: Data("lumos-visibility/99\non\n0\n".utf8))
        XCTAssertFalse(try DiscoveryVisibility(store: store).isVisibleNearby(),
                       "a record from a format this build does not know fails towards off")
    }

    // MARK: - Deliberate confirmation (SRS-SAF-009)

    func testTheRightPhraseInTimeConfirmsAndTheWrongOneDoesNot() throws {
        let phrase = "erase everything"
        let issued = try DeliberateConfirmation.issue(action: .emergencyWipe,
                                                      requiredPhrase: phrase, nowUtcMillis: 0)
        XCTAssertThrowsError(try DeliberateConfirmation
            .issue(action: .emergencyWipe, requiredPhrase: phrase, nowUtcMillis: 0)
            .confirm(typed: "erase", nowUtcMillis: 1)) {
                XCTAssertEqual($0 as? NotConfirmed, .phraseDoesNotMatch)
            }
        let confirmation = try issued.confirm(typed: "  Erase Everything ", nowUtcMillis: 1)
        XCTAssertEqual(confirmation.action, .emergencyWipe)
    }

    func testAChallengeExpiresAndIsSingleUse() throws {
        let phrase = "erase everything"
        let stale = try DeliberateConfirmation.issue(action: .emergencyWipe,
                                                     requiredPhrase: phrase, nowUtcMillis: 0)
        XCTAssertThrowsError(try stale.confirm(
            typed: phrase, nowUtcMillis: DeliberateConfirmation.windowMillis + 1)) {
                XCTAssertEqual($0 as? NotConfirmed, .windowHasPassed)
            }

        let once = try DeliberateConfirmation.issue(action: .emergencyWipe,
                                                    requiredPhrase: phrase, nowUtcMillis: 0)
        _ = try once.confirm(typed: phrase, nowUtcMillis: 1)
        XCTAssertThrowsError(try once.confirm(typed: phrase, nowUtcMillis: 2)) {
            XCTAssertEqual($0 as? NotConfirmed, .alreadyUsed)
        }
    }

    // MARK: - Emergency wipe (SRS-SAF-009, SRS-SAF-010)

    func testEveryRecordKindThatExistsIsClearedKeysFirst() throws {
        let (store, kek, database) = try openStack()
        defer { database.close() }
        for kind in RecordKind.allCases {
            let payload = Data("private-\(kind.table)".utf8)
            try store.put(kind: kind, id: kind.table, plaintext: payload,
                          naturalKey: kind.isLookedUpByTag ? payload : nil)
        }

        let confirmation = try DeliberateConfirmation
            .issue(action: .emergencyWipe, requiredPhrase: "erase everything", nowUtcMillis: 0)
            .confirm(typed: "erase everything", nowUtcMillis: 1)
        let outcome = try EmergencyWipe(kek: kek, store: store).execute(confirmation: confirmation)

        XCTAssertTrue(outcome.cryptoErasureHolds)
        XCTAssertTrue(outcome.report.fullyClean)
        XCTAssertEqual(outcome.report.results.map(\.name),
                       [EmergencyWipe.stageKek, EmergencyWipe.stageStoreKeys,
                        EmergencyWipe.stageLocalRecords])
        XCTAssertEqual(outcome.report.results.map(\.phase), [.keyMaterial, .keyMaterial, .content])
        for kind in RecordKind.allCases {
            XCTAssertEqual(try store.count(kind: kind), 0,
                           "\(kind.table) survived the wipe")
        }
    }

    func testAResetConfirmationCannotBeSpentOnAWipe() throws {
        let (store, kek, database) = try openStack()
        defer { database.close() }
        let forReset = try DeliberateConfirmation
            .issue(action: .identityReset, requiredPhrase: "new identity", nowUtcMillis: 0)
            .confirm(typed: "new identity", nowUtcMillis: 1)
        XCTAssertThrowsError(
            try EmergencyWipe(kek: kek, store: store).execute(confirmation: forReset))
        XCTAssertFalse(kek.isDestroyed, "a refused wipe destroys nothing on its way to refusing")
    }

    func testTheCoverageStatementNamesEverySrsSaf010ItemHonestly() {
        let coverage = WipeCoverage.forWpTwo
        let everything = (coverage.covered + coverage.notYetBuilt + coverage.beyondReach)
            .map { "\($0.what) \($0.status)" }.joined(separator: " ").lowercased()
        for item in ["profile", "conversations", "cached objects", "queued packets",
                     "venue tokens", "cryptographic state"] {
            XCTAssertTrue(everything.contains(item),
                          "SRS-SAF-010 names \(item) and the coverage statement omits it")
        }
        XCTAssertFalse(coverage.notYetBuilt.isEmpty)
        XCTAssertFalse(coverage.beyondReach.isEmpty)
        for item in coverage.notYetBuilt {
            XCTAssertTrue(item.status.contains("WP-"),
                          "a pending item must name the work package that closes it")
        }
    }

    // MARK: - Identity reset (SRS-IDN-003)

    func testAResetReplacesBothHalvesOfTheIdentityAndKeepsTheContent() throws {
        let (store, kek, database) = try openStack()
        defer { database.close() }
        let identities = IdentityRepository(store: store)
        try identities.replace(try IdentityKeyGenerator.generate(kek: kek))
        let before = try XCTUnwrap(try identities.load())
        try store.put(kind: .localProfile, id: "self", plaintext: Data("my profile".utf8))

        let confirmation = try DeliberateConfirmation
            .issue(action: .identityReset, requiredPhrase: "new identity", nowUtcMillis: 0)
            .confirm(typed: "new identity", nowUtcMillis: 1)
        let result = try IdentityReset(identities: identities, kek: kek)
            .reset(confirmation: confirmation)

        XCTAssertTrue(result.identityIsNew)
        XCTAssertEqual(result.previousEd25519Public, before.ed25519Public)
        let now = try XCTUnwrap(try identities.load())
        XCTAssertNotEqual(now.x25519Public, before.x25519Public,
                          "both halves of the ADR-008 identity are new")
        XCTAssertTrue(IdentityKeyGenerator.verifyBinding(ed25519Public: now.ed25519Public,
                                                         x25519Public: now.x25519Public,
                                                         signature: now.bindingSignature))
        XCTAssertNotNil(try store.get(kind: .localProfile, id: "self"),
                        "a reset is not a wipe: the profile stays")
        XCTAssertFalse(kek.isDestroyed)
    }

    func testTheResetWarningNamesMatchesConversationsAndWhatSurvives() throws {
        let (store, kek, database) = try openStack()
        defer { database.close() }
        let identities = IdentityRepository(store: store)
        try identities.replace(try IdentityKeyGenerator.generate(kek: kek))

        let consequences = try IdentityReset(identities: identities, kek: kek).consequences()
        let lost = consequences.lost.joined(separator: " ").lowercased()
        let kept = consequences.kept.joined(separator: " ").lowercased()
        XCTAssertTrue(lost.contains("match"))
        XCTAssertTrue(lost.contains("conversation"))
        XCTAssertTrue(kept.contains("profile") && kept.contains("block list"))
        XCTAssertTrue(consequences.residual.joined().lowercased().contains("storage"))
    }

    // MARK: - Recovery bundle (SRS-ONB-007, SRS-DAT-004)

    func testTheBundleFormatIsTheOneAndroidWrites() {
        let bundle = RecoveryBundle(exportedAtUtcMillis: affirmedAt, sections: [
            .profile: [RecoveryBundle.Item(id: "self", content: Data("hi".utf8))]
        ])
        let text = String(decoding: RecoveryBundleCodec.encode(bundle), as: UTF8.self)
        XCTAssertEqual(text, "lumos-recovery-bundle/1\nexported_at\t\(affirmedAt)\n"
                       + "unavailable\tmatch_roster\nunavailable\tconversation_history\n"
                       + "row\tprofile\tself\t\(Data("hi".utf8).base64EncodedString())\n")
    }

    func testABundleFromANewerBuildIsRefusedRatherThanPartlyRestored() {
        for bad in ["lumos-recovery-bundle/2\n",
                    "lumos-recovery-bundle/1\nrow\tsomething_new\tid\tAAAA\n",
                    "lumos-recovery-bundle/1\nattachment\tx\n"] {
            XCTAssertThrowsError(try RecoveryBundleCodec.decode(Data(bad.utf8)),
                                 "restoring part of a file and reporting success is worse "
                                    + "than refusing")
        }
    }

    func testAnExportCarriesTheContentSectionsAndNamesTheOnesItCannot() throws {
        let (store, kek, database) = try openStack()
        defer { database.close() }
        try IdentityRepository(store: store)
            .replace(try IdentityKeyGenerator.generate(kek: kek))
        try store.put(kind: .localProfile, id: "self", plaintext: Data("my profile".utf8))
        let blocked = Data("peer-identifier".utf8)
        try store.put(kind: .blockEntry, id: "b1", plaintext: blocked, naturalKey: blocked)

        let bundle = try RecoverySetup(store: store, now: { affirmedAtMillis }).assemble()
        XCTAssertEqual(bundle.filledSections, [.profile, .blockList])
        XCTAssertEqual(bundle.unavailableSections, [.matchRoster, .conversationHistory])
        XCTAssertEqual(bundle.rowCount, 2)
        for section in RecoverySection.allCases {
            XCTAssertNotEqual(section.kind, .identityKeyRecord,
                              "ADR-003 forbids exporting the identity table")
        }
    }

    func testAnExportRestoresOntoAFreshIdentityAndCarriesNoKeyMaterial() throws {
        let (source, sourceKek, sourceDatabase) = try openStack()
        defer { sourceDatabase.close() }
        let identity = try IdentityKeyGenerator.generate(kek: sourceKek)
        try IdentityRepository(store: source).replace(identity)
        try source.put(kind: .localProfile, id: "self", plaintext: Data("my profile".utf8))
        let sealed = try RecoverySetup(store: source, now: { affirmedAtMillis })
            .export(passphrase: Data("correct horse battery staple".utf8))

        let plaintext = try RecoveryRecord.open(
            passphrase: Data("correct horse battery staple".utf8), record: sealed)
        for secret in [identity.wrappedPrivateKeys, identity.ed25519Public,
                       identity.x25519Public, identity.bindingSignature] {
            XCTAssertNil(sealed.range(of: secret), "ADR-003: no identity material in the record")
            XCTAssertNil(plaintext.range(of: secret), "ADR-003: nor in its content")
        }

        let (target, targetKek, targetDatabase) = try openStack()
        defer { targetDatabase.close() }
        let identities = IdentityRepository(store: target)
        try identities.replace(try IdentityKeyGenerator.generate(kek: targetKek))
        let before = try XCTUnwrap(try identities.load()).ed25519Public

        let result = try RecoveryRestore(store: target, identities: identities, kek: targetKek)
            .restore(passphrase: Data("correct horse battery staple".utf8), sealed: sealed)

        XCTAssertTrue(result.identityIsNew, "SRS-ONB-007: restore creates a new identity")
        XCTAssertEqual(result.previousEd25519Public, before)
        XCTAssertNotEqual(result.newEd25519Public, before)
        XCTAssertNotEqual(result.newEd25519Public, identity.ed25519Public,
                          "and not the exporting device's identity either")
        XCTAssertEqual(result.restoredRows, 1)
        XCTAssertEqual(result.sectionsNotInTheFile, [.matchRoster, .conversationHistory])
        XCTAssertEqual(try target.get(kind: .localProfile, id: "self"), Data("my profile".utf8))
    }

    func testAWrongPassphraseRestoresNothingAndChangesNoIdentity() throws {
        let (source, sourceKek, sourceDatabase) = try openStack()
        defer { sourceDatabase.close() }
        try IdentityRepository(store: source)
            .replace(try IdentityKeyGenerator.generate(kek: sourceKek))
        try source.put(kind: .localProfile, id: "self", plaintext: Data("my profile".utf8))
        let sealed = try RecoverySetup(store: source, now: { affirmedAtMillis })
            .export(passphrase: Data("right".utf8))

        let (target, targetKek, targetDatabase) = try openStack()
        defer { targetDatabase.close() }
        let identities = IdentityRepository(store: target)
        try identities.replace(try IdentityKeyGenerator.generate(kek: targetKek))
        let before = try XCTUnwrap(try identities.load()).ed25519Public

        XCTAssertThrowsError(
            try RecoveryRestore(store: target, identities: identities, kek: targetKek)
                .restore(passphrase: Data("wrong".utf8), sealed: sealed))
        XCTAssertEqual(try XCTUnwrap(try identities.load()).ed25519Public, before,
                       "a failed restore must not have replaced the identity on the way")
        XCTAssertEqual(try target.count(kind: .localProfile), 0)
    }
}
