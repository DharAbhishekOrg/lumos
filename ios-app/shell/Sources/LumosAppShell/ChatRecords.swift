/// Lumos - LumosAppShell / The chat path's record contents and their byte encodings.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-LOC-001, TRS-LOC-004, TRS-LOC-005, TRS-PAT-004, SRS-RMT-004, SRS-RMT-017, TRS-CRY-005
/// ADRs: ADR-003
/// Threading: any
/// Notes: The Swift port of the Android ChatRecords and PrekeySecret, field
/// for field and in the same byte layouts, so the two platforms' rows mean
/// the same thing. What each schema-v6 row holds before LocalStore seals it.
/// Every encoding starts with a format byte so a later build can read an older
/// row (TRS-LOC-003), and every decoder checks lengths before copying.
/// Message ids are random version-4 UUIDs, exactly MESSAGE_ID_LEN_BYTES long,
/// so the row id of a message, its outbox item and its dedup entry is simply
/// the id's UUID string (TRS-LOC-010): it derives from nothing.

import Foundation
import LumosProtocol
import LumosProtocolGenerated

/// The chat records and their encodings.
public enum ChatRecords {

    static let format: UInt8 = 1
    static let idLen = 16
    static let keyLen = 32

    /// The UUID string of a 16-byte message id: the row id of its records.
    /// - Parameter messageID: the message id
    /// - Returns: the canonical UUID string, lower-case as the Android port writes it
    public static func rowID(_ messageID: Data) -> String {
        precondition(messageID.count == idLen, "a message id is 16 bytes")
        let b = [UInt8](messageID)
        return UUID(uuid: (b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7],
                           b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15])).uuidString.lowercased()
    }

    /// A fresh random message id: a version-4 UUID's 16 bytes.
    public static func newMessageID() -> Data {
        withUnsafeBytes(of: UUID().uuid) { Data($0) }
    }

    static func corrupt() -> StoreError {
        .engine("chat record is malformed")
    }

    // MARK: encoding helpers

    struct Writer {
        private(set) var data = Data([ChatRecords.format])

        mutating func byte(_ b: UInt8) {
            data.append(b)
        }

        mutating func raw(_ b: Data) {
            data += b
        }

        mutating func u32(_ v: UInt32) {
            data += LumosCrypto.be32(v)
        }

        mutating func i64(_ v: Int64) {
            let u = UInt64(bitPattern: v)
            data += Data((0..<8).map { UInt8(truncatingIfNeeded: u >> (56 - 8 * $0)) })
        }

        mutating func prefixed(_ b: Data) {
            u32(UInt32(b.count))
            raw(b)
        }
    }

    struct Reader {
        private let bytes: [UInt8]
        private var at = 0

        /// Opens a record, checking its format byte.
        init(_ data: Data) throws {
            bytes = [UInt8](data)
            guard bytes.first == ChatRecords.format else { throw ChatRecords.corrupt() }
            at = 1
        }

        var remaining: Int { bytes.count - at }

        mutating func fixed(_ n: Int) throws -> Data {
            guard n >= 0, remaining >= n else { throw ChatRecords.corrupt() }
            defer { at += n }
            return Data(bytes[at..<at + n])
        }

        mutating func byte() throws -> UInt8 {
            try fixed(1)[0]
        }

        mutating func u32() throws -> UInt32 {
            try fixed(4).reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
        }

        mutating func i64() throws -> Int64 {
            Int64(bitPattern: try fixed(8).reduce(UInt64(0)) { $0 << 8 | UInt64($1) })
        }

        mutating func prefixed() throws -> Data {
            let n = try u32()
            // A Java int length: the Android port refuses a negative one.
            guard n <= UInt32(Int32.max) else { throw ChatRecords.corrupt() }
            return try fixed(Int(n))
        }
    }

    // MARK: records

    /// A conversation with a matched peer.
    public struct Conversation: Equatable, Sendable {
        /// The match identifier.
        public let matchID: Data
        /// The peer's Ed25519 identity, pinned at the match.
        public let peerIdentityEd: Data

        /// Creates the record.
        public init(matchID: Data, peerIdentityEd: Data) {
            self.matchID = matchID
            self.peerIdentityEd = peerIdentityEd
        }

        /// The record bytes.
        public var encoded: Data {
            var w = Writer()
            w.prefixed(matchID)
            w.prefixed(peerIdentityEd)
            return w.data
        }

        /// - Throws: ``StoreError/engine(_:)`` when the bytes are not one
        public static func decode(_ b: Data) throws -> Conversation {
            var r = try Reader(b)
            return Conversation(matchID: try r.prefixed(), peerIdentityEd: try r.prefixed())
        }
    }

    /// A conversation's ratchet and, until the peer first replies, the PREKEY
    /// fields every envelope must carry.
    public struct Session: Equatable, Sendable {
        /// The exported ratchet state: key material, sealed at rest.
        public let ratchetState: Data
        /// The encoded PREKEY fields, or empty once the peer replied.
        public let prekeyFields: Data

        /// Creates the record.
        public init(ratchetState: Data, prekeyFields: Data) {
            self.ratchetState = ratchetState
            self.prekeyFields = prekeyFields
        }

        /// The record bytes.
        public var encoded: Data {
            var w = Writer()
            w.prefixed(ratchetState)
            w.prefixed(prekeyFields)
            return w.data
        }

        /// - Throws: ``StoreError/engine(_:)`` when the bytes are not one
        public static func decode(_ b: Data) throws -> Session {
            var r = try Reader(b)
            return Session(ratchetState: try r.prefixed(), prekeyFields: try r.prefixed())
        }
    }

    /// One visible message, with the events applied to it.
    public struct Message: Equatable, Sendable {
        /// The message id.
        public let messageID: Data
        /// The conversation.
        public let matchID: Data
        /// Whether this device sent it.
        public let outgoing: Bool
        /// The text, empty once deleted for everyone.
        public let body: Data
        /// UTC creation time.
        public let createdAtMillis: Int64
        /// This device's order of the message in its conversation.
        public let sequence: Int64
        /// The delivery state (outgoing only).
        public let state: DeliveryMachine.State
        /// Whether an edit was applied.
        public let edited: Bool
        /// Whether it was deleted for everyone.
        public let deleted: Bool
        /// This device's reaction, or empty.
        public let myReaction: Data
        /// The peer's reaction, or empty.
        public let peerReaction: Data

        /// Creates the record.
        public init(messageID: Data, matchID: Data, outgoing: Bool, body: Data, createdAtMillis: Int64,
                    sequence: Int64, state: DeliveryMachine.State, edited: Bool = false, deleted: Bool = false,
                    myReaction: Data = Data(), peerReaction: Data = Data()) {
            self.messageID = messageID
            self.matchID = matchID
            self.outgoing = outgoing
            self.body = body
            self.createdAtMillis = createdAtMillis
            self.sequence = sequence
            self.state = state
            self.edited = edited
            self.deleted = deleted
            self.myReaction = myReaction
            self.peerReaction = peerReaction
        }

        /// This message in another delivery state.
        public func with(state next: DeliveryMachine.State) -> Message {
            Message(messageID: messageID, matchID: matchID, outgoing: outgoing, body: body,
                    createdAtMillis: createdAtMillis, sequence: sequence, state: next, edited: edited,
                    deleted: deleted, myReaction: myReaction, peerReaction: peerReaction)
        }

        /// This message, edited.
        public func with(edit newBody: Data) -> Message {
            Message(messageID: messageID, matchID: matchID, outgoing: outgoing, body: newBody,
                    createdAtMillis: createdAtMillis, sequence: sequence, state: state, edited: true,
                    deleted: deleted, myReaction: myReaction, peerReaction: peerReaction)
        }

        /// This message, deleted for everyone: the body is gone.
        public func deletedForEveryone() -> Message {
            Message(messageID: messageID, matchID: matchID, outgoing: outgoing, body: Data(),
                    createdAtMillis: createdAtMillis, sequence: sequence, state: state, edited: edited,
                    deleted: true, myReaction: myReaction, peerReaction: peerReaction)
        }

        /// This message with a reaction set, this device's or the peer's.
        public func with(reaction: Data, mine: Bool) -> Message {
            Message(messageID: messageID, matchID: matchID, outgoing: outgoing, body: body,
                    createdAtMillis: createdAtMillis, sequence: sequence, state: state, edited: edited,
                    deleted: deleted, myReaction: mine ? reaction : myReaction,
                    peerReaction: mine ? peerReaction : reaction)
        }

        /// The state's index in the Android port's enum order, which the stored byte uses.
        private static let states: [DeliveryMachine.State] =
            [.queued, .sending, .sent, .delivered, .read, .failed, .localOnlyWaiting]

        /// The record bytes.
        public var encoded: Data {
            var w = Writer()
            w.raw(messageID)
            w.prefixed(matchID)
            w.byte(outgoing ? 1 : 0)
            w.prefixed(body)
            w.i64(createdAtMillis)
            w.i64(sequence)
            w.byte(UInt8(Message.states.firstIndex(of: state) ?? 0))
            w.byte((edited ? 1 : 0) | (deleted ? 2 : 0))
            w.prefixed(myReaction)
            w.prefixed(peerReaction)
            return w.data
        }

        /// - Throws: ``StoreError/engine(_:)`` when the bytes are not one
        public static func decode(_ b: Data) throws -> Message {
            var r = try Reader(b)
            let id = try r.fixed(ChatRecords.idLen)
            let match = try r.prefixed()
            let out = try r.byte() == 1
            let body = try r.prefixed()
            let created = try r.i64()
            let sequence = try r.i64()
            let stateIndex = Int(try r.byte())
            guard stateIndex < states.count else { throw ChatRecords.corrupt() }
            let flags = try r.byte()
            return Message(messageID: id, matchID: match, outgoing: out, body: body, createdAtMillis: created,
                           sequence: sequence, state: states[stateIndex], edited: flags & 1 != 0,
                           deleted: flags & 2 != 0, myReaction: try r.prefixed(), peerReaction: try r.prefixed())
        }
    }

    /// A send waiting for its transport: a TRS-PAT-004 command whose
    /// idempotency key is the message id. The envelope is sealed ONCE, when the
    /// message is created, so every retry sends the same bytes and the relay
    /// recognises it as the same message.
    public struct OutboxItem: Equatable, Sendable {
        /// The idempotency key.
        public let messageID: Data
        /// The conversation.
        public let matchID: Data
        /// The sealed relay payload.
        public let envelope: Data
        /// UTC enqueue time.
        public let createdAtMillis: Int64
        /// The device-wide enqueue order: the outbox is first in, first out.
        public let sequence: Int64
        /// Transport attempts so far.
        public let attempts: Int32
        /// The class the relay carries it under: a text, or a delivery or read receipt.
        public let messageClass: MessageClass

        /// Creates the record.
        public init(messageID: Data, matchID: Data, envelope: Data, createdAtMillis: Int64, sequence: Int64,
                    attempts: Int32, messageClass: MessageClass) {
            self.messageID = messageID
            self.matchID = matchID
            self.envelope = envelope
            self.createdAtMillis = createdAtMillis
            self.sequence = sequence
            self.attempts = attempts
            self.messageClass = messageClass
        }

        /// The record bytes.
        public var encoded: Data {
            var w = Writer()
            w.raw(messageID)
            w.prefixed(matchID)
            w.prefixed(envelope)
            w.i64(createdAtMillis)
            w.i64(sequence)
            w.u32(UInt32(bitPattern: attempts))
            // A trailing byte, as on Android (WP-05 S7e2): an item written
            // before it decodes as the text it was.
            w.byte(messageClass.rawValue)
            return w.data
        }

        /// - Throws: ``StoreError/engine(_:)`` when the bytes are not one
        public static func decode(_ b: Data) throws -> OutboxItem {
            var r = try Reader(b)
            let id = try r.fixed(ChatRecords.idLen)
            let match = try r.prefixed()
            let envelope = try r.prefixed()
            let created = try r.i64()
            let sequence = try r.i64()
            let attempts = Int32(bitPattern: try r.u32())
            let cls: MessageClass?
            cls = r.remaining > 0 ? MessageClass(rawValue: try r.byte()) : .textMessage
            guard let cls, r.remaining == 0 else { throw ChatRecords.corrupt() }
            return OutboxItem(messageID: id, matchID: match, envelope: envelope, createdAtMillis: created,
                              sequence: sequence, attempts: attempts, messageClass: cls)
        }
    }

    /// Encodes a dedup entry's expiry.
    public static func encodeDedup(expiresAtMillis: Int64) -> Data {
        var w = Writer()
        w.i64(expiresAtMillis)
        return w.data
    }

    /// - Throws: ``StoreError/engine(_:)`` when the bytes are not one
    public static func decodeDedup(_ b: Data) throws -> Int64 {
        var r = try Reader(b)
        return try r.i64()
    }
}

