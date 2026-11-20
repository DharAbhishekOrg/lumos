<!--
  Lumos - evidence/wp04 / WP-04 closeout report.
  Author: dhar.abhishek.email@gmail.com
  Implements: TRS-AI-003, TRS-AI-005, TRS-CICD-007
  ADRs: ADR-002, ADR-005, ADR-008, ADR-009, ADR-010, ADR-011
  Threading: n/a
  Notes: Every number here is derived from evidence/trace/trace-matrix.json
  and the executed-test set of conformance run 44754667606 (merge 1fbd64d on
  main) by tools/closeout. The machine twin is closeout-results.json beside
  this file; where the two disagree, the file wins.
-->

# WP-04 Closeout: Interest, Match, Consent State Machine and Verification

**Baseline:** SRS v1.1 / TRS v1.1 / EDP v1.0 · **Scope:** the EDP §7 WP-04
brief's "Key requirements" row, transcribed in `tools/closeout` (with its own
test) · **Pipeline:** conformance run `44754667606` on merge `1fbd64d`, 1,768
executed tests (Go 316, Java 1,269, Swift 179, TS 4) · **Slices:** S1a–S5b,
PRs #30–#39, plus this closeout.

---

## 1. Headline

**Of the 21 requirement IDs in WP-04's declared scope, 12 are `tested`, 5 are
`implemented`, and 4 are `not_started`.** Each of the 4 has a named
destination in §5. **Every test linked to an in-scope row ran in this
pipeline run.** The tracecheck honesty gate passed against that same
executed set, so no row is marked `tested` unless its tests ran. 124 further
matrix rows moved as spillover. They include the thirteen feed-surface rows
that the WP-03 closeout routed to this package (§4).

WP-04 delivered the following:

- **The Nearby screen.** Feed state machines run on both platforms. Schema v4
  adds hidden peers. The first Activity renders the feed, and an instrumented
  test demonstrates the full permission and radio-off state matrix, which
  closes the WP-03 exit gap.
- **The match domain on Go, Java and Swift.** All three follow one transition
  table and replay one set of committed vectors.
- **Persisted ledgers.** Schema v5 stores per-peer state, matches and a flood
  budget that survives a restart.
- **The in-venue verification token.**
- **Consent-stage wire envelopes.** A decline cannot be encoded at all.
- **The pre-match gate and the end-to-end consent flow.** AT-005's logic half
  runs over real canonical frames.

**Three guardrails from the EDP brief are now encoded, not just intended:**

1. **One match ID from an accept-accept race.** The identifier is symmetric:
   SHA-256 over the two peer IDs in lexicographic order. Both sides derive
   the same ID without exchanging a message, and the lower ID is the
   tiebreak. This is vector-pinned and proven over the wire by
   `theAcceptAcceptRaceOverTheWireYieldsOneMatch`.
2. **Declines are silent.** This is enforced structurally at three layers:
   - No transition table row gives a decline a transmitting effect.
   - The wire verdict alphabet is accept/unmatch only, so a decline is
     unencodable.
   - The session coordinator has no case to send one. The end-to-end test
     checks for the absence of any signal: the decliner's outbox stays empty,
     and the asker's state is byte-identical to "no answer yet".
3. **Nothing conversational crosses before a match.** ConsentGate covers
   every message class. Forged text, full-profile, object and voice frames
   are refused with `ERR_MATCH_NOT_MATCHED`.

**What WP-04 did not deliver:**

1. **No request, match or chat screens exist yet.** The domain is complete and
   tested, but no UI mounts it. Two consequences follow:
   - SRS-SAF-001 (safety actions from every context) is `not_started`.
   - SRS-MAT-001's "only to a discovered peer" eligibility check is not yet
     enforced at the point of request. The session accepts any peer key it is
     given.
2. **Blocking is only cross-path on the local side.** SRS-SAF-002 covers the
   feed veto and the machine's absorbing BLOCKED state. Relay, mesh and venue
   enforcement need those transports.
3. **AT-005 is still the owner's.** Two phones, one Android and one iPhone,
   have to run the flow physically. CI proved the logic half only.

