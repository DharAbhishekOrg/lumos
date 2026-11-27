/// Lumos - LumosAppShell / Sightings to cards: triage, budgeted links, both HELLOs, presence.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-ENV-001, TRS-ENV-002, TRS-PROTO-017, SRS-NBY-005, SRS-NBY-006, SRS-NBY-007, SRS-NBY-017,
///             SRS-SAF-002
/// ADRs: ADR-002, ADR-013
/// Threading: serial-executor (one queue drives every call)
/// Notes: The Swift twin of the Android DiscoveryEngine: sightings go into
/// DiscoveryTriage; each tick spends the connect budget
/// (MAX_CONCURRENT_GATT_LINKS, any state, either role) on the strongest
/// candidates, keeping one slot for links others open to us (without it a
/// room of phones all connecting at once refuses each other); each link runs
/// a LinkSession; a DONE link's Peer goes into PresenceTable (the only way a
/// card exists); finished and failed links close at once; a peer on a fresh
/// card is not connected again under the same key; stop() closes every link
/// and starts nothing. The radio is a seam - CoreBluetooth central and
/// peripheral adapters once CR-010 names the characteristics, an in-memory
/// air in tests. Link handles never leave this class.

import Foundation
import LumosProtocol
import LumosProtocolGenerated

/// The radio, as discovery uses it.
public protocol DiscoveryRadio: AnyObject {
    /// Connects out to a sighted peer as the central; the adapter later
    /// reports ready, frames and closure under the same handle.
    func connect(handle: String, linkToken: String)
    /// Sends one whole frame on a link (the adapter fragments it).
    func send(handle: String, frame: Data)
    /// Closes a link. Idempotent.
    func disconnect(handle: String)
}

/// One device's discovery.
public final class DiscoveryEngine {

    /// Links kept free for peers that connect to us.
    static let inboundReserve = 1

    private let radio: DiscoveryRadio
    private let freshExchange: () -> HelloExchange
    private let nonces: () -> Data
    private let handshakeTimeoutMillis: Int64
    private let triage = DiscoveryTriage()
    private let presence: PresenceTable
    private var links: [String: LinkSession] = [:]
    private var order: [String] = []
    private var peerByCandidate: [String: String] = [:]
    private var candidateByLink: [String: String] = [:]
    private var refusedUntil: [String: Int64] = [:]
    private let refuses: (String) -> Bool
    private var nextHandle = 0
    /// True between start and stop.
    public private(set) var running = false

    /// - Parameters:
    ///   - radio: the radio seam
    ///   - freshExchange: this device's identity, as a fresh exchange per link
    ///   - nonces: fresh HELLO nonces
    ///   - handshakeTimeoutMillis: how long a link may take (CR-010's register value on a device)
    ///   - presence: the presence table the Nearby feed reads, so the feed's
    ///     vetoes (hidden, blocked, interest) apply to what discovery finds;
    ///     the engine is still its only writer
    ///   - refuses: identities never answered or recorded - the block list
    ///     (SRS-SAF-002); a refused sighting is not dialled again until the
    ///     presence age-out bound passes
    public init(radio: DiscoveryRadio, freshExchange: @escaping () -> HelloExchange,
                nonces: @escaping () -> Data, handshakeTimeoutMillis: Int64,
                presence: PresenceTable = PresenceTable(),
                refuses: @escaping (String) -> Bool = { _ in false }) {
        self.refuses = refuses
        self.radio = radio
        self.freshExchange = freshExchange
        self.nonces = nonces
        self.handshakeTimeoutMillis = handshakeTimeoutMillis
        self.presence = presence
    }

    /// The links open now, in either role.
    public var activeLinks: Int { links.count }

    /// The authenticated cards.
    public var cards: [PresenceTable.Card] { presence.all() }

    /// Starts discovery.
    public func start() {
        running = true
    }

    /// The one-action stop (SRS-NBY-017).
    public func stop() {
        running = false
        for handle in order where links[handle] != nil {
            radio.disconnect(handle: handle)
        }
        links.removeAll()
        order.removeAll()
        candidateByLink.removeAll()
        refusedUntil.removeAll()
        for candidate in triage.candidates() {
            _ = triage.remove(key: candidate.key)
        }
    }

