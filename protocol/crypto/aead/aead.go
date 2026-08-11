// Lumos - protocol/crypto/aead / ChaCha20-Poly1305 AEAD with header-as-AAD and key derivation.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CRY-003, TRS-CRY-011, TRS-CRY-013, TRS-PROTO-003
// ADRs: ADR-001
// Threading: any
// Notes: One message key is used for exactly one AEAD operation, and both the
// encryption key and the nonce are derived from it through HKDF with the
// register's info string - that is the testable nonce-uniqueness rule of
// TRS-CRY-011: nonces cannot repeat under a key because keys are never reused.
// Open returns a single stable code for every failure mode; distinguishing
// tag, AAD or ciphertext tampering would be an oracle (TRS-CRY-013).
package aead

import (
	"crypto/hkdf"
	"crypto/sha256"

	"golang.org/x/crypto/chacha20poly1305"

	"github.com/dharabhishekemail-ops/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekemail-ops/lumos/protocol/gen/go/lumoserrors"
	"github.com/dharabhishekemail-ops/lumos/protocol/wire"
)

// KeySize and NonceSize are the ChaCha20-Poly1305 (IETF) parameters of ADR-001.
const (
	KeySize   = chacha20poly1305.KeySize
	NonceSize = chacha20poly1305.NonceSize
)

// MessageKeys are the per-message encryption parameters derived from a ratchet
// message key. Nonce uniqueness under the key is structural: each message key
// derives exactly one (key, nonce) pair and is then deleted (TRS-CRY-006/011).
type MessageKeys struct {
	Key   [KeySize]byte
	Nonce [NonceSize]byte
}

// DeriveMessageKeys expands a 32-byte message key into AEAD key material using
// HKDF-SHA-256 with the register's message-KDF info string.
func DeriveMessageKeys(messageKey []byte) (*MessageKeys, error) {
	if len(messageKey) != 32 {
		return nil, &wire.Error{Code: lumoserrors.ErrCryptoAeadAuthFailed, Detail: "message key must be 32 bytes"}
	}
	okm, err := hkdf.Key(sha256.New, messageKey, make([]byte, 32), lumosconstants.RatchetMessageKdfInfoValue, KeySize+NonceSize)
	if err != nil {
		return nil, &wire.Error{Code: lumoserrors.ErrCryptoAeadAuthFailed, Detail: "hkdf: " + err.Error()}
	}
	mk := &MessageKeys{}
	copy(mk.Key[:], okm[:KeySize])
	copy(mk.Nonce[:], okm[KeySize:])
	Zero(okm)
	return mk, nil
}

// Seal encrypts plaintext bound to aad. For frame payloads the AAD is the
// complete frame header (TRS-PROTO-003); for ratchet messages it is the session
// AD followed by the 40-byte ratchet header.
func Seal(key, nonce, plaintext, aad []byte) ([]byte, error) {
	c, err := chacha20poly1305.New(key)
	if err != nil {
		return nil, &wire.Error{Code: lumoserrors.ErrCryptoAeadAuthFailed, Detail: err.Error()}
	}
	return c.Seal(nil, nonce, plaintext, aad), nil
}

// Open authenticates and decrypts. Every failure - wrong key, wrong nonce,
// tampered ciphertext, tampered tag, tampered AAD - yields the same stable
// code with no distinguishing detail (TRS-CRY-013).
func Open(key, nonce, ciphertext, aad []byte) ([]byte, error) {
	c, err := chacha20poly1305.New(key)
	if err != nil {
		return nil, &wire.Error{Code: lumoserrors.ErrCryptoAeadAuthFailed, Detail: "aead rejected"}
	}
	plaintext, err := c.Open(nil, nonce, ciphertext, aad)
	if err != nil {
		return nil, &wire.Error{Code: lumoserrors.ErrCryptoAeadAuthFailed, Detail: "aead rejected"}
	}
	return plaintext, nil
}

// Zero best-effort clears key material after use (TRS-CRY-006). Go gives no
// hard guarantee, but overwriting shrinks the exposure window and makes reuse
// of a stale slice a visible bug.
func Zero(b []byte) {
	for i := range b {
		b[i] = 0
	}
}
