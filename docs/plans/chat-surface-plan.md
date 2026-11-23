<!--
  Lumos - docs/plans / The chat surface: execution plan.
  Author: dhar.abhishek.email@gmail.com
  Implements: TRS-AI-001, TRS-AI-013 (planning discipline)
  ADRs: ADR-012
  Threading: n/a
  Notes: Planned on 2026-11-23 under the owner's standing delegation, after the
  WP-05 closeout named "no chat screen on either app" as its largest gap and
  the owner accepted CR-008 (the design language). It runs before CR-006 and
  CR-007, whose reports are filed from these screens. Android and iOS
  alternate slice by slice (owner ruling, 2026-11-23).
-->

# The chat surface

**Baseline:** SRS v1.1 / TRS v1.1 / EDP v1.0, plus CR-008 (accepted 2026-11-23).
**Toolkits, fixed by the TRS:**
- **iOS:** SwiftUI, with UIKit bridges only where needed (TRS-IOS-001).
- **Android:** native Views/XML with Material 3 (TRS-AND-003). Material 3
  Expressive therefore arrives through Material Components themes, shapes and
  motion, not Compose.

**Design source:** the CR-008 design system, whose tokens are committed at
`docs/design/midnight-aurora.tokens.json`.

## Slices

### CS1: the design tokens on both platforms
- **`tools/tokensgen`** generates, from the one token file:
  - Android `values/` and `values-night/` colours, dimensions and the
    Material 3 theme;
  - a Swift `LumosTheme`.

  It has a `-check` mode, so CI fails if a platform drifts from the source
  (TRS-UX-001).
- **Sora,** shipped unmodified under SIL OFL 1.1, with its licence verbatim
  in `docs/legal/third-party-notices.md` (CR-008 ruling 3).
- **Contrast tests in code:** every text and icon pair the token file's
  `usage` notes promise is recomputed against WCAG 2.2 AA in both themes, so
  a later token edit cannot quietly break accessibility.
- **Done (2026-11-23):**
  - `tools/tokensgen` writes the Android colours (Daylight in `values/`,
    Midnight in `values-night/`) and dimensions, and Swift `LumosTokens`. Its
    `-check` runs as the token gate in CI, with 41 promised pairs held to
    WCAG AA in both themes.
  - The Material 3 theme maps every colour role and corner to the tokens.
  - Midnight is the default appearance.
  - The ticks take their token colours on both apps.
  - Sora 2.000 ships unmodified in `res/font/sora.ttf`, with its licence
    verbatim in the notices. Android 9+ draws display text in Sora at the
    token weights. Android 8.x uses the platform face in bold, because
    choosing a variable-font weight from XML needs API 28.
  - **iOS** has the tokens as plain values (the shell never imports SwiftUI).
    The font joins the iOS app target when that exists, from the same file.
  - **Not yet in the token source:** motion, elevation and breakpoints
    (TRS-UX-001 stays `implemented` until they are).

### CS2 (Android) and CS3 (iOS): Matches and Chat
- **The Matches list and the conversation screen,** bound to ChatStore:
  - the bubble family (TRS-UX-004);
  - ticks with spoken labels (SRS-RMT-005's UI half);
  - nearby-only media marked (SRS-RMT-013);
  - the composer;
  - inline recovery instead of spinners (SRS-UX-012).
- **Reply, react, copy, local delete and timestamps** (SRS-RMT-010).
- **The safety sheet** from the chat header: mute, unmatch, block, report
  entry. The report flow itself is CR-007's.
- **The dedup cache's id cap,** enforced (SRS-NFR-SEC-006).

- **CS2a done (2026-11-24): the Android conversation screen.**
  - **Logic:** a plain-Java presenter and row builder (JVM-tested) decide
    every layout and action.
  - **Bubbles:** they run in groups from one sender, with day dividers in
    the person's zone and a deleted message keeping its place.
  - **Ticks:** they draw from the shared style, and each bubble is one
    spoken sentence with its actions offered to accessibility services.
  - **Actions:** react (six quick reactions), copy, delete for me (refused
    while the message is still waiting to go, because its sealed envelope
    would still be sent), and delete for everyone (your own messages only).
  - **Composer:** it sends only real content that fits one envelope; a
    maximum-length first message is proven to fit. A failed send keeps the
    text.
  - **Offline:** an inline banner, never a spinner.
  - **The dedup cache's id cap** is enforced on Android.
  - **Found:** the wire has no reply event (AMD-005 defines text, edit,
    delete and reaction), so a quoted reply needs a change request before it
    can be built.
  - **Moved to CS2b:** the safety sheet, the Matches list and the tab shell.
    The peer's name stays "Your match" until previews are stored (CS4).
- **CS2b (Android):** the Matches list, the five-tab shell, and the safety
  sheet from the chat header.

### CS4 (Android) and CS5 (iOS): from Nearby to a match
- **Profile preview, Interested / Not now, and the match moment,** with the
  verification code (SRS-MAT-008) and the three-moment motion and haptics
  (SRS-UX-009).
- **The re-verification notice** when a match's identity key changes
  (SRS-IDN-006).

### CS6 (Android) and CS7 (iOS): settings and the last gaps
- **Read receipts and typing indicators,** opt-in and ephemeral
  (SRS-RMT-012, SRS-RMT-014's settings half).
- **An untrusted-certificate test** on both transports (SRS-NFR-SEC-009).
- **Onboarding's last step writes the ADR-012 key root,** so a real install
  turns the relay on.

## Every slice
- **Screen tests:** golden screenshots in both themes and at the largest
  text size, and screen-reader labels asserted (SRS-NFR-A11Y-001/004/007).
- **A "Safety, privacy and legal impact" section** in the PR.
- **Usability:** a test with real users and screen-reader passes on devices
  are the owner's (WP-10's audit and G-01). They are listed in each PR,
  never claimed.
