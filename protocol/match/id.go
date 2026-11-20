// Lumos - protocol/match / The stable match identifier and the race tiebreak.
// Author: dhar.abhishek.email@gmail.com
// Implements: SRS-MAT-007, SRS-MAT-014
// ADRs: ADR-008
// Threading: any
// Notes: One derivation settles two problems. The identifier is
// SHA-256(MATCH_ID_KDF_INFO | lower | higher) over the two PEER_ID
// identifiers in lexicographic byte order, so it is symmetric: both sides of
// an accept-accept race compute the SAME identifier without exchanging a
// byte, which is how "one match, one ID" survives the race (the EDP
// guardrail). And it is independent of every rotating EID by construction,
// because its inputs are the identity-derived peer identifiers and nothing
// else (SRS-MAT-007). The same ordering is the tiebreak: when both sides
// requested before either accepted, the lexicographically lower identifier
// acts as session initiator - deterministic, unforgeable short of owning a
// different identity key, and needing no negotiation message.

package match

import (
	"bytes"
	"crypto/sha256"
	"fmt"

	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
)

// PeerIDLen is the length both inputs must have.
const PeerIDLen = lumosconstants.PeerIDLenDefault

// ID derives the stable match identifier for a peer pair. Order of the
// arguments does not matter; that is the point.
func ID(a, b []byte) ([]byte, error) {
	if len(a) != PeerIDLen || len(b) != PeerIDLen {
		return nil, fmt.Errorf("match: peer ids must be %d bytes, got %d and %d",
			PeerIDLen, len(a), len(b))
	}
	if bytes.Equal(a, b) {
		return nil, fmt.Errorf("match: a match needs two distinct peers")
	}
	lower, higher := a, b
	if bytes.Compare(b, a) < 0 {
		lower, higher = b, a
	}
	h := sha256.New()
	h.Write([]byte(lumosconstants.MatchIDKdfInfoValue))
	h.Write(lower)
	h.Write(higher)
	return h.Sum(nil)[:lumosconstants.MatchIDLenDefault], nil
}

// InitiatorOnRace names which of the two peers acts as session initiator
// when both requested before either accepted: the lexicographically lower
// identifier.
func InitiatorOnRace(a, b []byte) []byte {
	if bytes.Compare(b, a) < 0 {
		return b
	}
	return a
}
