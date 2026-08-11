# /admin-console

Owner and constrained-partner portal (TRS Sec. 4.1).

TypeScript, ESLint + `tsc` strict.

## Status

Created in WP-01 slice S1. **No console code exists yet.** WP-01 scope is an
empty shell that compiles clean (slice S7). The admin workflow itself is WP-08
per EDP Sec. 7.

## Rules

- Consumes shared schemas from `/config-schema`, never mobile executable code
  (TRS Sec. 4.3).
- Configuration may only alter behavior that is already implemented; it must
  never carry executable scripts or arbitrary HTML (TRS-ARCH-006).
