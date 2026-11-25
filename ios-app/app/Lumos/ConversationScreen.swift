/// Lumos - ios-app/app / One conversation: bubbles, replies, reactions, deletes, and the safety menu.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-RMT-003, SRS-RMT-005, SRS-RMT-010, SRS-RMT-011, SRS-SAF-001, SRS-UX-011, TRS-IOS-010
/// ADRs: -
/// Threading: main-actor
/// Notes: The SwiftUI half of the Android ConversationActivity. Every rule
/// lives in the shell's ConversationPresenter and ConversationRows, tested on
/// Linux and macOS; this file only draws them. Words are the Android strings.
///
/// A long press offers what the presenter allows: reply, a fixed set of
/// reactions, copy, delete for me, delete for everyone (after a
/// confirmation). The shield menu offers Unmatch and Block, each confirmed
/// with the same plain words as Android - the other person is not told, and
/// it cannot be undone - and the screen closes after. A reply's quote is
/// drawn from this phone's copy; only the answered message's id is sent
/// (CR-009). The conversation is marked read while it is on screen.

import Combine
import LumosAppShell
import SwiftUI
import UIKit

/// The conversation screen's state.
@MainActor
final class ConversationModel: ObservableObject {

    @Published private(set) var rows: [ConversationRows.Row] = []
    @Published var draft = ""
    @Published var replyingTo: ConversationRows.Bubble?
    @Published private(set) var notice: LocalizedStringKey?
    @Published private(set) var closed = false

    let presenter: ConversationPresenter

    init(presenter: ConversationPresenter) {
        self.presenter = presenter
    }

    /// Re-reads the conversation, and marks what is now on screen read.
    func refresh() {
        guard presenter.isOpen else {
            closed = true
            return
        }
        rows = presenter.rows(nowMillis: Int64(Date().timeIntervalSince1970 * 1000), zone: .current,
                              locale: .current)
        presenter.markRead()
    }

    var canSend: Bool { ConversationPresenter.canSend(draft) }

    func send() {
        let result = replyingTo.map { presenter.sendReply(draft, to: $0) } ?? presenter.sendText(draft)
        switch result {
        case .sent:
            draft = ""
            replyingTo = nil
            notice = nil
        case .empty:
            notice = nil
        case .tooLong:
            notice = "This message is too long. Try splitting it in two."
        case .notReady:
            notice = "This chat is still being set up. Your message is kept. Try again soon."
        case .failed:
            notice = "That did not send. Your message is kept. Try again."
        }
        refresh()
    }

    func react(_ b: ConversationRows.Bubble, _ reaction: String) {
        _ = presenter.react(b, reaction)
        refresh()
    }

    func deleteForMe(_ b: ConversationRows.Bubble) {
        _ = presenter.deleteForMe(b)
        refresh()
    }

    func deleteForEveryone(_ b: ConversationRows.Bubble) {
        _ = presenter.deleteForEveryone(b)
        refresh()
    }

    func end(_ how: ConversationSafety.Ending) {
        if presenter.end(how) {
            closed = true
        } else {
            notice = "That did not finish. Nothing changed. Try again."
        }
    }
}

/// The screen.
struct ConversationScreen: View {

    @StateObject private var model: ConversationModel
    @Environment(\.dismiss) private var dismiss
    @State private var confirmDelete: ConversationRows.Bubble?
    @State private var confirmEnding: ConversationSafety.Ending?
    private let ticks = Timer.publish(every: 3, on: .main, in: .common).autoconnect()

    init(makeModel: @escaping () -> ConversationModel) {
        _model = StateObject(wrappedValue: makeModel())
    }

