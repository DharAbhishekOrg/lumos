<!--
  Lumos - evidence/wp05 / WP-05 closeout report.
  Author: dhar.abhishek.email@gmail.com
  Implements: TRS-AI-003, TRS-AI-005, TRS-CICD-007
  ADRs: ADR-001, ADR-002, ADR-008, ADR-012
  Threading: n/a
  Notes: Every number here is derived from evidence/trace/trace-matrix.json
  and the executed-test set of conformance run 45213450535 (merge 47dd491 on
  main) by tools/closeout. The machine twin is closeout-results.json beside
  this file; where the two disagree, the file wins.
-->

# WP-05 Closeout: Remote Text Backend, E2E Sessions and Push

**Baseline:** SRS v1.1 / TRS v1.1 / EDP v1.0 · **Scope:** the EDP §7 WP-05
brief's "Key requirements" row, transcribed in `tools/closeout` (with its own
test) · **Pipeline:** conformance run `45213450535` on merge `47dd491`, 2,242
executed tests (Go 414, Java 1,546, Swift 278, TS 4) · **Slices:**
S1–S9a, PRs #41–#66, plus this closeout.

---

## 1. Headline

**Of the 53 requirement IDs in WP-05's declared scope, 31 are
`tested`, 16 are `implemented`, and 6 are `not_started`.** Each of the
6 has a named destination in §6. **Every test linked to an in-scope row ran
in this pipeline run.** The tracecheck honesty gate passed against that same
executed set, so no row is marked `tested` unless its tests ran. 136
further matrix rows have moved since the matrix was seeded and sit outside
this scope; the ones WP-05 touched are named in §5.

WP-05 delivered the following:

- **A minimal backend (`lumosd`).** Device registration with a device-key
  proof and a revocable credential, and no phone or email. A prekey directory
  whose one-time keys are claimed atomically. An envelope relay with TTL
  deletion, quotas, rate limits, size limits, idempotency, and block and
  unmatch enforcement. Generic push wakes with FCM and APNs adapters ready
  for owner credentials. PostgreSQL holds the durable records and Redis the
  queues.
- **The end-to-end messaging path on Go, Java and Swift.** X3DH session
  establishment against fetched bundles, including an offline peer, and the
  Double Ratchet. The AMD-005 inner event envelope for edit, delete and
  reaction. The message id travels inside the ciphertext, so a relay cannot
  relabel messages. Receipts are sealed in the same ratchet. Four vector
  families are replayed byte for byte by all three languages.
- **Both apps' chat logic and wiring.** A transactional outbox, ratchet state
  that survives process death without reusing a key, the relay client, prekey
  publishing, receipts, the RELAY_ROUTE swap after a match, and unmatch at
  the relay. Android and iOS are level: iOS ported every Android scenario,
  and both boot the chat runtime from a hardware-backed key root (ADR-012).
- **WhatsApp-style ticks,** driven by one delivery machine.
- **docker-compose staging** (lumosd on scratch, PostgreSQL 16, Redis 7.2).
  Two suites run against it in CI on every change: the black-box integration
  suite and Android's real relay client.

**The EDP's three guardrails for this package are encoded, not just
intended:**

1. **"Delivered" never comes from the relay upload.** The delivery machine
   has no transition from an upload to delivered or read. Only the
   recipient's receipt reaches them, and a late receipt never moves a message
   backward. The machine is vector-pinned in three languages. End to end,
   `oneTickWhenTheRelayTakesItTwoOnlyFromTheDeliveredReceiptBlueOnlyFromTheRead`
   runs on both apps.
2. **A one-time prekey is never issued twice.** One `DELETE ... RETURNING`
   runs over a row locked with `FOR UPDATE SKIP LOCKED`. It is proven under
   concurrent claims against PostgreSQL in CI, and against the composed stack
   with 25 claimers racing for 10 keys.
3. **Rotating a push token never orphans a mailbox.** Mailboxes key on the
   device, not the token, and a rotated token replaces the route row
   (`TestARotatedPushTokenReplacesTheRouteAndKeepsTheDevice`).

**What WP-05 did not deliver:**

