# Lumos Threat Model — Phase 1 (WP-02 design input)

**Document:** `docs/security/threat-model.md`
**Implements:** TRS-REP-008 (phase 1 of 3)
**Baseline:** SRS v1.1 / TRS v1.1 / EDP v1.0
**Scope of this revision:** WP-02 — device-bound cryptographic identity, the local
profile, encrypted persistence, backup exclusion, emergency wipe and offline onboarding.
**Status:** living document. Seeded before WP-02 feature code (S0); §0.2 records
owner rulings received 2026-08-14, before the S1 key-hierarchy implementation.

### 0.2 Corrections and owner rulings (2026-08-14)

Recorded as dated corrections rather than silent edits, so the document's history
of what was believed when stays legible.

1. **Keys-first wipe order is CONFIRMED NORMATIVE — and it supersedes the
   ADR-003 phrasing.** This document's §4.2 (LT-14) and §7.3 proposed destroying
   key material first so that an interrupted wipe leaves ciphertext without a
   key; the owner confirmed the ordering as normative on crypto-erasure
   rationale. Where ADR-003's wording describes wipe as content deletion with
   key destruction alongside, **the keys-first ordering governs**: destruction
   of the key material *is* the erasure of everything it protects, and content
   clearing that follows is hygiene, not the mechanism. This is a correction of
   precedence recorded here per the owner's instruction, not an edit to
   ADR-003's text.
2. **§7.4 (keystore authentication policy) is RESOLVED** by
   [ADR-010](../adrs/ADR-010-keystore-authentication-policy.md), Accepted:
   identity/ratchet keys are not auth-gated at key level; user-facing protection
   is App Lock plus action-level device authentication for wipe, RecoveryRecord
   export and identity reset; StrongBox/Secure Enclave capability-probed.
3. **§7.5 (screenshot policy) is RESOLVED** by
   [ADR-011](../adrs/ADR-011-screenshot-policy.md), Accepted: recents snapshots
   always redacted on both platforms; screenshot detect-and-warn in chat where
   the platform permits; no global FLAG_SECURE, because SRS-SAF-004 evidence
   capture must remain possible.
4. **The data map's special-category classification is accepted pending G-09**
   (see `docs/privacy/data-map.md` §0/§7).

---

## 0. Why this exists before the code

TRS-REP-008 asks the repository to hold a threat model. The owner's 2026-08-12
assignment says *when*: seeded in WP-02 as a preflight, updated per work package,
audited in WP-11.

The timing is the whole point. A threat model written after a subsystem is built
is a description of that subsystem. Written before, it is a constraint on it —
and §7 below is the part that has to earn this document: a list of decisions WP-02
is no longer free to make. If a WP-02 slice contradicts §7, one of the two is
wrong and the disagreement has to be resolved rather than noticed later.

**This document does not constitute a security assessment.** It is the
implementer's model of the system. An independent penetration test and
cryptographic review are gate **G-07**, owner-executed, and no analysis here
substitutes for them (TRS-AI-009/010/014).

### 0.1 What later work packages must add

| Work package | Surface this document does not yet cover |
| --- | --- |
| WP-03 | BLE radio: advertisement linkability, GATT session hijack, proximity spoofing, EID correlation |
| WP-04 | Match semantics: consent, unmatch, block evasion, verification tokens |
| WP-05 | Zone B backend: relay, prekey registry, push metadata, routing-identity correlation |
| WP-06 | Mesh: relay abuse, TTL/hop forgery, store-and-forward quota exhaustion |
| WP-07 | Media: decode surface, resource exhaustion, chunk poisoning |
| WP-08 | Signed config: key handling, rollback, kill switches |
| WP-09 | Billing and entitlements |
| WP-11 | Full-system review, fuzz campaign, G-07 evidence |

---

## 1. Trust boundaries

Transcribed from TRS §3.1. WP-02 lives almost entirely inside Zone A.

