// Lumos - protocol/media / Unit tests for the object pipeline.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CRY-008, TRS-PROTO-010, TRS-PROTO-011, TRS-MED-002
// ADRs: ADR-001
// Threading: any
// Notes: The digest test is the one that matters: it decrypts cleanly and STILL
// fails, because the declared digest is over the plaintext. A pipeline that only
// checked the AEAD tag would pass a wrong-but-authentic reassembly.
package media

import (
	"bytes"
	"crypto/sha256"
	"errors"
	"testing"

	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumoserrors"
	"github.com/dharabhishekorg/lumos/protocol/wire"
)

func seedBytes(label string, n int) []byte {
	out := make([]byte, 0, n+32)
	for i := 0; len(out) < n; i++ {
		h := sha256.Sum256([]byte("TESTONLY-media-unit|" + label + "|" + string(rune('0'+i%10))))
		out = append(out, h[:]...)
	}
	return out[:n]
}

func fixture(t *testing.T, size, chunkSize int) ([]byte, [16]byte, []byte, *Meta, []byte) {
	t.Helper()
	sessionKey := seedBytes("session", 32)
	objectKey := seedBytes("objectkey", ObjectKeySize)
	var objectID [16]byte
	copy(objectID[:], seedBytes("objectid", 16))
	plaintext := seedBytes("plaintext", size)

	meta, ciphertext, err := Seal(sessionKey, objectID, objectKey, plaintext, chunkSize)
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}
	return sessionKey, objectID, plaintext, meta, ciphertext
}

func assertCode(t *testing.T, err error, want string) {
	t.Helper()
	var we *wire.Error
	if !errors.As(err, &we) || we.Code != want {
		t.Fatalf("expected %s, got %v", want, err)
	}
}

func TestSealChunkReassembleOpen(t *testing.T) {
	sessionKey, objectID, plaintext, meta, ciphertext := fixture(t, 5000, 1024)

	if meta.PlaintextSize != 5000 {
		t.Errorf("plaintext size %d", meta.PlaintextSize)
	}
	if meta.ChunkCount != (meta.CiphertextSize+1023)/1024 {
		t.Errorf("chunk plan %d does not cover %d ciphertext bytes", meta.ChunkCount, meta.CiphertextSize)
	}

	// Reassemble strictly from chunks, as a receiver would.
	var reassembled []byte
	ack := NewSelectiveAck(meta.ChunkCount)
	for i := 0; i < meta.ChunkCount; i++ {
		c, err := Chunk(ciphertext, meta.ChunkSize, i)
		if err != nil {
			t.Fatalf("Chunk %d: %v", i, err)
		}
		reassembled = append(reassembled, c...)
		if err := ack.Mark(i); err != nil {
			t.Fatalf("Mark %d: %v", i, err)
		}
	}
	if !ack.Complete() {
		t.Error("ack should be complete")
	}
	if len(reassembled) != meta.CiphertextSize {
		t.Fatalf("reassembled %d bytes, expected %d", len(reassembled), meta.CiphertextSize)
	}

	got, err := Open(sessionKey, objectID, meta, reassembled)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	if !bytes.Equal(got, plaintext) {
		t.Error("roundtrip lost the object")
	}
}

// TestWholeObjectEncryptionPrecedesChunking: no chunk boundary is a crypto
// boundary, so an individual chunk is not independently decryptable.
func TestWholeObjectEncryptionPrecedesChunking(t *testing.T) {
	sessionKey, objectID, _, meta, ciphertext := fixture(t, 4096, 512)
	if meta.ChunkCount < 4 {
		t.Fatalf("need several chunks, got %d", meta.ChunkCount)
	}
	first, err := Chunk(ciphertext, meta.ChunkSize, 0)
	if err != nil {
		t.Fatalf("Chunk: %v", err)
	}
	if _, err := Open(sessionKey, objectID, meta, first); err == nil {
		t.Error("a single chunk must not decrypt on its own (TRS-CRY-008)")
	}
}

