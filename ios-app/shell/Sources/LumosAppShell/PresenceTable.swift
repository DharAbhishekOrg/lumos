/// Lumos - ios-app/shell / Presence: authenticated peers, and nothing else.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-ENV-002, SRS-NBY-006, SRS-NBY-007, SRS-IDN-002, TRS-PROTO-017
/// ADRs: ADR-002, ADR-008
/// Threading: serial-executor
/// Notes: The Apple half of the zero-false-positive rule, enforced the same
/// way: the ONLY way into this table is a HelloExchange.Peer, and the only
/// way to hold one is a verified HELLO_ACK to one's own fresh HELLO. Cards
/// key on the SRS-IDN-002 identifier, so one rotating peer stays one card
/// however many EIDs it burns (SRS-NBY-006), and stale cards age out on the
/// register bound (SRS-NBY-007). The raw identity key and the link token
/// stay out of the card (SRS-NBY-008's data half).

import Foundation
import LumosProtocol
import LumosProtocolGenerated

/// Authenticated nearby presence (TRS-ENV-002).
public final class PresenceTable {

    /// One present peer, as the Nearby surfaces may know it.
    public struct Card: Sendable {
        /// The hex SRS-IDN-002 identifier - the dedup key, not UI copy.
        public let peerKey: String
        /// The authenticated capability bits.
        public let capabilityBits: Int
        /// The authenticated venue hint.
        public let venueHint: Int64
        /// When the peer last completed an authenticated HELLO.
        public let confirmedAtMillis: Int64
    }

    private var cards: [String: Card] = [:]

    /// Creates an empty table.
    public init() {}

    /// How many peers are currently present.
    public var count: Int { cards.count }

    /// Records a completed, authenticated exchange. The parameter type is the
    /// requirement: nothing but a HelloExchange.Peer can ever become presence.
    /// - Parameters:
    ///   - peer: the authenticated peer
    ///   - nowMillis: the caller's clock
    /// - Returns: the card, new or refreshed
    @discardableResult
    public func record(_ peer: HelloExchange.Peer, nowMillis: Int64) -> Card {
        let key = Self.keyFor(peerId: peer.id)
        let card = Card(peerKey: key,
                        capabilityBits: peer.fields.capabilityBits,
                        venueHint: peer.fields.venueHint,
                        confirmedAtMillis: nowMillis)
        cards[key] = card
        return card
    }

    /// The card key for a peer identifier - the one hidden and blocked lists
    /// are keyed by, so a refusal can be decided before a card exists.
    /// - Parameter peerId: the peer identifier (Hello.peerId of its signing key)
    /// - Returns: the key, hex
    public static func keyFor(peerId: Data) -> String {
        peerId.map { String(format: "%02x", $0) }.joined()
    }

    /// Drops cards not re-confirmed within the register age-out bound
    /// (SRS-NBY-007; the TRS-ENV-002 stale bound).
    /// - Parameter nowMillis: the caller's clock
    /// - Returns: how many cards aged out
    @discardableResult
    public func prune(nowMillis: Int64) -> Int {
        let bound = Int64(LumosConstants.presenceAgeoutSecDefault) * 1000
        let before = cards.count
        cards = cards.filter { nowMillis - $0.value.confirmedAtMillis <= bound }
        return before - cards.count
    }

    /// Forgets every card at once (discovery stopped: nothing stale may linger).
    public func clear() {
        cards.removeAll()
    }

    /// Every live card, most recently confirmed first.
    /// - Returns: the sorted cards
    public func all() -> [Card] {
        cards.values.sorted { $0.confirmedAtMillis > $1.confirmedAtMillis }
    }
}
