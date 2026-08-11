# ADR-006 (DRAFT) — Eleven overridable constants have no stated bounds

- **Status:** DRAFT — raised by the implementing agent per TRS-AI-006. **Not accepted.** Requires owner decision.
- **Raised:** 2026-08-11, WP-01 slice S2
- **Affected requirements:** TRS-CONST-002 (P0), TRS-CONST-001, TRS-CFG-001, TRS-ARCH-010
- **Affected artefacts:** `protocol/constants/lumos_constants.json`, config schema (WP-01 slice S3), signed-config validation (WP-08)

## Context

TRS-CONST-002 is a P0 requirement:

> Signed configuration may override only constants marked overridable in Section 23
> and only within their stated bounds; out-of-bounds values **fail schema validation**.

For most of the register this is directly implementable: TRS §23 states an
explicit `Min..Max` (for example `EID_ROTATION_SEC` 300..900). For **eleven**
entries the `Min..Max` column instead reads `bounded` or `calibrated`, which
names the intent without supplying a number:

| Constant | TRS §23 row | Bounds column |
| --- | --- | --- |
| `RESCAN_LADDER` | 23.1 | bounded |
| `RSSI_BANDS_DBM` | 23.1 | calibrated |
| `RELAY_MAILBOX_QUOTA` | 23.2 | bounded |
| `DEDUP_CACHE` | 23.2 | bounded |
| `STORE_FORWARD_QUOTA` | 23.3 | bounded |
| `REASSEMBLY_TIMEOUT_SEC` | 23.4 | bounded |
| `MEDIA_CACHE_QUOTA_MB` | 23.5 | bounded |
| `SPONSOR_ASSET_MAX_KB` | 23.6 | bounded |
| `VENUE_ROOM_POST_RATE_PER_MIN` | 23.6 | bounded |
| `VOUCHER_NONCE_CACHE` | 23.6 | bounded |
| `SUPPORT_RING_BUFFER_MB` | 23.6 | bounded |

A client cannot reject an out-of-range override for these, because no range
exists to compare against. TRS-CONST-002 is therefore **not satisfiable** for
these ten constants as the baseline stands.

## What the agent did instead of inventing numbers

Choosing plausible minima and maxima here would mean writing normative protocol
policy into a frozen register under the appearance of transcription. That is the
failure mode ADR-004 exists to prevent, so it was not done.

Each affected entry instead carries an explicit machine-readable marker:

```json
"overridable": true, "bounds_unspecified": true
```

`TestEveryOverridableEntryDeclaresBoundsOrTheGap` enforces that **every**
overridable entry either states numeric bounds or carries this flag, and a second
assertion pins the flagged set to exactly the eleven above. A new overridable
constant therefore cannot be added without either real bounds or a deliberate,
reviewed admission that they are missing. The gap is visible in CI rather than
resting in a document nobody re-reads.

## Decision required

For each of the eleven, the owner supplies a `Min..Max`. Suggested shape for the
decision, not values the agent is proposing:

- timeout-style entries (`REASSEMBLY_TIMEOUT_SEC`) need a floor that tolerates
  real BLE/LAN transfer latency and a ceiling that bounds memory held by partial
  reassemblies;
- quota-style entries (`RELAY_MAILBOX_QUOTA`, `STORE_FORWARD_QUOTA`,
  `DEDUP_CACHE`, `VOUCHER_NONCE_CACHE`, `MEDIA_CACHE_QUOTA_MB`,
  `SUPPORT_RING_BUFFER_MB`) need a floor that keeps the feature functional and a
  ceiling that bounds device storage;
- rate-style entries (`VENUE_ROOM_POST_RATE_PER_MIN`) need an abuse-resistance
  ceiling consistent with SRS-SAF-006;
- calibration entries (`RESCAN_LADDER`, `RSSI_BANDS_DBM`) receive on-device
  tuning in WP-03/WP-07 per TRS-CONST-004, so their bounds may reasonably be
  settled with that calibration evidence rather than now.

## Timing

This does **not** block WP-01 slices S3–S5. It **does** block:

- **WP-01 slice S3**, if the config-envelope schema is to mechanically derive
  min/max validation from the register (ADR-004 says it should).
- **WP-08**, where signed-config validation must fail closed.

Settling it before S3 is cheapest: the constants hash moves once, and no vectors
exist yet to regenerate.

## Consequences

- **If bounds are supplied:** `bounds_unspecified` flags are removed,
  `constants_hash` moves once, and TRS-CONST-002 becomes fully implementable.
- **If bounds are deliberately deferred:** the flag stays, and the config schema
  must reject overrides of flagged constants entirely rather than fail open —
  which is the stricter reading required by SRS §2.4 and TRS-ARCH-010.