| Boundary | Position | WP-02 relevance |
| --- | --- | --- |
| **Zone A — user device** | Trusted endpoint for profile/chat plaintext and private keys. **Device compromise remains an endpoint risk.** | The whole of WP-02 |
| Public radio / network | Untrusted. All sensitive payloads need E2E authentication and encryption. | Not reached — WP-02 is offline by construction |
| **Zone B — Lumos backend** | Trusted for routing, billing, operations. Designed **not** to receive dating/chat/media plaintext. | Only as a negative: nothing WP-02 stores may reach it |
| Zone C — owner operations | Highly privileged; MFA/RBAC; KMS/HSM signing. | Out of scope |
| Venue partner | Constrained tenant, no access to profile or chat content. | Out of scope |
| Store providers | Authoritative for storefront and platform account. | Out of scope |

The sentence that matters most is the one the TRS puts in its own words: *device
compromise remains an endpoint risk*. Zone A is trusted, not invulnerable. The
model below is largely about what remains true when that trust is partially
violated.

---

## 2. Assets

| # | Asset | Where it lives after WP-02 | Loss means |
| --- | --- | --- | --- |
| A1 | Ed25519 long-term identity private key | Hardware-backed keystore, non-exportable (ADR-003) | Impersonation of the user to every peer; the root of trust is gone |
| A2 | X25519 identity-DH private key (ADR-008) | Same | Session establishment can be impersonated; Ed25519 still gates it |
| A3 | Signed prekeys and one-time prekeys | Encrypted local store | Session establishment forgery within the prekey's validity |
| A4 | Double Ratchet session state, skipped-message keys | Encrypted local store | Historical and future message decryption for that peer |
| A5 | Media object keys | Encrypted local store | Decryption of stored media objects |
| A6 | Local profile content, including special-category fields | Encrypted DB + object store | Exposure of sexual orientation, gender identity, alias, photos — see the data map |
| A7 | Block list, report drafts, match roster metadata | Encrypted DB | Reveals who the user avoided and why; a safety asset, not merely a privacy one |
| A8 | Database / object-store encryption key (DEK) | Wrapped by a hardware-protected KEK | Everything from A3 to A7 at once |
| A9 | Consent record: accepted document versions, age-gate affirmation | Local store | Compliance evidence (SRS-ONB-001/002) |
| A10 | RecoveryRecord export (content-only, passphrase-protected) | User-chosen destination — **outside the app's control** | A6 and A7, at whatever strength the passphrase has |

A10 is the only asset in this table that WP-02 deliberately lets leave the
device, and the only one whose protection depends on a human choice.

---

## 3. Adversaries

| ID | Adversary | Capability assumed | In scope for WP-02 |
| --- | --- | --- | --- |
| ADV-1 | Opportunistic device finder | Physical possession, unlocked or lock-screen access, no tooling | Yes |
| ADV-2 | Intimate/coercive adversary | Physical access, may know or compel the passcode, may have installed something earlier, **is a plausible peer** | Yes — the highest-priority adversary for a dating product |
| ADV-3 | Forensic extraction | Physical possession, commercial extraction tooling, may hold the device for a long time | Yes |
| ADV-4 | Malicious app on the same device | No root, sandboxed, sees what the OS lets it see: shared storage, backups, clipboards, screenshots, notifications | Yes |
| ADV-5 | Rooted / jailbroken device with active malware | Reads process memory, hooks APIs, can invoke keystore operations as the app | Partly — see §5 |
| ADV-6 | Cloud-backup adversary | Holds the platform account, not the device | Yes |
| ADV-7 | Network attacker | Full control of radio and network | Not reachable in WP-02; WP-03/05 |
| ADV-8 | Malicious or compromised backend | Zone B | Not reachable in WP-02; WP-05 |

**ADV-2 is the adversary this product exists in tension with.** A dating
application's user may be at risk from someone who has held their unlocked phone,
who is in their contacts, and whose interest in the block list and match roster is
specific rather than opportunistic. Mitigations aimed only at remote attackers
miss them entirely. Where a design choice trades ADV-2 resistance for
convenience, that trade is written down here rather than made silently.

---

## 4. Zone-A device-compromise surface

The enumeration WP-02 must design against. Severity is the implementer's
judgement, not a rating from an assessment.

### 4.1 Key material

