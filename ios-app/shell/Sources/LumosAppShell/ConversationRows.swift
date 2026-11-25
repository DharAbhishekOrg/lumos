/// Lumos - LumosAppShell / A conversation's messages as the rows a person reads.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-RMT-005, SRS-RMT-010, SRS-RMT-011, SRS-UX-011, TRS-UX-004, SRS-UX-014
/// ADRs: -
/// Threading: any
/// Notes: The Swift port of the Android ConversationRows, so both apps lay a
/// conversation out by the same tested rules: a day divider before the first
/// message of each local day (Today, Yesterday, then the date); bubbles
/// grouped into runs from one sender, so only the last of a run carries the
/// tail corner and the time; a deleted message keeps its place and says who
/// deleted it rather than vanishing (the other person may have read it). A
/// reply's quote is drawn from this device's own copy of the answered message
/// and is never sent (CR-009). Words come from the screen's strings through
/// the row kinds, never assembled here (SRS-UX-014).

import Foundation
import LumosProtocol

/// Builds the rows of one conversation.
public enum ConversationRows {

    /// A message from the same sender within this many minutes continues
    /// the run. A layout choice (the Android RUN_GAP_MINUTES), not a
    /// protocol value.
    static let runGapMinutes = 3

    /// Which day a divider names.
    public enum Day: Sendable, Equatable {
        /// The person's today.
        case today
        /// The day before.
        case yesterday
        /// Any other day: shown as the divider's date label.
        case date
    }

    /// Where a bubble sits in a run from one sender.
    public enum RunPosition: Sendable, Equatable {
        /// Alone.
        case single
        /// First of several.
        case first
        /// In the middle.
        case middle
        /// Last of several: carries the tail and the time.
        case last
    }

    /// What a quote can show (CR-009).
    public enum QuoteKind: Sendable, Equatable {
        /// The answered message's words.
        case text
        /// The answered message was deleted for everyone.
        case deleted
        /// This device deleted it for itself, never had it, or it is not in this conversation.
        case unavailable
    }

    /// The quoted line above a reply.
    public struct Quote: Sendable, Equatable {
        /// What it shows.
        public let kind: QuoteKind
        /// The answered message's id.
        public let messageID: Data
        /// True when this device wrote the answered message.
        public let fromMe: Bool
        /// The answered words, empty unless ``QuoteKind/text``.
        public let text: String
    }

    /// A day divider.
    public struct DayDivider: Sendable, Equatable {
        /// A stable key.
        public let key: String
        /// Which kind of day.
        public let day: Day
        /// The localized date, used when the day is ``Day/date``.
        public let dateLabel: String
    }

    /// One message bubble.
    public struct Bubble: Sendable, Equatable {
        /// The message's row id.
        public let key: String
        /// The message id.
        public let messageID: Data
        /// True when this device sent it.
        public let outgoing: Bool
        /// The text, empty when deleted.
        public let text: String
        /// True when deleted for everyone.
        public let deleted: Bool
        /// True when edited after sending.
        public let edited: Bool
        /// The localized time.
        public let timeLabel: String
        /// The delivery state (meaningful for outgoing).
        public let state: DeliveryMachine.State
        /// This device's reaction, empty for none.
        public let myReaction: String
        /// The peer's reaction, empty for none.
        public let peerReaction: String
        /// Where it sits in its run.
        public let position: RunPosition
        /// What this bubble answers, or nil (CR-009).
        public let quote: Quote?

        /// True when the time and the tick show under this bubble.
        public var showsMeta: Bool { position == .single || position == .last }

        /// The tick an outgoing bubble shows, so a screen draws it without
        /// depending on the protocol module.
        public var tick: Tick {
            switch state {
            case .queued, .sending, .localOnlyWaiting: return .waiting
            case .sent: return .sent
            case .delivered: return .delivered
            case .read: return .read
            case .failed: return .failed
            }
        }
    }

    /// The delivery tick under your own messages.
    public enum Tick: Sendable, Equatable {
        /// Not gone yet.
        case waiting
        /// Accepted by the transport: one tick, never "delivered".
        case sent
        /// On their phone.
        case delivered
        /// Read (only when they share read receipts).
        case read
        /// Gave up; can be retried.
        case failed
    }

    /// One row of the conversation.
    public enum Row: Sendable, Equatable, Identifiable {
        /// A day divider.
        case divider(DayDivider)
        /// A message.
        case bubble(Bubble)

        /// A key that is stable across refreshes.
        public var id: String {
            switch self {
            case .divider(let d): return d.key
            case .bubble(let b): return b.key
            }
        }
    }

