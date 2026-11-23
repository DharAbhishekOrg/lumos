<!--
  Lumos - docs/plans / WP-05 execution plan.
  Author: dhar.abhishek.email@gmail.com
  Implements: TRS-AI-001, TRS-AI-013 (planning discipline); scope per EDP Sec. 7 WP-05
  ADRs: ADR-001, ADR-002, ADR-008
  Threading: n/a
  Notes: Ordered slice checklist for WP-05 (remote text backend, E2E sessions
  and push), produced at kickoff on 2026-11-21 under the owner's standing
  delegation. Two owner rulings were taken at kickoff and are recorded in
  "Owner rulings" below; they are binding for every slice. The order puts
  the delivery-state machine first because every other slice reports into
  it, and the owner asked for the WhatsApp-style ticks explicitly.
-->

# WP-05 Execution Plan: Remote Text Backend, E2E Sessions and Push

**Baseline:** SRS v1.1 / TRS v1.1 / EDP v1.0 · **Kickoff:** 2026-11-21.
**Entry criteria:** WP-04 green (met: `evidence/wp04/`); crypto vectors green
on all platforms (met for x3dh_kat and aead_kat; ratchet_sequences is Go-only
today and S2/S3 close that); owner FCM/APNs projects and a staging cloud
environment (**not met, owner-held**). The parts of WP-05 that need those are
the push-provider adapters and the cloud staging deploy; both are planned as
"ready for owner execution", and nothing else waits on them. The docker-compose
staging the brief names runs locally and in CI.

## Owner rulings at kickoff (2026-11-21)

1. **Text chat keeps the internet fallback, as baselined.** Matched people chat
   over Bluetooth/Wi-Fi while near each other, and over the end-to-end
   encrypted relay after they leave range (SRS-RMT-001, SRS-TRN-004,
   SRS-TRN-010). Media and voice notes stay Bluetooth/Wi-Fi only and never
   touch the relay (SRS-TRN-009). The owner considered a Bluetooth/Wi-Fi-only
   text design and an opt-in relay, and chose the baseline.
2. **Chats show WhatsApp-style ticks.** The SRS-RMT-005 states render as: a
   clock while queued or sending, one grey tick when sent, two grey ticks when
   delivered, two blue ticks when read, a red mark when failed, and a nearby
   mark for content waiting for a Bluetooth/Wi-Fi path. Each glyph also has a
   spoken label, so the delivered/read difference never rests on colour alone.
   **Delivered and read come only from the recipient's receipt, never from the
   relay accepting the upload** (the EDP guardrail, enforced by the machine).

## Scope, from the EDP §7 brief's Key requirements row

SRS-RMT-001..018, SRS-MAT-010/016 hooks, TRS-BCK-001..008/012,
TRS-CRY-001..016, TRS §10, SRS-NFR-SEC-004..009, SRS-NFR-PERF-006,
SRS-DAT-001/003, AT-006/007/025 (relay half), AT-018 (relay leg).

## Slices

### S1: Delivery states and ticks
- Go reference `protocol/chat`: the SRS-RMT-005 delivery machine as a lookup
  table with an exhaustive pair test. Invariants: only a recipient receipt
  reaches delivered or read; receipts never downgrade (a late "delivered"
  after "read" stays read); the recipient's receipt is ground truth even
  after a local "failed".
- The tick mapping in the reference, so every platform draws the same glyph.
- Vector family `chat/delivery_states.json`, replayed by Go, Java and Swift.
- Android: tick drawables, colours and spoken labels; iOS: the same mapping and
  a SwiftUI view.
- Receipt policy: read receipts can be turned off without turning off
  delivery receipts (SRS-RMT-014).

### S2 and S3: Double Ratchet on Java and on Swift
- Ports of the Go ratchet, replaying `ratchet_sequences.json` (in-order,
  out-of-order, skipped-key and negative cases) on both apps (TRS-CRY-002,
  -006, -007; SRS-RMT-008).

### S4: Backend registry, auth and prekeys
- Device registration with a device-key-bound, revocable access credential and
  no phone or email (TRS-BCK-007).
- Prekey upload and fetch with **atomic one-time prekey consumption**, so one
  one-time prekey can never be issued twice (TRS-BCK-005, TRS-CRY-005).
- Storage behind a Repository: PostgreSQL in staging and CI, per TRS-BCK-008.

### S5: Envelope relay
- Upload, pull and ack, with TTL deletion, quotas, rate limits, size limits
  and idempotency (TRS-BCK-003/004, SRS-DAT-003, SRS-RMT-015).
- Block and unmatch enforcement at the relay (SRS-RMT-016, SRS-MAT-010's
  remote half).
- The envelope parser is fuzzed; a log audit proves there is zero plaintext
  and zero key material (TRS-BCK-002).

### S6: Push
- Generic wake payload only (TRS-BCK-006, SRS-RMT-006).
- A provider seam whose FCM/APNs adapters are **ready for owner execution**
  (credentials are owner-held).