| ID | Threat | Adversary | Severity | Mitigation | Requirement |
| --- | --- | --- | --- | --- | --- |
| LT-01 | Identity private key extracted from storage | ADV-3, ADV-5 | Critical | Key generated **in** the keystore and never in app memory; non-exportable; no API path returns it | SRS-IDN-001, SRS-NFR-SEC-003, ADR-003 |
| LT-02 | Key exported through a "backup" or "recovery" feature | ADV-6, and a future maintainer | Critical | ADR-003: RecoveryRecord is content-only. Restore creates a **new** identity and peers see the re-verification notice | SRS-ONB-007, SRS-IDN-008 |
| LT-03 | X25519 identity-DH key attacked as an easier path than Ed25519 | ADV-3, ADV-5 | High | ADR-008: Ed25519 remains the sole root of trust; signature verification **fails closed before any DH** | ADR-008 (both conditions test-pinned) |
| LT-04 | Weak or predictable key generation | — | Critical | Platform CSPRNG only; no application-supplied entropy | SRS-NFR-SEC-002 |
| LT-05 | Keystore present but not hardware-backed (emulator, older or degraded device) | ADV-3 | High | Probe StrongBox / Secure Enclave and **record the actual protection level**; never assume it | SRS-IDN-001 |
| LT-06 | Enclave cannot host X25519/Ed25519 natively; key sits wrapped at rest | ADV-3, ADV-5 | High | KEK wrapping per TRS-IOS-007; the KEK is hardware-protected even where the key it wraps is not | TRS-IOS-007, ADR-003 |
| LT-07 | Key usable without user presence after device theft | ADV-1, ADV-2 | Medium | Keystore authentication policy is a WP-02 design decision — see §7.4 | SRS-NFR-SEC-003 |

**LT-05 deserves emphasis.** A StrongBox probe that fails soft is worse than no
probe: the app then reports hardware protection it does not have. The probe result
is a persisted fact about the installation, and anything that reasons about key
protection must read that fact rather than assume the best case. Physical
StrongBox and Secure Enclave behaviour is **owner-executed evidence** — emulators
diverge, and the WP-02 kickoff says so.

### 4.2 Local data at rest

| ID | Threat | Adversary | Severity | Mitigation | Requirement |
| --- | --- | --- | --- | --- | --- |
| LT-10 | Database file read directly from storage | ADV-3, ADV-4 (rooted), ADV-5 | Critical | Application-layer encryption **in addition to** platform data protection; DEK wrapped by the hardware KEK | SRS-DAT-002, TRS-AND-004 |
| LT-11 | Media objects readable as ordinary files | ADV-3, ADV-4 | High | Whole-object encryption with a per-object key; no plaintext object ever written to disk | SRS-PRO-004 |
| LT-12 | Sensitive values in `SharedPreferences` / `UserDefaults` | ADV-4 | High | **Nothing sensitive there, ever** — the WP-02 kickoff names this trap explicitly | WP-02 brief |
| LT-13 | Plaintext left in temp files, caches, or crash dumps | ADV-3, ADV-4 | High | Sanitise/EXIF/re-encode pipeline works on encrypted or ephemeral storage; sensitive data excluded from logs, screenshots, backups and crash payloads | SRS-NFR-SEC-013 |
| LT-14 | Deleted rows recoverable from database free pages or flash | ADV-3 | Medium | Wipe deletes key material first, so residue is ciphertext without a key; automated remnant scan where technically possible | SRS-NFR-PRI-012, SRS-SAF-010 |
| LT-15 | Migration writes a plaintext intermediate table | ADV-3 | High | Forward-only migrations operate inside the encryption boundary; no decrypt-transform-re-encrypt through disk | TRS-AND-004 |
| LT-16 | Schema or column names leak content (`orientation_gay`-style enums) | ADV-3, ADV-4 | Medium | Special-category values are encrypted payloads, not schema identifiers | SRS-NFR-PRI-001 |

**LT-14 is why deletion order is a security property.** SRS-SAF-010 requires
emergency wipe to clear profile, conversations, cached objects, queued packets,
venue tokens and cryptographic state. If content is deleted first and keys last, a
wipe interrupted in the middle leaves recoverable plaintext. **Destroy the key
material first**: every byte that survives is then ciphertext nobody can open. The
ordering is stated here so WP-02 implements it deliberately rather than in
whatever order the entities happen to be listed.

