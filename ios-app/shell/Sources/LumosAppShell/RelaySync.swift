/// Lumos - LumosAppShell / One relay pass: be registered and published, send the outbox, receive and acknowledge.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-RMT-001, SRS-RMT-003, SRS-RMT-004, SRS-RMT-009, SRS-RMT-016, SRS-RMT-017, SRS-RMT-018, TRS-PAT-004
/// ADRs: ADR-001, ADR-003
/// Threading: serial-executor
/// Notes: The Swift port of the Android RelaySync. Every step is ordered so
/// that dying between any two of them loses nothing and shows nothing twice:
/// an outbox item is removed only AFTER the relay took it (a resend is the
/// same id and bytes, which the relay answers as the same success); an
/// envelope is acknowledged only AFTER ChatStore committed it (a refetch is
/// answered by dedup). Everything the store refuses is acknowledged too, so a
/// bad envelope cannot sit in the mailbox being refetched forever
/// (SRS-RMT-009); only a local engine failure leaves one unacknowledged.
/// Order is kept per conversation: once one conversation's item cannot go, its
/// later items wait while other conversations carry on. Offline, rate limited
/// and a refused credential stop the whole pass; a refused credential also
/// drops the registration so the next pass registers, republishes and
/// re-allows every route. Delivered and read never come from here: the relay
/// taking a send is one tick (owner ruling 2026-11-21). Also TRS-TRN-012 and
/// TRS-TST-006, as on Android.

import Foundation
import LumosProtocol
import LumosProtocolGenerated

/// The relay pass.
public final class RelaySync {

    static let maxBatches = 5

    /// Why a pass stopped early.
    public struct Stop: Sendable, Equatable {
        public let kind: RelayError.Kind
        public let code: String
        public let retryAfterSeconds: Int64

        init(_ e: RelayError) {
            kind = e.kind
            code = e.code
            retryAfterSeconds = e.retryAfterSeconds
        }

        /// OFFLINE and BUSY: try again later.
        public var retryable: Bool { kind == .offline || kind == .busy }
    }

    /// What one pass did.
    public struct Report: Sendable, Equatable {
        public var sent = 0
        public var failed = 0
        public var shown = 0
        public var acknowledged = 0
        public var moreWaiting = false
        public var stoppedBy: Stop?

        /// Whether the scheduler should come back for this pass's work.
        public var retryLater: Bool { stoppedBy?.retryable ?? false }
    }

    private let chat: ChatStore
    private let routes: RelayRoutes
    private let account: RelayAccount
    private let prekeys: PrekeyPublisher
    private let anonymous: RelayClient
    private let clock: () -> Int64

    public init(chat: ChatStore, routes: RelayRoutes, account: RelayAccount, prekeys: PrekeyPublisher,
                anonymous: RelayClient, clock: @escaping () -> Int64) {
        self.chat = chat
        self.routes = routes
        self.account = account
        self.prekeys = prekeys
        self.anonymous = anonymous
        self.clock = clock
    }

    /// Runs one pass.
    /// - Throws: only local failures (``StoreError``); relay trouble is in the report
    public func run() throws -> Report {
        var t = Report()
        do {
            let client = try ready()
            try send(client, &t)
            try receive(client, &t)
        } catch let e as RelayError {
            if e.kind == .auth {
                try account.forget()
            }
            t.stoppedBy = Stop(e)
        }
        return t
    }

    /// Marks a match's route ending, revokes its capability at the relay and
    /// forgets the route (unmatch, block). Offline, the ending route is kept and
    /// every later pass finishes the job; meanwhile nothing the peer sends opens.
    /// - Returns: nil when done (or nothing to do); the stop when the relay could not be told yet
    public func forget(matchID: Data) throws -> Stop? {
        guard let route = try routes.forMatch(matchID) else { return nil }
        try routes.markEnding(matchID: matchID)
        do {
            try anonymous.as(credential: try account.ensure(anonymous).credential).revoke(proof: route.proof)
            try routes.remove(matchID: matchID)
            return nil
        } catch let e as RelayError {
            if e.kind == .auth {
                try account.forget()
            }
            return Stop(e)
        }
    }

    private func ready() throws -> RelayClient {
        let reg = try account.ensure(anonymous)
        let client = anonymous.as(credential: reg.credential)
        try prekeys.ensure(client: client, registration: reg)
        for r in try routes.all() {
            if r.ending {
                try client.revoke(proof: r.proof)
                try routes.remove(matchID: r.matchID)
            } else if r.allowedFor != reg.routingID {
                try client.allow(proof: r.proof)
                try routes.markAllowed(matchID: r.matchID, routingID: reg.routingID)
            }
        }
        return client
    }

    private func send(_ client: RelayClient, _ t: inout Report) throws {
        var held = Set<Data>()
        for item in try chat.pendingOutbox() where !held.contains(item.matchID) {
            guard let route = try routes.forMatch(item.matchID), !route.ending, route.canSend else {
                held.insert(item.matchID)
                continue
            }
            do {
                try client.submit(RelayTypes.Send(recipient: route.peerRoutingID, messageID: item.messageID,
                                                  messageClass: item.messageClass.rawValue,
                                                  capability: route.sendCapability, payload: item.envelope))
                try chat.markAccepted(messageID: item.messageID)
                t.sent += 1
            } catch let e as RelayError where e.kind == .refused {
                try chat.markFailed(messageID: item.messageID)
                t.failed += 1
            } catch let e as RelayError where e.code == LumosErrors.errRelayMailboxFull {
                // This peer's mailbox cannot take it now; others still can.
                held.insert(item.matchID)
            }
        }
    }

    private func receive(_ client: RelayClient, _ t: inout Report) throws {
        for _ in 0..<RelaySync.maxBatches {
            let envelopes = try client.fetch()
            guard !envelopes.isEmpty else {
                t.moreWaiting = false
                return
            }
            let byProof = try routes.byProof()
            var done: [Data] = []
            do {
                for e in envelopes {
                    if try open(byProof, e) {
                        t.shown += 1
                    }
                    done.append(e.messageID)
                }
            } catch let local as StoreError {
                // Acknowledge what committed before failing; the rest is refetched and answered by dedup.
                if !done.isEmpty, let n = try? client.ack(done) {
                    t.acknowledged += Int(n)
                }
                throw local
            }
            t.acknowledged += Int(try client.ack(done))
            t.moreWaiting = envelopes.count == RelayClient.fetchBatch
            guard t.moreWaiting else { return }
        }
    }

    private func open(_ byProof: [String: Data], _ e: RelayTypes.Envelope) throws -> Bool {
        guard let matchID = byProof[RelayHex.encode(e.senderProof)], e.expiresAt * 1000 > clock(),
              let cls = MessageClass(rawValue: e.messageClass) else { return false }
        switch cls {
        case .textMessage:
            let o = try chat.receive(matchID: matchID, outerID: e.messageID, envelope: e.payload)
            return o == .displayed || o == .applied
        case .deliveryReceipt, .readReceipt:
            _ = try chat.receiveReceipt(matchID: matchID, outerID: e.messageID, kind: cls, envelope: e.payload)
            return false
        default:
            return false
        }
    }
}
