/// Lumos - LumosAppShell / What the Matches and Chats tabs show, decided off the device.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-RMT-005, SRS-UX-002, SRS-UX-012
/// ADRs: -
/// Threading: any
/// Notes: The Swift port of the Android HomeLists, so both apps file and label
/// rows by the same rules, tested on Linux and macOS. A conversation with
/// nothing said yet is a new match (Matches tab); one with messages is a chat
/// (Chats tab), newest first. A chat row previews its last message as the
/// person would read it: their words, "You: " before your own, or that it was
/// deleted. The time is the time of day today, "Yesterday", the weekday within
/// the week, then a date, all in the person's zone and locale. Unread counts
/// above 99 read "99+". Names are not stored until profile previews arrive
/// (CS4), so every row carries an empty name and the screen says the fallback;
/// nothing is invented.

import Foundation

/// Builds the rows of the Matches and Chats tabs.
public enum HomeLists {

    /// The highest unread count shown as a number; above it the badge reads "99+".
    public static let maxUnreadShown = 99

    /// Within this many days a chat's time reads as a weekday.
    static let weekdayWindowDays = 6

    /// How a chat's last message is previewed.
    public enum Preview: Sendable, Equatable {
        /// Their words.
        case theirs
        /// Your words, shown after "You: ".
        case mine
        /// The last message was deleted for everyone.
        case deleted
    }

    /// How a chat's time reads.
    public enum When: Sendable, Equatable {
        /// Today: the time of day in `timeLabel`.
        case today
        /// Yesterday, in words.
        case yesterday
        /// This week: the weekday in `timeLabel`.
        case weekday
        /// Older: the date in `timeLabel`.
        case date
    }

    /// A new match: someone you can say hi to.
    public struct MatchRow: Sendable, Equatable, Identifiable {
        /// A stable list key.
        public let id: String
        /// The conversation.
        public let matchID: Data
        /// The peer's name, empty until stored (CS4).
        public let name: String
    }

    /// A chat with something said.
    public struct ChatRow: Sendable, Equatable, Identifiable {
        /// A stable list key.
        public let id: String
        /// The conversation.
        public let matchID: Data
        /// The peer's name, empty until stored (CS4).
        public let name: String
        /// How the last message is previewed.
        public let preview: Preview
        /// The last message's words, empty when deleted.
        public let text: String
        /// How the time reads.
        public let when: When
        /// The time of day, weekday or date; empty for ``When/yesterday``.
        public let timeLabel: String
        /// How many of their messages are unread.
        public let unread: Int

        /// True when the badge reads "99+" rather than the count.
        public var unreadCapped: Bool { unread > HomeLists.maxUnreadShown }
    }

    /// Both tabs' rows.
    public struct Lists: Sendable, Equatable {
        /// New matches, nothing said yet.
        public let matches: [MatchRow]
        /// Chats, newest first.
        public let chats: [ChatRow]

        /// The empty lists.
        public static let empty = Lists(matches: [], chats: [])
    }

    /// Builds both tabs from the conversation summaries.
    /// - Parameters:
    ///   - summaries: every conversation, newest activity first
    ///   - nowMillis: now, UTC
    ///   - zone: the person's zone
    ///   - locale: the person's locale
    /// - Returns: the rows
    public static func build(_ summaries: [ConversationList.Summary], nowMillis: Int64,
                             zone: TimeZone, locale: Locale) -> Lists {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        calendar.locale = locale
        let today = calendar.startOfDay(for: date(nowMillis))
        var matches: [MatchRow] = []
        var chats: [ChatRow] = []
        for summary in summaries {
            let key = ChatRecords.rowID(summary.matchID)
            guard let message = summary.last else {
                matches.append(MatchRow(id: key, matchID: summary.matchID, name: ""))
                continue
            }
            let preview: Preview = message.deleted ? .deleted : message.outgoing ? .mine : .theirs
            let text = message.deleted ? "" : String(bytes: message.body, encoding: .utf8) ?? ""
            let sent = date(message.createdAtMillis)
            let when = when(day: calendar.startOfDay(for: sent), today: today, calendar: calendar)
            chats.append(ChatRow(id: key, matchID: summary.matchID, name: "", preview: preview, text: text,
                                 when: when, timeLabel: label(when, sent, zone: zone, locale: locale),
                                 unread: summary.unread))
        }
        return Lists(matches: matches, chats: chats)
    }

    /// Which way a day reads, relative to today. A day in the future (a clock
    /// that ran ahead) reads as today rather than as a date.
    static func when(day: Date, today: Date, calendar: Calendar) -> When {
        if day >= today {
            return .today
        }
        let days = calendar.dateComponents([.day], from: day, to: today).day ?? Int.max
        if days == 1 {
            return .yesterday
        }
        return days <= weekdayWindowDays ? .weekday : .date
    }

    private static func label(_ when: When, _ at: Date, zone: TimeZone, locale: Locale) -> String {
        let formatter = DateFormatter()
        formatter.timeZone = zone
        formatter.locale = locale
        switch when {
        case .today:
            formatter.dateStyle = .none
            formatter.timeStyle = .short
        case .yesterday:
            return ""
        case .weekday:
            formatter.setLocalizedDateFormatFromTemplate("EEE")
        case .date:
            formatter.dateStyle = .medium
            formatter.timeStyle = .none
        }
        return formatter.string(from: at)
    }

    private static func date(_ millis: Int64) -> Date {
        Date(timeIntervalSince1970: TimeInterval(millis) / 1000)
    }
}
