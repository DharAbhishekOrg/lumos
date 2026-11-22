/// Lumos - LumosAppShellTests / Several record writes are one transaction, with the Android port's nesting semantics.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-LOC-004, TRS-PAT-004, TRS-TST-001
/// ADRs: ADR-003
/// Threading: test-only
/// Notes: The Swift side of the Java TransactionTest. TRS Sec. 8.3 needs a
/// message and its outbox item to land together. LocalStore.transaction makes
/// several puts one transaction; each put's own transaction joins it. A failure
/// in ANY nested write rolls back EVERYTHING, even when the outer body catches
/// the error and carries on, so the two platforms agree.

import Foundation
import XCTest
@testable import LumosAppShell

final class TransactionTests: XCTestCase {

    private let tag = Data([1, 2, 3])

    private func openInMemory() throws -> LocalStore {
        try TestStores.openInMemory()
    }

    private struct InnerFailure: Error {}

    func testEveryWriteInsideLandsTogether() throws {
        let store = try openInMemory()
        try store.transaction {
            try store.put(kind: .chatMessage, plaintext: Data([1]), naturalKey: tag)
            try store.put(kind: .outboxItem, plaintext: Data([2]))
        }
        XCTAssertEqual(try store.count(kind: .chatMessage), 1)
        XCTAssertEqual(try store.count(kind: .outboxItem), 1)
    }

    func testAFailureAfterTheFirstWriteRollsBackTheFirstWriteToo() throws {
        let store = try openInMemory()
        XCTAssertThrowsError(try store.transaction {
            try store.put(kind: .chatMessage, plaintext: Data([1]), naturalKey: tag)
            // A tag-keyed kind without its tag: refused by put.
            try store.put(kind: .ratchetSession, plaintext: Data([2]))
        })
        XCTAssertEqual(try store.count(kind: .chatMessage), 0, "the message must not outlive its failed sibling")
    }

    func testANestedFailureCaughtByTheOuterBodyStillRollsEverythingBack() throws {
        let store = try openInMemory()
        XCTAssertThrowsError(try store.transaction {
            try store.put(kind: .chatMessage, plaintext: Data([1]), naturalKey: tag)
            do {
                try store.transaction { throw InnerFailure() }
            } catch {
                // The outer body carries on, as careless code might.
            }
            try store.put(kind: .outboxItem, plaintext: Data([2]))
        })
        XCTAssertEqual(try store.count(kind: .chatMessage), 0)
        XCTAssertEqual(try store.count(kind: .outboxItem), 0)
    }

    func testAStoreIsUsableAgainAfterARolledBackTransaction() throws {
        let store = try openInMemory()
        XCTAssertThrowsError(try store.transaction { throw InnerFailure() })
        try store.transaction {
            try store.put(kind: .outboxItem, plaintext: Data([3]))
        }
        XCTAssertEqual(try store.count(kind: .outboxItem), 1, "a rollback must leave no doomed state behind")
    }

    func testEntriesFoundByNaturalKeyCarryTheirRowIds() throws {
        let store = try openInMemory()
        let a = try store.put(kind: .chatMessage, plaintext: Data("a".utf8), naturalKey: tag)
        let b = try store.put(kind: .chatMessage, plaintext: Data("b".utf8), naturalKey: tag)
        try store.put(kind: .chatMessage, plaintext: Data("other".utf8), naturalKey: Data([9]))
        let found = try store.findEntriesByNaturalKey(kind: .chatMessage, naturalKey: tag)
        XCTAssertEqual(Set(found.map(\.id)), [a, b])
        XCTAssertEqual(Set(found.map(\.content)), [Data("a".utf8), Data("b".utf8)])
    }
}
