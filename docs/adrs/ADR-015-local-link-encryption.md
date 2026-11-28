# ADR-015: Encrypt the local link after HELLO, before any preview or interest

- **Status:** Proposed (agent, 2026-11-28, raised before CS4). Needs the owner's decision. If accepted, it also needs a CR for its register values and the G-07 cryptographic review before it ships.
- **Relates to:** ADR-001 (the crypto suite), ADR-002 (trust only at the authenticated HELLO), ADR-008 (identity-DH key), ADR-013 (GATT link), TRS-PROTO-003, TRS-PROTO-017, TRS-CRY-001, TRS-CRY-011, SRS-PRO-002/003/007, SRS-MAT-001/004, SRS-NFR-PRI-002, G-07

## Context

CS4 sends the next frames over the Bluetooth link: an interest request carrying the requester's profile preview, then accept, then the match moment. Today those frames would travel **in the clear**. HELLO authenticates both sides (signatures), but nothing encrypts what follows. Discovery links are not paired at the Bluetooth level, so anyone nearby with a cheap radio sniffer could read them.

That matters here more than in most apps. A preview can carry the fields the data map treats as GDPR Article 9 special-category data: gender identity, orientation, bio, and the photo identifier. For an LGBTQ person in a hostile place, a sniffed "orientation" field next to a pseudonymous key is real-world danger. The person chose to show that preview to one person they are interested in, not to the room.

TRS-PROTO-003 already says the complete frame header is authenticated as AEAD associated data, so the frame format expects AEAD. TRS-PROTO-017 says preview retrieval and higher protocol happen "authenticated per session rules" after HELLO. What is missing is the session key for a local link.

## Options

**A. Keep local frames in the clear (authenticated only).** Simplest. Previews and interest are readable by any sniffer. Not acceptable for special-category data. I recommend against it.

**B. Static-static key from the two identity-DH keys.** Both HELLOs already carry each side's X25519 identity-DH key (ADR-008). X25519(mine, theirs), plus both HELLO nonces, through HKDF-SHA-256 gives a per-link ChaCha20-Poly1305 key.
- No new HELLO field and no wire-layout change.
- But **no forward secrecy**: anyone who records the radio today and steals either phone's identity key later can decrypt every preview ever sent between those two people.

**C. Ephemeral keys in the handshake (recommended).** Each side adds a fresh X25519 ephemeral public key to its HELLO, covered by the HELLO signature. The link key is HKDF over DH(ephemeral, ephemeral) plus DH(identity-DH, ephemeral) both ways, and both HELLO transcripts, then ChaCha20-Poly1305 with counter nonces per direction (TRS-CRY-011).
- **Forward secrecy:** the ephemeral keys are thrown away when the link closes.
- Both identities are bound into the key, so a relayed or mixed-up link fails to decrypt.
- Uses only the approved suite (libsodium X25519, ChaCha20-Poly1305, HKDF-SHA-256; ADR-001). No new dependency.
- Cost: a HELLO layout change (one 32-byte field) and a protocol version bump; new register values (CR) for the layout and two HKDF info labels; new golden vectors; G-07 review.

**D. Bluetooth LE Secure Connections pairing.** Use the OS's own link encryption. Needs a pairing prompt per person, which is unusable for meeting strangers, and the bond identifies the device long-term. Rejected.

## Recommendation

**Option C**, in this order:
1. A CR with the HELLO v2 layout and the KDF labels.
2. A Go reference with known-answer vectors, then Java and Swift implementations checked against those vectors.
3. G-07 review before any build carrying previews goes beyond your own phones.

Until C is in, CS4 builds the preview, Interested / Not now and match screens against a test link only. No real preview leaves a phone in the clear.

## Consequences if C is accepted

- `LinkSession` (both apps) derives the link key when the handshake finishes and hands it to the frame layer; HELLO and HELLO_ACK stay as the only plaintext frames.
- Every later frame on a link is ChaCha20-Poly1305 with the full header as associated data (TRS-PROTO-003), and a counter nonce per direction that never repeats (TRS-CRY-011).
- An older phone speaking HELLO v1 can still be discovered (a card), but it is never sent a preview or an interest request.
