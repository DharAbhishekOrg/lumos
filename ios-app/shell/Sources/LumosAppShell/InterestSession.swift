/// Lumos - LumosAppShell / One peer's interest session over the nearby link, and the RELAY_ROUTE swap on it.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-MAT-001, SRS-MAT-002, SRS-MAT-004, SRS-MAT-005, SRS-MAT-006, SRS-RMT-001, TRS-ARCH-009
/// ADRs: ADR-002
/// Threading: serial-executor
/// Notes: The Swift port of the Android InterestSession and NearbyRouteSwap,
/// closing the iOS gap for the coordinator AT-005's logic half runs through.
/// Local actions and inbound canonical frames become typed machine events
/// (TRS-ARCH-009); the ledger decides and persists; the effect becomes at most
/// one outbound frame. SEND_REQUEST carries the REQUESTER-audience preview, an
/// accept carries ACCEPT, an unmatch carries UNMATCH, and a decline or ignore
/// carries nothing, because there is nothing to carry it in (SRS-MAT-004).
/// Every other class, in or out, goes through ConsentGate against the peer's
/// persisted state: no conversation frame leaves or lands before MATCHED
/// (SRS-MAT-006), whoever forged it. Malformed frames are refused with their
/// stable code and change no state. Conversation frames that pass the gate go
/// to the conversation sink, which is how the RELAY_ROUTE swap (CR-005)
/// reaches the relay layer.
///
/// NearbyRouteSwap sends this device's RELAY_ROUTE as soon as the two are
/// matched, and again only when the payload changes (a re-registration gives
/// a new routing id), and takes the peer's from the sink; RouteExchange
/// applies the gate a second time.

import Foundation
import LumosProtocol
import LumosProtocolGenerated

/// One peer's interest session.
public final class InterestSession {

    /// Where outbound frames go: the BLE frame channel on a device, a loopback in tests.
    public typealias Outbox = (Data) -> Void

    /// Takes a conversation frame's content; called only for MATCH_ONLY classes with the peer MATCHED.
    public typealias ConversationSink = (MessageClass, Data, MatchMachine.State) throws -> Void

    /// What happened to one inbound frame.
    public struct Inbound: Sendable, Equatable {
        /// Whether the frame's content may reach the user.
        public let delivered: Bool
        /// The stable code when refused.
        public let refusalCode: String?
        /// The peer's state after the frame.
        public let state: MatchMachine.State
    }

    private let ledger: InterestLedger
    private let peerKey: String
    private let requestStagePreview: Data
    private let outbox: Outbox
    private var conversation: ConversationSink = { _, _, _ in }
    private var sequence: UInt32 = 0

    /// The last request-stage preview this peer sent (SRS-MAT-002).
    public private(set) var lastReceivedPreview = Data()

    /// Binds the session.
    /// - Parameters:
    ///   - ledger: the persisted interest ledger
    ///   - peerKey: the peer's authenticated identifier, hex
    ///   - requestStagePreview: this user's REQUESTER-audience preview blob
    ///   - outbox: where outbound frames go
    public init(ledger: InterestLedger, peerKey: String, requestStagePreview: Data, outbox: @escaping Outbox) {
        self.ledger = ledger
        self.peerKey = peerKey
        self.requestStagePreview = requestStagePreview
        self.outbox = outbox
    }

    /// Applies a local action and sends whatever its effect names.
    /// - Returns: the ledger's outcome, rate limiting included
    @discardableResult
    public func local(_ event: MatchMachine.Event, nowMillis: Int64) throws -> InterestLedger.Outcome {
        let outcome = try ledger.apply(peerKey: peerKey, event: event, nowMillis: nowMillis)
        switch outcome.effect {
        case .sendRequest:
            try emit(.interestRequest, try InterestWire.encodeRequest(preview: requestStagePreview))
        case .acceptAndEstablish:
            try emit(.interestResponse, InterestWire.encodeResponse(.accept))
        case .sendUnmatch:
            try emit(.interestResponse, InterestWire.encodeResponse(.unmatch))
        default:
            // NONE and MATCH_ESTABLISHED transmit nothing. Declines and
            // ignores always land here: silence is the absence of a case.
            break
        }
        return outcome
    }

