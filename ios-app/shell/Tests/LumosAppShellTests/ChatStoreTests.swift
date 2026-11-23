/// Lumos - LumosAppShellTests / Two devices chat through their outboxes, die, restart, and lose nothing.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-RMT-003, SRS-RMT-004, SRS-RMT-010, SRS-RMT-011, SRS-RMT-014, SRS-RMT-017, SRS-NFR-SEC-006,
///             TRS-LOC-004, TRS-CRY-005, TRS-TST-001
/// ADRs: ADR-001, ADR-003
/// Threading: test-only
/// Notes: The Swift side of the Android ChatStoreTest, scenario for scenario.
/// Each device is a real ChatStore over a real SQLite FILE through the same
/// sealed LocalStore the app uses. The "relay" is this test moving outbox
/// envelopes. "Process death" drops every object and reopens the file with
/// the same data key. Identities are real Ed25519 keys from CryptoKit, so the
/// bundle and binding signatures the protocol verifies are real.

import CryptoKit
import Foundation
import LumosProtocol
import LumosProtocolGenerated
import XCTest
@testable import LumosAppShell

/// A UTC-millisecond clock a test can move.
final class TestClock: @unchecked Sendable {
    private let lock = NSLock()
    private var millis: Int64

    init(_ start: Int64) {
        millis = start
    }

    func now() -> Int64 {
        lock.lock()
        defer { lock.unlock() }
        return millis
    }

    func advance(_ by: Int64) {
        lock.lock()
        millis += by
        lock.unlock()
    }
}

/// Free space a test can take away mid-operation.
final class AdjustableCapacity: StorageCapacity, @unchecked Sendable {
    private let lock = NSLock()
    private var bytes = Int64.max

    var usableSpaceBytes: Int64 {
        lock.lock()
        defer { lock.unlock() }
        return bytes
    }

    func set(_ value: Int64) {
        lock.lock()
        bytes = value
        lock.unlock()
    }
}

func randomBytes(_ n: Int) -> Data {
    Data((0..<n).map { _ in UInt8.random(in: 0...255) })
}

/// One device's identity and published prekeys, with real Ed25519 signatures.
struct ChatKeys {
    static let spkID: UInt32 = 7
    static let opkID: UInt32 = 42

    let ed = Curve25519.Signing.PrivateKey()
    let dhPriv = randomBytes(32)
    let spkPriv = randomBytes(32)
    let opkPriv = randomBytes(32)

    var edPub: Data { ed.publicKey.rawRepresentation }

    func bindingSig() throws -> Data {
        try ed.signature(for: Data(LumosConstants.x3dhIdentityBindingInfoValue.utf8) + X3DH.publicKey(of: dhPriv))
    }

    func identity() throws -> RemoteSession.Identity {
        RemoteSession.Identity(identityEd: edPub, identityDHPriv: dhPriv, identityDHSig: try bindingSig())
    }

    func bundle() throws -> X3DH.Bundle {
        let spkPub = try X3DH.publicKey(of: spkPriv)
        let spkSig = try ed.signature(for: Data(LumosConstants.x3dhPrekeySigningInfoValue.utf8)
                                        + LumosCrypto.be32(ChatKeys.spkID) + spkPub)
        return X3DH.Bundle(identityEd: edPub, identityDH: try X3DH.publicKey(of: dhPriv), identityDHSig: try bindingSig(),
                           spkID: ChatKeys.spkID, spk: spkPub, spkSig: spkSig, opkID: ChatKeys.opkID,
                           opk: try X3DH.publicKey(of: opkPriv))
    }
}

/// A device: its keys, its database file and its chat store.
final class ChatDevice {
    let keys: ChatKeys
    let file: URL
    let capacity = AdjustableCapacity()
    private let databaseKey: Data
    private let clock: TestClock
    private(set) var store: LocalStore!
    private(set) var chat: ChatStore!

    init(_ keys: ChatKeys, clock: TestClock) throws {
        self.keys = keys
        self.clock = clock
        file = FileManager.default.temporaryDirectory.appendingPathComponent("lumos-chat-\(UUID().uuidString).db")
        // Created once: a restart reopens the same keys, as a real device does.
        databaseKey = try DataKeyHierarchy.create(kek: SoftwareKekStore()).deriveKey(for: .database)
        try boot()
    }

    deinit {
        try? FileManager.default.removeItem(at: file)
    }

