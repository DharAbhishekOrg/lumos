/// Lumos - ios-app/shell / The Matches and Chats lists, on iOS.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-RMT-001, SRS-RMT-005, SRS-UX-002, SRS-UX-012
/// ADRs: ADR-003
/// Threading: any
/// Notes: The Swift half of the Android ConversationListTest and HomeListsTest.
/// ConversationList runs over two real chat stores, so "unread" means what a
/// delivered message really leaves behind; HomeLists is built from summaries
/// directly, so each rule is one assertion. Time labels are compared on the
/// parts every ICU version agrees on (the digits, the weekday, the year), not
/// on the spacing characters, which differ between platforms.

import Foundation
import LumosProtocol
import XCTest
@testable import LumosAppShell

final class ConversationListTests: XCTestCase {

    private let clock = TestClock(1_700_000_000_000)

    private func text(_ s: String) throws -> RemoteText.TextEvent {
        try RemoteText.TextEvent(kind: .text, target: nil, body: Data(s.utf8))
    }

    /// Alice starts a session with Bob on a match; Bob accepts it.
    private func pair(_ alice: ChatDevice, _ bob: ChatDevice, _ match: Data) throws {
        try bob.publishPrekeys()
        try bob.chat.acceptConversation(matchID: match, peerIdentityEd: alice.keys.edPub)
        try alice.chat.startSession(matchID: match, peerIdentityEd: bob.keys.edPub, bundle: try bob.keys.bundle(),
                                    ephemeralPriv: randomBytes(32), ratchetPriv: randomBytes(32))
    }

    /// Hands every queued text from one device to the other.
    private func deliver(_ from: ChatDevice, _ to: ChatDevice, _ match: Data) throws {
        for item in try from.chat.pendingOutbox() where item.messageClass == .textMessage {
            _ = try to.chat.receive(matchID: match, outerID: item.messageID, envelope: item.envelope)
            try from.chat.markAccepted(messageID: item.messageID)
        }
    }

    func testNewMatchesUnreadCountsAndTheNewestFirst() throws {
        let alice = try ChatDevice(ChatKeys(), clock: clock)
        let bob = try ChatDevice(ChatKeys(), clock: clock)
        let quiet = randomBytes(16)
        let talking = randomBytes(16)
        try bob.chat.acceptConversation(matchID: quiet, peerIdentityEd: alice.keys.edPub)
        try pair(alice, bob, talking)
        let list = ConversationList(store: bob.store, chat: bob.chat)
        XCTAssertEqual(try list.all().count, 2)
        XCTAssertTrue(try list.all().allSatisfy { $0.last == nil }, "two new matches, nothing said")

        clock.advance(1_000)
        try alice.chat.send(matchID: talking, event: try text("Hi!"))
        clock.advance(1_000)
        try alice.chat.send(matchID: talking, event: try text("I'm by the bar"))
        try deliver(alice, bob, talking)

        var all = try list.all()
        XCTAssertEqual(all[0].matchID, talking, "the chat with something said comes first")
        XCTAssertEqual(all[0].last?.body, Data("I'm by the bar".utf8))
        XCTAssertEqual(all[0].unread, 2)
        XCTAssertEqual(all[1].matchID, quiet)
        XCTAssertNil(all[1].last)

        _ = try bob.chat.markRead(matchID: talking, readReceiptsEnabled: false)
        XCTAssertEqual(try list.all()[0].unread, 0, "read once the conversation is opened")
        clock.advance(1_000)
        try bob.chat.send(matchID: talking, event: try text("On my way"))
        all = try list.all()
        XCTAssertEqual(all[0].unread, 0, "your own messages are never unread")
        XCTAssertEqual(all[0].last?.outgoing, true)
    }

    func testAnEndedConversationLeavesTheList() throws {
        let alice = try ChatDevice(ChatKeys(), clock: clock)
        let bob = try ChatDevice(ChatKeys(), clock: clock)
        let match = randomBytes(16)
        try pair(alice, bob, match)
        let list = ConversationList(store: bob.store, chat: bob.chat)
        XCTAssertEqual(try list.all().count, 1)
        _ = try ConversationRemoval.remove(bob.store, matchID: match)
        XCTAssertTrue(try list.all().isEmpty)
    }
}

final class HomeListsTests: XCTestCase {

    /// 2026-10-25 14:00 in Kolkata, a Sunday.
    private let now: Int64 = 1_792_917_000_000
    private let zone = TimeZone(identifier: "Asia/Kolkata")!
    private let locale = Locale(identifier: "en_GB")
    private let hour: Int64 = 3_600_000
    private let day: Int64 = 86_400_000

    private func id(_ n: UInt8) -> Data { Data(repeating: n, count: 16) }

