/// Lumos - ios-app/app / The home shell: Nearby, Matches and Chats under one tab bar.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-UX-008, SRS-UX-012, SRS-RMT-001, SRS-RMT-005, TRS-IOS-010
/// ADRs: ADR-012
/// Threading: main-actor
/// Notes: The SwiftUI half of the Android HomeActivity. Matches and Chats are
/// built by the shell's HomeLists from ConversationList, so both apps file and
/// label rows by the same tested rules; this file only draws them. The lists
/// are re-read when the tab bar appears and every few seconds while it shows,
/// so a new message moves its chat to the top. The Chats tab carries a badge
/// with the total unread.
///
/// Names are not stored before CS4, so every row says "Your match". The chat
/// runtime exists only once the relay is configured (ADR-012); until the
/// staging deploy there is none, and both lists are honestly empty. Nearby on
/// iPhone - the Bluetooth half - is not wired into the app yet, and its tab
/// says so rather than pretending to look. Rows open their conversation in
/// the next change.

import Combine
import LumosAppShell
import SwiftUI

/// What the home tabs show.
@MainActor
final class HomeModel: ObservableObject {

    @Published private(set) var lists = HomeLists.Lists.empty
    private let runtime: ChatRuntime?

    init(runtime: ChatRuntime?) {
        self.runtime = runtime
    }

    /// The total unread across chats, for the tab badge.
    var unreadTotal: Int { lists.chats.reduce(0) { $0 + $1.unread } }

    /// Re-reads the conversations. A read that fails leaves the last lists.
    func refresh() {
        guard let runtime, let summaries = try? runtime.conversations.all() else { return }
        lists = HomeLists.build(summaries, nowMillis: Int64(Date().timeIntervalSince1970 * 1000),
                                zone: .current, locale: .current)
    }
}

/// The tab bar.
struct HomeScreen: View {

    @ObservedObject var model: HomeModel
    private let ticks = Timer.publish(every: 5, on: .main, in: .common).autoconnect()

    var body: some View {
        TabView {
            NavigationView { NearbyTab() }
                .navigationViewStyle(.stack)
                .tabItem { Label("Nearby", systemImage: "dot.radiowaves.left.and.right") }
            NavigationView { MatchesTab(rows: model.lists.matches) }
                .navigationViewStyle(.stack)
                .tabItem { Label("Matches", systemImage: "heart") }
            NavigationView { ChatsTab(rows: model.lists.chats) }
                .navigationViewStyle(.stack)
                .tabItem { Label("Chats", systemImage: "bubble.left.and.bubble.right") }
                .badge(model.unreadTotal)
        }
        .onAppear { model.refresh() }
        .onReceive(ticks) { _ in model.refresh() }
    }
}

/// Nearby: Visible Nearby is off, and the iPhone radio half is still to come.
struct NearbyTab: View {
    var body: some View {
        List {
            Section {
                Label("Visible Nearby is off", systemImage: "eye.slash")
                    .font(.headline)
                Text("""
                    While this is on, people near you can see your preview. You choose for how long — an hour, \
                    a custom time, or until you leave the venue — and it turns itself off when that ends. It \
                    never turns itself on.
                    """)
                    .font(.body)
            }
            Section {
                Text("""
                    Finding people nearby on iPhone arrives in a coming update. Nothing is being sent or \
                    looked for right now.
                    """)
                    .font(.callout)
                    .foregroundColor(.secondary)
            }
        }
        .navigationTitle("Nearby")
    }
}

/// An empty list's one line, centred where the rows would be.
private struct EmptyListNote: View {
    let text: LocalizedStringKey
    var body: some View {
        Text(text)
            .font(.body)
            .foregroundColor(.secondary)
            .multilineTextAlignment(.center)
            .padding(32)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// Matches: new matches, nothing said yet.
struct MatchesTab: View {
    let rows: [HomeLists.MatchRow]

    var body: some View {
        Group {
            if rows.isEmpty {
                EmptyListNote(text: """
                    No matches yet. When you and someone nearby are both interested, they show here.
                    """)
            } else {
                List(rows) { _ in
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Your match").font(.headline)
                        Text("New match. Say hi.").font(.subheadline).foregroundColor(.secondary)
                    }
                    .padding(.vertical, 4)
                    .accessibilityElement(children: .combine)
                }
            }
        }
        .navigationTitle("Matches")
    }
}

/// Chats: newest first, each with its last message, time and unread badge.
struct ChatsTab: View {
    let rows: [HomeLists.ChatRow]

    var body: some View {
        Group {
            if rows.isEmpty {
                EmptyListNote(text: "No chats yet. Say hi to a match and the chat shows here.")
            } else {
                List(rows) { ChatRowView(row: $0) }
            }
        }
        .navigationTitle("Chats")
    }
}

/// One chat row.
struct ChatRowView: View {
    let row: HomeLists.ChatRow

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Your match").font(.headline)
                preview
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .lineLimit(2)
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 6) {
                time.font(.caption).foregroundColor(.secondary)
                if row.unread > 0 {
                    Text(row.unreadCapped ? "99+" : "\(row.unread)")
                        .font(.caption.bold())
                        .foregroundColor(.white)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 2)
                        .background(Capsule().fill(Color.accentColor))
                        .accessibilityLabel(Text("\(row.unread) unread"))
                }
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }

    private var preview: Text {
        switch row.preview {
        case .theirs: return Text(verbatim: row.text)
        case .mine: return Text("You: \(row.text)")
        case .deleted: return Text("Message deleted").italic()
        }
    }

    private var time: Text {
        row.when == .yesterday ? Text("Yesterday") : Text(verbatim: row.timeLabel)
    }
}
