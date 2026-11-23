/// Lumos - LumosAppShell / ADR-012's iOS boot: key root, KEK, data keys, store, identity, chat runtime; and the relay job.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-LOC-002, SRS-DAT-012, SRS-RMT-003, SRS-RMT-017, TRS-TRN-012, SRS-NFR-BAT-001
/// ADRs: ADR-003, ADR-010, ADR-012
/// Threading: serial-executor
/// Notes: The Swift port of the Android AppBoot and RelaySyncJobService. The
/// key root names the KEK and holds the wrapped data root; the KEK unwraps the
/// root; the root derives the database key; the store yields the identity; the
/// identity is unlocked for the chat. Any missing step means "not onboarded on
/// this install" and the boot answers nil rather than inventing state. An
/// empty relay base URL means the relay is not configured for this build (until
/// the owner's staging deploy, WP-05 S8): the chat runs nearby only. The
/// database family is excluded from OS backups at every boot (SRS-DAT-012),
/// because SQLite creates its sidecars lazily.
///
/// RelayRunner carries out RelaySchedule's decisions for one background job:
/// it runs passes until the schedule says stop and returns what the platform
/// should do next. It must run on the app's one relay queue, so two jobs never
/// run passes at once and ChatStore's transactions stay the only concurrency
/// rule. A kill mid-pass is what the relay layer was built for (SRS-RMT-017).

import Foundation

/// Everything the boot needs from the app target.
public struct BootConfig {
    /// The relay origin from the build's configuration; empty means not configured.
    public let relayBaseURL: String
    /// The database file, in Application Support.
    public let databaseURL: URL
    /// Where the key root lives (the Keychain in the app).
    public let vault: KeyRootVault
    /// Builds the transport for the origin.
    public let transport: (URL) throws -> HttpTransport
    /// Excludes the database family from OS backups.
    public let excludeFromBackup: (URL) throws -> Void
    /// The UTC-millisecond clock.
    public let clock: @Sendable () -> Int64

    public init(relayBaseURL: String, databaseURL: URL, vault: KeyRootVault,
                transport: @escaping (URL) throws -> HttpTransport = { try URLSessionTransport(base: $0) },
                excludeFromBackup: @escaping (URL) throws -> Void = BackupExclusion.excludeDatabaseFamily,
                clock: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.relayBaseURL = relayBaseURL
        self.databaseURL = databaseURL
        self.vault = vault
        self.transport = transport
        self.excludeFromBackup = excludeFromBackup
        self.clock = clock
    }
}

/// ADR-012's boot order.
public enum AppBoot {

    /// The relay base URL is not an acceptable origin.
    public struct BadRelayBase: Error, Equatable, Sendable {}

    /// Boots the chat runtime.
    /// - Returns: the runtime, or nil when the relay is not configured or this install has not onboarded
    /// - Throws: ``KeyRoot/Unreadable``, ``StoreError``, a KEK error or ``BadRelayBase``
    public static func boot(_ config: BootConfig) throws -> ChatRuntime? {
        guard !config.relayBaseURL.isEmpty else { return nil }
        guard let base = URL(string: config.relayBaseURL), URLSessionTransport.isAcceptableOrigin(base) else {
            throw BadRelayBase()
        }
        guard let root = try KeyRoot.open(from: config.vault) else { return nil }
        let clock = config.clock
        let store = try LocalStore.open(database: try Sqlite3Database(path: config.databaseURL.path),
                                        databaseDomainKey: try root.hierarchy.deriveKey(for: .database),
                                        capacity: FileSystemCapacity(
                                            path: config.databaseURL.deletingLastPathComponent().path),
                                        now: { clock() })
        try config.excludeFromBackup(config.databaseURL)
        guard let record = try IdentityRepository(store: store).load() else { return nil }
        let identity = try ChatIdentity.unlock(record: record, kek: root.kek)
        return ChatRuntime.assemble(store: store, identity: identity, transport: try config.transport(base),
                                    clock: clock)
    }
}

/// One background job's passes.
public enum RelayRunner {

    /// What the platform should do after the job.
    public enum After: Sendable, Equatable {
        /// Nothing pending: the next periodic refresh is enough.
        case idle
        /// Run again when the network returns (NWPathMonitor / the next refresh).
        case whenOnline
        /// Schedule the next job no earlier than this many milliseconds from now.
        case notBefore(millis: Int64)
        /// Back off: the platform's own spacing.
        case backoff
    }

