# /tools

Signing, fixture, replay, vectorgen and evidence utilities (TRS Sec. 4.1).

Go; part of the repository-root Go module `github.com/dharabhishekorg/lumos`.

## Contents

| Path | Status |
| --- | --- |
| `repolint/` | Present — repository-layout conformance check (WP-01 slice S1) |
| `toolchain/` | Present — pinned toolchain manifest (WP-01 slice S0) |
| `vectorgen/` | Present — the single deterministic golden-vector generator (framing + errors in S4a; crypto S4b; adv/media/config/voucher S4c) |
| `constantsgen/` | Present — tri-language constants codegen + generated error codes, class limits and config-override schema |
| `tracecheck/` | Present — trace-matrix completeness / freshness / honesty gate, plus `-stamp` and the executed-test collector |

## Rules

- Tools never touch signing material, production credentials or `.env` secrets
  (TRS-REP-005).
- `vectorgen` is the only program permitted to write under `/test-vectors`.
