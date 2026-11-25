/// Lumos - ios-app/shell / The conversation screen's rows and decisions, on iOS.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-RMT-003, SRS-RMT-005, SRS-RMT-010, SRS-RMT-011, SRS-SAF-001, SRS-UX-011
/// ADRs: -
/// Threading: any
/// Notes: The Swift half of the Android ConversationRowsTest and
/// ConversationPresenterTest. Rows are built from records directly; the
/// presenter runs over a scripted source so each rule is one assertion, and
/// one test runs the real RuntimeConversationSource over a booted runtime.

import Foundation
import LumosProtocol
import XCTest
@testable import LumosAppShell

/// 2026-10-25 14:00 in Kolkata.
private let now: Int64 = 1_792_917_000_000
private let minute: Int64 = 60_000
private let kolkata = TimeZone(identifier: "Asia/Kolkata")!
private let britain = Locale(identifier: "en_GB")

private func message(_ n: UInt8, outgoing: Bool = false, _ body: String = "x", at: Int64 = now,
                     state: DeliveryMachine.State = .delivered, deleted: Bool = false,
                     replyTo: Data = Data()) -> ChatRecords.Message {
    ChatRecords.Message(messageID: Data(repeating: n, count: 16), matchID: Data(repeating: 0xAA, count: 16),
                        outgoing: outgoing, body: deleted ? Data() : Data(body.utf8), createdAtMillis: at,
                        sequence: Int64(n), state: state, deleted: deleted, replyTo: replyTo)
}

private func bubbles(_ rows: [ConversationRows.Row]) -> [ConversationRows.Bubble] {
    rows.compactMap { if case .bubble(let b) = $0 { return b } else { return nil } }
}

private func dividers(_ rows: [ConversationRows.Row]) -> [ConversationRows.DayDivider] {
    rows.compactMap { if case .divider(let d) = $0 { return d } else { return nil } }
}

final class ConversationRowsTests: XCTestCase {

    private func build(_ ms: [ChatRecords.Message]) -> [ConversationRows.Row] {
        ConversationRows.build(ms, nowMillis: now, zone: kolkata, locale: britain)
    }

    func testADividerOpensEachLocalDayTodayYesterdayThenTheDate() {
        let day: Int64 = 86_400_000
        let rows = build([message(1, at: now - 30 * day), message(2, at: now - day), message(3), message(4)])
        let ds = dividers(rows)
        XCTAssertEqual(ds.map(\.day), [.date, .yesterday, .today])
        XCTAssertTrue(ds[0].dateLabel.contains("2026") && ds[0].dateLabel.contains("Sep"), ds[0].dateLabel)
        XCTAssertEqual(ds[1].dateLabel, "", "the word comes from the screen's strings")
        XCTAssertEqual(rows.count, 7, "three dividers and four bubbles")
        if case .divider = rows[0] {} else { XCTFail("a divider comes first") }
    }

    func testRunsFromOneSenderCarryTheTimeOnlyOnTheirLastBubble() {
        let b = bubbles(build([message(1, at: now), message(2, at: now + minute), message(3, at: now + 2 * minute),
                               message(4, outgoing: true, at: now + 3 * minute),
                               message(5, at: now + 20 * minute)]))
        XCTAssertEqual(b.map(\.position), [.first, .middle, .last, .single, .single])
        XCTAssertEqual(b.map(\.showsMeta), [false, false, true, true, true])
        XCTAssertTrue(b[0].timeLabel.contains("14:00"), b[0].timeLabel)
    }

    func testTheTickNeverClaimsMoreThanTheStateSays() {
        let states: [DeliveryMachine.State] = [.queued, .sending, .localOnlyWaiting, .sent, .delivered, .read, .failed]
        let ticks = bubbles(build(states.enumerated().map { message(UInt8($0.offset + 1), outgoing: true,
                                                                    state: $0.element) })).map(\.tick)
        XCTAssertEqual(ticks, [.waiting, .waiting, .waiting, .sent, .delivered, .read, .failed])
    }