### 4.3 Data leaving the device without being sent

| ID | Threat | Adversary | Severity | Mitigation | Requirement |
| --- | --- | --- | --- | --- | --- |
| LT-20 | OS backup carries the database or key-adjacent files to the cloud | ADV-6 | Critical | Android auto-backup disabled for DB, object store and key-adjacent files; iOS Keychain items `ThisDeviceOnly`, sensitive files do-not-back-up | SRS-DAT-012, ADR-003 |
| LT-21 | Device-to-device transfer migrates app data to new hardware | ADV-6 | High | Same exclusions; `ThisDeviceOnly` is what makes this true rather than an intention |
| LT-22 | Screenshots, task-switcher thumbnails, screen recording | ADV-1, ADV-2, ADV-4 | Medium | Screenshot policy is a WP-02 design decision — see §7.5 | SRS-NFR-SEC-013 |
| LT-23 | Notification previews on the lock screen | ADV-1, ADV-2 | High | Not reachable in WP-02 (no messaging yet), but the **local notification-privacy setting is a profile-scoped preference** and belongs in the WP-02 data model | SRS-NFR-PRI-006 |
| LT-24 | Crash reporter uploads memory containing profile content | ADV-8 | High | No crash SDK is integrated in WP-02; when one is, the exclusion is a precondition of integrating it | TRS-OBS-002, SRS-NFR-SEC-013 |
| LT-25 | RecoveryRecord export written to shared storage or a cloud drive | ADV-2, ADV-4, ADV-6 | High | Content-only by ADR-003, passphrase-protected; **the destination is the user's choice and outside our control** — the UI must say so | SRS-ONB-007 |

### 4.4 Onboarding, consent and the age gate

**Status after WP-02 slice S4c: LT-30 to LT-34 are implemented and tested on the
JVM, with one device-level caveat recorded in Sec. 7.6.** LT-30's mitigation is a
closed-by-default surface gate rather than a list of blocked screens, so a screen
added in a later work package is blocked until somebody deliberately opens it.
LT-32 (permission timing) remains WP-03's, because there is no permission to
request yet.

| ID | Threat | Adversary | Severity | Mitigation | Requirement |
| --- | --- | --- | --- | --- | --- |
| LT-30 | Profile surface reachable before the age gate | — | High | Age gate before **any** profile surface; the kickoff names this a trap | SRS-ONB-001 |
| LT-31 | Consent record forged or missing, so accepted versions are unknown | ADV-2 | Medium | Accepted document versions recorded locally, inside the encrypted store | SRS-ONB-002 |
| LT-32 | Permission requested at launch rather than at feature use, training users to grant blindly | — | Medium | Permissions requested only when the related feature is first invoked | SRS-ONB-004 |
| LT-33 | Visible Nearby enabled by installation, update, reboot or permission grant | — | High | Never auto-enabled; a WP-03 behaviour whose **default belongs in WP-02's persisted state** | SRS-ONB-008 |
| LT-34 | Onboarding requires a phone number or social login, creating an off-device identifier | ADV-8 | Medium | Offline no-account onboarding | SRS-ONB-006 |

### 4.5 Profile content

| ID | Threat | Adversary | Severity | Mitigation | Requirement |
| --- | --- | --- | --- | --- | --- |
| LT-40 | EXIF in profile media reveals home location and device | ADV-2, any peer | High | Strip EXIF and re-encode **before storing**, not before sending | SRS-PRO-005, SRS-NFR-PRI-008 |
| LT-41 | Malformed image exploits the decoder | ADV-4, later any peer | High | Validate type, dimensions, size and decode safety before storing or rendering | SRS-PRO-006 |
| LT-42 | Preview derived inconsistently from the full profile, leaking a field the user hid | any peer | High | Preview is **derived**, never a second hand-maintained copy — the kickoff names this trap | SRS-PRO-007 |
| LT-43 | Field-level visibility policy not enforced at the point of sharing | any peer | High | `ProfileFieldPolicy` is consulted by the derivation, not by each call site | SRS-PRO-003 |
| LT-44 | Special-category fields (orientation, gender identity) treated as ordinary strings | ADV-3, ADV-4 | High | Classified in the data map; encrypted; excluded from analytics by construction | SRS-NFR-PRI-005 |

