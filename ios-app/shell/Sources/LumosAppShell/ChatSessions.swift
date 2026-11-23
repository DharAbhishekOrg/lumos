/// Lumos - LumosAppShell / Ratchet sessions at rest: load, seal, open, save - inside the caller's transaction.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-RMT-007, SRS-RMT-017, SRS-NFR-SEC-006, TRS-CRY-005, TRS-LOC-004
/// ADRs: ADR-001, ADR-003
/// Threading: serial-executor
/// Notes: The Swift port of the Android ChatSessions. Nothing here opens a
/// transaction: every method runs inside ChatStore's, so the advanced ratchet,
/// the consumed one-time prekey, the dedup entry and whatever the plaintext
/// changes commit together or not at all (SRS-RMT-017). A session is restored
/// from the store for every operation and saved only on success, so a failed
/// decrypt is rolled back by throwing the in-memory ratchet away. The dedup
/// cache is held to its id cap after every write (SRS-NFR-SEC-006), as on
/// Android.

import Foundation
import LumosProtocol
import LumosProtocolGenerated

/// The chat's ratchet sessions, as stored.
final class ChatSessions {

    private static let millisPerSecond: Int64 = 1000
    private static let millisPerDay: Int64 = 24 * 60 * 60 * millisPerSecond

    private let store: LocalStore
    private let own: RemoteSession.Identity
    private let clock: () -> Int64
    private let ratchetKeys: () throws -> Data

    init(store: LocalStore, own: RemoteSession.Identity, clock: @escaping () -> Int64,
         ratchetKeys: @escaping () throws -> Data) {
        self.store = store
        self.own = own
        self.clock = clock
        self.ratchetKeys = ratchetKeys
    }

    var nowSeconds: Int64 { clock() / ChatSessions.millisPerSecond }

    func session(_ matchID: Data) throws -> ChatRecords.Session? {
        try store.findByNaturalKey(kind: .ratchetSession, naturalKey: matchID).first
            .map(ChatRecords.Session.decode)
    }

    func save(_ matchID: Data, _ ratchet: DoubleRatchet, prekeyFields: Data) throws {
        try store.putByNaturalKey(kind: .ratchetSession,
                                  plaintext: ChatRecords.Session(ratchetState: ratchet.exportState(),
                                                                 prekeyFields: prekeyFields).encoded,
                                  naturalKey: matchID)
    }

    func prekey(oneTime: Bool, id: UInt32) throws -> LocalStore.Entry? {
        try store.findEntriesByNaturalKey(kind: .prekeySecret,
                                          naturalKey: PrekeySecret.naturalKey(oneTime: oneTime, id: id)).first
    }

    /// Seals with the stored session and saves the advanced ratchet.
    /// - Throws: ``StoreError`` with no session or on engine failure; the sealer's own error unchanged
    func seal(_ matchID: Data,
              _ sealer: (inout DoubleRatchet, RemoteText.PrekeyFields?) throws -> Data) throws -> Data {
        guard let s = try session(matchID) else { throw StoreError.engine("no session with this match") }
        var ratchet = try ChatSessions.restore(s)
        let envelope = try sealer(&ratchet, try ChatSessions.decodePrekey(s.prekeyFields))
        try save(matchID, ratchet, prekeyFields: s.prekeyFields)
        return envelope
    }