    /// (Re)starts the "process": a fresh connection, store and chat store on the same file.
    func boot() throws {
        chat = nil
        store = nil
        let clock = self.clock
        store = try LocalStore.open(database: try Sqlite3Database(path: file.path), databaseDomainKey: databaseKey,
                                    capacity: capacity, now: { clock.now() })
        chat = ChatStore(store: store, own: try keys.identity(), clock: { clock.now() },
                         ratchetKeys: { randomBytes(32) })
    }

    func publishPrekeys() throws {
        try chat.putPrekeySecret(try PrekeySecret(oneTime: false, id: ChatKeys.spkID, privateKey: keys.spkPriv))
        try chat.putPrekeySecret(try PrekeySecret(oneTime: true, id: ChatKeys.opkID, privateKey: keys.opkPriv))
    }
}

final class ChatStoreTests: XCTestCase {

    private let match = randomBytes(16)
    private let clock = TestClock(1_700_000_000_000)

    private func text(_ s: String) throws -> RemoteText.TextEvent {
        try RemoteText.TextEvent(kind: .text, target: nil, body: Data(s.utf8))
    }

    /// Moves every queued envelope from one device to the other, in the given
    /// order, and marks it accepted. Like the relay, it carries each under its
    /// class: texts are returned in order; receipts must apply.
    @discardableResult
    private func deliver(_ from: ChatDevice, _ to: ChatDevice, _ order: [Int]? = nil) throws -> [ChatStore.Outcome] {
        let items = try from.chat.pendingOutbox()
        var out: [ChatStore.Outcome] = []
        for i in order ?? Array(items.indices) {
            let item = items[i]
            if item.messageClass == .textMessage {
                out.append(try to.chat.receive(matchID: match, outerID: item.messageID, envelope: item.envelope))
            } else {
                XCTAssertEqual(try to.chat.receiveReceipt(matchID: match, outerID: item.messageID,
                                                          kind: item.messageClass, envelope: item.envelope),
                               .applied, "a receipt applies")
            }
        }
        for item in items {
            try from.chat.markAccepted(messageID: item.messageID)
        }
        return out
    }

    private func matchedPair() throws -> (ChatDevice, ChatDevice) {
        let alice = try ChatDevice(ChatKeys(), clock: clock)
        let bob = try ChatDevice(ChatKeys(), clock: clock)
        try bob.publishPrekeys()
        try bob.chat.acceptConversation(matchID: match, peerIdentityEd: alice.keys.edPub)
        try alice.chat.startSession(matchID: match, peerIdentityEd: bob.keys.edPub, bundle: try bob.keys.bundle(),
                                    ephemeralPriv: randomBytes(32), ratchetPriv: randomBytes(32))
        return (alice, bob)
    }

    private func only(_ d: ChatDevice, _ kind: MessageClass) throws -> ChatRecords.OutboxItem {
        try XCTUnwrap(try d.chat.pendingOutbox().first { $0.messageClass == kind })
    }

    func testTwoDevicesChatThroughTheirOutboxesWhileOneIsOffline() throws {
        let (alice, bob) = try matchedPair()
        let first = try alice.chat.send(matchID: match, event: try text("Hi! Lovely meeting you tonight."))
        let second = try alice.chat.send(matchID: match, event: try text("Coffee on Saturday?"))
        XCTAssertEqual(try alice.chat.pendingOutbox().count, 2, "both sends wait in the outbox (SRS-RMT-003)")
        for item in try alice.chat.pendingOutbox() {
            XCTAssertEqual(item.envelope.first, RemoteText.formatPrekey, "first contact carries the PREKEY fields")
        }
        XCTAssertEqual(try bob.store.count(kind: .prekeySecret), 2, "the one-time prekey is still there")

        // Bob comes online and the relay hands him the second message first.
        let got = try deliver(alice, bob, [1, 0])
        XCTAssertEqual(got, [.displayed, .displayed])
        XCTAssertEqual(try bob.store.count(kind: .prekeySecret), 1,
                       "the consumed one-time prekey is deleted (TRS-CRY-005)")
        for m in try alice.chat.messages(matchID: match) {
            XCTAssertEqual(m.state, .sent, "accepted by the transport is one tick, never delivered")
        }

        // Bob reacts and replies; Alice's next envelopes drop the PREKEY fields.
        try bob.chat.send(matchID: match, event: try RemoteText.TextEvent(kind: .reaction, target: first,
                                                                          body: Data("❤".utf8)))
        try bob.chat.send(matchID: match, event: try text("Yes! 11am?"))
        XCTAssertEqual(try deliver(bob, alice), [.applied, .displayed])
        try alice.chat.send(matchID: match, event: try RemoteText.TextEvent(kind: .edit, target: first,
                                                                            body: Data("Hi! Lovely meeting you.".utf8)))
        XCTAssertEqual(try alice.chat.pendingOutbox().first?.envelope.first, RemoteText.formatNormal,
                       "once the peer replied, envelopes are NORMAL")
        try alice.chat.send(matchID: match, event: try RemoteText.TextEvent(kind: .deleteForEveryone, target: second,
                                                                            body: nil))
        try deliver(alice, bob)

        let bobs = try bob.chat.messages(matchID: match)
        XCTAssertEqual(bobs.count, 3, "two from Alice, one from Bob; events change messages, they add none")
        let edited = try XCTUnwrap(bobs.first { $0.messageID == first })
        XCTAssertEqual(edited.body, Data("Hi! Lovely meeting you.".utf8))
        XCTAssertTrue(edited.edited)
        XCTAssertEqual(edited.myReaction, Data("❤".utf8), "Bob's own reaction shows on his side too")
        XCTAssertTrue(bobs.contains { $0.deleted && $0.body.isEmpty }, "deleted for everyone: body gone")
        let alicesFirst = try XCTUnwrap(try alice.chat.messages(matchID: match).first { $0.messageID == first })
        XCTAssertEqual(alicesFirst.peerReaction, Data("❤".utf8), "Alice sees Bob's reaction")
    }

