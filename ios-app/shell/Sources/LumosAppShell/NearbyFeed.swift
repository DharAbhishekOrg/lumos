/// Lumos - ios-app/shell / The Nearby feed machines, the Android ones mirrored.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-NBY-010, SRS-NBY-011, SRS-NBY-012, SRS-NBY-013, SRS-NBY-014,
///             SRS-NBY-015, TRS-ENV-003, SRS-NFR-PERF-009
/// ADRs: ADR-002, ADR-003
/// Threading: serial-queue
/// Notes: Decision for decision the Java package: the typed CoreBluetooth
/// states fold to the four SRS-NBY-010 situations; EMPTY is a first-class
/// state; cards are presence after the three vetoes - hidden (silent,
/// identity-keyed, persisted in the blind-indexed hidden_peer table under
/// the canonical raw-id natural key), blocked (never reappears under
/// rotation because the key is identity), and the local opt-in interest
/// mask over AUTHENTICATED capability bits; the view lifecycle arms the
/// TRS-ENV-003 ladder and closes each session into exactly one metric
/// record; and a per-identity veto cache keeps a feed refresh off the blind
/// index, which is what the SRS-NFR-PERF-009 bound rests on.

import Foundation

/// One renderable state of the Nearby feed.
public enum FeedState {
    /// Bluetooth is off: the user can turn it on.
    case radioOff
    /// Bluetooth authorization is missing: the user can grant it.
    case permissionNeeded
    /// The stack refused in a way the user cannot fix here.
    case radioUnavailable
    /// The stack is resetting or saturated; it passes.
    case radioBusy
    /// Scanning runs and nothing has authenticated yet: skeletons.
    case scanningEmpty
    /// Authenticated, filtered presence cards, newest confirmation first.
    case cards([PresenceTable.Card])

    /// Folds a typed scan failure to the state the user should see.
    /// - Parameter failure: the typed CoreBluetooth state
    /// - Returns: the feed state
    public static func of(_ failure: BleScanFailure) -> FeedState {
        switch failure {
        case .poweredOff: return .radioOff
        case .unauthorized: return .permissionNeeded
        case .resetting: return .radioBusy
        case .unsupported, .undocumented: return .radioUnavailable
        }
    }

    /// The cards, empty except in `.cards`.
    public var cardList: [PresenceTable.Card] {
        if case .cards(let list) = self { return list }
        return []
    }
}

/// Peers the user hid from the Nearby feed (SRS-NBY-012).
public final class HiddenPeers {

    private static let versionLine = "lumos-hidden-peer/1"

    private let store: LocalStore

    /// Binds the list to its store.
    public init(store: LocalStore) {
        self.store = store
    }

    /// Hides a peer, silently; idempotent by effect.
    /// - Parameters:
    ///   - peerKey: the authenticated peer identifier, hex
    ///   - atUtcMillis: when the user chose it
    public func hide(peerKey: String, atUtcMillis: Int64) throws {
        let payload = Self.versionLine + "\n\(atUtcMillis)\n"
        try store.put(kind: .hiddenPeer, plaintext: Data(payload.utf8),
                      naturalKey: Self.naturalKey(peerKey))
    }

    /// Whether a peer is hidden.
    /// - Parameter peerKey: the authenticated peer identifier, hex
    public func isHidden(peerKey: String) throws -> Bool {
        try !store.findByNaturalKey(kind: .hiddenPeer,
                                    naturalKey: Self.naturalKey(peerKey)).isEmpty
    }

    /// The canonical peer-list natural key: the raw identifier bytes - the
    /// definition the Android side fixes and both platforms share.
    /// - Parameter peerKeyHex: the identifier as a presence card spells it
    /// - Returns: the raw bytes the blind index is computed over
    public static func naturalKey(_ peerKeyHex: String) -> Data {
        precondition(peerKeyHex.count % 2 == 0, "peer key hex has odd length")
        var raw = Data(capacity: peerKeyHex.count / 2)
        var index = peerKeyHex.startIndex
        while index < peerKeyHex.endIndex {
            let next = peerKeyHex.index(index, offsetBy: 2)
            raw.append(UInt8(peerKeyHex[index..<next], radix: 16) ?? 0)
            index = next
        }
        return raw
    }
}

/// The block list: placed from a conversation's safety actions, read by the
/// feed (SRS-NBY-013, SRS-SAF-002). There is no unblock until the settings
/// slice gives the person a reviewed way to do it.
public final class BlockedPeers {

    private static let versionLine = "lumos-blocked-peer/1"

    private let store: LocalStore

    /// Binds the reader to its store.
    public init(store: LocalStore) {
        self.store = store
    }

