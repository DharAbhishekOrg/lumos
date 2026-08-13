# Lumos Privacy Data Map — Phase 1 (WP-02 design input)

**Document:** `docs/privacy/data-map.md`
**Implements:** TRS-REP-008 (phase 1 of 3)
**Baseline:** SRS v1.1 / TRS v1.1 / EDP v1.0
**Scope of this revision:** personal data held **locally on the user's device** (Zone A)
after WP-02 — identity, profile, consent, safety state and the recovery export.
**Status:** seeded before WP-02 feature code exists. Living document.

---

## 0. Purpose and standing

SRS-NFR-PRI-001 requires the product to collect the minimum data necessary and to
**document purpose, retention and sharing for each field**. This document is that
record for the data WP-02 creates, shaped like a GDPR Article 30 processing record
so that the same table can be handed to a reviewer, a store privacy declaration
(SRS-NFR-PRI-010) and a privacy notice (SRS-NFR-PRI-009) without being rewritten
three times.

Two limits, stated up front:

- **This is not legal advice and is not legal sign-off.** Jurisdiction-specific
  legal review is **G-09**, owner-executed (SRS-NFR-COM-009). The
  lawful-basis column is the implementer's working position, recorded so that a
  lawyer has something specific to correct.
- **Scope is Zone A only.** WP-02 makes no backend call. The relay envelope, the
  prekey registry, push routing metadata and billing records are Zone B data and
  belong to the WP-05, WP-08 and WP-09 revisions of this document. They are listed
  in §6 as *not yet mapped* so their absence is visible rather than implied.

### 0.1 Roles

For locally held data the device is the storage location and no copy reaches
Lumos. The controller analysis for Zone B data — where a routing identity and an
opaque envelope do reach the backend — is **deferred to WP-05 and to G-09**. This
document does not assert a controller/processor position, because asserting one
without legal review would be exactly the kind of unearned claim AGENTS.md §3
forbids.

---

## 1. Data categories

| Class | Meaning | Handling rule |
| --- | --- | --- |
| **SC** | Special category (GDPR Art. 9): data revealing sexual orientation, and gender identity where it reveals or implies it | Encrypted at rest; never in analytics, logs, crash payloads, push payloads or BLE advertisements; explicit consent basis; user-controlled visibility per field |
| **P** | Ordinary personal data | Encrypted at rest; minimised; never in analytics |
| **K** | Cryptographic material | Hardware-backed or KEK-wrapped; never exported, never backed up, never logged |
| **O** | Operational, non-identifying | May be retained for function; still local |

**Sexual orientation is Article 9 special-category data, and this product's core
purpose is to collect it.** That is the single most consequential fact in this
document. It is why field-level visibility (SRS-PRO-003) is a P0 rather than a
nicety, why analytics exclusion (SRS-NFR-PRI-005) is written as a prohibition, and
why the block list (§2.4) is treated as a safety asset rather than a preference.

---

## 2. Inventory of locally held personal data (post-WP-02)

Storage locations: **DB** = encrypted Room/SQLite; **OBJ** = encrypted object
store; **KS** = platform keystore/keychain; **EXP** = user-chosen destination for
an export.

### 2.1 Identity and cryptographic material

| Field | Class | Purpose | Lawful basis (working position) | Store | Leaves device? | Retention |
| --- | --- | --- | --- | --- | --- | --- |
| Ed25519 identity key pair (private) | K | Root of trust; peer authentication | Contract — the service cannot function without an identity | KS, non-exportable | **Never** | Until identity reset or wipe |
| X25519 identity-DH key pair (private) (ADR-008) | K | X3DH session establishment | Contract | KS or KEK-wrapped | **Never** | Until identity reset or wipe |
| Public identity keys | P | Peer verification, safety number | Contract | DB | Yes — to peers, by design | Until reset or wipe |
| Signed prekey, one-time prekeys (private) | K | Asynchronous session establishment | Contract | DB (encrypted) | **Never** (public halves only) | Rotation policy — WP-05 |
| Double Ratchet session state, skipped-message keys | K | Forward secrecy per conversation | Contract | DB (encrypted) | **Never** | Until unmatch or wipe |
| Media object keys | K | Whole-object encryption | Contract | DB (encrypted) | Only to the consenting peer | With the object |
| DEK / KEK | K | Local store encryption | Contract | KS (KEK); DEK wrapped | **Never** | Until wipe |
| StrongBox / Secure Enclave probe result | O | Records the protection level actually obtained | Legitimate interest — security assurance | DB | No | Until wipe |

### 2.2 Profile (SRS-PRO-001/002)

