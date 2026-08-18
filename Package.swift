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
    dependencies: [
        // swift-sodium (ISC), owner-approved 2026-08-18, pinned EXACTLY rather
        // than by range: a KDF binding that moved on its own would change what
        // the recovery vectors assert without anyone deciding to.
        //
        // Only the Clibsodium product is taken - the libsodium XCFramework and
        // its module map - not the package's `Sodium` Swift wrapper, and that is
        // a correctness decision rather than a minimalism one. Sodium's
        // PWHash.hash maps the password through `passwd.map(Int8.init)`, and
        // Int8(_:) TRAPS on any byte above 0x7F. Every user whose passphrase
        // contains an accent, or an emoji, would crash rather than fail. The
        // recovery vector family carries non_ascii_passphrase for exactly this,
        // and RecoveryRecord calls crypto_pwhash directly with a non-trapping
        // rebind instead.
        //
        // ChaCha20-Poly1305 comes from CryptoKit, as it has since WP-01: the
        // threat model's assumption 5 is about libsodium-EQUIVALENT primitives
        // agreeing, and the golden vectors are what test that agreement.
        // Argon2id is taken from libsodium because CryptoKit has no Argon2 at
        // all, which is the whole reason this dependency exists.
        .package(url: "https://github.com/jedisct1/swift-sodium.git", exact: "0.11.0")
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

        // The iOS application shell. A SwiftPM library rather than an .app
        // bundle: a bundle needs an Xcode project and a signing identity, and
        // signing material never exists in source or CI (TRS-REP-005). WP-02 S1
        // adds the key hierarchy here, which consumes the generated constants
        // (ADR-004: the X3DH binding info string is never copied).
        .target(
            name: "LumosAppShell",
            dependencies: [
                "LumosProtocolGenerated",
                .product(name: "Clibsodium", package: "swift-sodium")
            ],
            path: "ios-app/shell/Sources/LumosAppShell",
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
        // LumosProtocol is a TEST dependency here, for its committed-vector
        // loader alone. The shell's RecoveryRecord is held to the same
        // test-vectors/crypto/recovery_record.json the conformance runner reads,
        // and writing a second loader for it would be a second thing to keep
        // agreeing with the first.
        .testTarget(
            name: "LumosAppShellTests",
            dependencies: ["LumosAppShell", "LumosProtocol", "LumosProtocolGenerated"],
            path: "ios-app/shell/Tests/LumosAppShellTests",
            swiftSettings: [.swiftLanguageMode(.v6)]
        )
    ]
)
