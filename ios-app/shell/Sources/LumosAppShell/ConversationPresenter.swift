/// Lumos - LumosAppShell / Every decision the conversation screen makes.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-RMT-003, SRS-RMT-005, SRS-RMT-010, SRS-RMT-011, SRS-SAF-001, SRS-UX-011, SRS-UX-012
/// ADRs: -
/// Threading: serial-executor
/// Notes: The Swift port of the Android ConversationPresenter and
/// ConversationSource, so the SwiftUI screen only draws what this returns and
/// every rule is tested on Linux and macOS: a message may be sent when it has
/// something besides whitespace and fits one envelope; which actions a bubble
/// offers (reply, react and copy unless deleted; delete for me unless it is
/// still waiting to go, because its sealed envelope would still be sent;
/// delete for everyone only for your own); and what a send's outcome means.
/// Unmatch and block end the conversation through ConversationSafety, and
/// the screen closes after. Reactions are a short fixed set.

import Foundation
import LumosProtocol
import LumosProtocolGenerated

/// What the conversation screen needs from the running app.
public protocol ConversationSource: AnyObject {
    /// Whether the conversation may be shown at all: it exists and has a peer.
    func entryAllowed(_ matchID: Data) -> Bool
    /// The messages, oldest first; empty when they cannot be read.
    func messages(_ matchID: Data) -> [ChatRecords.Message]
    /// Queues an event; never throws at the screen.
    func send(_ matchID: Data, _ event: RemoteText.TextEvent) -> ConversationPresenter.SendResult
    /// Deletes a message from this device only.
    func deleteForMe(_ messageID: Data) -> Bool
    /// Marks the peer's messages read.
    func markRead(_ matchID: Data)
    /// Unmatches or blocks.
    func end(_ matchID: Data, how: ConversationSafety.Ending) -> Bool
}

/// The conversation screen's logic.
public final class ConversationPresenter {

    /// Bytes reserved for everything in an envelope that is not the text.
    static let framingAllowanceBytes = 512

    /// The most UTF-8 bytes one message's text may have.
    public static let maxTextBytes = (MessageClassLimits.maxPayloadBytes[.textMessage] ?? 0) - framingAllowanceBytes

    /// The quick reactions, in the order they are offered.
    public static let reactions = ["❤️", "😂", "😮", "😢", "👍", "🙏"]

    /// What a bubble offers on a long press.
    public enum Action: Sendable, Equatable, Hashable {
        /// Answer it (CR-009).
        case reply
        /// One of ``reactions``.
        case react
        /// Copy its words.
        case copy
        /// Remove it from this phone only.
        case deleteForMe
        /// Remove it for both people.
        case deleteForEveryone
    }

    /// What a send came to.
    public enum SendResult: Sendable, Equatable {
        /// Queued; it goes when it can.
        case sent
        /// Nothing to send.
        case empty
        /// Over ``maxTextBytes``.
        case tooLong
        /// No session can start yet (no route or no prekey bundle).
        case notReady
        /// Refused or failed; nothing was queued.
        case failed
    }

    private let source: ConversationSource
    private let matchID: Data

    /// - Parameters:
    ///   - source: the chat stack
    ///   - matchID: the conversation
    public init(source: ConversationSource, matchID: Data) {
        self.source = source
        self.matchID = matchID
    }

    /// Whether the screen may show this conversation.
    public var isOpen: Bool { source.entryAllowed(matchID) }

    /// The rows to draw.
    public func rows(nowMillis: Int64, zone: TimeZone, locale: Locale) -> [ConversationRows.Row] {
        ConversationRows.build(source.messages(matchID), nowMillis: nowMillis, zone: zone, locale: locale)
    }

    /// Whether a draft may be sent.
    public static func canSend(_ draft: String) -> Bool { check(draft) == .sent }