| Field | Class | Purpose | Lawful basis (working position) | Store | Leaves device? | Retention |
| --- | --- | --- | --- | --- | --- | --- |
| Alias | P | Display to peers | Consent | DB | To peers, per field policy | Until edit/delete/wipe |
| Age or age band | P | Adult-only eligibility; matching | Consent + legal obligation (age gating) | DB | Per field policy | Until edit/delete/wipe |
| Pronouns | P | Respectful address | Consent | DB | Per field policy | Until edit/delete/wipe |
| **Gender identity** | **SC** | Matching and self-description | **Explicit consent** | DB | Per field policy | Until edit/delete/wipe |
| **Sexual orientation** | **SC** | Matching | **Explicit consent** | DB | Per field policy | Until edit/delete/wipe |
| Relationship intent | P | Matching | Consent | DB | Per field policy | Until edit/delete/wipe |
| Interests | P | Matching | Consent | DB | Per field policy | Until edit/delete/wipe |
| Short bio (free text) | **SC (assume)** | Self-description | **Explicit consent** | DB | Per field policy | Until edit/delete/wipe |
| Profile media (photos) | **SC (assume)** | Display to peers | **Explicit consent** | OBJ | Per field policy | Until edit/delete/wipe |
| Derived preview | **SC (assume)** | Privacy-preserving nearby display | Explicit consent | Derived, not stored twice | To nearby peers (WP-03) | Derived on demand |
| `ProfileFieldPolicy` | P | Per-field visibility control | Consent | DB | No | Until edit/wipe |

**Two rows are classified by assumption and it is deliberate.** A free-text bio
and a photograph may reveal orientation, gender identity, ethnicity, religion,
health or political opinion — the user decides, not the schema. Classifying them
as ordinary data would mean the strictest controls apply to the structured fields
and not to the two places users say the most. **Free text and images are handled
as special-category data.** This follows SRS §2.4: where the reading is
ambiguous, the stricter privacy interpretation applies.

**"Leaves device? Per field policy" is a WP-03/WP-04 behaviour, not a WP-02 one.**
After WP-02 nothing in this table has left the device at all. The column records
the *design intent* the field policy must be capable of enforcing, which is
precisely what makes it a design input.

### 2.3 Consent and compliance (SRS-ONB-001/002)

| Field | Class | Purpose | Lawful basis (working position) | Store | Leaves device? | Retention |
| --- | --- | --- | --- | --- | --- | --- |
| Age-gate affirmation + timestamp | P | Adult-only eligibility evidence | Legal obligation | DB | No | Until wipe |
| Accepted Terms / Privacy / Community Rules versions | P | Consent evidence | Legal obligation | DB | No | Until wipe |
| Local-radio disclosure acknowledgement | P | Informed consent to BLE use | Consent | DB | No | Until wipe |
| Permission grant state | O | Feature availability | Legitimate interest | Platform-owned | No | Platform-managed |

### 2.4 Safety and preferences

| Field | Class | Purpose | Lawful basis (working position) | Store | Leaves device? | Retention |
| --- | --- | --- | --- | --- | --- | --- |
| Block list | **P (safety-critical)** | Prevent contact | Legitimate interest — user safety | DB | No | Until removed or wipe |
| Report drafts, evidence exports | **SC (assume)** | Moderation reporting | Legitimate interest / consent on submission | DB, OBJ | Only on user submission (WP-04+) | Until submitted or wipe |
| Visible Nearby state and duration | O | Discovery control | Consent | DB | No | Until changed or wipe |
| Notification-privacy preference | O | Lock-screen exposure control | Consent | DB | No | Until changed or wipe |
| Profile pause / hide state | O | Visibility control | Consent | DB | No | Until changed or wipe |

**The block list is a disclosure risk in its own right.** It records who the user
chose to avoid; to the intimate-partner adversary (ADV-2 in the threat model) that
is often the most interesting data on the device. It is not a preference and is
not exempt from encryption because it holds "only identifiers".

### 2.5 The recovery export (ADR-003, SRS-ONB-007)

| Field | Class | Purpose | Lawful basis | Store | Leaves device? | Retention |
| --- | --- | --- | --- | --- | --- | --- |
| RecoveryRecord — profile content, preferences, block list, match roster metadata, local conversation history | **SC (assume)** | User-initiated content recovery | Consent — explicit, per export | **EXP** | **Yes — to wherever the user puts it** | Until the user deletes it |
| RecoveryRecord — private keys, prekeys, session state | — | — | — | — | **Never present. ADR-003 forbids it.** | — |
| Recovery identifier | P | Locating the user's own record | Consent | DB / EXP | Not exposed to nearby or matched users | With the record |

The second row is written down precisely because it is empty. ADR-003 makes the
RecoveryRecord content-only; restore creates a **new** cryptographic identity and
peers see the standard re-verification notice. Anyone reading this map later
looking for "where are the keys backed up" should find the answer stated rather
than have to infer it from an absence.

---

## 3. Data-subject rights

| Right | How it is served | Requirement | Available after WP-02 |
| --- | --- | --- | --- |
| Access | Inspect all locally held personal data | SRS-NFR-PRI-011 | Profile surfaces; a complete inspection view is WP-10 |
| Portability | Local export in a documented, user-protected form | SRS-DAT-004, SRS-ONB-007 | Yes — RecoveryRecord export |
| Rectification | Profile CRUD | SRS-PRO-001 | Yes |
| Erasure | Permanent local deletion; emergency wipe | SRS-PRO-011, SRS-SAF-009/010 | Yes |
| Restriction | Profile pause / temporary hide | SRS-PRO-011 | Yes |
| Objection | Feature-level controls; optional analytics off | SRS-NFR-PRI-006 | Partly — no analytics exists yet |
| Withdraw consent | Field visibility, hide, delete, wipe | SRS-PRO-003, SRS-PRO-011 | Yes |

