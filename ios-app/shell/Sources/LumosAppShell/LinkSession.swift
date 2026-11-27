/// Lumos - LumosAppShell / One discovery link: both HELLOs, bounded, over frames.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-PROTO-017, TRS-ENV-002, SRS-IDN-002, SRS-NBY-005, SRS-SAF-002
/// ADRs: ADR-002, ADR-008, ADR-013
/// Threading: serial-executor (the link's)
/// Notes: The Swift twin of the Android LinkSession, so both apps run one
/// tested handshake: open sends this side's HELLO frame; the peer's HELLO is
/// answered once with a HELLO_ACK; the ACK to our own HELLO is verified by
/// HelloExchange; DONE only when both are true, and only then is the Peer
/// (the one value PresenceTable accepts) available. A frame that doesn't
/// decode, a class other than HELLO or HELLO_ACK, a second HELLO or ACK
/// (replay), an unverifiable or mirrored ACK, or the handshake timer turns the
/// link FAILED with nothing granted, keeping only the stable code (or that it
/// timed out), never the peer's bytes. A HELLO or ACK from an identity the
/// person blocked (SRS-SAF-002) ends the link with nothing sent and no code
/// kept, so the blocked side gets no ACK to its own HELLO and never a card.

import Foundation
import LumosProtocol
import LumosProtocolGenerated

/// One discovery link's handshake.
public final class LinkSession {

    /// Which end of the GATT connection this side is (ADR-013 §3/§4).
    public enum Role: Sendable, Equatable {
        /// Connected out, writes to LINK_IN, hears LINK_OUT.
        case central
        /// Hosts the service, hears LINK_IN, notifies LINK_OUT.
        case peripheral
    }

    /// Where the link stands.
    public enum State: Sendable, Equatable {
        /// Not opened yet.
        case waiting
        /// Own HELLO sent; waiting for the peer's HELLO and the ACK to ours.
        case exchanging
        /// Both done.
        case done
        /// Ended without presence.
        case failed
    }

    /// This side's role.
    public let role: Role
    /// Where the link stands.
    public private(set) var state: State = .waiting
    /// The stable wire error code a refused link ended with.
    public private(set) var failure: String?
    /// True when the link ended because the handshake timer ran out.
    public private(set) var timedOut = false
    /// True when the link ended because the peer is one this side refuses
    /// (the block list, SRS-SAF-002): nothing was sent and no code is kept.
    public private(set) var refused = false

    private let exchange: HelloExchange
    private let outbox: (Data) -> Void
    private let nonces: () -> Data
    private let timeoutMillis: Int64
    private let refuses: (String) -> Bool
    private var openedAtMillis: Int64 = 0
    private var answered = false
    private var verified: HelloExchange.Peer?
    private var sequence: UInt32 = 0

    /// - Parameters:
    ///   - role: this side's end of the connection
    ///   - exchange: this side's HELLO exchange
    ///   - outbox: sends one encoded frame on the link
    ///   - nonces: fresh HELLO nonces
    ///   - timeoutMillis: how long the handshake may take once opened
    ///   - refuses: identities (card keys) never answered or recorded - the block list
    public init(role: Role, exchange: HelloExchange, outbox: @escaping (Data) -> Void,
                nonces: @escaping () -> Data, timeoutMillis: Int64,
                refuses: @escaping (String) -> Bool = { _ in false }) {
        self.role = role
        self.exchange = exchange
        self.outbox = outbox
        self.nonces = nonces
        self.timeoutMillis = timeoutMillis
        self.refuses = refuses
    }

    /// The verified peer once DONE.
    public var peer: HelloExchange.Peer? { state == .done ? verified : nil }

    /// Opens the link and sends this side's HELLO.
    /// - Parameter nowMillis: when
    public func open(nowMillis: Int64) {
        guard state == .waiting else { return }
        openedAtMillis = nowMillis
        state = .exchanging
        send(.hello, exchange.start(nonce: nonces()))
    }

    /// One whole frame arrived from the peer.
    /// - Parameters:
    ///   - blob: the frame bytes
    ///   - nowMillis: when
    public func onFrame(_ blob: Data, nowMillis: Int64) {
        if state == .done || state == .failed {
            return
        }
        if state == .waiting {
            // A peripheral can hear a HELLO before the subscription lands;
            // opening first keeps one order: our HELLO, then everything else.
            open(nowMillis: nowMillis)
        }
        if expired(nowMillis) {
            return
        }
        let frame: Frame
        do {
            frame = try FrameCodec.decode(blob)
        } catch let error as LumosCrypto.WireError {
            fail(error.code)
            return
        } catch {
            fail(LumosErrors.errFrameTruncated)
            return
        }
        switch frame.header.messageClass {
        case .hello?:
            onHello(frame.payload)
        case .helloAck?:
            onAck(frame.payload)
        default:
            // A discovery link carries the handshake and nothing else.
            fail(LumosErrors.errFrameUnknownType)
        }
    }

    /// Checks the handshake timer.
    /// - Parameter nowMillis: when
    public func tick(nowMillis: Int64) {
        _ = expired(nowMillis)
    }

    private func expired(_ nowMillis: Int64) -> Bool {
        if state == .exchanging && nowMillis - openedAtMillis >= timeoutMillis {
            fail(nil)
            timedOut = true
            return true
        }
        return state == .failed
    }

    private func onHello(_ hello: Data) {
        guard !answered else {
            // One HELLO per direction per link: a second is a replay attempt.
            fail(LumosErrors.errCryptoReplay)
            return
        }
        do {
            if refuses(PresenceTable.keyFor(peerId: Hello.peerId(try Hello.parseHello(hello).ed25519Public))) {
                refuse()
                return
            }
            send(.helloAck, try exchange.onHello(hello))
            answered = true
        } catch let error as LumosCrypto.WireError {
            fail(error.code)
            return
        } catch {
            fail(LumosErrors.errHelloSignatureInvalid)
            return
        }
        finishIfDone()
    }

    private func onAck(_ ack: Data) {
        guard verified == nil else {
            fail(LumosErrors.errCryptoReplay)
            return
        }
        do {
            verified = try exchange.onAck(ack)
        } catch let error as LumosCrypto.WireError {
            fail(error.code)
            return
        } catch {
            fail(LumosErrors.errHelloSignatureInvalid)
            return
        }
        if let peer = verified, refuses(PresenceTable.keyFor(peerId: peer.id)) {
            refuse()
            return
        }
        finishIfDone()
    }

    private func refuse() {
        refused = true
        fail(nil)
    }

    private func finishIfDone() {
        if state == .exchanging && answered && verified != nil {
            state = .done
        }
    }

    private func fail(_ code: String?) {
        state = .failed
        failure = code
        verified = nil
    }

    private func send(_ kind: MessageClass, _ payload: Data) {
        guard state != .failed else { return }
        sequence += 1
        var messageID = Data(count: 16)
        messageID[0] = UInt8((sequence >> 8) & 0xFF)
        messageID[1] = UInt8(sequence & 0xFF)
        let header = FrameHeader(type: kind.rawValue, sessionID: Data(count: 16), messageID: messageID,
                                 sequence: sequence)
        do {
            outbox(try FrameCodec.encode(header: header, payload: payload))
        } catch let error as LumosCrypto.WireError {
            fail(error.code)
        } catch {
            fail(LumosErrors.errFrameUnknownType)
        }
    }
}
