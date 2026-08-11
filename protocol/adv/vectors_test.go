// Lumos - protocol/adv / Go conformance consumption of the committed adv vectors.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-TST-001, TRS-PROTO-009, TRS-PROTO-015, SRS-IDN-004
// ADRs: ADR-002
// Threading: any
// Notes: Consumes the COMMITTED file and re-derives every EID and tag from
// k_adv rather than trusting the recorded bytes. A missing file is t.Fatal - a
// skipped vector file is a failed gate (TRS Sec. 18.4).
package adv

import (
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"

	"github.com/dharabhishekemail-ops/lumos/protocol/schema"
	"github.com/dharabhishekemail-ops/lumos/protocol/wire"
)

type advVectors struct {
	ConstantsHash  string `json:"constants_hash"`
	KAdvHex        string `json:"k_adv_hex"`
	RotationSec    int    `json:"eid_rotation_sec"`
	CapabilityBits int    `json:"capability_bits"`
	VenueHint      int64  `json:"venue_hint"`
	Schedule       []struct {
		Epoch           int64  `json:"epoch"`
		UnixSeconds     int64  `json:"unix_seconds"`
		ModeFlags       int    `json:"mode_flags"`
		EIDHex          string `json:"eid_hex"`
		AuthTagHex      string `json:"auth_tag_hex"`
		AdvV1Hex        string `json:"adv_v1_hex"`
		ScanResponseHex string `json:"scan_response_hex"`
	} `json:"schedule"`
	Negatives []struct {
		Name          string `json:"name"`
		AdvV1Hex      string `json:"adv_v1_hex"`
		Epoch         int64  `json:"epoch"`
		ExpectedError string `json:"expected_error"`
	} `json:"negatives"`
}

func loadAdvVectors(t *testing.T) advVectors {
	t.Helper()
	root := filepath.Join("..", "..")
	raw, err := os.ReadFile(filepath.Join(root, filepath.FromSlash("test-vectors/adv/ble_advertisement.json")))
	if err != nil {
		t.Fatalf("committed adv vectors unreadable - a missing vector file is a failed gate: %v", err)
	}
	var vf advVectors
	if err := json.Unmarshal(raw, &vf); err != nil {
		t.Fatalf("parse adv vectors: %v", err)
	}
	reg, err := schema.LoadRegister(root)
	if err != nil {
		t.Fatalf("LoadRegister: %v", err)
	}
	want, err := reg.String("constants_hash")
	if err != nil {
		t.Fatalf("constants_hash: %v", err)
	}
	if vf.ConstantsHash != want {
		t.Fatalf("stale adv vectors (TRS-CONST-005)")
	}
	return vf
}

func TestConformanceAdvSchedule(t *testing.T) {
	vf := loadAdvVectors(t)
	if len(vf.Schedule) == 0 {
		t.Fatal("no adv schedule cases")
	}
	kAdv, err := hex.DecodeString(vf.KAdvHex)
	if err != nil {
		t.Fatalf("bad k_adv: %v", err)
	}

	seenEID := map[string]bool{}
	for _, c := range vf.Schedule {
		t.Run(c.AdvV1Hex[:8], func(t *testing.T) {
			// Epoch arithmetic must agree with the recorded timestamp.
			if got := Epoch(c.UnixSeconds, vf.RotationSec); got != c.Epoch {
				t.Errorf("epoch(%d) = %d, vector says %d", c.UnixSeconds, got, c.Epoch)
			}
			eid, err := DeriveEID(kAdv, c.Epoch)
			if err != nil {
				t.Fatalf("DeriveEID: %v", err)
			}
			if hex.EncodeToString(eid) != c.EIDHex {
				t.Errorf("EID mismatch:\n got %s\nwant %s", hex.EncodeToString(eid), c.EIDHex)
			}
			tag := AuthTag(kAdv, 1, uint8(c.ModeFlags), eid, c.Epoch)
			if hex.EncodeToString(tag) != c.AuthTagHex {
				t.Errorf("authTag mismatch:\n got %s\nwant %s", hex.EncodeToString(tag), c.AuthTagHex)
			}
			built, err := Build(kAdv, uint8(c.ModeFlags), c.Epoch)
			if err != nil {
				t.Fatalf("Build: %v", err)
			}
			if hex.EncodeToString(built) != c.AdvV1Hex {
				t.Errorf("adv_v1 mismatch:\n got %s\nwant %s", hex.EncodeToString(built), c.AdvV1Hex)
			}
			if _, err := Verify(kAdv, built, c.Epoch); err != nil {
				t.Errorf("own payload failed verification: %v", err)
			}
			sr := BuildScanResponse(uint16(vf.CapabilityBits), uint32(vf.VenueHint))
			if hex.EncodeToString(sr) != c.ScanResponseHex {
				t.Errorf("scan response mismatch")
			}
			if seenEID[c.EIDHex] {
				t.Errorf("EID repeated across epochs - rotation must be unlinkable (SRS-IDN-004)")
			}
			seenEID[c.EIDHex] = true
		})
	}
}

func TestConformanceAdvNegatives(t *testing.T) {
	vf := loadAdvVectors(t)
	if len(vf.Negatives) == 0 {
		t.Fatal("no adv negatives")
	}
	kAdv, err := hex.DecodeString(vf.KAdvHex)
	if err != nil {
		t.Fatalf("bad k_adv: %v", err)
	}
	for _, n := range vf.Negatives {
		t.Run(n.Name, func(t *testing.T) {
			payload, err := hex.DecodeString(n.AdvV1Hex)
			if err != nil {
				t.Fatalf("bad adv_v1_hex: %v", err)
			}
			_, err = Verify(kAdv, payload, n.Epoch)
			var we *wire.Error
			if !errors.As(err, &we) || we.Code != n.ExpectedError {
				t.Fatalf("expected %s, got %v", n.ExpectedError, err)
			}
		})
	}
}
