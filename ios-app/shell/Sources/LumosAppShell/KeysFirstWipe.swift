/// Lumos - ios-app/shell / The keys-first wipe executor.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-SAF-010, SRS-NFR-PRI-012
/// ADRs: ADR-003, ADR-010
/// Threading: serial-executor
/// Notes: The ordering is the security property, and it is normative: the
/// owner confirmed keys-first on crypto-erasure rationale (2026-08-14),
/// superseding the ADR-003 phrasing - destroying the key material IS the
/// erasure of everything it protects, and content clearing afterwards is
/// hygiene, not the mechanism. Every key-material stage runs before any
/// content stage regardless of registration order; a failing stage is
/// recorded and the wipe continues, because stopping early would preserve
/// exactly the data the user asked to destroy. Mirrors the Android executor
/// stage for stage (TRS-CODE-007).

import Foundation

/// Executes registered wipe stages, all key material strictly before any content.
public final class KeysFirstWipe {

    /// The two phases, in their normative order.
    public enum Phase: Sendable, Equatable {
        /// KEKs and anything else that IS key material. Always first.
        case keyMaterial
        /// Everything the key material protected. Only after the key phase.
        case content
    }

    /// The outcome of one executed stage.
    public struct StageResult: Sendable {
        /// The stage's phase.
        public let phase: Phase
        /// The stage's registered name.
        public let name: String
        /// The failure description, or nil when the stage succeeded.
        public let failure: String?

        /// Whether the stage completed.
        public var succeeded: Bool {
            failure == nil
        }
    }

    /// The full record of one wipe execution.
    public struct WipeReport: Sendable {
        /// Every executed stage, in execution order.
        public let results: [StageResult]

        /// Whether every key-material stage completed. When true, everything
        /// the keys protected is ciphertext without a key whatever the content
        /// phase did - the crypto-erasure guarantee.
        public var allKeyMaterialDestroyed: Bool {
            results.allSatisfy { $0.phase != .keyMaterial || $0.succeeded }
        }

        /// Whether every stage of both phases completed.
        public var fullyClean: Bool {
            results.allSatisfy(\.succeeded)
        }
    }

    private struct Registered {
        let name: String
        let action: () throws -> Void
    }

    private var keyStages: [Registered] = []
    private var contentStages: [Registered] = []
    private var executed = false

    /// Creates an empty wipe awaiting stage registration.
    public init() {}

    /// Registers a key-material stage (a KEK destroy, an envelope delete).
    /// - Parameters:
    ///   - name: the stage's name for the report
    ///   - action: the destruction
    /// - Returns: self, for chaining
    @discardableResult
    public func registerKeyMaterial(_ name: String, action: @escaping () throws -> Void) -> KeysFirstWipe {
        precondition(!executed, "wipe already executed")
        keyStages.append(Registered(name: name, action: action))
        return self
    }

    /// Registers a content stage (a store clear, a cache purge).
    /// - Parameters:
    ///   - name: the stage's name for the report
    ///   - action: the destruction
    /// - Returns: self, for chaining
    @discardableResult
    public func registerContent(_ name: String, action: @escaping () throws -> Void) -> KeysFirstWipe {
        precondition(!executed, "wipe already executed")
        contentStages.append(Registered(name: name, action: action))
        return self
    }

    /// Runs the wipe: all key-material stages, then all content stages.
    /// - Returns: the execution report; it records failures, it never throws
    public func execute() -> WipeReport {
        precondition(!executed, "wipe already executed")
        executed = true
        var results: [StageResult] = []
        run(phase: .keyMaterial, stages: keyStages, into: &results)
        run(phase: .content, stages: contentStages, into: &results)
        return WipeReport(results: results)
    }

    /// Runs one phase's stages in registration order.
    /// - Parameters:
    ///   - phase: the phase label for the report
    ///   - stages: the registered stages
    ///   - results: the accumulating report
    private func run(phase: Phase, stages: [Registered], into results: inout [StageResult]) {
        for stage in stages {
            do {
                try stage.action()
                results.append(StageResult(phase: phase, name: stage.name, failure: nil))
            } catch {
                results.append(StageResult(phase: phase, name: stage.name, failure: "\(error)"))
            }
        }
    }
}
