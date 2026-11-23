# ADR-012: App boot order and where the wrapped data root lives

- **Status:** Accepted (agent, under the owner's standing delegation; WP-05 S7e2b, 2026-11-23; iOS half added in S7f2b, 2026-11-23)
- **Relates to:** ADR-003 (identity keys hardware-backed, never escrowed), ADR-010 (no key-level auth gating), TRS-LOC-002, TRS-AND-007, SRS-DAT-002

## Context

The local store is sealed under keys derived from a data root (`DataKeyHierarchy`), and the root is wrapped under the hardware KEK. Onboarding creates both, but until WP-05 nothing persisted the wrapped root or reopened anything at launch. Every WP-02..WP-05 component was proven in tests, and none ran in the app. The relay needs a running store and identity, so the boot has to exist now.

## Decision

1. **The wrapped root lives in one file outside the database**, `lumos-key-root.bin` in the no-backup directory (`Context.getNoBackupFilesDir()`). It can't live in the database, because the database key derives from it. The file is not secret (the root is wrapped under the KEK and is noise without it), but it is useless on another device. Keeping it out of backups means a restore to a new phone is a fresh install, as ADR-003 intends.
2. **The file also records the KEK's protection tier** (StrongBox, TEE or software fallback), which `AndroidKekStore.open` must be told. Layout: `version u8 = 1 | tier u8 | root_len u16 | wrapped_root`. Writes are atomic (temporary file, fsync, rename).
3. **One app KEK alias**, `lumos.kek.v1`. Onboarding creates the KEK under it and writes the key root file when it finishes.
4. **Boot order:** key root file, then KEK, then data root, then database key, then Room store, then identity record, then the unlocked chat identity, then the runtime. Any missing step means "not onboarded on this install", and the boot returns nothing rather than inventing state.
5. **The identity's private halves are unwrapped once per process** for the chat's lifetime and zeroed when it stops. ADR-010 already rules out key-level authentication gating, because background decryption needs them.
6. **Boot runs on the relay's single background executor**, never on the main thread, and at most once per process.

## Consequences

- Losing the KEK (factory reset, keystore wipe) makes the root unreadable. The install is then not onboarded and must start over. That matches ADR-003: identities are never escrowed, and content comes back only through the RecoveryRecord.
- Writing the key root file is the last step of onboarding. Until the onboarding screens call it (they are not built yet), the boot finds no file and the relay stays off. That is correct, not a gap in this decision.
- iOS needs the same decision in its own terms (a Keychain item). That is recorded below (WP-05 S7f2b).

## iOS (WP-05 S7f2b)

1. **The KEK and the wrapped root live in ONE generic-password Keychain item**, account `lumos.key-root.v1`, written through `KeychainBlobStore` with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`. ThisDeviceOnly keeps it out of backups, iCloud Keychain and device migration, so a restore to a new phone is a fresh install (ADR-003). AfterFirstUnlock, not WhenUnlocked, because background decrypt runs while the device is locked (ADR-010). There is no file: the Keychain is iOS's place for exactly this.
2. **The KEK must be reopened, not re-created.** `SecureEnclaveKekStore` now reopens from the enclave key's encrypted `dataRepresentation`, which only this device's Secure Enclave can use, so storing it exports nothing. On a device or simulator without an enclave, the software fallback stores the raw KEK in the same item, and the tier records that. This is the honest cost of having no hardware, as on Android.
3. **Layout:** `version u8 = 1 | tier u8 (1 enclave, 2 software) | kek_len u16 | kek | root_len u16 | wrapped_root`. `KeyRoot.open` proves the KEK opens the root before returning. Any unreadable record is `KeyRoot.Unreadable`: corrupt, from a newer build, or a KEK the device no longer has.
4. **Boot order:** the same as Android: key root, KEK, data root, database key, SQLite store (excluded from backups at every boot, sidecars included), identity, unlocked chat identity, runtime. A missing step means "not onboarded"; an empty relay base URL means nearby only.
5. **Background:** a `BGAppRefreshTask` (`com.lumos.app.relay-sync`) when the app is suspended, and an `NWPathMonitor` kick when the network returns while it runs. Both carry out `RelaySchedule` through `RelayRunner` on the one relay queue. The app target must list the identifier under `BGTaskSchedulerPermittedIdentifiers` and register before launch finishes; it doesn't exist yet (WP-03), so this is recorded as an app-target requirement.
6. **Not provable off-device:** the Keychain protection class, its exclusion from backups, and enclave reopening across launches are device semantics. CI tests the record, the boot order and the software tier; the hardware behaviour is owner-verified on a device, as for the Android KEK tiers.
