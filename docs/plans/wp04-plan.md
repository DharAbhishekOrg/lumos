<!--
  Lumos - docs/plans / WP-04 execution plan.
  Author: dhar.abhishek.email@gmail.com
  Implements: TRS-AI-001, TRS-AI-013 (planning discipline); scope per EDP Sec. 7 WP-04
  ADRs: ADR-002, ADR-005, ADR-008, ADR-009
  Threading: n/a
  Notes: Ordered slice checklist for WP-04 (interest, match, consent state
  machine and verification), produced at the WP-04 kickoff under the standing
  full-delegation mandate (2026-11-19) and carrying the WP-03 closeout's
  Sec. 6 dispositions. The first slice is the WP-03 closeout's own named
  destination: mounting the Nearby feed surface the thirteen deferred
  SRS-NBY rows share, because requests ride presence and presence needs a
  screen before anything can be requested from it. Scope boundaries: no
  remote relay transport (WP-05), no venue perks (WP-08); unmatch/block
  propagation lands its hooks here and completes cross-path in WP-05/06.
-->

# WP-04 Execution Plan — Interest, Match, Consent State Machine and Verification

**Baseline:** SRS v1.1 / TRS v1.1 / EDP v1.0 · **Kickoff:** under the owner's
standing delegation, 2026-11-19, at the WP-03 boundary. **Entry criteria:**
WP-03 green — met (closeout `evidence/wp03/`, 1,631-test regression, run
44607391105/44607391166). **Authoring host:** Claude cloud container. CI
remains the sole prover.

## Scope, from the EDP §7 brief's Key requirements row

SRS-MAT-001..014, SRS-IDN-005/006, SRS-MED-003 (gate), SRS-SAF-001/002
(local scope), TRS §4.2 match-domain, TRS-ARCH-009, SRS-NFR-REL-004, AT-005 —
plus, by the WP-03 closeout §6.2 ruling, the thirteen deferred feed-surface
rows (SRS-NBY-008..015/017, SRS-NFR-BAT-002, TRS-IOS-009,
SRS-NFR-PERF-005/009) whose destination is this work package's first slice.

## Slice order, and why this order

Requests ride the authenticated local session on a card the user can see, so
the feed surface comes first; the match domain is pure state and vectors, so
its reference lands before either platform consumes it (the WP-01 constants
discipline); verification is a derivation over material frozen since ADR-009;
AT-005 closes the package end to end.

### S1 — The Nearby feed surface (the WP-03 §6.2 destination)
Material 3 native Views/XML on Android (TRS-AND-003, Java 17), SwiftUI on
iOS (TRS-IOS-001), mounted on `PresenceTable` and nothing else. Shipped as
three PRs (the S2/S5 precedent, recorded as the split happened): S1a the
Android machines over schema v4, S1b the iOS machine siblings, S1c the
rendering layers with the instrumented state demo that closes the WP-03
§6.3 exit gap.
- Feed list over presence cards with skeleton and empty states, no
  indefinite spinners (SRS-NBY-014); proximity words from NearbyLabels, no
  plumbing vocabulary anywhere a user reads (SRS-NBY-008 as an executable
  UI-copy test, extending the clean scan the closeout recorded).
- Adapter/permission/local-state messages for off, unavailable, restricted
  and busy (SRS-NBY-010) from the typed states TRS-AND-005 already maps.
- The persistent advertising indicator in every main surface (SRS-NBY-003
  graduates), the one-action stop (SRS-NBY-017) wired to advertise+scan
  teardown, and stop-on-leaving-the-flow (SRS-NFR-BAT-002).
- Hide without notifying (SRS-NBY-012) and blocked-identities-never-reappear
  (SRS-NBY-013) as presence-side filters over WP-02's blind-indexed block
  store; local preference filtering (SRS-NBY-011).
- Preview-only retrieval before open/request (SRS-NBY-009) over the
  PROFILE_PREVIEW class; the full profile stays unrequested at this stage.
