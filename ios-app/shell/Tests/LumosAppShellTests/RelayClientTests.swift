/// Lumos - LumosAppShellTests / The strict JSON subset, hex, and every relay answer sorted by what the caller does next.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-BCK-001, SRS-RMT-002, SRS-RMT-003, SRS-RMT-009, TRS-TST-001
/// ADRs: ADR-001
/// Threading: test-only
/// Notes: The Swift side of the Android JsonTest and RelayClientTest. The
/// refusal list is the Android one, entry for entry, so the two platforms
/// refuse the same responses. What the writer writes is read back by
/// Foundation's JSONSerialization, an independent parser.

import Foundation
import LumosProtocol
import LumosProtocolGenerated
import XCTest
@testable import LumosAppShell

final class RelayJsonTests: XCTestCase {

    func testTheContractShapesRead() throws {
        let o = try RelayJson.parseObject(#" {"a":"x\"y\u0041\n", "n": -12, "t": true, "f": false,"#
            + #" "z": null, "o": {"k": 0}, "l": [{"m": 1}, {"m": 2}], "unknown": [1, "s"]} "#)
        XCTAssertEqual(try o.string("a"), "x\"yA\n")
        XCTAssertEqual(try o.integer("n"), -12)
        XCTAssertTrue(try o.bool("t"))
        XCTAssertFalse(try o.bool("f"))
        XCTAssertNil(try o.optObject("z"), "null reads as absent")
        XCTAssertNil(try o.optObject("missing"))
        XCTAssertEqual(try o.object("o").integer("k"), 0)
        XCTAssertEqual(try o.objects("l")[1].integer("m"), 2)
        XCTAssertTrue(o.has("unknown"), "an unknown field is kept and ignored, so a newer server can add one")
        XCTAssertEqual(try RelayJson.parseObject("{\"x\":\(Int64.max)}").integer("x"), Int64.max)
    }

    func testEverythingElseIsRefused() {
        let deep = String(repeating: "{\"a\":", count: RelayJson.maxDepth + 2) + "1"
            + String(repeating: "}", count: RelayJson.maxDepth + 2)
        let refused = [
            "", "[]", "\"top\"", "{", "{\"a\":1,}", "{\"a\" 1}", "{a:1}", "{\"a\":1} x", "{\"a\":1}{}",
            "{\"a\":1,\"a\":2}", "{\"a\":1.5}", "{\"a\":1e3}", "{\"a\":01}", "{\"a\":-}", "{\"a\":+1}",
            "{\"a\":99999999999999999999}", "{\"a\":\"\u{01}\"}", "{\"a\":\"\\x\"}", "{\"a\":\"\\u12\"}",
            "{\"a\":\"\\u١٢٣٤\"}", "{\"a\":tru}", "{\"a\":nul}", "{\"a\":NaN}", "{\"a\":\"open}", deep
        ]
        for s in refused {
            XCTAssertThrowsError(try RelayJson.parseObject(s), "must refuse: \(s)") {
                XCTAssertTrue($0 is RelayJson.Malformed)
            }
        }
    }

    func testAFieldOfTheWrongTypeIsRefusedWhereItIsRead() throws {
        let o = try RelayJson.parseObject(#"{"s":"x","n":1,"a":[1]}"#)
        XCTAssertThrowsError(try o.integer("s"))
        XCTAssertThrowsError(try o.string("n"))
        XCTAssertThrowsError(try o.bool("n"))
        XCTAssertThrowsError(try o.object("n"))
        XCTAssertThrowsError(try o.objects("a"), "an array of non-objects")
        XCTAssertThrowsError(try o.string("absent"))
    }

    func testWhatTheWriterWritesAnotherParserReadsBack() throws {
        let tricky = "q\"b\\s/\u{00}\u{1f}\u{7f} é"
        var inner = RelayJson.Writer()
        inner.put("k", 1)
        var w = RelayJson.Writer()
        w.put("s", tricky)
        w.put("n", -7)
        w.putRaw("a", RelayJson.stringArray(["x", "y"]))
        w.putRaw("o", RelayJson.array([inner.end()]))
        let g = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(w.end().utf8)) as? [String: Any])
        XCTAssertEqual(g["s"] as? String, tricky)
        XCTAssertEqual((g["n"] as? NSNumber)?.int64Value, -7)
        XCTAssertEqual((g["a"] as? [String])?[1], "y")
        XCTAssertEqual(((g["o"] as? [[String: Any]])?[0]["k"] as? NSNumber)?.intValue, 1)
        XCTAssertEqual(RelayJson.Writer().end(), "{}")
        XCTAssertEqual(try RelayJson.parseObject(w.end()).string("s"), tricky, "and so does our own reader")
    }

