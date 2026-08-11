# ADR-005 — SAFETY_NUMBER_FORMAT is 60 digits plus 6 emoji

- **Status:** **ACCEPTED** by the owner, 2026-08-11 (WP-01 slice S2).
- **Raised:** 2026-08-11 by the implementing agent per TRS-AI-006.
- **Affected requirements:** TRS-CONST-001, TRS-CONST-003, SRS-IDN-005, SRS-MAT-008, TRS-TST-002
- **Affected artefacts:** `protocol/constants/lumos_constants.json`, `test-vectors/crypto/safety_number.json` (WP-01 slice S4), verification token derivation (WP-04)

## Context

TRS §23.2 freezes `SAFETY_NUMBER_FORMAT` as "60 digits + 6-emoji pair", and
EDP §4.2 repeats the wording. The phrase admits two readings, and the anchor
requirements do not disambiguate:

- **SRS-IDN-005** — "a human-verifiable safety number, emoji pair or short code".
- **SRS-MAT-008** — "a shared in-venue verification token such as emoji pair and colour pulse".
- **TRS §18.4** — `crypto/safety_number.json`: "identity pairs -> 60-digit + emoji-pair output".

"pair" is used throughout to describe the token being *shared between two users*,
not to state a quantity. The two candidate readings were six emoji in total, or
six pairs of emoji (twelve).

## Decision

**60 decimal digits plus 6 emoji in total — not 12.**

The emoji are drawn from a **fixed curated 64-emoji alphabet**. The alphabet
itself is frozen by WP-01 slice S4 in `test-vectors/crypto/safety_number.json`;
this ADR fixes only its size, which is what the register needs in order to
generate constants.

Encoded in the register as:

```json
"SAFETY_NUMBER_FORMAT": {
  "digits": 60, "emoji_count": 6, "emoji_alphabet_size": 64, "overridable": false
}
```

A 64-symbol alphabet carries 6 bits per emoji, so the emoji code contributes 36
bits of the comparison surface alongside the 60 decimal digits.

## Consequences

- `constants_hash` moved once, at acceptance, before any vector existed to
  regenerate. This was the whole reason for raising the ambiguity before slice S4
  rather than discovering it during vector generation.
- Slice S4 must freeze the concrete 64-emoji alphabet, and should choose symbols
  that survive cross-platform font rendering and are not visually confusable —
  the alphabet is a human-verification surface, so near-duplicate glyphs directly
  weaken the check.
- WP-04's in-venue verification token derives from the same value.
- `SAFETY_NUMBER_FORMAT` is not overridable, so no signed-config bounds apply.

## Verification

`TestSafetyNumberFormatMatchesADR005` in `tools/constantsgen` pins all three
values against this decision, transcribed from the ADR rather than read back out
of the register.