    func testAMessageShowsAtMostOnceEvenIfDeliveredTwice() throws {
        let (alice, bob) = try matchedPair()
        try alice.chat.send(matchID: match, event: try text("once"))
        let item = try XCTUnwrap(try alice.chat.pendingOutbox().first)
        XCTAssertEqual(try bob.chat.receive(matchID: match, outerID: item.messageID, envelope: item.envelope), .displayed)
        XCTAssertEqual(try bob.chat.receive(matchID: match, outerID: item.messageID, envelope: item.envelope), .duplicate)
        XCTAssertEqual(try bob.chat.messages(matchID: match).count, 1, "SRS-RMT-004")
    }

    func testARestartLosesNothingAndReusesNoKey() throws {
        let (alice, bob) = try matchedPair()
        try alice.chat.send(matchID: match, event: try text("one"))
        try alice.chat.send(matchID: match, event: try text("two"))
        let before = try alice.chat.pendingOutbox()

        try alice.boot() // process death on Alice's side
        let after = try alice.chat.pendingOutbox()
        XCTAssertEqual(after.count, 2, "the outbox survives process death (TRS-LOC-004)")
        XCTAssertEqual(before[0].envelope, after[0].envelope, "a retry resends the same bytes")

        XCTAssertEqual(try bob.chat.receive(matchID: match, outerID: after[0].messageID, envelope: after[0].envelope),
                       .displayed)
        try bob.boot() // process death on Bob's side, between two messages
        XCTAssertEqual(try bob.chat.receive(matchID: match, outerID: after[1].messageID, envelope: after[1].envelope),
                       .displayed)
        XCTAssertEqual(try bob.chat.messages(matchID: match).count, 2)

        // Even once the dedup window has passed, the first message cannot open again: its key is gone.
        clock.advance(Int64(LumosConstants.dedupCacheDays + 1) * 24 * 60 * 60 * 1000)
        XCTAssertEqual(try bob.chat.purgeExpiredDedup(), 2)
        try bob.boot()
        let replay = try bob.chat.receive(matchID: match, outerID: after[0].messageID, envelope: after[0].envelope)
        guard case .rejected = replay else {
            return XCTFail("a deleted message key is never reused (SRS-RMT-017), got \(replay)")
        }
        XCTAssertEqual(try bob.chat.messages(matchID: match).count, 2)

        // Alice's sends continue on the same session after her restart.
        try alice.chat.send(matchID: match, event: try text("three"))
        XCTAssertEqual(try deliver(alice, bob, [2]), [.displayed])
    }

