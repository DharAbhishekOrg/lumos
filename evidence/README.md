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

## The gate (WP-01 slice S6)

`tools/tracecheck` enforces all three rules in CI, after the three conformance
runners so it can prove `tested` rests on a test that ran in **this** run.

```
go run ./tools/tracecheck                       # completeness + structural honesty
go run ./tools/tracecheck -base origin/main     # + freshness against the PR base
go run ./tools/tracecheck -executed <set.json>  # + the run-linked honesty rule
go run ./tools/tracecheck -stamp -auto          # stamp the entries your change requires
```

**There is no exclusion list.** Slice S2 carried a placeholder `**/README.md`
exclusion only because restamping by hand was tedious enough to make the gate
feel like noise. `-stamp -auto` computes exactly which entries a change requires
and rewrites them in one command, so freshness now applies to **every** file
under a mapped module, documentation included.

`last_commit` records the HEAD the entry's evidence was produced against — a
commit cannot contain its own hash — and freshness proves the field *changed*
between base and head, which is what EDP §6.2 actually asks for.

### What the gate refuses

- an ID missing from the matrix, an entry absent from the catalogues, a duplicate row, an invented status;
- a change under a mapped module without a corresponding `last_commit` change;
- `tested` with no linked test, no linked code, or no linked test that **executed in this run**;
- an EDP §8 owner gate claimed as `tested` — an agent cannot produce that evidence (TRS-AI-009/010/014);
- `unproven` outside an owner gate, or without an `owner_signoff` field;
- `owner_executed` in the gate register without both a sign-off and an evidence file.

## WP-01 owner-executed gates

**None.** Per EDP Sec. 7, WP-01 is fully provable in CI.
