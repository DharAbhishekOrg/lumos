# /backend

Relay, registry, config, venue, billing and reports services (TRS Sec. 4.1).

Go; part of the repository-root Go module `github.com/dharabhishekorg/lumos`.

## Status

- WP-01: `cmd/lumosd`, an empty shell with a health check.
- WP-05 S4a: `internal/registry` (device registration by key proof, revocable
  hashed credentials, prekey directory with atomic one-time prekey claims)
  and `internal/api` (its HTTP routes). Storage is behind `registry.Repository`.
- WP-05 S4b: the PostgreSQL implementation (`registry.PostgresRepository`,
  forward-only schema in `registry.Migrate`), held to the same contract suite
  as the memory store. `lumosd` serves the registry routes **only** when
  `LUMOS_DATABASE_URL` is set (from the secret manager when deployed); without
  it, it serves the health check alone.

### Running the PostgreSQL tests locally

    export LUMOS_TEST_POSTGRES_DSN="host=localhost port=5432 user=postgres dbname=postgres sslmode=disable"
    go test ./backend/...

Without the variable the PostgreSQL half skips; CI sets
`LUMOS_REQUIRE_POSTGRES=1`, which turns that skip into a failure.

## Rules

- No plaintext profile, chat, media or key material in the backend, its logs,
  analytics, push payloads or crash reports — ever (TRS-BCK-002, TRS-OBS-002).
- The relay handles authenticated ciphertext envelopes only.
