# ADR-008 — Separate X25519 identity-DH key, Ed25519-signed

- **Status:** **PROVISIONAL** — raised and applied by the implementing agent in WP-01 slice S4b per TRS-AI-006. Needs owner ratification.
- **Raised:** 2026-08-11
- **Affected requirements:** TRS-CRY-001, TRS-CRY-003, TRS-CRY-014, TRS-PROTO-005
- **Affected artefacts:** `protocol/schemas/prekey_bundle.schema.json`, `protocol/crypto/x3dh`, `crypto/x3dh_kat.json`

## Context

ADR-001 fixes the primitive suite: X25519 for key agreement, Ed25519 for
signatures, on all platforms. X3DH performs Diffie-Hellman between **identity
keys**. The canonical Signal X3DH uses a single XEdDSA identity key and converts
between its Montgomery (X25519) and twisted-Edwards (Ed25519) forms as needed.

That conversion is the problem. **Neither the Go standard library, nor Apple
CryptoKit, exposes Ed25519↔X25519 conversion.** Implementing XEdDSA by hand
would mean hand-rolling curve arithmetic — exactly the class of code ADR-001
avoids by mandating vetted primitives, and the owner's S4b instruction forbids
crypto beyond the stdlib and the approved `x/crypto`.

## Decision

Each device holds **two** identity keys:

1. an **Ed25519 identity signing key** — the long-term identity, what the safety
   number is computed over and what identity-change re-verification tracks
   (TRS-CRY-004);
2. a separate **X25519 identity-DH key**, whose public half is **signed by the
   Ed25519 identity** under the domain-separated prefix
   `X3DH_IDENTITY_BINDING_INFO`.

X3DH then runs entirely in X25519 (DH1–DH4 as normal), and every signature is
Ed25519. No cross-curve conversion exists anywhere. `VerifyBundle` checks the
identity-DH binding signature before any DH, so an unbound identity-DH key is
rejected.

## Consequences

- Stays strictly within ADR-001's primitives on every platform, with no bespoke
  curve code.
- The prekey bundle carries one extra key and one extra signature (`identity_dh`,
  `identity_dh_sig`), already reflected in `prekey_bundle.schema.json`.
- The transcript binds **both** identity forms (Ed25519 and identity-DH), so a
  mismatch between them cannot pass unnoticed.
- Security note for review: the binding proves the Ed25519 identity vouches for
  the identity-DH key. The reduction to standard X3DH's authentication holds as
  long as that signature is verified before use — which the code enforces — but
  this is the point most deserving of the external cryptographic review of gate
  G-07.

## Alternatives considered

- **XEdDSA / single identity key (canonical X3DH):** rejected — requires
  cross-curve conversion absent from the mandated platform libraries.
- **PQXDH now:** out of scope; TRS-CRY-016 designs for a post-quantum upgrade
  later, and the register's KDF info strings are already versioned to allow it.

## Verification

`protocol/crypto/x3dh` tests: both sides agree on SK and transcript with and
without a one-time prekey; tampered identity-DH, signed-prekey and re-numbered
prekey signatures are all rejected with the stable code; the transcript is shown
to bind every mandated input.
