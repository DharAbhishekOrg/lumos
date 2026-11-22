/// Lumos - LumosAppShell / The registry and relay routes, as one typed client.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-BCK-001, TRS-BCK-003, TRS-BCK-005, TRS-BCK-007, SRS-RMT-002, SRS-RMT-003, SRS-RMT-009
/// ADRs: ADR-001
/// Threading: serial-executor
/// Notes: The Swift port of the Android RelayClient, HttpTransport,
/// RelayException and RelayTypes. One method per backend route, in the shapes
/// backend/internal/api serves: lowercase hex for keys and ids, standard
/// base64 for payloads. A response that does not parse is BUSY (the server is
/// misbehaving, retry later), never a crash (SRS-RMT-009). Statuses map to
/// ``RelayError/Kind`` in one place, so every caller retries the same way. A
/// fetch asks for at most ``RelayClient/fetchBatch`` envelopes. The relay never
/// sees a plaintext: what goes up is the already-sealed envelope (SRS-RMT-002).
/// Bodies are never logged.

import Foundation
import LumosProtocol
import LumosProtocolGenerated

/// One HTTP exchange; the socket implementation lives in the wiring slice.
public protocol HttpTransport: AnyObject {
    /// Sends one request and returns the status, body and Retry-After.
    /// - Throws: ``TransportUnreachable`` when the network could not be reached
    ///   (the client reads it as OFFLINE); anything else is a bug and propagates
    func exchange(method: String, path: String, bearer: String?, jsonBody: String?) throws -> HttpResponse
}

/// The network could not be reached, or the exchange was lost: the Android
/// port's IOException. Transports throw exactly this for every network
/// failure, so any other error is a defect and is not mistaken for OFFLINE.
public struct TransportUnreachable: Error, Sendable, Equatable {
    public init() {}
}

/// What came back.
public struct HttpResponse: Sendable, Equatable {
    /// The HTTP status.
    public let status: Int
    /// The body text, possibly empty.
    public let body: String
    /// Retry-After in seconds, or -1 when absent.
    public let retryAfterSeconds: Int64

    public init(status: Int, body: String, retryAfterSeconds: Int64 = -1) {
        self.status = status
        self.body = body
        self.retryAfterSeconds = retryAfterSeconds
    }
}

/// A relay call that did not succeed. Callers look at the kind, never a status.
public struct RelayError: Error, Equatable, Sendable {
    /// How to react.
    public enum Kind: Sendable, Equatable {
        /// No network: keep everything queued (SRS-RMT-003).
        case offline
        /// Rate limited, server trouble or a full mailbox: retry later.
        case busy
        /// The credential is gone: register again.
        case auth
        /// No such route or bundle.
        case notFound
        /// Permanent for this request.
        case refused
    }

    /// How to react.
    public let kind: Kind
    /// The stable registry code, for diagnostics only; never shown (TRS-UX-005).
    public let code: String
    /// The server's Retry-After, or -1.
    public let retryAfterSeconds: Int64

    static let offline = RelayError(kind: .offline, code: "", retryAfterSeconds: -1)
    static let malformedResponse = RelayError(kind: .busy, code: LumosErrors.errPayloadMalformed,
                                              retryAfterSeconds: -1)

    /// OFFLINE and BUSY keep work queued for the next attempt.
    public var retryable: Bool { kind == .offline || kind == .busy }
}

/// Immutable values exchanged with the relay.
public enum RelayTypes {
    /// A registration: the mailbox address and its bearer credential.
    public struct Registration: Sendable, Equatable {
        public let routingID: Data
        public let credential: Data

        public init(routingID: Data, credential: Data) {
            self.routingID = routingID
            self.credential = credential
        }
    }

    /// A one-time prekey's public half.
    public struct OneTimeKey: Sendable, Equatable {
        public let id: UInt32
        public let publicKey: Data
    }

    /// How many one-time prekeys the registry still holds.
    public struct PrekeyStatus: Sendable, Equatable {
        public let remaining: Int64
        public let needsReplenish: Bool
    }

    /// One submission.
    public struct Send: Sendable, Equatable {
        public let recipient: Data
        public let messageID: Data
        public let messageClass: UInt8
        public let capability: Data
        public let payload: Data
    }

    /// One fetched envelope.
    public struct Envelope: Sendable, Equatable {
        public let messageID: Data
        public let messageClass: UInt8
        public let senderProof: Data
        public let createdAt: Int64
        public let expiresAt: Int64
        public let payload: Data
    }
}

/// The client. Value-like: ``as(credential:)`` returns an authenticated copy.
public final class RelayClient {

    /// Envelopes per fetch, sized so the largest possible batch fits the transport's response cap.
    public static let fetchBatch = 10
    public static let routingIDSize = 16
    public static let messageIDSize = 16
    public static let keySize = 32
    static let signatureSize = 64

    private let http: HttpTransport
    private let bearer: String?

    private init(http: HttpTransport, bearer: String?) {
        self.http = http
        self.bearer = bearer
    }

    /// A client with no credential: challenge, register and nothing else.
    public static func anonymous(_ http: HttpTransport) -> RelayClient {
        RelayClient(http: http, bearer: nil)
    }

