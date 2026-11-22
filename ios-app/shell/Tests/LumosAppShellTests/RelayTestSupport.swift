/// Lumos - LumosAppShellTests / An in-memory relay and registry, and a device that can be killed, for the relay tests.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-TST-001, TRS-TST-006, TRS-BCK-001
/// ADRs: ADR-001
/// Threading: test-only
/// Notes: The Swift side of the Android FakeRelay and RelayDevice. The fake
/// enforces what the backend enforces and the client depends on: challenges
/// are single-use and signatures verified; a credential maps to one routing id
/// and can be revoked (401); bundles and one-time keys are stored, one-time
/// ids may never repeat, claims hand each one-time key out once; a send needs
/// a capability whose proof the recipient allowed (otherwise accepted and
/// dropped, as the relay does); a resend with the same id and bytes is the
/// same success, stored once; a quota yields ERR_RELAY_MAILBOX_FULL; fetch
/// skips expired envelopes; ack deletes. It reads requests with Foundation's
/// JSONSerialization, an independent parser, so what the client writes is
/// checked by something other than its own reader. Faults are scripted per
/// request: OFFLINE (TransportUnreachable), DIE_BEFORE / DIE_AFTER (a
/// ProcessDeath error that is NOT TransportUnreachable, so it escapes the
/// client exactly as a killed process would, before or after the server
/// acted).

import CryptoKit
import Foundation
import LumosProtocol
import LumosProtocolGenerated
import XCTest
@testable import LumosAppShell

/// The process was killed mid-request.
struct ProcessDeath: Error {}

final class FakeRelay: HttpTransport {

    enum Fault { case offline, dieBefore, dieAfter }

    static let ttlSeconds: Int64 = 7 * 24 * 3600

    private let clock: TestClock
    private var challenges = Set<String>()
    private var deviceByCredential: [String: String] = [:]
    private var revoked = Set<String>()
    private var bundles: [String: [String: Any]] = [:]
    private var oneTime: [String: [[String: Any]]] = [:]
    private var usedOneTimeIDs: [String: Set<Int64>] = [:]
    private var proofs: [String: Set<String>] = [:]
    /// Mailbox per routing id: ordered ids and their envelopes.
    private var mailboxes: [String: (order: [String], envelopes: [String: [String: Any]])] = [:]
    private var quotas: [String: Int] = [:]
    private var faults: [(prefix: String, fault: Fault)] = []
    private var rateLimitRetryAfter: Int64 = -1
    private var serveExpiredEnvelopes = false
    private(set) var storedEver = 0
    private(set) var log: [String] = []
    var offline = false

    init(clock: TestClock) {
        self.clock = clock
    }

    // MARK: scripting

    func fault(_ requestPrefix: String, _ f: Fault) {
        faults.append((requestPrefix, f))
    }

    func rateLimitNextSend(retryAfter: Int64) {
        rateLimitRetryAfter = retryAfter
    }

    func quota(_ recipient: Data, envelopes: Int) {
        quotas[RelayHex.encode(recipient)] = envelopes
    }

    func serveExpired(_ on: Bool) {
        serveExpiredEnvelopes = on
    }

    func revokeDevice(credential: Data) {
        if let d = deviceByCredential[RelayHex.encode(credential)] { revoked.insert(d) }
    }

    func waiting(_ recipient: Data) -> Int {
        mailboxes[RelayHex.encode(recipient)]?.order.count ?? 0
    }

    func oneTimeIDs(_ routingID: Data) -> [Int64] {
        (oneTime[RelayHex.encode(routingID)] ?? []).compactMap { ($0["key_id"] as? NSNumber)?.int64Value }
    }

    func oneTimeLeft(_ routingID: Data) -> Int {
        oneTime[RelayHex.encode(routingID)]?.count ?? 0
    }

