// Lumos - protocol/match / The committed timelines, replayed.
// Author: dhar.abhishek.email@gmail.com
// Implements: SRS-MAT-004, SRS-MAT-005, SRS-MAT-007, SRS-MAT-014, TRS-TST-001
// ADRs: ADR-002, ADR-008
// Threading: test-only
// Notes: The other half of the generator's self-check: this consumer reads
// the COMMITTED file, so a hand-edited vector, a stale constants_hash or a
// machine drift all fail here. Two family-level properties are asserted
// over the whole file, not per script, because that is where they live: no
// step whose event is a decline or ignore carries a transmitting effect
// anywhere in the family (SRS-MAT-004), and the only unlocking effects
// follow acceptance events (SRS-MAT-005).

package match

import (
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/dharabhishekorg/lumos/protocol/schema"
)

type matchVectorFile struct {
	ConstantsHash string `json:"constants_hash"`
	Derivations   []struct {
		Name         string `json:"name"`
		PeerAHex     string `json:"peer_a_hex"`
		PeerBHex     string `json:"peer_b_hex"`
		MatchIDHex   string `json:"match_id_hex"`
		InitiatorHex string `json:"initiator_on_race_hex"`
	} `json:"derivations"`
	Tokens []struct {
		Name         string    `json:"name"`
		MatchIDHex   string    `json:"match_id_hex"`
		EmojiIndices [2]int    `json:"emoji_indices"`
		ColorIndex   int       `json:"color_index"`
		Emoji        [2]string `json:"emoji"`
		ColorName    string    `json:"color_name"`
		ColorHex     string    `json:"color_hex"`
	} `json:"tokens"`
	Scripts []struct {
		Name  string `json:"name"`
		Steps []struct {
			Event        string `json:"event"`
			ExpectState  string `json:"expect_state"`
			ExpectEffect string `json:"expect_effect"`
		} `json:"steps"`
	} `json:"scripts"`
}

var vectorEventByName = map[string]Event{
	"local_request": LocalRequest, "local_accept": LocalAccept,
	"local_decline": LocalDecline, "local_ignore": LocalIgnore,
	"local_block": LocalBlock, "local_unmatch": LocalUnmatch,
	"remote_request": RemoteRequest, "remote_accept": RemoteAccept,
	"remote_unmatch": RemoteUnmatch,
}

var vectorStateName = map[State]string{
	None: "none", RequestedOut: "requested_out", RequestedIn: "requested_in",
	BothRequested: "both_requested", Matched: "matched", DeclinedIn: "declined_in",
	IgnoredIn: "ignored_in", Unmatched: "unmatched", Blocked: "blocked",
}

var vectorEffectName = map[Effect]string{
	EffectNone: "none", EffectSendRequest: "send_request",
	EffectAcceptAndEstablish: "accept_and_establish",
	EffectMatchEstablished:   "match_established",
	EffectSendUnmatch:        "send_unmatch",
}

func loadMatchVectors(t *testing.T) matchVectorFile {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("..", "..",
		"test-vectors", "matches", "match_timelines.json"))
	if err != nil {
		t.Fatalf("committed vector file must exist: %v", err)
	}
	var file matchVectorFile
	if err := json.Unmarshal(raw, &file); err != nil {
		t.Fatalf("unparseable vector file: %v", err)
	}
	reg, err := schema.LoadRegister(filepath.Join("..", ".."))
	if err != nil {
		t.Fatal(err)
	}
	declared, err := reg.String("constants_hash")
	if err != nil {
		t.Fatal(err)
	}
	if file.ConstantsHash != declared {
		t.Fatal("vector constants_hash does not match the register - stale vectors (TRS-CONST-005)")
	}
	if len(file.Derivations) == 0 || len(file.Scripts) == 0 {
		t.Fatal("a match family without derivations and scripts proves nothing")
	}
	return file
}

func TestCommittedDerivationsReproduceByteForByte(t *testing.T) {
	file := loadMatchVectors(t)
	for _, d := range file.Derivations {
		a, err := hex.DecodeString(d.PeerAHex)
		if err != nil {
			t.Fatal(err)
		}
		b, err := hex.DecodeString(d.PeerBHex)
		if err != nil {
			t.Fatal(err)
		}
		id, err := ID(a, b)
		if err != nil {
			t.Fatalf("%s: %v", d.Name, err)
		}
		if hex.EncodeToString(id) != d.MatchIDHex {
			t.Errorf("%s: identifier mismatch", d.Name)
		}
		if hex.EncodeToString(InitiatorOnRace(a, b)) != d.InitiatorHex {
			t.Errorf("%s: tiebreak mismatch", d.Name)
		}
	}
}

func TestCommittedTokensReproduceExactly(t *testing.T) {
	file := loadMatchVectors(t)
	if len(file.Tokens) == 0 {
		t.Fatal("a token section with no cases proves nothing")
	}
	for _, c := range file.Tokens {
		id, err := hex.DecodeString(c.MatchIDHex)
		if err != nil {
			t.Fatal(err)
		}
		token, err := DeriveToken(id)
		if err != nil {
			t.Fatalf("%s: %v", c.Name, err)
		}
		if token.EmojiIndices != c.EmojiIndices || token.ColorIndex != c.ColorIndex {
			t.Errorf("%s: indices mismatch", c.Name)
		}
		if token.Emoji() != c.Emoji || token.ColorName() != c.ColorName ||
			token.ColorHex() != c.ColorHex {
			t.Errorf("%s: rendered channels mismatch", c.Name)
		}
	}
}

func TestCommittedTimelinesReplayExactly(t *testing.T) {
	file := loadMatchVectors(t)
	for _, script := range file.Scripts {
		state := None
		for i, step := range script.Steps {
			event, ok := vectorEventByName[step.Event]
			if !ok {
				t.Fatalf("%s step %d: unknown event %q", script.Name, i, step.Event)
			}
			next, effect := Step(state, event)
			if vectorStateName[next] != step.ExpectState {
				t.Errorf("%s step %d: state %s, vector %s",
					script.Name, i, vectorStateName[next], step.ExpectState)
			}
			if vectorEffectName[effect] != step.ExpectEffect {
				t.Errorf("%s step %d: effect %s, vector %s",
					script.Name, i, vectorEffectName[effect], step.ExpectEffect)
			}
			state = next
		}
	}
}

func TestTheFamilyItselfKeepsTheSilenceAndTheSingleDoor(t *testing.T) {
	file := loadMatchVectors(t)
	for _, script := range file.Scripts {
		for i, step := range script.Steps {
			quiet := step.Event == "local_decline" || step.Event == "local_ignore"
			if quiet && step.ExpectEffect != "none" {
				t.Errorf("%s step %d: a decline/ignore step carries effect %s "+
					"(SRS-MAT-004)", script.Name, i, step.ExpectEffect)
			}
			unlocking := strings.Contains(step.ExpectEffect, "establish")
			accepting := step.Event == "local_accept" || step.Event == "remote_accept"
			if unlocking && !accepting {
				t.Errorf("%s step %d: effect %s without an acceptance (SRS-MAT-005)",
					script.Name, i, step.ExpectEffect)
			}
		}
	}
}
