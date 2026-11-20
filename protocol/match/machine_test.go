// Lumos - protocol/match / Every pair decided, every guardrail pinned.
// Author: dhar.abhishek.email@gmail.com
// Implements: SRS-MAT-003, SRS-MAT-004, SRS-MAT-005, SRS-MAT-014, TRS-PAT-002
// ADRs: ADR-002
// Threading: test-only
// Notes: The exhaustiveness test is the TRS-PAT-002 teeth: it walks the full
// state x event product and demands each pair be either a listed transition
// or a pair this file EXPLICITLY expects to absorb - so adding a state or
// event forces a decision here, in review, not a silent fallthrough. The
// silence test proves SRS-MAT-004 structurally: across the entire table, no
// decline or ignore event ever carries a sending effect. The race test runs
// both sides of accept-accept to one Matched state each and one shared
// identifier. The replay suite is SRS-MAT-014 as the EDP wrote it:
// duplicates, reorders, and accept echoes after unmatch all absorb.

package match

import (
	"bytes"
	"fmt"
	"testing"
)

// absorbing lists every state x event pair that deliberately changes
// nothing. The exhaustiveness walk fails on any pair that is neither here
// nor in the table.
var absorbing = map[State][]Event{
	None:          {LocalAccept, LocalDecline, LocalIgnore, LocalUnmatch, RemoteAccept, RemoteUnmatch},
	RequestedOut:  {LocalRequest, LocalAccept, LocalDecline, LocalIgnore, LocalUnmatch, RemoteUnmatch},
	RequestedIn:   {LocalUnmatch, RemoteRequest, RemoteAccept, RemoteUnmatch},
	BothRequested: {LocalRequest, LocalUnmatch, RemoteRequest, RemoteUnmatch},
	Matched:       {LocalRequest, LocalAccept, LocalDecline, LocalIgnore, RemoteRequest, RemoteAccept},
	DeclinedIn:    {LocalAccept, LocalDecline, LocalIgnore, LocalUnmatch, RemoteAccept, RemoteUnmatch},
	IgnoredIn:     {LocalAccept, LocalDecline, LocalIgnore, LocalUnmatch, RemoteRequest, RemoteAccept, RemoteUnmatch},
	Unmatched:     {LocalAccept, LocalDecline, LocalIgnore, LocalUnmatch, RemoteAccept, RemoteUnmatch},
	Blocked:       {LocalRequest, LocalAccept, LocalDecline, LocalIgnore, LocalBlock, LocalUnmatch, RemoteRequest, RemoteAccept, RemoteUnmatch},
}

func isAbsorbing(s State, e Event) bool {
	for _, a := range absorbing[s] {
		if a == e {
			return true
		}
	}
	return false
}

func TestEveryPairIsEitherATransitionOrADeliberateAbsorption(t *testing.T) {
	for _, s := range States() {
		for _, e := range Events() {
			next, effect := Step(s, e)
			moved := next != s || effect != EffectNone
			listed := isAbsorbing(s, e)
			if moved && listed {
				t.Errorf("state %d event %d is listed as absorbing but moved to %d/%d",
					s, e, next, effect)
			}
			if !moved && !listed {
				t.Errorf("state %d event %d absorbs but is not listed as deliberate:"+
					" decide it in review (TRS-PAT-002)", s, e)
			}
			if listed {
				if next != s || effect != EffectNone {
					t.Errorf("absorbing pair %d/%d changed something", s, e)
				}
			}
		}
	}
}

func TestDecliningAndIgnoringNeverTransmitAnything(t *testing.T) {
	for _, s := range States() {
		for _, e := range []Event{LocalDecline, LocalIgnore} {
			if _, effect := Step(s, e); effect != EffectNone {
				t.Errorf("state %d: a decline/ignore produced effect %d; "+
					"SRS-MAT-004 demands structural silence", s, effect)
			}
		}
	}
}

func TestTheOnlyDoorIsMutualAcceptance(t *testing.T) {
	for _, s := range States() {
		for _, e := range Events() {
			next, effect := Step(s, e)
			if effect.Establishes() && next != Matched {
				t.Errorf("state %d event %d unlocked without landing in Matched", s, e)
			}
			if effect.Establishes() && e != LocalAccept && e != RemoteAccept {
				t.Errorf("state %d event %d unlocked on something other than an "+
					"acceptance (SRS-MAT-005)", s, e)
			}
			if effect == EffectAcceptAndEstablish && e != LocalAccept {
				t.Errorf("state %d event %d transmits an acceptance nobody made", s, e)
			}
		}
	}
}

func TestTheAcceptAcceptRaceYieldsOneMatchAndOneIdentifier(t *testing.T) {
	idA := bytes.Repeat([]byte{0x11}, PeerIDLen)
	idB := bytes.Repeat([]byte{0xEE}, PeerIDLen)

	// Both sides request; each sees the other's request arrive.
	a, _ := Step(None, LocalRequest)
	b, _ := Step(None, LocalRequest)
	a, _ = Step(a, RemoteRequest)
	b, _ = Step(b, RemoteRequest)
	if a != BothRequested || b != BothRequested {
		t.Fatalf("race setup: %d/%d", a, b)
	}

	// A accepts locally; B hears the accept.
	a, effectA := Step(a, LocalAccept)
	b, effectB := Step(b, RemoteAccept)
	if a != Matched || b != Matched {
		t.Fatalf("race outcome: %d/%d", a, b)
	}
	if effectA != EffectAcceptAndEstablish || effectB != EffectMatchEstablished {
		t.Fatalf("race effects: %d/%d", effectA, effectB)
	}

	// One identifier, derived independently on each side.
	fromA, err := ID(idA, idB)
	if err != nil {
		t.Fatal(err)
	}
	fromB, err := ID(idB, idA)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(fromA, fromB) {
		t.Fatal("the two sides derived different match identifiers")
	}
	if !bytes.Equal(InitiatorOnRace(idA, idB), idA) {
		t.Fatal("the lexicographically lower identifier is the initiator")
	}

	// B's own acceptance still crossing the air is a duplicate: absorbed.
	if next, effect := Step(b, LocalAccept); next != Matched || effect != EffectNone {
		t.Fatalf("late own-accept must absorb, got %d/%d", next, effect)
	}
}

func TestDuplicatesReordersAndDeadEchoesAllAbsorb(t *testing.T) {
	cases := []struct {
		name  string
		state State
		event Event
	}{
		{"second remote request while pending", RequestedIn, RemoteRequest},
		{"replayed remote accept inside the match", Matched, RemoteAccept},
		{"accept echo after the match died", Unmatched, RemoteAccept},
		{"remote accept with nothing outstanding", None, RemoteAccept},
		{"unmatch replay after unmatch", Unmatched, RemoteUnmatch},
		{"anything at all while blocked", Blocked, RemoteRequest},
	}
	for _, c := range cases {
		next, effect := Step(c.state, c.event)
		if next != c.state || effect != EffectNone {
			t.Errorf("%s: wanted absorption, got %d/%d (SRS-MAT-014)",
				c.name, next, effect)
		}
	}
}

func TestDecliningInTheRaceLeavesOurOwnRequestStanding(t *testing.T) {
	next, effect := Step(BothRequested, LocalDecline)
	if next != RequestedOut || effect != EffectNone {
		t.Fatalf("got %d/%d: declining THEIR request must not retract OURS "+
			"and must not transmit", next, effect)
	}
}

func ExampleStep() {
	s, effect := Step(None, LocalRequest)
	fmt.Println(s == RequestedOut, effect == EffectSendRequest)
	// Output: true true
}
