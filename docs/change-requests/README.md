# Change requests

TRS-CONST-003 and SRS §evolution require an **approved change request** before
any frozen constant, accepted behaviour or pinned specification value moves.
This directory is the register of those approvals. An ADR records *why a design
is what it is*; a CR records *an owner's authority to change something already
frozen*, and names the slice that executes it.

| CR | Status | Executes | Subject |
| --- | --- | --- | --- |
| [001](CR-001.md) | **Accepted** | WP-02 S4c (done) | `RECOVERY_ARGON2ID.parallelism` becomes a fixed 1; `parallelism_min`/`_max` removed |
| [002](CR-002.md) | **Accepted** | WP-02 S5 (done) | TRS §5.3 toolchain Java 17 → Java 21 LTS, to adopt Lazysodium 5.2.0 (16 KB alignment, G-06 blocker) |
| [003](CR-003.md) | **Accepted** (route open) | WP-07 | Share a one-shot location in a matched chat; accepted 2026-11-22, route (a) nearby-only or (b) relay still to be chosen |

A CR is applied in exactly one slice. Applying one that moves `constants_hash`
also regenerates every golden vector that embeds it and reruns the full
protocol-conformance suite in the same commit (TRS-CONST-003).
