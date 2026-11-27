/// Lumos - ios-app/app / The home shell: Nearby, Matches and Chats under one tab bar.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-UX-008, SRS-UX-012, SRS-RMT-001, SRS-RMT-005, SRS-RMT-014, TRS-IOS-010
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
/// staging deploy there is none, and both lists are honestly empty. The
/// Nearby tab (NearbyTab.swift) holds Visible Nearby. A row opens its
/// conversation. Until a settings place exists, the Chats tab starts with
/// the read-receipt switch (SRS-RMT-014), as on Android: shown only once the
/// stored choice is read (so not at all without a runtime), and a save that
/// fails shows the stored choice again.

import Combine
import LumosAppShell
import SwiftUI

/// What the home tabs show.
@MainActor
final class HomeModel: ObservableObject {

    @Published private(set) var lists = HomeLists.Lists.empty
    /// Whether there is a stored read-receipt choice to show (not without a runtime).
    @Published private(set) var readReceiptsKnown = false
    /// The read-receipt switch. Set by the person through the switch, it is
    /// stored at once; set from the store, it is only shown.
    @Published var readReceiptsOn = false {
        didSet {
            if !showingStored && readReceiptsOn != oldValue {
                save(readReceipts: readReceiptsOn)
            }
        }
    }
    private var showingStored = false
    private let runtime: ChatRuntime?
    /// The conversation screens' source; nil without a runtime, and then
    /// there are no rows to open either.
    let conversations: RuntimeConversationSource?

    init(runtime: ChatRuntime?) {
        self.runtime = runtime
        self.conversations = runtime.map { RuntimeConversationSource(runtime: $0) }
    }

    /// The model for one conversation screen.
    func conversation(_ matchID: Data, source: RuntimeConversationSource) -> ConversationModel {
        ConversationModel(presenter: ConversationPresenter(source: source, matchID: matchID))
    }

    /// The total unread across chats, for the tab badge.
    var unreadTotal: Int { lists.chats.reduce(0) { $0 + $1.unread } }

    /// Re-reads the conversations. A read that fails leaves the last lists.
    func refresh() {
        showStoredReadReceipts()
        guard let runtime, let summaries = try? runtime.conversations.all() else { return }
        lists = HomeLists.build(summaries, nowMillis: Int64(Date().timeIntervalSince1970 * 1000),
                                zone: .current, locale: .current)
    }

    /// Shows the stored choice without storing it again.
    private func showStoredReadReceipts() {
        let stored = try? runtime?.settings.readReceipts()
        showingStored = true
        readReceiptsKnown = stored != nil
        readReceiptsOn = stored ?? false
        showingStored = false
    }

    /// Stores the person's choice; afterwards the switch shows what is
    /// stored, so a save that failed puts it back.
    private func save(readReceipts enabled: Bool) {
        if let runtime {
            try? runtime.settings.setReadReceipts(enabled, atUtcMillis: Int64(Date().timeIntervalSince1970 * 1000))
        }
        showStoredReadReceipts()
    }
}

/// The tab bar.
struct HomeScreen: View {

    @ObservedObject var model: HomeModel
    let nearby: VisibilityModel
    private let ticks = Timer.publish(every: 5, on: .main, in: .common).autoconnect()

    var body: some View {
        TabView {
            NavigationView { NearbyTab(model: nearby) }
                .navigationViewStyle(.stack)
                .tabItem { Label("Nearby", systemImage: "dot.radiowaves.left.and.right") }
            NavigationView { MatchesTab(rows: model.lists.matches, home: model) }
                .navigationViewStyle(.stack)
                .tabItem { Label("Matches", systemImage: "heart") }
            NavigationView { ChatsTab(rows: model.lists.chats, home: model) }
                .navigationViewStyle(.stack)
                .tabItem { Label("Chats", systemImage: "bubble.left.and.bubble.right") }
                .badge(model.unreadTotal)
        }
        .onAppear { model.refresh() }
        .onReceive(ticks) { _ in model.refresh() }
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
    let home: HomeModel

    var body: some View {
        Group {
            if rows.isEmpty {
                EmptyListNote(text: """
                    No matches yet. When you and someone nearby are both interested, they show here.
                    """)
            } else {
                List(rows) { row in
                    OpensConversation(home: home, matchID: row.matchID) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Your match").font(.headline)
                            Text("New match. Say hi.").font(.subheadline).foregroundColor(.secondary)
                        }
                        .padding(.vertical, 4)
                        .accessibilityElement(children: .combine)
                    }
                }
            }
        }
        .navigationTitle("Matches")
    }
}

/// Chats: newest first, each with its last message, time and unread badge.
struct ChatsTab: View {
    let rows: [HomeLists.ChatRow]
    @ObservedObject var home: HomeModel

    var body: some View {
        VStack(spacing: 0) {
            if home.readReceiptsKnown {
                ReadReceiptsSwitch(isOn: $home.readReceiptsOn)
            }
            if rows.isEmpty {
                EmptyListNote(text: "No chats yet. Say hi to a match and the chat shows here.")
            } else {
                List(rows) { row in
                    OpensConversation(home: home, matchID: row.matchID) { ChatRowView(row: row) }
                }
            }
        }
        .navigationTitle("Chats")
    }
}

/// The read-receipt switch and its one line (SRS-RMT-014), the Android copy.
struct ReadReceiptsSwitch: View {
    @Binding var isOn: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Toggle("Read receipts", isOn: $isOn)
                .font(.body)
                .frame(minHeight: 44)
            Text("""
                When this is on, people you chat with can see when you have read their messages. \
                Delivered ticks are always sent.
                """)
                .font(.footnote)
                .foregroundColor(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }
}

/// A row that opens its conversation.
struct OpensConversation<Content: View>: View {
    let home: HomeModel
    let matchID: Data
    @ViewBuilder let label: Content

    var body: some View {
        if let source = home.conversations {
            NavigationLink(destination: ConversationScreen(makeModel: { home.conversation(matchID, source: source) })) {
                label
            }
        } else {
            label
        }
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