    private static func check(_ draft: String) -> SendResult {
        let trimmed = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            return .empty
        }
        return trimmed.utf8.count > maxTextBytes ? .tooLong : .sent
    }

    private static func body(_ draft: String) -> Data {
        Data(draft.trimmingCharacters(in: .whitespacesAndNewlines).utf8)
    }

    /// Sends a text; leading and trailing whitespace is dropped.
    public func sendText(_ draft: String) -> SendResult {
        let ok = Self.check(draft)
        guard ok == .sent else { return ok }
        guard let event = try? RemoteText.TextEvent(kind: .text, target: nil, body: Self.body(draft)) else {
            return .failed
        }
        return source.send(matchID, event)
    }

    /// Sends a reply. Only the answered message's id travels; its words are never sent again.
    public func sendReply(_ draft: String, to answered: ConversationRows.Bubble) -> SendResult {
        let ok = Self.check(draft)
        guard ok == .sent else { return ok }
        guard Self.actions(for: answered).contains(.reply),
              let event = try? RemoteText.TextEvent(kind: .reply, target: answered.messageID, body: Self.body(draft))
        else { return .failed }
        return source.send(matchID, event)
    }

    /// The actions a bubble offers.
    public static func actions(for b: ConversationRows.Bubble) -> Set<Action> {
        guard !b.deleted else { return [] }
        var out: Set<Action> = [.reply, .react]
        if !b.text.isEmpty {
            out.insert(.copy)
        }
        let waiting = b.outgoing && (b.state == .queued || b.state == .sending)
        if !waiting {
            out.insert(.deleteForMe)
        }
        if b.outgoing {
            out.insert(.deleteForEveryone)
        }
        return out
    }

    /// Reacts to a message with one of ``reactions``.
    public func react(_ b: ConversationRows.Bubble, _ reaction: String) -> SendResult {
        guard Self.reactions.contains(reaction), Self.actions(for: b).contains(.react),
              let event = try? RemoteText.TextEvent(kind: .reaction, target: b.messageID, body: Data(reaction.utf8))
        else { return .failed }
        return source.send(matchID, event)
    }

    /// Deletes one of your own messages for both people.
    public func deleteForEveryone(_ b: ConversationRows.Bubble) -> SendResult {
        guard Self.actions(for: b).contains(.deleteForEveryone),
              let event = try? RemoteText.TextEvent(kind: .deleteForEveryone, target: b.messageID, body: nil)
        else { return .failed }
        return source.send(matchID, event)
    }

    /// Deletes a message from this device only.
    public func deleteForMe(_ b: ConversationRows.Bubble) -> Bool {
        Self.actions(for: b).contains(.deleteForMe) && source.deleteForMe(b.messageID)
    }

    /// Ends the conversation: unmatch or block. The screen closes after.
    public func end(_ how: ConversationSafety.Ending) -> Bool { source.end(matchID, how: how) }

    /// Marks the conversation read.
    public func markRead() { source.markRead(matchID) }
}

/// The real source: the booted chat runtime.
public final class RuntimeConversationSource: ConversationSource {

    private let runtime: ChatRuntime
    private let kick: () -> Void

    /// - Parameters:
    ///   - runtime: the booted chat runtime
    ///   - kick: asks for a relay pass soon (the app's relay job)
    public init(runtime: ChatRuntime, kick: @escaping () -> Void = {}) {
        self.runtime = runtime
        self.kick = kick
    }

    public func entryAllowed(_ matchID: Data) -> Bool {
        ((try? runtime.chat.peerIdentity(matchID: matchID)) ?? nil) != nil
    }

    public func messages(_ matchID: Data) -> [ChatRecords.Message] {
        (try? runtime.chat.messages(matchID: matchID)) ?? []
    }

    public func send(_ matchID: Data, _ event: RemoteText.TextEvent) -> ConversationPresenter.SendResult {
        guard let peer = (try? runtime.chat.peerIdentity(matchID: matchID)) ?? nil else { return .failed }
        do {
            guard try runtime.send(matchID: matchID, peerIdentityEd: peer, event: event) != nil else {
                return .notReady
            }
            kick()
            return .sent
        } catch is RelayError {
            return .notReady
        } catch {
            return .failed
        }
    }

    public func deleteForMe(_ messageID: Data) -> Bool {
        (try? runtime.chat.deleteForMe(messageID: messageID)) ?? false
    }

    public func markRead(_ matchID: Data) {
        // An unreadable choice shares nothing (the safe direction).
        let share = (try? runtime.settings.readReceipts()) ?? false
        if ((try? runtime.chat.markRead(matchID: matchID, readReceiptsEnabled: share)) ?? 0) > 0 {
            kick()
        }
    }

    public func end(_ matchID: Data, how: ConversationSafety.Ending) -> Bool {
        guard (try? runtime.safety.end(matchID: matchID, how: how)) ?? false else { return false }
        kick()
        return true
    }
}
