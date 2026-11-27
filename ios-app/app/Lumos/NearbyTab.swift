/// Lumos - ios-app/app / The Nearby tab: Visible Nearby, chosen for a while, and the radio behind it.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-ONB-004, SRS-ONB-008, SRS-NBY-001, SRS-NBY-002, SRS-NBY-003, SRS-NBY-005, SRS-NBY-010,
///             SRS-NBY-012, TRS-IOS-003
/// ADRs: ADR-002
/// Threading: main-actor
/// Notes: The shell's DiscoveryVisibility and VisibleNearbyController own the
/// rules (tested on Linux and macOS); this file only draws them. The screen
/// and the radio come from the same one evaluation, so they never disagree.
///
/// - Off until the person turns it on, for an hour or a time they choose
///   (the register's 15 minutes to 8 hours). "Until you leave the venue" is
///   not offered on iPhone yet: there is no venue-exit detection here, and
///   offering it would mean visible with no end.
/// - The Bluetooth permission is asked the first time the person turns it on
///   (SRS-ONB-004), never at launch.
/// - While visible and the app is open, the phone advertises the Lumos
///   service identifier alone (ADR-002): nothing about the person. It stops
///   the moment the window ends, the person turns it off, or the app leaves
///   the screen; background advertising waits for a slice that can also stop
///   it on time while suspended (SRS-NBY-002).
/// - If Bluetooth is not allowed or not supported, visibility turns off and
///   the tab says why; if Bluetooth is merely off, the window keeps running
///   and the tab says nobody can see you until it is on.
/// - People nearby (NB-1): while visible and open, the phone also looks for
///   and connects to other Lumos phones (owner ruling, 2026-11-28: you see
///   others only while they can see you). Each card is a person whose HELLO
///   verified, after the hide and block vetoes; Hide removes one silently.
///   The copy matches Android's word for word.

import Combine
import LumosAppShell
import SwiftUI

/// Publishes the shell's VisibleNearbyController to SwiftUI.
@MainActor
final class VisibilityModel: ObservableObject {

    @Published private(set) var indicator = DiscoveryVisibility.Indicator.off
    @Published private(set) var problem: VisibleNearbyController.RadioProblem?
    @Published private(set) var endedOnItsOwn = false

    @Published private(set) var feed = FeedState.notVisible

    private var controller: VisibleNearbyController?
    private let discovery: NearbyDiscovery?

    /// - Parameters:
    ///   - visibility: the persisted state; nil when the store did not open
    ///   - advertiser: the radio
    ///   - discovery: live discovery, or nil when the identity could not be unlocked
    init(visibility: DiscoveryVisibility?, advertiser: BleAdvertising, discovery: NearbyDiscovery?) {
        self.discovery = discovery
        guard let visibility else { return }
        // The failure arrives on the radio's queue: a Sendable, non-isolated
        // closure hops it to the main actor, where the controller lives.
        controller = VisibleNearbyController(
            visibility: visibility, advertiser: advertiser,
            clock: { Int64(Date().timeIntervalSince1970 * 1000) },
            failureHop: { @Sendable [weak self] failure in
                Task { @MainActor in self?.radioFailed(failure) }
            })
    }

    private func radioFailed(_ failure: BleAdvertiseFailure) {
        controller?.radioFailed(failure)
        publish()
    }

    /// Whether the controls can work at all (the store opened).
    var available: Bool { controller != nil }

    func refresh() { controller?.refresh(); publish() }
    func turnOn(_ selection: DiscoveryVisibility.Selection) { controller?.turnOn(selection); publish() }
    func turnOff() { controller?.turnOff(); publish() }
    func setForeground(_ active: Bool) { controller?.setForeground(active); publish() }
    func hide(_ peerKey: String) { discovery?.hideCard(peerKey: peerKey) }
    func feedChanged() { feed = discovery?.state ?? .notVisible }

    private func publish() {
        feedChanged()
        guard let controller else { return }
        indicator = controller.indicator
        problem = controller.problem
        endedOnItsOwn = controller.endedOnItsOwn
    }
}

/// The Nearby tab.
struct NearbyTab: View {

    @ObservedObject var model: VisibilityModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var choosingTime = false
    private let ticks = Timer.publish(every: 5, on: .main, in: .common).autoconnect()

    var body: some View {
        List {
            Section {
                if model.indicator.visible {
                    onControls
                } else {
                    offControls
                }
            } header: {
                Text("Visible Nearby")
            } footer: {
                Text("""
                    Lumos never turns this on by itself — not when you install it, not when it updates, not \
                    when you restart your phone, and not when you allow Bluetooth. Only you turn it on.
                    """)
            }
            if let problem = model.problem {
                Section { Text(problemText(problem)).foregroundColor(.red) }
            }
            Section {
                PeopleNearby(feed: model.feed) { model.hide($0) }
            } header: {
                Text("People nearby")
            }
        }
        .navigationTitle("Nearby")
        .sheet(isPresented: $choosingTime) { ChooseTimeSheet(model: model) }
        .onAppear { model.refresh() }
        .onReceive(ticks) { _ in model.refresh() }
        .onChange(of: scenePhase) { phase in model.setForeground(phase == .active) }
    }

