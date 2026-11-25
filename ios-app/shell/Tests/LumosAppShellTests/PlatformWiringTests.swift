/// Lumos - LumosAppShellTests / The URLSession transport, the key root, the boot and the relay job runner.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-NFR-SEC-009, TRS-BCK-002, TRS-LOC-002, SRS-DAT-012, SRS-RMT-003, SRS-RMT-017, TRS-TST-001
/// ADRs: ADR-001, ADR-003, ADR-012
/// Threading: test-only
/// Notes: The Swift side of the Android UrlConnectionTransportTest and
/// KeyRootFileTest, plus the iOS boot and the job runner. The transport is
/// exercised against a real loopback HTTP server. The key root runs on the
/// software tier with an in-memory vault, because the Keychain and the Secure
/// Enclave are device semantics; what is tested is the record and the boot
/// order around them. The boot is "killed" by dropping every object and
/// booting again from the same vault and file.

import Foundation
import LumosProtocol
import LumosProtocolGenerated
import XCTest
@testable import LumosAppShell

final class URLSessionTransportTests: XCTestCase {

    func testARequestCarriesItsMethodPathCredentialAndBody() throws {
        let server = try LoopbackHTTPServer { _ in .init(201, body: Data(#"{"ok":true}"#.utf8)) }
        defer { server.stop() }
        let t = try URLSessionTransport(base: server.base)
        let r = try t.exchange(method: "POST", path: "/v1/devices", bearer: "abc", jsonBody: #"{"k":"v"}"#)
        XCTAssertEqual(r.status, 201)
        XCTAssertEqual(r.body, #"{"ok":true}"#)
        XCTAssertEqual(r.retryAfterSeconds, -1)
        let seen = try XCTUnwrap(server.requests.first)
        XCTAssertEqual(seen.method, "POST")
        XCTAssertEqual(seen.path, "/v1/devices")
        XCTAssertEqual(seen.headers["authorization"], "Bearer abc")
        XCTAssertEqual(seen.headers["content-type"], "application/json")
        XCTAssertEqual(seen.body, Data(#"{"k":"v"}"#.utf8))
        XCTAssertNil(seen.headers["cookie"], "an ephemeral session sends no cookies")
        _ = try t.exchange(method: "GET", path: "/v1/envelopes?limit=10", bearer: nil, jsonBody: nil)
        XCTAssertEqual(server.requests.last?.path, "/v1/envelopes?limit=10")
        XCTAssertNil(server.requests.last?.headers["authorization"], "no credential when none is given")
    }

    func testAnErrorStatusStillReturnsItsBodyAndRetryAfter() throws {
        let server = try LoopbackHTTPServer { _ in
            .init(429, headers: ["Retry-After": "17"], body: Data(#"{"code":"ERR_RELAY_RATE_LIMITED"}"#.utf8))
        }
        defer { server.stop() }
        let r = try URLSessionTransport(base: server.base).exchange(method: "POST", path: "/v1/envelopes",
                                                                    bearer: "x", jsonBody: "{}")
        XCTAssertEqual(r.status, 429)
        XCTAssertEqual(r.retryAfterSeconds, 17)
        XCTAssertTrue(r.body.contains("ERR_RELAY_RATE_LIMITED"))
    }

    func testARedirectIsReturnedNotFollowed() throws {
        let server = try LoopbackHTTPServer { req in
            req.path == "/elsewhere" ? .init(200, body: Data("followed".utf8))
                : .init(302, headers: ["Location": "/elsewhere"])
        }
        defer { server.stop() }
        let r = try URLSessionTransport(base: server.base).exchange(method: "GET", path: "/v1/prekeys/status",
                                                                    bearer: "secret", jsonBody: nil)
        XCTAssertEqual(r.status, 302, "the 3xx is the answer")
        XCTAssertEqual(server.requests.count, 1, "the credential never went anywhere else")
    }

    func testAResponseOverTheCapIsTreatedAsLost() throws {
        let big = Data(repeating: 0x61, count: Int(RelayClient.maxResponseBytes) + 1)
        let server = try LoopbackHTTPServer { _ in .init(200, body: big) }
        defer { server.stop() }
        XCTAssertThrowsError(try URLSessionTransport(base: server.base).exchange(method: "GET", path: "/v1/envelopes",
                                                                                 bearer: "x", jsonBody: nil)) {
            XCTAssertTrue($0 is TransportUnreachable)
        }
        let exact = Data(repeating: 0x61, count: Int(RelayClient.maxResponseBytes))
        let ok = try LoopbackHTTPServer { _ in .init(200, body: exact) }
        defer { ok.stop() }
        XCTAssertEqual(try URLSessionTransport(base: ok.base).exchange(method: "GET", path: "/", bearer: nil,
                                                                       jsonBody: nil).body.utf8.count,
                       exact.count, "exactly at the cap is fine")
    }

    func testNoServerIsTransportUnreachable() throws {
        let server = try LoopbackHTTPServer { _ in .init(200) }
        let base = server.base
        server.stop() // the port is now closed
        XCTAssertThrowsError(try URLSessionTransport(base: base).exchange(method: "GET", path: "/", bearer: nil,
                                                                          jsonBody: nil)) {
            XCTAssertTrue($0 is TransportUnreachable, "the client reads this as OFFLINE")
        }
    }

    func testOnlyHttpsOrALiteralLoopbackOriginIsAccepted() {
        for ok in ["https://relay.example", "https://relay.example/", "https://relay.example:8443",
                   "http://127.0.0.1:8080", "http://127.9.9.9", "http://localhost:3000", "http://[::1]:8080"] {
            XCTAssertTrue(URLSessionTransport.isAcceptableOrigin(URL(string: ok)!), ok)
        }
        for bad in ["http://relay.example", "http://10.0.0.1", "http://127.0.0.1.nip.io", "http://128.0.0.1",
                    "https://relay.example/v1", "https://relay.example?x=1", "https://u:p@relay.example",
                    "ftp://relay.example", "https://", "http://1270.0.0.1"] {
            XCTAssertFalse(URLSessionTransport.isAcceptableOrigin(URL(string: bad)!), bad)
        }
        XCTAssertThrowsError(try URLSessionTransport(base: URL(string: "http://relay.example")!))
    }

    func testRetryAfterIsReadAsSecondsOnly() {
        XCTAssertEqual(URLSessionTransport.retryAfter("30"), 30)
        XCTAssertEqual(URLSessionTransport.retryAfter(" 7 "), 7)
        XCTAssertEqual(URLSessionTransport.retryAfter("0"), 0)
        for bad: String? in [nil, "", "-1", "1.5", "Wed, 21 Oct 2026 07:28:00 GMT", "1234567890", "١٢"] {
            XCTAssertEqual(URLSessionTransport.retryAfter(bad), -1, String(describing: bad))
        }
    }
}

/// A vault in memory, standing in for the Keychain item.
final class MemoryVault: KeyRootVault {
    var record: Data?
    func load() throws -> Data? { record }
    func save(_ record: Data) throws { self.record = record }
    func delete() throws { record = nil }
}

final class KeyRootTests: XCTestCase {

    func testARootRoundTripsReopensTheSameKeysAndReplacesTheOldOneWhole() throws {
        let vault = MemoryVault()
        XCTAssertNil(try KeyRoot.open(from: vault), "no record: not onboarded")
        let created = try KeyRoot.create(in: vault, preferHardware: false)
        XCTAssertEqual(created.kek.protection, .softwareFallback, "the tier is recorded honestly")
        let reopened = try XCTUnwrap(try KeyRoot.open(from: vault))
        XCTAssertEqual(try reopened.hierarchy.deriveKey(for: .database), try created.hierarchy.deriveKey(for: .database),
                       "a relaunch derives the same database key")
        let first = vault.record
        _ = try KeyRoot.create(in: vault, preferHardware: false)
        XCTAssertNotEqual(vault.record, first, "a new onboarding replaces the record whole")
        XCTAssertNotEqual(try XCTUnwrap(try KeyRoot.open(from: vault)).hierarchy.deriveKey(for: .database),
                          try created.hierarchy.deriveKey(for: .database))
    }

    func testAnythingThatIsNotAKeyRootIsAnError() throws {
        let vault = MemoryVault()
        _ = try KeyRoot.create(in: vault, preferHardware: false)
        let good = try XCTUnwrap(vault.record)
        var wrongTier = good
        wrongTier[1] = 9
        var otherKek = good
        otherKek[4] ^= 0x01 // a different software KEK cannot open the root
        let bad: [Data] = [Data(), Data([1]), Data([2]) + good.dropFirst(), wrongTier, good.dropLast(), good + Data([0]),
                           otherKek, Data([1, 2, 0, 0, 0, 0])]
        for b in bad {
            vault.record = b
            XCTAssertThrowsError(try KeyRoot.open(from: vault)) { XCTAssertTrue($0 is KeyRoot.Unreadable) }
        }
    }
}

final class AppBootTests: XCTestCase {

    private let clock = TestClock(1_700_000_000_000)
    private var file: URL!

    override func setUp() {
        file = FileManager.default.temporaryDirectory.appendingPathComponent("lumos-boot-\(UUID().uuidString).db")
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: file)
    }

    private func config(_ vault: KeyRootVault, _ relay: HttpTransport, base: String = "https://relay.example",
                        excluded: @escaping (URL) -> Void = { _ in }) -> BootConfig {
        let clock = self.clock
        return BootConfig(relayBaseURL: base, databaseURL: file, vault: vault, transport: { _ in relay },
                          excludeFromBackup: excluded, clock: { clock.now() })
    }

    /// Onboarding's last steps on this install: key root, store, identity.
    private func onboard(_ vault: KeyRootVault) throws -> IdentityKeyRecord {
        let root = try KeyRoot.create(in: vault, preferHardware: false)
        let clock = self.clock
        let store = try LocalStore.open(database: try Sqlite3Database(path: file.path),
                                        databaseDomainKey: try root.hierarchy.deriveKey(for: .database),
                                        capacity: NoCapacityLimit(), now: { clock.now() })
        let record = try IdentityKeyGenerator.generate(kek: root.kek)
        try IdentityRepository(store: store).replace(record)
        return record
    }

    func testNoRelayConfiguredOrNotOnboardedBootsNothing() throws {
        let relay = FakeRelay(clock: clock)
        let vault = MemoryVault()
        XCTAssertNil(try AppBoot.boot(config(vault, relay, base: "")), "no relay for this build: nearby only")
        XCTAssertNil(try AppBoot.boot(config(vault, relay)), "no key root: not onboarded")
        _ = try KeyRoot.create(in: vault, preferHardware: false)
        XCTAssertNil(try AppBoot.boot(config(vault, relay)), "a key root but no identity yet: not onboarded")
        XCTAssertThrowsError(try AppBoot.boot(config(vault, relay, base: "http://relay.example"))) {
            XCTAssertTrue($0 is AppBoot.BadRelayBase, "plain http to a real host is refused")
        }
    }

    func testTheOnboardingServiceWritesWhatTheBootReads() throws {
        let relay = FakeRelay(clock: clock)
        let vault = MemoryVault()
        let unconfigured = config(vault, relay, base: "")
        XCTAssertFalse(try AppBoot.isOnboarded(unconfigured))
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.path), "asking does not create a database")

        let clock = self.clock
        let onboarding = OnboardingService(clock: { clock.now() })
        onboarding.declareAge(dateOfBirth: Date(timeIntervalSince1970: 0), today: Date(timeIntervalSince1970: 1_764_028_800))
        onboarding.acceptDocuments(try LegalDocuments.load { _ in "Document version: 2026-08-19.1\n" })
        XCTAssertFalse(try AppBoot.isOnboarded(unconfigured), "accepting the documents is not onboarding")
        _ = try onboarding.createIdentity(vault: vault, preferHardware: false) { try AppBoot.openStore($0, unconfigured) }

        XCTAssertTrue(try AppBoot.isOnboarded(unconfigured), "a relaunch with no relay still knows it has onboarded")
        XCTAssertNotNil(try AppBoot.boot(config(vault, relay)), "and with a relay, the chat boots on that identity")
    }

    func testAnOnboardedInstallBootsChatsAndBootsAgainAfterAKill() throws {
        let relay = FakeRelay(clock: clock)
        let vault = MemoryVault()
        let record = try onboard(vault)
        var excludedPaths: [String] = []
        var runtime = try XCTUnwrap(try AppBoot.boot(config(vault, relay, excluded: { excludedPaths.append($0.path) })))
        XCTAssertEqual(excludedPaths, [file.path], "the database is excluded from backups at every boot")
        XCTAssertNil(try runtime.sync.run().stoppedBy, "the booted runtime registers and publishes")

        let peer = try RelayDevice(clock: clock, transport: relay)
        _ = try peer.sync.run()
        let match = randomBytes(16)
        try runtime.chat.acceptConversation(matchID: match, peerIdentityEd: peer.edPub)
        try peer.chat.acceptConversation(matchID: match, peerIdentityEd: record.ed25519Public)
        _ = try peer.exchange.take(matchID: match, state: .matched,
                                   payload: try XCTUnwrap(try runtime.exchange.offer(matchID: match, state: .matched)))
        _ = try runtime.exchange.take(matchID: match, state: .matched,
                                      payload: try XCTUnwrap(try peer.exchange.offer(matchID: match, state: .matched)))
        _ = try runtime.sync.run()
        _ = try peer.sync.run()
        XCTAssertNotNil(try runtime.send(matchID: match, peerIdentityEd: peer.edPub,
                                         event: try RemoteText.TextEvent(kind: .text, target: nil,
                                                                         body: Data("before the kill".utf8))))

        runtime = try XCTUnwrap(try AppBoot.boot(config(vault, relay)), "process death, then a fresh launch")
        XCTAssertEqual(try runtime.chat.pendingOutbox().count, 1, "the queued send survived the relaunch")
        XCTAssertEqual(try runtime.sync.run().sent, 1, "and goes on the same registration and session")
        XCTAssertEqual(try peer.sync.run().shown, 1)
    }
}

final class RelayRunnerTests: XCTestCase {

    private func report(more: Bool = false, stop: RelayError? = nil) -> RelaySync.Report {
        var r = RelaySync.Report()
        r.moreWaiting = more
        r.stoppedBy = stop.map(RelaySync.Stop.init)
        return r
    }

    func testTheRunnerCarriesOutTheScheduleAndNeverLoopsPastTheCap() throws {
        var reports = [report(more: true), report(more: true), report()]
        XCTAssertEqual(try RelayRunner.run(pass: { reports.removeFirst() }).after, .idle)
        XCTAssertTrue(reports.isEmpty, "it kept going while more was waiting")

        var n = 0
        let capped = try RelayRunner.run(pass: { n += 1; return self.report(more: true) })
        XCTAssertEqual(capped.passes, RelaySchedule.maxPassesPerJob, "a bad server cannot pin the CPU")
        XCTAssertEqual(capped.after, .idle)

        XCTAssertEqual(try RelayRunner.run(pass: { self.report(stop: .offline) }).after, .whenOnline)
        XCTAssertEqual(try RelayRunner.run(pass: {
            self.report(stop: RelayError(kind: .busy, code: "", retryAfterSeconds: 30))
        }).after, .notBefore(millis: 30_000))
        XCTAssertEqual(try RelayRunner.run(pass: {
            self.report(stop: RelayError(kind: .refused, code: "", retryAfterSeconds: -1))
        }).after, .backoff)
    }

    func testTheOSEndingTheJobStopsItBetweenPassesAndComesBackSoon() throws {
        var passes = 0
        let r = try RelayRunner.run(pass: { passes += 1; return self.report(more: true) }, expired: { passes >= 2 })
        XCTAssertEqual(r.passes, 2)
        XCTAssertEqual(r.after, .notBefore(millis: 0), "work is still waiting: the next job should come soon")
    }

    func testALocalFailureEscapesSoTheCallerBacksOff() {
        XCTAssertThrowsError(try RelayRunner.run(pass: { throw StoreError.engine("disk") }))
    }
}
