# ADR-005 (DRAFT) — Interpretation of SAFETY_NUMBER_FORMAT

- **Status:** DRAFT — raised by the implementing agent per TRS-AI-006. **Not accepted.** Requires owner decision.
- **Raised:** 2026-08-11, WP-01 slice S2
- **Affected requirements:** TRS-CONST-001, TRS-CONST-003, SRS-IDN-005, SRS-MAT-008, TRS-TST-002
- **Affected artefacts:** `protocol/constants/lumos_constants.json`, `test-vectors/crypto/safety_number.json` (WP-01 slice S4), verification token derivation (WP-04)

## Context

TRS §23.2 freezes `SAFETY_NUMBER_FORMAT` as:

> 60 digits + 6-emoji pair — fixed — No (not overridable) — SRS-IDN-005, SRS-MAT-008

EDP §4.2 carries the identical wording. The phrase "6-emoji pair" admits two
readings, and the anchor requirements do not disambiguate:

- **SRS-IDN-005** — "shall provide a human-verifiable safety number, emoji pair
  or short code after mutual matching."
- **SRS-MAT-008** — "shall receive a shared in-venue verification token such as
  emoji pair and color pulse."
- **TRS §18.4** — `crypto/safety_number.json`: "identity pairs -> 60-digit +
  emoji-pair output."

Neither states an emoji count. "pair" is used throughout to describe the *shared
between two users* property of the token, not a quantity of two.

## The two readings

| Reading | Emoji rendered | Entropy carried by the emoji code |
| --- | --- | --- |
| **A — six emoji total** | 6 | 6 × log2(alphabet) |
| **B — six pairs, twelve emoji** | 12 | 12 × log2(alphabet) |

The distinction is not cosmetic. It changes the derived output length, the
golden vectors, both apps' verification UI, and the WP-04 in-venue verification
token.

## Decision required

The register currently encodes **Reading A** (`digits: 60`, `emoji_count: 6`) as
the more natural parse of "6-emoji". This is provisional.

## Why this must be settled before WP-01 slice S4

`SAFETY_NUMBER_FORMAT` is frozen and non-overridable, and it is an input to
`constants_hash`. Per TRS-CONST-003, changing it after vectors exist requires an
approved change request, regeneration of every affected golden vector and a full
protocol-conformance re-run. Settling it **before** S4 generates
`crypto/safety_number.json` costs nothing; settling it after costs a change
request and a vector regeneration cycle.

## Consequences of accepting Reading A

- `constants_hash` stabilises at its current value.
- S4 generates the safety-number vectors against a 60-digit + 6-emoji output.

## Consequences of accepting Reading B

- `constants_hash` changes; every vector file embedding it is regenerated.
- No other WP-01 artefact is affected, because no derivation code exists yet.

## Status of this draft

Recorded so the ambiguity is visible rather than silently resolved by the
implementer. The agent has **not** treated Reading A as owner-approved; it is a
documented provisional encoding, and the trace matrix reflects that
`SAFETY_NUMBER_FORMAT`'s anchor requirements remain `not_started`.
