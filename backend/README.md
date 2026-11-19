# /backend

Relay, registry, config, venue, billing and reports services (TRS Sec. 4.1).

Go; part of the repository-root Go module `github.com/dharabhishekorg/lumos`.

## Status

Created in WP-01 slice S1. **No services exist yet.** WP-01 scope is limited to
an empty shell that compiles clean plus a health check (slice S7); every other
endpoint is explicitly out of scope for WP-01 per EDP Sec. 7.

## Rules

- No plaintext profile, chat, media or key material in the backend, its logs,
  analytics, push payloads or crash reports — ever (TRS-BCK-002, TRS-OBS-002).
- The relay handles authenticated ciphertext envelopes only.
