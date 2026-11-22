/// Lumos - LumosProtocol / The match identifier and machine, mirrored.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-MAT-003, SRS-MAT-004, SRS-MAT-005, SRS-MAT-007, SRS-MAT-014,
///             TRS-PAT-002
/// ADRs: ADR-002, ADR-008
/// Threading: any
/// Notes: The Go reference row for row, held to it by the committed timeline
/// vectors. MatchID: SHA-256(MATCH_ID_KDF_INFO | lower | higher) over the two
/// peer identifiers in lexicographic byte order, truncated to MATCH_ID_LEN -
/// symmetric, so both sides of an accept-accept race derive the same
/// identifier without a message, and the lower identifier is the initiator
/// tiebreak. MatchMachine: unlisted pairs absorb (same state, no effect);
/// no decline or ignore row transmits (SRS-MAT-004); the two establishing
/// effects are the only door (SRS-MAT-005).

import CryptoKit
import Foundation
import LumosProtocolGenerated

/// Derives the SRS-MAT-007 stable match identifier.
public enum MatchID {

    /// Derivation refusals: malformed inputs, never protocol surprises.
    public enum DerivationError: Error, Equatable {
        /// A peer identifier was not PEER_ID_LEN bytes.
        case badLength
        /// Both identifiers were the same peer.
        case samePeer
    }

    /// Derives the identifier; argument order does not matter.
    public static func derive(_ a: Data, _ b: Data) throws -> Data {
        guard a.count == LumosConstants.peerIDLenDefault,
              b.count == LumosConstants.peerIDLenDefault else {
            throw DerivationError.badLength
        }
        guard a != b else { throw DerivationError.samePeer }
        let (lower, higher) = lexicographicallyOrdered(a, b)
        var hasher = SHA256()
        hasher.update(data: Data(LumosConstants.matchIDKdfInfoValue.utf8))
        hasher.update(data: lower)
        hasher.update(data: higher)
        return Data(hasher.finalize()).prefix(LumosConstants.matchIDLenDefault)
    }

    /// The accept-accept race tiebreak: the lexicographically lower
    /// identifier acts as session initiator.
    public static func initiatorOnRace(_ a: Data, _ b: Data) -> Data {
        lexicographicallyOrdered(a, b).0
    }

    private static func lexicographicallyOrdered(_ a: Data, _ b: Data) -> (Data, Data) {
        for (x, y) in zip(a, b) where x != y {
            return x < y ? (a, b) : (b, a)
        }
        return a.count <= b.count ? (a, b) : (b, a)
    }
}

/// The SRS-MAT-008 shared verification token: SHA-256(MATCH_TOKEN_KDF_INFO |
/// match_id), first two 6-bit groups into the frozen ADR-009 emoji alphabet,
/// next 3 bits into the named colour palette. Both sides derive it from the
/// match identifier alone; the venue comparison is two screens side by side.
public struct MatchToken: Sendable, Equatable {

    /// The two alphabet positions (0..63).
    public let emojiIndices: (Int, Int)
    /// The palette position (0..7).
    public let colorIndex: Int

    /// The pair's glyphs from the frozen alphabet.
    public var emoji: (String, String) {
        (LumosConstants.safetyNumberEmojiAlphabetGlyphs[emojiIndices.0],
         LumosConstants.safetyNumberEmojiAlphabetGlyphs[emojiIndices.1])
    }

    /// The pulse colour's accessible name.
    public var colorName: String { LumosConstants.matchTokenColorsNames[colorIndex] }

    /// The pulse colour's displayed value.
    public var colorHex: String { LumosConstants.matchTokenColorsHex[colorIndex] }

    /// Derives the token for a match identifier.
    public static func derive(matchID: Data) throws -> MatchToken {
        guard matchID.count == LumosConstants.matchIDLenDefault else {
            throw MatchID.DerivationError.badLength
        }
        var hasher = SHA256()
        hasher.update(data: Data(LumosConstants.matchTokenKdfInfoValue.utf8))
        hasher.update(data: matchID)
        let sum = Array(hasher.finalize())
        let bits = Int(sum[0]) << 8 | Int(sum[1])
        return MatchToken(emojiIndices: ((bits >> 10) & 0x3F, (bits >> 4) & 0x3F),
                          colorIndex: (bits >> 1) & 0x07)
    }

