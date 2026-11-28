/// Lumos - ios-app/app / What the app is showing, and the onboarding steps behind it.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-ONB-001, SRS-ONB-002, SRS-ONB-006, SRS-ONB-008
/// ADRs: ADR-008, ADR-012
/// Threading: main-actor
/// Notes: A thin bridge from SwiftUI to the shell's OnboardingService, which
/// owns the rules and is tested on every CI run. This file decides only what
/// is on screen. A failure is shown as one plain notice: no error text, path
/// or identifier is put on screen or in a log (TRS-OBS-002).

import Foundation
import LumosAppShell

/// The app's one source of what to show.
@MainActor
final class AppModel: ObservableObject {

    /// What the root view shows.
    enum Phase: Equatable {
        /// Working out whether this install has onboarded.
        case starting
        /// An onboarding step.
        case onboarding(OnboardingState)
        /// Onboarding is done.
        case ready
        /// Something went wrong that the person cannot fix from here.
        case failed
    }

    @Published private(set) var phase: Phase = .starting

    /// The home tabs' model, made once onboarding is done.
    private(set) var home: HomeModel?
    /// Visible Nearby, made with the home tabs.
    private(set) var nearby: VisibilityModel?
    /// The Profile tab, made with the home tabs.
    private(set) var profile: ProfileModel?

    private let config: BootConfig
    private let onboarding: OnboardingService
    private var documents: LegalDocuments?
    private var started = false

    init(config: BootConfig) {
        self.config = config
        self.onboarding = OnboardingService(clock: config.clock)
    }

    /// Decides the first screen, once per launch.
    func start() {
        guard !started else { return }
        started = true
        do {
            if try AppBoot.isOnboarded(config) {
                becomeReady()
            } else {
                phase = .onboarding(onboarding.machine.state)
            }
        } catch {
            phase = .failed
        }
    }

    /// The age gate's answer. The date is judged and dropped: nothing keeps it.
    func declareAge(dateOfBirth: Date) {
        phase = .onboarding(onboarding.declareAge(dateOfBirth: dateOfBirth, today: Date()))
    }

    /// The text of one shipped document, for reading before accepting.
    func text(of document: LegalDocument) -> String {
        (try? Self.read(document)) ?? ""
    }

    /// Accepts the documents exactly as shipped in this build.
    func acceptDocuments() {
        do {
            let shown = try documents ?? LegalDocuments.load(read: Self.read)
            documents = shown
            phase = .onboarding(onboarding.acceptDocuments(shown))
        } catch {
            phase = .failed
        }
    }

    /// Creates the key root, the store and the identity, on this phone.
    func createIdentity() {
        guard onboarding.machine.state == .identityCreation else { return }
        let config = self.config
        do {
            _ = try onboarding.createIdentity(vault: config.vault) { try AppBoot.openStore($0, config) }
            if onboarding.machine.state == .complete {
                becomeReady()
            } else {
                phase = .onboarding(onboarding.machine.state)
            }
        } catch {
            phase = .failed
        }
    }

    /// Opens the home tabs. The chat runtime boots only when this build has a
    /// relay (ADR-012); without one the tabs run with no runtime and say so.
    private func becomeReady() {
        let runtime: ChatRuntime?
        do {
            runtime = try AppBoot.boot(config)
        } catch {
            phase = .failed
            return
        }
        home = HomeModel(runtime: runtime)
        let root = try? KeyRoot.open(from: config.vault)
        let store = root.flatMap { try? AppBoot.openStore($0, config) }
        let profiles = store.map { ProfileRepository(store: $0) }
        let relay = FeedRelay()
        var discovery: NearbyDiscovery?
        if let root, let store, let identity = try? AppBoot.nearbyIdentity(root, store: store) {
            // Discovery runs only while Visible Nearby's radio runs (owner
            // ruling, 2026-11-28); its store side is this main queue, where
            // the store already lives.
            let links = DispatchQueue(label: "lumos.ble.link")
            discovery = NearbyDiscovery(
                link: CoreBluetoothGattLink(queue: links, clock: { Int64(Date().timeIntervalSince1970 * 1000) }),
                scanner: CoreBluetoothScanner(queue: links, millisClock: { Int64(Date().timeIntervalSince1970 * 1000) },
                                              onRestore: { _ in }),
                identity: identity, store: store, linkQueue: links, storage: .main,
                clock: { Int64(Date().timeIntervalSince1970 * 1000) },
                changed: { Task { @MainActor in relay.model?.feedChanged() } })
        }
        let model = VisibilityModel(
            visibility: store.map { DiscoveryVisibility(store: $0) },
            advertiser: DiscoveringAdvertiser(
                advertiser: CoreBluetoothAdvertiser(queue: DispatchQueue(label: "lumos.ble.advertise")),
                discovery: discovery),
            discovery: discovery, profiles: profiles)
        profile = ProfileModel(repository: profiles)
        relay.model = model
        nearby = model
        phase = .ready
    }

    /// Reads a document from the app bundle.
    private static func read(_ document: LegalDocument) throws -> String {
        let name = (document.assetName as NSString).deletingPathExtension
        guard let url = Bundle.main.url(forResource: name, withExtension: "md") else {
            throw LegalDocumentError.unreadable(document.assetName)
        }
        return try String(contentsOf: url, encoding: .utf8)
    }
}

/// Carries "the feed changed" from the store's queue to the Nearby model on
/// the main actor. Only ever read on the main actor, which is the
/// @unchecked Sendable contract.
final class FeedRelay: @unchecked Sendable {
    weak var model: VisibilityModel?
}