    func testHexIsStrictAsciiOfExactlyTheRightLength() throws {
        XCTAssertEqual(RelayHex.encode(Data([0, 0xFF, 0x10, 0xAB])), "00ff10ab")
        XCTAssertEqual(try RelayHex.decode("AB", count: 1), Data([0xAB]), "upper case is read")
        for bad in ["0", "000", "zz", "١٢", " 0", "+f"] {
            XCTAssertThrowsError(try RelayHex.decode(bad, count: 1), "must refuse: \(bad)")
        }
    }
}

final class RelayClientTests: XCTestCase {

    /// One recorded request.
    private struct Request {
        let method: String
        let path: String
        let bearer: String?
        let body: String?
    }

    /// A transport that records each request and answers from a script.
    private final class Scripted: HttpTransport {
        private(set) var requests: [Request] = []
        var answer = HttpResponse(status: 500, body: "")
        var offline = false

        func answer(_ status: Int, _ body: String, _ retryAfter: Int64 = -1) -> Scripted {
            answer = HttpResponse(status: status, body: body, retryAfterSeconds: retryAfter)
            return self
        }

        func exchange(method: String, path: String, bearer: String?, jsonBody: String?) throws -> HttpResponse {
            requests.append(Request(method: method, path: path, bearer: bearer, body: jsonBody))
            if offline { throw TransportUnreachable() }
            return answer
        }

        var last: Request { requests[requests.count - 1] }

        func lastJSON() throws -> [String: Any] {
            try XCTUnwrap(try JSONSerialization.jsonObject(with: Data((last.body ?? "").utf8)) as? [String: Any])
        }
    }

    private func aSend() -> RelayTypes.Send {
        RelayTypes.Send(recipient: randomBytes(16), messageID: randomBytes(16),
                        messageClass: MessageClass.textMessage.rawValue, capability: randomBytes(32),
                        payload: randomBytes(100))
    }

    private func submitAgainst(_ t: Scripted, file: StaticString = #filePath, line: UInt = #line) -> RelayError? {
        do {
            try RelayClient.anonymous(t).as(credential: randomBytes(32)).submit(aSend())
            XCTFail("expected a relay error", file: file, line: line)
            return nil
        } catch {
            return error as? RelayError
        }
    }

