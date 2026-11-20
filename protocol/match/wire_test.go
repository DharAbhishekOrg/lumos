// Lumos - protocol/match / The consent envelopes, pinned.
// Author: dhar.abhishek.email@gmail.com
// Implements: SRS-MAT-002, SRS-MAT-004, SRS-MAT-009, TRS-TST-001
// ADRs: ADR-002
// Threading: test-only
// Notes: Round trips, refusals with their stable codes, and the property
// the layout exists for: every byte pair that decodes as a response is an
// accept or an unmatch - there is no input, of any length tried here, that
// decodes as a rejection, because no rejection exists to decode.

package match

import (
	"bytes"
	"errors"
	"testing"

	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumoserrors"
)

func TestRequestRoundTripsAndRefusesStructurally(t *testing.T) {
	preview := []byte("lumos-profile/1\nalias=Asha\n")
	payload, err := EncodeRequest(preview)
	if err != nil {
		t.Fatal(err)
	}
	got, err := DecodeRequest(payload)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(preview, got) {
		t.Fatal("round trip changed the preview")
	}

	cases := map[string][]byte{
		"truncated":        {1, 0},
		"wrong version":    {9, 0, 0},
		"length disagrees": {1, 0, 5, 'x'},
	}
	for name, bad := range cases {
		if _, err := DecodeRequest(bad); err == nil {
			t.Errorf("%s: decode must refuse", name)
		} else {
			var we *WireError
			if !errors.As(err, &we) || we.Code != lumoserrors.ErrMatchBadPayload {
				t.Errorf("%s: wrong code %v", name, err)
			}
		}
	}
}

func TestTheResponseAlphabetHoldsExactlyTwoWords(t *testing.T) {
	for _, v := range []Verdict{VerdictAccept, VerdictUnmatch} {
		payload, err := EncodeResponse(v)
		if err != nil {
			t.Fatal(err)
		}
		got, err := DecodeResponse(payload)
		if err != nil || got != v {
			t.Fatalf("verdict %d did not round trip", v)
		}
	}

	// Every other verdict byte refuses: no input decodes as a rejection,
	// because no rejection exists to decode (SRS-MAT-004).
	for b := 0; b <= 0xFF; b++ {
		if Verdict(b) == VerdictAccept || Verdict(b) == VerdictUnmatch {
			continue
		}
		if _, err := DecodeResponse([]byte{1, byte(b)}); err == nil {
			t.Fatalf("verdict byte %d decoded; the alphabet must hold two words", b)
		}
		if _, err := EncodeResponse(Verdict(b)); err == nil {
			t.Fatalf("verdict byte %d encoded; a decline must be unencodable", b)
		}
	}
	if _, err := DecodeResponse([]byte{1}); err == nil {
		t.Fatal("a truncated response must refuse")
	}
	if _, err := DecodeResponse([]byte{7, 1}); err == nil {
		t.Fatal("an unknown version must refuse")
	}
}
