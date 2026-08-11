/// Lumos - ios-app/shell / Tests for the empty iOS shell.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-REP-004, TRS-REP-007
/// ADRs: -
/// Threading: any
/// Notes: TestShellDeclaresNoCapabilities is the one worth keeping past WP-01.
/// An empty shell stays empty only if something asserts it, and "we did not add
/// anything yet" is not a property a reader can verify from a diff.

import XCTest
@testable import LumosAppShell

final class LumosAppShellTests: XCTestCase {

    func testFourEnvironmentsAreSeparated() {
        let all = LumosEnvironment.allCases.map(\.rawValue)
        XCTAssertEqual(all, ["development", "test", "staging", "production"],
                       "TRS-REP-004 separates exactly these four environments")
    }

    func testBuildProvenanceIsReported() {
        let build = LumosBuildInfo(version: "0.0.0-dev", revision: "abc1234", environment: .development)
        let shell = LumosAppShell(build: build)
        XCTAssertTrue(shell.isReady())
        XCTAssertTrue(build.summary.contains("0.0.0-dev"))
        XCTAssertTrue(build.summary.contains("abc1234"))
        XCTAssertTrue(build.summary.contains("development"))
    }

    func testEmptyVersionIsNotReady() {
        let shell = LumosAppShell(
            build: LumosBuildInfo(version: "", revision: "r", environment: .production))
        XCTAssertFalse(shell.isReady(), "an artefact with no version is not release-shaped")
    }
}
