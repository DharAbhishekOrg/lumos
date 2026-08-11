// Lumos - protocol/crypto/aead / Unit tests including the RFC 8439 outside witness.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CRY-003, TRS-CRY-011, TRS-CRY-013
// ADRs: ADR-001
// Threading: any
// Notes: TestRFC8439KnownAnswer transcribes the official ChaCha20-Poly1305 test
// vector from RFC 8439 Sec. 2.8.2 BY HAND. It is the independent anchor proving
// this package speaks the real IETF construction, not merely something
// self-consistent - the same role the hand-computed frame hex plays in S4a.
package aead

import (
	"bytes"
	"encoding/hex"
	"errors"
	"strings"
	"testing"

	"github.com/dharabhishekemail-ops/lumos/protocol/gen/go/lumoserrors"
	"github.com/dharabhishekemail-ops/lumos/protocol/wire"
)

func mustHex(t *testing.T, s string) []byte {
	t.Helper()
	b, err := hex.DecodeString(strings.ReplaceAll(s, " ", ""))
	if err != nil {
		t.Fatalf("bad hex fixture: %v", err)
	}
	return b
}

// TestRFC8439KnownAnswer: RFC 8439 Sec. 2.8.2, transcribed by hand.
func TestRFC8439KnownAnswer(t *testing.T) {
	key := mustHex(t, "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
	nonce := mustHex(t, "070000004041424344454647")
	aad := mustHex(t, "50515253c0c1c2c3c4c5c6c7")
	plaintext := []byte("Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.")
	wantCiphertext := mustHex(t,
		"d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6"+
			"3dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b36"+
			"92ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc"+
			"3ff4def08e4b7a9de576d26586cec64b6116")
	wantTag := mustHex(t, "1ae10b594f09e26a7e902ecbd0600691")

	got, err := Seal(key, nonce, plaintext, aad)
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}
	want := append(append([]byte(nil), wantCiphertext...), wantTag...)
	if !bytes.Equal(got, want) {
		t.Fatalf("RFC 8439 KAT mismatch:\n got %x\nwant %x", got, want)
	}

	back, err := Open(key, nonce, got, aad)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	if !bytes.Equal(back, plaintext) {
		t.Error("roundtrip lost the plaintext")
	}
}

func assertAuthFail(t *testing.T, err error) {
	t.Helper()
	if err == nil {
		t.Fatal("expected authentication failure, got success")
	}
	var we *wire.Error
	if !errors.As(err, &we) || we.Code != lumoserrors.ErrCryptoAeadAuthFailed {
		t.Fatalf("expected %s, got %v", lumoserrors.ErrCryptoAeadAuthFailed, err)
	}
	if strings.Contains(we.Detail, "tag") || strings.Contains(we.Detail, "aad") {
		t.Errorf("failure detail %q leaks oracle information (TRS-CRY-013)", we.Detail)
	}
}

func TestEveryTamperFailsIdentically(t *testing.T) {
	mk, err := DeriveMessageKeys(bytes.Repeat([]byte{0x11}, 32))
	if err != nil {
		t.Fatalf("DeriveMessageKeys: %v", err)
	}
	aad := []byte("associated data")
	ct, err := Seal(mk.Key[:], mk.Nonce[:], []byte("payload"), aad)
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}

	t.Run("tampered aad", func(t *testing.T) {
		bad := append([]byte(nil), aad...)
		bad[0] ^= 1
		_, err := Open(mk.Key[:], mk.Nonce[:], ct, bad)
		assertAuthFail(t, err)
	})
	t.Run("tampered ciphertext", func(t *testing.T) {
		bad := append([]byte(nil), ct...)
		bad[0] ^= 1
		_, err := Open(mk.Key[:], mk.Nonce[:], bad, aad)
		assertAuthFail(t, err)
	})
	t.Run("tampered tag", func(t *testing.T) {
		bad := append([]byte(nil), ct...)
		bad[len(bad)-1] ^= 1
		_, err := Open(mk.Key[:], mk.Nonce[:], bad, aad)
		assertAuthFail(t, err)
	})
	t.Run("wrong key", func(t *testing.T) {
		other, _ := DeriveMessageKeys(bytes.Repeat([]byte{0x22}, 32))
		_, err := Open(other.Key[:], mk.Nonce[:], ct, aad)
		assertAuthFail(t, err)
	})
	t.Run("wrong nonce", func(t *testing.T) {
		var n [NonceSize]byte
		copy(n[:], mk.Nonce[:])
		n[0] ^= 1
		_, err := Open(mk.Key[:], n[:], ct, aad)
		assertAuthFail(t, err)
	})
}

// TestDerivationIsDeterministicAndKeySeparated pins the TRS-CRY-011 rule: one
// message key yields exactly one (key, nonce) pair, and distinct message keys
// yield distinct pairs.
func TestDerivationIsDeterministicAndKeySeparated(t *testing.T) {
	a1, err := DeriveMessageKeys(bytes.Repeat([]byte{0xAA}, 32))
	if err != nil {
		t.Fatalf("DeriveMessageKeys: %v", err)
	}
	a2, _ := DeriveMessageKeys(bytes.Repeat([]byte{0xAA}, 32))
	if *a1 != *a2 {
		t.Error("derivation is not deterministic")
	}
	b, _ := DeriveMessageKeys(bytes.Repeat([]byte{0xAB}, 32))
	if a1.Key == b.Key || a1.Nonce == b.Nonce {
		t.Error("distinct message keys must derive distinct key/nonce material")
	}
	if _, err := DeriveMessageKeys([]byte("short")); err == nil {
		t.Error("short message key must be rejected")
	}
}
