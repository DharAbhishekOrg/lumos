<!--
  Lumos - evidence/wp02 / WP-02 closeout record.
  Author: dhar.abhishek.email@gmail.com
  Implements: TRS-AI-004, TRS-AI-005, TRS-REP-002, TRS-CICD-007
  ADRs: ADR-003, ADR-008, ADR-010, ADR-011
  Threading: n/a
  Notes: A frozen record of one moment, like WP-01's. Every number here is
  computed into evidence/wp02/closeout-results.json by tools/closeout and quoted
  from there; none is typed by hand. The scope is EDP Sec. 7's WP-02 brief,
  transcribed, not derived from what moved - a scope read back from the work
  done can never report the work as incomplete, and seventeen of the forty-five
  are not_started.
-->

# WP-02 Closeout — Local identity, profile, encrypted persistence, onboarding, recovery and wipe

**Work package:** WP-02 · **Baseline:** SRS v1.1 / TRS v1.1 / EDP v1.0
**Machine-readable twin:** [`closeout-results.json`](closeout-results.json)
**Regression run:** conformance `32275262712`, pipeline `32275266312` — both
`workflow_dispatch` on `wp02/s5-close`, which is the form that forces the
path-filtered Swift and emulator jobs to run regardless of what the closing
change happened to touch.

---

## 1. Headline

**22 of the 45 requirement IDs in WP-02's declared scope are `tested`, 6 are
`implemented`, and 17 are `not_started`.** Every one of the 17 has a named
destination in §6. Nothing was upgraded that did not execute in this run: all
**1,224** executed tests come from the regression above, and **every single
linked test on every in-scope row ran in it** — the "executed this run" column
in §3 equals the "linked tests" column on all 28 rows that have tests.

WP-02 delivered the device-bound identity, the inclusive profile with
field-level visibility and derived preview, encrypted persistence with
forward-only migrations and backup exclusions, the media sanitiser, the
content-only RecoveryRecord across three languages, offline no-account
onboarding behind an adult gate, legal-version consent, and emergency wipe with
deliberate confirmation and a normative keys-first order.

**Three things it did not deliver, stated here rather than in a footnote:**

1. **Identity generation does not run on Android.** Not at any API level. §5.1.
2. **The permissions trio (SRS-ONB-003/004/005) is untouched** — there are no
   permissions to request until BLE arrives in WP-03.
3. **SRS-NFR-PRI-012's post-wipe remnant scan, named in this work package's own
   exit-evidence clause, was not built.** §6.3.

---

## 2. Regression and vector suite — this run

### 2.1 What executed

| Language | Tests executed | Where |
| --- | ---: | --- |
| Go | 264 | ubuntu-latest |
| Java | 825 | ubuntu-latest (4 unit variants + emulator) |
| Swift | 131 | macos-latest |
| TypeScript | 4 | ubuntu-latest |
| **Total** | **1,224** | conformance run `32275262712` |

The Java figure is four variants of the same 198-test Android suite
(`app-dev`, `app-qa`, `app-staging`, `app-prod`) plus 21 instrumented tests on
an API-30 emulator and 12 pure-JVM conformance tests. The four variants are
counted separately on purpose: Gradle compiles a separate `BuildConfig` per
flavour, so they are four programs, and collapsing them into one identifier
would let any single variant satisfy a TRS-REP-004 row.

Both workflows also ran as ordinary pull-request checks on the same commit, and
all 18 checks were green. The dispatch run is the one this record cites because
it is the one that cannot have skipped anything.

### 2.2 The vector suite

All **13** golden-vector files of TRS §18.4 regenerate deterministically and are
consumed identically by Go, Java and Swift at
`constants_hash sha256:5b73a3fd6a3d99e5e35ec5a93c857d16a6b8c1e4207823e331afeeac6b808d29`.
The hash moved twice in this work package — once for CR-001, once for the
`ADULT_AGE_MINIMUM` entry the age gate needed — and each time every vector was
regenerated and the full conformance suite rerun in the same commit, which is
what TRS-CONST-003 asks for.

