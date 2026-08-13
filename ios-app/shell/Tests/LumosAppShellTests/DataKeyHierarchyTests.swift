/// Lumos - ios-app/shell / Per-domain derivation and crypto-erasure tests.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-DAT-002, TRS-IOS-007
/// ADRs: ADR-003, ADR-004
/// Threading: any
/// Notes: The properties that make one wrapped root safe to persist: domains
/// derive distinct keys, derivation is deterministic across restore, and KEK
/// destruction erases every domain at once - which is what lets the keys-first
/// wipe order treat content clearing as hygiene rather than the mechanism.

import Foundation
import XCTest
@testable import LumosAppShell

/// Behaviour of the KEK-rooted data-key hierarchy.
final class DataKeyHierarchyTests: XCTestCase {

    func testDomainsDeriveDistinctKeys() throws {
        let hierarchy = try DataKeyHierarchy.create(kek: SoftwareKekStore())
        let db = try hierarchy.deriveKey(for: .database)
        let media = try hierarchy.deriveKey(for: .mediaStore)
        XCTAssertEqual(db.count, 32)
        XCTAssertEqual(media.count, 32)
        XCTAssertNotEqual(db, media,
                          "domain separation must yield different keys for different stores")
    }

    func testRestoreDerivesTheSameKeys() throws {
        let kek = SoftwareKekStore()
        let original = try DataKeyHierarchy.create(kek: kek)
        let before = try original.deriveKey(for: .database)

        let restored = DataKeyHierarchy.restore(kek: kek, wrappedRoot: original.wrappedRoot)
        XCTAssertEqual(try restored.deriveKey(for: .database), before,
                       "a restart must reopen the same database key from the persisted wrapped root")
    }

    func testFreshHierarchiesAreIndependent() throws {
        let kek = SoftwareKekStore()
        let first = try DataKeyHierarchy.create(kek: kek).deriveKey(for: .database)
        let second = try DataKeyHierarchy.create(kek: kek).deriveKey(for: .database)
        XCTAssertNotEqual(first, second, "each created hierarchy draws a fresh CSPRNG root")
    }

    func testKekDestructionErasesEveryDomainAtOnce() throws {
        let kek = SoftwareKekStore()
        let hierarchy = try DataKeyHierarchy.create(kek: kek)
        _ = try hierarchy.deriveKey(for: .database)
        kek.destroy()
        for domain in DataKeyHierarchy.DataDomain.allCases {
            XCTAssertThrowsError(try hierarchy.deriveKey(for: domain),
                                 "\(domain) must be unrecoverable once the KEK is gone (crypto-erasure)")
        }
    }
}