## 2. Regression and vector suite in this run

| Language | Tests executed |
| --- | ---: |
| Go | 316 |
| Java | 1,269 (four unit flavours, the emulator job, and conformance) |
| Swift | 179 |
| TypeScript | 4 |

The WP-01 to WP-03 regression is inside these totals. The suite is
cumulative and nothing was skipped. The 18th vector family,
`matches/match_timelines.json`, is new in this package. It carries:

- 4 identifier derivations
- 4 tokens
- 4 wire cases
- 7 wire negatives
- 9 scripted timelines

Go, Java and Swift each replay it from the committed file.

## 3. Status of every WP-04 requirement ID

| Requirement | Status | Linked tests | Executed this run |
| --- | --- | ---: | ---: |
| SRS-IDN-005 | `tested` | 5 | 5 |
| SRS-IDN-006 | `not_started` | 0 | - |
| SRS-MAT-001 | `implemented` | 1 | 1 |
| SRS-MAT-002 | `tested` | 5 | 5 |
| SRS-MAT-003 | `implemented` | 5 | 5 |
| SRS-MAT-004 | `tested` | 6 | 6 |
| SRS-MAT-005 | `tested` | 6 | 6 |
| SRS-MAT-006 | `tested` | 4 | 4 |
| SRS-MAT-007 | `tested` | 6 | 6 |
| SRS-MAT-008 | `tested` | 4 | 4 |
| SRS-MAT-009 | `tested` | 4 | 4 |
| SRS-MAT-010 | `implemented` | 2 | 2 |
| SRS-MAT-011 | `tested` | 6 | 6 |
| SRS-MAT-012 | `not_started` | 0 | - |
| SRS-MAT-013 | `not_started` | 0 | - |
| SRS-MAT-014 | `tested` | 9 | 9 |
| SRS-MED-003 | `tested` | 3 | 3 |
| SRS-NFR-REL-004 | `tested` | 5 | 5 |
| SRS-SAF-001 | `not_started` | 0 | - |
| SRS-SAF-002 | `implemented` | 2 | 2 |
| TRS-ARCH-009 | `implemented` | 1 | 1 |

### 3.1 Why the five `implemented` rows are not `tested`

- **SRS-MAT-001.** Requests flow end to end, but the "currently or recently
  discovered" eligibility check is not enforced yet. It needs the request
  action in the UI, which reads presence. A test asserting it would assert a
  rule nobody applies.
- **SRS-MAT-003.** Its verification is a cross-device behaviour test, which
  is AT-005 on physical phones.
- **SRS-MAT-010.** The local half is built: unmatching flips the match record
  and never deletes it. The rest is not:
  - the report flow with its "retain evidence" choice, which comes with the
    safety UI
  - stopping remote delivery, which comes with the remote transport
- **SRS-SAF-002.** Only the local-discovery path is enforced (§1).
- **TRS-ARCH-009.** The interest path is serialized into a deterministic
  machine. The requirement covers all network and radio callbacks, and the
  remote and mesh paths do not exist yet.

### 3.2 Why rows moved to `tested` in the closeout

Each graduation rests on the requirement's stated verification method being
exactly what CI ran:

| Requirement | Stated verification | What CI ran |
| --- | --- | --- |
| SRS-MAT-002 | Payload inspection | Wire vectors in three languages |
| SRS-MAT-004 | Notification and peer-state test | Empty outbox and unchanged asker state |
| SRS-MAT-005 | State-machine test | The machine tests |
| SRS-MAT-007 | Protocol and persistence test | Identifier vectors and the two-store ledger test |
| SRS-MAT-009 | Lifecycle test | The unmatch lifecycle tests |
| SRS-MAT-011 | Flood and cooldown test | The flood and cooldown tests |
| SRS-MAT-014 | Duplicate-frame integration test | Replayed accept frames through the session |
| SRS-MED-003 | Negative consent test | Forged object and voice frames refused before a match |
| SRS-NFR-REL-004 | Model-based tests | Full state × event walk plus replayed timelines |

