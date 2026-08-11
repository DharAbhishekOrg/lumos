# /test-vectors

Cross-language crypto and protocol golden vectors (TRS Sec. 4.1, Sec. 18.4).

## Single generator, three consumers

`/tools/vectorgen` (Go) deterministically produces **every** file here. The
Java, Swift and Go runners only consume and assert, so no platform can agree
with itself. Java and Swift never generate vectors.

## Status

| File | Contents | Status |
| --- | --- | --- |
| `manifest.json` | schema_version, generator commit, constants hash | **Present** (S4a) |
| `framing/frame_roundtrip.json` | every message class: fields -> exact bytes; decode-only skippable cases | **Present** (S4a) — 26 cases |
| `framing/frame_invalid.json` | truncation, bad magic/lengths, unknown critical type, reserved bits, oversize | **Present** (S4a) — 12 negatives, exact codes |
| `errors/error_codes.json` | stable code registry snapshot | **Present** (S4a) — 27 codes |
| `crypto/x3dh_kat.json` | identity+prekeys -> shared secret + transcript AD; with/without OPK | **Present** (S4b) |
| `crypto/ratchet_sequences.json` | in-order, out-of-order, skipped, DH turns, MAX_SKIP-breach negative | **Present** (S4b) |
| `crypto/aead_kat.json` | ChaCha20-Poly1305 over the real frame header as AAD; 3 tamper cases | **Present** (S4b) |
| `crypto/safety_number.json` | identity pairs -> 60 digits + 6 emoji; embeds the frozen 64-glyph alphabet | **Present** (S4b) |
| `adv/ble_advertisement.json` | EID schedule + authTag derivation per ADR-002 | S4c |
| `media/object_pipeline.json` | object key wrap, whole-object AEAD, SHA-256, chunk plan, ACK | S4c |
| `config/package_valid.json` | canonical bytes, payloadHash, signatures | S4c |
| `config/package_invalid.json` | tampered, expired, wrong env, rollback attempt | S4c |
| `voucher/voucher_tokens.json` | valid, replayed nonce, revoked, expired | S4c |

Regenerate with `go run ./tools/vectorgen`; CI verifies with
`go run ./tools/vectorgen -check`. Generation always double-builds and
byte-compares before writing, so a nondeterministic generator cannot emit at all.

## Rules

- Vectors are committed, **immutable once released in a tag**, and additive in
  evolution.
- Each file carries the requirement IDs it verifies plus a `constants_hash`
  binding it to TRS Sec. 23.
- Negative vectors are first-class: every "shall reject" requirement has at
  least one vector that must fail with the **exact** stable error code of
  TRS-PROTO-013.
- All key material is namespaced `TESTONLY-`. A skipped file is a failed gate.

## Carried into slice S4: the safety-number emoji alphabet

ADR-005 fixes `SAFETY_NUMBER_FORMAT` at 60 digits plus 6 emoji from a **64-emoji
alphabet**. S4 freezes the alphabet itself in `crypto/safety_number.json`.

**Selecting those 64 emoji is a security decision, not a cosmetic one.** The emoji
code is a surface two people compare by eye to detect a man-in-the-middle. Two
criteria are binding:

- **Cross-platform font survival** — a glyph that renders as a replacement box, or
  differently enough on Android and iOS to look like a different symbol, breaks the
  comparison for the pair that needs it most.
- **Non-confusability** — near-duplicate glyphs (skin-tone or gender variants, the
  same object in two colours, visually similar faces) let an attacker search for a
  colliding fingerprint that *looks* right at a glance. Distinct silhouettes matter
  more than variety.

Both criteria must be recorded as rationale alongside the frozen alphabet, so a
later change cannot quietly reintroduce a confusable pair.
