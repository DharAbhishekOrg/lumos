# /backend

Relay, registry, config, venue, billing and reports services (TRS Sec. 4.1).

Go; part of the repository-root Go module `github.com/dharabhishekorg/lumos`.

## Status

- WP-01: `cmd/lumosd`, an empty shell with a health check.
- WP-05 S4a: `internal/registry` (device registration by key proof, revocable
  hashed credentials, prekey directory with atomic one-time prekey claims)
  and `internal/api` (its HTTP routes). Storage is behind `registry.Repository`;
  only the in-memory implementation exists, so `lumosd` does not serve these
  routes yet. The PostgreSQL adapter (TRS-BCK-008) and the wiring are S4b.

## Rules

- No plaintext profile, chat, media or key material in the backend, its logs,
  analytics, push payloads or crash reports — ever (TRS-BCK-002, TRS-OBS-002).
- The relay handles authenticated ciphertext envelopes only.
