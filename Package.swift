// swift-tools-version: 6.0
//
/// Lumos - build / SwiftPM package for the protocol conformance runner.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-TST-001, TRS-PROTO-015, SRS-NFR-CMP-004
/// ADRs: ADR-001, ADR-004
/// Threading: any
/// Notes: Package.swift sits at the repository root because SwiftPM requires it
/// at the package root and the LumosProtocol target consumes generated sources
/// from /protocol/gen/swift - a package rooted under ios-app/ could not reach
/// them without duplicating generated code, which ADR-004 forbids. The target is
/// PURE Foundation + CryptoKit: no UIKit, no simulator, no Xcode project, so
/// `swift test` runs on a macOS CI host exactly as TRS Sec. 18.4 requires.

import PackageDescription

let package = Package(
    name: "LumosProtocolConformance",
    platforms: [
        // CryptoKit needs macOS 10.15+; 13 is the floor the CI image provides.
        // iOS 17 is declared so the app shell is compiled against the platform
        // it actually ships to, not merely against the host.
        .macOS(.v13),
        .iOS(.v17)
    ],
    targets: [
        // Generated constants, message classes, error codes and class limits.
        // A separate target so the generator's output directory is consumed
        // directly, never copied (TRS-REP-002, ADR-004).
        .target(
            name: "LumosProtocolGenerated",
            path: "protocol/gen/swift",
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
        .target(
            name: "LumosProtocol",
            dependencies: ["LumosProtocolGenerated"],
            path: "ios-app/protocol-conformance/Sources/LumosProtocol",
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
        .testTarget(
            name: "LumosProtocolConformance",
            dependencies: ["LumosProtocol", "LumosProtocolGenerated"],
            path: "ios-app/protocol-conformance/Tests/LumosProtocolConformance",
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),

        // The empty iOS application shell. A SwiftPM library rather than an .app
        // bundle: a bundle needs an Xcode project and a signing identity, and
        // signing material never exists in source or CI (TRS-REP-005).
        .target(
            name: "LumosAppShell",
            path: "ios-app/shell/Sources/LumosAppShell",
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
        .testTarget(
            name: "LumosAppShellTests",
            dependencies: ["LumosAppShell"],
            path: "ios-app/shell/Tests/LumosAppShellTests",
            swiftSettings: [.swiftLanguageMode(.v6)]
        )
    ]
)
