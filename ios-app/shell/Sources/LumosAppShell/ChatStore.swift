/// Lumos - LumosAppShell / Conversations, messages, the outbox and ratchet sessions, each change one transaction.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-RMT-001, SRS-RMT-003, SRS-RMT-004, SRS-RMT-005, SRS-RMT-010, SRS-RMT-011, SRS-RMT-014,
///             TRS-LOC-004, TRS-PAT-004
/// ADRs: ADR-001, ADR-003
/// Threading: serial-executor
/// Notes: The Swift port of the Android ChatStore, behaviour for behaviour.
/// Also serves SRS-RMT-009, SRS-RMT-017, TRS-LOC-005 and TRS-CRY-005, with
/// ChatSessions and ChatRecords (the header line has a length budget).
/// The rule everything here follows is TRS Sec. 8.3: a change that touches
/// several records is ONE transaction. Sending seals the envelope, saves the
/// advanced ratchet, writes the visible message and writes its outbox item
/// together, so after a crash there is either a queued message with the exact
/// bytes to resend, or nothing (TRS-PAT-004, TRS-LOC-004). Receiving opens the
/// envelope, saves the advanced ratchet, deletes a consumed one-time prekey
/// (TRS-CRY-005), applies the event and records the id as seen, together, so a
/// restart can never return to a ratchet that still holds a key already used
/// (SRS-RMT-017), and a message shows at most once (SRS-RMT-004).
///
/// Nothing is cached between calls. An event kind this build does not know (a
/// newer app) decrypts fine; it is consumed and ignored, never retried
/// (SRS-RMT-011). Edits and deletes apply only to the sender's own messages;
/// an event whose target is unknown is ignored.
///
/// Receipts (CR-005) ride the same sessions. Showing a message seals its
/// delivered receipt into the outbox in the same transaction; markRead sends
/// read receipts only when the user allows them (SRS-RMT-014); only the
/// recipient's receipts ever move a message past one tick (owner ruling
/// 2026-11-21).

import Foundation
import LumosProtocol
import LumosProtocolGenerated

/// The device's chat state.
public final class ChatStore {

    /// What receiving one envelope did.
    public enum Outcome: Sendable, Equatable {
        /// A new message is shown.
        case displayed
        /// An edit, delete or reaction was applied to its target.
        case applied
        /// This id was already shown; nothing changed.
        case duplicate
        /// Opened and consumed, but nothing to show: unknown kind or unknown target.
        case ignored
        /// Refused; nothing changed. The code says why, for diagnostics only.
        case rejected(code: String)
    }

    private let store: LocalStore
    private let own: RemoteSession.Identity
    private let clock: () -> Int64
    private let sessions: ChatSessions

    /// Binds the chat state to the device's store. The store is shared on
    /// purpose: chat writes join the same transactions as everything else.
    /// - Parameters:
    ///   - store: the sealed local store
    ///   - own: this device's identity
    ///   - clock: the UTC-millisecond clock
    ///   - ratchetKeys: fresh ratchet scalars from the platform CSPRNG
    public init(store: LocalStore, own: RemoteSession.Identity, clock: @escaping () -> Int64,
                ratchetKeys: @escaping () throws -> Data) {
        self.store = store
        self.own = own
        self.clock = clock
        self.sessions = ChatSessions(store: store, own: own, clock: clock, ratchetKeys: ratchetKeys)
    }

    // MARK: prekeys and conversations

    /// Stores a private prekey this device published.
    public func putPrekeySecret(_ secret: PrekeySecret) throws {
        try store.putByNaturalKey(kind: .prekeySecret, plaintext: secret.encoded,
                                  naturalKey: PrekeySecret.naturalKey(oneTime: secret.oneTime, id: secret.id))
    }

    /// Pins a matched peer's identity, so its first message can be accepted.
    public func acceptConversation(matchID: Data, peerIdentityEd: Data) throws {
        try store.putByNaturalKey(kind: .conversation,
                                  plaintext: ChatRecords.Conversation(matchID: matchID,
                                                                      peerIdentityEd: peerIdentityEd).encoded,
                                  naturalKey: matchID)
    }

    /// The identity a match pinned for its peer: what a send must address.
    /// - Returns: the peer's Ed25519 identity, or nil for a conversation this device never accepted
    public func peerIdentity(matchID: Data) throws -> Data? {
        try conversation(matchID)?.peerIdentityEd
    }

    private func conversation(_ matchID: Data) throws -> ChatRecords.Conversation? {
        try store.findByNaturalKey(kind: .conversation, naturalKey: matchID).first.map(ChatRecords.Conversation.decode)
    }

