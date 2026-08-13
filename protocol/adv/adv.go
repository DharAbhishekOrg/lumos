// Lumos - protocol/adv / BLE advertisement hint: EID schedule and authTag (ADR-002).
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-PROTO-009, TRS-PROTO-017, TRS-PROTO-018, SRS-IDN-004
// ADRs: ADR-002
// Threading: any
// Notes: THIS PAYLOAD CARRIES NO TRUST. The authTag is cheap pre-connection
// noise rejection on Android only; a matching tag means "worth connecting to",
// never "authenticated peer". Canonical identity, capabilities, version and
// venue context are re-established inside the authenticated GATT HELLO
// (TRS-PROTO-017), and presence dedup keys on that authenticated identity - so a
// hintless peer (every iOS peer, and any Android peer in degraded mode) behaves
// identically in the Nearby feed. Epoch arithmetic is pure: the caller supplies
// unix seconds, this package never reads a clock.
package adv

import (
	"crypto/hkdf"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/binary"

	"github.com/dharabhishekemail-ops/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekemail-ops/lumos/protocol/gen/go/lumoserrors"
	"github.com/dharabhishekemail-ops/lumos/protocol/wire"
)

// Layout sizes, all from the register (TRS Sec. 9.3).
const (
	TotalLen        = lumosconstants.AdvV1LayoutTotalLen
	EIDLen          = lumosconstants.AdvV1LayoutEIDLen
	AuthTagLen      = lumosconstants.AdvAuthTagLenDefault
	ScanResponseLen = lumosconstants.AdvV1ScanResponseLayoutTotalLen
)

// Mode flag bits carried in adv_v1.mode_flags. Bit 0 marks venue mode; the rest
// are reserved and MUST be zero, so an unexpected bit is a decode rejection
// rather than a silently-ignored capability claim.
const (
	ModeFlagVenue        = 0x01
	ModeFlagReservedMask = 0xFE
)

// Epoch returns the EID rotation epoch for a unix timestamp. Rotation cadence
// comes from the register, so a signed-config change to EID_ROTATION_SEC moves
// the whole schedule coherently (SRS-IDN-004).
func Epoch(unixSeconds int64, rotationSec int) int64 {
	if rotationSec <= 0 {
		rotationSec = lumosconstants.EIDRotationSecDefault
	}
	return unixSeconds / int64(rotationSec)
}

// DeriveKAdv derives the per-identity advertisement key from a device's
// long-term advertisement secret. K_adv never leaves the device.
func DeriveKAdv(advSecret []byte) ([]byte, error) {
	k, err := hkdf.Key(sha256.New, advSecret, make([]byte, 32), lumosconstants.AdvKeyKdfInfoValue, 32)
	if err != nil {
		return nil, &wire.Error{Code: lumoserrors.ErrAdvAuthTagMismatch, Detail: "kadv derivation failed"}
	}
	return k, nil
}

// DeriveEID returns the rotating ephemeral identifier for an epoch. Distinct
// epochs yield unlinkable identifiers, which is what stops correlation through
// a static advertisement payload (SRS-IDN-004).
func DeriveEID(kAdv []byte, epoch int64) ([]byte, error) {
	var e [8]byte
	// #nosec G115 -- the conversion IS the serialisation: the epoch is written as
	// eight bytes big-endian two's-complement, which is what both platforms and
	// the adv golden vectors reproduce. Nothing here interprets the result as a
	// magnitude, so there is no overflow to guard against.
	binary.BigEndian.PutUint64(e[:], uint64(epoch))
	info := append([]byte(lumosconstants.AdvEIDKdfInfoValue), e[:]...)
	eid, err := hkdf.Key(sha256.New, kAdv, make([]byte, 32), string(info), EIDLen)
	if err != nil {
		return nil, &wire.Error{Code: lumoserrors.ErrAdvAuthTagMismatch, Detail: "eid derivation failed"}
	}
	return eid, nil
}

