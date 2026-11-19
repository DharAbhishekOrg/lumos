// Lumos - protocol/media / Go conformance consumption of the committed media vectors.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-TST-001, TRS-CRY-008, TRS-PROTO-011, TRS-PROTO-015, TRS-MED-002
// ADRs: ADR-001
// Threading: any
// Notes: Reassembles strictly from the committed chunks, exactly as a receiver
// would, and recomputes the seal rather than trusting the recorded ciphertext.
// A missing file is t.Fatal (TRS Sec. 18.4).
package media

import (
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"

	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumoserrors"
	"github.com/dharabhishekorg/lumos/protocol/schema"
	"github.com/dharabhishekorg/lumos/protocol/wire"
)

type mediaVectors struct {
	ConstantsHash   string `json:"constants_hash"`
	SessionKeyHex   string `json:"session_key_hex"`
	ObjectIDHex     string `json:"object_id_hex"`
	ObjectKeyHex    string `json:"object_key_hex"`
	WrappedKeyHex   string `json:"wrapped_object_key_hex"`
	PlaintextHex    string `json:"plaintext_hex"`
	PlaintextSHA256 string `json:"plaintext_sha256_hex"`
	CiphertextHex   string `json:"ciphertext_hex"`
	ChunkSize       int    `json:"chunk_size"`
	ChunkCount      int    `json:"chunk_count"`
	Chunks          []struct {
		Index int    `json:"index"`
		Hex   string `json:"hex"`
	} `json:"chunks"`
	AckCases []struct {
		Name           string   `json:"name"`
		Received       []int    `json:"received"`
		BitmapHex      string   `json:"bitmap_hex"`
		MissingRanges  [][2]int `json:"missing_ranges"`
		ResumeTokenHex string   `json:"resume_token_hex"`
		Complete       bool     `json:"complete"`
	} `json:"ack_cases"`
	Negatives []struct {
		Name          string `json:"name"`
		ExpectedError string `json:"expected_error"`
	} `json:"negatives"`
}

func loadMediaVectors(t *testing.T) mediaVectors {
	t.Helper()
	root := filepath.Join("..", "..")
	raw, err := os.ReadFile(filepath.Join(root, filepath.FromSlash("test-vectors/media/object_pipeline.json")))
	if err != nil {
		t.Fatalf("committed media vectors unreadable - a missing vector file is a failed gate: %v", err)
	}
	var vf mediaVectors
	if err := json.Unmarshal(raw, &vf); err != nil {
		t.Fatalf("parse: %v", err)
	}
	reg, err := schema.LoadRegister(root)
	if err != nil {
		t.Fatalf("LoadRegister: %v", err)
	}
	want, _ := reg.String("constants_hash")
	if vf.ConstantsHash != want {
		t.Fatal("stale media vectors (TRS-CONST-005)")
	}
	return vf
}

func hexDec(t *testing.T, s string) []byte {
	t.Helper()
	b, err := hex.DecodeString(s)
	if err != nil {
		t.Fatalf("bad hex: %v", err)
	}
	return b
}

func TestConformanceMediaPipeline(t *testing.T) {
	vf := loadMediaVectors(t)
	sessionKey := hexDec(t, vf.SessionKeyHex)
	objectKey := hexDec(t, vf.ObjectKeyHex)
	plaintext := hexDec(t, vf.PlaintextHex)
	var objectID [16]byte
	copy(objectID[:], hexDec(t, vf.ObjectIDHex))

	// Recompute the seal: the committed ciphertext must be reproducible.
	meta, ciphertext, err := Seal(sessionKey, objectID, objectKey, plaintext, vf.ChunkSize)
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}
	if hex.EncodeToString(ciphertext) != vf.CiphertextHex {
		t.Error("recomputed ciphertext does not match the vector")
	}
	if hex.EncodeToString(meta.PlaintextSHA256[:]) != vf.PlaintextSHA256 {
		t.Error("plaintext digest mismatch")
	}
	if hex.EncodeToString(meta.WrappedKey) != vf.WrappedKeyHex {
		t.Error("wrapped object key mismatch")
	}
	if meta.ChunkCount != vf.ChunkCount {
		t.Errorf("chunk count %d, vector says %d", meta.ChunkCount, vf.ChunkCount)
	}

	// Reassemble strictly from the committed chunks.
	if len(vf.Chunks) != vf.ChunkCount {
		t.Fatalf("%d chunks listed, plan says %d", len(vf.Chunks), vf.ChunkCount)
	}
	var reassembled []byte
	for i, c := range vf.Chunks {
		if c.Index != i {
			t.Fatalf("chunk %d out of order", i)
		}
		reassembled = append(reassembled, hexDec(t, c.Hex)...)
	}
	got, err := Open(sessionKey, objectID, meta, reassembled)
	if err != nil {
		t.Fatalf("Open from committed chunks: %v", err)
	}
	if hex.EncodeToString(got) != vf.PlaintextHex {
		t.Error("reassembly lost the object")
	}
}

