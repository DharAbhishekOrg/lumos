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
| [008](ADR-008-identity-dh-key.md) | **Provisional** | Separate X25519 identity-DH key, Ed25519-signed (avoids cross-curve conversion) |
| [009](ADR-009-safety-number-emoji-alphabet.md) | **Accepted** | The frozen 64-emoji safety-number alphabet and its selection criteria |

**ADR-008 is provisional** and awaits owner ratification: it resolves how X3DH
runs without an Ed25519↔X25519 conversion that the mandated platform libraries
do not expose. See the record for the security note flagged for gate G-07.
