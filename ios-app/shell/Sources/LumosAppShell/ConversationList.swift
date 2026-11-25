/// Lumos - LumosAppShell / Every conversation on this device, summarised for the Matches and Chats lists.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-RMT-001, SRS-RMT-005
/// ADRs: ADR-003
/// Threading: serial-executor
/// Notes: The Swift port of the Android ConversationList. One read-only pass
/// over the store: each pinned conversation with its newest message and how
/// many of the peer's messages are still unread (delivered here, not yet read;
/// markRead is what turns them read). A conversation with no messages yet is a
/// new match, which the Matches tab shows; one with messages belongs to the
/// Chats tab. Nothing is decrypted beyond what ChatStore already opens per
/// call, and nothing is cached between calls.

import Foundation
import LumosProtocol

/// Summarises the device's conversations.
public struct ConversationList {

    /// One conversation, as a list row needs it.
    public struct Summary: Equatable, Sendable {
        /// The conversation.
        public let matchID: Data
        /// Its newest message, or nil for a new match with nothing said yet.
        public let last: ChatRecords.Message?
        /// How many of the peer's messages are delivered here and not yet read.
        public let unread: Int

        /// When the newest message was sent or received, or 0 for a new match.
        public var lastActivityMillis: Int64 { last?.createdAtMillis ?? 0 }
    }

    private let store: LocalStore
    private let chat: ChatStore

    /// Binds the list to the store the chat store lives in.
    /// - Parameters:
    ///   - store: the sealed local store
    ///   - chat: the chat store
    public init(store: LocalStore, chat: ChatStore) {
        self.store = store
        self.chat = chat
    }

    /// Every conversation, newest activity first. New matches (nothing said
    /// yet) come last, in a stable order.
    /// - Returns: the summaries
    /// - Throws: ``StoreError`` on engine failure or an unopenable row
    public func all() throws -> [Summary] {
        var out: [Summary] = []
        for entry in try store.allOf(kind: .conversation) {
            let matchID = try ChatRecords.Conversation.decode(entry.content).matchID
            let messages = try chat.messages(matchID: matchID)
            let unread = messages.filter { !$0.outgoing && $0.state == .delivered }.count
            out.append(Summary(matchID: matchID, last: messages.last, unread: unread))
        }
        return out.sorted {
            if $0.lastActivityMillis != $1.lastActivityMillis {
                return $0.lastActivityMillis > $1.lastActivityMillis
            }
            return ChatRecords.rowID($0.matchID) < ChatRecords.rowID($1.matchID)
        }
    }
}
