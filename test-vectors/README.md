# /test-vectors

Cross-language crypto and protocol golden vectors (TRS Sec. 4.1, Sec. 18.4).

## Single generator, three consumers

`/tools/vectorgen` (Go) deterministically produces **every** file here. The
Java, Swift and Go runners only consume and assert, so no platform can agree
with itself. Java and Swift never generate vectors.

## Status

Created in WP-01 slice S1. **No vectors exist yet** — the tree below is
generated in slice S4 and consumed by the runners in slice S5.

| File | Contents |
| --- | --- |
| `manifest.json` | schema_version, generator commit, constants hash |
| `framing/frame_roundtrip.json` | every message class: fields -> exact bytes |
| `framing/frame_invalid.json` | truncation, bad magic/lengths, unknown critical type |
| `crypto/x3dh_kat.json` | identity+prekeys -> shared secret, first-message binding |
| `crypto/ratchet_sequences.json` | in-order, out-of-order, skipped, DH turns, MAX_SKIP breach |
| `crypto/aead_kat.json` | ChaCha20-Poly1305 with header-as-AAD; tamper cases |
| `crypto/safety_number.json` | identity pairs -> 60-digit + emoji-pair output |
| `adv/ble_advertisement.json` | EID schedule + authTag derivation per ADR-002 |
| `media/object_pipeline.json` | object key wrap, whole-object AEAD, SHA-256, chunk plan, ACK |
| `config/package_valid.json` | canonical bytes, payloadHash, signatures |
| `config/package_invalid.json` | tampered, expired, wrong env, rollback attempt |
| `voucher/voucher_tokens.json` | valid, replayed nonce, revoked, expired |
| `errors/error_codes.json` | stable code registry snapshot |

## Rules

- Vectors are committed, **immutable once released in a tag**, and additive in
  evolution.
- Each file carries the requirement IDs it verifies plus a `constants_hash`
  binding it to TRS Sec. 23.
- Negative vectors are first-class: every "shall reject" requirement has at
  least one vector that must fail with the **exact** stable error code of
  TRS-PROTO-013.
- All key material is namespaced `TESTONLY-`. A skipped file is a failed gate.
