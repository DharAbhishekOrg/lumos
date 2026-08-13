# /docs

SRS, TRS, EDP, ADRs, threat model and privacy map (TRS Sec. 4.1).

## Baselined source of truth

Reading order per AGENTS.md Sec. 1:

| File | Contents |
| --- | --- |
| `Lumos_SRS_v1_1.pdf` | Behavior — 396 requirements, 33 acceptance tests |
| `Lumos_TRS_v1_1.pdf` | Architecture, protocol, constants (Sec. 23), DFMEA (Sec. 22), code standard (Sec. 24), extensibility (Sec. 25) — 252 requirements |
| `Lumos_EDP_v1_0.pdf` | ADR-001..004 full records, WP-01..WP-11 kickoff briefs (Sec. 7), owner-executed gates G-01..G-09 (Sec. 8) |

If prose and a numbered requirement conflict, the stricter privacy/safety/security
interpretation applies (SRS Sec. 2.4).

## Machine-readable equivalents

Same content, easier to query:

- `requirements/srs_requirements_v1_1.json`
- `requirements/trs_requirements_v1_1.json`
- `acceptance/acceptance_tests_v1_1.csv`

## Other contents

| Path | Status |
| --- | --- |
| `prompts/` | Per-work-package kickoff prompts |
| `handover/` | Original handover pack archive, retained for provenance |
| `adrs/` | ADR-005..009, all **Accepted**. ADR-001..004 remain in the EDP; back-porting them as standalone records is not a WP-01 deliverable |
| [`security/threat-model.md`](security/threat-model.md) | **Seeded** in WP-02 slice S0 (TRS-REP-008 phase 1). Covers the Zone-A device-compromise surface, key storage and the WP-02 constraints that follow from them |
| [`privacy/data-map.md`](privacy/data-map.md) | **Seeded** in WP-02 slice S0 (TRS-REP-008 phase 1). Classifies locally held personal data and maps it to purpose, lawful basis, retention and data-subject rights |

Both documents are **design inputs, written before WP-02 feature code**: the
threat model's §7 lists decisions WP-02 is no longer free to make, and the data
map classifies fields the persistence layer has yet to create. Per the owner's
2026-08-12 TRS-REP-008 assignment they then live per work package — the package
that changes the attack surface or the data map updates them in the same pull
request — with the final documentation audit in WP-11.

## Architecture decision records

| ADR | Status | Subject |
| --- | --- | --- |
| ADR-001..004 | Accepted | Recorded in `Lumos_EDP_v1_0.pdf` — crypto, BLE asymmetry, recovery, constants freeze |
| [ADR-005](adrs/ADR-005-safety-number-format.md) | **Accepted** 2026-08-11 | `SAFETY_NUMBER_FORMAT` = 60 digits + 6 emoji from a 64-emoji alphabet |
| [ADR-006](adrs/ADR-006-override-bounds.md) | **Accepted** 2026-08-11 | Override bounds for the eleven constants TRS §23 left unbounded |
| [ADR-007](adrs/ADR-007-wire-encoding-choices.md) | **Accepted** 2026-08-11 | Version-byte nibbles and the four frame flag bit positions |
| [ADR-008](adrs/ADR-008-identity-dh-key.md) | **Accepted** 2026-08-11, 2 conditions | Separate X25519 identity-DH key; Ed25519 stays the sole root of trust. **Flagged for G-07** |
| [ADR-009](adrs/ADR-009-safety-number-emoji-alphabet.md) | **Accepted** 2026-08-11 | The 64-emoji alphabet; non-confusability reasoned, not human-tested. **Flagged for G-07** |

## Note on `handover/Lumos_Codex_Handover_Pack.zip`

The archive is password-protected, so its contents cannot be hash-verified. All
11 entries were confirmed present in the working tree by name and exact
uncompressed size before relocation. It is **retained, not deleted**, because
deletion on size-equality alone is not reversible.
