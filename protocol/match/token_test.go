// Lumos - protocol/match / The token derivation, pinned.
// Author: dhar.abhishek.email@gmail.com
// Implements: SRS-MAT-008, TRS-TST-001
// ADRs: ADR-005, ADR-009
// Threading: test-only
// Notes: Determinism, range, refusal, and the one property the venue scene
// depends on: the token is a pure function of the match identifier, so the
// two sides need no message to agree.

package match

import (
	"bytes"
	"testing"

	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
)

func TestTheTokenIsAPureFunctionOfTheMatchIdentifier(t *testing.T) {
	a := bytes.Repeat([]byte{0x11}, PeerIDLen)
	b := bytes.Repeat([]byte{0x22}, PeerIDLen)
	id, err := ID(a, b)
	if err != nil {
		t.Fatal(err)
	}
	one, err := DeriveToken(id)
	if err != nil {
		t.Fatal(err)
	}
	two, err := DeriveToken(id)
	if err != nil {
		t.Fatal(err)
	}
	if one != two {
		t.Fatal("two derivations of one identifier disagreed")
	}
	for _, idx := range one.EmojiIndices {
		if idx < 0 || idx >= len(lumosconstants.SafetyNumberEmojiAlphabetGlyphs) {
			t.Fatalf("emoji index %d outside the frozen alphabet", idx)
		}
	}
	if one.ColorIndex < 0 || one.ColorIndex >= len(lumosconstants.MatchTokenColorsNames) {
		t.Fatalf("colour index %d outside the palette", one.ColorIndex)
	}
	if one.ColorName() == "" || one.ColorHex() == "" || one.Emoji()[0] == "" {
		t.Fatal("the accessible channels must all be present")
	}
}

func TestAMalformedIdentifierIsRefused(t *testing.T) {
	if _, err := DeriveToken([]byte{1, 2, 3}); err == nil {
		t.Fatal("a short match id must be refused")
	}
}