1. **No chat screen exists on either app.** The chat runs end to end, but no
   UI mounts it. The WP-04 closeout routed the request, match and chat
   screens to "the first UI-bearing slice of WP-05", and WP-05 had none. This
   is the largest gap the package leaves. SRS-RMT-005's UI test, SRS-RMT-010,
   SRS-RMT-012, SRS-RMT-013 and SRS-IDN-006 all wait on it (§6).
2. **The push client is not in the apps yet.** The backend sends generic
   wakes, but the apps cannot receive them until the FCM and APNs client
   dependencies are approved (§8).
3. **The physical runs are the owner's:** AT-006 on separate networks, AT-007
   on a locked screen, the cloud deploy, the TLS scan and the load test.

## 2. The brief's exit evidence and deliverables

| EDP §7 item | Where it stands |
| --- | --- |
| Backend integration green | `staging integration` runs in CI on every change. It covers the offline queue, TTL expiry, replay, duplicate, block, atomic prekeys, rate limit, local-only classes refused, revocation, and mail surviving a restart. |
| Fuzz (envelope parser) green | Three fuzz targets (the envelope, text-event, receipt and route decoders, and the HTTP send route) run their corpus in every CI run. For this closeout, each was also fuzzed for 2 minutes: 19.3 million executions, no crash and no internal error (§3). |
| Ratchet out-of-order/skip suite green | `ratchet_sequences.json` covers in-order, out-of-order, skipped-key and MAX_SKIP-negative cases. Go, Java (JVM and the API-30 emulator) and Swift replay it. |
| Airplane-mode queue/recovery demonstrated | As logic on both apps: everything waits in order offline and goes when the path returns. Against the real composed backend, an offline burst arrives in order across kills on both sides (Android). **On a device in airplane mode: the owner's (AT-007).** |
| Push payload inspection shows generic content | The FCM and APNs adapters are tested to send only the generic wake or alert, and errors never carry the token. |
| docker-compose staging of backend | `backend/deploy/staging/`. |
| Process-kill outbox recovery on both apps | Kills at every step between relay and store, on Android and iOS. |
| Log audit proving zero plaintext/keys in backend logs | `TestNothingTheRelayHandlesReachesTheLog` and `TestNoResponseCarriesPrivateOrStoredSecrets`. |

## 3. Regression and vector suite in this run

| Language | Tests executed |
| --- | ---: |
| Go | 414 |
| Java | 1,546 (four unit flavours, the emulator job, and conformance) |
| Swift | 278 |
| TypeScript | 4 |

The WP-01 to WP-04 regression is inside these totals. The suite is
cumulative and nothing was skipped. WP-05 added three vector families, bringing
the total to 20: `chat/delivery_states.json`, `chat/remote_text.json` and
`chat/receipts.json`. It also brought `crypto/ratchet_sequences.json` onto
Java and Swift, where it had been Go-only. Every family is replayed by Go,
Java and Swift from the committed file.

**Run outside CI for this closeout (agent-executed, on `47dd491`):**

| Fuzz target | Duration | Executions |
| --- | --- | ---: |
| `protocol/chat` `FuzzDecoders` | 2 min | 9,021,474 |
| `protocol/chat` `FuzzReceiptAndRouteDecoders` | 2 min | 9,012,880 |
| `backend/internal/api` `FuzzTheSendRouteNeverPanicsOrFailsInternally` | 2 min | 1,312,166 |

## 4. Status of every WP-05 requirement ID