    func inject(recipient: Data, messageID: Data, senderProof: Data, cls: UInt8, payload: Data) {
        store(RelayHex.encode(recipient), [
            "message_id": RelayHex.encode(messageID), "class": Int(cls), "sender_proof": RelayHex.encode(senderProof),
            "created_at": now, "expires_at": now + FakeRelay.ttlSeconds, "payload": payload.base64EncodedString()
        ])
    }

    private var now: Int64 { clock.now() / 1000 }

    private func store(_ box: String, _ e: [String: Any]) {
        let id = e["message_id"] as? String ?? ""
        var m = mailboxes[box] ?? ([], [:])
        if m.envelopes[id] == nil { m.order.append(id) }
        m.envelopes[id] = e
        mailboxes[box] = m
    }

    // MARK: the transport

    func exchange(method: String, path: String, bearer: String?, jsonBody: String?) throws -> HttpResponse {
        let bare = path.split(separator: "?").first.map(String.init) ?? path
        log.append("\(method) \(bare)")
        if offline { throw TransportUnreachable() }
        var f: Fault?
        if let next = faults.first, "\(method) \(path)".hasPrefix(next.prefix) {
            faults.removeFirst()
            f = next.fault
        }
        if f == .offline { throw TransportUnreachable() }
        if f == .dieBefore { throw ProcessDeath() }
        let r = handle(method, "\(method) \(bare)", path, bearer, jsonBody)
        if f == .dieAfter { throw ProcessDeath() }
        return r
    }

    private static func json(_ status: Int, _ body: [String: Any]) -> HttpResponse {
        let data = (try? JSONSerialization.data(withJSONObject: body)) ?? Data()
        return HttpResponse(status: status, body: String(bytes: data, encoding: .utf8) ?? "")
    }

    private static func empty(_ status: Int) -> HttpResponse {
        HttpResponse(status: status, body: "")
    }

    private static func code(_ status: Int, _ c: String) -> HttpResponse {
        json(status, ["code": c])
    }

    private func handle(_ method: String, _ route: String, _ path: String, _ bearer: String?,
                        _ body: String?) -> HttpResponse {
        let input = body.flatMap { try? JSONSerialization.jsonObject(with: Data($0.utf8)) as? [String: Any] }
        if route == "POST /v1/auth/challenge" {
            let c = randomBytes(32)
            challenges.insert(RelayHex.encode(c))
            return FakeRelay.json(200, ["challenge": RelayHex.encode(c)])
        }
        if route == "POST /v1/devices" { return register(input ?? [:]) }
        guard let bearer, let device = deviceByCredential[bearer], !revoked.contains(device) else {
            return FakeRelay.code(401, "ERR_AUTH_REJECTED")
        }
        return route.contains("/prekeys") ? prekeyRoutes(device, route, input)
            : mailboxRoutes(device, route, path, input)
    }

    private func prekeyRoutes(_ device: String, _ route: String, _ input: [String: Any]?) -> HttpResponse {
        if route == "PUT /v1/prekeys" { return publish(device, input ?? [:]) }
        if route == "GET /v1/prekeys/status" {
            let n = oneTime[device]?.count ?? 0
            return FakeRelay.json(200, ["one_time_remaining": n,
                                        "needs_replenish": n < LumosConstants.prekeyReplenishThresholdDefault])
        }
        if route.hasPrefix("POST /v1/prekeys/"), route.hasSuffix("/claim") {
            return claim(String(route.dropFirst("POST /v1/prekeys/".count).dropLast("/claim".count)))
        }
        return FakeRelay.code(404, "NOT_FOUND")
    }