    func testADeletedMessageKeepsItsPlaceWithoutItsWords() {
        let b = bubbles(build([message(1, "secret", deleted: true), message(2, "after")]))
        XCTAssertEqual(b.count, 2)
        XCTAssertTrue(b[0].deleted)
        XCTAssertEqual(b[0].text, "")
    }

    func testAQuoteIsDrawnFromThisPhonesOwnCopy() {
        let answered = message(1, outgoing: true, "Coffee?")
        let gone = message(2, "never mind", deleted: true)
        let rows = build([answered, gone,
                          message(3, "Yes!", replyTo: answered.messageID),
                          message(4, "what?", replyTo: gone.messageID),
                          message(5, "hm", replyTo: Data(repeating: 9, count: 16)),
                          message(6, "x", deleted: true, replyTo: answered.messageID)])
        let b = bubbles(rows)
        XCTAssertEqual(b[2].quote, ConversationRows.Quote(kind: .text, messageID: answered.messageID, fromMe: true,
                                                          text: "Coffee?"))
        XCTAssertEqual(b[3].quote?.kind, .deleted)
        XCTAssertEqual(b[3].quote?.text, "", "a deleted message's words never come back in a quote")
        XCTAssertEqual(b[4].quote?.kind, .unavailable, "a message this phone never had")
        XCTAssertNil(b[5].quote, "a deleted reply shows no quote")
        XCTAssertNil(b[0].quote)
    }
}

/// A scripted source: records what the presenter asked for.
private final class ScriptedSource: ConversationSource {
    var allowed = true
    var outcome: ConversationPresenter.SendResult = .sent
    var sent: [RemoteText.TextEvent] = []
    var deleted: [Data] = []
    var ended: [ConversationSafety.Ending] = []
    var reads = 0

    func entryAllowed(_ matchID: Data) -> Bool { allowed }
    func messages(_ matchID: Data) -> [ChatRecords.Message] { [] }
    func send(_ matchID: Data, _ event: RemoteText.TextEvent) -> ConversationPresenter.SendResult {
        sent.append(event)
        return outcome
    }
    func deleteForMe(_ messageID: Data) -> Bool {
        deleted.append(messageID)
        return true
    }
    func markRead(_ matchID: Data) { reads += 1 }
    func end(_ matchID: Data, how: ConversationSafety.Ending) -> Bool {
        ended.append(how)
        return true
    }
}

final class ConversationPresenterTests: XCTestCase {

    private let source = ScriptedSource()
    private lazy var presenter = ConversationPresenter(source: source, matchID: Data(repeating: 0xAA, count: 16))

    private func bubble(_ m: ChatRecords.Message) -> ConversationRows.Bubble {
        bubbles(ConversationRows.build([m], nowMillis: now, zone: kolkata, locale: britain))[0]
    }

    func testOnlyRealTextThatFitsOneEnvelopeIsSent() {
        XCTAssertEqual(presenter.sendText("   \n "), .empty)
        XCTAssertEqual(presenter.sendText(String(repeating: "é", count: ConversationPresenter.maxTextBytes / 2 + 1)),
                       .tooLong, "bytes are counted, not characters")
        XCTAssertTrue(source.sent.isEmpty, "nothing was queued for either")
        XCTAssertEqual(presenter.sendText("  hi there \n"), .sent)
        XCTAssertEqual(source.sent.first?.body, Data("hi there".utf8), "the edges are trimmed")
        XCTAssertEqual(source.sent.first?.kind, .text)
        source.outcome = .notReady
        XCTAssertEqual(presenter.sendText("again"), .notReady, "the source's outcome is passed on")
    }

    func testWhatABubbleOffers() {
        XCTAssertEqual(ConversationPresenter.actions(for: bubble(message(1, "theirs"))),
                       [.reply, .react, .copy, .deleteForMe])
        XCTAssertEqual(ConversationPresenter.actions(for: bubble(message(2, outgoing: true, "mine", state: .read))),
                       [.reply, .react, .copy, .deleteForMe, .deleteForEveryone])
        XCTAssertEqual(ConversationPresenter.actions(for: bubble(message(3, outgoing: true, "waiting", state: .queued))),
                       [.reply, .react, .copy, .deleteForEveryone],
                       "a queued message cannot be deleted for me: its sealed envelope would still go")
        XCTAssertEqual(ConversationPresenter.actions(for: bubble(message(4, deleted: true))), [])
    }

