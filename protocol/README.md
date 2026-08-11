# /protocol

Canonical schemas, constants and generated code (TRS Sec. 4.1, Sec. 9).

This directory is the **single source of truth** for on-wire behavior. Android,
iOS and the backend consume generated artefacts from here; they never duplicate
a numeric definition (TRS-REP-002, SRS-NFR-MNT-002).

## Contents

| Path | Status |
| --- | --- |
| `constants/lumos_constants.json` | **Present** — master register, frozen service UUID, `constants_hash` |
| `constants/lumos_constants.seed.json` | Present — original handover seed, retained for provenance; superseded by the master |
| `constants/canonical-form.txt` | **Generated** — the exact bytes `constants_hash` is taken over |
| `gen/go/lumosconstants/` | **Generated** — `constants.go`, `message_class.go` |
| `gen/java/com/lumos/protocol/` | **Generated** — `LumosConstants.java`, `MessageClass.java`, `TextEventKind.java` |
| `gen/swift/LumosConstants.swift` | **Generated** |
| schemas (frame header, `adv_v1`, prekey bundle, envelopes) | WP-01 slice S3 |

## Frozen values

- **Service UUID** `E853EB6A-775C-4A49-A6BF-320E6A5CC609` — one fixed 128-bit GATT
  service UUID (ADR-002 / TRS Sec. 9.3), generated once from a CSPRNG as an
  RFC 4122 v4 UUID in slice S2. Changing it is a breaking protocol change.
- **constants_hash** is computed over `canonical-form.txt`: every hashable leaf
  flattened to `<path>=<value>`, sorted bytewise, newline-joined. Documentation
  fields (`$…`, `anchors`, `source`, `notes`) are excluded, so rewording a
  comment does not invalidate the golden vectors but changing a value does.

## Regenerating

```
go run ./tools/constantsgen          # regenerate all artefacts
go run ./tools/constantsgen -stamp   # recompute and write constants_hash
go run ./tools/constantsgen -check   # CI gate: verify hash + byte-compare outputs
```

## Accepted decisions folded into the register

- **[ADR-005](../docs/adrs/ADR-005-safety-number-format.md)** — `SAFETY_NUMBER_FORMAT`
  is 60 digits plus **6 emoji in total** (not 6 pairs), from a fixed curated
  64-emoji alphabet. Slice S4 freezes the alphabet itself in
  `test-vectors/crypto/safety_number.json`.
- **[ADR-006](../docs/adrs/ADR-006-override-bounds.md)** — the eleven overridable
  constants TRS Sec. 23 left as "bounded"/"calibrated" now carry normative
  bounds. No `bounds_unspecified` flag remains, and the pin-test is kept with an
  empty expected set so a twelfth cannot appear silently.

Multi-field entries express bounds as sibling `<field>_min` / `<field>_max` keys
(for example `ble`, `ble_min`, `ble_max`), which keeps every entry a flat map of
scalars for the codegen model.

Three entries are bounded differently by TRS Sec. 23 and are exempt from
per-field bounds, with the exemption pinned in test: `SCAN_WINDOW_FG_MS` and
`SCAN_WINDOW_VENUE_MS` (duty-cycle percentage) and `VOICE_NOTE` (fixed codec
profile, duration bounded by `sec_min`/`sec_max`).

## Rules

- **Codegen, not copies.** A literal duplicated constant in any language is a
  defect (ADR-004, TRS-CONST-001).
- Generated files carry a `GENERATED - DO NOT EDIT` header naming the generator
  (TRS Sec. 24.1).
- Changing a frozen constant invalidates the golden vectors and requires an
  approved change request plus regeneration (TRS-CONST-003).