    func testTheMessageAndItsOutboxItemLandTogetherOrNotAtAll() throws {
        let (alice, bob) = try matchedPair()
        let stateBefore = try alice.store.findByNaturalKey(kind: .ratchetSession, naturalKey: match).first
        // The store fills up mid-send: whatever was written first must roll back with it.
        alice.capacity.set(0)
        XCTAssertThrowsError(try alice.chat.send(matchID: match, event: try text("lost?"))) {
            XCTAssertEqual($0 as? StoreError, .lowStorage)
        }
        alice.capacity.set(Int64.max)
        XCTAssertEqual(try alice.chat.pendingOutbox().count, 0)
        XCTAssertEqual(try alice.chat.messages(matchID: match).count, 0)
        XCTAssertEqual(try alice.store.findByNaturalKey(kind: .ratchetSession, naturalKey: match).first, stateBefore,
                       "the ratchet did not advance either")
        // An edit of a message that does not exist is refused before anything is written.
        XCTAssertThrowsError(try alice.chat.send(matchID: match, event: try RemoteText.TextEvent(
            kind: .edit, target: randomBytes(16), body: Data("x".utf8))))
        XCTAssertEqual(try alice.chat.pendingOutbox().count, 0)
        // And a normal send still works, on the unadvanced session.
        try alice.chat.send(matchID: match, event: try text("fine"))
        XCTAssertEqual(try deliver(alice, bob), [.displayed])
    }

    func testARelabelledEnvelopeIsRefusedAndChangesNothing() throws {
        let (alice, bob) = try matchedPair()
        try alice.chat.send(matchID: match, event: try text("hi"))
        let item = try XCTUnwrap(try alice.chat.pendingOutbox().first)
        XCTAssertEqual(try bob.chat.receive(matchID: match, outerID: randomBytes(16), envelope: item.envelope),
                       .rejected(code: LumosErrors.errPayloadMalformed))
        XCTAssertFalse(try bob.chat.hasSession(matchID: match), "a refused first message leaves no session behind")
        XCTAssertEqual(try bob.store.count(kind: .prekeySecret), 2, "nor consumes the one-time prekey")
        XCTAssertEqual(try bob.chat.receive(matchID: match, outerID: item.messageID, envelope: item.envelope), .displayed)
    }

    func testAConsumedOneTimePrekeyIsNeverAcceptedAgain() throws {
        let (alice, bob) = try matchedPair()
        try alice.chat.send(matchID: match, event: try text("first"))
        try deliver(alice, bob)
        // A second initiator (or a replayed first contact) names the same one-time prekey.
        let carol = ChatKeys()
        let other = randomBytes(16)
        try bob.chat.acceptConversation(matchID: other, peerIdentityEd: carol.edPub)
        var (ratchet, prekey) = try RemoteSession.initiate(own: try carol.identity(), bundle: try bob.keys.bundle(),
                                                           matchID: other, ephemeralPriv: randomBytes(32),
                                                           ratchetPriv: randomBytes(32))
        let id = ChatRecords.newMessageID()
        let env = try RemoteSession.seal(&ratchet, RemoteText.Plaintext(messageID: id, event: try text("me too")),
                                         prekey: prekey, now: 0)
        XCTAssertEqual(try bob.chat.receive(matchID: other, outerID: id, envelope: env),
                       .rejected(code: LumosErrors.errCryptoPrekeySignatureInvalid), "one-time means once (TRS-CRY-005)")
    }

    func testAnUnknownEventKindIsConsumedAndIgnoredAndTheSessionCarriesOn() throws {
        let (alice, bob) = try matchedPair()
        // A newer app's event kind, sealed on Alice's real session.
        let s = try ChatRecords.Session.decode(
            try XCTUnwrap(try alice.store.findByNaturalKey(kind: .ratchetSession, naturalKey: match).first))
        var ratchet = try DoubleRatchet.restoreState(s.ratchetState)
        let id = ChatRecords.newMessageID()
        let (header, ciphertext) = try ratchet.encrypt(id + Data([0x7F, 1, 2, 3]), now: 0)
        let env = Data([RemoteText.formatPrekey]) + s.prekeyFields + header.bytes + ciphertext
        try alice.store.putByNaturalKey(kind: .ratchetSession,
                                        plaintext: ChatRecords.Session(ratchetState: ratchet.exportState(),
                                                                       prekeyFields: s.prekeyFields).encoded,
                                        naturalKey: match)

        XCTAssertEqual(try bob.chat.receive(matchID: match, outerID: id, envelope: env), .ignored,
                       "an unknown kind is skipped safely (SRS-RMT-011)")
        XCTAssertEqual(try bob.chat.receive(matchID: match, outerID: id, envelope: env), .duplicate, "and consumed")
        try alice.chat.send(matchID: match, event: try text("still here"))
        XCTAssertEqual(try deliver(alice, bob), [.displayed])
        XCTAssertEqual(try bob.chat.messages(matchID: match).count, 1)
    }