func TestConformanceMediaAckCases(t *testing.T) {
	vf := loadMediaVectors(t)
	for _, c := range vf.AckCases {
		t.Run(c.Name, func(t *testing.T) {
			ack := NewSelectiveAck(vf.ChunkCount)
			for _, i := range c.Received {
				if err := ack.Mark(i); err != nil {
					t.Fatalf("Mark %d: %v", i, err)
				}
			}
			if hex.EncodeToString(ack.Bitmap) != c.BitmapHex {
				t.Errorf("bitmap %s, vector says %s", hex.EncodeToString(ack.Bitmap), c.BitmapHex)
			}
			if ack.Complete() != c.Complete {
				t.Errorf("complete = %v, vector says %v", ack.Complete(), c.Complete)
			}
			if hex.EncodeToString(ack.ResumeToken()) != c.ResumeTokenHex {
				t.Errorf("resume token mismatch")
			}
			got := ack.MissingRanges()
			if len(got) != len(c.MissingRanges) {
				t.Fatalf("missing ranges %v, vector says %v", got, c.MissingRanges)
			}
			for i := range got {
				if got[i] != c.MissingRanges[i] {
					t.Errorf("range %d = %v, vector says %v", i, got[i], c.MissingRanges[i])
				}
			}
		})
	}
}

// TestConformanceMediaNegatives drives each declared failure to its exact code.
func TestConformanceMediaNegatives(t *testing.T) {
	vf := loadMediaVectors(t)
	sessionKey := hexDec(t, vf.SessionKeyHex)
	objectKey := hexDec(t, vf.ObjectKeyHex)
	plaintext := hexDec(t, vf.PlaintextHex)
	var objectID [16]byte
	copy(objectID[:], hexDec(t, vf.ObjectIDHex))

	meta, ciphertext, err := Seal(sessionKey, objectID, objectKey, plaintext, vf.ChunkSize)
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}

	drive := map[string]func(t *testing.T) error{
		"digest_mismatch": func(t *testing.T) error {
			other := append([]byte(nil), plaintext...)
			other[0] ^= 0xFF
			otherMeta, otherCT, err := Seal(sessionKey, objectID, objectKey, other, vf.ChunkSize)
			if err != nil {
				t.Fatalf("Seal: %v", err)
			}
			forged := *meta // original digest
			forged.WrappedKey = otherMeta.WrappedKey
			_, err = Open(sessionKey, objectID, &forged, otherCT)
			return err
		},
		"truncated_reassembly": func(t *testing.T) error {
			_, err := Open(sessionKey, objectID, meta, ciphertext[:len(ciphertext)-1])
			return err
		},
		"foreign_session_unwrap": func(t *testing.T) error {
			foreign := append([]byte(nil), sessionKey...)
			foreign[0] ^= 0xFF
			_, err := Open(foreign, objectID, meta, ciphertext)
			return err
		},
		"chunk_index_out_of_range": func(t *testing.T) error {
			_, err := Chunk(ciphertext, meta.ChunkSize, meta.ChunkCount+5)
			return err
		},
	}

	if len(vf.Negatives) == 0 {
		t.Fatal("no media negatives")
	}
	for _, n := range vf.Negatives {
		t.Run(n.Name, func(t *testing.T) {
			fn, ok := drive[n.Name]
			if !ok {
				t.Fatalf("vector declares negative %q with no consumer driving it", n.Name)
			}
			err := fn(t)
			var we *wire.Error
			if !errors.As(err, &we) || we.Code != n.ExpectedError {
				t.Fatalf("expected %s, got %v", n.ExpectedError, err)
			}
			if !lumoserrors.Known(we.Code) {
				t.Errorf("%s is not in the stable registry", we.Code)
			}
		})
	}
}