    @ViewBuilder private var onControls: some View {
        Label("You are visible to people nearby", systemImage: "dot.radiowaves.left.and.right")
            .font(.headline)
            .foregroundColor(.accentColor)
        Text(until).font(.body)
        Text("You can turn this off at any time and it stops immediately.")
            .font(.callout)
            .foregroundColor(.secondary)
        Button(role: .destructive) { model.turnOff() } label: {
            Text("Turn off").frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
        .controlSize(.large)
    }

    @ViewBuilder private var offControls: some View {
        Label("Visible Nearby is off", systemImage: "eye.slash").font(.headline)
        Text("You are not visible to anyone right now. Nothing is being broadcast from your phone.")
            .font(.body)
        if model.endedOnItsOwn {
            Text("Visible Nearby turned itself off. Turn it on again any time.")
                .font(.callout)
                .foregroundColor(.secondary)
        }
        Button { model.turnOn(.defaultSession) } label: {
            Text("For an hour").frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .disabled(!model.available)
        Button { choosingTime = true } label: {
            Text("For a time you choose").frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
        .controlSize(.large)
        .disabled(!model.available)
    }

    private var until: String {
        let end = Date(timeIntervalSince1970: TimeInterval(model.indicator.expiresAtMillis) / 1000)
        let time = DateFormatter.localizedString(from: end, dateStyle: .none, timeStyle: .short)
        return String(format: NSLocalizedString("Visible until %@", comment: "Visible Nearby end time"), time)
    }

    private func problemText(_ problem: VisibleNearbyController.RadioProblem) -> LocalizedStringKey {
        switch problem {
        case .bluetoothOff:
            return "Bluetooth is off. Nobody can see you until you turn it on in Control Centre or Settings."
        case .notAllowed:
            return "Lumos is not allowed to use Bluetooth, so Visible Nearby is off. You can allow it in Settings → Lumos."
        case .unsupported:
            return "This phone cannot be visible over Bluetooth, so Visible Nearby is off."
        case .refused:
            return "Bluetooth did not start, so Visible Nearby is off. Try again in a moment."
        }
    }
}

/// Chooses a custom while, inside the register's bounds.
struct ChooseTimeSheet: View {

    @ObservedObject var model: VisibilityModel
    @Environment(\.dismiss) private var dismiss
    /// Starts on the default session's length, the register's one hour.
    @State private var minutes = DiscoveryVisibility.Selection.defaultSession.durationSeconds / 60

    private let stepMinutes = 15

    var body: some View {
        NavigationView {
            Form {
                Section {
                    Stepper(value: $minutes, in: minMinutes...maxMinutes, step: stepMinutes) {
                        Text(label).font(.title3.monospacedDigit())
                    }
                } footer: {
                    Text("It turns itself off when this time is up.")
                }
            }
            .navigationTitle("For a time you choose")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Turn on") {
                        if let selection = DiscoveryVisibility.Selection.custom(seconds: minutes * secondsPerMinute) {
                            model.turnOn(selection)
                        }
                        dismiss()
                    }
                }
            }
        }
    }

    private let secondsPerMinute = 60
    private var minMinutes: Int { DiscoveryVisibility.Selection.minimumSeconds / secondsPerMinute }
    private var maxMinutes: Int { DiscoveryVisibility.Selection.maximumSeconds / secondsPerMinute }

    private var label: String {
        let formatter = DateComponentsFormatter()
        formatter.allowedUnits = [.hour, .minute]
        formatter.unitsStyle = .full
        return formatter.string(from: TimeInterval(minutes * secondsPerMinute)) ?? ""
    }
}

/// The people-nearby section: one message or the cards, never a spinner
/// (SRS-NBY-014), in Android's words.
struct PeopleNearby: View {
    let feed: FeedState
    let hide: (String) -> Void

    var body: some View {
        switch feed {
        case .cards(let cards):
            ForEach(cards, id: \.peerKey) { card in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Someone nearby").font(.headline)
                        Text("Here right now").font(.callout).foregroundColor(.secondary)
                    }
                    Spacer()
                    Button("Hide") { hide(card.peerKey) }
                        .buttonStyle(.bordered)
                }
                .frame(minHeight: 44)
            }
        case .notVisible:
            note("You see people nearby only while they can see you too. Turn on Visible Nearby above to start.")
        case .scanningEmpty:
            note("Looking around. No one nearby yet — people appear here the moment Lumos is sure they are real.")
        case .radioOff:
            note("Bluetooth is off. Turn it on to see people nearby.")
        case .permissionNeeded:
            note("Lumos needs Bluetooth permission to find people near you. You can grant it in Settings.")
        case .radioUnavailable:
            note("Nearby is not available on this phone right now.")
        case .radioBusy:
            note("The radio is catching its breath. Nearby will continue in a moment.")
        }
    }

    private func note(_ text: String) -> some View {
        Text(text).font(.callout).foregroundColor(.secondary)
    }
}