- RescanLadder + DiscoverySuccessMetric wired to the view lifecycle their
  S6 contracts await; responsiveness harness at 100+ synthetic peers with
  the 100 ms stall bound (SRS-NBY-015, SRS-NFR-PERF-009 CI half).
- iOS background-restoration exercise to the extent a host can
  (TRS-IOS-009 stays honest about what only a device relaunch proves).
- **Graduates: SRS-NBY-003/008/009/010/011/012/013/014/015/017,
  SRS-NFR-BAT-002 (CI halves; PERF rows keep their G-01 measurement half);
  closes the WP-03 §6.3 exit-evidence gap.**

### S2 — Match-domain reference and vectors (Go first, as always)
- Register constants and error codes for the interest/match domain per
  TRS §19: state names, transition table, cooldown shape beside the
  existing INTEREST_RATE_LIMIT_PER_HOUR, match-ID derivation inputs.
- The TRS §4.2 transactional state machine in Go: request / accept /
  decline / ignore / block with idempotent transitions, duplicate and
  reordered event absorption, stable match IDs.
- **The accept-accept race resolves to ONE match ID by the lowest-identity
  tiebreak, encoded in a vector** (the EDP guardrail, verbatim).
- `matches/` vector family: scripted timelines (the fragmentation-vector
  pattern) covering races, replays, reorders, and the silent-decline rule.
- **Graduates: TRS §4.2 match-domain reference, SRS-NFR-REL-004 groundwork.**

### S3 — Platform match domain (schema v4)
- The match store on both platforms: interest and match records join the
  ladder as version 4, one-new-table-per-kind, full Room-parity discipline
  (the v3 precedent).
- Vector-conformant state machine on Java and Swift over the local session
  (SRS-MAT-001..); request-stage field policy (what a requester may see,
  SRS-MAT + SRS-PRO request-stage rows); **no-rejection-signal proven by
  absence tests — no observable signal of any kind, not merely no
  notification** (the EDP guardrail).
- Rate limits and cooldown from the register, with the cooldown UX copy
  under the OnboardingCopyTest disciplines.
- Unmatch/block propagation hooks (cross-path enforcement completes
  WP-05/06 and is flagged, not faked).
- **Graduates: SRS-MAT core rows, SRS-IDN-005/006, SRS-SAF-001/002 local
  scope, SRS-MED-003 gate.**

### S4 — Verification token
- Shared emoji/color verification token derived from the safety-number
  vectors (ADR-005 format, ADR-009 alphabet, frozen since WP-01), parity
  tests on all three languages, UI rendering on both platforms.
- **Graduates: the verification rows of SRS-MAT; AT-005's token leg.**

### S5 — AT-005 and closeout
- The mutual-consent flow end to end over the local transport
  (emulator-pair or loopback FrameLink/FrameChannel), executed as AT-005's
  logic half and flagged for the owner's physical Android↔iOS re-run.
- Negative proof: **no chat or media path opens pre-match** (exit-evidence
  clause), including under replayed and reordered events.
- Full WP-01..03 regression; `evidence/wp04/closeout.md` + machine twin by
  `tools/closeout` (scope row transcribed there with its own test);
  boundary audit.

## Owner-executed, never simulated (unchanged, EDP §8)

AT-005 re-run on a physical Android↔iOS pair; everything G-01 the WP-03
closeout lists (now runnable); G-05/G-06/G-07/G-09 exactly where EDP §8
puts them. No gate is advanced from here.

## Known traps carried from the brief, verbatim where it matters

- Accept-accept race: one match ID, lowest-identity tiebreak, in a vector.
- Declines are silent: test for the absence of any observable signal.
- iOS cannot broadcast service data — never "fix" this with a private API.
- Presence dedup keys on authenticated identity, not advertisement bytes.
- Scan loops stay bounded; the feed surface must stop, not just pause,
  discovery when the user leaves the flow.
