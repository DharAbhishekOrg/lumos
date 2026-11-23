/// Lumos - LumosAppShellTests / A minimal HTTP/1.1 server on 127.0.0.1 for the transport tests.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-TST-001
/// ADRs: ADR-001
/// Threading: test-only
/// Notes: The Swift side of the JDK HttpServer the Android transport test
/// uses: a real socket on a literal loopback address, so redirects, headers
/// and body caps go through URLSession's real HTTP stack rather than a
/// URLProtocol stub. One connection at a time, Connection: close, a request
/// body read by Content-Length. Plain POSIX sockets, for macOS and Linux.

import Foundation
#if canImport(Glibc)
import Glibc
#elseif canImport(Darwin)
import Darwin
#endif

final class LoopbackHTTPServer: @unchecked Sendable {

    struct Request: Sendable {
        let method: String
        let path: String
        let headers: [String: String]
        let body: Data
    }

    struct Response: Sendable {
        let status: Int
        let headers: [String: String]
        let body: Data

        init(_ status: Int, headers: [String: String] = [:], body: Data = Data()) {
            self.status = status
            self.headers = headers
            self.body = body
        }
    }

    private let fd: Int32
    private let lock = NSLock()
    private var handler: @Sendable (Request) -> Response
    private var seen: [Request] = []
    private var stopped = false
    let port: UInt16

    init(_ handler: @escaping @Sendable (Request) -> Response) throws {
        self.handler = handler
        #if canImport(Glibc)
        let stream = Int32(SOCK_STREAM.rawValue)
        #else
        let stream = SOCK_STREAM
        #endif
        let sock = socket(AF_INET, stream, 0)
        guard sock >= 0 else { throw POSIXError(.EIO) }
        var one: Int32 = 1
        setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, &one, socklen_t(MemoryLayout<Int32>.size))
        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = 0
        addr.sin_addr.s_addr = inet_addr("127.0.0.1")
        let bound = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(sock, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        guard bound == 0, listen(sock, 8) == 0 else { throw POSIXError(.EADDRINUSE) }
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        _ = withUnsafeMutablePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(sock, $0, &len) }
        }
        fd = sock
        port = UInt16(bigEndian: addr.sin_port)
        Thread.detachNewThread { [self] in self.serve() }
    }

    deinit {
        stop()
    }

    var base: URL { URL(string: "http://127.0.0.1:\(port)")! }

    var requests: [Request] {
        lock.lock()
        defer { lock.unlock() }
        return seen
    }

    func stop() {
        lock.lock()
        let first = !stopped
        stopped = true
        lock.unlock()
        if first {
            shutdown(fd, Int32(SHUT_RDWR))
            close(fd)
        }
    }

    #if canImport(Glibc)
    static let noSignal = Int32(MSG_NOSIGNAL)
    #else
    static let noSignal: Int32 = 0
    #endif

    private func serve() {
        while true {
            let client = accept(fd, nil, nil)
            if client < 0 { return }
            #if canImport(Darwin)
            var one: Int32 = 1
            setsockopt(client, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
            #endif
            handle(client)
            close(client)
        }
    }

    private func readAll(_ client: Int32, until done: (Data) -> Bool) -> Data {
        var data = Data()
        var buf = [UInt8](repeating: 0, count: 8192)
        while !done(data) {
            let n = recv(client, &buf, buf.count, 0)
            if n <= 0 { break }
            data.append(buf, count: n)
        }
        return data
    }

    private func handle(_ client: Int32) {
        let separator = Data("\r\n\r\n".utf8)
        var raw = readAll(client) { $0.range(of: separator) != nil }
        guard let end = raw.range(of: separator) else { return }
        let head = String(bytes: raw[..<end.lowerBound], encoding: .utf8) ?? ""
        var lines = head.components(separatedBy: "\r\n")
        let start = lines.removeFirst().split(separator: " ")
        var headers: [String: String] = [:]
        for l in lines {
            if let colon = l.firstIndex(of: ":") {
                headers[l[..<colon].lowercased()] = l[l.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            }
        }
        let length = Int(headers["content-length"] ?? "0") ?? 0
        let bodyStart = end.upperBound
        let missing = length - (raw.count - bodyStart)
        if missing > 0 {
            raw += readAll(client) { $0.count >= missing }
        }
        let body = Data(raw[bodyStart..<min(raw.count, bodyStart + length)])
        let request = Request(method: start.count > 0 ? String(start[0]) : "",
                              path: start.count > 1 ? String(start[1]) : "", headers: headers, body: body)
        lock.lock()
        seen.append(request)
        let h = handler
        lock.unlock()
        let r = h(request)
        var out = "HTTP/1.1 \(r.status) X\r\nContent-Length: \(r.body.count)\r\nConnection: close\r\n"
        for (k, v) in r.headers { out += "\(k): \(v)\r\n" }
        let bytes = Data((out + "\r\n").utf8) + r.body
        bytes.withUnsafeBytes { p in
            var sent = 0
            while sent < bytes.count {
                // The client may hang up mid-body (the cap test does): never die of SIGPIPE.
                let n = send(client, p.baseAddress!.advanced(by: sent), bytes.count - sent, LoopbackHTTPServer.noSignal)
                if n <= 0 { break }
                sent += n
            }
        }
    }
}
