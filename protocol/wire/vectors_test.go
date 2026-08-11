// Lumos - protocol/wire / Go conformance consumption of the committed framing vectors.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-TST-001, TRS-PROTO-015, TRS-CONST-005, SRS-NFR-CMP-004
// ADRs: ADR-007
// Threading: any
// Notes: This is the Go third of the tri-language conformance contract, running
// under 'go test ./protocol/...' as TRS Sec. 18.4 requires. It consumes the
// COMMITTED files, not freshly generated ones, so a stale or hand-edited vector
// fails here even if the generator is healthy. Conformance is exact: positive
// vectors must reproduce expected bytes exactly, negative vectors must fail with
// exactly the stated stable code, and a missing file is a failed gate, not a skip.
package wire

import (
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"

	"github.com/dharabhishekemail-ops/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekemail-ops/lumos/protocol/schema"
)

type vectorFields struct {
	Version       int    `json:"version"`
	MessageType   int    `json:"message_type"`
	Flags         int    `json:"flags"`
	SessionIDHex  string `json:"session_id_hex"`
	MessageIDHex  string `json:"message_id_hex"`
	Sequence      uint32 `json:"sequence"`
	AckBase       uint32 `json:"ack_base"`
	PayloadLength int    `json:"payload_length"`
	ExpirySeconds uint32 `json:"expiry_seconds"`
	HopLimit      int    `json:"hop_limit"`
	PayloadHex    string `json:"payload_hex"`
}

type vectorMeta struct {
	SchemaVersion int    `json:"schema_version"`
	ConstantsHash string `json:"constants_hash"`
	KeyProvenance string `json:"key_provenance"`
}

type roundtripVectors struct {
	vectorMeta
	Cases []struct {
		Name       string       `json:"name"`
		DecodeOnly bool         `json:"decode_only"`
		Fields     vectorFields `json:"fields"`
		FrameHex   string       `json:"frame_hex"`
		Expect     struct {
			MessageTypeKnown bool `json:"message_type_known"`
		} `json:"expect"`
	} `json:"cases"`
}

type invalidVectors struct {
	vectorMeta
	Cases []struct {
		Name          string `json:"name"`
		FrameHex      string `json:"frame_hex"`
		ExpectedError string `json:"expected_error"`
	} `json:"cases"`
}

// loadVectors fails the test on a missing file: a skipped vector file is a
// failed gate (TRS Sec. 18.4), never a soft skip.
func loadVectors(t *testing.T, rel string, into any) {
	t.Helper()
	path := filepath.Join("..", "..", filepath.FromSlash(rel))
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("committed vector file %s is unreadable - a missing vector file is a failed gate: %v", rel, err)
	}
	if err := json.Unmarshal(raw, into); err != nil {
		t.Fatalf("parse %s: %v", rel, err)
	}
}

// registerHash reads the register's constants_hash for the drift check.
func registerHash(t *testing.T) string {
	t.Helper()
	reg, err := schema.LoadRegister(filepath.Join("..", ".."))
	if err != nil {
		t.Fatalf("LoadRegister: %v", err)
	}
	h, err := reg.String("constants_hash")
	if err != nil {
		t.Fatalf("constants_hash: %v", err)
	}
	return h
}

func frameFromFields(t *testing.T, f vectorFields) *Frame {
	t.Helper()
	var session, message [16]byte
	sb, err := hex.DecodeString(f.SessionIDHex)
	if err != nil || len(sb) != 16 {
		t.Fatalf("bad session_id_hex %q", f.SessionIDHex)
	}
	mb, err := hex.DecodeString(f.MessageIDHex)
	if err != nil || len(mb) != 16 {
		t.Fatalf("bad message_id_hex %q", f.MessageIDHex)
	}
	copy(session[:], sb)
	copy(message[:], mb)
	payload, err := hex.DecodeString(f.PayloadHex)
	if err != nil {
		t.Fatalf("bad payload_hex: %v", err)
	}
	return &Frame{
		Header: Header{
			Version:       uint8(f.Version),
			Type:          lumosconstants.MessageClass(f.MessageType),
			Flags:         uint16(f.Flags),
			SessionID:     session,
			MessageID:     message,
			Sequence:      f.Sequence,
			AckBase:       f.AckBase,
			ExpirySeconds: f.ExpirySeconds,
			HopLimit:      uint8(f.HopLimit),
		},
		Payload: payload,
	}
}

