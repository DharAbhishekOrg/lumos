# ADR-010 — Keystore authentication policy: no key-level auth gating; App Lock plus action-level device auth

- **Status:** **ACCEPTED** — decided by the owner 2026-08-14, resolving the open
  decision recorded in `docs/security/threat-model.md` §7.4 (threat LT-07).
  Raised by the implementing agent per TRS-AI-006 during the WP-02 S0 preflight.
- **Raised:** 2026-08-13 (threat model §7.4) · **Decided:** 2026-08-14
- **Affected requirements:** SRS-IDN-001, SRS-NFR-SEC-003, SRS-SAF-009, SRS-ONB-007, SRS-IDN-003, TRS-IOS-007, TRS-AND (keystore integration)
- **Affected artefacts:** `android-app/app/src/main/java/com/lumos/app/keystore/`, `ios-app/shell/Sources/LumosAppShell/KekStore.swift`, the App Lock feature (later WP-02 slice)

## Context

Android Keystore and the iOS Keychain/Secure Enclave can bind a key to device
authentication: every cryptographic operation with that key then requires
biometric or passcode presentation (`setUserAuthenticationRequired` /
`kSecAccessControl` with user presence). The threat model's LT-07 asked whether
Lumos's identity and ratchet keys should carry that binding.

The tension, as recorded in §7.4: per-operation authentication defends the
opportunistic and coercive physical adversaries (ADV-1, partially ADV-2), but it
breaks everything that must decrypt without a person present — background BLE
sessions in WP-03, message receipt while locked in WP-05. **The parameter is set
at key generation and cannot be changed afterwards; changing it later means a new
identity**, which is precisely the event ADR-003 makes deliberately expensive.
The documents did not settle it, so implementation stopped and asked
(AGENTS.md §6).

## Decision (owner ruling, 2026-08-14)

1. **Identity and ratchet keys are NOT auth-gated at the key level.** The KEK and
   the keys it wraps are usable by the app process without per-operation device
   authentication, because background decrypt requires exactly that.
2. **User-facing protection is layered above the keys instead:**
   - **App Lock** — an application-level lock (biometric/passcode via the
     platform prompt) gating the UI, not the key material. A later WP-02 slice
     delivers it.
   - **Action-level device authentication** for the three irreversible actions:
     **emergency wipe** (SRS-SAF-009's protection against accidental
     activation), **RecoveryRecord export** (SRS-ONB-007) and **identity reset**
     (SRS-IDN-003). Each of these presents the platform device-auth prompt at
     the moment of the action.
3. **StrongBox / Secure Enclave use is capability-probed**, never assumed: the
   KEK is created in the strongest available protection tier, the achieved tier
   is persisted, and everything that reasons about key protection reads the
   recorded fact (threat model LT-05).

## Consequences

- Background operation (WP-03 BLE, WP-05 push-triggered pull) works with the
  device locked, which SRS-NFR-REL and the transport requirements assume.
- The residual risk shifts exactly as the threat model's RR-1 states: malware
  running as the app can use the keys the app can use. Key-level auth gating
  would not have closed RR-1 either (an unlocked, in-use device authorises the
  operation for malware just as it does for the app); what is genuinely given up
  is protection of an **unlocked, unattended** device against a casual borrower
  — which App Lock addresses at the UI, where it does not break background
  decrypt.
- The three action-level prompts are load-bearing security controls, not UX
  polish. Their tests must assert the prompt path is unavoidable — no code path
  reaches wipe, export or reset without the authentication callback.
- Keys are generated **without** `setUserAuthenticationRequired` (Android) and
  **without** user-presence access control (iOS). This is deliberate and this
  ADR is the record of why; a reviewer meeting the bare flag should be pointed
  here. `setUnlockedDeviceRequired(true)` (Android P+) is still set where
  available — data at rest stays inaccessible on a locked, rebooted device
  without breaking unlocked background work.

## Alternatives considered

- **Key-level auth on everything:** rejected — breaks background decrypt
  categorically; WP-03/WP-05 cannot function.
- **Split key population (auth-gated identity, free ratchet keys):** rejected —
  X3DH responder flow uses the identity-DH key in the background on first
  contact, so the split protects nothing in practice and doubles the key
  management surface.
- **Auth-gated with authentication validity window** (e.g. 4-hour
  `setUserAuthenticationValidityDurationSeconds`): rejected — the window makes
  the guarantee illegible (protection depends on when the user last unlocked),
  still fails for long idle periods, and its expiry failure mode surfaces as
  missed messages that look like transport bugs.

## Verification

- WP-02 S1: key-generation code carries no auth-gating parameter, asserted by
  the negative tests that inspect the generation parameters; the capability
  probe records STRONGBOX / TEE / SOFTWARE_FALLBACK and tests assert the
  recorded tier drives the code paths.
- Later WP-02 slices: App Lock tests, and per-action auth tests for wipe,
  export and reset (the prompt path is unavoidable).
- Hardware StrongBox/Secure Enclave behaviour: **owner-executed on physical
  devices** (WP-02 kickoff, owner-executed row); CI records the software
  fallback only.