`recovery_record.json` is the suite's newest member and is **not** prescribed by
§18.4; the completeness gate keeps the specification's transcription separate
from extensions so an addition cannot be smuggled into it.

---

## 3. Trace-matrix status of every WP-02 requirement ID

Scope is EDP §7's WP-02 brief, "Key requirements" row: SRS-ONB-001..008,
SRS-IDN-001..003/007, SRS-PRO-001..013, SRS-SAF-009/010, SRS-DAT-002/004/012,
TRS-LOC-001..010, TRS-AND-004, TRS-IOS-007, SRS-NFR-SEC-002/003/013. Forty-five
IDs.

| Requirement | Status | Linked tests | Executed this run |
| --- | --- | ---: | ---: |
| SRS-DAT-002 | `tested` | 7 | 7 |
| SRS-DAT-004 | `tested` | 48 | 48 |
| SRS-DAT-012 | `not_started` | 0 | - |
| SRS-IDN-001 | `implemented` | 8 | 8 |
| SRS-IDN-002 | `not_started` | 0 | - |
| SRS-IDN-003 | `implemented` | 12 | 12 |
| SRS-IDN-007 | `not_started` | 0 | - |
| SRS-NFR-SEC-002 | `not_started` | 0 | - |
| SRS-NFR-SEC-003 | `implemented` | 6 | 6 |
| SRS-NFR-SEC-013 | `not_started` | 0 | - |
| SRS-ONB-001 | `tested` | 25 | 25 |
| SRS-ONB-002 | `tested` | 28 | 28 |
| SRS-ONB-003 | `not_started` | 0 | - |
| SRS-ONB-004 | `not_started` | 0 | - |
| SRS-ONB-005 | `not_started` | 0 | - |
| SRS-ONB-006 | `implemented` | 8 | 8 |
| SRS-ONB-007 | `tested` | 48 | 48 |
| SRS-ONB-008 | `tested` | 11 | 11 |
| SRS-PRO-001 | `tested` | 4 | 4 |
| SRS-PRO-002 | `tested` | 4 | 4 |
| SRS-PRO-003 | `tested` | 5 | 5 |
| SRS-PRO-004 | `not_started` | 0 | - |
| SRS-PRO-005 | `tested` | 6 | 6 |
| SRS-PRO-006 | `tested` | 5 | 5 |
| SRS-PRO-007 | `tested` | 2 | 2 |
| SRS-PRO-008 | `tested` | 3 | 3 |
| SRS-PRO-009 | `tested` | 2 | 2 |
| SRS-PRO-010 | `tested` | 3 | 3 |
| SRS-PRO-011 | `tested` | 3 | 3 |
| SRS-PRO-012 | `not_started` | 0 | - |
| SRS-PRO-013 | `not_started` | 0 | - |
| SRS-SAF-009 | `tested` | 19 | 19 |
| SRS-SAF-010 | `implemented` | 17 | 17 |
| TRS-AND-004 | `tested` | 6 | 6 |
| TRS-IOS-007 | `implemented` | 4 | 4 |
| TRS-LOC-001 | `not_started` | 0 | - |
| TRS-LOC-002 | `tested` | 6 | 6 |
| TRS-LOC-003 | `tested` | 11 | 11 |
| TRS-LOC-004 | `not_started` | 0 | - |
| TRS-LOC-005 | `not_started` | 0 | - |
| TRS-LOC-006 | `not_started` | 0 | - |
| TRS-LOC-007 | `not_started` | 0 | - |
| TRS-LOC-008 | `not_started` | 0 | - |
| TRS-LOC-009 | `tested` | 7 | 7 |
| TRS-LOC-010 | `tested` | 3 | 3 |