| Requirement | Status | Linked tests | Executed this run |
| --- | --- | ---: | ---: |
| SRS-DAT-001 | `tested` | 6 | 6 |
| SRS-DAT-003 | `tested` | 5 | 5 |
| SRS-MAT-010 | `implemented` | 4 | 4 |
| SRS-NFR-PERF-006 | `not_started` | 0 | - |
| SRS-NFR-SEC-004 | `tested` | 14 | 14 |
| SRS-NFR-SEC-005 | `tested` | 15 | 15 |
| SRS-NFR-SEC-006 | `implemented` | 13 | 13 |
| SRS-NFR-SEC-007 | `tested` | 17 | 17 |
| SRS-NFR-SEC-008 | `not_started` | 0 | - |
| SRS-NFR-SEC-009 | `implemented` | 5 | 5 |
| SRS-RMT-001 | `implemented` | 18 | 18 |
| SRS-RMT-002 | `implemented` | 1 | 1 |
| SRS-RMT-003 | `implemented` | 16 | 16 |
| SRS-RMT-004 | `tested` | 13 | 13 |
| SRS-RMT-005 | `implemented` | 21 | 21 |
| SRS-RMT-006 | `implemented` | 2 | 2 |
| SRS-RMT-007 | `tested` | 28 | 28 |
| SRS-RMT-008 | `tested` | 5 | 5 |
| SRS-RMT-009 | `tested` | 34 | 34 |
| SRS-RMT-010 | `not_started` | 0 | - |
| SRS-RMT-011 | `tested` | 13 | 13 |
| SRS-RMT-012 | `not_started` | 0 | - |
| SRS-RMT-013 | `implemented` | 2 | 2 |
| SRS-RMT-014 | `implemented` | 11 | 11 |
| SRS-RMT-015 | `tested` | 5 | 5 |
| SRS-RMT-016 | `implemented` | 8 | 8 |
| SRS-RMT-017 | `tested` | 12 | 12 |
| SRS-RMT-018 | `implemented` | 4 | 4 |
| TRS-BCK-001 | `implemented` | 13 | 13 |
| TRS-BCK-002 | `tested` | 7 | 7 |
| TRS-BCK-003 | `tested` | 1 | 1 |
| TRS-BCK-004 | `tested` | 8 | 8 |
| TRS-BCK-005 | `tested` | 8 | 8 |
| TRS-BCK-006 | `tested` | 4 | 4 |
| TRS-BCK-007 | `tested` | 5 | 5 |
| TRS-BCK-008 | `implemented` | 10 | 10 |
| TRS-BCK-012 | `implemented` | 2 | 2 |
| TRS-CRY-001 | `tested` | 55 | 55 |
| TRS-CRY-002 | `tested` | 13 | 13 |
| TRS-CRY-003 | `tested` | 6 | 6 |
| TRS-CRY-004 | `implemented` | 6 | 6 |
| TRS-CRY-005 | `tested` | 12 | 12 |
| TRS-CRY-006 | `tested` | 7 | 7 |
| TRS-CRY-007 | `tested` | 8 | 8 |
| TRS-CRY-008 | `tested` | 4 | 4 |
| TRS-CRY-009 | `tested` | 5 | 5 |
| TRS-CRY-010 | `not_started` | 0 | - |
| TRS-CRY-011 | `tested` | 6 | 6 |
| TRS-CRY-012 | `tested` | 2 | 2 |
| TRS-CRY-013 | `tested` | 10 | 10 |
| TRS-CRY-014 | `tested` | 8 | 8 |
| TRS-CRY-015 | `tested` | 36 | 36 |
| TRS-CRY-016 | `not_started` | 0 | - |

### 4.1 Why the 16 `implemented` rows are not `tested`

Each is held back for a named reason. Two of them are WP-05's own unfinished
work, and are listed as that.

- **Waiting on the chat screen:**
  - SRS-RMT-005 (its verification is a UI test; the machine and the ticks
    are tested)
  - SRS-RMT-013 (a UX test)
  - SRS-RMT-014 (a settings-matrix test; the receipt policy is tested, but
    there is no settings screen and no typing indicator yet)
  - TRS-CRY-004 (a changed identity key is refused, never silently trusted:
    the session pins the identity from the match. The re-verification flow
    the user sees is SRS-IDN-006, not built)
- **Waiting on physical devices or the cloud (owner):**
  - SRS-RMT-001 (separate networks, AT-006)
  - SRS-RMT-003 (airplane mode on a device)
  - SRS-RMT-006 (locked screen, AT-007)
  - SRS-RMT-018 (radios off on a device)
  - SRS-RMT-002 (a traffic capture; the backend inspection half is the
    SRS-DAT-001 schema audit)
  - TRS-BCK-012 (a cloud security audit; the runbook gives the service
    account only `secretAccessor` on its two secrets)
