/// Lumos - ios-app/shell / The profile store on iOS, byte for byte with Android.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-PRO-002, SRS-PRO-003, SRS-PRO-004, SRS-PRO-010, TRS-LOC-002
/// ADRs: ADR-003
/// Threading: test-only
/// Notes: The two golden literals are the same as Android's
/// ProfileCodecGoldenTest, so a drift on either side fails a test. Over the
/// real store: nothing saved is no profile and the private policy; a save
/// round-trips; showable needs a name and an age; an unknown format fails closed.

import Foundation
import XCTest
@testable import LumosAppShell

final class ProfileStoreTests: XCTestCase {

    static let profile = "lumos-profile/1\n"
        + "lifecycle\tACTIVE\n"
        + "field\tALIAS\tSTATED\tAsha\n"
        + "field\tAGE_BAND\tSTATED\t25–34\n"
        + "field\tPRONOUNS\tUNSET\t\n"
        + "field\tGENDER_IDENTITY\tUNSET\t\n"
        + "field\tORIENTATION\tPREFER_NOT_TO_SAY\t\n"
        + "field\tRELATIONSHIP_INTENT\tUNSET\t\n"
        + "field\tINTERESTS\tUNSET\t\n"
        + "field\tBIO\tSTATED\tline one%0Aline%09two 50%25\n"
        + "field\tMEDIA\tUNSET\t\n"

    static let policy = "lumos-policy/1\n"
        + "ALIAS\tNEARBY\n"
        + "AGE_BAND\tREQUESTER\n"
        + "PRONOUNS\tMATCH\n"
        + "GENDER_IDENTITY\tMATCH\n"
        + "ORIENTATION\tMATCH\n"
        + "RELATIONSHIP_INTENT\tMATCH\n"
        + "INTERESTS\tMATCH\n"
        + "BIO\tMATCH\n"
        + "MEDIA\tMATCH\n"

    private struct Roomy: StorageCapacity {
        var usableSpaceBytes: Int64 { Int64.max }
    }

    private var sample: LocalProfile {
        LocalProfile.empty
            .with(.alias, .statement("Asha"))
            .with(.ageBand, .statement("25–34"))
            .with(.orientation, .preferNotToSay)
            .with(.bio, .statement("line one\nline\ttwo 50%"))
    }

    private var samplePolicy: ProfileFieldPolicy {
        ProfileFieldPolicy.privateByDefault.with(.alias, .nearby).with(.ageBand, .requester)
    }

    /// Same answers and lifecycle, whether "unset" is stored or absent.
    private func assertSame(_ lhs: LocalProfile?, _ rhs: LocalProfile, line: UInt = #line) {
        guard let lhs else { return XCTFail("no profile", line: line) }
        for field in ProfileField.allCases {
            XCTAssertEqual(lhs.value(field), rhs.value(field), "\(field)", line: line)
        }
        XCTAssertEqual(lhs.lifecycle, rhs.lifecycle, line: line)
        XCTAssertEqual(lhs.media, rhs.media, line: line)
    }

    /// Same threshold for every field, whether a default is stored or implied.
    private func assertSame(_ lhs: ProfileFieldPolicy, _ rhs: ProfileFieldPolicy, line: UInt = #line) {
        for field in ProfileField.allCases {
            XCTAssertEqual(lhs.threshold(field), rhs.threshold(field), "\(field)", line: line)
        }
    }

    private func openInMemory() throws -> LocalStore {
        let database = try Sqlite3Database(path: ":memory:")
        let key = try DataKeyHierarchy.create(kek: SoftwareKekStore()).deriveKey(for: .database)
        return try LocalStore.open(database: database, databaseDomainKey: key, capacity: Roomy(), now: { 0 })
    }

    func testTheProfileEncodesToAndroidsGoldenBytesAndBack() throws {
        XCTAssertEqual(String(bytes: ProfileCodec.encode(sample), encoding: .utf8), Self.profile)
        assertSame(try ProfileCodec.decodeProfile(Data(Self.profile.utf8)), sample)
    }

    func testThePolicyEncodesToAndroidsGoldenBytesAndBack() throws {
        XCTAssertEqual(String(bytes: ProfileCodec.encode(samplePolicy), encoding: .utf8), Self.policy)
        assertSame(try ProfileCodec.decodePolicy(Data(Self.policy.utf8)), samplePolicy)
    }

    func testTheRepositoryRoundTripsAndDefaultsPrivate() throws {
        let repository = ProfileRepository(store: try openInMemory())
        XCTAssertNil(try repository.load())
        assertSame(try repository.loadPolicy(), .privateByDefault)
        XCTAssertFalse(repository.isShowable, "no profile cannot be shown")

        try repository.save(LocalProfile.empty.with(.alias, .statement("Asha")))
        XCTAssertFalse(repository.isShowable, "a name without an age is incomplete (SRS-PRO-010)")
        try repository.save(sample)
        try repository.savePolicy(samplePolicy)
        assertSame(try repository.load(), sample)
        assertSame(try repository.loadPolicy(), samplePolicy)
        XCTAssertTrue(repository.isShowable)
        try repository.save(sample.withLifecycle(.paused))
        XCTAssertFalse(repository.isShowable, "a paused profile is never shown")
    }

    func testAnUnknownFormatFailsClosed() {
        XCTAssertThrowsError(try ProfileCodec.decodeProfile(Data("lumos-profile/9\n".utf8)))
        XCTAssertThrowsError(try ProfileCodec.decodePolicy(Data("lumos-policy/1\nALIAS\tEVERYONE\n".utf8)))
    }
}
