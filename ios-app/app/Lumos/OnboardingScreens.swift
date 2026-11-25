/// Lumos - ios-app/app / The onboarding screens: age, documents, identity.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-ONB-001, SRS-ONB-002, SRS-ONB-006, SRS-ONB-008, TRS-IOS-010
/// ADRs: ADR-008
/// Threading: main-actor
/// Notes: The words are the Android strings, so both apps say the same thing.
/// Text uses the system text styles so it follows Dynamic Type, and each
/// screen scrolls so the largest sizes still reach the button (TRS-IOS-010).
///
/// The age gate starts on today's date, which does not pass, and Continue
/// stays off until a date has been chosen: the default is never an answer
/// somebody can tap through without reading the question.

import LumosAppShell
import SwiftUI

/// The step the onboarding machine is on.
struct OnboardingFlow: View {

    @ObservedObject var model: AppModel
    let state: OnboardingState

    var body: some View {
        NavigationView {
            switch state {
            case .ageGate:
                AgeGateScreen(model: model)
            case .blockedUnderAge:
                BlockedScreen()
            case .legalConsent:
                ConsentScreen(model: model)
            case .identityCreation, .complete:
                IdentityScreen(model: model)
            }
        }
        .navigationViewStyle(.stack)
    }
}

/// A screen's title, marked as a heading for VoiceOver.
private struct Heading: View {
    let text: LocalizedStringKey
    var body: some View {
        Text(text)
            .font(.largeTitle.bold())
            .accessibilityAddTraits(.isHeader)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// A scrolling column with the action pinned under it.
private struct StepLayout<Content: View, Action: View>: View {
    @ViewBuilder let content: Content
    @ViewBuilder let action: Action
    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) { content }
                    .padding()
            }
            action
                .padding()
        }
    }
}

/// SRS-ONB-001: adults only.
struct AgeGateScreen: View {

    @ObservedObject var model: AppModel
    @State private var dateOfBirth = Date()
    @State private var chosen = false

    var body: some View {
        StepLayout {
            Heading(text: "How old are you?")
            Text("""
                Lumos is for adults only. Enter your date of birth so we can check. We do not keep it — we \
                check it and throw it away, and all that stays on your phone is that you passed.
                """)
                .font(.body)
            DatePicker("Date of birth", selection: $dateOfBirth, in: ...Date(), displayedComponents: .date)
                .datePickerStyle(.wheel)
                .onChange(of: dateOfBirth) { _ in chosen = true }
        } action: {
            Button {
                model.declareAge(dateOfBirth: dateOfBirth)
            } label: {
                Text("Continue").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(!chosen)
        }
        .navigationBarHidden(true)
    }
}

/// Where someone under 18 stops. There is no way forward from here.
struct BlockedScreen: View {
    var body: some View {
        StepLayout {
            Heading(text: "You need to be 18 to use Lumos")
            Text("""
                That is the rule everywhere Lumos works, and there is no way around it. Come back on your \
                eighteenth birthday.
                """)
                .font(.body)
        } action: {
            EmptyView()
        }
        .navigationBarHidden(true)
    }
}

/// SRS-ONB-002: the four documents, each readable in full, then acceptance.
struct ConsentScreen: View {

    @ObservedObject var model: AppModel

    var body: some View {
        StepLayout {
            Heading(text: "Before you start")
            Text("Please read these. They are short, and they are written to be read rather than skipped.")
                .font(.body)
            VStack(spacing: 0) {
                ForEach(LegalDocument.allCases, id: \.self) { document in
                    NavigationLink(destination: DocumentScreen(title: Self.title(of: document),
                                                               text: model.text(of: document))) {
                        HStack {
                            Text(Self.title(of: document)).font(.body)
                            Spacer()
                            Image(systemName: "chevron.right")
                                .foregroundColor(.secondary)
                                .accessibilityHidden(true)
                        }
                        .padding(.vertical, 12)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    Divider()
                }
            }
        } action: {
            Button {
                model.acceptDocuments()
            } label: {
                Text("I have read these and I accept them").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
        }
        .navigationBarHidden(true)
    }

    /// The name each document goes by on screen.
    static func title(of document: LegalDocument) -> LocalizedStringKey {
        switch document {
        case .termsOfUse: return "Terms of Use"
        case .privacyNotice: return "Privacy Notice"
        case .communityRules: return "Community Rules"
        case .localRadioDisclosure: return "How Lumos uses your phone's radio"
        }
    }
}

/// One document, in full.
struct DocumentScreen: View {
    let title: LocalizedStringKey
    let text: String

    var body: some View {
        ScrollView {
            Text(verbatim: text)
                .font(.body)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// SRS-ONB-006: a local identity, with no sign-up of any kind.
struct IdentityScreen: View {

    @ObservedObject var model: AppModel

    var body: some View {
        StepLayout {
            Heading(text: "Setting up your phone")
            Text("Lumos is making your account. It happens on this phone and takes a moment.")
                .font(.body)
            Text("""
                There is no sign-up. Lumos never asks for your phone number, your email address or a login \
                from anywhere else, and nothing about you is sent to us.
                """)
                .font(.body)
            Text("This works with no internet connection.")
                .font(.callout)
                .foregroundColor(.secondary)
        } action: {
            ProgressView()
                .accessibilityLabel(Text("Setting up"))
        }
        .navigationBarHidden(true)
        .onAppear { model.createIdentity() }
    }
}

/// After onboarding. Visible Nearby is off and stays off until the person
/// turns it on (SRS-ONB-008); the home screens replace this in CS3b part 4.
struct ReadyScreen: View {
    var body: some View {
        StepLayout {
            Heading(text: "You're set up")
            Text("""
                Your account lives on this phone. Nobody can see you nearby until you turn on Visible \
                Nearby yourself.
                """)
                .font(.body)
        } action: {
            EmptyView()
        }
    }
}

/// A failure the person cannot fix from inside the app.
struct FailureScreen: View {
    var body: some View {
        StepLayout {
            Heading(text: "Something went wrong")
            Text("Lumos could not finish setting up on this phone. Close the app and open it again.")
                .font(.body)
        } action: {
            EmptyView()
        }
    }
}