    func testRepliesReactionsAndDeletesCarryOnlyTheTargetsID() {
        let theirs = bubble(message(1, "Coffee?"))
        XCTAssertEqual(presenter.sendReply("Yes!", to: theirs), .sent)
        XCTAssertEqual(source.sent.last?.kind, .reply)
        XCTAssertEqual(source.sent.last?.target, theirs.messageID)
        XCTAssertEqual(source.sent.last?.body, Data("Yes!".utf8), "the answered words are never sent again")
        XCTAssertEqual(presenter.react(theirs, "❤️"), .sent)
        XCTAssertEqual(presenter.react(theirs, "🍆"), .failed, "only the fixed reactions")
        XCTAssertEqual(presenter.deleteForEveryone(theirs), .failed, "only your own messages")
        let mine = bubble(message(2, outgoing: true, "oops", state: .sent))
        XCTAssertEqual(presenter.deleteForEveryone(mine), .sent)
        XCTAssertEqual(source.sent.last?.kind, .deleteForEveryone)
        XCTAssertTrue(presenter.deleteForMe(theirs))
        XCTAssertFalse(presenter.deleteForMe(bubble(message(3, outgoing: true, state: .sending))))
        XCTAssertEqual(source.deleted, [theirs.messageID])
    }

    func testUnmatchAndBlockGoToTheSafetyLayer() {
        XCTAssertTrue(presenter.end(.unmatch))
        XCTAssertTrue(presenter.end(.block))
        XCTAssertEqual(source.ended, [.unmatch, .block])
        presenter.markRead()
        XCTAssertEqual(source.reads, 1)
        source.allowed = false
        XCTAssertFalse(presenter.isOpen, "an ended conversation cannot be reopened")
    }
}

final class RuntimeConversationSourceTests: XCTestCase {

    private let clock = TestClock(1_700_000_000_000)
    private var file: URL!

    override func setUp() {
        file = FileManager.default.temporaryDirectory.appendingPathComponent("lumos-conv-\(UUID().uuidString).db")
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: file)
    }

    /// A real runtime over a real store, as the app boots it.
    private func runtime() throws -> ChatRuntime {
        let root = try KeyRoot.create(in: MemoryVault(), preferHardware: false)
        let clock = self.clock
        let store = try LocalStore.open(database: try Sqlite3Database(path: file.path),
                                        databaseDomainKey: try root.hierarchy.deriveKey(for: .database),
                                        capacity: NoCapacityLimit(), now: { clock.now() })
        let record = try IdentityKeyGenerator.generate(kek: root.kek)
        try IdentityRepository(store: store).replace(record)
        return ChatRuntime.assemble(store: store, identity: try ChatIdentity.unlock(record: record, kek: root.kek),
                                    transport: FakeRelay(clock: clock), clock: { clock.now() })
    }

    func testTheRealSourceOpensOnlyRealConversationsAndEndsThemForGood() throws {
        let rt = try runtime()
        var kicks = 0
        let source = RuntimeConversationSource(runtime: rt, kick: { kicks += 1 })
        let match = randomBytes(16)
        XCTAssertFalse(source.entryAllowed(match), "no such conversation")
        try rt.chat.acceptConversation(matchID: match, peerIdentityEd: ChatKeys().edPub)
        XCTAssertTrue(source.entryAllowed(match))
        let presenter = ConversationPresenter(source: source, matchID: match)
        XCTAssertEqual(presenter.sendText("hi"), .notReady, "no route or bundle yet: nothing is queued")
        XCTAssertTrue(try rt.chat.pendingOutbox().isEmpty)
        XCTAssertEqual(kicks, 0)
        XCTAssertTrue(presenter.end(.block))
        XCTAssertEqual(kicks, 1, "a block asks for a relay pass to revoke the route")
        XCTAssertFalse(presenter.isOpen, "a blocked conversation cannot be reopened")
        XCTAssertTrue(try rt.conversations.all().isEmpty, "and it leaves the lists")
    }
}
