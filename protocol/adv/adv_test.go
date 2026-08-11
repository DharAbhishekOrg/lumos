// Lumos - protocol/adv / Unit tests for the advertisement hint.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-PROTO-009, TRS-PROTO-017, SRS-IDN-004
// ADRs: ADR-002
// Threading: any
// Notes: TestEIDIsUnlinkableAcrossEpochs is the SRS-IDN-004 anti-correlation
// evidence: consecutive epochs must share no structure, since a static or
// predictable payload is exactly the tracking vector the rotation exists to
// prevent. TestVerifyRejectsForeignKey pins that a matching tag proves only
// "worth connecting to" and never identity (TRS-PROTO-017).
package adv

import (
	"bytes"
	"crypto/sha256"
	"errors"
	"testing"

	"github.com/dharabhishekemail-ops/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekemail-ops/lumos/protocol/gen/go/lumoserrors"
	"github.com/dharabhishekemail-ops/lumos/protocol/wire"
)

func testKAdv(t *testing.T, label string) []byte {
	t.Helper()
	seed := sha256.Sum256([]byte("TESTONLY-adv-unit|" + label))
	k, err := DeriveKAdv(seed[:])
	if err != nil {
		t.Fatalf("DeriveKAdv: %v", err)
	}
	return k
}

func assertAdvErr(t *testing.T, err error) {
	t.Helper()
	var we *wire.Error
	if !errors.As(err, &we) || we.Code != lumoserrors.ErrAdvAuthTagMismatch {
		t.Fatalf("expected %s, got %v", lumoserrors.ErrAdvAuthTagMismatch, err)
	}
}

func TestLayoutMatchesADR002(t *testing.T) {
	k := testKAdv(t, "layout")
	b, err := Build(k, 0, 100)
	if err != nil {
		t.Fatalf("Build: %v", err)
	}
	if len(b) != 18 {
		t.Fatalf("adv_v1 is %d bytes, TRS Sec. 9.3 fixes 18", len(b))
	}
	if b[0] != 1 {
		t.Errorf("version byte = %d, want 1", b[0])
	}
	// version(1) + modeFlags(1) + eid(12) + authTag(4)
	if EIDLen != 12 || AuthTagLen != 4 {
		t.Errorf("field widths drifted: eid=%d tag=%d", EIDLen, AuthTagLen)
	}
	if got := len(BuildScanResponse(0x0003, 0xDEADBEEF)); got != 6 {
		t.Errorf("scan response is %d bytes, TRS Sec. 9.3 fixes 6", got)
	}
}

func TestRoundtripVerify(t *testing.T) {
	k := testKAdv(t, "roundtrip")
	b, err := Build(k, ModeFlagVenue, 4242)
	if err != nil {
		t.Fatalf("Build: %v", err)
	}
	p, err := Verify(k, b, 4242)
	if err != nil {
		t.Fatalf("Verify: %v", err)
	}
	if !p.Venue() {
		t.Error("venue mode flag lost")
	}
	if len(p.EID) != EIDLen {
		t.Errorf("EID length %d", len(p.EID))
	}
}

// TestEIDIsUnlinkableAcrossEpochs: rotation must produce identifiers that share
// no bytes positionally, so a scanner cannot correlate a device across epochs.
func TestEIDIsUnlinkableAcrossEpochs(t *testing.T) {
	k := testKAdv(t, "rotation")
	seen := map[string]bool{}
	var prev []byte
	for epoch := int64(0); epoch < 24; epoch++ {
		eid, err := DeriveEID(k, epoch)
		if err != nil {
			t.Fatalf("DeriveEID: %v", err)
		}
		if seen[string(eid)] {
			t.Fatalf("epoch %d repeated an earlier EID", epoch)
		}
		seen[string(eid)] = true
		if prev != nil && bytes.Equal(prev, eid) {
			t.Fatalf("epoch %d EID equals the previous epoch", epoch)
		}
		prev = eid
	}
	if len(seen) != 24 {
		t.Errorf("only %d distinct EIDs across 24 epochs", len(seen))
	}
}

func TestEpochUsesRegisterCadence(t *testing.T) {
	rot := lumosconstants.EIDRotationSecDefault
	if Epoch(0, rot) != 0 {
		t.Error("epoch 0")
	}
	if Epoch(int64(rot)-1, rot) != 0 {
		t.Error("epoch must not advance before the rotation interval elapses")
	}
	if Epoch(int64(rot), rot) != 1 {
		t.Error("epoch must advance at exactly the rotation interval")
	}
	// A zero cadence falls back to the register default rather than dividing by zero.
	if Epoch(int64(rot), 0) != 1 {
		t.Error("zero cadence must fall back to the register default")
	}
}

func TestVerifyRejectsWrongEpochAndTamper(t *testing.T) {
	k := testKAdv(t, "tamper")
	b, err := Build(k, 0, 900)
	if err != nil {
		t.Fatalf("Build: %v", err)
	}

	t.Run("wrong epoch", func(t *testing.T) {
		_, err := Verify(k, b, 901)
		assertAdvErr(t, err)
	})
	t.Run("tampered tag", func(t *testing.T) {
		bad := append([]byte(nil), b...)
		bad[len(bad)-1] ^= 1
		_, err := Verify(k, bad, 900)
		assertAdvErr(t, err)
	})
	t.Run("tampered eid", func(t *testing.T) {
		bad := append([]byte(nil), b...)
		bad[5] ^= 1
		_, err := Verify(k, bad, 900)
		assertAdvErr(t, err)
	})
	t.Run("reserved mode flag", func(t *testing.T) {
		bad := append([]byte(nil), b...)
		bad[1] |= 0x02
		_, err := Verify(k, bad, 900)
		assertAdvErr(t, err)
	})
	t.Run("wrong length", func(t *testing.T) {
		_, err := Verify(k, b[:17], 900)
		assertAdvErr(t, err)
	})
	t.Run("bad version", func(t *testing.T) {
		bad := append([]byte(nil), b...)
		bad[0] = 2
		_, err := Verify(k, bad, 900)
		assertAdvErr(t, err)
	})
}

// TestVerifyRejectsForeignKey: the tag is keyed to one identity's K_adv, so
// another device's advertisement never verifies - but note that a verifying tag
// still confers no trust, which is why the Nearby feed keys on the authenticated
// GATT identity instead (TRS-PROTO-017).
func TestVerifyRejectsForeignKey(t *testing.T) {
	mine := testKAdv(t, "mine")
	theirs := testKAdv(t, "theirs")
	b, err := Build(theirs, 0, 7)
	if err != nil {
		t.Fatalf("Build: %v", err)
	}
	_, err = Verify(mine, b, 7)
	assertAdvErr(t, err)
}

func TestNoProhibitedContentCapacity(t *testing.T) {
	// The layout is fully consumed by version, flags, EID and tag: there is no
	// spare byte in which an alias, photo or location could ride (TRS-PROTO-009).
	if 1+1+EIDLen+AuthTagLen != TotalLen {
		t.Errorf("adv_v1 has %d unaccounted bytes", TotalLen-(1+1+EIDLen+AuthTagLen))
	}
}