    var body: some View {
        VStack(spacing: 0) {
            messages
            if let notice = model.notice {
                Text(notice)
                    .font(.footnote)
                    .foregroundColor(.secondary)
                    .padding(.horizontal)
                    .padding(.top, 6)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            Composer(model: model)
        }
        .navigationTitle("Your match")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Menu {
                    Button { confirmEnding = .unmatch } label: { Label("Unmatch", systemImage: "heart.slash") }
                    Button(role: .destructive) { confirmEnding = .block } label: {
                        Label("Block", systemImage: "hand.raised")
                    }
                } label: {
                    Image(systemName: "shield.lefthalf.filled")
                        .accessibilityLabel(Text("Safety options"))
                }
            }
        }
        .confirmationDialog(endingTitle, isPresented: endingShown, titleVisibility: .visible) {
            Button(confirmEnding == .block ? "Block" : "Unmatch", role: .destructive) {
                if let how = confirmEnding { model.end(how) }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(endingBody)
        }
        .confirmationDialog("Delete for everyone?", isPresented: deleteShown, titleVisibility: .visible) {
            Button("Delete for everyone", role: .destructive) {
                if let b = confirmDelete { model.deleteForEveryone(b) }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("It is removed for both of you. They may already have read it.")
        }
        .onAppear { model.refresh() }
        .onReceive(ticks) { _ in model.refresh() }
        .onChange(of: model.closed) { closed in
            if closed { dismiss() }
        }
    }

    private var messages: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 2) {
                    if model.rows.isEmpty {
                        Text("Say hi. Only the two of you can read this chat.")
                            .font(.body)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                            .padding(32)
                    }
                    ForEach(model.rows) { row in
                        switch row {
                        case .divider(let d):
                            DayDividerView(divider: d)
                        case .bubble(let b):
                            BubbleView(bubble: b)
                                .contextMenu { actions(for: b) }
                        }
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
            }
            .onChange(of: model.rows.count) { _ in
                if let last = model.rows.last { proxy.scrollTo(last.id, anchor: .bottom) }
            }
        }
    }

    @ViewBuilder
    private func actions(for b: ConversationRows.Bubble) -> some View {
        let allowed = ConversationPresenter.actions(for: b)
        if allowed.contains(.reply) {
            Button { model.replyingTo = b } label: { Label("Reply", systemImage: "arrowshape.turn.up.left") }
        }
        if allowed.contains(.react) {
            Menu {
                ForEach(ConversationPresenter.reactions, id: \.self) { r in
                    Button(r) { model.react(b, r) }
                }
            } label: { Label("React", systemImage: "face.smiling") }
        }
        if allowed.contains(.copy) {
            Button { UIPasteboard.general.string = b.text } label: { Label("Copy text", systemImage: "doc.on.doc") }
        }
        if allowed.contains(.deleteForMe) {
            Button { model.deleteForMe(b) } label: { Label("Delete for me", systemImage: "trash") }
        }
        if allowed.contains(.deleteForEveryone) {
            Button(role: .destructive) { confirmDelete = b } label: {
                Label("Delete for everyone", systemImage: "trash.slash")
            }
        }
    }

    private var endingShown: Binding<Bool> {
        Binding(get: { confirmEnding != nil }, set: { if !$0 { confirmEnding = nil } })
    }

    private var deleteShown: Binding<Bool> {
        Binding(get: { confirmDelete != nil }, set: { if !$0 { confirmDelete = nil } })
    }

    private var endingTitle: Text {
        confirmEnding == .block ? Text("Block your match?") : Text("Unmatch your match?")
    }

    private var endingBody: LocalizedStringKey {
        if confirmEnding == .block {
            return """
                This chat is deleted from this phone. They can no longer message you or appear in your Nearby. \
                They are not told. This cannot be undone.
                """
        }
        return """
            This chat is deleted from this phone. Their messages stop reaching you. They are not told. This \
            cannot be undone.
            """
    }
}

/// Today, Yesterday or the date, centred between messages.
struct DayDividerView: View {
    let divider: ConversationRows.DayDivider
    var body: some View {
        Group {
            switch divider.day {
            case .today: Text("Today")
            case .yesterday: Text("Yesterday")
            case .date: Text(verbatim: divider.dateLabel)
            }
        }
        .font(.caption.weight(.semibold))
        .foregroundColor(.secondary)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity)
        .accessibilityAddTraits(.isHeader)
    }
}

/// One message.
struct BubbleView: View {
    let bubble: ConversationRows.Bubble

