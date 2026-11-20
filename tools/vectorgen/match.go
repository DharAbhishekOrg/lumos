// Lumos - tools/vectorgen / The match-domain vector family.
// Author: dhar.abhishek.email@gmail.com
// Implements: SRS-MAT-004, SRS-MAT-005, SRS-MAT-007, SRS-MAT-014, TRS-PAT-002,
//             TRS-TST-001
// ADRs: ADR-002, ADR-008
// Threading: build-time
// Notes: Two sections. derivations pin the SRS-MAT-007 identifier bytes and
// the race tiebreak for committed peer-id pairs (TESTONLY provenance, like
// every input in this suite). scripts are event timelines against the
// reference machine with every step's state and effect pinned in a stable
// string vocabulary - the platform mirrors replay them, which is what makes
// the transition table a frozen encoding under TRS Sec. 19 rather than a Go
// implementation detail. Every expectation is executed against the
// reference before emission, so a wrong intention cannot be committed.

package main

import (
	"encoding/hex"
	"fmt"

	"github.com/dharabhishekorg/lumos/protocol/match"
)

// matchDerivation pins one identifier derivation and its tiebreak.
type matchDerivation struct {
	Name         string `json:"name"`
	PeerAHex     string `json:"peer_a_hex"`
	PeerBHex     string `json:"peer_b_hex"`
	MatchIDHex   string `json:"match_id_hex"`
	InitiatorHex string `json:"initiator_on_race_hex"`
}

// matchStep is one event applied to the machine, with the pinned outcome.
type matchStep struct {
	Event        string `json:"event"`
	ExpectState  string `json:"expect_state"`
	ExpectEffect string `json:"expect_effect"`
}

// matchScript is one machine timeline, always starting at none.
type matchScript struct {
	Name        string      `json:"name"`
	Description string      `json:"description"`
	Steps       []matchStep `json:"steps"`
}

// matchVectorsFile is the emitted matches/match_timelines.json.
type matchVectorsFile struct {
	fileMeta
	Derivations []matchDerivation `json:"derivations"`
	Scripts     []matchScript     `json:"scripts"`
}

// implementsIDs echoes fileMeta for the vectorDoc registration.
func (f *matchVectorsFile) implementsIDs() []string { return f.Implements }

// The stable vocabulary the platforms parse. Kept here, beside the vectors
// that use it, and mapped 1:1 onto the reference enums.
var matchStateNames = map[match.State]string{
	match.None: "none", match.RequestedOut: "requested_out",
	match.RequestedIn: "requested_in", match.BothRequested: "both_requested",
	match.Matched: "matched", match.DeclinedIn: "declined_in",
	match.IgnoredIn: "ignored_in", match.Unmatched: "unmatched",
	match.Blocked: "blocked",
}

var matchEventNames = map[string]match.Event{
	"local_request": match.LocalRequest, "local_accept": match.LocalAccept,
	"local_decline": match.LocalDecline, "local_ignore": match.LocalIgnore,
	"local_block": match.LocalBlock, "local_unmatch": match.LocalUnmatch,
	"remote_request": match.RemoteRequest, "remote_accept": match.RemoteAccept,
	"remote_unmatch": match.RemoteUnmatch,
}

var matchEffectNames = map[match.Effect]string{
	match.EffectNone: "none", match.EffectSendRequest: "send_request",
	match.EffectAcceptAndEstablish: "accept_and_establish",
	match.EffectMatchEstablished:   "match_established",
	match.EffectSendUnmatch:        "send_unmatch",
}

// runMatchScript replays a script against the reference and verifies every
// pinned outcome.
func runMatchScript(s matchScript) error {
	state := match.None
	for i, step := range s.Steps {
		event, ok := matchEventNames[step.Event]
		if !ok {
			return fmt.Errorf("script %s step %d: unknown event %q", s.Name, i, step.Event)
		}
		next, effect := match.Step(state, event)
		if matchStateNames[next] != step.ExpectState {
			return fmt.Errorf("script %s step %d: reference went to %s, vector says %s",
				s.Name, i, matchStateNames[next], step.ExpectState)
		}
		if matchEffectNames[effect] != step.ExpectEffect {
			return fmt.Errorf("script %s step %d: reference effect %s, vector says %s",
				s.Name, i, matchEffectNames[effect], step.ExpectEffect)
		}
		state = next
	}
	return nil
}