    private func chat(_ n: UInt8, outgoing: Bool = false, _ body: String = "x", at: Int64? = nil,
                      deleted: Bool = false, unread: Int = 0) -> ConversationList.Summary {
        let message = ChatRecords.Message(messageID: randomBytes(16), matchID: id(n), outgoing: outgoing,
                                          body: deleted ? Data() : Data(body.utf8), createdAtMillis: at ?? now,
                                          sequence: 1, state: outgoing ? .sent : .delivered, deleted: deleted)
        return ConversationList.Summary(matchID: id(n), last: message, unread: unread)
    }

    private func build(_ s: [ConversationList.Summary]) -> HomeLists.Lists {
        HomeLists.build(s, nowMillis: now, zone: zone, locale: locale)
    }

    func testNewMatchesAndChatsGoToTheirOwnTabsInTheOrderGiven() {
        let lists = build([chat(1), chat(2), ConversationList.Summary(matchID: id(3), last: nil, unread: 0)])
        XCTAssertEqual(lists.matches.map(\.matchID), [id(3)])
        XCTAssertEqual(lists.chats.map(\.matchID), [id(1), id(2)], "newest first, as the list gave them")
        XCTAssertEqual(lists.chats[0].name, "", "no name is invented before profiles are stored (CS4)")
        XCTAssertEqual(lists.chats[0].id, ChatRecords.rowID(id(1)))
    }

    func testThePreviewIsTheirWordsYourWordsOrDeletedNeverTheDeletedText() {
        let rows = build([chat(1, "hello"), chat(2, outgoing: true, "hi back"), chat(3, "secret", deleted: true)]).chats
        XCTAssertEqual(rows[0].preview, .theirs)
        XCTAssertEqual(rows[0].text, "hello")
        XCTAssertEqual(rows[1].preview, .mine)
        XCTAssertEqual(rows[1].text, "hi back")
        XCTAssertEqual(rows[2].preview, .deleted)
        XCTAssertEqual(rows[2].text, "", "a deleted message's words never reach the list")
    }

    func testTheTimeReadsTodayYesterdayWeekdayThenDateInThePersonsZone() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        let today = calendar.startOfDay(for: Date(timeIntervalSince1970: TimeInterval(now) / 1000))
        func back(_ days: Int) -> Date { calendar.date(byAdding: .day, value: -days, to: today)! }
        XCTAssertEqual(HomeLists.when(day: today, today: today, calendar: calendar), .today)
        XCTAssertEqual(HomeLists.when(day: back(-1), today: today, calendar: calendar), .today,
                       "a clock that ran ahead reads as today")
        XCTAssertEqual(HomeLists.when(day: back(1), today: today, calendar: calendar), .yesterday)
        XCTAssertEqual(HomeLists.when(day: back(2), today: today, calendar: calendar), .weekday)
        XCTAssertEqual(HomeLists.when(day: back(6), today: today, calendar: calendar), .weekday)
        XCTAssertEqual(HomeLists.when(day: back(7), today: today, calendar: calendar), .date)

        // 01:30 on the 25th in Kolkata is 20:00 on the 24th in UTC: today for
        // the person, yesterday by UTC's calendar.
        let earlyThisMorning = now - 12 * hour - 30 * 60_000
        XCTAssertEqual(build([chat(1, at: earlyThisMorning)]).chats[0].when, .today, "the person's day, not UTC's")

        let yesterday = build([chat(1, at: now - day)]).chats[0]
        XCTAssertEqual(yesterday.when, .yesterday)
        XCTAssertEqual(yesterday.timeLabel, "", "the word comes from the screen's strings")
        let thursday = build([chat(1, at: now - 3 * day)]).chats[0]
        XCTAssertEqual(thursday.when, .weekday)
        XCTAssertEqual(thursday.timeLabel, "Thu")
        let old = build([chat(1, at: now - 30 * day)]).chats[0]
        XCTAssertEqual(old.when, .date)
        XCTAssertTrue(old.timeLabel.contains("25") && old.timeLabel.contains("Sep"), old.timeLabel)
        XCTAssertTrue(old.timeLabel.contains("2026"), old.timeLabel)
        let todayRow = build([chat(1, at: now)]).chats[0]
        XCTAssertTrue(todayRow.timeLabel.contains("14:00"), todayRow.timeLabel)
    }

    func testTheUnreadBadgeCapsAtNinetyNinePlus() {
        let rows = build([chat(1, unread: 99), chat(2, unread: 100)]).chats
        XCTAssertFalse(rows[0].unreadCapped)
        XCTAssertTrue(rows[1].unreadCapped)
        XCTAssertEqual(rows[1].unread, 100, "the real count is kept for the spoken sentence")
    }
}
