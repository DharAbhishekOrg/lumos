/// Lumos - ios-app/shell / Storing, replacing and resetting the device identity.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-IDN-001, SRS-IDN-003, SRS-ONB-006, SRS-DAT-002
/// ADRs: ADR-003, ADR-008, ADR-010
/// Threading: serial-executor
/// Notes: The Android counterparts are IdentityCodec, IdentityRepository and
/// IdentityReset. The payload format `lumos-identity/1` is shared, because a
/// database written by one platform is read by the other in the tests that
/// prove the two agree.
///
/// `replace` is what an identity reset and a recovery restore both go through,
/// deliberately the same method: SRS-ONB-007 says restore creates a new
/// identity and SRS-IDN-003 says a reset does, and if those were two code paths
/// one of them would eventually keep the old keys.
///
/// The limit of a reset, stated rather than glossed: the previous identity's
/// private keys existed only inside one KEK-wrapped envelope in one row, and
/// this overwrites that row. The old ciphertext may survive in SQLite free
/// pages (threat model LT-14), and unlike emergency wipe a reset does NOT
/// destroy the KEK - it cannot, because the same KEK protects the profile the
/// user is keeping. Recovering the old private keys would therefore need both
/// the database file and an unlocked device with a live KEK. The answer for a
/// user who needs more than that is emergency wipe.

import Foundation

/// Encodes and decodes the stored identity record.
///
/// Base64 rather than raw bytes because the payload format is line-oriented
/// like every other codec here, and a tab-separated format that could contain a
/// tab is not a format. The envelope is already ciphertext when it arrives and
/// this codec does not open it; the row is then sealed again by the store's own
/// AEAD, so the private material is wrapped twice and the outer wrapping is the
/// one wipe destroys.
public enum IdentityCodec {

    /// Payload format version; an unknown one fails closed.
    static let versionLine = "lumos-identity/1"

    /// Lines a well-formed payload carries after its version line.
    static let fieldLines = 5

    /// Encodes an identity record.
    /// - Parameter record: the record
    /// - Returns: the payload bytes
    public static func encode(_ record: IdentityKeyRecord) -> Data {
        var out = versionLine + "\n"
        out += record.ed25519Public.base64EncodedString() + "\n"
        out += record.x25519Public.base64EncodedString() + "\n"
        out += record.bindingSignature.base64EncodedString() + "\n"
        out += record.wrappedPrivateKeys.base64EncodedString() + "\n"
        out += record.protection.rawValue + "\n"
        return Data(out.utf8)
    }

    /// Decodes an identity record.
    /// - Parameter payload: the payload bytes
    /// - Returns: the record
    /// - Throws: ``StoreError/unreadable`` on an unknown version or a short payload
    public static func decode(_ payload: Data) throws -> IdentityKeyRecord {
        let lines = String(decoding: payload, as: UTF8.self)
            .split(separator: "\n", omittingEmptySubsequences: false)
        guard lines.count > fieldLines, lines[0] == versionLine[...] else {
            throw StoreError.unreadable
        }
        guard let ed = Data(base64Encoded: String(lines[1])),
              let dh = Data(base64Encoded: String(lines[2])),
              let signature = Data(base64Encoded: String(lines[3])),
              let wrapped = Data(base64Encoded: String(lines[4])),
              let protection = KeyProtection(rawValue: String(lines[5])) else {
            throw StoreError.unreadable
        }
        return IdentityKeyRecord(ed25519Public: ed, x25519Public: dh,
                                 bindingSignature: signature, wrappedPrivateKeys: wrapped,
                                 protection: protection)
    }
}

/// Reads and replaces the device's identity record.
public struct IdentityRepository {

    /// The single-row identifier for the device identity.
    public static let identityId = "self"

    private let store: LocalStore

    /// Binds a repository to a store.
    /// - Parameter store: the opened local store
    public init(store: LocalStore) {
        self.store = store
    }

    /// Whether an identity exists on this device.
    /// - Returns: true when a record is stored
    /// - Throws: ``StoreError`` on engine failure
    public func exists() throws -> Bool {
        try store.get(kind: .identityKeyRecord, id: Self.identityId) != nil
    }

