/// Lumos - ios-app/shell / Live discovery for the app: scanner, links, engine and feed, on their queues.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-NBY-003, SRS-NBY-005, SRS-NBY-010, SRS-NBY-012, SRS-NBY-013, SRS-NBY-017, SRS-SAF-002
/// ADRs: ADR-002, ADR-013
/// Threading: start, stop, state and hideCard from the app's (main) side; the engine runs on the
///            serial link queue and the feed on the store's serial queue
/// Notes: The Swift twin of the Android NearbyDiscovery (NB-1 step 3c), with
/// the owner's ruling (2026-11-28): discovery runs exactly while Visible
/// Nearby's radio runs, so the person looks for and connects to others only
/// while they can be seen themselves and the app is open. Connecting sends
/// the person's HELLO, and a HELLO is what puts a card on someone else's
/// screen.
///
/// Two queues, each owning its own state: the link queue runs the scanner's
/// sightings, the radio and the DiscoveryEngine (with its own "already found"
/// table); the store's queue runs the feed, with its own presence table fed
/// by every peer a link verifies, through NearbyFeedController's hidden and
/// blocked vetoes. The block list is the engine's refusals (SRS-SAF-002): each
/// HELLO's key is checked on the store's queue with a bounded wait, and
/// anything but a clear "not blocked" refuses. A new feed controller is made
/// at every start, so a block placed elsewhere always applies. Stopping
/// forgets every card and closes every link at once. The class is
/// @unchecked Sendable by queue confinement: the main-side flags are behind
/// a lock, everything else lives on exactly one of the two queues.

import Foundation
import LumosProtocol
import LumosProtocolGenerated

/// A discovery radio with a server side and a lifecycle.
public protocol LinkRadio: DiscoveryRadio {
    /// Binds the engine the links report to; called on the link queue.
    func bind(_ engine: DiscoveryEngine)
    /// Opens the server side so peers can connect here; false when the radio cannot.
    func openServer() -> Bool
    /// Closes the server side and every link.
    func close()
}

/// The app's live discovery.
public final class NearbyDiscovery: @unchecked Sendable {

    /// How often links are ticked and the feed is refreshed.
    static let tickMillis = 1_000
    /// How long a HELLO waits for the block-list answer before it is refused.
    static let refusalWaitSeconds = 2

    private let link: LinkRadio
    private let scanner: BleScanning
    private let store: LocalStore
    private let linkQueue: DispatchQueue
    private let storage: DispatchQueue
    private let clock: @Sendable () -> Int64
    private let changed: @Sendable () -> Void
    private let blocked: BlockedPeers
    private var engine: DiscoveryEngine!

    // Store-queue state.
    private let feedPresence = PresenceTable()
    private var feed: NearbyFeedController?

    // Main-side state, behind the lock.
    private let lock = NSLock()
    private var runningFlag = false
    private var latestState = FeedState.notVisible
    private var timer: DispatchSourceTimer?

    /// - Parameters:
    ///   - link: the radio (CoreBluetooth on a device)
    ///   - scanner: the advertisement scanner
    ///   - identity: this install's HELLO identity
    ///   - store: the open store (hidden and blocked lists, the metric)
    ///   - linkQueue: the serial queue the radio and engine run on
    ///   - storage: the store's serial queue
    ///   - clock: the millisecond clock
    ///   - changed: called after the feed state changes, on the store's queue
    public init(link: LinkRadio, scanner: BleScanning, identity: NearbyIdentity, store: LocalStore,
                linkQueue: DispatchQueue, storage: DispatchQueue,
                clock: @escaping @Sendable () -> Int64, changed: @escaping @Sendable () -> Void) {
        self.link = link
        self.scanner = scanner
        self.store = store
        self.linkQueue = linkQueue
        self.storage = storage
        self.clock = clock
        self.changed = changed
        self.blocked = BlockedPeers(store: store)
        self.engine = DiscoveryEngine(
            radio: link, freshExchange: identity.freshExchange, nonces: Self.nonce,
            handshakeTimeoutMillis: Int64(LumosConstants.helloHandshakeTimeoutSecDefault) * 1000,
            presence: PresenceTable(), refuses: { [unowned self] key in self.refuses(key) })
        engine.onFound = { [unowned self] peer, now in
            self.storage.async {
                _ = self.feedPresence.record(peer, nowMillis: now)
                self.publish()
            }
        }
        linkQueue.async { self.link.bind(self.engine) }
    }

