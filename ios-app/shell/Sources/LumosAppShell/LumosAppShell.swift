/// Lumos - ios-app/shell / Empty iOS application shell.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-REP-001, TRS-REP-004, TRS-REP-007
/// ADRs: ADR-002, ADR-004
/// Threading: main-safe
/// Notes: The WP-01 shell held build provenance and nothing else; WP-02 S1
/// added the first feature code beside it (the key hierarchy: Identity,
/// KekStore, DataKeyHierarchy, KeysFirstWipe, KeychainBlobStore). This file
/// still carries only provenance and the TRS-REP-004 environment separation.
///
/// This is a SwiftPM library, not an .app bundle. A bundle needs an Xcode
/// project and a signing identity, and signing material never exists in source
/// or CI (TRS-REP-005) - so the bundle arrives with the WP-02 UI slices that
/// justify it. What CI proves here is that the code compiles for iOS.

import Foundation

/// The build environments a Lumos artefact may target (TRS-REP-004).
public enum LumosEnvironment: String, CaseIterable, Sendable {
    /// Local development.
    case development
    /// Automated test.
    case test
    /// Pre-production staging.
    case staging
    /// Production.
    case production
}

/// Build provenance reported by an artefact (TRS-REP-007).
public struct LumosBuildInfo: Equatable, Sendable {
    /// Application version.
    public let version: String
    /// Source revision.
    public let revision: String
    /// Target environment.
    public let environment: LumosEnvironment

    /// Creates build provenance.
    /// - Parameters:
    ///   - version: application version
    ///   - revision: source revision
    ///   - environment: target environment
    public init(version: String, revision: String, environment: LumosEnvironment) {
        self.version = version
        self.revision = revision
        self.environment = environment
    }

    /// A one-line description for smoke checks.
    public var summary: String {
        "lumos-ios \(version) (\(environment.rawValue), \(revision))"
    }
}

/// The application shell.
///
/// Deliberately inert. It holds build provenance and nothing else; every
/// capability the product needs is introduced by the work package that owns it.
public struct LumosAppShell: Sendable {
    /// Build provenance for this artefact.
    public let build: LumosBuildInfo

    /// Creates the shell.
    /// - Parameter build: build provenance
    public init(build: LumosBuildInfo) {
        self.build = build
    }

    /// Reports whether the shell is ready to hand off to a feature module.
    ///
    /// Always true today: there is nothing to initialise. The method exists so
    /// WP-02 has a defined seam rather than inventing one against a bare struct.
    public func isReady() -> Bool {
        !build.version.isEmpty
    }
}
