# /protocol

Canonical schemas, constants and generated code (TRS Sec. 4.1, Sec. 9).

This directory is the **single source of truth** for on-wire behavior. Android,
iOS and the backend consume generated artefacts from here; they never duplicate
a numeric definition (TRS-REP-002, SRS-NFR-MNT-002).

## Contents

| Path | Status |
| --- | --- |
| `constants/lumos_constants.seed.json` | Present — seed of TRS Sec. 23, superseded in slice S2 |
| `constants/lumos_constants.json` | WP-01 slice S2 — master register, service UUID, `constants_hash` |
| schemas (frame header, message classes, `adv_v1`, prekey bundle, envelopes) | WP-01 slice S3 |
| generated code (`LumosConstants.java`, `LumosConstants.swift`, `constants.go`) | WP-01 slice S2 |

## Rules

- **Codegen, not copies.** A literal duplicated constant in any language is a
  defect (ADR-004, TRS-CONST-001).
- Generated files carry a `GENERATED - DO NOT EDIT` header naming the generator
  (TRS Sec. 24.1).
- Changing a frozen constant invalidates the golden vectors and requires an
  approved change request plus regeneration (TRS-CONST-003).