func TestConformanceFrameRoundtripVectors(t *testing.T) {
	var vf roundtripVectors
	loadVectors(t, "test-vectors/framing/frame_roundtrip.json", &vf)
	if vf.ConstantsHash != registerHash(t) {
		t.Fatalf("vector constants_hash %s does not match the register - stale vectors (TRS-CONST-005)", vf.ConstantsHash)
	}
	if len(vf.Cases) == 0 {
		t.Fatal("no roundtrip cases")
	}

	covered := map[lumosconstants.MessageClass]bool{}
	for _, tc := range vf.Cases {
		t.Run(tc.Name, func(t *testing.T) {
			wantBytes, err := hex.DecodeString(tc.FrameHex)
			if err != nil {
				t.Fatalf("bad frame_hex: %v", err)
			}

			if !tc.DecodeOnly {
				got, err := Encode(frameFromFields(t, tc.Fields))
				if err != nil {
					t.Fatalf("Encode: %v", err)
				}
				if hex.EncodeToString(got) != tc.FrameHex {
					t.Errorf("encode mismatch:\n got %s\nwant %s", hex.EncodeToString(got), tc.FrameHex)
				}
				covered[lumosconstants.MessageClass(tc.Fields.MessageType)] = true
			}

			decoded, err := Decode(wantBytes)
			if err != nil {
				t.Fatalf("Decode: %v", err)
			}
			f := tc.Fields
			h := decoded.Header
			if int(h.Version) != f.Version || int(h.Type) != f.MessageType || int(h.Flags) != f.Flags ||
				hex.EncodeToString(h.SessionID[:]) != f.SessionIDHex ||
				hex.EncodeToString(h.MessageID[:]) != f.MessageIDHex ||
				h.Sequence != f.Sequence || h.AckBase != f.AckBase ||
				h.ExpirySeconds != f.ExpirySeconds || int(h.HopLimit) != f.HopLimit ||
				hex.EncodeToString(decoded.Payload) != f.PayloadHex {
				t.Error("decoded fields do not match the vector")
			}
			if len(decoded.Payload) != f.PayloadLength {
				t.Errorf("payload length %d, vector says %d", len(decoded.Payload), f.PayloadLength)
			}
			if h.Type.Known() != tc.Expect.MessageTypeKnown {
				t.Errorf("Known() = %v, vector expects %v", h.Type.Known(), tc.Expect.MessageTypeKnown)
			}
		})
	}

	for mc := range lumosconstants.MessageClassNames {
		if !covered[mc] {
			t.Errorf("message class %s has no positive roundtrip vector (TRS-PROTO-014)", mc)
		}
	}
}

func TestConformanceFrameInvalidVectors(t *testing.T) {
	var vf invalidVectors
	loadVectors(t, "test-vectors/framing/frame_invalid.json", &vf)
	if vf.ConstantsHash != registerHash(t) {
		t.Fatalf("vector constants_hash does not match the register - stale vectors (TRS-CONST-005)")
	}
	if len(vf.Cases) == 0 {
		t.Fatal("no invalid cases")
	}

	for _, tc := range vf.Cases {
		t.Run(tc.Name, func(t *testing.T) {
			raw, err := hex.DecodeString(tc.FrameHex)
			if err != nil {
				t.Fatalf("bad frame_hex: %v", err)
			}
			_, err = Decode(raw)
			if err == nil {
				t.Fatalf("decoder ACCEPTED a negative vector; must fail with %s", tc.ExpectedError)
			}
			var we *Error
			if !errors.As(err, &we) {
				t.Fatalf("non-wire error %T: %v", err, err)
			}
			if we.Code != tc.ExpectedError {
				t.Errorf("failed with %s, vector requires exactly %s", we.Code, tc.ExpectedError)
			}
		})
	}
}