**LT-42 and LT-43 are the same defect in two places**, and they are the WP-02
instance of the self-agreement failure recorded in the WP-01 closeout §5.2. Two
representations of one truth diverge. One derivation function, consulted
everywhere, is the structural answer; a test that asserts a hidden field is absent
from the preview for **every** field in the policy — rather than for the fields
someone remembered — is what keeps it true.

---

## 5. What is explicitly not mitigated

Stating these plainly is more useful than a mitigation column that overstates.

| ID | Residual risk | Why it stays |
| --- | --- | --- |
| RR-1 | **Rooted or jailbroken device with active malware (ADV-5)** | Malware running as or above the app can invoke keystore operations the app is entitled to and read plaintext from process memory. Non-exportability protects the key *bytes*, not their *use*. No client-side mitigation changes this; integrity attestation raises cost without closing it. |
| RR-2 | **Coercion (ADV-2)** | A user compelled to unlock the device and open the app defeats every at-rest control. Emergency wipe is the only lever, and it needs the user to reach it. |
| RR-3 | **Cold-boot and memory-scraping on the plaintext working set** | Profile plaintext must exist in memory to be shown. Minimising lifetime helps; eliminating it is not possible. |
| RR-4 | **Backup exclusion is enforced by the platform, not by us** | `ThisDeviceOnly` and backup rules are OS promises. If a platform honours them incorrectly, the data leaves. Verifiable only by testing a real backup/restore — owner-executed on hardware. |
| RR-5 | **RecoveryRecord passphrase strength** | A user-chosen passphrase on a file the user places wherever they like. The KDF cost is ours to choose; the passphrase is not. |
| RR-6 | **Forensic extraction of an unlocked, running device (ADV-3)** | Extraction against a live device with the app's keys usable is not defended by at-rest encryption. |
| RR-7 | **The implementer's own cryptographic judgement** | Every conclusion in this document was reached by the party that wrote the code. That is exactly the conflict G-07 exists to resolve, and ADR-008 and ADR-009 already carry flags for it. |

---

## 6. Assumptions this model rests on

If any of these turns out to be false, the analysis above needs redoing rather
than patching.

1. The platform keystore/keychain behaves as documented on the shipping device.
   **Unverified** — emulators diverge; owner-executed on hardware.
2. The OS enforces app-sandbox boundaries against ADV-4.
3. `ThisDeviceOnly` and Android backup rules are honoured (see RR-4).
4. The device is not already compromised at first run, when the identity key is
   generated. First-run compromise defeats everything downstream.
5. libsodium-equivalent primitives are correctly implemented on each platform —
   the golden-vector suite tests agreement between our three implementations, not
   the primitives themselves.
6. The user's device passcode is not known to ADV-2. Frequently false in the
   intimate-partner case, which is why RR-2 is stated rather than mitigated.

---

## 7. Constraints on WP-02 design

**This is the section that makes writing this document early worth anything.** A
WP-02 slice that contradicts an item here has to resolve the contradiction — by
changing the design or by amending this document with a reason — rather than
proceeding past it.

### 7.1 Key handling
- Identity keys are generated **inside** the keystore. No code path exists that
  can return private key bytes; the absence is proven by a negative test, not by
  reading the code.
- The StrongBox / Secure Enclave probe result is **persisted** and read by anything
  that reasons about key protection. No caller assumes hardware backing (LT-05).
- Where the enclave cannot host the algorithm, the key is wrapped at rest by a
  hardware-protected KEK and is never written unwrapped (LT-06, TRS-IOS-007).

### 7.2 Persistence
- One DEK for the database and object store, wrapped by the KEK. Encryption is
  application-layer **in addition to** platform data protection (LT-10).
- Migrations run inside the encryption boundary. A migration that writes a
  plaintext intermediate is a defect, not a performance choice (LT-15).
- No sensitive value reaches `SharedPreferences` or `UserDefaults` — including
  "just a flag" values that reveal state by existing.
- Special-category values are encrypted payloads. Schema identifiers carry no
  content (LT-16).

