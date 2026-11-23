/// Lumos - LumosAppShell / The relay transport over URLSession: https only, no redirects, a capped response.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-NFR-SEC-009, TRS-BCK-001, TRS-BCK-002, SRS-RMT-003
/// ADRs: ADR-001
/// Threading: serial-executor
/// Notes: The Swift port of the Android UrlConnectionTransport. The
/// platform's own TLS and certificate validation are untouched: no custom
/// trust evaluation and no authentication-challenge override, so there is
/// nothing here that could be a trust-all bypass (SRS-NFR-SEC-009). The TLS
/// floor itself (SRS-NFR-SEC-008) is the backend's policy and the owner-run
/// scan's to prove. The base must be an https origin; plain http is accepted
/// only for a LITERAL loopback host (or `localhost`), which tests and a local
/// docker-compose backend use; a name is never resolved here, so the check
/// cannot be steered by DNS. Redirects are never followed, so the bearer
/// credential only ever goes to the configured host; a 3xx comes back as its
/// status. A body is read up to RelayClient.maxResponseBytes; beyond it the
/// exchange is treated as lost. The session is ephemeral: no cookies, no
/// cache, no credential storage. Every network failure surfaces as
/// TransportUnreachable, the client's OFFLINE. Nothing here logs a header or
/// a body (TRS-BCK-002). exchange() blocks its caller and must run on the
/// relay's serial background queue, never the main thread.

import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// The URLSession relay transport.
public final class URLSessionTransport: HttpTransport {

    static let requestTimeoutSeconds: TimeInterval = 30

    /// The base is not an https origin (or a literal loopback http one).
    public struct BadOrigin: Error, Equatable, Sendable {}

    private let base: URL
    private let session: URLSession
    private let guardian: Guardian

    /// Binds the transport to the backend origin.
    /// - Parameters:
    ///   - base: an origin, e.g. `https://relay.example`
    ///   - protocolClasses: test-only URLProtocol stubs; nil in production
    /// - Throws: ``BadOrigin`` for anything but an https origin or a literal loopback http one
    public init(base: URL, protocolClasses: [AnyClass]? = nil) throws {
        guard URLSessionTransport.isAcceptableOrigin(base) else { throw BadOrigin() }
        self.base = base
        let config = URLSessionConfiguration.ephemeral
        config.httpCookieStorage = nil
        config.httpShouldSetCookies = false
        config.urlCache = nil
        config.urlCredentialStorage = nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.timeoutIntervalForRequest = URLSessionTransport.requestTimeoutSeconds
        config.timeoutIntervalForResource = URLSessionTransport.requestTimeoutSeconds * 2
        if let protocolClasses {
            config.protocolClasses = protocolClasses
        }
        guardian = Guardian()
        session = URLSession(configuration: config, delegate: guardian, delegateQueue: nil)
    }

    deinit {
        session.invalidateAndCancel()
    }

    static func isAcceptableOrigin(_ url: URL) -> Bool {
        guard let comps = URLComponents(url: url, resolvingAgainstBaseURL: false), let host = comps.host,
              !host.isEmpty, comps.user == nil, comps.password == nil, comps.query == nil, comps.fragment == nil,
              comps.path.isEmpty || comps.path == "/" else { return false }
        switch comps.scheme {
        case "https": return true
        case "http": return isLiteralLoopback(host)
        default: return false
        }
    }

    /// `localhost`, or a literal IPv4 127/8 or IPv6 ::1 address; never a resolved name.
    static func isLiteralLoopback(_ host: String) -> Bool {
        if host == "localhost" { return true }
        let bare = host.trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        if bare == "::1" || bare == "0:0:0:0:0:0:0:1" { return true }
        let parts = bare.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4, parts.allSatisfy({ !$0.isEmpty && $0.count <= 3 && $0.allSatisfy(\.isASCII) }),
              let octets = Optional(parts.compactMap { UInt8($0) }), octets.count == 4 else { return false }
        return octets[0] == 127
    }

    /// Retry-After as delta-seconds only; an HTTP date or anything else is absent (-1).
    static func retryAfter(_ header: String?) -> Int64 {
        guard let v = header?.trimmingCharacters(in: .whitespaces), (1...9).contains(v.count),
              v.unicodeScalars.allSatisfy({ ("0"..."9").contains($0) }), let n = Int64(v) else { return -1 }
        return n
    }

    public func exchange(method: String, path: String, bearer: String?, jsonBody: String?) throws -> HttpResponse {
        guard path.hasPrefix("/"), let url = URL(string: path, relativeTo: base)?.absoluteURL else {
            throw BadOrigin()
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let bearer {
            request.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
        }
        if let jsonBody {
            request.httpBody = Data(jsonBody.utf8)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let outcome = guardian.run(session.dataTask(with: request))
        guard case .success(let response, let body) = outcome else { throw TransportUnreachable() }
        return HttpResponse(status: response.statusCode, body: String(bytes: body, encoding: .utf8) ?? "",
                            retryAfterSeconds: URLSessionTransport.retryAfter(
                                response.value(forHTTPHeaderField: "Retry-After")))
    }
}

/// The session delegate: refuses redirects, caps the body, and hands the
/// result back to the blocked caller.
private final class Guardian: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    enum Outcome {
        case success(HTTPURLResponse, Data)
        case lost
    }

    private let lock = NSLock()
    private var bodies: [Int: Data] = [:]
    private var results: [Int: Outcome] = [:]
    private var waiters: [Int: DispatchSemaphore] = [:]

    func run(_ task: URLSessionDataTask) -> Outcome {
        let done = DispatchSemaphore(value: 0)
        lock.lock()
        waiters[task.taskIdentifier] = done
        bodies[task.taskIdentifier] = Data()
        lock.unlock()
        task.resume()
        done.wait()
        lock.lock()
        defer { lock.unlock() }
        let outcome = results.removeValue(forKey: task.taskIdentifier) ?? .lost
        waiters[task.taskIdentifier] = nil
        bodies[task.taskIdentifier] = nil
        return outcome
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil) // never followed: the 3xx itself is the answer
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        let id = dataTask.taskIdentifier
        let total = (bodies[id]?.count ?? 0) + data.count
        if total > Int(RelayClient.maxResponseBytes) {
            results[id] = .lost
            lock.unlock()
            dataTask.cancel()
            return
        }
        bodies[id]?.append(data)
        lock.unlock()
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock()
        let id = task.taskIdentifier
        if results[id] == nil {
            if error == nil, let response = task.response as? HTTPURLResponse {
                results[id] = .success(response, bodies[id] ?? Data())
            } else {
                results[id] = .lost
            }
        }
        let waiter = waiters[id]
        lock.unlock()
        waiter?.signal()
    }
}
