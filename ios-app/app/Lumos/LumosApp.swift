/// Lumos - ios-app/app / The app's entry point and its launch configuration.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-IOS-001, SRS-ONB-001
/// ADRs: ADR-012
/// Threading: main-actor
/// Notes: The app shows exactly one of three things: onboarding, the home
/// tabs, or a failure notice. Until onboarding completes nothing else is
/// reachable (SRS-ONB-001, threat model LT-30), because the root view has no
/// other branch to take. Nothing here touches Bluetooth, notifications or the
/// network at launch (SRS-ONB-004).

import LumosAppShell
import SwiftUI

@main
@MainActor
struct LumosApp: App {

    @StateObject private var model = AppModel(config: BootConfig.device())

    var body: some Scene {
        WindowGroup {
            RootView(model: model)
        }
    }
}

/// Chooses the one screen the app's phase allows.
struct RootView: View {

    @ObservedObject var model: AppModel

    var body: some View {
        Group {
            switch model.phase {
            case .starting:
                ProgressView()
                    .accessibilityLabel(Text("Starting Lumos"))
            case .onboarding(let state):
                OnboardingFlow(model: model, state: state)
            case .ready:
                if let home = model.home, let nearby = model.nearby {
                    HomeScreen(model: home, nearby: nearby)
                } else {
                    FailureScreen()
                }
            case .failed:
                FailureScreen()
            }
        }
        .onAppear { model.start() }
    }
}

extension BootConfig {

    /// The configuration of this install: the database in Application
    /// Support, the key root in the Keychain, and the relay origin this build
    /// was given (empty until the staging deploy).
    static func device() -> BootConfig {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let folder = support.appendingPathComponent("Lumos", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let relay = Bundle.main.object(forInfoDictionaryKey: "LumosRelayBaseURL") as? String ?? ""
        return BootConfig(relayBaseURL: relay,
                          databaseURL: folder.appendingPathComponent("lumos.sqlite"),
                          vault: KeychainKeyRootVault())
    }
}