    /// Starts a session with a matched peer from its fetched bundle. The bundle
    /// must belong to the identity the match pinned.
    /// - Throws: ERR_CRYPTO_PREKEY_SIGNATURE_INVALID when the bundle is not the
    ///   matched peer's or does not verify; ``StoreError`` on engine failure
    public func startSession(matchID: Data, peerIdentityEd: Data, bundle: X3DH.Bundle,
                             ephemeralPriv: Data, ratchetPriv: Data) throws {
        guard bundle.identityEd == peerIdentityEd else {
            throw LumosCrypto.WireError(LumosErrors.errCryptoPrekeySignatureInvalid,
                                        "the bundle is not the matched peer's")
        }
        let (ratchet, prekey) = try RemoteSession.initiate(own: own, bundle: bundle, matchID: matchID,
                                                           ephemeralPriv: ephemeralPriv, ratchetPriv: ratchetPriv)
        try store.transaction {
            try acceptConversation(matchID: matchID, peerIdentityEd: peerIdentityEd)
            try sessions.save(matchID, ratchet, prekeyFields: prekey.encoded)
        }
    }

    /// Whether a session exists with this match.
    public func hasSession(matchID: Data) throws -> Bool {
        try sessions.session(matchID) != nil
    }

    // MARK: sending

    /// Seals and queues one event. The message, the advanced ratchet and the
    /// outbox item land together or not at all.
    /// - Returns: the new message id
    /// - Throws: a sealing ``LumosCrypto/WireError``; ``StoreError`` with no
    ///   session, an invalid target, or an engine failure
    @discardableResult
    public func send(matchID: Data, event: RemoteText.TextEvent) throws -> Data {
        let id = ChatRecords.newMessageID()
        try store.transaction {
            let target = try targetFor(matchID, event, mine: true)
            let envelope = try sessions.seal(matchID) { ratchet, prekey in
                try RemoteSession.seal(&ratchet, RemoteText.Plaintext(messageID: id, event: event),
                                       prekey: prekey, now: sessions.nowSeconds)
            }
            try applyOwn(matchID, id, event, target)
            try enqueue(matchID, id, envelope, .textMessage)
        }
        return id
    }

    /// Writes an outbox item, last in the device-wide first-in-first-out order.
    private func enqueue(_ matchID: Data, _ id: Data, _ envelope: Data, _ cls: MessageClass) throws {
        let order = (try pendingOutbox().map(\.sequence).max() ?? 0) + 1
        try store.put(kind: .outboxItem, id: ChatRecords.rowID(id),
                      plaintext: ChatRecords.OutboxItem(messageID: id, matchID: matchID, envelope: envelope,
                                                        createdAtMillis: clock(), sequence: order, attempts: 0,
                                                        messageClass: cls).encoded)
    }

    private func targetFor(_ matchID: Data, _ event: RemoteText.TextEvent, mine: Bool) throws -> ChatRecords.Message? {
        guard event.kind != .text, let targetID = event.target else { return nil }
        let t = try message(targetID)
        let authorOnly = event.kind != .reaction
        let valid = t.map { $0.matchID == matchID && !$0.deleted && (!authorOnly || $0.outgoing == mine) } ?? false
        if !valid && mine {
            throw StoreError.engine("that message cannot be the target of this event")
        }
        return valid ? t : nil
    }

    private func applyOwn(_ matchID: Data, _ id: Data, _ event: RemoteText.TextEvent,
                          _ target: ChatRecords.Message?) throws {
        guard event.kind != .text else {
            try putMessage(ChatRecords.Message(messageID: id, matchID: matchID, outgoing: true,
                                               body: event.body ?? Data(), createdAtMillis: clock(),
                                               sequence: try nextSequence(matchID), state: .queued))
            return
        }
        guard let target else { throw StoreError.engine("that message cannot be the target of this event") }
        try putMessage(ChatStore.applied(target, event, mine: true))
    }

    private static func applied(_ t: ChatRecords.Message, _ event: RemoteText.TextEvent,
                                mine: Bool) -> ChatRecords.Message {
        switch event.kind {
        case .edit: return t.with(edit: event.body ?? Data())
        case .deleteForEveryone: return t.deletedForEveryone()
        default: return t.with(reaction: event.body ?? Data(), mine: mine)
        }
    }

    /// The next position in a conversation: one past the highest, assigned inside the write's transaction.
    private func nextSequence(_ matchID: Data) throws -> Int64 {
        (try messages(matchID: matchID).map(\.sequence).max() ?? 0) + 1
    }

