/// Lumos - LumosAppShell / The person's chat settings: whether read receipts are shared.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-RMT-014, TRS-LOC-001
/// ADRs: ADR-003
/// Threading: serial-executor (the store's)
/// Notes: The Swift twin of the Android ChatSettings, byte for byte: one sealed
/// chat_settings row (schema version 8). Read receipts can be turned off
/// without touching delivery receipts, which never depend on this
/// (SRS-RMT-014). With no row the product default applies (on, as before
/// this type; the owner may change it). A row this build cannot read reads as
/// OFF, so a person who chose to stop sharing is never turned back on by a
/// format change.

import Foundation

/// The chat settings the person controls.
public final class ChatSettings {

    /// Read receipts with no stored choice.
    public static let readReceiptsDefault = true

    /// The single-row identifier.
    static let settingsID = "self"

    private static let versionLine = "lumos-chat-settings/1"
    private static let readReceiptsOn = "read_receipts=on"
    private static let readReceiptsOff = "read_receipts=off"

    private let store: LocalStore

    /// - Parameter store: the opened local store
    public init(store: LocalStore) {
        self.store = store
    }

    /// Whether read receipts go to the people this person talks to: the
    /// stored choice, the default with none, off when unreadable.
    public func readReceipts() throws -> Bool {
        guard let raw = try store.get(kind: .chatSettings, id: Self.settingsID) else {
            return Self.readReceiptsDefault
        }
        let lines = (String(bytes: raw, encoding: .utf8) ?? "").components(separatedBy: "\n")
        return lines.count >= 2 && lines[0] == Self.versionLine && lines[1] == Self.readReceiptsOn
    }

    /// Records the person's read-receipt choice.
    /// - Parameters:
    ///   - enabled: true to share read receipts
    ///   - atUtcMillis: when the person chose it
    public func setReadReceipts(_ enabled: Bool, atUtcMillis: Int64) throws {
        let payload = "\(Self.versionLine)\n\(enabled ? Self.readReceiptsOn : Self.readReceiptsOff)\n\(atUtcMillis)\n"
        try store.put(kind: .chatSettings, id: Self.settingsID, plaintext: Data(payload.utf8))
    }
}