**Fifty-six rows outside the declared scope carry a non-default status**, listed
in `closeout-results.json` under `out_of_scope_but_moved`; most are WP-01's and
this package only re-exercised them. **Fourteen actually changed during WP-02**,
and five of those were genuine advances rather than re-runs:

| ID | Was | Is | What moved it |
| --- | --- | --- | --- |
| TRS-CODE-004 | `not_started` | `tested` | The class budget and ADR-exception clause, S5 |
| TRS-CODE-006 | `not_started` | `tested` | The five-analyser stack completed |
| TRS-REP-004 | `implemented` | `tested` | Four-variant unit suites executing in CI |
| TRS-REP-008 | `not_started` | `implemented` | Threat model and privacy data map, the WP-02 preflight |
| SRS-NFR-LEG-007 | `not_started` | `implemented` | The global 18+ floor as a register constant |

The other nine (TRS-CICD-001/007, TRS-CRY-001/011/013/015, TRS-PROTO-015,
TRS-REP-003, TRS-TST-001) kept their status and gained code paths or tests.

### 3.1 Why the six `implemented` rows are not `tested`

Each has code and each has tests that ran. None is `tested`, and the reason is
never "we ran out of time".

| ID | Subject | Why not `tested` |
| --- | --- | --- |
| SRS-ONB-006 | Offline no-account onboarding creating a local identity | The flow is proven on the JVM, and **identity generation cannot run on Android at all** (§5.1). An onboarding mode that cannot create an identity on the platform it ships to is not tested, whatever the JVM says. |
| SRS-IDN-003 | Identity reset with clear warning | Same cause. The warning, the confirmation and the replacement are JVM-proven; the key generation the reset depends on is device-unproven. |
| SRS-IDN-001 | Device-bound identity, keystore-protected | Same cause, plus the half that was always owner-executed: StrongBox and Secure Enclave behaviour diverges on emulators, and the WP-02 brief assigns it to physical hardware. |
| SRS-SAF-010 | Wipe clears profile, conversations, cached objects, queued packets, venue tokens, crypto state | **Four of the six stores it names do not exist yet.** The wipe covers every store that does, keys-first, proven over the real store — but the requirement names a set, and the set is incomplete. WP-03 (outbox), WP-05 (messages, object cache), WP-07 (venue tokens). |
| TRS-IOS-007 | Keychain ThisDeviceOnly, Secure Enclave where the algorithm permits | Swift-side code exists and its tests ran on macOS. Secure Enclave behaviour is hardware, and macOS CI is not the hardware the requirement is about. Owner-executed on devices. |
| SRS-NFR-SEC-003 | Private keys protected by platform keystore, not exportable | The narrow-interface half is enforced by test — `KekStoreContractTest` pins the method set by reflection so a convenience accessor cannot be added. The platform-protection half is the same hardware question as SRS-IDN-001. |

---

## 4. Dispositions

### 4.1 ADRs

| ADR | Status | Disposition at WP-02 close |
| --- | --- | --- |
| **ADR-010** — no key-level auth gating; App Lock plus action-level device auth for wipe, export and reset; StrongBox/Secure Enclave capability-probed | **Accepted** (owner, 2026-08-14) | **Partially discharged, and the split is clean.** The capability probe is built and the achieved tier is recorded rather than assumed (`KeyProtection`, threat model LT-05). The *absence* of key-level gating is structural and holds. The three **action-level device-auth prompts are NOT built**: they belong to a presentation layer WP-02 does not have. `EmergencyWipe`, `IdentityReset` and `RecoverySetup` each take a confirmation token that cannot be constructed, which is the enforcement point the prompt will attach to. **App Lock is not built** — destination WP-03. |
| **ADR-011** — recents snapshots always redacted; detect-and-warn in chat where the platform permits; no global FLAG_SECURE | **Accepted** (owner, 2026-08-14) | **Not discharged, and not this package's to discharge.** Every clause is about UI surfaces and a chat that do not exist. Nothing in WP-02 contradicts it and nothing implements it. Destination: recents redaction WP-03 (first activity), chat detection WP-05. |