- Rotating a push token never orphans a mailbox: mailboxes key on the device
  route, not the token.

### S7: Client chat path
Split on 2026-11-22 into three sub-slices, each merged on its own:

- **S7a, the remote-text wire (Go reference, Java, Swift).** The AMD-005 inner
  event envelope for edit, delete and reaction (SRS-RMT-011); the ratchet
  plaintext, which carries the message id inside the ciphertext so a relay
  cannot relabel messages; the relay envelope (PREKEY until the peer replies,
  then NORMAL); X3DH session establishment against fetched bundles, including
  an offline peer, as production code on both apps. Byte layouts are fixed
  under the TRS §19 AI delivery rule (TRS §10.1 names the fields, not their
  bytes) and frozen by `chat/remote_text.json`, which all three languages
  replay byte for byte.
- **S7b, Android chat store.** Schema v6: conversation, message, outbox,
  ratchet session, dedup and prekey secret; the transactional outbox insert
  (TRS §8.3, TRS-PAT-004); ratchet state that survives process death without
  reusing a deleted message key (SRS-RMT-017). Not in S7b: the iOS chat
  store (the Swift schema mirror is in, the store follows with the iOS chat
  path), the dedup id cap (the TTL is in), and simultaneous initiation by
  both peers.
- **S7c, Android relay client.** The registry and relay contract as a typed
  client over `HttpURLConnection` (no new dependency: a strict JSON codec for
  the fixed contract); the device's relay registration kept sealed in schema
  v7; signed and one-time prekey publishing and replenishment, the private
  halves stored before anything is uploaded; the outbox drained in order
  against the relay; the mailbox fetched, opened and acknowledged only after
  the open has committed. Process-kill recovery tests at every step between
  the relay and the store (SRS-RMT-003, SRS-RMT-017, SRS-RMT-004), against an
  in-memory relay with the S5 semantics; the real cross-stack run is S8's.
  A conversation's relay route (the peer's routing id, the capability to
  send to it and the capability this device issued) is an input here.
- **S7d, Android chat crypto on libsodium.** Found during S7c (2026-11-23):
  protocol-java's LumosCrypto builds X25519 and Ed25519 keys through
  XECPublicKeySpec, NamedParameterSpec and EdECPoint and agrees through JCA
  "XDH", all of which Android added at API 33. The app supports API 26, so on
  Android 8 to 12 the chat path (X3DH, ratchet, remote sessions) cannot run,
  and neither can nearby HELLO verification: PresenceTable verifies peers'
  Ed25519 signatures through the same JCA call, which fails closed, so every
  peer would be refused. Nothing reaches either from the UI yet, so no user is
  exposed. The fix is
  ADR-001's own answer and the one identity already took: a primitive seam in
  protocol-java, JCA behind it on the JVM, libsodium (already shipped as
  Lazysodium) behind it in the app. Proven on the CI emulator, which runs
  API 30, by replaying the chat vector families there; the lumoslint
  minsdk-api rule gains the API-33 key classes and algorithm names so the
  JCA path cannot creep back into shipped code.
- **S7e, route exchange and receipts.** How matched devices hand each other
  their relay route, and the delivery and read receipts that move the ticks
  past one (SRS-RMT-005, SRS-RMT-014). Owner rulings 2026-11-23 (CR-005): the
  route travels in a new post-match, nearby-only class RELAY_ROUTE (0x07);
  receipts keep the TRS classes 0x11/0x12, sealed in the chat's ratchet.
  Split in two:
  - **S7e1, the wire.** CR-005 applied: the class, RECEIPT_IDS_PER_ENVELOPE,
    the consent gate, the route and receipt codecs in Go, Java and Swift, and
    the chat/receipts.json family with a relay's relabelling moves.
  - **S7e2a, the app's logic.** Receipts sent on receive (sealed into the
    outbox in the receive's own transaction) and applied on arrival through
    the delivery machine; read receipts only when the user allows them
    (SRS-RMT-014); the outbox carries each item under its class; the
    RELAY_ROUTE offer and take behind the consent gate; unmatch revokes the
    peer's capability at the relay (SRS-RMT-016). All JVM-tested.
  - **S7e2b, the Android wiring.** The RELAY_ROUTE frame on the nearby
    session after a match; the INTERNET permission; the relay pass
    scheduled on connectivity and push wake; the composition that builds
    the relay parts for the running app.
