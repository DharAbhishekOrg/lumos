/// Lumos - ios-app/app / The Profile tab: edit each field, choose who sees it, see what others see.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-PRO-001, SRS-PRO-002, SRS-PRO-003, SRS-PRO-005, SRS-PRO-008, SRS-PRO-009, SRS-PRO-010,
///             SRS-PRO-012, SRS-NFR-A11Y-001
/// ADRs: ADR-003
/// Threading: main actor
/// Notes: The iPhone twin of Android's Profile tab, over the shell's
/// ProfileEditor (which holds the logic and the copy and is tested on
/// Linux). Photos first: thumbnails in order (the first is the main one),
/// "Add a photo" through the system photo picker (iOS 16 and later; it
/// needs no photo-library permission and hands over one picture), and a tap
/// on a thumbnail for make main, move or remove (remove asks first). Every
/// picked picture is re-drawn, sanitised and encrypted by the shell before a
/// thumbnail exists. Then one row per field; tapping it opens an editor sheet with Save,
/// Cancel and, for every optional field, Prefer not to say. Age is a band.
/// Each row's "Who can see this" is a menu of the three audiences; every
/// field starts as "Only your matches". "See what others see" shows exactly
/// what each audience would get. While name or age is missing a note says
/// the profile cannot be seen yet.

import Combine
import LumosAppShell
import PhotosUI
import SwiftUI
import UIKit

/// Publishes the shell's ProfileEditor to SwiftUI.
@MainActor
final class ProfileModel: ObservableObject {
    @Published private(set) var revision = 0
    /// Decoded thumbnails by photo identifier, rebuilt when the photos change.
    @Published private(set) var thumbnails: [String: UIImage] = [:]
    let editor: ProfileEditor

    init(repository: ProfileRepository?, photos: ProfilePhotos? = nil) {
        editor = ProfileEditor(repository: repository, photos: photos)
        refreshThumbnails()
    }

    func reload() { editor.reload(); changed() }
    func addPhoto(_ picked: Data) { editor.addPhoto(picked); changed() }
    func makePrimaryPhoto(_ id: String) { editor.makePrimaryPhoto(id); changed() }
    func movePhoto(_ id: String, to index: Int) { editor.movePhoto(id, to: index); changed() }
    func removePhoto(_ id: String) { editor.removePhoto(id); changed() }

    private func changed() {
        refreshThumbnails()
        revision += 1
    }

    private func refreshThumbnails() {
        var next: [String: UIImage] = [:]
        for media in editor.profile.media {
            if let image = thumbnails[media.objectId]
                ?? editor.photoBytes(media.objectId).flatMap({ UIImage(data: $0) }) {
                next[media.objectId] = image
            }
        }
        thumbnails = next
    }
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
    @State private var choosing: PhotoChoice?
    @State private var removing: PhotoChoice?

    /// Sheet and alert items (wrappers, so no shell type needs a retroactive conformance).
    private struct Editing: Identifiable {
        let field: ProfileField
        var id: String { field.rawValue }
    }

    private struct Previewing: Identifiable {
        let audience: ProfileFieldPolicy.Audience
        var id: Int { audience.rawValue }
    }

    private struct PhotoChoice: Identifiable {
        let id: String
        let place: Int
        let count: Int
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
            if model.editor.photosAvailable {
                photosSection
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

    private var photosSection: some View {
        Section {
            let media = model.editor.profile.media
            if !media.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(Array(media.enumerated()), id: \.element.objectId) { place, photo in
                            thumbnail(photo.objectId, place: place, count: media.count)
                        }
                    }
                }
            }
            Text(media.isEmpty ? "No photos yet. Your first photo is your main one."
                               : "Your first photo is your main one. Tap a photo to change the order or remove it.")
                .font(.footnote).foregroundColor(.secondary)
            if let notice = model.editor.photoNotice {
                Text(notice.text)
            }
            if #available(iOS 16.0, *) {
                PhotoAddButton { model.addPhoto($0) }
            } else {
                Text("Adding photos needs iOS 16 or later.").font(.footnote)
            }
            Menu {
                ForEach(ProfileEditor.choosable, id: \.self) { audience in
                    Button(ProfileEditor.audienceName(audience)) { model.setAudience(.media, audience) }
                }
            } label: {
                Text("Who can see this: \(ProfileEditor.audienceName(model.editor.policy.threshold(.media)))")
                    .font(.footnote)
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            }
            .accessibilityHint(Text("Change who can see your photos"))
        } header: {
            Text("Photos")
        }
        .confirmationDialog(choosing.map { ProfileEditor.photoDescription(place: $0.place, count: $0.count) } ?? "",
                            isPresented: Binding(get: { choosing != nil }, set: { if !$0 { choosing = nil } }),
                            titleVisibility: .visible, presenting: choosing) { choice in
            if choice.place > 0 {
                Button("Make this my main photo") { model.makePrimaryPhoto(choice.id) }
                Button("Move earlier") { model.movePhoto(choice.id, to: choice.place - 1) }
            }
            if choice.place < choice.count - 1 {
                Button("Move later") { model.movePhoto(choice.id, to: choice.place + 1) }
            }
            Button("Remove photo", role: .destructive) { removing = choice }
        }
        .alert(item: $removing) { choice in
            Alert(title: Text("Remove this photo?"), message: Text("It is deleted from this phone."),
                  primaryButton: .destructive(Text("Remove photo")) { model.removePhoto(choice.id) },
                  secondaryButton: .cancel())
        }
    }

    private func thumbnail(_ id: String, place: Int, count: Int) -> some View {
        Button { choosing = PhotoChoice(id: id, place: place, count: count) } label: {
            Group {
                if let image = model.thumbnails[id] {
                    Image(uiImage: image).resizable().scaledToFill()
                } else {
                    Color(.secondarySystemBackground)
                }
            }
            .frame(width: 96, height: 96)
            .clipped()
            .cornerRadius(8)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(ProfileEditor.photoDescription(place: place, count: count)))
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

/// "Add a photo": the system photo picker. It needs no photo-library
/// permission; the person hands over one picture, which the shell re-draws,
/// sanitises and encrypts before anything is shown.
@available(iOS 16.0, *)
private struct PhotoAddButton: View {
    let add: (Data) -> Void
    @State private var item: PhotosPickerItem?

    var body: some View {
        PhotosPicker(selection: $item, matching: .images) {
            Text("Add a photo").frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
        }
        .onChange(of: item) { next in
            guard let next else { return }
            item = nil
            Task { @MainActor in
                if let data = try? await next.loadTransferable(type: Data.self) {
                    add(data)
                }
            }
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
