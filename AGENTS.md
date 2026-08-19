# AGENTS.md — Lumos Implementation Contract (read every session)

You are implementing **Lumos**, a privacy-first proximity dating and venue-social
platform: native Android (Java 17), native iOS (Swift 6/SwiftUI), Go backend,
TypeScript admin console. This file is binding. Where it conflicts with anything
else except the baselined documents, this file and the documents win.

## 1. Source of truth (reading order)

1. `docs/Lumos_SRS_v1_1.pdf` — behavior (396 requirements, 33 acceptance tests)
2. `docs/Lumos_TRS_v1_1.pdf` — architecture, protocol, constants (§23), DFMEA (§22),
   code standard (§24), extensibility (§25) (252 requirements)
3. `docs/Lumos_EDP_v1_0.pdf` — ADR-001..004 full records, WP-01..WP-11 kickoff
   briefs (§7), owner-executed gates G-01..G-09 (§8)
4. Machine-readable equivalents (same content, easier to query):
   - `docs/requirements/srs_requirements_v1_1.json`
   - `docs/requirements/trs_requirements_v1_1.json`
   - `docs/acceptance/acceptance_tests_v1_1.csv`
   - `protocol/constants/lumos_constants.seed.json`

If prose and a numbered requirement conflict, the stricter privacy/safety/security
interpretation applies (SRS §2.4).

## 2. Binding decisions — do not re-open

- **ADR-001** Crypto: libsodium X25519 / Ed25519 / ChaCha20-Poly1305 / HKDF-SHA-256
  on all platforms; in-house X3DH-compatible + Double Ratchet state machines per the
  Signal specs, vector-proven. **libsignal is prohibited** (AGPL-3.0).
- **ADR-002** BLE: asymmetric advertising; canonical trust ONLY at authenticated
  GATT HELLO. Android 18-byte Service Data hint + scan response; iOS service-UUID
  only. Never attempt service-data advertising on iOS.
- **ADR-003** Recovery: content-only RecoveryRecord; identity keys hardware-backed,
  non-exportable, never escrowed; backups exclude all cryptographic state.
- **ADR-004** Every tunable value comes from generated constants
  (`/protocol/constants/lumos_constants.json`). No magic numbers anywhere.

## 3. Absolute prohibitions (release-blocking if violated)

- No placeholders, stubs presented as done, demo keys, fake billing, hard-coded
  success, fake telemetry, or unexecuted tests reported as passed (TRS-AI-002/008).
- Never claim BLE/radio interoperability, store approval, load results, legal or
  security sign-off. Physical-radio and third-party gates are **owner-executed**
  (EDP §8, G-01..G-09): deliver them "ready for execution" and mark the trace
  matrix `unproven`. The correct final state of such work is *ready, awaiting
  owner evidence* — say so plainly (TRS-AI-009/010/014).
- No plaintext profile/chat/media/keys in the backend, logs, analytics, push
  payloads or crash reports — ever (TRS-BCK-002, TRS-OBS-002).
- Local-only media/voice notes never touch the internet relay (SRS-TRN-009).
- No new dependency without stating name, version, license and why; ISC/MIT/Apache-2
  preferred; any copyleft requires explicit owner approval first.
- Never touch signing material, production credentials or `.env` secrets; never
  commit them (TRS-REP-005).

## 4. Per-session workflow

1. State which **work package** you are in. Work strictly by the WP kickoff brief
   (EDP §7). Do not start a WP whose entry criteria are unmet; do not drift scope.
2. Before coding, list the requirement IDs the change implements.
3. Small vertical slices ending in working, tested behavior (TRS-AI-013).
4. **Same-commit rule:** every code/test change updates
   `evidence/trace/trace-matrix.json` (status, code paths, tests, last_commit).
   A stale matrix is a CI failure by design.
5. Run the relevant tests and the protocol-conformance vectors before claiming
   anything. `tested` status requires an execution in this session's run.
6. Full regression of prior WPs at each WP close (TRS-AI-004/005).

## 5. Code standard (TRS §24 — lint-enforced)

Every source file starts with:

```
/*
 * Lumos - <module> / <one-line purpose>
 * Author: dhar.abhishek.email@gmail.com
 * Implements: <requirement IDs>
 * ADRs: <IDs or '-'>
 * Threading: <main-safe | serial-executor | actor | any>
 * Notes: <non-obvious intent, or '-'>
 */
```

Public APIs documented (Javadoc/DocC/godoc). Complexity ≤15/function, ≤800
lines/file, ≤500/class — all four enforced since WP-02 S5: complexity by each
language's own analyser, the file and class budgets by `lumoslint -budgets`.
TRS-CODE-004's exception clause is one optional extra header line, and the ADR
it cites must exist:

