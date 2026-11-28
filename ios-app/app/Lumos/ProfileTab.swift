/// Lumos - ios-app/app / The Profile tab: edit each field, choose who sees it, see what others see.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-PRO-001, SRS-PRO-002, SRS-PRO-003, SRS-PRO-008, SRS-PRO-010, SRS-PRO-012, SRS-NFR-A11Y-001
/// ADRs: ADR-003
/// Threading: main actor
/// Notes: The iPhone twin of Android's Profile tab, over the shell's
/// ProfileEditor (which holds the logic and the copy and is tested on
/// Linux). One row per field; tapping it opens an editor sheet with Save,
/// Cancel and, for every optional field, Prefer not to say. Age is a band.
/// Each row's "Who can see this" is a menu of the three audiences; every
/// field starts as "Only your matches". "See what others see" shows exactly
/// what each audience would get. While name or age is missing a note says
/// the profile cannot be seen yet.

import Combine
import LumosAppShell
import SwiftUI

/// Publishes the shell's ProfileEditor to SwiftUI.
@MainActor
final class ProfileModel: ObservableObject {
    @Published private(set) var revision = 0
    let editor: ProfileEditor

    init(repository: ProfileRepository?) {
        editor = ProfileEditor(repository: repository)
    }

    func reload() { editor.reload(); revision += 1 }
    func set(_ field: ProfileField, _ value: FieldValue) { editor.set(field, value); revision += 1 }
    func setAudience(_ field: ProfileField, _ audience: ProfileFieldPolicy.Audience) {
        editor.setAudience(field, audience)
        revision += 1
    }
}

/// The Profile tab.
struct ProfileTab: View {
    @ObservedObject var model: ProfileModel
    @State private var editing: Editing?
    @State private var previewing: Previewing?

    /// Sheet and alert items (wrappers, so no shell type needs a retroactive conformance).
    private struct Editing: Identifiable {
        let field: ProfileField
        var id: String { field.rawValue }
    }

    private struct Previewing: Identifiable {
        let audience: ProfileFieldPolicy.Audience
        var id: Int { audience.rawValue }
    }

    var body: some View {
        List {
            Section {
                Text("Only you see this page. Each line says who else can see it, and you choose.")
                    .font(.callout).foregroundColor(.secondary)
                if model.editor.unreadable {
                    Text("Your profile could not be opened just now. Nothing was changed.")
                } else if model.editor.notSaved {
                    Text("That change was not saved. Please try again.")
                } else if !model.editor.isShowable {
                    Text("Add your name and age so people nearby can see you.")
                }
            }
            ForEach(ProfileEditor.edited, id: \.self) { field in
                Section { row(field) }
            }
            Section {
                ForEach([ProfileFieldPolicy.Audience.nearby, .requester, .match], id: \.self) { audience in
                    Button(previewTitle(audience)) { previewing = Previewing(audience: audience) }
                        .frame(minHeight: 44)
                }
            } header: {
                Text("See what others see")
            }
        }
        .navigationTitle("Profile")
        .onAppear { model.reload() }
        .sheet(item: $editing) { item in
            FieldEditor(field: item.field, value: model.editor.profile.value(item.field)) { model.set(item.field, $0) }
        }
        .alert(item: $previewing) { item in
            let lines = model.editor.previewLines(for: item.audience)
            return Alert(title: Text(previewTitle(item.audience)),
                         message: Text(lines.isEmpty ? "They see nothing from your profile yet."
                                                     : lines.joined(separator: "\n")),
                         dismissButton: .default(Text("Close")))
        }
    }

    private func row(_ field: ProfileField) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Button { editing = Editing(field: field) } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text(ProfileEditor.label(field)).font(.subheadline.weight(.semibold))
                    Text(ProfileEditor.shown(model.editor.profile.value(field))).font(.body)
                }
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            }
            .foregroundColor(.primary)
            Menu {
                ForEach(ProfileEditor.choosable, id: \.self) { audience in
                    Button(ProfileEditor.audienceName(audience)) { model.setAudience(field, audience) }
                }
            } label: {
                Text("Who can see this: \(ProfileEditor.audienceName(model.editor.policy.threshold(field)))")
                    .font(.footnote)
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            }
            .accessibilityHint(Text("Change who can see your \(ProfileEditor.label(field).lowercased())"))
        }
    }

    private func previewTitle(_ audience: ProfileFieldPolicy.Audience) -> String {
        switch audience {
        case .nearby: return "As someone nearby"
        case .requester: return "As someone you show interest in"
        default: return "As a match"
        }
    }
}

/// One field's editor sheet.
private struct FieldEditor: View {
    let field: ProfileField
    let value: FieldValue
    let save: (FieldValue) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""

    var body: some View {
        NavigationView {
            Form {
                if field == .ageBand {
                    ForEach(ProfileEditor.ageBands, id: \.self) { band in
                        Button { save(.statement(band)); dismiss() } label: {
                            HStack {
                                Text(band)
                                Spacer()
                                if value == .stated(band) { Image(systemName: "checkmark") }
                            }
                        }
                        .foregroundColor(.primary)
                    }
                } else {
                    Section {
                        TextField(ProfileEditor.hint(field), text: $text)
                            .onChange(of: text) { next in
                                let limit = field == .bio || field == .interests
                                    ? ProfileEditor.longMaxCharacters : ProfileEditor.shortMaxCharacters
                                if next.count > limit { text = String(next.prefix(limit)) }
                            }
                    }
                    if !field.isRequiredForCompleteness {
                        Button("Prefer not to say") { save(.preferNotToSay); dismiss() }
                    }
                }
            }
            .navigationTitle(ProfileEditor.label(field))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                // The condition sits inside the item: a conditional toolbar
                // item itself needs iOS 16, and the floor is iOS 15.
                ToolbarItem(placement: .confirmationAction) {
                    if field != .ageBand {
                        Button("Save") { save(.statement(text)); dismiss() }
                    }
                }
            }
            .onAppear { text = value.text }
        }
    }
}
