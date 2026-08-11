# /config-schema

Signed configuration package and voucher JSON/CBOR schemas (TRS Sec. 4.1).

This is the `schemas` root required by TRS-REP-001.

## Contents

| File | Status |
| --- | --- |
| `config_package.schema.json` | **Present** — signed configuration envelope (TRS Sec. 13) |
| `voucher_token.schema.json` | **Present** — signed voucher / bootstrap token |
| `config_overrides.schema.json` | **Generated** — override bounds derived from the constants register |

## Derived, not maintained

`config_overrides.schema.json` is emitted by `tools/constantsgen` directly from
`protocol/constants/lumos_constants.json`, as ADR-004 requires. Only constants
marked `overridable` appear, each carrying its register `min`/`max`, and
`additionalProperties` is `false` so a package naming a frozen constant is
**rejected rather than ignored**. Hand-editing it would reintroduce the
duplicated-constant defect ADR-004 exists to prevent; CI byte-compares it.

## Two limits worth knowing

- **Cross-field constraints are not enforceable by JSON Schema.**
  `SCAN_WINDOW_FG_MS` and `SCAN_WINDOW_VENUE_MS` are bounded by a duty-cycle
  *ratio* (`on / (on + off)`), which JSON Schema 2020-12 cannot express. The
  generated schema carries these under `x-cross-field-constraints` and lists the
  affected properties under `x-unbounded-properties`. **Passing schema validation
  does not prove a scan-window override is in bounds** — the config client must
  apply the duty-cycle rule itself and fail closed. This is a WP-08 obligation,
  recorded here so it cannot be assumed handled.
- **Field-level overridability.** `VOICE_NOTE` is overridable only in
  `max_sec`; TRS Sec. 23.5 fixes the codec profile. The register expresses this
  with `overridable_fields`, and the generated schema offers no other
  voice-note property.

## Status of the workflow

The signing, activation, rollback and kill-switch workflow is **WP-08**. What
exists today is the envelope shape and the mechanically derived bounds.

## Rules

- Signed configuration may override only constants marked overridable in TRS
  Sec. 23, and only within their stated bounds; out-of-range packages fail
  schema validation client-side (TRS-CONST-002).
- Schemas are versioned and compatibility-tested before merge (TRS-CICD-005).
- Configuration never carries executable content (TRS-ARCH-006).