```
 * Budget-exception: class ADR-012 - <why THIS file needs the room>
```

There are none in the tree. Splitting the type is the first answer; an
exception is what you write when splitting it would be worse, and it is
reviewed as a decision rather than granted by writing a number. Zero analyzer warnings (Checkstyle+SpotBugs+Android Lint /
SwiftLint+Swift 6 strict / golangci-lint / ESLint+tsc strict). Patterns per TRS
§24.2: transports = Adapter+Strategy; routing/match/transfer/config = explicit
State machines with typed events (no boolean-flag state); Repository for data;
unidirectional immutable view state for UI; Command outbox; Circuit
Breaker/Backoff as policy objects; constructor injection, no mutable singletons.

## 6. When to stop and ask the owner

Stop — do not improvise — when you hit: a genuine ambiguity not resolved by the
documents (write an ADR draft first, TRS-AI-006); any need for credentials,
consoles, cloud resources, physical devices or spending; any temptation to relax
a P0; a failing gate you cannot fix within the WP's scope. Honesty about a gap
is success; papering over it is the only real failure mode.

## 7. Current state

- **WP-01 and WP-02 are both closed.** Records: `evidence/wp01/closeout.md` and
  `evidence/wp02/closeout.md`, each with a machine-readable twin. **Read the
  WP-02 closeout before starting WP-03** — §6 lists every deferral with a named
  destination, and eight of them land in WP-03.
- Active WP: **WP-03** (EDP §7). Do not start it without its kickoff brief, and
  note its entry criteria include physical devices the owner supplies.
- **The first thing WP-03 must fix:** `IdentityKeyGenerator.generate()` runs on
  the JDK and on **no Android runtime at any API level** — the platform JCA has
  no Ed25519 `KeyPairGenerator`. Onboarding, identity reset and the restore half
  of recovery are therefore JVM-proven and device-unproven. ADR-001's answer is
  libsodium, already in the tree; the owner ruling of 2026-08-14 parked the
  wiring for WP-03. `PlatformKeyAlgorithmInstrumentedTest` is the tripwire.
  Closeout §5.1 and threat model §7.6 carry the measurements.
- Trace matrix: 648 entries — 60 `tested`, 24 `implemented`, 564 `not_started`.
  Of the 45 IDs in WP-02's declared scope (EDP §7), 22 are `tested`, 6
  `implemented` and 17 `not_started`. All nine EDP §8 owner gates remain
  `unproven_owner`; no agent may advance one.
- **The repository ruleset is owner-held (ruling 2026-08-19).** Never modify
  branch protection and do not offer to. Required check names live in
  `docs/ci/required-checks.md`; `tools/cicheck` fails the build if a workflow
  job name drifts from that register, and a rename means the owner must edit
  the ruleset before the pull request can merge.
- Repo layout is the TRS §4.1 ten-root form. The handover pack's
  `requirements/`, `acceptance/` and `prompts/` directories were moved under
  `docs/` in slice S1; the original archive is retained at
  `docs/handover/Lumos_Codex_Handover_Pack.zip`.
- Toolchains are pinned in `tools/toolchain/toolchain-manifest.json`. **Java 21
  LTS since CR-002**; the Android module still compiles to Java 17 language
  level, deliberately. Swift is unavailable on the Windows authoring host by
  design; the iOS conformance runner and the iOS shell build on a GitHub Actions
  `macos-latest` runner.
- What exists after WP-02: 318 frozen constants with tri-language codegen (no
  hand-written constant anywhere); the complete TRS §18.4 golden-vector suite
  plus the recovery-record extension; a device-bound two-key identity per
  ADR-008; encrypted persistence at schema version 2 with a forward-only ladder
  and Room behind the cross-platform seam; the inclusive profile, its field
  policy and derived preview; the EXIF-stripping media sanitiser; the
  content-only RecoveryRecord with setup and restore; offline no-account
  onboarding behind an adult gate with legal-version consent; emergency wipe,
  keys-first, behind an unforgeable confirmation; the first user-facing copy;
  and the full CI stack with nine required checks.
- ADRs 005–011 are all Accepted. ADR-010 is **partially discharged** (the
  capability probe and the confirmation tokens exist; App Lock and the
  action-level auth prompts are WP-03) and ADR-011 is **not discharged** — both
  need surfaces WP-02 does not have. CR-001 and CR-002 are both **executed**;
  see `docs/change-requests/`.
- The application shells now contain real domain code, but **there is still no
  activity, no view and no UI anywhere.** Do not assume a surface exists that
  you have not read.