    /// Sends a conversation-class payload, if the gate allows it.
    /// - Returns: true when sent; false when the gate refused (not yet matched)
    @discardableResult
    public func sendConversation(_ messageClass: MessageClass, _ payload: Data) throws -> Bool {
        guard ConsentGate.permits(messageClass, try ledger.stateOf(peerKey: peerKey)) else { return false }
        try emit(messageClass, payload)
        return true
    }

    /// Handles one inbound frame from this peer.
    public func onFrame(_ encoded: Data, nowMillis: Int64) throws -> Inbound {
        let frame: Frame
        do {
            frame = try FrameCodec.decode(encoded)
        } catch let e as LumosCrypto.WireError {
            return Inbound(delivered: false, refusalCode: e.code, state: try state())
        }
        guard let mc = frame.header.messageClass, ConsentGate.permits(mc, try state()) else {
            return Inbound(delivered: false, refusalCode: LumosErrors.errMatchNotMatched, state: try state())
        }
        do {
            if mc == .interestRequest {
                lastReceivedPreview = try InterestWire.decodeRequest(frame.payload)
                return Inbound(delivered: true, refusalCode: nil,
                               state: try ledger.apply(peerKey: peerKey, event: .remoteRequest,
                                                       nowMillis: nowMillis).state)
            }
            if mc == .interestResponse {
                let event: MatchMachine.Event =
                    try InterestWire.decodeResponse(frame.payload) == .accept ? .remoteAccept : .remoteUnmatch
                return Inbound(delivered: true, refusalCode: nil,
                               state: try ledger.apply(peerKey: peerKey, event: event, nowMillis: nowMillis).state)
            }
        } catch let e as InterestWire.WireError {
            return Inbound(delivered: false, refusalCode: e.code, state: try state())
        }
        let current = try state()
        if ConsentGate.matchOnly.contains(mc) {
            try conversation(mc, frame.payload, current)
        }
        return Inbound(delivered: true, refusalCode: nil, state: current)
    }

    /// Routes conversation-class frames (the RELAY_ROUTE swap among them) to a consumer.
    public func onConversation(_ sink: @escaping ConversationSink) {
        conversation = sink
    }

    /// Where the relationship with this peer stands.
    public func state() throws -> MatchMachine.State {
        try ledger.stateOf(peerKey: peerKey)
    }

    private func emit(_ messageClass: MessageClass, _ payload: Data) throws {
        sequence &+= 1
        var messageID = Data(count: 16)
        messageID[0] = UInt8(truncatingIfNeeded: sequence >> 8)
        messageID[1] = UInt8(truncatingIfNeeded: sequence)
        let header = FrameHeader(type: messageClass.rawValue, sessionID: Data(count: 16), messageID: messageID,
                                 sequence: sequence)
        outbox(try FrameCodec.encode(header: header, payload: payload))
    }
}

/// CR-005 on a live session: the RELAY_ROUTE swap.
public final class NearbyRouteSwap {
    private let session: InterestSession
    private let exchange: RouteExchange
    private let matchID: Data
    private var lastOffered = Data()

    /// What the last peer RELAY_ROUTE did, or nil when none arrived yet.
    public private(set) var lastTaken: RouteExchange.Taken?

    /// Binds the swap to a session and takes over its conversation sink.
    public init(session: InterestSession, exchange: RouteExchange, matchID: Data) {
        self.session = session
        self.exchange = exchange
        self.matchID = matchID
        session.onConversation { [weak self] cls, payload, state in
            guard let self, cls == .relayRoute else { return }
            self.lastTaken = try self.exchange.take(matchID: self.matchID, state: state, payload: payload)
        }
    }

    /// Sends this device's route when matched and not already sent unchanged.
    /// - Returns: true when a RELAY_ROUTE frame left
    @discardableResult
    public func offerIfMatched() throws -> Bool {
        guard let offer = try exchange.offer(matchID: matchID, state: try session.state()), offer != lastOffered,
              try session.sendConversation(.relayRoute, offer) else { return false }
        lastOffered = offer
        return true
    }
}
