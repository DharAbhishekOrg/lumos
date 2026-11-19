/// Lumos - ios-app/shell / RSSI-sorted candidate triage, Apple side.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-ENV-001, TRS-ENV-002, TRS-PROTO-017, TRS-PROTO-018
/// ADRs: ADR-002
/// Threading: serial-executor
/// Notes: Semantics identical to the Android triage by construction, because
/// a venue full of mixed phones is one radio environment and the two sides
/// must spend their connection budgets the same way: structurally valid
/// Android hints keyed on their rotating EID, hintless sightings (every iOS
/// peer, degraded Android peers) keyed on their link token, RSSI-banded,
/// aged out on the register bound, connect candidates never exceeding
/// MAX_CONCURRENT_GATT_LINKS minus active. A candidate is NOT presence
/// (TRS-ENV-002): nothing here may render a nearby card - presence exists
/// only after an authenticated HELLO, WP-03 S4's layer. The cheap discard is
/// Adv.parse, structure only (TRS-PROTO-017).

import Foundation
import LumosProtocol
import LumosProtocolGenerated

/// Ingests sightings, maintains candidates, enforces the connect budget.
public final class DiscoveryTriage {

    /// The register RSSI bands (RSSI_BANDS_DBM).
    public enum RssiBand: Sendable {
        /// rssi >= very_close_gte.
        case veryClose
        /// rssi >= nearby_gte.
        case nearby
        /// everything weaker.
        case far
    }

    /// One connect-worthy sighting, keyed and banded.
    public struct Candidate: Sendable {
        /// The triage key: EID hex for hinted peers, link token otherwise.
        public let key: String
        /// The transport's opaque connect handle.
        public let linkToken: String
        /// Whether this candidate carried a structurally valid adv_v1 hint.
        public let hinted: Bool
        /// The strongest recent signal strength.
        public let rssiDbm: Int
        /// When this candidate was last sighted.
        public let lastSeenMillis: Int64

        /// The register band for the current signal strength.
        public var band: RssiBand {
            if rssiDbm >= LumosConstants.rssiBandsDBMVeryCloseGte { return .veryClose }
            if rssiDbm >= LumosConstants.rssiBandsDBMNearbyGte { return .nearby }
            return .far
        }
    }

    private var table: [String: Candidate] = [:]
    private var discarded = 0

    /// Creates an empty triage table.
    public init() {}

    /// How many sightings the structural cheap discard rejected.
    public var structurallyDiscarded: Int { discarded }

    /// How many candidates are currently live.
    public var count: Int { table.count }

    /// Ingests one sighting: cheap structural discard, then candidate upsert.
    /// - Parameter sighting: the sighting
    /// - Returns: the candidate it became, or nil when the hint was discarded
    @discardableResult
    public func ingest(_ sighting: BleSighting) -> Candidate? {
        var key = sighting.linkToken
        var hinted = false
        if let serviceData = sighting.serviceData {
            guard let payload = try? Adv.parse(serviceData) else {
                // Pre-connection noise rejection (ADR-002): not Lumos-shaped,
                // silently dropped, counted so the synthetic harness can
                // assert the discard path actually ran.
                discarded += 1
                return nil
            }
            key = payload.eid.map { String(format: "%02x", $0) }.joined()
            hinted = true
        }
        let rssi = max(table[key]?.rssiDbm ?? Int.min, sighting.rssiDbm)
        let merged = Candidate(key: key, linkToken: sighting.linkToken, hinted: hinted,
                               rssiDbm: rssi, lastSeenMillis: sighting.atMillis)
        table[key] = merged
        return merged
    }

    /// Drops candidates not sighted within the register age-out bound.
    /// - Parameter nowMillis: the caller's clock
    /// - Returns: how many candidates aged out
    @discardableResult
    public func prune(nowMillis: Int64) -> Int {
        let bound = Int64(LumosConstants.presenceAgeoutSecDefault) * 1000
        let before = table.count
        table = table.filter { nowMillis - $0.value.lastSeenMillis <= bound }
        return before - table.count
    }

    /// Every live candidate, strongest signal first.
    /// - Returns: the sorted candidates
    public func candidates() -> [Candidate] {
        table.values.sorted { $0.rssiDbm > $1.rssiDbm }
    }

    /// The candidates worth a connection right now: RSSI order, never more
    /// than the register link budget leaves free (TRS-ENV-001).
    /// - Parameter activeLinks: how many links are already established
    /// - Returns: the next candidates to connect, possibly empty
    public func nextConnects(activeLinks: Int) -> [Candidate] {
        let budget = max(0, LumosConstants.maxConcurrentGATTLinksDefault - activeLinks)
        return Array(candidates().prefix(budget))
    }

    /// Takes a candidate out of rotation - the connector calls this the
    /// moment it spends a link on one, so the next wave is the next strongest
    /// peers rather than the same few forever. A later sighting makes the
    /// peer a candidate again; whether it deserves a SECOND connection is the
    /// connector's question to answer against the presence table.
    /// - Parameter key: the candidate's triage key
    /// - Returns: whether a candidate was removed
    @discardableResult
    public func remove(key: String) -> Bool {
        table.removeValue(forKey: key) != nil
    }
}
