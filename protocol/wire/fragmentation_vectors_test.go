// Lumos - protocol/wire / Go conformance consumption of the fragmentation vectors.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-PROTO-010, TRS-PROTO-015, TRS-TST-001, TRS-CONST-005
// ADRs: ADR-004
// Threading: any
// Notes: Go is the generator's language, so the value here is the committed-file
// direction (the WP-01 S5 lesson): these tests replay the COMMITTED family
// through the real Split and Reassembler, so a regenerated-but-uncommitted file,
// or a drift in either implementation, goes red here before Java or Swift ever
// see it. The scripted timelines execute with the file's own millisecond clock
// values - the reassembler has no other clock to fall back to.
package wire

import (
	"bytes"
	"encoding/hex"
	"errors"
	"testing"
)

type fragVectors struct {
	vectorMeta
	Split []struct {
		Name         string   `json:"name"`
		ChunkPayload int      `json:"chunk_payload"`
		AssemblyID   int      `json:"assembly_id"`
		BlobHex      string   `json:"blob_hex"`
		FragmentsHex []string `json:"fragments_hex"`
	} `json:"split"`
	Scripts []struct {
		Name          string `json:"name"`
		TimeoutMillis int64  `json:"timeout_millis"`
		Steps         []struct {
			AtMillis        int64  `json:"at_millis"`
			FragmentHex     string `json:"fragment_hex"`
			Expect          string `json:"expect"`
			Error           string `json:"error"`
			PayloadHex      string `json:"payload_hex"`
			Restarted       bool   `json:"restarted"`
			ExpiredPrevious bool   `json:"expired_previous"`
		} `json:"steps"`
	} `json:"scripts"`
}

func loadFragVectors(t *testing.T) fragVectors {
	t.Helper()
	var vf fragVectors
	loadVectors(t, "test-vectors/framing/fragmentation.json", &vf)
	if vf.ConstantsHash != registerHash(t) {
		t.Fatalf("vector constants_hash does not match the register - stale vectors (TRS-CONST-005)")
	}
	if len(vf.Split) == 0 || len(vf.Scripts) == 0 {
		t.Fatal("a fragmentation family without splits and scripts proves nothing")
	}
	return vf
}

func TestConformanceFragmentationSplitVectors(t *testing.T) {
	vf := loadFragVectors(t)
	for _, c := range vf.Split {
		blob := unhexT(t, c.Name+" blob", c.BlobHex)
		fragments, err := Split(blob, uint8(c.AssemblyID), c.ChunkPayload)
		if err != nil {
			t.Fatalf("%s: %v", c.Name, err)
		}
		if len(fragments) != len(c.FragmentsHex) {
			t.Fatalf("%s: %d fragments, vector has %d", c.Name, len(fragments), len(c.FragmentsHex))
		}
		for i, f := range fragments {
			if hex.EncodeToString(f) != c.FragmentsHex[i] {
				t.Fatalf("%s: fragment %d differs from the committed bytes", c.Name, i)
			}
		}

		// The committed fragments must also reassemble to the committed blob.
		r := NewReassembler(1)
		var out []byte
		for i, fh := range c.FragmentsHex {
			res, err := r.Offer(unhexT(t, c.Name, fh), 0)
			if err != nil {
				t.Fatalf("%s: reassembling committed fragment %d: %v", c.Name, i, err)
			}
			if res.Outcome == OfferComplete {
				out = res.Payload
			}
		}
		if !bytes.Equal(out, blob) {
			t.Fatalf("%s: committed fragments did not reassemble to the committed blob", c.Name)
		}
	}
}

func TestConformanceFragmentationScripts(t *testing.T) {
	vf := loadFragVectors(t)
	for _, s := range vf.Scripts {
		r := NewReassembler(s.TimeoutMillis)
		for i, step := range s.Steps {
			res, err := r.Offer(unhexT(t, s.Name, step.FragmentHex), step.AtMillis)
			if step.Expect == "error" {
				var we *Error
				if err == nil || !errors.As(err, &we) || we.Code != step.Error {
					t.Fatalf("%s step %d: want %s, got %v", s.Name, i, step.Error, err)
				}
				continue
			}
			if err != nil {
				t.Fatalf("%s step %d: %v", s.Name, i, err)
			}
			want := map[string]OfferOutcome{
				"accepted": OfferAccepted, "duplicate": OfferDuplicate, "complete": OfferComplete,
			}[step.Expect]
			if res.Outcome != want {
				t.Fatalf("%s step %d: outcome %v, vector expects %s", s.Name, i, res.Outcome, step.Expect)
			}
			if res.Restarted != step.Restarted || res.ExpiredPrevious != step.ExpiredPrevious {
				t.Fatalf("%s step %d: flags (restarted %v, expired %v) differ from the vector",
					s.Name, i, res.Restarted, res.ExpiredPrevious)
			}
			if step.Expect == "complete" && hex.EncodeToString(res.Payload) != step.PayloadHex {
				t.Fatalf("%s step %d: reassembled payload differs from the vector", s.Name, i)
			}
		}
	}
}

// unhexT decodes or fails the test.
func unhexT(t *testing.T, what, value string) []byte {
	t.Helper()
	out, err := hex.DecodeString(value)
	if err != nil {
		t.Fatalf("%s is not hex: %v", what, err)
	}
	return out
}