- **S7f, iOS chat path.** The chat store and relay client on iOS.
  - **S7f1a, the iOS chat store.** Swift DoubleRatchet exportState /
    restoreState in the Java byte layout (proven byte-identical by a
    cross-language desk check); nested transactions in Sqlite3Database with
    the Android semantics; LocalStore.transaction and
    findEntriesByNaturalKey; ChatRecords, PrekeySecret, ChatSessions and
    ChatStore with receipts (CR-005), ported behaviour for behaviour, with
    the Android ChatStoreTest scenarios.
  - **S7f1b, the iOS relay client.** RelayClient, account, routes, prekey
    publishing, sessions, the relay pass and the route exchange, with the
    fake-relay and process-kill tests. The strict JSON subset is ported
    rather than using JSONSerialization (which accepts duplicate keys and
    fractions the Android port refuses). Transports throw
    TransportUnreachable for every network failure, the Swift counterpart of
    IOException, so a defect is never mistaken for OFFLINE.
  - **S7f2a, the iOS match session and runtime.** iOS had no
    InterestSession (a WP-04 parity gap the route swap depends on), so this
    slice ports it with NearbyRouteSwap, ChatIdentity, RelaySchedule and
    ChatRuntime, all platform-free and tested with the Android scenarios.
  - **S7f2b, the iOS platform wiring.** Reopening the Secure Enclave KEK,
    the Keychain key root (ADR-012's iOS half), the URLSession transport
    (tested against a real loopback HTTP server), the boot, and background
    relay passes (BGAppRefreshTask + NWPathMonitor carrying out
    RelaySchedule through RelayRunner). The app target that must list the
    task identifier in Info.plist is WP-03's.

**Owner ruling 2026-11-23 (order):** from S7e2b on, Android and iOS alternate
slice by slice and stay within one slice of each other: S7e2b (Android
wiring), then S7f1 (iOS chat logic: store, relay client, prekeys, receipts,
route swap), then S7f2 (iOS wiring), and so on for later work.

**Owner ruling 2026-11-23 (scope):** CR-006 (mutual-compatibility visibility,
offline intent matching) and CR-007 (encounter-proven reports, automatic
reversible enforcement, minor protection) are built **after** WP-05 closes,
together, alternating Android and iOS. Nothing in WP-05 implements them.

### S8: docker-compose staging and integration suite
- Offline queue, TTL expiry, replay rejection, duplicate envelope and block
  scenarios against the composed backend.
- **Done (2026-11-23):** `backend/deploy/staging/` (the lumosd image on scratch,
  digest-pinned; compose with PostgreSQL 16 and Redis 7.2 noeviction +
  append-only), `backend/integration` (the black-box Go suite above plus
  atomic prekey claims, rate limit, local-only classes, revocation, and mail
  surviving a restart), and `StagingCrossStackTest` (Android's real relay
  client against the stack). All run in the new `staging integration` CI
  check, gated by `conformance gate`. The suite found a real defect: the
  relay ordered a same-second burst by random message id, shuffling it for
  the recipient; both stores now fetch in submission order. The cloud deploy
  is owner-executed ([docs/ops/staging-deploy.md](../ops/staging-deploy.md)).
  Owner licence ruling 2026-11-23: scratch image plus the MPL-2.0 Mozilla CA
  bundle; upstream PostgreSQL/Redis images approved for local and CI only.
  Not yet covered: the iOS client against the stack (no Docker on the macOS
  runners); its relay logic is the Android port's, scenario for scenario.

### S9: Closeout
In two PRs, as for WP-04: the prep, then the report derived by
`tools/closeout` from the executed-test set of main's conformance run after the
prep merges (so the tests the prep adds are counted).

- **S9a, closeout prep.** WP-05's scope transcribed into `tools/closeout`
  with its own test. An audit of every in-scope row found three gaps, closed
  here:
  - **iOS accepted low-order X25519 keys.** `LumosCrypto.x25519` returned the
    all-zero secret for all seven low-order points instead of refusing them
    (shown on swift-crypto by the new test failing without the guard). It now
    refuses them, as Java's CryptoBackend and Go's crypto/ecdh already did;
    both apps replay the same seven points, directly and as a ratchet key.
  - **No static guard for TRS-CRY-012 / SRS-NFR-SEC-009.** A new lumoslint
    rule refuses non-CSPRNG generators and TLS trust or hostname bypasses in
    shipped Go, Java, Swift and TypeScript. The repository is clean under it.
  - **No schema audit for SRS-DAT-001.** A test lists every column of the
    migrated database with its type, so a new column fails until it is
    checked against "no user content centrally".

  The iOS chat-path tests S7f ported were running in CI but were never linked
  in the matrix; each is now linked to the rows its Java original proves (88
  links on 33 rows, every one in main's executed set).
- **S9b, the closeout report.** `evidence/wp05/closeout.md` and its machine
  twin.

## Owner-executed, never simulated

- AT-006 on separate physical networks.
- AT-007 with the screen locked, on devices.
- Production cloud deploy.
- TLS posture scan (SRS-NFR-SEC-008).
- Load test (SRS-NFR-SCL-004).
- Push at scale, and FCM/APNs credentials.

## Known traps (from the brief)

- Never derive "delivered" from the relay upload; derive it only from the
  recipient's ACK.
- Prekey consumption must be atomic, so no one-time prekey is ever issued
  twice.
- Push token rotation must not orphan mailboxes.
