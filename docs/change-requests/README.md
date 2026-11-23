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
| [003](CR-003.md) | **Accepted** | WP-07 | Share a one-shot location in a matched chat; accepted 2026-11-22, route (b): nearby and over the relay, end-to-end encrypted |
| [004](CR-004.md) | **Accepted** | WP-05 CR-004 slice (done) | New register value `RELAY_SEND_RATE_PER_MIN` (default 120, 30–600) and error `ERR_RELAY_RATE_LIMITED`, completing TRS-BCK-004 |
| [005](CR-005.md) | **Accepted** | WP-05 S7e1 | New post-match, nearby-only class `RELAY_ROUTE` (0x07) for swapping relay addresses; receipt payload on 0x11/0x12 and `RECEIPT_IDS_PER_ENVELOPE` (default 32, 1–64) |
| [006](CR-006.md) | **Accepted** | After WP-05 | Mutual-compatibility visibility in discovery (gender + seeking set, checked in-session, yes/no only) and rules-first offline free-text intent matching |
| [007](CR-007.md) | **Accepted** | After WP-05, with CR-006 | Encounter-proven abuse reports, automatic quiet limits and temporary suspension (no automatic permanent ban), signed offline denylist, store-compliant minor protection |
| [008](CR-008.md) | Proposed | The chat-surface slice | The design language: Midnight Aurora token values in two themes, glass on navigation only, M3 Expressive on Android, safety one tap away; Daylight theme and the Sora font need a ruling |

A CR is applied in exactly one slice. Applying one that moves `constants_hash`
also regenerates every golden vector that embeds it and reruns the full
protocol-conformance suite in the same commit (TRS-CONST-003).
