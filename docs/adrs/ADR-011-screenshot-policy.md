# ADR-011 — Screenshot and screen-capture policy: redacted recents always, detect-and-warn in chat, no global FLAG_SECURE

- **Status:** **ACCEPTED** — decided by the owner 2026-08-14, resolving the open
  decision recorded in `docs/security/threat-model.md` §7.5 (threat LT-22).
  Raised by the implementing agent per TRS-AI-006 during the WP-02 S0 preflight.
- **Raised:** 2026-08-13 (threat model §7.5) · **Decided:** 2026-08-14
- **Affected requirements:** SRS-NFR-SEC-013, SRS-SAF-004 (evidence capture), SRS-PRO-008
- **Affected artefacts:** presentation layer (WP-02 onboarding/profile slices onward); no code in slice S1

## Context

SRS-NFR-SEC-013 requires sensitive local data to be excluded from screenshots
"according to policy" without stating the policy. The blunt instrument — global
`FLAG_SECURE` on Android and full screen-capture suppression on iOS — defends
the shoulder-surfing and task-switcher threats (LT-22) but carries two real
costs the threat model recorded: it degrades accessibility (screen readers,
magnification, user-initiated screen sharing), and it is in direct tension with
**SRS-SAF-004's evidence capture**: a user reporting abuse must be able to
capture what they are being shown. A safety feature that blocks the collection
of evidence of harassment protects the harasser.

## Decision (owner ruling, 2026-08-14)

1. **Recents/app-switcher snapshots are ALWAYS redacted, on both platforms.**
   The OS-captured task-switcher thumbnail never shows profile or chat content:
   Android uses per-window snapshot redaction (or `FLAG_SECURE` scoped to the
   snapshot mechanism where the platform offers nothing finer), iOS replaces the
   snapshot via the standard resign-active cover. This is unconditional — the
   snapshot is written by the OS to disk and outlives the session, so it is
   at-rest data, not a display concern.
2. **Screenshot detect-and-warn in chat, where the platform permits.** When a
   screenshot of a conversation is detected (iOS
   `userDidTakeScreenshotNotification`; Android 14+ screenshot-detection API),
   the local user is informed and the design may inform the peer in a later
   package. Detection, not prevention.
3. **No global FLAG_SECURE.** Screens remain capturable by the user, because
   evidence capture per SRS-SAF-004 must remain possible. Prevention is applied
   to no user-facing surface by default.

## Consequences

- The user can always document what they see — SRS-SAF-004 evidence capture is
  never blocked by the privacy mechanism. The trade accepted in return: a
  malicious *viewer* can also capture what they are shown. The threat model
  already treats shown-content capture as unpreventable (RR-2/RR-3 territory —
  a camera pointed at the screen defeats any flag); this ADR stops pretending
  otherwise at the cost of honest UX.
- Detection is best-effort by platform: universal on iOS, API-gated on Android
  (14+), absent below. The chat warning copy must not claim more than the
  platform delivers (SRS-NFR-COM-008's no-overclaiming rule applies).
- The recents redaction is testable per platform surface and joins the WP-02
  presentation slices' test obligations; nothing in slice S1 renders content,
  so S1 carries no implementation of this ADR.
- LT-22's mitigation column resolves to this ADR; ADV-4's screenshot-reading
  path is closed at the snapshot (the one OS-written artefact), not at the
  display.

## Alternatives considered

- **Global FLAG_SECURE / capture suppression:** rejected — blocks SRS-SAF-004
  evidence capture, breaks accessibility flows, and creates a false sense of
  control (photographing the screen remains trivial).
- **Per-screen FLAG_SECURE on "sensitive" screens only:** rejected — in a dating
  app every content screen is sensitive, so the set degenerates to "all", which
  is the global flag with extra classification burden; and it still blocks
  evidence capture exactly where evidence lives (chat).
- **Prevention with an "allow capture for reporting" toggle:** rejected — an
  discoverable toggle that disables protection is the worst of both: coercible,
  confusing, and its state is invisible at capture time.

## Verification

- WP-02 presentation slices: per-platform test that the recents snapshot is
  redacted (Android instrumented; iOS UI test with resign-active); chat-slice
  test that detection fires the warning where the API exists and that the copy
  degrades honestly where it does not.
- This slice (S1): none — no rendering surface exists yet. The ADR is recorded
  now because the threat model's §7.5 default ruled stricter-until-decided, and
  slices between S1 and the presentation work must build against the decided
  policy, not the interim default.
