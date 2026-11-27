# ADR-013: The GATT link: two characteristics, both directions say HELLO

- **Status:** **Accepted** (owner, 2026-11-28, with CR-010 accepted for its three register values). Decision 4 stands as written: the owner chose ADR-014 option A (both sides say HELLO on open).
- **Relates to:** ADR-002 (asymmetric advertising; trust only at the authenticated HELLO), ADR-004 (every tunable value is generated), ADR-008 (identity), TRS-TRN-002/003, TRS-PROTO-017, TRS-TST-003, SRS-NBY-005..007

## Context

ADR-002 fixes the service UUID, the advertisement, and the rule that trust is established only by HELLO/HELLO_ACK over GATT. It doesn't say how bytes cross a GATT connection: which characteristics exist under the Lumos service, which side writes and which notifies, who speaks first, or how long a handshake may take. The pieces on both sides already exist and are tested: the frame codec, `Fragmenter`/`FragmentReassembler`, `FrameLink` (one blob in flight, paced fragments), `GattOperationQueue` (serialized operations, reconnect epochs), `HelloExchange` (presence only for a verified ACK to one's own HELLO), triage and `PresenceTable`. Nothing connects them over a real link, so two phones can't find each other (NB-1). Writing that link means choosing identifiers both apps must agree on, and those are register values (ADR-004). The choice is recorded here, and the values go through CR-010.

## Decision

1. **Two characteristics under the Lumos service.** Both are new register values (CR-010).
   - `LINK_IN`: the peripheral receives. Properties: Write and Write Without Response. The central writes fragments to it.
   - `LINK_OUT`: the peripheral sends. Properties: Notify, with the standard Client Characteristic Configuration descriptor (0x2902). The peripheral notifies fragments to the central.

   One write characteristic and one notify characteristic is the smallest layout that gives each direction its own stream and works on both platforms' peripheral APIs.
2. **Everything on the link is a frame** (TRS Sec. 9.1), fragmented by the existing fragmenter at the negotiated MTU. The link layer stays content-opaque. HELLO and HELLO_ACK travel as frames of message class `HELLO` (0x01) and `HELLO_ACK` (0x02).
3. **The central's order:** connect, then request the register's target ATT MTU (`BLE_TARGET_ATT_MTU`, 251), then discover services, then enable notifications on `LINK_OUT`, then write its own HELLO to `LINK_IN`.
4. **The peripheral's order:** when a central enables notifications on `LINK_OUT`, it notifies its own HELLO. *(ADR-014 weighed having the peripheral speak last; the owner kept this order on 2026-11-28.)*
5. **Both directions run their own exchange** (`HelloExchange`): each side answers the other's HELLO with a HELLO_ACK and grants presence only when the ACK to its own HELLO verifies. A link is done when both sides have their peer, or the handshake timer (`HELLO_HANDSHAKE_TIMEOUT_SEC`, CR-010) runs out. Either way the central disconnects. Discovery links are short; interest and preview reuse this layout in a later slice.
6. **Don't connect twice.** A peer's HELLO carries its current EID, so triage skips a candidate whose EID already belongs to a fresh presence card. When two phones connect to each other at once, the duplicate is harmless: presence is keyed on identity (SRS-NBY-006).
7. **Failure is quiet and bounded.** A malformed or unverifiable message, a stale callback (the epoch rule), or a timeout ends that link and grants nothing (TRS-ENV-002). The connect budget (`MAX_CONCURRENT_GATT_LINKS`) applies to links in any state.

## Consequences

- Both apps gain a GATT server (peripheral role) beside the advertiser, and a GATT client (central role) beside the scanner. iOS paces writes with `canSendWriteWithoutResponse` (TRS-IOS), and Android with the serialized queue.
- Three new register values change `constants_hash`. CR-010's slice regenerates the vectors that embed it, as CR-004 and CR-005 did.
- The per-link state machine and the discovery engine are testable off a device with in-memory links. The adapters are radio glue, and their evidence is G-01.
- **Alternatives rejected:**
  - One characteristic with Write and Notify both: possible, but it muddles the two directions, and some peripheral stacks report a central's own write back through the notify path.
  - L2CAP channels: not available to a backgrounded iOS peripheral, and uneven across Android OEMs.
  - Encoding the identifiers outside the register: ADR-004 forbids it.