// AuthTag computes the advertisement tag: the first ADV_AUTH_TAG_LEN bytes of
// HMAC-SHA-256(K_adv, info | version | mode_flags | eid | epoch).
func AuthTag(kAdv []byte, version, modeFlags uint8, eid []byte, epoch int64) []byte {
	m := hmac.New(sha256.New, kAdv)
	m.Write([]byte(lumosconstants.AdvAuthTagInfoValue))
	m.Write([]byte{version, modeFlags})
	m.Write(eid)
	var e [8]byte
	// #nosec G115 -- see DeriveEID: the conversion is the eight-byte big-endian
	// serialisation of the epoch, not an arithmetic widening.
	binary.BigEndian.PutUint64(e[:], uint64(epoch))
	m.Write(e[:])
	return m.Sum(nil)[:AuthTagLen]
}

// Payload is a decoded adv_v1 service-data hint.
type Payload struct {
	Version   uint8
	ModeFlags uint8
	EID       []byte
	AuthTag   []byte
}

// Venue reports whether the venue mode flag is set.
func (p Payload) Venue() bool { return p.ModeFlags&ModeFlagVenue != 0 }

// Build assembles the 18-byte Android service-data hint for an epoch.
func Build(kAdv []byte, modeFlags uint8, epoch int64) ([]byte, error) {
	eid, err := DeriveEID(kAdv, epoch)
	if err != nil {
		return nil, err
	}
	version := uint8(1)
	tag := AuthTag(kAdv, version, modeFlags, eid, epoch)

	out := make([]byte, 0, TotalLen)
	out = append(out, version, modeFlags)
	out = append(out, eid...)
	out = append(out, tag...)
	if len(out) != TotalLen {
		return nil, &wire.Error{Code: lumoserrors.ErrAdvAuthTagMismatch, Detail: "layout width mismatch"}
	}
	return out, nil
}

// Parse decodes an adv_v1 payload without verifying it. Structure only: a
// caller must still Verify before treating the hint as worth connecting to,
// and must never treat a verified hint as an authenticated identity.
func Parse(b []byte) (*Payload, error) {
	if len(b) != TotalLen {
		return nil, &wire.Error{Code: lumoserrors.ErrAdvAuthTagMismatch, Detail: "advertisement length"}
	}
	p := &Payload{
		Version:   b[0],
		ModeFlags: b[1],
		EID:       append([]byte(nil), b[2:2+EIDLen]...),
		AuthTag:   append([]byte(nil), b[2+EIDLen:]...),
	}
	if p.Version != 1 {
		return nil, &wire.Error{Code: lumoserrors.ErrAdvAuthTagMismatch, Detail: "advertisement version"}
	}
	if p.ModeFlags&ModeFlagReservedMask != 0 {
		return nil, &wire.Error{Code: lumoserrors.ErrAdvAuthTagMismatch, Detail: "reserved mode flag"}
	}
	return p, nil
}

// Verify recomputes the tag for the given epoch and compares in constant time.
// A mismatch is discarded silently before any connection attempt; it is not a
// user-visible error (ADR-002).
func Verify(kAdv []byte, b []byte, epoch int64) (*Payload, error) {
	p, err := Parse(b)
	if err != nil {
		return nil, err
	}
	want := AuthTag(kAdv, p.Version, p.ModeFlags, p.EID, epoch)
	if !hmac.Equal(want, p.AuthTag) {
		return nil, &wire.Error{Code: lumoserrors.ErrAdvAuthTagMismatch, Detail: "advertisement discarded"}
	}
	return p, nil
}

// BuildScanResponse assembles the 6-byte Android scan response:
// capabilityBits(2) | venueHint(4).
func BuildScanResponse(capabilityBits uint16, venueHint uint32) []byte {
	out := make([]byte, ScanResponseLen)
	binary.BigEndian.PutUint16(out[0:2], capabilityBits)
	binary.BigEndian.PutUint32(out[2:6], venueHint)
	return out
}
