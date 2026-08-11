# /config-schema

Signed configuration package and voucher JSON/CBOR schemas (TRS Sec. 4.1).

This is the `schemas` root required by TRS-REP-001.

## Status

Created in WP-01 slice S1. **No schemas exist yet.** The config and voucher
envelope schemas are delivered in WP-01 slice S3; min/max validation derived
mechanically from the constants register lands with them (ADR-004). The signing
and activation workflow itself is WP-08.

## Rules

- Signed configuration may override only constants marked overridable in TRS
  Sec. 23, and only within their stated bounds; out-of-range packages fail
  schema validation client-side (TRS-CONST-002).
- Schemas are versioned and compatibility-tested before merge (TRS-CICD-005).
- Configuration never carries executable content (TRS-ARCH-006).
