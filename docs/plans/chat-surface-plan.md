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
- **CS2b done (2026-11-24): the safety sheet, unmatch and block, end to end.**
  Safety came before the list, so CS2b was re-scoped to this alone.
  - **Sheet:** a shield in the chat header (spoken as "Safety options for"
    the person) opens "Safety with" the person, saying first that they are
    not told. It offers Unmatch and Block (danger colour plus its own icon),
    each explained in one sentence, and acts only after a confirmation that
    says the chat is deleted from this phone and it cannot be undone.
  - **One local transaction, no network needed:** the conversation, its
    messages, its ratchet session and its unsent queue are deleted; the
    relay route is marked ending; a block also writes the identity to the
    block list the Nearby feed already reads (SRS-NBY-013).
  - **The relay half is durable:** routes now carry an ending mark (route
    format 2; format 1 rows still read, as open). An ending route accepts
    nothing, cannot be re-allowed, and every relay pass revokes and then
    forgets it, retrying while offline and across a process kill. A message
    already waiting in the mailbox is dropped unread and deleted from the
    relay. `RelaySync.forget` now marks first too.
  - **Not offered yet, by design:** mute (needs notifications), report
    (CR-007; evidence retention per SRS-MAT-010 comes with it), and the
    other contexts SRS-SAF-001 lists (profile, request, venue room).
  - **Still to land elsewhere:** the match ledger is not composed in
    production, so ending the match record joins CS4; the nearby Hello
    responder must refuse a blocked identity when the GATT exchange is wired
    (SRS-SAF-002), and only then may the block copy say "and you won't
    appear in theirs".
- **CS2c done (2026-11-24): the Android home shell.**
  - **`HomeActivity`:** Nearby, Matches and Chats under one Material tab bar.
    It is gated exactly like Nearby (no onboarding, nothing opens). The
    selected tab survives rotation, and the Chats tab carries the unread
    total as a badge (capped at 99+).
  - **Three tabs, not five:** Discover (venues) and Profile join when they
    exist. A tab that opens onto nothing would be a placeholder.
  - **Matches:** conversations with nothing said yet. They are created at
    match time, so this is real data.
  - **Chats:** newest first. Each row previews the last message ("You: "
    for your own, "Message deleted", never the deleted words), with the
    time as time of day, Yesterday, weekday or date in the person's zone,
    and the unread count. Each row is one spoken sentence, and a tap opens
    the conversation.
  - **Nearby** is now `NearbyPanel`, shared by the tab and `NearbyActivity`.
  - **Found and fixed:** the CS2a conversation screen never applied
    ADR-011's recents redaction. `RecentsRedaction` now applies it on
    Nearby, home and conversation, with device tests that the thumbnail is
    blanked on the way out.
  - **Known limit until CS4:** names are not stored, so every row says
    "Your match", and new matches are in a stable but arbitrary order (the
    match time lives in the ledger, composed in CS4).
- **CS3a done (2026-11-24): iOS logic parity for CS2a and CS2b,** in the
  SwiftPM shell (no screens).
  - **Chat store:** `peerIdentity`, delete-for-me (refused while the message
    still waits to go), and the dedup cache held to its 10,000-id cap after
    every write (SRS-NFR-SEC-006).
  - **Safety:** `ConversationSafety` (unmatch, block) and
    `ConversationRemoval`, one transaction each, exposed as
    `ChatRuntime.safety`; `BlockedPeers.block`.
  - **Routes:** the same format-2 ending mark; every relay pass revokes and
    forgets ending routes; `forget` marks first.
  - **Tests:** the Android scenarios ported one for one (11 new Swift tests),
    with the same mutation check.
- **CS3b (iOS):** the conversation screen and safety sheet in SwiftUI. These
  wait on the owner's app-target decision: the shell is a library and never
  imports SwiftUI.

### CS3c (iOS): Visible Nearby on iPhone
- **Done (2026-11-26):** `DiscoveryVisibility` ported to the Android format
  (lumos-visibility/2: an hour or a custom 15 minutes to 8 hours, ending by
  evaluation; a version-1 record reads as off). `VisibleNearbyController`
  drives the CoreBluetooth advertiser from the same evaluation: on only by
  the person's tap (which is when Bluetooth permission is asked), off the
  moment the window ends, they turn it off or the app leaves the screen;
  Bluetooth not allowed or unsupported turns visibility off and says why.
  The Nearby tab draws it. "Until you leave the venue" is not offered on
  iPhone until there is venue-exit detection, so no window can be endless.
- **Honest limit, both apps:** the HELLO exchange over a live GATT link is
  not wired on either platform yet (the advertisers, scanners, presence
  table, feed and interest machines exist and are tested, but nothing
  connects them on real radios). Until that slice (**NB-1**, Android then
  iOS), two phones cannot find each other; the Nearby tab says so. Real-radio
  behaviour is the owner's G-01 field campaign.

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

## Owner rulings, 2026-11-24 ("yes to all")
Put to the owner as one plain table; all accepted.
- **iOS app target:** an Xcode project generated by XcodeGen (2.46.0
  exact, MIT, build-time only, never shipped) from a text spec in the
  repository. CI builds it unsigned on GitHub's macOS runners; no Mac is
  needed. Staging bundle `in.dharabhishek.lumos.staging`, production
  `in.dharabhishek.lumos` (`com.lumos.app` was taken on Apple; see
  `docs/ops/environments.md`), iOS 15 floor (the frozen baseline).
- **On-device iOS testing:** through TestFlight from CI, once Apple approves
  the developer enrolment (still pending). The App Store Connect API key is
  created by the owner and held only in GitHub's encrypted secrets; that
  pipeline is its own later change.
- **Staging deploy: deferred** until the app can be tested end to end on
  phones, to avoid a monthly bill for an idle server. Then the runbook is
  rewritten for the low-cost layout (one small VM running the CI-tested
  compose file for Postgres and Redis; Cloud Run with zero minimum
  instances) and handed over as copy-paste steps.
- **Push:** Firebase Cloud Messaging on Android (`firebase-messaging` via the
  Firebase BoM, Apache-2.0, with Google Play services under the Android SDK
  terms) and APNs on iOS (no SDK) are approved. Analytics off; wake-only
  payloads with no content. The FCM API and role are enabled with the
  deploy. The APNs `.p8` key is created by the owner after Apple approves,
  stored straight into Secret Manager, and never passes through the agent.
- **Store identifiers:** check that `com.lumos.app` is free with Apple and
  Google once the Apple enrolment is approved; rename before launch if not.
- **CR-009 accepted;** it runs straight after CS2c.
- **Order from here:** CS2c (Android chats list and tab bar), CR-009 (reply,
  both apps), CS3b (the iOS app target and SwiftUI screens).

## Every slice
- **Screen tests:** golden screenshots in both themes and at the largest
  text size, and screen-reader labels asserted (SRS-NFR-A11Y-001/004/007).
- **A "Safety, privacy and legal impact" section** in the PR.
- **Usability:** a test with real users and screen-reader passes on devices
  are the owner's (WP-10's audit and G-01). They are listed in each PR,
  never claimed.
