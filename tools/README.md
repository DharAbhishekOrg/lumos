# /tools

Signing, fixture, replay, vectorgen and evidence utilities (TRS Sec. 4.1).

Go; part of the repository-root Go module `github.com/dharabhishekemail-ops/lumos`.

## Contents

| Path | Status |
| --- | --- |
| `repolint/` | Present — repository-layout conformance check (WP-01 slice S1) |
| `toolchain/` | Present — pinned toolchain manifest (WP-01 slice S0) |
| `vectorgen/` | WP-01 slice S4 — the single deterministic golden-vector generator |
| `constantsgen/` | WP-01 slice S2 — tri-language constants codegen |
| `tracecheck/` | WP-01 slice S6 — trace-matrix completeness/freshness/honesty validator |

## Rules

- Tools never touch signing material, production credentials or `.env` secrets
  (TRS-REP-005).
- `vectorgen` is the only program permitted to write under `/test-vectors`.
