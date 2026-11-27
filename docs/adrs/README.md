# Architecture Decision Records

ADR-001..004 are recorded in `Lumos_EDP_v1_0.pdf` and are baselined. ADR-005
onward are raised during implementation per TRS-AI-006 and captured here.

| ADR | Status | Subject |
| --- | --- | --- |
| 001 | Accepted (EDP) | Crypto suite: libsodium X25519 / Ed25519 / ChaCha20-Poly1305 / HKDF-SHA-256; libsignal prohibited |
| 002 | Accepted (EDP) | Asymmetric BLE advertising; canonical trust at authenticated GATT HELLO |
| 003 | Accepted (EDP) | Content-only RecoveryRecord; identity keys hardware-backed, never escrowed |
| 004 | Accepted (EDP) | Protocol/policy constants frozen; generated, never hand-written |
| [005](ADR-005-safety-number-format.md) | **Accepted** | SAFETY_NUMBER_FORMAT = 60 digits + 6 emoji from a 64-glyph alphabet |
| [006](ADR-006-override-bounds.md) | **Accepted** | Override bounds for the eleven constants TRS §23 left unbounded |
| [007](ADR-007-wire-encoding-choices.md) | **Accepted** | Frozen wire encodings: protocol-version byte and flag bit positions |
| [008](ADR-008-identity-dh-key.md) | **Accepted** | Separate X25519 identity-DH key, Ed25519-signed; Ed25519 sole root of trust, verify-before-DH (2 conditions) |
| [009](ADR-009-safety-number-emoji-alphabet.md) | **Accepted** | The frozen 64-emoji safety-number alphabet and its selection criteria |
| [010](ADR-010-keystore-authentication-policy.md) | **Accepted** | No key-level auth gating (background decrypt requires it); App Lock + action-level device auth for wipe, export, reset; StrongBox/SE capability-probed |
| [011](ADR-011-screenshot-policy.md) | **Accepted** | Recents snapshots always redacted; detect-and-warn in chat where the platform permits; no global FLAG_SECURE (SRS-SAF-004 evidence capture) |
| [012](ADR-012-app-boot-and-key-root.md) | **Accepted** | App boot order; the wrapped data root lives in a no-backup file with the KEK tier, outside the database it unlocks |
| [013](ADR-013-gatt-link-layout.md) | Proposed | The GATT link: LINK_IN (write) and LINK_OUT (notify) under the Lumos service; both sides say HELLO; bounded handshake (needs CR-010) |

**ADR-008 was ratified** with two binding conditions (Ed25519 as sole root of
trust; signature verification fails closed before any DH), both enforced by test.
It resolves how X3DH runs without an Ed25519↔X25519 conversion the mandated
platform libraries do not expose. The gate G-07 cryptographic-review flag is
retained.
