// Lumos - protocol/match / The consent-stage payload codecs.
// Author: dhar.abhishek.email@gmail.com
// Implements: SRS-MAT-001, SRS-MAT-002, SRS-MAT-004, SRS-MAT-009
// ADRs: ADR-002
// Threading: any
// Notes: Two tiny envelopes with one loud property: the response verdict
// alphabet is ACCEPT and UNMATCH, so a decline or ignore is UNENCODABLE -
// SRS-MAT-004's silence enforced at the codec, not remembered at call
// sites. The request envelope is content-opaque: a version byte, a u16
// length, and the sender's request-stage preview blob exactly as the app's
// profile codec emits it for the REQUESTER audience (SRS-MAT-002); the
// requester's identity rides the authenticated session, never the payload.
// Decode errors carry the stable ERR_MATCH_* codes.

package match

import (
	"encoding/binary"
	"fmt"

	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumoserrors"
)

// Verdict is the response alphabet. There is no decline on purpose.
type Verdict byte

// The encodable verdicts.
const (
	// VerdictAccept answers a request with mutual consent underway.
	VerdictAccept Verdict = 1
	// VerdictUnmatch is the one deliberately visible ending (SRS-MAT-009).
	VerdictUnmatch Verdict = 2
)

const (
	requestVersion  = 1
	responseVersion = 1
)

// WireError is a stable-coded consent-stage decode failure.
type WireError struct {
	Code string
	Why  string
}

func (e *WireError) Error() string {
	return fmt.Sprintf("match wire: %s (%s)", e.Why, e.Code)
}

// EncodeRequest builds a class-3 payload around the preview blob.
func EncodeRequest(preview []byte) ([]byte, error) {
	if len(preview) > 0xFFFF {
		return nil, &WireError{lumoserrors.ErrMatchBadPayload,
			"preview longer than the length prefix can carry"}
	}
	out := make([]byte, 3+len(preview))
	out[0] = requestVersion
	// #nosec G115 -- len(preview) is bounded above by the 0xFFFF check
	binary.BigEndian.PutUint16(out[1:3], uint16(len(preview)))
	copy(out[3:], preview)
	return out, nil
}

// DecodeRequest opens a class-3 payload and returns the preview blob.
func DecodeRequest(payload []byte) ([]byte, error) {
	if len(payload) < 3 {
		return nil, &WireError{lumoserrors.ErrMatchBadPayload, "truncated request"}
	}
	if payload[0] != requestVersion {
		return nil, &WireError{lumoserrors.ErrMatchBadPayload,
			fmt.Sprintf("unknown request version %d", payload[0])}
	}
	want := int(binary.BigEndian.Uint16(payload[1:3]))
	if len(payload) != 3+want {
		return nil, &WireError{lumoserrors.ErrMatchBadPayload,
			"length prefix disagrees with the bytes"}
	}
	preview := make([]byte, want)
	copy(preview, payload[3:])
	return preview, nil
}

// EncodeResponse builds a class-4 payload. The type system already keeps a
// decline out; the range check keeps a corrupted caller out too.
func EncodeResponse(verdict Verdict) ([]byte, error) {
	if verdict != VerdictAccept && verdict != VerdictUnmatch {
		return nil, &WireError{lumoserrors.ErrMatchBadVerdict,
			fmt.Sprintf("verdict %d is not encodable", verdict)}
	}
	return []byte{responseVersion, byte(verdict)}, nil
}

// DecodeResponse opens a class-4 payload.
func DecodeResponse(payload []byte) (Verdict, error) {
	if len(payload) != 2 {
		return 0, &WireError{lumoserrors.ErrMatchBadPayload, "response must be two bytes"}
	}
	if payload[0] != responseVersion {
		return 0, &WireError{lumoserrors.ErrMatchBadPayload,
			fmt.Sprintf("unknown response version %d", payload[0])}
	}
	v := Verdict(payload[1])
	if v != VerdictAccept && v != VerdictUnmatch {
		return 0, &WireError{lumoserrors.ErrMatchBadVerdict,
			fmt.Sprintf("verdict %d is outside the alphabet", payload[1])}
	}
	return v, nil
}