    /// The same transport, authenticated.
    public func `as`(credential: Data) -> RelayClient {
        RelayClient(http: http, bearer: RelayHex.encode(credential))
    }

    // MARK: registration

    /// A fresh registration challenge.
    public func challenge() throws -> Data {
        let o = try expectBody(try call("POST", "/v1/auth/challenge", nil), 200)
        return try read { try RelayHex.decode(o.string("challenge"), count: RelayClient.keySize) }
    }

    /// Registers a device key that signed the challenge.
    public func register(challenge: Data, devicePub: Data, signature: Data) throws -> RelayTypes.Registration {
        try requireLength(challenge, RelayClient.keySize)
        try requireLength(devicePub, RelayClient.keySize)
        try requireLength(signature, RelayClient.signatureSize)
        var w = RelayJson.Writer()
        w.put("challenge", RelayHex.encode(challenge))
        w.put("device_key", RelayHex.encode(devicePub))
        w.put("signature", RelayHex.encode(signature))
        let o = try expectBody(try call("POST", "/v1/devices", w.end()), 201)
        return try read {
            RelayTypes.Registration(routingID: try RelayHex.decode(o.string("routing_id"), count: RelayClient.routingIDSize),
                                    credential: try RelayHex.decode(o.string("credential"), count: RelayClient.keySize))
        }
    }

    /// Revokes this device's own registration.
    public func revokeSelf() throws {
        try failUnless(try call("DELETE", "/v1/devices/self", nil), 204)
    }

    // MARK: prekeys

    /// Publishes a signed bundle and/or one-time keys.
    public func publishPrekeys(bundle: X3DH.Bundle?, oneTime: [RelayTypes.OneTimeKey]) throws {
        var w = RelayJson.Writer()
        if let b = bundle {
            var bw = RelayJson.Writer()
            bw.put("identity_key", RelayHex.encode(b.identityEd))
            bw.put("identity_dh", RelayHex.encode(b.identityDH))
            bw.put("identity_dh_sig", RelayHex.encode(b.identityDHSig))
            bw.put("spk_id", Int64(b.spkID))
            bw.put("spk", RelayHex.encode(b.spk))
            bw.put("spk_sig", RelayHex.encode(b.spkSig))
            w.putRaw("bundle", bw.end())
        }
        if !oneTime.isEmpty {
            let keys = try oneTime.map { k -> String in
                try requireLength(k.publicKey, RelayClient.keySize)
                var kw = RelayJson.Writer()
                kw.put("key_id", Int64(k.id))
                kw.put("public_key", RelayHex.encode(k.publicKey))
                return kw.end()
            }
            w.putRaw("one_time", RelayJson.array(keys))
        }
        try failUnless(try call("PUT", "/v1/prekeys", w.end()), 204)
    }

    /// How many one-time prekeys remain at the registry.
    public func prekeyStatus() throws -> RelayTypes.PrekeyStatus {
        let o = try expectBody(try call("GET", "/v1/prekeys/status", nil), 200)
        return try read {
            RelayTypes.PrekeyStatus(remaining: try o.integer("one_time_remaining"),
                                    needsReplenish: try o.bool("needs_replenish"))
        }
    }

    /// Claims a peer's bundle, consuming one of its one-time prekeys.
    /// - Returns: the bundle, or nil when the peer has none
    public func claim(routingID: Data) throws -> X3DH.Bundle? {
        try requireLength(routingID, RelayClient.routingIDSize)
        let r = try call("POST", "/v1/prekeys/\(RelayHex.encode(routingID))/claim", nil)
        if r.status == 404 { return nil }
        let o = try expectBody(r, 200)
        return try read {
            let b = try o.object("bundle")
            let k = try o.optObject("one_time")
            return X3DH.Bundle(identityEd: try RelayHex.decode(b.string("identity_key"), count: RelayClient.keySize),
                               identityDH: try RelayHex.decode(b.string("identity_dh"), count: RelayClient.keySize),
                               identityDHSig: try RelayHex.decode(b.string("identity_dh_sig"),
                                                                  count: RelayClient.signatureSize),
                               spkID: try RelayClient.uint32(b.integer("spk_id")),
                               spk: try RelayHex.decode(b.string("spk"), count: RelayClient.keySize),
                               spkSig: try RelayHex.decode(b.string("spk_sig"), count: RelayClient.signatureSize),
                               opkID: try k.map { try RelayClient.uint32($0.integer("key_id")) } ?? 0,
                               opk: try k.map { try RelayHex.decode($0.string("public_key"), count: RelayClient.keySize) })
        }
    }

    // MARK: mailbox

    /// Lets a capability's holder send to this mailbox.
    public func allow(proof: Data) throws {
        try requireLength(proof, RelayClient.keySize)
        var w = RelayJson.Writer()
        w.put("proof", RelayHex.encode(proof))
        try failUnless(try call("PUT", "/v1/mailbox/capabilities", w.end()), 204)
    }