The seven WP-01 ADRs (005–009) and ADR-001..004 are unchanged and unchallenged
by this work package. No new ADR was raised in WP-02: the two decisions that
needed owner authority arrived as change requests instead, which is the right
instrument for changing something already frozen.

### 4.2 Change requests

| CR | Status | Disposition |
| --- | --- | --- |
| **CR-001** — `RECOVERY_ARGON2ID.parallelism` becomes a fixed 1; `parallelism_min`/`_max` removed | **Accepted 2026-08-19, EXECUTED in S4c** | The register entry, `constants_hash`, all 13 vector files and three implementations moved in one commit. The single-lane enforcement and its negative vectors are unchanged. The change came from a finding: libsodium's `crypto_pwhash` has no multi-lane entry point on either shipping platform, so a record declaring two lanes would open in the Go reference and nowhere a user could use it. |
| **CR-002** — TRS §5.3 toolchain Java 17 → Java 21 LTS, to adopt Lazysodium 5.2.0 | **Accepted 2026-08-19, EXECUTED in S5** | Toolchain at Temurin 21.0.12+8 across the Gradle toolchain, five CI pins and the manifest. Lazysodium at 5.2.0, sha256 recorded, notice re-versioned. **The `Aligned16KB` lint disable is deleted and the check passes.** Verified from the artefacts, not the release notes: every `PT_LOAD` segment of both 64-bit objects in 5.2.0 has `p_align 0x4000`; in 5.1.0 the same segments have `0x1000`. Two things it deliberately did not do, both deferred to WP-03 in §6.1: Checkstyle 13.x and the Android language level. |

---

## 5. Findings for the G-07 cryptographic-review dossier

Three findings from WP-02 that a reviewer should have in front of them. Each was
found by executing something rather than by reading it, and each changed the
design or the record.

### 5.1 Android cannot generate an Ed25519 key, at any API level

**Found in:** slice S4c, by running identity generation on an emulator for the
first time. **Measured** on API 30 and API 34:

| | API 30 | API 34 |
| --- | --- | --- |
| `KeyPairGenerator.getInstance("XDH")` | absent | resolves |
| `Signature.getInstance("Ed25519")` | absent | resolves |
| `KeyPairGenerator.getInstance("Ed25519")` | absent | **absent** |

Slice S1 had recorded the boundary as "works on JDK 17 and on API 33+, only
physical API 26–32 devices affected". Half of that held: XDH and Ed25519
*signing* did arrive at API 33. Ed25519 key *generation* never arrived. **The
boundary is not an API level; it is Android.**

**Impact.** `IdentityKeyGenerator.generate()` runs on the JDK and on no Android
runtime. Everything that creates an identity — onboarding (SRS-ONB-006),
identity reset (SRS-IDN-003) and the restore half of recovery (SRS-ONB-007) —
is JVM-proven and device-unproven. `verifyBinding()` works on API 33+, so the
ADR-008 verification path is fine; it is generation that is missing.

**Standing.** ADR-001's answer is libsodium, already in the tree as Lazysodium
for the RecoveryRecord. The owner ruling of 2026-08-14 parks the wiring for
WP-03 device work. `PlatformKeyAlgorithmInstrumentedTest` asserts the algorithms
are ABSENT, so the day a platform update makes them available the emulator job
fails and says so. Nothing ships in WP-02, so no user is exposed by the interval.

### 5.2 swift-sodium's password API traps on non-ASCII

**Found in:** slice S4b, reading the binding rather than trusting it.
`PWHash.hash` maps the passphrase bytes through `Int8(_:)`, which **traps** —
a crash, not a thrown error — on any byte above `0x7F`. Every user whose
passphrase contains an accent or an emoji would have crashed the app at the
moment they tried to protect their data.