    private func putMessage(_ m: ChatRecords.Message) throws {
        try store.put(kind: .chatMessage, id: ChatRecords.rowID(m.messageID), plaintext: m.encoded,
                      naturalKey: m.matchID)
    }

    private func message(_ id: Data) throws -> ChatRecords.Message? {
        guard id.count == ChatRecords.idLen else { return nil }
        return try store.get(kind: .chatMessage, id: ChatRecords.rowID(id)).map(ChatRecords.Message.decode)
    }

    // MARK: receiving

    /// Opens one envelope the relay delivered under outerID.
    /// - Returns: what happened; a bad envelope is ``Outcome/rejected(code:)``, not thrown
    /// - Throws: ``StoreError`` on an engine failure
    public func receive(matchID: Data, outerID: Data, envelope envelopeBytes: Data) throws -> Outcome {
        guard outerID.count == RemoteText.messageIDSize else {
            return .rejected(code: LumosErrors.errPayloadMalformed)
        }
        if try store.get(kind: .messageDedup, id: ChatRecords.rowID(outerID)) != nil {
            return .duplicate
        }
        do {
            let envelope = try RemoteText.decodeEnvelope(envelopeBytes)
            return try store.transaction { try openAndApply(matchID, outerID, envelope) }
        } catch let refused as LumosCrypto.WireError {
            return .rejected(code: refused.code)
        }
    }

    private func openAndApply(_ matchID: Data, _ outerID: Data, _ envelope: RemoteText.Envelope) throws -> Outcome {
        let plaintext = try sessions.open(try conversationOrRefuse(matchID), outerID: outerID, envelope: envelope)
        let p: RemoteText.Plaintext
        do {
            p = try RemoteText.decodePlaintext(plaintext)
        } catch let e as LumosCrypto.WireError where e.code == LumosErrors.errTextUnknownEventKind {
            return .ignored // consumed, safely ignored (SRS-RMT-011)
        }
        let outcome = try applyPeer(matchID, outerID, p.event)
        if outcome == .displayed {
            try queueReceipt(matchID, .deliveryReceipt, [outerID])
        }
        return outcome
    }

    private func conversationOrRefuse(_ matchID: Data) throws -> ChatRecords.Conversation {
        guard let c = try conversation(matchID) else {
            throw LumosCrypto.WireError(LumosErrors.errPayloadMalformed, "no such conversation")
        }
        return c
    }

    private func applyPeer(_ matchID: Data, _ id: Data, _ event: RemoteText.TextEvent) throws -> Outcome {
        if event.kind == .text {
            try putMessage(ChatRecords.Message(messageID: id, matchID: matchID, outgoing: false,
                                               body: event.body ?? Data(), createdAtMillis: clock(),
                                               sequence: try nextSequence(matchID), state: .delivered))
            return .displayed
        }
        guard let target = try targetFor(matchID, event, mine: false) else { return .ignored }
        try putMessage(ChatStore.applied(target, event, mine: false))
        return .applied
    }

    // MARK: receipts (CR-005)

    /// Seals one receipt into the outbox, inside the caller's transaction.
    private func queueReceipt(_ matchID: Data, _ kind: MessageClass, _ ids: [Data]) throws {
        let id = ChatRecords.newMessageID()
        let receipt = try Receipt(id: id, kind: kind, acked: ids)
        let envelope = try sessions.seal(matchID) { ratchet, prekey in
            try receipt.seal(&ratchet, prekey: prekey, now: sessions.nowSeconds)
        }
        try enqueue(matchID, id, envelope, kind)
    }

    /// The user has seen a conversation: its unread incoming messages become
    /// read, and read receipts go to the peer only when the user allows them
    /// (SRS-RMT-014; delivered receipts were already sent on arrival).
    /// - Returns: how many messages became read
    @discardableResult
    public func markRead(matchID: Data, readReceiptsEnabled: Bool) throws -> Int {
        try store.transaction {
            var read: [Data] = []
            for m in try messages(matchID: matchID) where !m.outgoing && m.state == .delivered {
                try putMessage(m.with(state: .read))
                read.append(m.messageID)
            }
            if DeliveryMachine.receiptsToSend(readReceiptsEnabled: readReceiptsEnabled, opened: !read.isEmpty).read {
                let batch = LumosConstants.receiptIDSPerEnvelopeDefault
                for start in stride(from: 0, to: read.count, by: batch) {
                    try queueReceipt(matchID, .readReceipt, Array(read[start..<min(read.count, start + batch)]))
                }
            }
            return read.count
        }
    }