    /// The scanner saw an advertiser.
    public func onSighting(_ sighting: BleSighting) {
        if running {
            _ = triage.ingest(sighting)
        }
    }

    /// Advances time: ends overdue links, ages out, spends the free budget.
    public func tick(nowMillis: Int64) {
        for handle in order {
            guard let link = links[handle] else { continue }
            link.tick(nowMillis: nowMillis)
            settle(handle, link, nowMillis)
        }
        _ = triage.prune(nowMillis: nowMillis)
        _ = presence.prune(nowMillis: nowMillis)
        refusedUntil = refusedUntil.filter { $0.value > nowMillis }
        guard running else { return }
        // A peer already on a card is dropped from rotation without spending
        // budget; otherwise the same strongest few, all known, would fill
        // every wave and starve the peers still to be found.
        var budget = LumosConstants.maxConcurrentGATTLinksDefault - links.count - Self.inboundReserve
        for candidate in triage.candidates() where budget > 0 {
            _ = triage.remove(key: candidate.key)
            if alreadyPresent(candidate.key) || refusedUntil[candidate.key] != nil {
                continue
            }
            budget -= 1
            nextHandle += 1
            let handle = "c\(nextHandle)"
            add(handle, session(.central, handle))
            candidateByLink[handle] = candidate.key
            radio.connect(handle: handle, linkToken: candidate.linkToken)
        }
    }

    private func alreadyPresent(_ candidateKey: String) -> Bool {
        guard let peerKey = peerByCandidate[candidateKey] else { return false }
        if presence.all().contains(where: { $0.peerKey == peerKey }) {
            return true
        }
        peerByCandidate[candidateKey] = nil
        return false
    }

    private func session(_ role: LinkSession.Role, _ handle: String) -> LinkSession {
        LinkSession(role: role, exchange: freshExchange(),
                    outbox: { [weak radio] frame in radio?.send(handle: handle, frame: frame) },
                    nonces: nonces, timeoutMillis: handshakeTimeoutMillis, refuses: refuses)
    }

    private func add(_ handle: String, _ link: LinkSession) {
        links[handle] = link
        order.append(handle)
    }

    /// A central link is connected and subscribed: say HELLO.
    public func onLinkReady(handle: String, nowMillis: Int64) {
        guard let link = links[handle] else { return }
        link.open(nowMillis: nowMillis)
        settle(handle, link, nowMillis)
    }

    /// A central subscribed to our LINK_OUT: a peripheral link opens.
    public func onCentralSubscribed(handle: String, nowMillis: Int64) {
        guard running, links[handle] == nil,
              links.count < LumosConstants.maxConcurrentGATTLinksDefault else {
            radio.disconnect(handle: handle)
            return
        }
        let link = session(.peripheral, handle)
        add(handle, link)
        link.open(nowMillis: nowMillis)
        settle(handle, link, nowMillis)
    }

    /// A whole frame arrived on a link.
    public func onFrame(handle: String, frame: Data, nowMillis: Int64) {
        guard let link = links[handle] else { return }
        link.onFrame(frame, nowMillis: nowMillis)
        settle(handle, link, nowMillis)
    }

    /// The link dropped.
    public func onLinkClosed(handle: String) {
        forget(handle)
    }

    private func settle(_ handle: String, _ link: LinkSession, _ nowMillis: Int64) {
        if let peer = link.peer {
            let card = presence.record(peer, nowMillis: nowMillis)
            if let candidate = candidateByLink[handle] {
                peerByCandidate[candidate] = card.peerKey
            }
            close(handle)
        } else if link.state == .failed {
            if link.refused, let candidate = candidateByLink[handle] {
                refusedUntil[candidate] = nowMillis + Int64(LumosConstants.presenceAgeoutSecDefault) * 1000
            }
            close(handle)
        }
    }

    private func close(_ handle: String) {
        forget(handle)
        radio.disconnect(handle: handle)
    }

    private func forget(_ handle: String) {
        links[handle] = nil
        order.removeAll { $0 == handle }
        candidateByLink[handle] = nil
    }
}