    /// Stops a capability's holder from sending here (SRS-RMT-016).
    public func revoke(proof: Data) throws {
        try requireLength(proof, RelayClient.keySize)
        try failUnless(try call("DELETE", "/v1/mailbox/capabilities/\(RelayHex.encode(proof))", nil), 204)
    }

    /// Submits one sealed envelope.
    public func submit(_ s: RelayTypes.Send) throws {
        try requireLength(s.recipient, RelayClient.routingIDSize)
        try requireLength(s.messageID, RelayClient.messageIDSize)
        try requireLength(s.capability, RelayClient.keySize)
        var w = RelayJson.Writer()
        w.put("recipient", RelayHex.encode(s.recipient))
        w.put("message_id", RelayHex.encode(s.messageID))
        w.put("class", Int64(s.messageClass))
        w.put("capability", RelayHex.encode(s.capability))
        w.put("payload", s.payload.base64EncodedString())
        try failUnless(try call("POST", "/v1/envelopes", w.end()), 202)
    }

    /// Fetches up to ``fetchBatch`` waiting envelopes.
    public func fetch() throws -> [RelayTypes.Envelope] {
        let o = try expectBody(try call("GET", "/v1/envelopes?limit=\(RelayClient.fetchBatch)", nil), 200)
        return try read {
            let out = try o.objects("envelopes").map { e -> RelayTypes.Envelope in
                let cls = try e.integer("class")
                guard (0...0xFF).contains(cls) else { throw RelayJson.Malformed(detail: "class out of range") }
                guard let payload = Data(base64Encoded: try e.string("payload")) else {
                    throw RelayJson.Malformed(detail: "not base64")
                }
                return RelayTypes.Envelope(
                    messageID: try RelayHex.decode(e.string("message_id"), count: RelayClient.messageIDSize),
                    messageClass: UInt8(cls),
                    senderProof: try RelayHex.decode(e.string("sender_proof"), count: RelayClient.keySize),
                    createdAt: try e.integer("created_at"), expiresAt: try e.integer("expires_at"), payload: payload)
            }
            guard out.count <= RelayClient.fetchBatch else {
                throw RelayJson.Malformed(detail: "more envelopes than asked for")
            }
            return out
        }
    }

    /// Acknowledges fetched envelopes, which the relay then deletes.
    /// - Returns: how many the relay acknowledged
    @discardableResult
    public func ack(_ messageIDs: [Data]) throws -> Int64 {
        let ids = try messageIDs.map { id -> String in
            try requireLength(id, RelayClient.messageIDSize)
            return RelayHex.encode(id)
        }
        var w = RelayJson.Writer()
        w.putRaw("message_ids", RelayJson.stringArray(ids))
        let o = try expectBody(try call("POST", "/v1/envelopes/ack", w.end()), 200)
        return try read { try o.integer("acknowledged") }
    }

    // MARK: plumbing

    /// The largest fetch response a well-behaved relay can send.
    static func largestFetchBody() -> Int64 {
        let payload = 4 * ((Int64(LumosConstants.relayTextEnvelopeMaxBytesMax) + 2) / 3)
        let perEnvelope = payload + 2 * Int64(messageIDSize) + 2 * Int64(keySize) + 160
        return Int64(fetchBatch) * perEnvelope + 32
    }

    private func call(_ method: String, _ path: String, _ body: String?) throws -> HttpResponse {
        do {
            return try http.exchange(method: method, path: path, bearer: bearer, jsonBody: body)
        } catch is TransportUnreachable {
            throw RelayError.offline
        }
    }

    private func expectBody(_ r: HttpResponse, _ want: Int) throws -> RelayJson.Obj {
        try failUnless(r, want)
        do {
            return try RelayJson.parseObject(r.body)
        } catch {
            throw RelayError.malformedResponse
        }
    }

    private func failUnless(_ r: HttpResponse, _ want: Int) throws {
        guard r.status != want else { return }
        let code = RelayClient.codeOf(r.body)
        let kind: RelayError.Kind
        switch r.status {
        case 401: kind = .auth
        case 429, 500...: kind = .busy
        case _ where code == LumosErrors.errRelayMailboxFull: kind = .busy
        case 404: kind = .notFound
        default: kind = .refused
        }
        throw RelayError(kind: kind, code: code, retryAfterSeconds: r.retryAfterSeconds)
    }

    private static func codeOf(_ body: String) -> String {
        guard let c = try? RelayJson.parseObject(body).string("code"), (1...64).contains(c.utf16.count),
              c.utf16.allSatisfy({ (0x41...0x5A).contains($0) || $0 == 0x5F }) else { return "" }
        return c
    }

    private func read<T>(_ parse: () throws -> T) throws -> T {
        do {
            return try parse()
        } catch {
            throw RelayError.malformedResponse
        }
    }

    private static func uint32(_ v: Int64) throws -> UInt32 {
        guard let u = UInt32(exactly: v) else { throw RelayJson.Malformed(detail: "not a uint32") }
        return u
    }

    private func requireLength(_ b: Data, _ n: Int) throws {
        guard b.count == n else { throw RelayError(kind: .refused, code: LumosErrors.errPayloadMalformed,
                                                   retryAfterSeconds: -1) }
    }
}
