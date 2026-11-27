# ADR-014: Who says HELLO first, and what a blocked person can learn

- **Status:** **Decided: option A** (owner, 2026-11-28). Both sides keep saying HELLO on open (ADR-013 decision 4 unchanged). The recommendation below (B) was not taken. The residual risk in "Context" is accepted and stays written down; the block copy keeps promising only what is true.
- **Relates to:** ADR-002 (trust only at the authenticated HELLO), ADR-013 (GATT link layout), SRS-SAF-002 (blocking applies to local discovery), SRS-NBY-013, TRS-PROTO-017, G-07 (cryptographic review)

## Context

A HELLO carries the sender's identity public key. ADR-013 decision 4 says the peripheral sends its HELLO as soon as a central subscribes, and the central sends its HELLO as soon as it is subscribed. So **each side shows its identity key before it knows who the other side is.**

Since NB-1 step 2e (#97), a phone never answers a blocked identity: no HELLO_ACK, so the blocked person never gets a Nearby card of the person, and the phone stops dialling that sighting for the presence age-out bound. That holds on both apps and is tested.

What step 2e cannot stop: a blocked person running **modified software** can read the identity key inside the person's HELLO and so tell that the person is nearby, even with no card. For someone avoiding a specific person (stalking, harassment), "is she here?" is exactly the information that matters. The block copy today only promises what is true: "They can no longer message you or appear in your Nearby."

There are two cases:

| Case | Who connects | Today |
|---|---|---|
| 1 | The blocked person's phone connects to the person's phone (person is peripheral) | The person's HELLO goes out on subscribe, before the blocked HELLO arrives. **Leaks.** |
| 2 | The person's phone connects to the blocked person's phone (person is central) | The person's HELLO goes out first. **Leaks.** |

A passive radio sniffer can also read HELLOs, since discovery links are not encrypted (ADR-002: identity keys are pseudonymous public keys). That is a separate, existing property and is not changed here.

## Options

**A. Keep ADR-013 as written.** Both sides say HELLO on open. Simplest. Both cases leak.

**B. The peripheral speaks last.** The peripheral waits for the central's HELLO, checks it against the block list, and only then sends its own HELLO with its HELLO_ACK. A blocked central gets nothing at all.
- Closes case 1 completely.
- Case 2 still leaks once per sighting. Step 2e's refusal memory stops repeat dials for the age-out bound, but the first HELLO has already gone out.
- Cost: about half a round trip more per link, a small change in `LinkSession` on both apps, no new register value, no crypto change.

**C. Hide identities from the unknown side (a Noise-style handshake: ephemeral keys first, identities sent encrypted).** Protects against passive sniffers too. But one side must still reveal itself first to someone it does not yet know, so on its own it has the same case-2 gap as B. It is a protocol and crypto change that needs G-07 review.

**D. Recognise a blocked person before connecting.** Would close case 2. Not possible with today's advertisement: the hint's auth tag is shared-key noise rejection, not a per-person identifier (ADR-002), and iPhones advertise no hint at all. It would need a new, privacy-reviewed design.

## Recommendation (for the owner to accept or change)

Take **B now**, by amending ADR-013 decision 4 before ADR-013 is accepted, so the radio adapters are written once in the safer order. Keep **C** and **D** as later work with crypto review. State the remaining case-2 gap plainly in the plan and keep the block copy as it is ("appear in your Nearby"), never "they can't tell you're nearby".

## Consequences if B is accepted

- `LinkSession` (both apps): a peripheral-role link no longer sends HELLO on open; it sends HELLO and HELLO_ACK together after the central's HELLO passes the refusal check. The central's side is unchanged. Tests: a blocked central receives zero frames; an unblocked one still completes; the timeout still bounds a central that never speaks.
- ADR-013 decision 4 changes from "notifies its own HELLO on subscribe" to "waits for the central's HELLO, then answers with HELLO and HELLO_ACK".
- No register, constant or wire-format change; CR-010 is unaffected.