    public static func == (lhs: MatchToken, rhs: MatchToken) -> Bool {
        lhs.emojiIndices == rhs.emojiIndices && lhs.colorIndex == rhs.colorIndex
    }
}

/// Class-3/4 consent-stage payload codecs: the Go reference byte for byte.
/// The response verdict alphabet is accept and unmatch, so a decline or
/// ignore is UNENCODABLE - SRS-MAT-004's silence enforced at the codec.
public enum InterestWire {

    /// The response alphabet. There is no decline on purpose.
    public enum Verdict: UInt8, Sendable {
        /// Mutual consent underway.
        case accept = 1
        /// The one deliberately visible ending (SRS-MAT-009).
        case unmatch = 2
    }

    /// A stable-coded consent-stage decode failure.
    public struct WireError: Error, Equatable {
        /// The stable error-registry code.
        public let code: String
        /// What was wrong, for the log line.
        public let why: String
    }

    private static let badPayload = "ERR_MATCH_BAD_PAYLOAD"
    private static let badVerdict = "ERR_MATCH_BAD_VERDICT"
    private static let requestVersion: UInt8 = 1
    private static let responseVersion: UInt8 = 1

    /// Builds a class-3 payload around the preview blob.
    public static func encodeRequest(preview: Data) throws -> Data {
        guard preview.count <= 0xFFFF else {
            throw WireError(code: badPayload,
                            why: "preview longer than the length prefix can carry")
        }
        var out = Data([requestVersion,
                        UInt8((preview.count >> 8) & 0xFF),
                        UInt8(preview.count & 0xFF)])
        out.append(preview)
        return out
    }

    /// Opens a class-3 payload.
    public static func decodeRequest(_ payload: Data) throws -> Data {
        let bytes = Array(payload)
        guard bytes.count >= 3 else {
            throw WireError(code: badPayload, why: "truncated request")
        }
        guard bytes[0] == requestVersion else {
            throw WireError(code: badPayload, why: "unknown request version \(bytes[0])")
        }
        let want = Int(bytes[1]) << 8 | Int(bytes[2])
        guard bytes.count == 3 + want else {
            throw WireError(code: badPayload, why: "length prefix disagrees with the bytes")
        }
        return Data(bytes[3...])
    }

    /// Builds a class-4 payload. The enum already keeps a decline out.
    public static func encodeResponse(_ verdict: Verdict) -> Data {
        Data([responseVersion, verdict.rawValue])
    }

    /// Opens a class-4 payload.
    public static func decodeResponse(_ payload: Data) throws -> Verdict {
        let bytes = Array(payload)
        guard bytes.count == 2 else {
            throw WireError(code: badPayload, why: "response must be two bytes")
        }
        guard bytes[0] == responseVersion else {
            throw WireError(code: badPayload, why: "unknown response version \(bytes[0])")
        }
        guard let verdict = Verdict(rawValue: bytes[1]) else {
            throw WireError(code: badVerdict,
                            why: "verdict \(bytes[1]) is outside the alphabet")
        }
        return verdict
    }
}

/// The SRS-MAT-006 pre-match gate, the Java table mirrored: conversation
/// classes pass only in matched, consent-stage classes in every state but
/// blocked, and session/mesh/venue/housekeeping classes are other gates'.
/// The three sets are disjoint and cover every MessageClass case - the test
/// walks allCases, so a new class cannot open a pre-match path by default.
public enum ConsentGate {

    /// Classes that exist only between matched people.
    public static let matchOnly: Set<MessageClass> = [
        .profileFull, .relayRoute, .textMessage, .deliveryReceipt, .readReceipt, .typing,
        .objectMeta, .objectChunk, .objectAck, .objectCancel, .voiceMeta
    ]

    /// The consent stage itself: open in every state but blocked.
    public static let consentStage: Set<MessageClass> = [
        .interestRequest, .interestResponse, .profilePreview
    ]