// TestDigestMismatchOnCleanDecrypt is the core TRS-MED-002 assertion.
func TestDigestMismatchOnCleanDecrypt(t *testing.T) {
	sessionKey, objectID, plaintext, meta, ciphertext := fixture(t, 2048, 1024)

	// Re-seal DIFFERENT plaintext with the same key/ID: it decrypts perfectly,
	// but the digest declared in meta belongs to the original object.
	other := append([]byte(nil), plaintext...)
	other[0] ^= 0xFF
	objectKey := seedBytes("objectkey", ObjectKeySize)
	otherMeta, otherCiphertext, err := Seal(sessionKey, objectID, objectKey, other, 1024)
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}
	if otherMeta.CiphertextSize != meta.CiphertextSize {
		t.Fatalf("fixture sizes differ; the test would pass for the wrong reason")
	}

	forged := *meta // original digest...
	forged.WrappedKey = otherMeta.WrappedKey
	_, err = Open(sessionKey, objectID, &forged, otherCiphertext)
	assertCode(t, err, lumoserrors.ErrMediaDigestMismatch)
	_ = ciphertext
}

func TestTruncatedReassemblyRejected(t *testing.T) {
	sessionKey, objectID, _, meta, ciphertext := fixture(t, 3000, 900)
	_, err := Open(sessionKey, objectID, meta, ciphertext[:len(ciphertext)-1])
	assertCode(t, err, lumoserrors.ErrMediaDigestMismatch)
}

func TestObjectKeyIsBoundToItsSession(t *testing.T) {
	sessionKey, objectID, _, meta, ciphertext := fixture(t, 1000, 512)

	t.Run("foreign session", func(t *testing.T) {
		foreign := seedBytes("other-session", 32)
		_, err := Open(foreign, objectID, meta, ciphertext)
		assertCode(t, err, lumoserrors.ErrMediaObjectKeyUnwrapFailed)
	})
	t.Run("foreign object id", func(t *testing.T) {
		var other [16]byte
		copy(other[:], seedBytes("other-objectid", 16))
		_, err := Open(sessionKey, other, meta, ciphertext)
		assertCode(t, err, lumoserrors.ErrMediaObjectKeyUnwrapFailed)
	})
	t.Run("tampered wrap", func(t *testing.T) {
		bad := *meta
		bad.WrappedKey = append([]byte(nil), meta.WrappedKey...)
		bad.WrappedKey[0] ^= 1
		_, err := Open(sessionKey, objectID, &bad, ciphertext)
		assertCode(t, err, lumoserrors.ErrMediaObjectKeyUnwrapFailed)
	})
}

func TestSelectiveAckBitmapAndRanges(t *testing.T) {
	ack := NewSelectiveAck(10)
	for _, i := range []int{0, 1, 2, 5, 9} {
		if err := ack.Mark(i); err != nil {
			t.Fatalf("Mark %d: %v", i, err)
		}
	}
	if ack.Complete() {
		t.Error("ack must not be complete")
	}
	got := ack.MissingRanges()
	want := [][2]int{{3, 4}, {6, 8}}
	if len(got) != len(want) {
		t.Fatalf("missing ranges %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("range %d = %v, want %v", i, got[i], want[i])
		}
	}
	// Resume token points at the first gap, so a resume does not retransmit
	// what already arrived (TRS-PROTO-011).
	if tok := ack.ResumeToken(); len(tok) != 4 || tok[3] != 3 {
		t.Errorf("resume token %v, want next-chunk 3", tok)
	}

	if err := ack.Mark(10); err == nil {
		t.Error("out-of-range mark must be rejected")
	}
	if err := ack.Mark(-1); err == nil {
		t.Error("negative mark must be rejected")
	}
}

func TestChunkPlanEdgeCases(t *testing.T) {
	if n, _ := ChunkPlan(0, 100); n != 0 {
		t.Errorf("empty object plan = %d, want 0", n)
	}
	if n, _ := ChunkPlan(100, 100); n != 1 {
		t.Errorf("exact fit = %d, want 1", n)
	}
	if n, _ := ChunkPlan(101, 100); n != 2 {
		t.Errorf("one over = %d, want 2", n)
	}
	if _, err := ChunkPlan(100, 0); err == nil {
		t.Error("zero chunk size must be rejected")
	}
	if _, err := Chunk([]byte("abc"), 2, 5); err == nil {
		t.Error("out-of-range chunk index must be rejected")
	}
}
