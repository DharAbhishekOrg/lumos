# ADR-006 — Override bounds for the eleven constants TRS §23 left unbounded

- **Status:** **ACCEPTED** by the owner, 2026-08-11 (WP-01 slice S2).
- **Raised:** 2026-08-11 by the implementing agent per TRS-AI-006.
- **Affected requirements:** TRS-CONST-002 (P0), TRS-CONST-001, TRS-CFG-001, TRS-ARCH-010
- **Affected artefacts:** `protocol/constants/lumos_constants.json`, config schema (WP-01 slice S3), signed-config validation (WP-08)

## Context

TRS-CONST-002 is a P0 requirement:

> Signed configuration may override only constants marked overridable in Section 23
> and only within their stated bounds; out-of-bounds values **fail schema validation**.

Most of the register states an explicit `Min..Max`. For **eleven** entries the
bounds column instead read `bounded` or `calibrated` — naming the intent without
supplying a number. A client cannot reject an out-of-range override when no range
exists, so TRS-CONST-002 was **not satisfiable** for those eleven.

The implementing agent declined to invent plausible ranges, since writing
normative protocol policy into a frozen register under the appearance of
transcription is exactly the failure mode ADR-004 exists to prevent. Each entry
was instead flagged `bounds_unspecified` and escalated.

## Decision

The owner supplied the following normative bounds. All are now in the register,
every `bounds_unspecified` flag is cleared, and no overridable constant remains
without a range.

| Constant | Field | Default | Min | Max |
| --- | --- | ---: | ---: | ---: |
| `RESCAN_LADDER` | `step_pct` | 50 | 25 | 100 |
| `RESCAN_LADDER` | `trigger_empty_cycles` | 2 | 1 | 4 |
| `RESCAN_LADDER` | `max_steps` | 3 | 1 | 5 |
| `RSSI_BANDS_DBM` | `very_close_gte` (dBm) | −55 | −60 | −45 |
| `RSSI_BANDS_DBM` | `nearby_gte` (dBm) | −75 | −85 | −65 |
| `REASSEMBLY_TIMEOUT_SEC` | `ble` | 120 | 60 | 300 |
| `REASSEMBLY_TIMEOUT_SEC` | `lan` | 30 | 15 | 120 |
| `RELAY_MAILBOX_QUOTA` | `envelopes` | 1000 | 200 | 5000 |
| `RELAY_MAILBOX_QUOTA` | `bytes` | 8 MB | 2 MB | 32 MB |
| `DEDUP_CACHE` | `ids` | 10000 | 5000 | 50000 |
| `DEDUP_CACHE` | `days` | 7 | 3 | 30 |
| `STORE_FORWARD_QUOTA` | `envelopes` | 50 | 10 | 200 |
| `STORE_FORWARD_QUOTA` | `bytes` | 2 MB | 512 KB | 8 MB |
| `MEDIA_CACHE_QUOTA_MB` | `default` (MB) | 512 | 128 | 2048 |
| `MEDIA_CACHE_QUOTA_MB` | `ttl_days` | 30 | 7 | 90 |
| `SPONSOR_ASSET_MAX_KB` | `default` (KB) | 300 | 100 | 512 |
| `SPONSOR_ASSET_MAX_KB` | `cache_count` | 8 | 4 | 16 |
| `VENUE_ROOM_POST_RATE_PER_MIN` | `default` | 10 | 3 | 30 |
| `VOUCHER_NONCE_CACHE` | `count` | 5000 | 1000 | 20000 |
| `VOUCHER_NONCE_CACHE` | `days` | 90 | 30 | 365 |
| `SUPPORT_RING_BUFFER_MB` | `default` (MB) | 4 | 1 | 16 |

Byte values are stored exactly: 2 MB = 2097152, 8 MB = 8388608,
32 MB = 33554432, 512 KB = 524288.

## Encoding

Entries with a single tunable use the existing `default` / `min` / `max` form.
Multi-field entries gain sibling `<field>_min` / `<field>_max` keys — for example
`ble`, `ble_min`, `ble_max`. This keeps every entry a flat map of scalars, which
is what the codegen model consumes, so no emitter change was needed and the
generated identifiers stay mechanical (`REASSEMBLY_TIMEOUT_SEC_BLE_MIN`).

## Verification

- `TestADR006BoundsWereAppliedExactly` transcribes the table above by hand and
  checks each bound landed exactly, with the shipped default inside its range.
- `TestEveryBoundTripleIsCoherent` enforces `min <= value <= max` for every
  per-field triple.
- `TestEveryOverridableEntryDeclaresBoundsOrTheGap` keeps the `bounds_unspecified`
  pin with an **empty** expected set. The pin was deliberately retained rather
  than deleted: it is what prevents a twelfth unbounded overridable constant from
  being introduced silently.

Three entries are exempt from per-field bounds because TRS §23 bounds them
differently, and the exemption list is itself pinned in the test:
`SCAN_WINDOW_FG_MS` and `SCAN_WINDOW_VENUE_MS` are bounded by duty-cycle
percentage, and `VOICE_NOTE` has a fixed codec profile with only its duration
overridable via `sec_min`/`sec_max`.

## Consequences

- TRS-CONST-002 becomes implementable. It remains `not_started` in the trace
  matrix because no validation code exists yet — the config-envelope schema
  arrives in WP-01 slice S3 and signed-config enforcement in WP-08. The data
  blocker is cleared; the behaviour is not yet built.
- `constants_hash` moved once at acceptance. No golden vectors existed yet, so no
  regeneration was required — the friction TRS-CONST-003 imposes was paid at its
  cheapest possible moment.
- Slice S3 can now derive config min/max validation mechanically from the
  register, as ADR-004 intends.
- Calibration entries `RESCAN_LADDER` and `RSSI_BANDS_DBM` keep `calibrated: true`
  and receive on-device tuning within these bounds during WP-03/WP-07 per
  TRS-CONST-004.
