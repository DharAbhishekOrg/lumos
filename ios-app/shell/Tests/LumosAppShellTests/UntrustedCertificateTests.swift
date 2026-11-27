/// Lumos - LumosAppShellTests / The relay transport refuses a server the platform does not trust.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-NFR-SEC-009, TRS-BCK-001, TRS-TST-001
/// ADRs: ADR-001
/// Threading: test-only
/// Notes: The Swift side of the Android UntrustedCertificateTest. SRS-NFR-SEC-009's
/// dynamic half (the static half is lumoslint's crypto-hygiene rule): a real
/// TLS server on loopback - the system `openssl s_server`, which echoes every
/// byte it receives after a handshake - presents a self-signed certificate
/// made for this run. URLSessionTransport, on platform trust, must fail, and
/// the server must have received no request bytes and so no bearer
/// credential. The control proves the fixture is sound: `openssl s_client`
/// told to trust exactly that certificate completes a verified handshake, so
/// the transport's failure is about trust, not a broken server.

import Foundation
import XCTest
@testable import LumosAppShell

final class UntrustedCertificateTests: XCTestCase {

    private var dir: URL!
    private var server: Process?
    private var serverLog: URL!

    private struct NoOpenSSL: Error {}

    /// OpenSSL 3 first (Homebrew on the macOS runners, the system one on
    /// Linux), the system LibreSSL last. A host with none fails the test:
    /// a skipped security test would read as a passed one.
    private func openssl() throws -> URL {
        for path in ["/opt/homebrew/opt/openssl@3/bin/openssl", "/usr/local/opt/openssl@3/bin/openssl",
                     "/usr/bin/openssl"]
            where FileManager.default.isExecutableFile(atPath: path) {
            return URL(fileURLWithPath: path)
        }
        XCTFail("no openssl binary on this host")
        throw NoOpenSSL()
    }

    /// Runs openssl to completion; returns its exit status and combined output.
    @discardableResult
    private func run(_ arguments: [String]) throws -> (status: Int32, output: String) {
        let process = Process()
        process.executableURL = try openssl()
        process.arguments = arguments
        process.standardInput = FileHandle.nullDevice
        let log = dir.appendingPathComponent("run-\(UUID().uuidString).log")
        FileManager.default.createFile(atPath: log.path, contents: nil)
        let sink = try FileHandle(forWritingTo: log)
        process.standardOutput = sink
        process.standardError = sink
        try process.run()
        process.waitUntilExit()
        try? sink.close()
        let output = (try? String(contentsOf: log, encoding: .utf8)) ?? ""
        return (process.terminationStatus, output)
    }

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("lumos-tls-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let config = """
            [req]
            distinguished_name = dn
            x509_extensions = v3
            prompt = no
            [dn]
            CN = 127.0.0.1
            [v3]
            subjectAltName = IP:127.0.0.1
            basicConstraints = critical,CA:TRUE
            keyUsage = critical,digitalSignature,keyEncipherment,keyCertSign
            extendedKeyUsage = serverAuth
            """
        try config.write(to: dir.appendingPathComponent("req.cnf"), atomically: true, encoding: .utf8)
        let made = try run(["req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
                            "-config", dir.appendingPathComponent("req.cnf").path,
                            "-keyout", dir.appendingPathComponent("key.pem").path,
                            "-out", dir.appendingPathComponent("cert.pem").path])
        XCTAssertEqual(made.status, 0, "openssl made the test certificate: \(made.output)")
    }

    override func tearDownWithError() throws {
        if let server, server.isRunning {
            server.terminate()
            server.waitUntilExit()
        }
        try? FileManager.default.removeItem(at: dir)
    }

    /// Starts s_server on a free loopback port and waits until a trusting client gets through.
    private func startServer() throws -> UInt16 {
        let probe = try LoopbackHTTPServer { _ in .init(200) }
        let port = probe.port
        probe.stop()
        serverLog = dir.appendingPathComponent("server.log")
        FileManager.default.createFile(atPath: serverLog.path, contents: nil)
        let process = Process()
        process.executableURL = try openssl()
        process.arguments = ["s_server", "-accept", String(port), "-quiet",
                             "-cert", dir.appendingPathComponent("cert.pem").path,
                             "-key", dir.appendingPathComponent("key.pem").path]
        process.standardInput = Pipe()
        process.standardOutput = try FileHandle(forWritingTo: serverLog)
        process.standardError = try FileHandle(forWritingTo: serverLog)
        try process.run()
        server = process
        var trusted: (status: Int32, output: String) = (-1, "")
        for _ in 0..<50 where trusted.status != 0 {
            Thread.sleep(forTimeInterval: 0.2)
            trusted = try run(["s_client", "-connect", "127.0.0.1:\(port)", "-verify_return_error",
                               "-CAfile", dir.appendingPathComponent("cert.pem").path])
        }
        XCTAssertEqual(trusted.status, 0, """
            control: a client that trusts exactly this certificate completes the handshake
            client: \(trusted.output.suffix(800))
            server: \(((try? String(contentsOf: serverLog, encoding: .utf8)) ?? "").suffix(400))
            """)
        return port
    }

    func testAnUntrustedCertificateFailsBeforeAnyRequestOrCredentialIsSent() throws {
        let port = try startServer()
        let transport = try URLSessionTransport(base: try XCTUnwrap(URL(string: "https://127.0.0.1:\(port)")))
        XCTAssertThrowsError(try transport.exchange(method: "POST", path: "/v1/envelopes", bearer: "c0ffee",
                                                    jsonBody: "{}"),
                             "platform trust refuses a self-signed server")
        server?.terminate()
        server?.waitUntilExit()
        let received = (try? String(contentsOf: serverLog, encoding: .utf8)) ?? ""
        XCTAssertFalse(received.contains("POST"), "not one request reached the server")
        XCTAssertFalse(received.contains("c0ffee"), "and so no bearer credential")
    }
}
