# WP-01 Kickoff — Repository, Schemas, Constants, Vector Harness and CI Bootstrap

Read AGENTS.md first, then LUMOS-EDP-001 §7 (WP-01 brief), TRS §4 (repo layout),
§9 (protocol), §18.4 (vector harness), §19.3 (traceability), §23 (constants),
§24 (code standard). Then execute WP-01 exactly as follows.

## Objective
Stand up the monorepo, the canonical protocol schemas with tri-language codegen,
the complete golden-vector suite with Java/Swift/Go conformance runners, the
seeded traceability enforcement, and hermetic CI — before any feature code exists.

## Ordered deliverables
1. Repo roots per TRS §4.1 (android-app, ios-app, backend, admin-console,
   protocol, test-vectors, config-schema, tools, docs, evidence). Move the files
   of this handover pack into their proper locations.
2. `/protocol/constants/lumos_constants.json`: formalize from
   `lumos_constants.seed.json` (TRS §23 is normative), generate one fixed 128-bit
   Lumos service UUID and commit it, compute constants_hash. Codegen emits
   LumosConstants.java, LumosConstants.swift, constants.go + message-class enums;
   wire codegen into all builds. No hand-written constants anywhere.
3. `/protocol` schemas: frame header (TRS §9.1), message classes incl. the 0x10
   inner event_kind envelope (AMD-005), BLE adv_v1 hint layout (TRS §9.3),
   prekey bundle with typed prekey-kind, config/voucher envelopes.
4. `/tools/vectorgen` (Go): deterministic generator for the full §18.4 tree —
   framing (positive + invalid), crypto (x3dh_kat, ratchet_sequences incl.
   out-of-order/skip/MAX_SKIP-breach, aead_kat with header-as-AAD tamper cases,
   safety_number), adv/ble_advertisement (EID schedule + authTag), media
   object_pipeline, config valid/invalid, voucher tokens, errors. TESTONLY- key
   namespacing. Double-generate + byte-compare determinism self-check.
5. Conformance runners: Android pure-JVM `:protocol-conformance` (JUnit, no
   Android framework), iOS SwiftPM `LumosProtocolConformance` (XCTest,
   macOS-runnable, no UIKit), `go test ./protocol/...`. All consume the committed
   vector files; every negative vector must fail with the exact stable error code.
6. Traceability enforcement: CI job validating trace-matrix.json completeness
   (all 648 IDs), freshness vs module-map.json, and status honesty rules
   (TRS §19.3). Extend module-map as modules are created.
7. PR pipeline: format, lint (architecture + header/doc lint per TRS §24),
   unit, vectors, secret scan, dependency lock + SBOM stub. Empty-shell Android,
   iOS, backend and admin builds compile clean. Bootstrap scripts for a fresh
   machine (TRS-REP-006), documented in the repo.

## Exit evidence (all agent-provable; report machine-readable results)
- Three conformance runners green on the full vector suite in CI.
- Determinism self-check green; constants_hash cross-check green.
- Clean-machine build documented and executed in CI containers.
- Trace matrix updated: WP-01 requirement IDs (TRS-REP-*, TRS-PROTO-001..016,
  TRS-CONST-*, TRS-CICD-001/005, TRS-TST-001) moved to implemented/tested with
  linked tests; everything else remains not_started. No inflated statuses.

## Out of scope — do not touch
Feature UI, radio code, backend endpoints beyond a health check, billing,
anything requiring credentials or network resources beyond dependency fetch.

## Known traps
- Swift conformance must run on a macOS CI host without a simulator.
- Java/Swift never generate vectors — consume only (single-generator rule).
- Codegen, not copies: a literal duplicated constant is a defect.
