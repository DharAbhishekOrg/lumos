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
| `schemas/frame_header.schema.json` | **Present** — TRS Sec. 9.1 binary layout |
| `schemas/message_payloads.schema.json` | **Present** — per-class bounds + AMD-005 event envelope |
| `schemas/adv_v1.schema.json` | **Present** — TRS Sec. 9.3 BLE hint (ADR-002) |
| `schemas/prekey_bundle.schema.json` | **Present** — typed prekey kinds, public material only |
| `schemas/error_codes.schema.json` | **Present** — stable registry (TRS-PROTO-013) |
| `schema/` | **Present** — Go loader and invariant checks |

## How the schemas stay honest

- **Byte offsets are never written down.** The loader derives them from field
  widths in declaration order, so an offset cannot drift out of step with a
  width, and the derived total is cross-checked against
  `framing.FRAME_HEADER_LEN_BYTES`.
- **Schemas reference constants by register path**, never by value. A test
  resolves every `*_ref` against the register, so a stale path fails the build.
- **Every message class needs a payload bound.** Adding a class to the register
  without giving it a `max_payload_ref` fails the build (TRS-PROTO-007).
- **Error codes are checked both ways**: every code a schema names must exist in
  the registry, and user-facing strings are scanned for protocol jargon on word
  boundaries (TRS-UX-005). The jargon detector has its own test, because a
  regex that silently matches nothing would pass every leak.

## Not yet built

The wire **codec** — the encoder and decoder that these schemas describe — is not
implemented. S3 delivers the schemas, their register bindings and their
invariants; the codecs and their golden vectors arrive in slices S4 and S5.

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