    /// Blocks a peer; a second block of the same identity writes nothing.
    /// - Parameters:
    ///   - peerKey: the authenticated peer identifier, hex
    ///   - atUtcMillis: when the user chose it
    public func block(peerKey: String, atUtcMillis: Int64) throws {
        guard try !isBlocked(peerKey: peerKey) else { return }
        let payload = Self.versionLine + "\n\(atUtcMillis)\n"
        try store.put(kind: .blockEntry, plaintext: Data(payload.utf8), naturalKey: HiddenPeers.naturalKey(peerKey))
    }

    /// Whether a peer is blocked.
    /// - Parameter peerKey: the authenticated peer identifier, hex
    public func isBlocked(peerKey: String) throws -> Bool {
        try !store.findByNaturalKey(kind: .blockEntry,
                                    naturalKey: HiddenPeers.naturalKey(peerKey)).isEmpty
    }
}

/// Turns presence, vetoes and radio states into one renderable value.
public final class NearbyFeedController {

    /// An interest mask accepting every peer; filtering is opt-in.
    public static let acceptAll: Int = ~0

    private let presence: PresenceTable
    private let hidden: HiddenPeers
    private let blocked: BlockedPeers
    private let ladder: RescanLadder
    private let metric: DiscoverySuccessMetric

    private var vetoCache: [String: Bool] = [:]
    private var failure: BleScanFailure?
    private var interestMask = NearbyFeedController.acceptAll
    private var viewing = false

    /// Binds the controller to its collaborators.
    public init(presence: PresenceTable, hidden: HiddenPeers, blocked: BlockedPeers,
                ladder: RescanLadder, metric: DiscoverySuccessMetric) {
        self.presence = presence
        self.hidden = hidden
        self.blocked = blocked
        self.ladder = ladder
        self.metric = metric
    }

    /// The user opened Nearby: the ladder arms with the view.
    public func viewStarted() {
        viewing = true
        ladder.nearbyViewStarted()
    }

    /// The user left Nearby: the ladder disarms and the session closes into
    /// one privacy-safe metric record.
    /// - Parameter nowMillis: the caller's clock, for the closing count
    public func viewEnded(nowMillis: Int64) throws {
        guard viewing else { return }
        viewing = false
        let present = try state(nowMillis: nowMillis).cardList.count
        try metric.record(cyclesRun: ladder.cyclesRun,
                          maxStepReached: ladder.maxStepReached, peersPresent: present)
        ladder.nearbyViewEnded()
    }

    /// Visible Nearby changed; the ladder follows.
    public func visibilityChanged(visibleNearby: Bool) {
        ladder.visibilityChanged(visibleNearby: visibleNearby)
    }

    /// A scan cycle finished.
    public func scanCycleCompleted(sightingsInCycle: Int) {
        ladder.cycleCompleted(sightingsInCycle: sightingsInCycle)
    }

    /// Scanning failed in a typed way; the feed shows the situation.
    public func scanFailed(_ scanFailure: BleScanFailure) {
        failure = scanFailure
    }

    /// Scanning runs again; the trouble state clears.
    public func scanRecovered() {
        failure = nil
    }

    /// Sets the local interest filter (SRS-NBY-011): authenticated bits
    /// only, nothing fetched, nothing leaves the device.
    public func setInterestMask(_ mask: Int) {
        interestMask = mask
    }

    /// What the screen renders right now.
    /// - Parameter nowMillis: the caller's clock, for presence age-out
    public func state(nowMillis: Int64) throws -> FeedState {
        if let failure { return .of(failure) }
        presence.prune(nowMillis: nowMillis)
        var kept: [PresenceTable.Card] = []
        for card in presence.all() {
            if interestMask != Self.acceptAll && (card.capabilityBits & interestMask) == 0 {
                continue
            }
            let vetoed: Bool
            if let cached = vetoCache[card.peerKey] {
                vetoed = cached
            } else {
                vetoed = try hidden.isHidden(peerKey: card.peerKey)
                    || blocked.isBlocked(peerKey: card.peerKey)
                vetoCache[card.peerKey] = vetoed
            }
            if vetoed { continue }
            kept.append(card)
        }
        if kept.isEmpty { return .scanningEmpty }
        kept.sort { $0.confirmedAtMillis > $1.confirmedAtMillis }
        return .cards(kept)
    }

    /// Hides one card, silently (SRS-NBY-012).
    public func hideCard(peerKey: String, atUtcMillis: Int64) throws {
        try hidden.hide(peerKey: peerKey, atUtcMillis: atUtcMillis)
        vetoCache.removeAll()
    }
}