    /// True while discovery runs.
    public var running: Bool {
        lock.lock()
        defer { lock.unlock() }
        return runningFlag
    }

    /// What the Nearby screen shows now.
    public var state: FeedState {
        lock.lock()
        defer { lock.unlock() }
        return runningFlag ? latestState : .notVisible
    }

    /// Starts looking, connecting and answering. Idempotent.
    public func start() {
        lock.lock()
        if runningFlag {
            lock.unlock()
            return
        }
        runningFlag = true
        latestState = .scanningEmpty
        lock.unlock()
        storage.async {
            self.feedPresence.clear()
            let feed = NearbyFeedController(presence: self.feedPresence, hidden: HiddenPeers(store: self.store),
                                            blocked: self.blocked, ladder: RescanLadder(),
                                            metric: DiscoverySuccessMetric(store: self.store))
            feed.viewStarted()
            feed.visibilityChanged(visibleNearby: true)
            self.feed = feed
            self.publish()
        }
        linkQueue.async {
            _ = self.link.openServer()
            self.engine.start()
            self.scanner.start(onSighting: { sighting in
                self.linkQueue.async { self.engine.onSighting(sighting) }
            }, onFailure: { failure in
                self.storage.async {
                    self.feed?.scanFailed(failure)
                    self.publish()
                }
            })
        }
        let ticker = DispatchSource.makeTimerSource(queue: linkQueue)
        ticker.schedule(deadline: .now() + .milliseconds(Self.tickMillis), repeating: .milliseconds(Self.tickMillis))
        ticker.setEventHandler {
            self.engine.tick(nowMillis: self.clock())
            self.storage.async { self.publish() }
        }
        lock.lock()
        timer = ticker
        lock.unlock()
        ticker.resume()
    }

    /// Stops everything at once: scanning, every link, every card. Idempotent.
    public func stop() {
        lock.lock()
        if !runningFlag {
            lock.unlock()
            return
        }
        runningFlag = false
        latestState = .notVisible
        let ticker = timer
        timer = nil
        lock.unlock()
        ticker?.cancel()
        linkQueue.async {
            self.scanner.stop()
            self.engine.stop()
            self.link.close()
        }
        storage.async {
            try? self.feed?.viewEnded(nowMillis: self.clock())
            self.feed = nil
            self.feedPresence.clear()
            self.changed()
        }
    }

    /// Hides one card, silently (SRS-NBY-012).
    /// - Parameter peerKey: the card's identifier
    public func hideCard(peerKey: String) {
        storage.async {
            try? self.feed?.hideCard(peerKey: peerKey, atUtcMillis: self.clock())
            self.publish()
        }
    }

    /// Store queue: recompute and hand the state to the screen.
    private func publish() {
        guard running, let feed else { return }
        // A veto that cannot be read shows nobody rather than someone hidden.
        let next = (try? feed.state(nowMillis: clock())) ?? .scanningEmpty
        lock.lock()
        if runningFlag {
            latestState = next
        }
        lock.unlock()
        changed()
    }

    /// Link queue: the block list, read where the store lives, failing closed.
    private func refuses(_ peerKey: String) -> Bool {
        let answer = Answer()
        let done = DispatchSemaphore(value: 0)
        storage.async {
            answer.set((try? self.blocked.isBlocked(peerKey: peerKey)) ?? true)
            done.signal()
        }
        if done.wait(timeout: .now() + .seconds(Self.refusalWaitSeconds)) == .timedOut {
            return true
        }
        return answer.value
    }

    private static func nonce() -> Data {
        Data((0..<Hello.nonceLen).map { _ in UInt8.random(in: 0...255) })
    }

    /// One answer handed from the store's queue to the link queue.
    private final class Answer: @unchecked Sendable {
        private let lock = NSLock()
        private var refused = true

        var value: Bool {
            lock.lock()
            defer { lock.unlock() }
            return refused
        }

        func set(_ refused: Bool) {
            lock.lock()
            self.refused = refused
            lock.unlock()
        }
    }
}