    var body: some View {
        HStack {
            if bubble.outgoing { Spacer(minLength: 48) }
            VStack(alignment: bubble.outgoing ? .trailing : .leading, spacing: 2) {
                VStack(alignment: .leading, spacing: 4) {
                    if let quote = bubble.quote { QuoteView(quote: quote, outgoing: bubble.outgoing) }
                    words
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(fill))
                .foregroundColor(bubble.outgoing && !bubble.deleted ? .white : .primary)
                if !bubble.myReaction.isEmpty || !bubble.peerReaction.isEmpty {
                    Text(verbatim: bubble.peerReaction + bubble.myReaction).font(.callout)
                }
                if bubble.showsMeta { meta }
            }
            if !bubble.outgoing { Spacer(minLength: 48) }
        }
        .padding(.top, bubble.position == .single || bubble.position == .first ? 6 : 0)
        .accessibilityElement(children: .combine)
    }

    private var fill: Color {
        if bubble.deleted { return Color(.secondarySystemBackground) }
        return bubble.outgoing ? .accentColor : Color(.secondarySystemBackground)
    }

    @ViewBuilder private var words: some View {
        if bubble.deleted {
            Text(bubble.outgoing ? "You deleted this message." : "This message was deleted.")
                .italic()
                .foregroundColor(.secondary)
        } else {
            Text(verbatim: bubble.text).font(.body)
        }
    }

    private var meta: some View {
        HStack(spacing: 4) {
            if bubble.edited { Text("Edited ·") }
            Text(verbatim: bubble.timeLabel)
            if bubble.outgoing { TickView(tick: bubble.tick) }
        }
        .font(.caption2)
        .foregroundColor(.secondary)
    }
}

/// The quoted line above a reply, drawn from this phone's copy.
struct QuoteView: View {
    let quote: ConversationRows.Quote
    let outgoing: Bool

    var body: some View {
        HStack(spacing: 6) {
            Rectangle().frame(width: 3).opacity(0.6)
            VStack(alignment: .leading, spacing: 1) {
                switch quote.kind {
                case .text:
                    Text(quote.fromMe ? "You" : "Your match").font(.caption.bold())
                    Text(verbatim: quote.text).font(.caption).lineLimit(2)
                case .deleted:
                    Text("Original message deleted").font(.caption).italic()
                case .unavailable:
                    Text("Original message not available").font(.caption).italic()
                }
            }
        }
        .opacity(0.85)
        .fixedSize(horizontal: false, vertical: true)
    }
}

/// The delivery tick under your own messages.
struct TickView: View {
    let tick: ConversationRows.Tick

    var body: some View {
        switch tick {
        case .waiting:
            Image(systemName: "clock").accessibilityLabel(Text("Waiting to send"))
        case .sent:
            Image(systemName: "checkmark").accessibilityLabel(Text("Sent"))
        case .delivered:
            Image(systemName: "checkmark.circle").accessibilityLabel(Text("Delivered"))
        case .read:
            Image(systemName: "checkmark.circle.fill").accessibilityLabel(Text("Read"))
        case .failed:
            Image(systemName: "exclamationmark.circle").accessibilityLabel(Text("Not sent"))
        }
    }
}

/// The reply bar and the text field.
struct Composer: View {
    @ObservedObject var model: ConversationModel

    var body: some View {
        VStack(spacing: 6) {
            if let answering = model.replyingTo {
                HStack {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(answering.outgoing ? "You" : "Your match").font(.caption.bold())
                        Text(verbatim: answering.text).font(.caption).lineLimit(1).foregroundColor(.secondary)
                    }
                    Spacer()
                    Button { model.replyingTo = nil } label: {
                        Image(systemName: "xmark.circle.fill").foregroundColor(.secondary)
                    }
                    .accessibilityLabel(Text("Cancel"))
                }
            }
            HStack(spacing: 8) {
                TextField("Message", text: $model.draft)
                    .textFieldStyle(.roundedBorder)
                    .submitLabel(.send)
                    .onSubmit { if model.canSend { model.send() } }
                Button { model.send() } label: {
                    Image(systemName: "arrow.up.circle.fill").font(.title2)
                }
                .disabled(!model.canSend)
                .accessibilityLabel(Text("Send"))
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
        .background(Color(.systemBackground))
    }
}
