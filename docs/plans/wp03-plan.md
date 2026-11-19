<!--
  Lumos - docs/plans / WP-03 execution plan.
  Author: dhar.abhishek.email@gmail.com
  Implements: TRS-AI-001, TRS-AI-013 (planning discipline); scope per EDP Sec. 7 WP-03
  ADRs: ADR-001, ADR-002, ADR-008
  Threading: n/a
  Notes: Ordered slice checklist for WP-03 (BLE nearby presence, preview and
  Visible Nearby), produced at the owner's WP-03 kickoff and carrying the
  owner rulings of the WP-02 boundary audit (2026-08-19) and the resumption
  handover (2026-11-19). Each slice names its target graduations and separates
  agent-provable evidence from owner-executed gates. Scope boundaries: no
  interest/match semantics (WP-04), no chat, no media pipeline (WP-07), no
  mesh forwarding (WP-06).
-->

# WP-03 Execution Plan — BLE Nearby Presence, Preview and Visible Nearby

**Baseline:** SRS v1.1 / TRS v1.1 / EDP v1.0 · **Kickoff:** owner, 2026-08-19;
resumed under full delegation 2026-11-19. **Authoring host:** Claude cloud
container (toolchain manifest records the move). CI remains the sole prover;
the emulator job and the macOS job carry the halves this host cannot run.

## Slice order, and why this order

The §5.1 finding of the WP-02 closeout dictates it: identity generation does
not run on Android, and almost everything in WP-03 rides an authenticated
identity. So the libsodium provider comes first among feature slices; radio
work follows; UI mounts last on proven plumbing.

### S0 — Preflights (owner-ruled at the WP-02 boundary audit)
- Go module path → `github.com/dharabhishekorg/lumos` (mechanical; org move).
- SRS-NFR-PRI-012 post-wipe remnant scan: JVM + instrumented, decoy-proven
  scanner. **Graduates: SRS-NFR-PRI-012 → tested.**
- AT-019 instrumented on the emulator (real `AndroidKekStore` path).
- Checkstyle 12.3.1 → 13.11.0 (CR-002 removed the JVM obstacle; explicit
  config, zero violations, executed).
- Authoring-host migration records (toolchain manifest, host block) and the
  constantsgen harness made robust to JVM stderr banners.

### S1 — Libsodium identity provider (closes closeout §5.1)
- `IdentityKeyGenerator` backed by Lazysodium 5.2.0 (Android) and Clibsodium
  (iOS) behind the existing generator seam; ADR-008 two-key identity
  generated, signed, verified on device; tripwire test retired the day the
  platform disproves it, not before.
- AT-002 (offline no-account onboarding) executes on the emulator.
- **Graduates (on executed CI evidence): SRS-ONB-006, SRS-IDN-003,
  SRS-NFR-SEC-002; SRS-IDN-001 remains hardware-half owner-executed.**

### S2 — BLE adapter foundation, Android (ADR-002)
Delivered as two PRs for reviewability:
- **S2a (protocol):** extract the shared pure-JVM `:protocol-java` library
  (frame codec + JCA primitives out of `:protocol-conformance`, consumed by
  both the conformance suite and the APK) and land TRS-PROTO-010 fragmentation
  three ways — Go reference, Java port, Swift port — under a new scripted
  vector family (`test-vectors/framing/fragmentation.json`: byte-exact splits
  plus offer-by-offer timelines with an injected clock). TRS-PROTO-010
  graduates here: the vector evidence is the cross-language half, and the
  synthetic harness in S2b then drives the same code.
- **S2b (adapter):** scan/advertise controllers with bounded register duty
  cycles; Service-Data hint `adv_v1` + scan response; degraded UUID-only mode
  as a supported mode; serialized GATT queue with epochs, driving the shared
  fragmentation layer.
- Synthetic-peer harness: injected transport driving 100+ peers (TRS-ENV-001
  CI half).
- **Graduates: TRS-PROTO-010 → tested (S2a, three-language vector evidence);
  TRS-AND-005/006 → implemented (S2b); TRS-PROTO-009 stays implemented (radio
  is G-01).**

### S3 — BLE adapter foundation, iOS (ADR-002)
- UUID-only advertising (`CBAdvertisementDataServiceUUIDsKey`), central scan +
  connection-time exchange, `canSendWriteWithoutResponse` pacing, state
  restoration; same fragmentation contract against the same vectors (macOS CI).
- **Graduates: TRS-IOS-003 → implemented (background behaviour and radio are
  owner-executed).**

### S4 — HELLO/HELLO_ACK and authenticated presence (TRS-PROTO-017/018)
- Canonical trust point: authenticated capability + EID exchange over the
  session; presence keyed on authenticated identity, never advertisement
  bytes; age-out per register; SRS-IDN-002 peer identifiers derived from
  crypto material; EID schedule + authTag wired to `adv/ble_advertisement`
  vectors; spoof/replay negatives (TRS-ENV-002 CI half).
- **Graduates: TRS-PROTO-017, TRS-PROTO-018, SRS-IDN-002, SRS-NBY-006/007
  (CI-provable halves).**

### S5 — First activities: Visible Nearby, permissions, app lock
- Visible Nearby control with duration + indicator (SRS-NBY-001..003);
  permissions trio at first feature use with plain-language copy
  (SRS-ONB-003/004/005); profile media object store completing SRS-PRO-004;
  App Lock + recents redaction (ADR-010/011 UI halves); AT-001 scenario
  completion (fresh install blocks product entry); proximity bands per
  SRS-PRO-013 from calibrated thresholds; respectful-copy rule extended
  (SRS-PRO-012).
- **Graduates: the above on emulator/robolectric-free JVM + instrumented
  evidence; UI-polish rows stay for WP-10.**

### S6 — Synthetic venue load, rescan ladder, closeout
- 100+ synthetic peers through the full feed path; rescan ladder
  (TRS-ENV-003 CI half); discovery-success metric plumbing (privacy-safe);
  full regression of WP-01/02; `evidence/wp03/closeout.md` + machine twin.

## Owner-executed, never simulated (unchanged, EDP §8)

G-01 physical radio campaigns (AT-003 capture, AT-004 crowded venue, 3-second
preview benchmark, Android↔iOS both directions) ship from S2 onward as
scripted evidence packs marked `unproven_owner`. Hardware keystore tiers
(StrongBox/Secure Enclave), store consoles, KMS, legal and external crypto
review remain exactly where EDP §8 puts them.
