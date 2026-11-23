/// Lumos - LumosAppShell / Ending a conversation: unmatch or block, as one local transaction.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-MAT-009, SRS-MAT-010, SRS-RMT-016, SRS-SAF-001, SRS-SAF-002, SRS-NBY-013
/// ADRs: ADR-001, ADR-003
/// Threading: serial-executor
/// Notes: The Swift port of the Android ConversationSafety and
/// ConversationRemoval (CS2b). One transaction does everything that must not
/// half-happen: the conversation, its messages, its ratchet session and its
/// unsent queue are deleted, its relay route is marked ending, and for a block
/// the peer's identity joins the block list the Nearby feed already respects.
/// Nothing here touches the network: the next relay pass revokes the
/// capability this device gave the peer, retrying until it succeeds, and until
/// then an envelope under it matches no open route and is dropped unread
/// (SRS-RMT-016). The peer is not told either way. Evidence is kept only when
/// a reporting user chooses it (SRS-MAT-010), and report is CR-007, so nothing
/// is kept yet. The message-dedup ids stay until they expire: they hold only an
/// id and an expiry. Not here yet, by plan: ending the match record (CS4) and
/// refusing a blocked identity in the nearby Hello responder (SRS-SAF-002).

import Foundation
import LumosProtocol

/// Forgets one conversation on this device, inside the caller's transaction.
enum ConversationRemoval {

    /// Deletes the conversation, its messages, its session and its queued sends.
    /// - Returns: how many records were deleted
    @discardableResult
    static func remove(_ store: LocalStore, matchID: Data) throws -> Int {
        var n = 0
        for kind in [RecordKind.conversation, .chatMessage, .ratchetSession] {
            for e in try store.findEntriesByNaturalKey(kind: kind, naturalKey: matchID) {
                try store.delete(kind: kind, id: e.id)
                n += 1
            }
        }
        for e in try store.allOf(kind: .outboxItem) where try ChatRecords.OutboxItem.decode(e.content).matchID == matchID {
            try store.delete(kind: .outboxItem, id: e.id)
            n += 1
        }
        return n
    }
}

/// Unmatch and block, as one local transaction each.
public final class ConversationSafety {

    /// How a conversation ends.
    public enum Ending: Sendable, Equatable {
        /// End the match: the conversation is deleted and the peer can no longer reach this device.
        case unmatch
        /// Unmatch, and never show or accept this identity again.
        case block
    }

    private let store: LocalStore
    private let chat: ChatStore
    private let routes: RelayRoutes
    private let blocked: BlockedPeers
    private let clock: () -> Int64

    /// Binds the safety actions to the chat stack's parts.
    public init(store: LocalStore, chat: ChatStore, routes: RelayRoutes, clock: @escaping () -> Int64) {
        self.store = store
        self.chat = chat
        self.routes = routes
        self.blocked = BlockedPeers(store: store)
        self.clock = clock
    }

    /// The hex peer key a block is placed under: the identifier a nearby
    /// presence card carries for the same identity.
    public static func peerKey(of peerIdentityEd: Data) -> String {
        RelayHex.encode(Hello.peerId(peerIdentityEd))
    }

    /// Ends a conversation on this device.
    /// - Returns: true when ended; false when there was no such conversation (nothing changed)
    /// - Throws: ``StoreError`` on engine failure; nothing changed then
    @discardableResult
    public func end(matchID: Data, how: Ending) throws -> Bool {
        try store.transaction {
            guard let peer = try chat.peerIdentity(matchID: matchID) else { return false }
            if how == .block {
                try blocked.block(peerKey: Self.peerKey(of: peer), atUtcMillis: clock())
            }
            try ConversationRemoval.remove(store, matchID: matchID)
            try routes.markEnding(matchID: matchID)
            return true
        }
    }
}