    /// Session, mesh, venue and housekeeping: other gates own these.
    public static let notThisGate: Set<MessageClass> = [
        .hello, .helloAck, .meshRelay, .storeForward, .venuePackageHint,
        .venueRoomEvent, .voucherToken, .heartbeat, .error
    ]

    /// Whether a class may flow with a peer in a state; unknown fails closed.
    public static func permits(_ messageClass: MessageClass,
                               _ state: MatchMachine.State) -> Bool {
        if matchOnly.contains(messageClass) { return state == .matched }
        if consentStage.contains(messageClass) { return state != .blocked }
        return notThisGate.contains(messageClass)
    }
}

/// The per-peer interest state machine (TRS Sec. 4.2).
public enum MatchMachine {

    /// Where one peer relationship stands, seen from this device.
    public enum State: String, Sendable {
        case none
        case requestedOut = "requested_out"
        case requestedIn = "requested_in"
        case bothRequested = "both_requested"
        case matched
        case declinedIn = "declined_in"
        case ignoredIn = "ignored_in"
        case unmatched
        case blocked
    }

    /// One typed input, local action or remote frame.
    public enum Event: String, Sendable {
        case localRequest = "local_request"
        case localAccept = "local_accept"
        case localDecline = "local_decline"
        case localIgnore = "local_ignore"
        case localBlock = "local_block"
        case localUnmatch = "local_unmatch"
        case remoteRequest = "remote_request"
        case remoteAccept = "remote_accept"
        case remoteUnmatch = "remote_unmatch"
    }

    /// The single instruction a step hands back.
    public enum Effect: String, Sendable {
        case none
        case sendRequest = "send_request"
        case acceptAndEstablish = "accept_and_establish"
        case matchEstablished = "match_established"
        case sendUnmatch = "send_unmatch"

        /// Whether this effect opens the SRS-MAT-005 door.
        public var establishes: Bool {
            self == .acceptAndEstablish || self == .matchEstablished
        }
    }

    /// Every pair that CHANGES anything; unlisted pairs absorb.
    private static let table: [State: [Event: (State, Effect)]] = [
        .none: [
            .localRequest: (.requestedOut, .sendRequest),
            .remoteRequest: (.requestedIn, .none),
            .localBlock: (.blocked, .none)
        ],
        .requestedOut: [
            .remoteRequest: (.bothRequested, .none),
            .remoteAccept: (.matched, .matchEstablished),
            .localBlock: (.blocked, .none)
        ],
        .requestedIn: [
            .localAccept: (.matched, .acceptAndEstablish),
            .localDecline: (.declinedIn, .none),
            .localIgnore: (.ignoredIn, .none),
            .localRequest: (.bothRequested, .sendRequest),
            .localBlock: (.blocked, .none)
        ],
        .bothRequested: [
            .localAccept: (.matched, .acceptAndEstablish),
            .remoteAccept: (.matched, .matchEstablished),
            // Declining THEIR request leaves OUR request standing.
            .localDecline: (.requestedOut, .none),
            .localIgnore: (.requestedOut, .none),
            .localBlock: (.blocked, .none)
        ],
        .matched: [
            .localUnmatch: (.unmatched, .sendUnmatch),
            .remoteUnmatch: (.unmatched, .none),
            .localBlock: (.blocked, .none)
        ],
        .declinedIn: [
            .remoteRequest: (.requestedIn, .none),
            .localRequest: (.requestedOut, .sendRequest),
            .localBlock: (.blocked, .none)
        ],
        .ignoredIn: [
            .localRequest: (.requestedOut, .sendRequest),
            .localBlock: (.blocked, .none)
        ],
        .unmatched: [
            .localRequest: (.requestedOut, .sendRequest),
            .remoteRequest: (.requestedIn, .none),
            .localBlock: (.blocked, .none)
        ],
        .blocked: [:]
    ]

    /// Applies one event. Unlisted pairs absorb: same state, no effect.
    public static func step(_ state: State, _ event: Event) -> (state: State, effect: Effect) {
        if let (next, effect) = table[state]?[event] {
            return (next, effect)
        }
        return (state, .none)
    }
}