    private func mailboxRoutes(_ device: String, _ route: String, _ path: String,
                               _ input: [String: Any]?) -> HttpResponse {
        switch route {
        case "PUT /v1/mailbox/capabilities":
            proofs[device, default: []].insert(input?["proof"] as? String ?? "")
            return FakeRelay.empty(204)
        case _ where route.hasPrefix("DELETE /v1/mailbox/capabilities/"):
            proofs[device]?.remove(String(route.split(separator: "/").last ?? ""))
            return FakeRelay.empty(204)
        case "POST /v1/envelopes":
            return submit(input ?? [:])
        case "GET /v1/envelopes":
            return fetch(device, Int(path.components(separatedBy: "limit=").last ?? "") ?? 0)
        case "POST /v1/envelopes/ack":
            var n = 0
            for id in input?["message_ids"] as? [String] ?? [] where mailboxes[device]?.envelopes[id] != nil {
                mailboxes[device]?.envelopes[id] = nil
                mailboxes[device]?.order.removeAll { $0 == id }
                n += 1
            }
            return FakeRelay.json(200, ["acknowledged": n])
        default:
            return FakeRelay.code(404, "NOT_FOUND")
        }
    }

    private func register(_ input: [String: Any]) -> HttpResponse {
        let ch = input["challenge"] as? String ?? ""
        let key = Hex.decode(input["device_key"] as? String ?? "") ?? Data()
        let sig = Hex.decode(input["signature"] as? String ?? "") ?? Data()
        let msg = Data(LumosConstants.deviceAuthSigningInfoValue.utf8) + (Hex.decode(ch) ?? Data()) + key
        guard challenges.remove(ch) != nil,
              LumosCrypto.ed25519Verify(publicKeyRaw: key, message: msg, signature: sig) else {
            return FakeRelay.code(401, "ERR_AUTH_REJECTED")
        }
        let rid = RelayHex.encode(randomBytes(16))
        let cred = RelayHex.encode(randomBytes(32))
        deviceByCredential[cred] = rid
        return FakeRelay.json(201, ["routing_id": rid, "credential": cred])
    }

    private func publish(_ device: String, _ input: [String: Any]) -> HttpResponse {
        if let b = input["bundle"] as? [String: Any] {
            bundles[device] = b
        } else if bundles[device] == nil {
            return FakeRelay.code(400, "ERR_PREKEY_UPLOAD_INVALID")
        }
        if let keys = input["one_time"] as? [[String: Any]] {
            let stock = oneTime[device] ?? []
            guard stock.count + keys.count <= LumosConstants.oneTimePrekeysUploadedMax else {
                return FakeRelay.code(400, "ERR_PREKEY_UPLOAD_INVALID")
            }
            for k in keys {
                let id = (k["key_id"] as? NSNumber)?.int64Value ?? -1
                guard usedOneTimeIDs[device, default: []].insert(id).inserted else {
                    return FakeRelay.code(400, "ERR_PREKEY_UPLOAD_INVALID")
                }
            }
            oneTime[device] = stock + keys
        }
        return FakeRelay.empty(204)
    }

    private func claim(_ target: String) -> HttpResponse {
        guard let bundle = bundles[target], !revoked.contains(target) else { return FakeRelay.code(404, "NOT_FOUND") }
        var o: [String: Any] = ["bundle": bundle]
        if var stock = oneTime[target], !stock.isEmpty {
            o["one_time"] = stock.removeFirst()
            oneTime[target] = stock
        }
        return FakeRelay.json(200, o)
    }

    private func submit(_ input: [String: Any]) -> HttpResponse {
        if rateLimitRetryAfter >= 0 {
            defer { rateLimitRetryAfter = -1 }
            return HttpResponse(status: 429, body: "{\"code\":\"ERR_RELAY_RATE_LIMITED\"}",
                                retryAfterSeconds: rateLimitRetryAfter)
        }
        let recipient = input["recipient"] as? String ?? ""
        let proof = RelayHex.encode(LumosCrypto.sha256(Hex.decode(input["capability"] as? String ?? "") ?? Data()))
        guard proofs[recipient]?.contains(proof) == true else { return FakeRelay.empty(202) }
        let id = input["message_id"] as? String ?? ""
        let payload = input["payload"] as? String ?? ""
        if let existing = mailboxes[recipient]?.envelopes[id] {
            return existing["payload"] as? String == payload ? FakeRelay.empty(202)
                : FakeRelay.code(400, "ERR_CRYPTO_REPLAY")
        }
        if (mailboxes[recipient]?.order.count ?? 0) >= (quotas[recipient] ?? Int.max) {
            return FakeRelay.code(400, "ERR_RELAY_MAILBOX_FULL")
        }
        store(recipient, ["message_id": id, "class": (input["class"] as? NSNumber)?.intValue ?? -1,
                          "sender_proof": proof, "created_at": now, "expires_at": now + FakeRelay.ttlSeconds,
                          "payload": payload])
        storedEver += 1
        return FakeRelay.empty(202)
    }