**Impact.** The RecoveryRecord's passphrase is exactly where non-ASCII is most
likely and most legitimate.

**Standing.** Lumos takes only the package's `Clibsodium` product — the
libsodium XCFramework and its module map — and calls `crypto_pwhash` directly
through a non-trapping `withMemoryRebound`. The `non_ascii_passphrase` case is
in the cross-language derivation vector set so this cannot regress silently, and
the instrumented Android suite uses a non-ASCII passphrase for the same reason.
A reviewer should note that the mitigation is *not using the binding's own API*,
which is a standing constraint on anyone who later "simplifies" that call.

### 5.3 Parameter inflation, and the sign bit that makes it worse

**Found in:** slice S4b, while building the negative vectors.

A RecoveryRecord carries its own Argon2id work factors so a record written today
still opens after the defaults move. That means an attacker who can edit the
file controls what the opener will attempt to allocate. Two shapes:

- **Downgrade** — lower the work factor below the register minimum, then guess
  the passphrase cheaply. Refused before derivation; four `downgrade` vectors.
- **Inflation** — raise it absurdly, and the opener allocates 16 GiB or spins.
  Refused before derivation by absolute ceilings that sit far *above* the
  register's tuning maximum, so a later build's stronger record still opens;
  five `inflation` vectors.

**The sign bit.** Memory and time cost are unsigned 32-bit on the wire. A reader
holding them in a **signed** int sees `0xFFFFFFFF` as `-1` — below every ceiling
and above nothing, so every bound passes and the derivation is then handed a
nonsense parameter. `inflation_memory_uint32_max` and
`inflation_iterations_uint32_max` exist for that specific mistake.

**Standing.** All three implementations validate before deriving, and the
ordering is the property: validation after derivation would be a resource-
exhaustion hole with a passing test. Both inflation tests **hang rather than
fail** if that guard ever regresses, because libsodium will honestly try to
allocate what the header asked for; all three test files say so at the top, so a
timeout is read as the symptom it is.

---

## 6. UNPROVEN and deferred, with destination

### 6.1 Deferred by this work package, with the reason

| Item | Destination | Why |
| --- | --- | --- |
| Ed25519/X25519 key generation via a libsodium-backed provider | **WP-03** | Owner ruling 2026-08-14. §5.1. The dependency is already in the tree; the wiring is device work. |
| App Lock (ADR-010) | **WP-03** | Needs a UI. The action-level confirmation tokens it will gate already exist. |
| Action-level device-auth prompts for wipe, export and reset (ADR-010) | **WP-03** | Needs a presentation layer. The enforcement point is built and unforgeable. |
| Recents-snapshot redaction (ADR-011) | **WP-03** | Needs a first activity. |
| Screenshot detect-and-warn in chat (ADR-011) | **WP-05** | Needs a chat. |
| Checkstyle 13.x | **WP-03** | Java 21 removed the JVM obstacle that held it at 12.3.1, but CR-002 says raising it is a separate decision. The first slice to touch the analyser stack should take the new rules deliberately. |
| Android module compiling to Java 21 language level | **WP-03** | CR-002 needed a javac that can *read* bytecode 65, which is a property of the JDK. Raising what the app *emits* puts class file 65 through D8 and desugaring at minSdk 26 for no language feature anybody uses. |
| Go module path still `github.com/dharabhishekemail-ops/lumos` after the org move | **WP-03** | A mechanical rename touching every import; not this slice's business, and it changes no behaviour. |

### 6.2 In-scope requirements left `not_started`, with destination