/// A private prekey scalar this device published, kept in PREKEY_SECRET and
/// found by a blind index over its kind and id. A one-time prekey's row is
/// deleted in the same transaction that opens the first message it agreed
/// (TRS-CRY-005).
public struct PrekeySecret: Equatable, Sendable {
    /// True for a one-time prekey, false for a signed prekey.
    public let oneTime: Bool
    /// The prekey id.
    public let id: UInt32
    /// The X25519 scalar.
    public let privateKey: Data

    /// Creates the record.
    /// - Throws: ``StoreError/engine(_:)`` unless the scalar is 32 bytes
    public init(oneTime: Bool, id: UInt32, privateKey: Data) throws {
        guard privateKey.count == ChatRecords.keyLen else { throw StoreError.engine("a prekey scalar is 32 bytes") }
        self.oneTime = oneTime
        self.id = id
        self.privateKey = privateKey
    }

    /// The blind-index input: kind and id.
    public static func naturalKey(oneTime: Bool, id: UInt32) -> Data {
        Data([oneTime ? 1 : 0]) + LumosCrypto.be32(id)
    }

    /// The record bytes.
    public var encoded: Data {
        var w = ChatRecords.Writer()
        w.byte(oneTime ? 1 : 0)
        w.u32(id)
        w.raw(privateKey)
        return w.data
    }

    /// - Throws: ``StoreError/engine(_:)`` when the bytes are not one
    public static func decode(_ b: Data) throws -> PrekeySecret {
        var r = try ChatRecords.Reader(b)
        let oneTime = try r.byte() == 1
        let id = try r.u32()
        return try PrekeySecret(oneTime: oneTime, id: id, privateKey: try r.fixed(ChatRecords.keyLen))
    }
}