    private func fetch(_ device: String, _ limit: Int) -> HttpResponse {
        var out: [[String: Any]] = []
        let box = mailboxes[device] ?? ([], [:])
        for id in box.order {
            if out.count == limit { break }
            guard let e = box.envelopes[id] else { continue }
            if serveExpiredEnvelopes || ((e["expires_at"] as? NSNumber)?.int64Value ?? 0) > now { out.append(e) }
        }
        return FakeRelay.json(200, ["envelopes": out])
    }
}

/// One installed app on a real SQLite file, with every relay part, that can be killed and rebooted.
final class RelayDevice {
    let ed = Curve25519.Signing.PrivateKey()
    let dhPriv = randomBytes(32)
    let file: URL
    private let databaseKey: Data
    private let clock: TestClock
    private let transport: HttpTransport
    private(set) var store: LocalStore!
    private(set) var chat: ChatStore!
    private(set) var routes: RelayRoutes!
    private(set) var account: RelayAccount!
    private(set) var prekeys: PrekeyPublisher!
    private(set) var sync: RelaySync!
    private(set) var sessions: RelaySessions!
    private(set) var exchange: RouteExchange!

    var edPub: Data { ed.publicKey.rawRepresentation }

    init(clock: TestClock, transport: HttpTransport) throws {
        self.clock = clock
        self.transport = transport
        file = FileManager.default.temporaryDirectory.appendingPathComponent("lumos-relay-\(UUID().uuidString).db")
        databaseKey = try DataKeyHierarchy.create(kek: SoftwareKekStore()).deriveKey(for: .database)
        try boot()
    }

    deinit {
        try? FileManager.default.removeItem(at: file)
    }

    func sign(_ message: Data) throws -> Data {
        try ed.signature(for: message)
    }

    private func identity() throws -> RemoteSession.Identity {
        let binding = try sign(Data(LumosConstants.x3dhIdentityBindingInfoValue.utf8) + X3DH.publicKey(of: dhPriv))
        return RemoteSession.Identity(identityEd: edPub, identityDHPriv: dhPriv, identityDHSig: binding)
    }

    func boot() throws {
        let clock = self.clock
        store = try LocalStore.open(database: try Sqlite3Database(path: file.path), databaseDomainKey: databaseKey,
                                    capacity: NoCapacityLimit(), now: { clock.now() })
        chat = ChatStore(store: store, own: try identity(), clock: { clock.now() }, ratchetKeys: { randomBytes(32) })
        routes = RelayRoutes(store: store)
        account = RelayAccount(store: store)
        let ed = self.ed
        prekeys = PrekeyPublisher(store: store, chat: chat, own: try identity(),
                                  signer: { try ed.signature(for: $0) })
        let anonymous = RelayClient.anonymous(transport)
        sync = RelaySync(chat: chat, routes: routes, account: account, prekeys: prekeys, anonymous: anonymous,
                         clock: { clock.now() })
        sessions = RelaySessions(chat: chat, routes: routes, account: account, anonymous: anonymous)
        exchange = RouteExchange(routes: routes, account: account)
    }

    /// Process death: drop every object and reboot from the same file.
    func kill() throws {
        store = nil
        chat = nil
        routes = nil
        account = nil
        prekeys = nil
        sync = nil
        sessions = nil
        exchange = nil
        try boot()
    }

    func routingID() throws -> Data {
        try XCTUnwrap(try account.current()).routingID
    }

    func credential() throws -> Data {
        try XCTUnwrap(try account.current()).credential
    }
}