    func testTheOutboxIsFirstInFirstOutEvenWithinOneMillisecond() throws {
        let (alice, bob) = try matchedPair()
        var sent: [Data] = []
        for i in 0..<5 {
            sent.append(try alice.chat.send(matchID: match, event: try text("m\(i)"))) // the clock does not move
        }
        XCTAssertEqual(try alice.chat.pendingOutbox().map(\.messageID), sent,
                       "the transport sends in the order the user did")
        XCTAssertEqual(try alice.chat.messages(matchID: match).map(\.messageID), sent,
                       "and the conversation shows them in that order")
        try deliver(alice, bob)
        XCTAssertEqual(try bob.chat.messages(matchID: match).map(\.messageID), sent,
                       "the recipient shows them in arrival order, which here is send order")
    }

    func testALateDeliveredReceiptNeverDowngradesRead() throws {
        let (alice, bob) = try matchedPair()
        try alice.chat.send(matchID: match, event: try text("hi"))
        let sent = try XCTUnwrap(try alice.chat.pendingOutbox().first)
        _ = try bob.chat.receive(matchID: match, outerID: sent.messageID, envelope: sent.envelope)
        XCTAssertEqual(try bob.chat.markRead(matchID: match, readReceiptsEnabled: true), 1)
        let delivered = try only(bob, .deliveryReceipt)
        let read = try only(bob, .readReceipt)
        // The relay hands Alice the read receipt first and the delivered one late.
        XCTAssertEqual(try alice.chat.receiveReceipt(matchID: match, outerID: read.messageID, kind: read.messageClass,
                                                     envelope: read.envelope), .applied)
        XCTAssertEqual(try alice.chat.messages(matchID: match).first?.state, .read)
        XCTAssertEqual(try alice.chat.receiveReceipt(matchID: match, outerID: delivered.messageID,
                                                     kind: delivered.messageClass, envelope: delivered.envelope),
                       .applied)
        XCTAssertEqual(try alice.chat.messages(matchID: match).first?.state, .read, "receipts never downgrade")
        XCTAssertEqual(try alice.chat.receiveReceipt(matchID: match, outerID: read.messageID, kind: read.messageClass,
                                                     envelope: read.envelope), .duplicate)
    }

    func testReadReceiptsGoOnlyWhenTheUserAllowsThem() throws {
        let (alice, bob) = try matchedPair()
        try alice.chat.send(matchID: match, event: try text("hi"))
        try deliver(alice, bob)
        XCTAssertEqual(try bob.chat.markRead(matchID: match, readReceiptsEnabled: false), 1,
                       "the message is read on Bob's side either way")
        XCTAssertEqual(try bob.chat.messages(matchID: match).first?.state, .read)
        XCTAssertEqual(try bob.chat.pendingOutbox().map(\.messageClass), [.deliveryReceipt],
                       "only the delivered receipt goes; no read receipt (SRS-RMT-014)")
    }

    func testARelabelledReceiptIsRefusedAndRolledBackSoTheTrueOneStillOpens() throws {
        let (alice, bob) = try matchedPair()
        try alice.chat.send(matchID: match, event: try text("hi"))
        let sent = try XCTUnwrap(try alice.chat.pendingOutbox().first)
        _ = try bob.chat.receive(matchID: match, outerID: sent.messageID, envelope: sent.envelope)
        let delivered = try only(bob, .deliveryReceipt)
        let forged = try alice.chat.receiveReceipt(matchID: match, outerID: delivered.messageID, kind: .readReceipt,
                                                   envelope: delivered.envelope)
        XCTAssertEqual(forged, .rejected(code: LumosErrors.errPayloadMalformed), "a delivered receipt relabelled as read")
        XCTAssertEqual(try alice.chat.messages(matchID: match).first?.state, .queued, "nothing changed")
        XCTAssertEqual(try alice.chat.receiveReceipt(matchID: match, outerID: delivered.messageID,
                                                     kind: delivered.messageClass, envelope: delivered.envelope),
                       .applied, "the refusal advanced no ratchet")
        XCTAssertEqual(try alice.chat.messages(matchID: match).first?.state, .delivered)
    }

