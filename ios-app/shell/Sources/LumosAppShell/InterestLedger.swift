/// Lumos - ios-app/shell / The persisted interest machine, mirrored.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-MAT-001, SRS-MAT-003, SRS-MAT-005, SRS-MAT-007, SRS-MAT-010,
///             SRS-MAT-011, SRS-MAT-014
/// ADRs: ADR-002, ADR-003, ADR-008
/// Threading: serial-queue
/// Notes: The Android ledger decision for decision: MatchMachine decides,
/// this class loads the peer's state from the sealed interest_record
/// (absence is none), steps it, persists what changed through the
/// natural-key upsert, spends the persisted budget on a send_request (a
/// refused budget means the event never reaches the machine), writes the
/// match_record with the derived identifier on an establishing effect, and
/// flips - never deletes - that record on an unmatch (SRS-MAT-010's local
/// half). Absorbed events write nothing.

import Foundation
import LumosProtocol
import LumosProtocolGenerated

/// The persisted rolling-hour interest-request allowance (SRS-MAT-011).
public final class PersistedBudget {

    /// The single-row identifier.
    public static let budgetID = "self"

    private static let versionLine = "lumos-interest-budget/1"
    private static let windowMillis: Int64 = 3_600_000

    private let store: LocalStore

    /// Binds the budget, refusing register values outside their bounds.
    public init(store: LocalStore) {
        precondition(
            (LumosConstants.interestRateLimitPerHourMin
                ... LumosConstants.interestRateLimitPerHourMax)
                .contains(LumosConstants.interestRateLimitPerHourDefault)
                && (LumosConstants.interestCooldownSecMin
                    ... LumosConstants.interestCooldownSecMax)
                .contains(LumosConstants.interestCooldownSecDefault),
            "interest budget outside the register bounds")
        self.store = store
    }

    /// Whether one more request may be sent.
    public func allowed(nowMillis: Int64) throws -> Bool {
        try load(nowMillis: nowMillis).count
            < LumosConstants.interestRateLimitPerHourDefault
    }

    /// Notes a sent request, written through before it counts.
    public func record(nowMillis: Int64) throws {
        var sent = try load(nowMillis: nowMillis)
        sent.append(nowMillis)
        let payload = Self.versionLine + "\n"
            + sent.map(String.init).joined(separator: "\n") + "\n"
        try store.put(kind: .interestBudget, id: Self.budgetID,
                      plaintext: Data(payload.utf8))
    }

    /// How long the respectful cooldown message should still hold: zero when
    /// sending is allowed, otherwise the longer of the INTEREST_COOLDOWN_SEC
    /// floor and the time until a slot actually frees.
    public func cooldownRemainingMillis(nowMillis: Int64) throws -> Int64 {
        let sent = try load(nowMillis: nowMillis)
        guard sent.count >= LumosConstants.interestRateLimitPerHourDefault,
              let last = sent.last, let first = sent.first else { return 0 }
        let floor = last + Int64(LumosConstants.interestCooldownSecDefault) * 1000 - nowMillis
        let slot = first + Self.windowMillis - nowMillis
        return max(max(floor, slot), 0)
    }

    private func load(nowMillis: Int64) throws -> [Int64] {
        guard let raw = try store.get(kind: .interestBudget, id: Self.budgetID),
              let text = String(bytes: raw, encoding: .utf8) else { return [] }
        let lines = text.split(separator: "\n", omittingEmptySubsequences: true)
        guard lines.first.map(String.init) == Self.versionLine else { return [] }
        return lines.dropFirst().compactMap { Int64($0) }
            .filter { nowMillis - $0 < Self.windowMillis }
    }
}

/// Persists per-peer interest state and the matches it produces.
public final class InterestLedger {

    private static let interestVersion = "lumos-interest/1"
    private static let matchVersion = "lumos-match/1"

    /// One applied event's outcome.
    public struct Outcome: Sendable {
        /// Where the relationship now stands.
        public let state: MatchMachine.State
        /// The one instruction for the caller.
        public let effect: MatchMachine.Effect
        /// The derived identifier, on an establishing or unmatching step.
        public let matchIDHex: String?
        /// Non-zero when the rate limit refused the event.
        public let cooldownRemainingMillis: Int64