    /// Opens one envelope from the conversation's peer: restores the session or
    /// responds to a PREKEY envelope, decrypts, checks the authenticated id
    /// against the delivered one, saves the ratchet, deletes a consumed one-time
    /// prekey and records the id as seen.
    /// - Returns: the plaintext, whose first 16 bytes are outerID
    /// - Throws: ``LumosCrypto/WireError`` on any refusal; ``StoreError`` on engine failure
    func open(_ conv: ChatRecords.Conversation, outerID: Data, envelope: RemoteText.Envelope) throws -> Data {
        let matchID = conv.matchID
        let existing = try session(matchID)
        var ratchet: DoubleRatchet
        var prekeyFields = Data()
        if let existing {
            ratchet = try ChatSessions.restore(existing)
            // The responder never carries PREKEY fields; the initiator drops
            // them once a NORMAL envelope from the peer opens.
            prekeyFields = envelope.prekey == nil ? Data() : existing.prekeyFields
        } else {
            ratchet = try respond(conv, envelope)
        }
        let plaintext = try ratchet.decrypt(envelope.header, envelope.ciphertext, now: nowSeconds,
                                            nextKey: ratchetKeys)
        guard plaintext.count >= RemoteText.messageIDSize, plaintext.prefix(RemoteText.messageIDSize) == outerID else {
            throw LumosCrypto.WireError(LumosErrors.errPayloadMalformed, "authenticated id differs")
        }
        try save(matchID, ratchet, prekeyFields: prekeyFields)
        if existing == nil, let p = envelope.prekey, p.opkID != UInt32(LumosConstants.x3dhOpkIDNoneDefault),
           let consumed = try prekey(oneTime: true, id: p.opkID) {
            try store.delete(kind: .prekeySecret, id: consumed.id)
        }
        try store.put(kind: .messageDedup, id: ChatRecords.rowID(outerID),
                      plaintext: ChatRecords.encodeDedup(
                        expiresAtMillis: clock() + Int64(LumosConstants.dedupCacheDays) * ChatSessions.millisPerDay))
        try ChatSessions.trimDedup(store, cap: LumosConstants.dedupCacheIDS)
        return plaintext
    }

    /// Holds the dedup cache to its id cap (SRS-NFR-SEC-006, the register's
    /// DEDUP_CACHE ids). Over the cap, the ids closest to expiry go first. The
    /// cache is defence in depth: a replayed id whose entry was evicted still
    /// fails, because the ratchet deleted that message's key when it opened
    /// (SRS-RMT-017). The count is cheap, so the full scan runs only when the
    /// cap is actually exceeded.
    /// - Returns: how many were evicted
    @discardableResult
    static func trimDedup(_ store: LocalStore, cap: Int) throws -> Int {
        let excess = try store.count(kind: .messageDedup) - cap
        guard excess > 0 else { return 0 }
        let byExpiry = try store.allOf(kind: .messageDedup)
            .map { (try ChatRecords.decodeDedup($0.content), $0.id) }
            .sorted { $0.0 < $1.0 }
        for (_, id) in byExpiry.prefix(excess) {
            try store.delete(kind: .messageDedup, id: id)
        }
        return excess
    }

    private func respond(_ conv: ChatRecords.Conversation, _ envelope: RemoteText.Envelope) throws -> DoubleRatchet {
        guard let p = envelope.prekey else {
            throw LumosCrypto.WireError(LumosErrors.errCryptoAeadAuthFailed, "no session for a NORMAL envelope")
        }
        guard let spk = try prekey(oneTime: false, id: p.spkID) else {
            throw LumosCrypto.WireError(LumosErrors.errCryptoPrekeySignatureInvalid, "signed prekey unknown")
        }
        var opkPriv: Data?
        if p.opkID != UInt32(LumosConstants.x3dhOpkIDNoneDefault), let opk = try prekey(oneTime: true, id: p.opkID) {
            opkPriv = try PrekeySecret.decode(opk.content).privateKey
        }
        let keys = RemoteSession.ResponderKeys(spkPriv: try PrekeySecret.decode(spk.content).privateKey,
                                               spkID: p.spkID, opkPriv: opkPriv, opkID: p.opkID)
        return try RemoteSession.respond(own: own, keys: keys, initiatorEd: conv.peerIdentityEd, prekey: p,
                                         matchID: conv.matchID)
    }

    static func restore(_ s: ChatRecords.Session) throws -> DoubleRatchet {
        do {
            return try DoubleRatchet.restoreState(s.ratchetState)
        } catch {
            throw StoreError.engine("stored ratchet state is unreadable")
        }
    }

    static func decodePrekey(_ fields: Data) throws -> RemoteText.PrekeyFields? {
        guard !fields.isEmpty else { return nil }
        do {
            return try RemoteText.decodePrekeyFields(fields)
        } catch {
            throw StoreError.engine("stored prekey fields are unreadable")
        }
    }
}