| ID | Subject | Destination | Basis |
| --- | --- | --- | --- |
| SRS-ONB-003 | Plain-language explanation that discovery needs Bluetooth and remote chat needs internet | **WP-03** | There is no Bluetooth to explain yet. The copy pattern and its test exist. |
| SRS-ONB-004 | Permissions requested only at first feature use | **WP-03** | No permission is declared in the manifest yet, deliberately (threat model LT-32). |
| SRS-ONB-005 | Reduced feature set on denied permission, with the exact capability named | **WP-03** | Same. |
| SRS-IDN-002 | Peer identifiers derived from crypto material, never MAC/phone/store IDs | **WP-03** | The identity satisfies the *input* half; a peer identifier is not derived until there is a peer. |
| SRS-IDN-007 | Separate cryptographic contexts per match | **WP-04** | Needs matches. |
| SRS-PRO-004 | Profile records and media encrypted at rest, on-device unless shared | **WP-03** | The database half is done and `tested` under SRS-DAT-002; the **object store for media bytes does not exist** — only its metadata. Splitting the claim would be dishonest, so the row stays whole and unstarted. |
| SRS-PRO-012 | Respectful, non-objectifying copy; no hot-or-not voting | **WP-03** | A UI requirement. The onboarding copy already follows the rule and is lint-checked, but the surfaces it governs are not built. |
| SRS-PRO-013 | Broad proximity labels, never precise indoor distance | **WP-03** | Needs proximity. |
| SRS-DAT-012 | OS backups exclude all cryptographic state | **WP-03** | Android backup rules are written and `BackupExclusionTest` passes; the **iOS Keychain `ThisDeviceOnly`** half is device-verified, so the row cannot close from CI. |
| TRS-LOC-001 | Local schemas covering all sixteen entity domains | **WP-03/04/05/07** | Nine of sixteen exist. Claiming it at nine-sixteenths is the thing the honesty rule forbids. |
| TRS-LOC-004 | Outbox/media transfer state survives process death, idempotent | **WP-03** | No outbox. |
| TRS-LOC-005 | Dedup tables with bounded TTL and volume-suitable indexes | **WP-05** | No messages. |
| TRS-LOC-006 | Cache quotas, LRU/TTL cleanup, low-storage handling | **WP-05** | The low-storage guard exists in `LocalStore`; caches do not. |
| TRS-LOC-007 | Wipe deletes database, object files, key aliases and queued work in order | **WP-05** | The order is built and normative; two of the four things it names do not exist. Same shape as SRS-SAF-010. |
| TRS-LOC-008 | Support export through a redaction layer, never copying key material | **WP-09** | No support export. |
| SRS-NFR-SEC-002 | Keys generated with a CSPRNG | **WP-03** | Every generator uses the platform CSPRNG and it is asserted in code review, but "asserted by reading" is not a status. It closes when the libsodium provider lands and the generation path is device-executable. |
| SRS-NFR-SEC-013 | Sensitive data encrypted and excluded from logs, screenshots, backups, crash payloads | **WP-09** | Backups are done; logs, screenshots and crash payloads need the observability and UI layers. |

### 6.3 The exit-evidence gap this work package did not close

WP-02's brief lists, under **Agent exit evidence**: *"storage scans after wipe
show no remnants (SRS-NFR-PRI-012); AT-001/AT-002 executed on emulator + at
least documented manual script."*

- **SRS-NFR-PRI-012 — not built.** No automated post-wipe remnant scan exists.
  What exists instead is the property that makes a remnant survivable: the wipe
  destroys key material first, and `EmergencyWipeTest` asserts that rows
  surviving an interrupted wipe **cannot be opened**. That is a different claim
  from "no remnants", and it is the one that is proven. Destination **WP-03**.
- **AT-001 (adult gate and legal consent) — mechanism proven, scenario not.**
  The criterion is "fresh install blocks product entry"; there is no product
  entry to block because there is no activity. The gate, the closed-by-default
  surface enumeration and the consent recording are covered by the 53 distinct JVM tests linked to SRS-ONB-001 and
  SRS-ONB-002.
- **AT-002 (offline no-account onboarding) — cannot run on an emulator.**
  Blocked by §5.1: onboarding creates an identity, and identity creation does
  not run on Android.