    func testAReceiptCannotTouchThePeersOwnMessages() throws {
        let (alice, bob) = try matchedPair()
        try alice.chat.send(matchID: match, event: try text("hi"))
        let sent = try XCTUnwrap(try alice.chat.pendingOutbox().first)
        _ = try bob.chat.receive(matchID: match, outerID: sent.messageID, envelope: sent.envelope)
        let delivered = try only(bob, .deliveryReceipt)
        // Bob's device applies its own receipt to itself: the message it names
        // is Alice's on Bob's side, so nothing may change there.
        _ = try bob.chat.receiveReceipt(matchID: match, outerID: delivered.messageID, kind: delivered.messageClass,
                                        envelope: delivered.envelope)
        XCTAssertEqual(try bob.chat.messages(matchID: match).first?.state, .delivered)
        XCTAssertEqual(try bob.chat.messages(matchID: match).first?.outgoing, false)
    }

    func testRecordsRoundTripAndRefuseGarbage() throws {
        let m = ChatRecords.Message(messageID: ChatRecords.newMessageID(), matchID: match, outgoing: true,
                                    body: Data("hé".utf8), createdAtMillis: -5, sequence: 9, state: .localOnlyWaiting,
                                    edited: true, deleted: false, myReaction: Data("x".utf8), peerReaction: Data())
        XCTAssertEqual(try ChatRecords.Message.decode(m.encoded), m)
        let o = ChatRecords.OutboxItem(messageID: m.messageID, matchID: match, envelope: Data([1, 2]),
                                       createdAtMillis: 3, sequence: 4, attempts: 5, messageClass: .readReceipt)
        XCTAssertEqual(try ChatRecords.OutboxItem.decode(o.encoded), o)
        XCTAssertEqual(try ChatRecords.OutboxItem.decode(o.encoded.dropLast()).messageClass, .textMessage,
                       "an item written before the class byte decodes as the text it was")
        XCTAssertEqual(ChatRecords.rowID(Data(repeating: 0xAB, count: 16)), "abababab-abab-abab-abab-abababababab",
                       "lower-case, as the Android port's UUID.toString writes it")
        for garbage in [Data(), Data([2]), m.encoded.dropLast(), Data([1]) + Data(repeating: 0xFF, count: 40)] {
            XCTAssertThrowsError(try ChatRecords.Message.decode(Data(garbage)))
        }
        XCTAssertThrowsError(try ChatRecords.OutboxItem.decode(o.encoded + Data([0])), "trailing bytes are refused")
        XCTAssertThrowsError(try PrekeySecret(oneTime: true, id: 1, privateKey: Data(count: 31)))
    }

    func testDeleteForMeWaitsUntilTheMessageHasGoneAndNeverTouchesThePeer() throws {
        let (alice, bob) = try matchedPair()
        let id = try alice.chat.send(matchID: match, event: try text("see you there"))
        XCTAssertFalse(try alice.chat.deleteForMe(messageID: id), "still in the outbox: its sealed envelope would still be sent")
        try deliver(alice, bob)
        XCTAssertTrue(try alice.chat.deleteForMe(messageID: id))
        XCTAssertTrue(try alice.chat.messages(matchID: match).isEmpty, "gone from this device")
        XCTAssertEqual(try bob.chat.messages(matchID: match).count, 1, "the peer keeps their copy and is told nothing")
        XCTAssertTrue(try alice.chat.pendingOutbox().isEmpty, "and nothing was queued to tell them")
        XCTAssertEqual(try alice.chat.peerIdentity(matchID: match), bob.keys.edPub, "the pinned peer")
        XCTAssertFalse(try alice.chat.deleteForMe(messageID: randomBytes(16)), "an unknown id removes nothing")
    }

    func testOverTheDedupCapTheOldestIdsGoAndTheNewestStay() throws {
        let d = try ChatDevice(ChatKeys(), clock: clock)
        func put(_ expiresAt: Int64) throws -> String {
            let id = ChatRecords.rowID(ChatRecords.newMessageID())
            try d.store.put(kind: .messageDedup, id: id, plaintext: ChatRecords.encodeDedup(expiresAtMillis: expiresAt))
            return id
        }
        let oldest = try put(1_000), older = try put(2_000), newer = try put(3_000), newest = try put(4_000)
        XCTAssertEqual(try d.store.transaction { try ChatSessions.trimDedup(d.store, cap: 4) }, 0, "at the cap: untouched")
        XCTAssertEqual(try d.store.transaction { try ChatSessions.trimDedup(d.store, cap: 2) }, 2)
        XCTAssertNil(try d.store.get(kind: .messageDedup, id: oldest))
        XCTAssertNil(try d.store.get(kind: .messageDedup, id: older))
        XCTAssertNotNil(try d.store.get(kind: .messageDedup, id: newer))
        XCTAssertNotNil(try d.store.get(kind: .messageDedup, id: newest))
    }
}
