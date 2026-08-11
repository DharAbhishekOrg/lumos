# /evidence

Machine-readable test results and the traceability matrix (TRS Sec. 4.1, Sec. 19.3).

## Contents

| Path | Status |
| --- | --- |
| `trace/trace-matrix.json` | Present — 648 entries, one per catalogued SRS/TRS requirement ID |
| `trace/module-map.json` | Present — module-path prefix -> requirement domain mapping |
| `<release>/...` | Per-release machine-readable results (TRS Sec. 18.3); none yet |

## The matrix is an input to the Definition of Done, not paperwork produced for it

Per EDP Sec. 6.2 and TRS Sec. 19.3, CI enforces three rules — the validator is
delivered in WP-01 slice S6:

- **Seeding** — every catalogued SRS/TRS ID has exactly one entry, and every
  matrix ID is in the catalogues. Verified as of WP-01 slice S1: 648 = 648,
  zero orphans in either direction.
- **Freshness** — a PR touching files under a module mapped in `module-map.json`
  must also update those entries' `last_commit`, else CI fails with the list of
  stale IDs.
- **Honesty** — `tested` requires at least one linked test that executed in the
  same pipeline run; `unproven` is mandatory for any EDP Sec. 8 owner-executed
  gate until owner evidence is attached.

A matrix reconstructed after the fact is indistinguishable from a fabricated
one. Statuses are never inflated: anything not done stays `not_started`.

## WP-01 owner-executed gates

**None.** Per EDP Sec. 7, WP-01 is fully provable in CI.