- **AT-019 (emergency wipe) — JVM-proven, device-unproven.** It needs no key
  generation, so unlike AT-002 it *could* run on an emulator; it is not
  instrumented. Destination **WP-03**.

### 6.4 Owner-executed gates

All nine EDP §8 gates remain `unproven_owner`. No agent may advance one and none
was advanced. Two moved in substance without moving in status:

- **G-06 (store submission)** — the Lazysodium 16 KB alignment blocker is
  **cleared** by CR-002. The gate itself is a submission and stays owner-held.
- **G-09 (legal review)** — WP-02 added four documents the app ships and shows
  at onboarding (Terms of Use, Privacy Notice, Community Rules, local-radio
  disclosure). Their **text has not had the jurisdiction-specific legal review**
  SRS-NFR-COM-009 requires; each says so in its own body. The consent
  *mechanism* does not depend on the wording and is `tested`.
- **G-07 (cryptographic review)** — §5 is this work package's contribution to
  the dossier. ADR-008's two conditions and ADR-009's alphabet criteria carry
  forward from WP-01 unchanged.

### 6.5 The four facts carried into this closeout verbatim

Recorded at the end of the previous session as the state a boundary audit would
find, and reproduced here word for word with what became of each.

> **S5 never happened.** CR-002 (Java 17 → 21 / Lazysodium 5.2.0, G-06 blocker)
> is unexecuted and TRS-CODE-004 is unenforced despite the ruling that it lands
> by S5.

**Closed by this slice.** CR-002 is executed (§4.2) and TRS-CODE-004 is complete
and `tested`: the class budget and the ADR-exception clause are enforced by
`lumoslint -budgets` across all four languages, and complexity remains with the
per-language analysers that measure it properly.

> **`SRS-ONB-006` and `SRS-IDN-003` are `implemented`, not `tested`** — the
> Android Ed25519 key-generation gap, parked by your ruling for WP-03.

**Stands.** §3.1 and §5.1.

> **`SRS-SAF-010` is `implemented`** — four of the six stores it names don't
> exist yet.

**Stands.** §3.1 and §6.2 (TRS-LOC-007 is the same shape).

> **TRS-CICD-001/007 hold at `implemented`** by design: the ruleset half is
> unverifiable from CI, and now permanently so.

**Stands, and is now permanent by ruling.** The repository ruleset is owner-held
(2026-08-19). `tools/cicheck` holds the workflow half still — job names cannot
drift from `docs/ci/required-checks.md` — and the ruleset half is diffed by hand
against that register. Claiming `tested` would claim the half nothing here can
see.

---

## 7. What WP-03 inherits

- A device-bound two-key identity per ADR-008, KEK-wrapped, with a recorded
  protection tier — **and a generation path that does not run on the platform**
  (§5.1). This is the first thing WP-03 must fix; almost everything else in this
  list depends on it.
- Encrypted persistence at schema version 2 with a genuinely forward-only
  ladder, nine record kinds, blind-index lookup for the block list, backup
  exclusions, and Room behind the cross-platform seam.
- The inclusive profile, its per-field policy, the derived preview (derived,
  never a second copy) and a media sanitiser that strips EXIF before storing.
- The content-only RecoveryRecord, proven three ways, plus setup and restore
  flows whose bundle manifest names the sections it *could not* contain.
- Emergency wipe, keys-first, behind a confirmation token that cannot be
  constructed — and a coverage statement that names what it cannot reach.
- The first user-facing copy, in `strings.xml`, held to plain-language rules by
  a test that parses the file.
- A Java 21 LTS toolchain, Lazysodium 5.2.0 with 16 KB-aligned natives, and
  size budgets that are enforced rather than described.
- Nine required CI checks whose names cannot drift from their register.

**Entry criteria WP-03 needs that WP-02 does not supply:** two Android and two
iOS physical devices for the owner's evidence loop, and the BLE vectors green on
both platforms — the vectors exist and are green in CI, but on hardware they are
G-01.
