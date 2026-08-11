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

## Open decisions blocking full constants closure

- **ADR-005 (draft)** — `SAFETY_NUMBER_FORMAT` "6-emoji pair" is ambiguous;
  `emoji_count: 6` is provisional. Must be settled before slice S4 generates
  `crypto/safety_number.json`.
- **ADR-006 (draft)** — eleven overridable constants carry `bounds_unspecified`
  because TRS Sec. 23 states "bounded"/"calibrated" without a numeric range.
  TRS-CONST-002 is not satisfiable for those until the owner supplies bounds.

## Rules

- **Codegen, not copies.** A literal duplicated constant in any language is a
  defect (ADR-004, TRS-CONST-001).
- Generated files carry a `GENERATED - DO NOT EDIT` header naming the generator
  (TRS Sec. 24.1).
- Changing a frozen constant invalidates the golden vectors and requires an
  approved change request plus regeneration (TRS-CONST-003).
