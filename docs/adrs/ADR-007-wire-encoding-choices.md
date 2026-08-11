# ADR-007 — Frozen wire encodings TRS §9.1 left open

- **Status:** **ACCEPTED** by the owner, 2026-08-11 (after WP-01 slice S3 review; recorded at S4a).
- **Raised:** 2026-08-11 by the implementing agent in slice S3, flagged in the register as implementation choices rather than transcription.
- **Affected requirements:** TRS-PROTO-001, TRS-PROTO-002, TRS-PROTO-006, TRS-PROTO-016
- **Affected artefacts:** `protocol/constants/lumos_constants.json` (`framing` group), `protocol/schemas/frame_header.schema.json`, the wire codecs on all three platforms, every framing golden vector from slice S4a onward

## Context

TRS §9.1 defines the canonical frame header but deliberately leaves two encodings
to the implementation (TRS §19: the agent "may choose internal implementation
details where the TRS allows"):

1. `protocol_version` is "current major/minor encoded per schema" — no encoding given.
2. The `flags` field names four meanings (critical/optional, compression, relay
   eligibility, local-only) — no bit positions given.

Both were fixed in slice S3, flagged as provisional implementation choices, and
are accepted here. **From the moment slice S4a commits framing golden vectors,
both are frozen wire format**: changing either invalidates every framing vector
and is a breaking protocol change under TRS-PROTO-016 and TRS-CONST-003.

## Decision 1 — protocol version byte

One `u8`: **high nibble = major, low nibble = minor**. Version 1.0 = `0x10`
(`framing.PROTOCOL_VERSION`: `major: 1`, `minor: 0`, `encoded: 16`).

Handling per TRS-PROTO-006: a frame whose **major** nibble differs from the
build's fails closed with `ERR_FRAME_UNSUPPORTED_VERSION`; a differing **minor**
nibble is accepted. This gives fifteen majors and sixteen minors per major, which
is sufficient headroom for a protocol whose changes are governed by semantic
versioning and compatibility review (TRS-PROTO-016); exhausting it would itself
be a major-version event on a field that is versioned.

## Decision 2 — flag bit positions

| Bit | Mask | Constant | Meaning |
| --- | --- | --- | --- |
| 0 | `0x0001` | `FRAME_FLAG_CRITICAL` | Receiver that cannot interpret the frame must fail closed, not skip |
| 1 | `0x0002` | `FRAME_FLAG_COMPRESSED` | Payload compressed (negotiated; bomb-guarded per TRS-PROTO-008) |
| 2 | `0x0004` | `FRAME_FLAG_RELAY_ELIGIBLE` | Clear ⇒ no relay may forward |
| 3 | `0x0008` | `FRAME_FLAG_LOCAL_ONLY` | Must never traverse the internet relay (SRS-TRN-009) |
| 4–15 | `0xFFF0` | `FRAME_FLAGS_RESERVED_MASK` | Reserved. Set + critical ⇒ fail closed; set + non-critical ⇒ ignored |

The reserved-bit rule is the TRS-PROTO-006 posture applied to flags: unknown
optional information is skippable, unknown critical information is not.

## Consequences

- Slice S4a's `framing/frame_roundtrip.json` and `frame_invalid.json` encode both
  decisions into byte-exact golden vectors consumed by all three platforms; the
  Java and Swift codecs (S5) implement this ADR by consuming those vectors, not
  by re-deciding.
- The constants live only in the `framing` group of the register and reach code
  through generated symbols — no codec hand-writes `0x10` or a bit mask.
- Any future change requires an approved change request, vector regeneration and
  a full conformance re-run (TRS-CONST-003).

## Verification

Register values pinned by `TestGoldenValues` (`framing.*` rows, transcribed by
hand); byte-level behaviour pinned by the S4a golden vectors and the codec test
suite, including negative vectors for the reserved-bit and version-major rules.