    func testEveryAnswerIsSortedByWhatTheCallerDoesNext() {
        struct Row {
            let status: Int
            let body: String
            let kind: RelayError.Kind
            init(_ status: Int, _ body: String, _ kind: RelayError.Kind) {
                self.status = status
                self.body = body
                self.kind = kind
            }
        }
        let table: [Row] = [
            Row(401, #"{"code":"ERR_AUTH_REJECTED"}"#, .auth),
            Row(429, #"{"code":"ERR_RELAY_RATE_LIMITED"}"#, .busy),
            Row(500, #"{"code":"INTERNAL"}"#, .busy),
            Row(503, "<html>gateway</html>", .busy),
            Row(400, #"{"code":"ERR_RELAY_MAILBOX_FULL"}"#, .busy),
            Row(400, #"{"code":"ERR_PAYLOAD_MALFORMED"}"#, .refused),
            Row(400, #"{"code":"ERR_CRYPTO_REPLAY"}"#, .refused),
            Row(413, "", .refused),
            Row(404, #"{"code":"NOT_FOUND"}"#, .notFound),
            Row(200, "", .refused)
        ]
        for row in table {
            XCTAssertEqual(submitAgainst(Scripted().answer(row.status, row.body))?.kind, row.kind,
                           "status \(row.status) \(row.body)")
        }
        let off = Scripted()
        off.offline = true
        let e = submitAgainst(off)
        XCTAssertEqual(e?.kind, .offline)
        XCTAssertEqual(e?.retryable, true)
        XCTAssertEqual(submitAgainst(Scripted().answer(429, "", 17))?.retryAfterSeconds, 17)
        XCTAssertEqual(submitAgainst(Scripted().answer(400, ""))?.retryable, false)
    }

    func testATransportDefectIsNotMistakenForOffline() {
        XCTAssertThrowsError(try RelayClient.anonymous(BrokenTransport()).challenge()) {
            XCTAssertTrue($0 is BrokenTransport.Bug, "only TransportUnreachable means OFFLINE")
        }
    }

    func testAnErrorCodeIsKeptOnlyWhenItLooksLikeOne() {
        XCTAssertEqual(submitAgainst(Scripted().answer(400, #"{"code":"ERR_CRYPTO_REPLAY"}"#))?.code,
                       LumosErrors.errCryptoReplay)
        XCTAssertEqual(submitAgainst(Scripted().answer(400, #"{"code":"<b>hi</b>"}"#))?.code, "",
                       "server text never reaches diagnostics verbatim")
        XCTAssertEqual(submitAgainst(Scripted().answer(400, "not json"))?.code, "")
    }

    private func envelope(_ id: String, _ cls: Int, _ payload: String, created: String = "1",
                          expires: String = "2") -> String {
        "{\"message_id\":\"\(id)\",\"class\":\(cls),\"sender_proof\":\"\(RelayHex.encode(randomBytes(32)))\","
            + "\"created_at\":\(created),\"expires_at\":\(expires),\"payload\":\"\(payload)\"}"
    }

    func testASuccessThatDoesNotParseIsBusyNeverACrash() {
        let eleven = (0...RelayClient.fetchBatch).map { _ in envelope(RelayHex.encode(randomBytes(16)), 16, "AAAA") }
        let badFetches = [
            "not json", "{}", #"{"envelopes":[{"message_id":"00"}]}"#,
            "{\"envelopes\":[\(envelope(RelayHex.encode(randomBytes(16)), 300, "AAAA"))]}",
            "{\"envelopes\":[\(envelope(RelayHex.encode(randomBytes(16)), 16, "***"))]}",
            "{\"envelopes\":[\(eleven.joined(separator: ","))]}"
        ]
        for body in badFetches {
            XCTAssertThrowsError(try RelayClient.anonymous(Scripted().answer(200, body))
                .as(credential: randomBytes(32)).fetch(), body) {
                XCTAssertEqual($0 as? RelayError, RelayError.malformedResponse)
            }
        }
        let rid = RelayHex.encode(randomBytes(16))
        XCTAssertThrowsError(try RelayClient.anonymous(
            Scripted().answer(201, "{\"routing_id\":\"\(rid)\",\"credential\":\"00\"}"))
            .register(challenge: randomBytes(32), devicePub: randomBytes(32), signature: randomBytes(64)))
    }

    func testAnUnknownPeerIsAnEmptyClaimNotAnError() throws {
        let t = Scripted().answer(404, #"{"code":"NOT_FOUND"}"#)
        XCTAssertNil(try RelayClient.anonymous(t).as(credential: randomBytes(32)).claim(routingID: randomBytes(16)))
    }

    func testTheRequestsAreTheShapesTheBackendReads() throws {
        let t = Scripted()
        let anon = RelayClient.anonymous(t)
        let cred = randomBytes(32)
        let me = anon.as(credential: cred)
        _ = t.answer(200, "{\"challenge\":\"\(RelayHex.encode(randomBytes(32)))\"}")
        _ = try anon.challenge()
        XCTAssertEqual(t.last.method, "POST")
        XCTAssertEqual(t.last.path, "/v1/auth/challenge")
        XCTAssertNil(t.last.bearer, "no credential before registering")

        let s = aSend()
        _ = t.answer(202, "")
        try me.submit(s)
        XCTAssertEqual(t.last.path, "/v1/envelopes")
        XCTAssertEqual(t.last.bearer, RelayHex.encode(cred))
        let sub = try t.lastJSON()
        XCTAssertEqual(sub["recipient"] as? String, RelayHex.encode(s.recipient))
        XCTAssertEqual(sub["message_id"] as? String, RelayHex.encode(s.messageID))
        XCTAssertEqual((sub["class"] as? NSNumber)?.intValue, Int(MessageClass.textMessage.rawValue))
        XCTAssertEqual(sub["capability"] as? String, RelayHex.encode(s.capability))
        XCTAssertEqual(Data(base64Encoded: sub["payload"] as? String ?? ""), s.payload)
        XCTAssertEqual(sub.count, 5, "nothing else is sent")

        _ = t.answer(200, #"{"envelopes":[]}"#)
        _ = try me.fetch()
        XCTAssertEqual(t.last.method, "GET")
        XCTAssertEqual(t.last.path, "/v1/envelopes?limit=\(RelayClient.fetchBatch)")

        let id = randomBytes(16)
        _ = t.answer(200, #"{"acknowledged":1}"#)
        XCTAssertEqual(try me.ack([id]), 1)
        XCTAssertEqual((try t.lastJSON()["message_ids"] as? [String])?.first, RelayHex.encode(id))

        let proof = randomBytes(32)
        _ = t.answer(204, "")
        try me.allow(proof: proof)
        XCTAssertEqual(t.last.method, "PUT")
        XCTAssertEqual(t.last.path, "/v1/mailbox/capabilities")
        try me.revoke(proof: proof)
        XCTAssertEqual(t.last.method, "DELETE")
        XCTAssertEqual(t.last.path, "/v1/mailbox/capabilities/\(RelayHex.encode(proof))")

        let b = X3DH.Bundle(identityEd: randomBytes(32), identityDH: randomBytes(32), identityDHSig: randomBytes(64),
                            spkID: 7, spk: randomBytes(32), spkSig: randomBytes(64), opkID: 0, opk: nil)
        try me.publishPrekeys(bundle: b, oneTime: [RelayTypes.OneTimeKey(id: 9, publicKey: randomBytes(32))])
        let pub = try t.lastJSON()
        let bundle = try XCTUnwrap(pub["bundle"] as? [String: Any])
        XCTAssertEqual(bundle["identity_dh_sig"] as? String, RelayHex.encode(b.identityDHSig))
        XCTAssertEqual((bundle["spk_id"] as? NSNumber)?.intValue, 7)
        XCTAssertEqual(((pub["one_time"] as? [[String: Any]])?[0]["key_id"] as? NSNumber)?.intValue, 9)
        try me.publishPrekeys(bundle: nil, oneTime: [RelayTypes.OneTimeKey(id: 10, publicKey: randomBytes(32))])
        XCTAssertNil(try t.lastJSON()["bundle"], "replenishment sends no bundle")
    }

    func testTheLargestPossibleFetchFitsTheTransportCap() {
        let max = LumosConstants.relayTextEnvelopeMaxBytesMax
        let e = (0..<RelayClient.fetchBatch).map { _ in
            envelope(RelayHex.encode(randomBytes(16)), 255, Data(count: max).base64EncodedString(),
                     created: String(Int64.max), expires: String(Int64.max))
        }
        let real = Int64("{\"envelopes\":[\(e.joined(separator: ","))]}\n".utf8.count)
        XCTAssertLessThanOrEqual(real, RelayClient.largestFetchBody())
        XCTAssertLessThanOrEqual(RelayClient.largestFetchBody(), RelayClient.maxResponseBytes,
                                 "a full batch of the largest envelopes the register allows must fit the response cap")
    }
}

/// A transport with a defect: it throws something that is not TransportUnreachable.
private final class BrokenTransport: HttpTransport {
    struct Bug: Error {}

    func exchange(method: String, path: String, bearer: String?, jsonBody: String?) throws -> HttpResponse {
        throw Bug()
    }
}
