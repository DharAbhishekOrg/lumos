// Lumos - protocol/match / The identifier derivation, pinned.
// Author: dhar.abhishek.email@gmail.com
// Implements: SRS-MAT-007, SRS-MAT-014
// ADRs: ADR-008
// Threading: test-only
// Notes: Symmetry, length, domain separation and the refusals. The EID
// independence needs no test here because the derivation never sees one -
// the inputs are the identity-derived peer identifiers, and that is checked
// by reading the signature.

package match

import (
	"bytes"
	"testing"

	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
)

func TestTheIdentifierIsSymmetricAndTheRightLength(t *testing.T) {
	a := bytes.Repeat([]byte{0x01}, PeerIDLen)
	b := bytes.Repeat([]byte{0x02}, PeerIDLen)
	ab, err := ID(a, b)
	if err != nil {
		t.Fatal(err)
	}
	ba, err := ID(b, a)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(ab, ba) {
		t.Fatal("argument order changed the identifier")
	}
	if len(ab) != lumosconstants.MatchIDLenDefault {
		t.Fatalf("length %d", len(ab))
	}
}

func TestDistinctPairsGetDistinctIdentifiers(t *testing.T) {
	a := bytes.Repeat([]byte{0x01}, PeerIDLen)
	b := bytes.Repeat([]byte{0x02}, PeerIDLen)
	c := bytes.Repeat([]byte{0x03}, PeerIDLen)
	ab, _ := ID(a, b)
	ac, _ := ID(a, c)
	if bytes.Equal(ab, ac) {
		t.Fatal("two different pairs derived the same identifier")
	}
}

func TestMalformedInputsAreRefused(t *testing.T) {
	a := bytes.Repeat([]byte{0x01}, PeerIDLen)
	if _, err := ID(a, a); err == nil {
		t.Fatal("a match with oneself must be refused")
	}
	if _, err := ID(a[:4], a); err == nil {
		t.Fatal("a short identifier must be refused")
	}
}