// buildMatchVectors emits matches/match_timelines.json.
func buildMatchVectors(constantsHash string) (*matchVectorsFile, error) {
	file := &matchVectorsFile{
		fileMeta: fileMeta{
			SchemaVersion: 1, Generator: GeneratorName, ConstantsHash: constantsHash,
			Implements:    []string{"SRS-MAT-004", "SRS-MAT-005", "SRS-MAT-007", "SRS-MAT-014", "TRS-PAT-002"},
			KeyProvenance: KeyProvenance,
			Notes: "The interest/consent state machine and the SRS-MAT-007 identifier. " +
				"derivations: match_id = SHA-256(MATCH_ID_KDF_INFO | lower | higher)" +
				"[:MATCH_ID_LEN] over the two peer ids in lexicographic byte order - " +
				"symmetric, so both sides of an accept-accept race derive the same id " +
				"with no message; initiator_on_race is the lower id. scripts: event " +
				"timelines starting at state none; consumers replay each and compare " +
				"state and effect after every step. Effects accept_and_establish and " +
				"match_established are the ONLY unlocking effects (SRS-MAT-005); no " +
				"decline or ignore step anywhere carries a transmitting effect " +
				"(SRS-MAT-004); absorbed duplicates and reorders appear as steps whose " +
				"state and effect repeat unchanged (SRS-MAT-014).",
		},
	}

	for i := 0; i < 4; i++ {
		a := testonlyBytes(fmt.Sprintf("match/peer-a/%d", i), match.PeerIDLen)
		b := testonlyBytes(fmt.Sprintf("match/peer-b/%d", i), match.PeerIDLen)
		id, err := match.ID(a, b)
		if err != nil {
			return nil, fmt.Errorf("match derivation %d: %w", i, err)
		}
		swapped, err := match.ID(b, a)
		if err != nil {
			return nil, fmt.Errorf("match derivation %d swapped: %w", i, err)
		}
		if hex.EncodeToString(id) != hex.EncodeToString(swapped) {
			return nil, fmt.Errorf("match derivation %d: asymmetric identifier", i)
		}
		file.Derivations = append(file.Derivations, matchDerivation{
			Name:         fmt.Sprintf("pair_%d", i),
			PeerAHex:     hex.EncodeToString(a),
			PeerBHex:     hex.EncodeToString(b),
			MatchIDHex:   hex.EncodeToString(id),
			InitiatorHex: hex.EncodeToString(match.InitiatorOnRace(a, b)),
		})
	}

	step := func(event, state, effect string) matchStep {
		return matchStep{Event: event, ExpectState: state, ExpectEffect: effect}
	}
	file.Scripts = []matchScript{
		{
			Name:        "request_then_remote_accept",
			Description: "The plain happy path from the requester's side.",
			Steps: []matchStep{
				step("local_request", "requested_out", "send_request"),
				step("remote_accept", "matched", "match_established"),
				step("remote_accept", "matched", "none"),
			},
		},
		{
			Name:        "remote_request_then_local_accept",
			Description: "The plain happy path from the recipient's side.",
			Steps: []matchStep{
				step("remote_request", "requested_in", "none"),
				step("remote_request", "requested_in", "none"),
				step("local_accept", "matched", "accept_and_establish"),
			},
		},
		{
			Name: "decline_is_silent_and_not_final",
			Description: "A decline transmits nothing, leaves the asker's view " +
				"unchanged, and a genuinely new request may try again.",
			Steps: []matchStep{
				step("remote_request", "requested_in", "none"),
				step("local_decline", "declined_in", "none"),
				step("remote_request", "requested_in", "none"),
				step("local_ignore", "ignored_in", "none"),
				step("remote_request", "ignored_in", "none"),
			},
		},
		{
			Name: "accept_accept_race_local_side",
			Description: "Both requested before either answered; our acceptance " +
				"lands first on our side.",
			Steps: []matchStep{
				step("local_request", "requested_out", "send_request"),
				step("remote_request", "both_requested", "none"),
				step("local_accept", "matched", "accept_and_establish"),
				step("remote_accept", "matched", "none"),
			},
		},
		{
			Name: "accept_accept_race_remote_side",
			Description: "The same race seen from the peer whose acceptance was " +
				"still in flight.",
			Steps: []matchStep{
				step("local_request", "requested_out", "send_request"),
				step("remote_request", "both_requested", "none"),
				step("remote_accept", "matched", "match_established"),
				step("local_accept", "matched", "none"),
			},
		},
		{
			Name: "declining_the_race_keeps_our_request",
			Description: "Declining THEIR request in the race does not retract " +
				"ours, and their later acceptance still matches.",
			Steps: []matchStep{
				step("local_request", "requested_out", "send_request"),
				step("remote_request", "both_requested", "none"),
				step("local_decline", "requested_out", "none"),
				step("remote_accept", "matched", "match_established"),
			},
		},
		{
			Name: "unmatch_then_dead_echoes",
			Description: "After an unmatch, replayed accepts and unmatches from " +
				"the dead match are absorbed; a fresh round may start.",
			Steps: []matchStep{
				step("remote_request", "requested_in", "none"),
				step("local_accept", "matched", "accept_and_establish"),
				step("local_unmatch", "unmatched", "send_unmatch"),
				step("remote_accept", "unmatched", "none"),
				step("remote_unmatch", "unmatched", "none"),
				step("remote_request", "requested_in", "none"),
			},
		},
		{
			Name:        "block_absorbs_everything",
			Description: "Blocking wins from anywhere and nothing leaves it here.",
			Steps: []matchStep{
				step("remote_request", "requested_in", "none"),
				step("local_block", "blocked", "none"),
				step("remote_request", "blocked", "none"),
				step("remote_accept", "blocked", "none"),
				step("local_request", "blocked", "none"),
			},
		},
		{
			Name: "reorder_accept_before_any_request",
			Description: "A remote accept with nothing outstanding is absorbed, " +
				"never trusted.",
			Steps: []matchStep{
				step("remote_accept", "none", "none"),
				step("local_request", "requested_out", "send_request"),
			},
		},
	}

	for _, s := range file.Scripts {
		if err := runMatchScript(s); err != nil {
			return nil, err
		}
	}
	return file, nil
}