    /// Loads the identity.
    /// - Returns: the record, or nil when the device has no identity yet
    /// - Throws: ``StoreError`` if the record exists but cannot be opened
    public func load() throws -> IdentityKeyRecord? {
        guard let payload = try store.get(kind: .identityKeyRecord, id: Self.identityId) else {
            return nil
        }
        return try IdentityCodec.decode(payload)
    }

    /// Writes the identity, replacing whatever was there.
    ///
    /// Replacing rather than adding is the point: the previous identity's
    /// private keys were only readable through the envelope in the row this
    /// overwrites.
    ///
    /// - Parameter record: the identity to store
    /// - Throws: ``StoreError`` on low storage or engine failure
    public func replace(_ record: IdentityKeyRecord) throws {
        try store.put(kind: .identityKeyRecord, id: Self.identityId,
                      plaintext: IdentityCodec.encode(record))
    }
}

/// Replaces the device identity, after an explicit warning and confirmation.
public struct IdentityReset {

    /// What this device will lose, computed rather than asserted.
    public struct Consequences: Sendable, Equatable {
        /// What goes, in the words the warning screen uses.
        public let lost: [String]
        /// What stays, so the user is not frightened away from a safe action.
        public let kept: [String]
        /// What this action cannot promise, stated rather than omitted.
        public let residual: [String]
    }

    /// The outcome, carrying the proof that the identity actually changed.
    public struct ResetResult: Sendable, Equatable {
        /// The identity the device had, if any.
        public let previousEd25519Public: Data?
        /// The identity the device has now.
        public let newEd25519Public: Data

        /// Whether the identity changed.
        public var identityIsNew: Bool { previousEd25519Public != newEd25519Public }
    }

    private let identities: IdentityRepository
    private let kek: KekStore

    /// Assembles the reset.
    /// - Parameters:
    ///   - identities: where the device identity is stored
    ///   - kek: the key-encryption key the new envelope is wrapped under
    public init(identities: IdentityRepository, kek: KekStore) {
        self.identities = identities
        self.kek = kek
    }

    /// The warning this device must show before resetting (SRS-IDN-003).
    ///
    /// Computed, because the honest warning is not the same on every build.
    /// In WP-02 there are no matches or conversations stored, and saying so is
    /// truer than a general sentence about things that might not be there.
    ///
    /// - Returns: what will be lost, kept, and not promised
    /// - Throws: ``StoreError`` on engine failure
    public func consequences() throws -> Consequences {
        var lost = ["Your Lumos identity. The keys on this phone are destroyed and new ones "
                    + "are made, so you become a different person to everyone you have met here."]
        if try identities.exists() {
            lost.append("Any match you already have. Matches are tied to the old keys and "
                        + "cannot be moved to the new ones.")
            lost.append("The ability to carry on an existing encrypted conversation. Anyone "
                        + "you were talking to will need to verify you again from scratch.")
        }
        lost.append("Nothing else - this build has no matches or conversations stored yet, "
                    + "so there is nothing further to lose today.")

        let kept = [
            "Your profile, your photos and your settings.",
            "Your block list. Resetting your identity does not unblock anyone.",
            "Any recovery file you have already saved. It holds content, not keys, "
                + "and it still opens."
        ]
        let residual = [
            "Traces of the old keys may remain in unused parts of the phone's storage "
                + "until the phone overwrites them. They are encrypted, and they cannot be "
                + "read without this phone unlocked. If you need more than that, use "
                + "Emergency wipe instead."
        ]
        return Consequences(lost: lost, kept: kept, residual: residual)
    }

    /// Resets the identity (SRS-IDN-003).
    /// - Parameter confirmation: proof the warning was shown and phrase typed
    /// - Returns: the old and new public keys
    /// - Throws: ``NotConfirmed/malformedChallenge`` if the confirmation is for
    ///   another action, or ``StoreError`` on a store failure
    public func reset(
        confirmation: DeliberateConfirmation.Confirmation
    ) throws -> ResetResult {
        guard confirmation.action == .identityReset else {
            throw NotConfirmed.malformedChallenge
        }
        let before = try identities.load()
        let fresh = try IdentityKeyGenerator.generate(kek: kek)
        try identities.replace(fresh)
        return ResetResult(previousEd25519Public: before?.ed25519Public,
                           newEd25519Public: fresh.ed25519Public)
    }
}