**Erasure is unusually strong here and that is a design property worth keeping.**
Because Zone B holds no profile or chat plaintext (SRS-NFR-PRI-003), local
deletion is not a request to a server — it is the erasure itself. Anything that
later weakens that (a cached profile in Zone B, a "helpful" cloud sync) turns a
one-step right into a multi-party workflow and must be treated as a change to this
document, not an implementation detail.

**Erasure verification:** SRS-NFR-PRI-012 requires automated storage scans for
prohibited remnants where technically possible. Threat-model item LT-14 sets the
order — key material first, then content — so that an interrupted wipe leaves
ciphertext rather than plaintext.

---

## 4. Prohibitions

Restating these as prohibitions rather than as intentions, because that is how
they will need to be tested.

| # | Prohibition | Requirement |
| --- | --- | --- |
| PR-1 | No plaintext profile, chat, media or keys in the backend, logs, analytics, push payloads or crash reports — ever | TRS-BCK-002, TRS-OBS-002, SRS-NFR-PRI-003 |
| PR-2 | BLE advertisements carry no photo, alias, gender, orientation, phone number, exact location or persistent store-account identifier | SRS-NFR-PRI-002 |
| PR-3 | Analytics exclude message content, exact location, sexual orientation, gender identity and the social graph | SRS-NFR-PRI-005 |
| PR-4 | Local-only media and voice notes never touch the internet relay | SRS-TRN-009 |
| PR-5 | Peer identifiers are derived from cryptographic material — never MAC address, phone number or store-account identifier | SRS-IDN-002 |
| PR-6 | OS backups exclude all cryptographic state | SRS-DAT-012, ADR-003 |
| PR-7 | EXIF location, device and timestamp metadata are removed from images | SRS-PRO-005, SRS-NFR-PRI-008 |
| PR-8 | No private key is exportable through any application API | SRS-NFR-SEC-003 |

**PR-7 has a WP-02 implementation consequence** that is easy to get backwards:
strip and re-encode **before storing**, not before sending. Storing the original
and sanitising on the way out means the unsanitised original sits on disk, inside
a backup-excluded store but still recoverable by ADV-3 — and it means every future
sharing path has to remember to sanitise.

---

## 5. Retention

| Data | Retention | Trigger for deletion |
| --- | --- | --- |
| Profile content and media | Until the user edits, deletes, or wipes | User action |
| Cryptographic identity | Until identity reset or wipe | User action |
| Session state | Until unmatch or wipe | User action (WP-04) |
| Consent record | Until wipe | Wipe only — it is the evidence that consent was given |
| Block list | Until removed by the user or wipe | User action |
| RecoveryRecord export | Outside our control | User deletes the file |
| Nearby presence cache | Presence aging (WP-03) | Automatic |

There is **no server-side retention period for user content in WP-02**, because
there is no server-side user content. Relay envelope TTL, push route retention and
abuse-prevention windows are Zone B and belong to the WP-05 revision.

---

## 6. Not yet mapped

Listed so their absence is a stated gap rather than an oversight.

| Data | Zone | Destination work package |
| --- | --- | --- |
| Device routing identity, auth key, push route | B | WP-05 |
| Prekey bundle (public) as held by the registry | B | WP-05 |
| Relay envelope metadata: sender/recipient route, class, timestamps, TTL | B | WP-05 |
| Push notification wake hints | B | WP-05 |
| Venue activation, room membership, voucher redemption | B | WP-08 |
| Billing and entitlement records | Store providers | WP-09 |
| Crash analytics, if any is ever integrated | B / third party | WP-11 |
| Partner and advertiser aggregate reports | B | WP-08 |

---

## 7. Open questions for the owner

Each of these is a decision this document cannot make. They are recorded rather
than guessed, per AGENTS.md §6.

1. **Lawful basis for the special-category fields.** The working position is
   explicit consent (GDPR Art. 9(2)(a)). Confirmation is part of **G-09**.
2. **Controller/processor position for Zone B** routing metadata — deferred to
   WP-05 and G-09 (§0.1).
3. **Launch jurisdictions.** Retention and lawful-basis analysis differs by
   market; SRS-NFR-COM-011 refers to "launch markets" without naming them.
4. **Minimum age**, described in SRS-ONB-001 as "the configured minimum age" —
   configured to what, and does it vary by jurisdiction?
5. **Free text and images as special-category data** (§2.2). This document takes
   the stricter reading under SRS §2.4. A narrower position is possible but is an
   owner and legal call, not an implementer's.

---

## 8. Maintenance

Per the owner's TRS-REP-008 assignment, this document lives per work package: a
package that changes what data exists, where it is stored, or where it goes
updates it in the same pull request, and stamps the TRS-REP-008 trace-matrix entry
in the same commit. The final documentation audit is WP-11.

The companion document is
[`docs/security/threat-model.md`](../security/threat-model.md), which models the
attacks this classification exists to resist.
