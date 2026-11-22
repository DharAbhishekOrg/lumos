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
- WP-05 S5: `internal/relay` (store-and-forward mailbox for ciphertext
  envelopes: 30-day expiry, delete on acknowledgement, per-mailbox quota,
  idempotent retries, replay refusal, per-peer send capabilities so a blocked
  sender is told nothing) and its HTTP routes in `internal/api/relay.go`.
  Only relay-eligible message classes are accepted; local-only classes are
  refused (SRS-TRN-009). Storage is behind `relay.Store`; only the in-memory
  store exists.
- WP-05 S5b: the relay on the deployed stores. Send capabilities live in
  PostgreSQL (`relay.PostgresCapabilities`, schema v2) because losing them
  would silently drop every sender; envelopes live in Redis
  (`relay.RedisMailboxes`, one atomic Lua script per operation, payload keys
  expired by Redis itself). Both run the same contract suite as the memory
  store. `lumosd` serves the relay when `LUMOS_REDIS_URL` is set as well as
  `LUMOS_DATABASE_URL`; a Redis URL without a database is refused at
  startup. The Redis deployment must use the `noeviction` policy.
- CR-004: the per-device send rate limit (`RELAY_SEND_RATE_PER_MIN`, 120
  a minute by default) on memory and Redis. Over the limit the relay answers
  `429` with `Retry-After` and `ERR_RELAY_RATE_LIMITED`, before any
  capability lookup, so the answer reveals nothing about blocks.
- WP-05 S6: push. A device registers one push token (`PUT /v1/push/route`,
  FCM or APNs), stored with the device so a rotated token never orphans its
  mailbox; `DELETE /v1/devices/self` revokes a device and drops its route
  (SRS-IDN-008). When the relay stores a NEW envelope it sends the
  recipient one generic wake: no sender, content or count (TRS-BCK-006).
  `internal/push` holds the FCM adapter (HTTP v1, authenticated by the
  runtime's service account through the metadata server, so no key file
  exists) and the APNs adapter (HTTP/2, ES256 provider token from the
  owner's `.p8` key mounted from Secret Manager), both tested against fake
  provider servers. Real delivery is owner-executed: see
  `docs/ops/environments.md`.

### Running the PostgreSQL and Redis tests locally

    export LUMOS_TEST_POSTGRES_DSN="host=localhost port=5432 user=postgres dbname=postgres sslmode=disable"
    export LUMOS_TEST_REDIS_ADDR="localhost:6379"
    go test ./backend/...

Without a variable its server half skips; CI sets `LUMOS_REQUIRE_POSTGRES=1`
and `LUMOS_REQUIRE_REDIS=1`, which turn those skips into failures.

## Rules

- No plaintext profile, chat, media or key material in the backend, its logs,
  analytics, push payloads or crash reports — ever (TRS-BCK-002, TRS-OBS-002).
- The relay handles authenticated ciphertext envelopes only.