- **Waiting on later packages:**
  - TRS-BCK-001 (venue, voucher, billing and report APIs: WP-08, WP-09,
    WP-10)
  - TRS-BCK-008 (venue, billing, campaign and safety records, same packages)
  - SRS-RMT-016 (unmatch stops delivery and is tested on both apps and
    against the real backend; *blocking* from the UI is SRS-SAF-001, WP-10)
  - SRS-MAT-010 (the remote half is now built; the report flow's "retain
    evidence" choice is CR-007 and WP-10)
- **Not finished in WP-05:**
  - **SRS-NFR-SEC-006.** Replay, MAX_SKIP and the skipped-key limits are
    tested, but the dedup cache's id cap (`DEDUP_CACHE` ids, 10,000) is not
    enforced on either app: only its 7-day TTL is.
  - **SRS-NFR-SEC-009.** The static rule and https-only/no-redirect are
    tested on both apps, but no test yet proves that an untrusted certificate
    is refused.

  Both go to the chat-surface slice (§6).

### 4.2 Why rows moved to `tested` in the closeout prep (S9a, PR #66)

Each graduation rests on the requirement's stated verification being what CI
runs. Where the stated method includes a review, the row is graded as
TRS-CRY-001 and TRS-CRY-002 already were: the vectors are CI's, and the
external review is G-07's.

| Requirement | Stated verification | What CI ran |
| --- | --- | --- |
| SRS-NFR-SEC-004 | Protocol review and test vectors | X3DH, ratchet and session vectors in three languages; DH turns; used keys gone |
| SRS-NFR-SEC-005 | Golden-vector test | AEAD known answers and tampers; the header is the AAD; tampered ratchet headers; relabelled messages and receipts |
| SRS-NFR-SEC-007 | Negative protocol suite | Low-order keys (new); bundle-signature, AEAD, frame and remote-text negatives |
| SRS-RMT-007 | Crypto design review and protocol test vectors | As SEC-004, plus MAX_SKIP and the skipped-key limits |
| TRS-BCK-002 | Code and log audit | The log and response audits over every relay handler |
| SRS-DAT-001 | Data-flow and backend schema audit | The new schema audit, the envelope record, local-only classes refused |
| TRS-CRY-012 | Static review | The new `crypto-hygiene` lint rule on every file of every change |

**The prep found one real defect:**

- **iOS accepted low-order X25519 keys.** `LumosCrypto.x25519` returned the
  all-zero secret for all seven low-order points. It now refuses them, as
  Java and Go already did. Both apps replay the same seven points, and the
  test is proven to fail without the guard.

**S8 found another:**

- **The relay shuffled a burst sent within one second.** It fetched by
  creation second, then by random message id. It now fetches in submission
  order, and a contract test pins this.

## 5. What the WP-04 closeout routed here

| Routed item | Outcome |
| --- | --- |
| SRS-IDN-006 (a non-alarming notice when a match's identity key changes) | **Not delivered.** The detection half exists: the session refuses an identity the match did not pin. The notice is UI. → the chat-surface slice. |
| The request, match and chat screens | **Not delivered** (§1). → the chat-surface slice. |
| The composition root that wires store, radio and sessions at process start | **Delivered on both apps:** `AppBoot` (S7e2b, S7f2b) from the ADR-012 key root. Onboarding's last step must write that key root. The onboarding screens are not built, so until they are, a real install boots with the relay off, as ADR-012 intends. |
| The iOS session coordinator (Swift InterestSession) | **Delivered** (S7f2a), with the Android scenarios. |
| SRS-MAT-010's remote half (unmatch stops remote delivery) | **Delivered** on both apps and against the real backend. |

Rows outside the brief's row that WP-05 moved, all reported as spillover:

- **TRS-PAT-004 and TRS-LOC-004/005:** the transactional outbox and
  restart-safe stores.
- **TRS-TRN-012:** the relay schedule.
- **TRS-TST-006:** the process-kill suites.
- **iOS links on rows from earlier packages,** where S7f's ports prove the
  same behaviour.

## 6. Not started or deferred, with destination

- **The chat-surface slice (proposed as the next slice, Android then iOS):**
  - the chat screen and its states (SRS-RMT-005's UI half);
  - reply, react, copy, local delete and timestamps (**SRS-RMT-010**; the
    edit, delete and reaction wire is already tested under SRS-RMT-011);
  - opt-in, ephemeral typing indicators (**SRS-RMT-012**; the TYPING class
    0x13 exists in the register, and the receipt policy already keeps read
    receipts separate from delivery);
  - the nearby-only versus remote distinction (SRS-RMT-013);
  - the re-verification notice (**SRS-IDN-006**);
  - the dedup id cap (SRS-NFR-SEC-006);
  - an untrusted-certificate test (SRS-NFR-SEC-009);
  - onboarding writing the ADR-012 key root, so a real install's boot turns the relay on.

  It goes ahead of CR-006 and CR-007, because CR-007's reports are filed from
  the chat and match screens.
- **SRS-NFR-PERF-006** (remote round trip under 500 ms at P75 and 2 s at
  P95) → after the owner's cloud deploy. The agent can then benchmark two
  stacks through the cloud relay, and the device figure is G-03's.
- **SRS-NFR-SEC-008** (TLS 1.3 client to backend) → **G-04**, the owner's
  TLS posture scan. Cloud Run terminates TLS. The apps refuse anything but
  https, except a literal loopback in development.
- **TRS-CRY-010** (signed key rotation with rollback protection) → **G-05,
  WP-08**.
- **TRS-CRY-016** (designed for a post-quantum upgrade) → an ADR before WP-11's
  G-07 review. The hooks are already there: the protocol version is bound
  into every X3DH transcript and every frame header, and the suite sits
  behind one seam per platform.

## 7. Findings for the G-07 cryptographic-review dossier

- **Low-order keys (fixed, §4.2).** Reviewers should confirm that every X25519
  agreement on a peer-supplied key goes through `LumosCrypto.x25519` or the
  Java CryptoBackend. The Secure Enclave KEK wrapping in `KekStore` agrees
  only with this device's own keys.
- **The relay sees metadata.** Routing ids, envelope sizes and timing are
  visible to it. Remote envelopes are not padded, so ciphertext length tracks
  message length. This is within SRS-RMT-002 ("routing identifiers, expiry
  and delivery metadata"), but padding to size classes is a cheap mitigation
  worth the review's opinion.
- **The relay stores hashes of secrets only.** It keeps the SHA-256 of each
  send capability and of each device credential, never the secret itself, so
  a database leak hands an attacker neither.
- **Receipts are sealed in the chat's ratchet.** A relay can neither forge a
  delivered or read state nor move a receipt between messages: the receipt
  vectors carry its relabelling attempts, and every one fails.
- **The message id is inside the ciphertext.** Duplicates and replays are
  refused at the relay (a reused id is a replay) and on the device (a message
  shows at most once). The device-side id cap is open (§4.1).

## 8. Owner gates and actions

All nine EDP §8 gates remain `unproven_owner`, and none was advanced. WP-05
readies two of them:

- **G-03 (push at scale, separate-network chat: AT-006 and AT-007).** This
  needs the apps' push clients first. FCM needs the owner's approval of the
  dependency (`com.google.firebase:firebase-messaging`) and of the Firebase
  terms. APNs needs the push capability on the owner's Apple developer
  account.
- **G-04 (cloud production posture).** `docs/ops/staging-deploy.md` is the
  runbook: Cloud Run, Cloud SQL, Memorystore, Secret Manager, a least-privilege
  service account, and a cost check against ₹2,000 a month. Once the owner
  sends the service URL, the agent points the Android staging build at it
  and runs the cross-stack suite through the cloud.

**Also the owner's, from WP-05:**

- the iOS app target's `Info.plist` must list the BGTask identifier
  `com.lumos.app.relay-sync`;
- the Keychain and Secure Enclave key root needs a check on a real iPhone;
- the load test (SRS-NFR-SCL-004).

## 9. What the next package inherits

- **The chat path, complete below the UI on both apps.** A screen can bind to
  ChatStore's conversation and message records and to the delivery machine's
  ticks without touching crypto or transport.
- **A backend with one schema** that is audited column by column against
  SRS-DAT-001, and a staging stack that CI exercises on every change.
- **The `crypto-hygiene` lint rule,** which keeps weak randomness and TLS
  bypasses out of every platform.
- **A CI pipeline that ran 2,242 tests,** with the honesty gate requiring
  every row marked `tested` to have its linked tests in the executed set.