### 7.3 Wipe
- **Key material is destroyed first**, then content (LT-14). The ordering is
  normative — **owner-confirmed 2026-08-14 on crypto-erasure rationale, and it
  supersedes the ADR-003 phrasing** (§0.2, correction 1). Its test asserts the
  order, not just the end state.
- Wipe covers the documented platform limit and says so; SRS-SAF-010 names the
  set. What cannot be reached is named in the UI rather than implied to be gone.

### 7.4 Keystore authentication policy (LT-07) - DECIDED
Resolved by [ADR-010](../adrs/ADR-010-keystore-authentication-policy.md)
(Accepted 2026-08-14). Identity and ratchet keys carry **no key-level auth
gating** — background decrypt requires it; user-facing protection is **App Lock
plus action-level device authentication** for emergency wipe, RecoveryRecord
export and identity reset; StrongBox/Secure Enclave use is capability-probed
and the achieved tier persisted. The original tension — per-operation auth
versus WP-03/WP-05 background operation, with the parameter fixed at key
generation — is preserved in the ADR's context section.

### 7.5 Screenshot policy (LT-22) — DECIDED
Resolved by [ADR-011](../adrs/ADR-011-screenshot-policy.md) (Accepted
2026-08-14). Recents/app-switcher snapshots are **always redacted on both
platforms** (the snapshot is OS-written at-rest data); screenshot
**detect-and-warn in chat** where the platform permits; **no global
FLAG_SECURE**, because evidence capture per SRS-SAF-004 must remain possible —
a capture block that suppresses evidence of harassment protects the harasser.
The accessibility and no-overclaiming constraints are recorded in the ADR.

### 7.6 Identity generation does not run on Android (found in S4c)

Recorded here because it is a control this document assumes and a WP-02 slice
proved is not yet available on the platform.

`IdentityKeyGenerator.generate()` uses the platform JCA. Slice S1 reported that
this "works on JDK 17 and on API 33+", leaving only physical API 26-32 devices
affected. **Slice S4c ran it on an emulator for the first time and that is not
what happens.** Measured on API 34:

| | API 30 | API 34 |
| --- | --- | --- |
| `KeyPairGenerator.getInstance("XDH")` | absent | resolves |
| `Signature.getInstance("Ed25519")` | absent | resolves |
| `KeyPairGenerator.getInstance("Ed25519")` | absent | **absent** |

XDH and Ed25519 *signing* arrived with API 33, exactly as S1 said. Ed25519 key
*generation* never arrived. The boundary is therefore not an API level - it is
Android. What that means:

- Identity creation (SRS-ONB-006), identity reset (SRS-IDN-003) and the restore
  half of recovery (SRS-ONB-007) are **JVM-proven and device-unproven**. The
  export half of recovery is proven on a device, on the shipping libsodium
  binding, because it needs no key generation.
- The answer is ADR-001's own: libsodium, already in the tree as Lazysodium for
  the RecoveryRecord. The owner ruling of **2026-08-14 parks it until WP-03
  device work** rather than taking it inside WP-02, and S4c respects that.
- Nothing SHIPS in WP-02, so no user is exposed by the interval.

`PlatformKeyAlgorithmInstrumentedTest` asserts the algorithms are ABSENT, so the
day a platform update makes them available the emulator job fails and says so.

### 7.7 Owner-executed, not ours to close
- **G-07** — external penetration test and cryptographic review. ADR-008 and
  ADR-009 already carry flags for it.
- **Physical StrongBox / Secure Enclave behaviour** on real hardware; the WP-02
  brief lists it as owner-executed.
- **Backup/restore verification** on a real platform account (RR-4).
- **G-09** — legal, jurisdictional and policy clearances, including anything in
  the data map's lawful-basis column.

---

## 8. Maintenance

Per the owner's TRS-REP-008 assignment, this document lives per work package.
A work package that changes the attack surface updates it in the same pull
request that changes the surface, and the trace-matrix entry for TRS-REP-008 is
stamped in the same commit. The final documentation audit is WP-11.

The companion document is [`docs/privacy/data-map.md`](../privacy/data-map.md),
which classifies the data this model protects.