## 4. The thirteen rows routed here by the WP-03 closeout

| Status | Rows |
| --- | --- |
| `tested` | SRS-NBY-010, SRS-NBY-013 |
| `implemented` | SRS-NBY-003, -008, -011, -012, -014, -015, -017, SRS-NFR-PERF-009 |
| `not_started` | SRS-NBY-009, SRS-NFR-BAT-002, SRS-NFR-PERF-005, TRS-IOS-009 |

The WP-03 §6.3 exit-evidence gap is **closed**. `NearbyStatesInstrumentedTest`
drives the real screen on the emulator through every radio and permission
state.

The four rows still open, and where they go:

- **SRS-NBY-009** (only the preview is fetched before a request): the PROFILE_PREVIEW request path lands with the composition root, in the slice that wires the radio stack to the screen.
- **SRS-NFR-BAT-002** (stop discovery on leaving the flow): the same slice; the controller's `viewEnded` already disarms the ladder.
- **SRS-NFR-PERF-005** (preview within 3 seconds at P75): G-01, the physical measurement.
- **TRS-IOS-009** (iOS background restoration): still at scaffold level; only a device relaunch can prove it.

## 5. Not started or deferred, with destination

- **SRS-IDN-006** (non-alarming notice when a long-term identity key changes) → **WP-05**, where remote E2E sessions first meet a changed identity key.
- **SRS-MAT-012** (venue match perks without changing consent rules) → **WP-08**, which owns signed venue configuration.
- **SRS-MAT-013** (no "who viewed me" list) → **WP-10**. Nothing in the codebase records views, so the rule holds by absence today. It is verified by inspecting the UI, which belongs to WP-10.
- **SRS-SAF-001** (block, mute, hide, unmatch and report from every context) → **WP-10**, the safety UI. The block and hide storage, and the machine's block event, already exist for it to call.
- **The request, match and chat screens, and the composition root** that wires store, radio and sessions at process start → the first UI-bearing slice of WP-05, since remote chat needs a match screen to open from.
- **The iOS session coordinator** (Swift InterestSession): Swift has the machine, wire, token and gate, all conformance-pinned. The coordinator that couples them to a link lands with the iOS composition root in the same slice.

## 6. Findings for the G-07 cryptographic-review dossier

- **The match identifier is symmetric and unkeyed by design.** Anyone holding
  both public identities can compute it, and it is not a secret.
  What it guarantees is agreement without messages and EID-independence.
  Reviewers should confirm that nothing treats it as a bearer credential.
- **The verification token is a co-presence check, not authentication.** It
  is 15 bits derived from the match ID. Its job is to defeat "I'm your match"
  across a crowded room. The cryptographic verification is the safety number
  underneath (SRS-IDN-005). The register note says this explicitly.
- **Peer-keyed rows have random row IDs, not IDs derived from the peer key.**
  `putByNaturalKey` deliberately keeps row IDs as random UUIDs. Deriving them
  from the peer key would give an unkeyed way to confirm a relationship,
  which is exactly what the blind index exists to prevent.

## 7. Owner gates

All nine EDP §8 gates remain `unproven_owner`; none was advanced. AT-005 joins
the G-01 queue: run the consent flow on the Android phone and the iPhone, and
confirm that both screens show the same emoji pair and the same named colour.

## 8. What WP-05 inherits

- Persisted per-peer interest state and match records, keyed by blind index.
  `INTEREST_RECORD` and `MATCH_RECORD` hold the stable match ID that remote
  sessions will bind to (TRS-CRY-014's match-context binding).
- `ConsentGate`, which remote sends and receives must consult exactly as the
  local session does. Remote text is a conversation class, and the gate
  already says MATCHED-only.
- The unmatch record that remote delivery must honour (SRS-MAT-010's remote
  half).
- The match token and safety-number material for the re-verification notice
  (SRS-IDN-006).
- A CI pipeline that ran 1,768 tests, with the honesty gate requiring every
  row marked `tested` to have its linked tests in the executed set.