    /// Draws a reply's quote from the conversation as this device holds it.
    /// - Returns: its quote, or nil when it is not a reply or is itself deleted
    static func quote(of message: ChatRecords.Message, byID: [String: ChatRecords.Message]) -> Quote? {
        guard message.isReply, !message.deleted else { return nil }
        guard message.replyTo.count == 16, let answered = byID[ChatRecords.rowID(message.replyTo)] else {
            return Quote(kind: .unavailable, messageID: message.replyTo, fromMe: false, text: "")
        }
        if answered.deleted {
            return Quote(kind: .deleted, messageID: message.replyTo, fromMe: answered.outgoing, text: "")
        }
        return Quote(kind: .text, messageID: message.replyTo, fromMe: answered.outgoing, text: utf8(answered.body))
    }

    /// Builds the rows.
    /// - Parameters:
    ///   - messages: the conversation, oldest first
    ///   - nowMillis: the current UTC time
    ///   - zone: the person's time zone
    ///   - locale: the person's locale
    /// - Returns: the rows, top to bottom
    public static func build(_ messages: [ChatRecords.Message], nowMillis: Int64, zone: TimeZone,
                             locale: Locale) -> [Row] {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        let time = DateFormatter()
        time.timeZone = zone
        time.locale = locale
        time.dateStyle = .none
        time.timeStyle = .short
        let date = DateFormatter()
        date.timeZone = zone
        date.locale = locale
        date.dateStyle = .medium
        date.timeStyle = .none
        let today = calendar.startOfDay(for: at(nowMillis))
        let byID = Dictionary(messages.map { (ChatRecords.rowID($0.messageID), $0) }, uniquingKeysWith: { $1 })
        var rows: [Row] = []
        var shownDay: Date?
        for (i, m) in messages.enumerated() {
            let sent = at(m.createdAtMillis)
            let day = calendar.startOfDay(for: sent)
            if day != shownDay {
                shownDay = day
                rows.append(.divider(divider(day, today: today, calendar: calendar, date: date)))
            }
            rows.append(.bubble(Bubble(key: ChatRecords.rowID(m.messageID), messageID: m.messageID,
                                       outgoing: m.outgoing, text: m.deleted ? "" : utf8(m.body),
                                       deleted: m.deleted, edited: m.edited, timeLabel: time.string(from: sent),
                                       state: m.state, myReaction: utf8(m.myReaction),
                                       peerReaction: utf8(m.peerReaction),
                                       position: position(messages, i, calendar: calendar),
                                       quote: quote(of: m, byID: byID))))
        }
        return rows
    }

    private static func divider(_ day: Date, today: Date, calendar: Calendar, date: DateFormatter) -> DayDivider {
        let parts = calendar.dateComponents([.year, .month, .day], from: day)
        let key = "day-\(parts.year ?? 0)-\(parts.month ?? 0)-\(parts.day ?? 0)"
        if day == today {
            return DayDivider(key: key, day: .today, dateLabel: "")
        }
        if let yesterday = calendar.date(byAdding: .day, value: -1, to: today), day == yesterday {
            return DayDivider(key: key, day: .yesterday, dateLabel: "")
        }
        return DayDivider(key: key, day: .date, dateLabel: date.string(from: day))
    }

    private static func position(_ ms: [ChatRecords.Message], _ i: Int, calendar: Calendar) -> RunPosition {
        let joinsPrevious = i > 0 && continues(ms[i - 1], ms[i], calendar: calendar)
        let joinsNext = i + 1 < ms.count && continues(ms[i], ms[i + 1], calendar: calendar)
        switch (joinsPrevious, joinsNext) {
        case (true, true): return .middle
        case (true, false): return .last
        case (false, true): return .first
        case (false, false): return .single
        }
    }

    /// Whether b continues a's run: same sender, same day, within the gap.
    private static func continues(_ a: ChatRecords.Message, _ b: ChatRecords.Message, calendar: Calendar) -> Bool {
        a.outgoing == b.outgoing
            && at(b.createdAtMillis).timeIntervalSince(at(a.createdAtMillis))
                <= TimeInterval(runGapMinutes) * Self.secondsPerMinute
            && calendar.isDate(at(a.createdAtMillis), inSameDayAs: at(b.createdAtMillis))
    }

    private static let secondsPerMinute: TimeInterval = 60

    private static func at(_ millis: Int64) -> Date {
        Date(timeIntervalSince1970: TimeInterval(millis) / 1000)
    }

    private static func utf8(_ b: Data) -> String {
        b.isEmpty ? "" : String(bytes: b, encoding: .utf8) ?? ""
    }
}