    /// Default spacing for a back-off when the platform has none of its own.
    public static let backoffMillis: Int64 = 15 * 60 * 1000

    /// Runs passes until the schedule says stop.
    /// - Parameters:
    ///   - pass: one relay pass (``RelaySync/run()``)
    ///   - expired: true once the OS wants the job to end; checked between passes
    /// - Returns: what to schedule next and how many passes ran
    /// - Throws: a local failure (``StoreError``); the caller reschedules with back-off
    public static func run(pass: () throws -> RelaySync.Report,
                           expired: () -> Bool = { false }) throws -> (after: After, passes: Int) {
        var passes = 0
        while true {
            let report = try pass()
            passes += 1
            switch RelaySchedule.after(report, passesSoFar: passes) {
            case .again:
                if expired() { return (.notBefore(millis: 0), passes) }
                continue
            case .done: return (.idle, passes)
            case .whenOnline: return (.whenOnline, passes)
            case .afterDelay(let millis): return (.notBefore(millis: millis), passes)
            case .backoff: return (.backoff, passes)
            }
        }
    }
}

#if os(iOS) && canImport(BackgroundTasks)
import BackgroundTasks
import Network

/// The iOS side of the relay job: a BGAppRefreshTask for when the app is
/// suspended, and a path monitor that kicks a pass when the network returns
/// while it runs. The app target must list ``taskIdentifier`` under
/// BGTaskSchedulerPermittedIdentifiers in Info.plist and call ``register()``
/// before the app finishes launching (Apple's rule).
public final class RelayBackground: @unchecked Sendable {

    /// The refresh task identifier.
    public static let taskIdentifier = "com.lumos.app.relay-sync"

    private let queue = DispatchQueue(label: "com.lumos.relay")
    private let runtime: () -> ChatRuntime?
    private let monitor = NWPathMonitor()

    /// - Parameter runtime: the booted runtime, or nil when there is none
    public init(runtime: @escaping () -> ChatRuntime?) {
        self.runtime = runtime
    }

    /// Registers the refresh handler and starts watching the network.
    public func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: RelayBackground.taskIdentifier, using: nil) { task in
            self.handle(task)
        }
        monitor.pathUpdateHandler = { path in
            if path.status == .satisfied { self.kick() }
        }
        monitor.start(queue: queue)
    }

    /// Runs passes now, on the relay queue.
    public func kick() {
        queue.async { _ = self.runPasses(expired: { false }) }
    }

    /// Set by the OS's expiration handler, read between passes.
    private final class Flag: @unchecked Sendable {
        private let lock = NSLock()
        private var raised = false

        func raise() {
            lock.lock()
            raised = true
            lock.unlock()
        }

        var isRaised: Bool {
            lock.lock()
            defer { lock.unlock() }
            return raised
        }
    }

    /// Carries the task to the relay queue; the OS allows completing it from any thread.
    private struct TaskBox: @unchecked Sendable {
        let task: BGTask
    }

    private func handle(_ task: BGTask) {
        let expired = Flag()
        task.expirationHandler = { expired.raise() }
        let box = TaskBox(task: task)
        queue.async {
            let after = self.runPasses(expired: { expired.isRaised })
            box.task.setTaskCompleted(success: after != nil)
        }
    }

    @discardableResult
    private func runPasses(expired: @escaping () -> Bool) -> RelayRunner.After? {
        guard let rt = runtime() else { return nil }
        let after: RelayRunner.After
        do {
            after = try RelayRunner.run(pass: { try rt.sync.run() }, expired: expired).after
        } catch {
            after = .backoff
        }
        schedule(after)
        return after
    }

    private func schedule(_ after: RelayRunner.After) {
        let request = BGAppRefreshTaskRequest(identifier: RelayBackground.taskIdentifier)
        switch after {
        case .notBefore(let millis): request.earliestBeginDate = Date(timeIntervalSinceNow: Double(millis) / 1000)
        case .backoff: request.earliestBeginDate = Date(timeIntervalSinceNow: Double(RelayRunner.backoffMillis) / 1000)
        case .idle, .whenOnline: request.earliestBeginDate = nil
        }
        try? BGTaskScheduler.shared.submit(request)
    }
}
#endif