        /// Whether the event was refused by the rate limit.
        public var rateLimited: Bool { cooldownRemainingMillis > 0 }
    }

    private let store: LocalStore
    private let budget: PersistedBudget
    private let ownPeerID: Data

    /// Binds the ledger.
    public init(store: LocalStore, budget: PersistedBudget, ownPeerID: Data) {
        self.store = store
        self.budget = budget
        self.ownPeerID = ownPeerID
    }

    /// The peer's current state; absence is none.
    public func stateOf(peerKey: String) throws -> MatchMachine.State {
        let rows = try store.findByNaturalKey(kind: .interestRecord,
                                              naturalKey: Self.naturalKey(peerKey))
        guard let raw = rows.first,
              let text = String(bytes: raw, encoding: .utf8) else { return .none }
        let lines = text.split(separator: "\n", omittingEmptySubsequences: false)
        guard lines.count >= 2, String(lines[0]) == Self.interestVersion,
              let state = MatchMachine.State(rawValue: String(lines[1])) else { return .none }
        return state
    }

    /// Applies one event to one peer, persisting whatever changed.
    public func apply(peerKey: String, event: MatchMachine.Event,
                      nowMillis: Int64) throws -> Outcome {
        let before = try stateOf(peerKey: peerKey)

        // The budget gates the EVENT, not the transition: a rate-limited
        // request never reaches the machine.
        if event == .localRequest,
           MatchMachine.step(before, event).effect == .sendRequest,
           try !budget.allowed(nowMillis: nowMillis) {
            return Outcome(state: before, effect: .none, matchIDHex: nil,
                           cooldownRemainingMillis:
                            try budget.cooldownRemainingMillis(nowMillis: nowMillis))
        }

        let result = MatchMachine.step(before, event)
        if result.state != before {
            try persistState(peerKey: peerKey, state: result.state, nowMillis: nowMillis)
        }
        if result.effect == .sendRequest {
            try budget.record(nowMillis: nowMillis)
        }

        var matchIDHex: String?
        if result.effect.establishes {
            matchIDHex = Hex.encode(try MatchID.derive(ownPeerID, Self.naturalKey(peerKey)))
            try persistMatch(peerKey: peerKey, matchIDHex: matchIDHex ?? "",
                             state: "matched", nowMillis: nowMillis)
        }
        if result.state == .unmatched, before == .matched {
            // The row survives with its state flipped (SRS-MAT-010).
            matchIDHex = Hex.encode(try MatchID.derive(ownPeerID, Self.naturalKey(peerKey)))
            try persistMatch(peerKey: peerKey, matchIDHex: matchIDHex ?? "",
                             state: "unmatched", nowMillis: nowMillis)
        }
        return Outcome(state: result.state, effect: result.effect,
                       matchIDHex: matchIDHex, cooldownRemainingMillis: 0)
    }

    /// The stored match record for a peer, if any.
    public func matchRecordOf(peerKey: String) throws -> String? {
        let rows = try store.findByNaturalKey(kind: .matchRecord,
                                              naturalKey: Self.naturalKey(peerKey))
        guard let raw = rows.first else { return nil }
        return String(bytes: raw, encoding: .utf8)
    }

    /// The canonical peer-list natural key, shared with the nearby lists.
    static func naturalKey(_ peerKeyHex: String) -> Data {
        HiddenPeers.naturalKey(peerKeyHex)
    }

    private func persistState(peerKey: String, state: MatchMachine.State,
                              nowMillis: Int64) throws {
        let payload = Self.interestVersion + "\n" + state.rawValue + "\n\(nowMillis)\n"
        try store.putByNaturalKey(kind: .interestRecord, plaintext: Data(payload.utf8),
                                  naturalKey: Self.naturalKey(peerKey))
    }

    private func persistMatch(peerKey: String, matchIDHex: String, state: String,
                              nowMillis: Int64) throws {
        let payload = Self.matchVersion + "\n" + matchIDHex + "\n" + state + "\n\(nowMillis)\n"
        try store.putByNaturalKey(kind: .matchRecord, plaintext: Data(payload.utf8),
                                  naturalKey: Self.naturalKey(peerKey))
    }
}