    /// Opens one receipt the relay delivered under outerID and kind, and moves
    /// this conversation's own messages it names to delivered or read. A
    /// receipt never downgrades a message (DeliveryMachine), and one naming a
    /// message from another conversation, or the peer's own, changes nothing.
    /// - Returns: ``Outcome/applied``, ``Outcome/duplicate``, or ``Outcome/rejected(code:)``
    /// - Throws: ``StoreError`` on an engine failure
    public func receiveReceipt(matchID: Data, outerID: Data, kind: MessageClass,
                               envelope envelopeBytes: Data) throws -> Outcome {
        guard outerID.count == RemoteText.messageIDSize else {
            return .rejected(code: LumosErrors.errPayloadMalformed)
        }
        if try store.get(kind: .messageDedup, id: ChatRecords.rowID(outerID)) != nil {
            return .duplicate
        }
        do {
            return try store.transaction {
                let envelope = try RemoteText.decodeEnvelope(envelopeBytes)
                let r = try Receipt.decode(try sessions.open(try conversationOrRefuse(matchID), outerID: outerID,
                                                             envelope: envelope))
                guard r.kind == kind else {
                    throw LumosCrypto.WireError(LumosErrors.errPayloadMalformed, "receipt kind differs")
                }
                try applyReceipt(matchID, r)
                return .applied
            }
        } catch let refused as LumosCrypto.WireError {
            return .rejected(code: refused.code)
        }
    }

    private func applyReceipt(_ matchID: Data, _ r: Receipt) throws {
        let event: DeliveryMachine.Event = r.kind == .readReceipt ? .readReceipt : .deliveryReceipt
        for id in r.acked {
            if let m = try message(id), m.outgoing, m.matchID == matchID {
                try putMessage(m.with(state: DeliveryMachine.step(m.state, event)))
            }
        }
    }

    // MARK: reading, the outbox and housekeeping

    /// A conversation's messages, in this device's order: the order it sent or received them.
    public func messages(matchID: Data) throws -> [ChatRecords.Message] {
        try store.findByNaturalKey(kind: .chatMessage, naturalKey: matchID)
            .map(ChatRecords.Message.decode).sorted { $0.sequence < $1.sequence }
    }

    /// Every queued send, first in first out: what the transport must deliver.
    public func pendingOutbox() throws -> [ChatRecords.OutboxItem] {
        try store.allOf(kind: .outboxItem).map { try ChatRecords.OutboxItem.decode($0.content) }
            .sorted { $0.sequence < $1.sequence }
    }

    /// Records that a transport accepted a queued send: one tick, and the outbox
    /// item is done. Delivered and read come only from the peer's receipts,
    /// never from here (owner ruling 2026-11-21).
    public func markAccepted(messageID: Data) throws {
        try finish(messageID, .transportAccepted)
    }

    /// Records that a queued send can never go as it is (the relay refused it
    /// for good): the red mark, and the outbox item is done. A retry is a new
    /// send the user chooses.
    public func markFailed(messageID: Data) throws {
        try finish(messageID, .attemptsExhausted)
    }

    private func finish(_ messageID: Data, _ outcome: DeliveryMachine.Event) throws {
        try store.transaction {
            if let m = try message(messageID), m.outgoing {
                let sending = DeliveryMachine.step(m.state, .sendStarted)
                try putMessage(m.with(state: DeliveryMachine.step(sending, outcome)))
            }
            try store.delete(kind: .outboxItem, id: ChatRecords.rowID(messageID))
        }
    }

    /// Removes one message from this device only (SRS-RMT-010); the peer is told
    /// nothing. Refused while it waits in the outbox: its sealed envelope would
    /// still go, and the person must not think it gone.
    /// - Returns: true when removed; false when still waiting to go out, or unknown
    public func deleteForMe(messageID: Data) throws -> Bool {
        try store.transaction {
            guard try message(messageID) != nil,
                  try store.get(kind: .outboxItem, id: ChatRecords.rowID(messageID)) == nil else { return false }
            try store.delete(kind: .chatMessage, id: ChatRecords.rowID(messageID))
            return true
        }
    }

    /// Forgets displayed ids whose dedup window has passed (TRS-LOC-005).
    /// - Returns: how many were forgotten
    @discardableResult
    public func purgeExpiredDedup() throws -> Int {
        let now = clock()
        var n = 0
        for e in try store.allOf(kind: .messageDedup) where try ChatRecords.decodeDedup(e.content) < now {
            try store.delete(kind: .messageDedup, id: e.id)
            n += 1
        }
        return n
    }
}
