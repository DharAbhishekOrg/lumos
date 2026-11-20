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
	"errors"
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

// matchToken pins one SRS-MAT-008 verification-token derivation.
type matchToken struct {
	Name         string    `json:"name"`
	MatchIDHex   string    `json:"match_id_hex"`
	EmojiIndices [2]int    `json:"emoji_indices"`
	ColorIndex   int       `json:"color_index"`
	Emoji        [2]string `json:"emoji"`
	ColorName    string    `json:"color_name"`
	ColorHex     string    `json:"color_hex"`
}

// matchWireCase pins one consent-envelope encoding.
type matchWireCase struct {
	Name       string `json:"name"`
	PreviewHex string `json:"preview_hex,omitempty"`
	Verdict    string `json:"verdict,omitempty"`
	PayloadHex string `json:"payload_hex"`
}

// matchWireNegative pins one refused payload and its stable code.
type matchWireNegative struct {
	Name       string `json:"name"`
	Kind       string `json:"kind"`
	PayloadHex string `json:"payload_hex"`
	Error      string `json:"error"`
}

// matchVectorsFile is the emitted matches/match_timelines.json.
type matchVectorsFile struct {
	fileMeta
	Derivations   []matchDerivation   `json:"derivations"`
	Tokens        []matchToken        `json:"tokens"`
	WireCases     []matchWireCase     `json:"wire"`
	WireNegatives []matchWireNegative `json:"wire_negatives"`
	Scripts       []matchScript       `json:"scripts"`
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
			Implements:    []string{"SRS-MAT-002", "SRS-MAT-004", "SRS-MAT-005", "SRS-MAT-007", "SRS-MAT-008", "SRS-MAT-009", "SRS-MAT-014", "TRS-PAT-002"},
			KeyProvenance: KeyProvenance,
			Notes: "The interest/consent state machine and the SRS-MAT-007 identifier. " +
				"derivations: match_id = SHA-256(MATCH_ID_KDF_INFO | lower | higher)" +
				"[:MATCH_ID_LEN] over the two peer ids in lexicographic byte order - " +
				"symmetric, so both sides of an accept-accept race derive the same id " +
				"with no message; initiator_on_race is the lower id. tokens: the SRS-MAT-008 venue token - SHA-256(MATCH_TOKEN_KDF_INFO | match_id), 6+6 bits into the ADR-009 emoji alphabet, 3 bits into the named colour palette. wire: the class-3/4 consent envelopes, exact bytes both ways; the response verdict alphabet is accept|unmatch and NOTHING ELSE - a decline is unencodable, which is SRS-MAT-004 enforced at the codec. wire_negatives: refusals with their stable codes. scripts: event " +
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
		token, err := match.DeriveToken(id)
		if err != nil {
			return nil, fmt.Errorf("match token %d: %w", i, err)
		}
		file.Tokens = append(file.Tokens, matchToken{
			Name:         fmt.Sprintf("pair_%d", i),
			MatchIDHex:   hex.EncodeToString(id),
			EmojiIndices: token.EmojiIndices,
			ColorIndex:   token.ColorIndex,
			Emoji:        token.Emoji(),
			ColorName:    token.ColorName(),
			ColorHex:     token.ColorHex(),
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

	if err := buildMatchWire(file); err != nil {
		return nil, err
	}
	return file, nil
}

// buildMatchWire fills the consent-envelope sections (WP-04 S5): exact bytes
// both ways, and the refusals with their stable codes. The preview blob is
// opaque derived bytes - the envelope never knows.
func buildMatchWire(file *matchVectorsFile) error {
	preview := testonlyBytes("match/preview", 41)
	requestPayload, err := match.EncodeRequest(preview)
	if err != nil {
		return err
	}
	acceptPayload, err := match.EncodeResponse(match.VerdictAccept)
	if err != nil {
		return err
	}
	unmatchPayload, err := match.EncodeResponse(match.VerdictUnmatch)
	if err != nil {
		return err
	}
	emptyRequest, err := match.EncodeRequest(nil)
	if err != nil {
		return err
	}
	file.WireCases = []matchWireCase{
		{Name: "request_with_preview", PreviewHex: hex.EncodeToString(preview),
			PayloadHex: hex.EncodeToString(requestPayload)},
		{Name: "request_empty_preview", PreviewHex: "",
			PayloadHex: hex.EncodeToString(emptyRequest)},
		{Name: "response_accept", Verdict: "accept",
			PayloadHex: hex.EncodeToString(acceptPayload)},
		{Name: "response_unmatch", Verdict: "unmatch",
			PayloadHex: hex.EncodeToString(unmatchPayload)},
	}
	file.WireNegatives = []matchWireNegative{
		{Name: "request_truncated", Kind: "request", PayloadHex: "0100",
			Error: "ERR_MATCH_BAD_PAYLOAD"},
		{Name: "request_unknown_version", Kind: "request", PayloadHex: "090000",
			Error: "ERR_MATCH_BAD_PAYLOAD"},
		{Name: "request_length_disagrees", Kind: "request", PayloadHex: "01000578",
			Error: "ERR_MATCH_BAD_PAYLOAD"},
		{Name: "response_truncated", Kind: "response", PayloadHex: "01",
			Error: "ERR_MATCH_BAD_PAYLOAD"},
		{Name: "response_unknown_version", Kind: "response", PayloadHex: "0701",
			Error: "ERR_MATCH_BAD_PAYLOAD"},
		{Name: "response_verdict_outside_alphabet", Kind: "response", PayloadHex: "0103",
			Error: "ERR_MATCH_BAD_VERDICT"},
		{Name: "response_verdict_zero", Kind: "response", PayloadHex: "0100",
			Error: "ERR_MATCH_BAD_VERDICT"},
	}
	for _, c := range file.WireNegatives {
		raw, err := hex.DecodeString(c.PayloadHex)
		if err != nil {
			return err
		}
		var decodeErr error
		if c.Kind == "request" {
			_, decodeErr = match.DecodeRequest(raw)
		} else {
			_, decodeErr = match.DecodeResponse(raw)
		}
		var we *match.WireError
		if decodeErr == nil || !errors.As(decodeErr, &we) || we.Code != c.Error {
			return fmt.Errorf("wire negative %s: reference disagreed: %w", c.Name, decodeErr)
		}
	}
	return nil
}
