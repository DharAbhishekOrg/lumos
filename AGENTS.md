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
lines/file, ≤500/class. Zero analyzer warnings (Checkstyle+SpotBugs+Android Lint /
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

- **WP-01 is closed.** Full record: `evidence/wp01/closeout.md`, with
  machine-readable results in `evidence/wp01/closeout-results.json`. Read the
  closeout before starting WP-02 — it lists every deviation, every ADR raised,
  and everything left `not_started` with its destination WP.
- Active WP: **WP-02** (EDP §7). Do not start it without its kickoff brief.
- **WP-02 preflight, owner-assigned 2026-08-12 (TRS-REP-008):** seed the threat
  model and privacy data map **before** feature code. They constrain design;
  written afterwards they only describe it. They then live per-WP and are
  audited in WP-11.
- Trace matrix: 648 entries. Of the 33 IDs in WP-01's declared scope, 17 are
  `tested`, 9 `implemented` and 7 `not_started`. All nine EDP §8 owner gates
  remain `unproven_owner`; no agent may advance one.
- Repo layout is the TRS §4.1 ten-root form. The handover pack's
  `requirements/`, `acceptance/` and `prompts/` directories were moved under
  `docs/` in slice S1; the original archive is retained at
  `docs/handover/Lumos_Codex_Handover_Pack.zip`.
- Toolchains are pinned in `tools/toolchain/toolchain-manifest.json`. Swift is
  unavailable on the Windows authoring host by design; the iOS conformance
  runner and the iOS shell build on a GitHub Actions `macos-latest` runner.
- What exists: 306 frozen constants with tri-language codegen (no hand-written
  constant anywhere), the protocol schemas, the complete TRS §18.4 golden-vector
  suite, Go/Java/Swift/TypeScript conformance and unit runners, the traceability
  gate, the PR pipeline (lint, secret scan, dependency locks, SBOM stub), and
  four EMPTY application shells.
- ADRs 005–009 were raised during WP-01 and are all Accepted; two carry flags
  for gate G-07 (external cryptographic review). See `docs/adrs/`.
- Beyond the above, nothing is implemented. The shells are empty by instruction.
  Do not assume any code exists that you have not read.
