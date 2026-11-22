// Lumos - tools/vectorgen / The remote-text vector family: AMD-005 events, envelopes and a scripted session.
// Author: dhar.abhishek.email@gmail.com
// Implements: SRS-RMT-001, SRS-RMT-004, SRS-RMT-008, SRS-RMT-009, SRS-RMT-011, TRS-CRY-001, TRS-CRY-002, TRS-TST-001
// ADRs: ADR-001, ADR-008
// Threading: build-time
// Notes: Three sections. text_events pins the AMD-005 byte layout for every
// kind; the negatives pin the code each malformed input earns. envelope
// negatives do the same for the relay payload. session is a scripted
// exchange between Alice and an offline Bob with a one-time prekey: Alice
// seals two PREKEY envelopes, Bob opens them out of order, both sides
// continue with NORMAL envelopes carrying a reaction, an edit and a delete.
// Each step is an action - seal or open - so a consumer replays it in order:
// a seal must reproduce the envelope byte for byte (the ratchet is
// deterministic given its keys), an open must yield the plaintext exactly.
// Every private key both sides need is recorded under testonly_* fields.

package main

import (
	"crypto/ecdh"
	"crypto/ed25519"
	"encoding/hex"
	"errors"
	"fmt"

	"github.com/dharabhishekorg/lumos/protocol/chat"
	"github.com/dharabhishekorg/lumos/protocol/crypto/ratchet"
	"github.com/dharabhishekorg/lumos/protocol/crypto/x3dh"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumoserrors"
	"github.com/dharabhishekorg/lumos/protocol/wire"
)

type textEventCase struct {
	Name       string `json:"name"`
	Kind       int    `json:"kind"`
	TargetHex  string `json:"target_message_id_hex,omitempty"`
	BodyHex    string `json:"body_hex,omitempty"`
	EncodedHex string `json:"encoded_hex"`
}

type bytesNegative struct {
	Name          string `json:"name"`
	EncodedHex    string `json:"encoded_hex"`
	ExpectedError string `json:"expected_error"`
}

type remoteParty struct {
	IdentityEdSeedHex string   `json:"testonly_identity_ed_seed_hex"`
	IdentityDHPrivHex string   `json:"testonly_identity_dh_priv_hex"`
	TurnPrivsHex      []string `json:"testonly_ratchet_turn_privs_hex"`
}

type remoteAlice struct {
	remoteParty
	EphemeralPrivHex    string `json:"testonly_ephemeral_priv_hex"`
	FirstRatchetPrivHex string `json:"testonly_first_ratchet_priv_hex"`
}

type remoteBob struct {
	remoteParty
	SPKPrivHex string `json:"testonly_spk_priv_hex"`
	SPKID      uint32 `json:"spk_id"`
	OPKPrivHex string `json:"testonly_opk_priv_hex"`
	OPKID      uint32 `json:"opk_id"`
}

type remoteMessage struct {
	From         string `json:"from"`
	MessageIDHex string `json:"message_id_hex"`
	PlaintextHex string `json:"plaintext_hex"`
	Prekey       bool   `json:"prekey"`
	EnvelopeHex  string `json:"envelope_hex"`
}

type remoteStep struct {
	Actor   string `json:"actor"`
	Op      string `json:"op"`
	Message int    `json:"message"`
}

type remoteSession struct {
	Description string          `json:"description"`
	MatchIDHex  string          `json:"match_id_hex"`
	Now         int64           `json:"now"`
	Alice       remoteAlice     `json:"alice"`
	Bob         remoteBob       `json:"bob"`
	Messages    []remoteMessage `json:"messages"`
	Steps       []remoteStep    `json:"steps"`
}

// remoteTextFile is the emitted chat/remote_text.json.
type remoteTextFile struct {
	fileMeta
	TextEvents         []textEventCase `json:"text_events"`
	TextEventNegatives []bytesNegative `json:"text_event_negatives"`
	EnvelopeNegatives  []bytesNegative `json:"envelope_negatives"`
	Session            remoteSession   `json:"session"`
}

func (f *remoteTextFile) implementsIDs() []string { return f.Implements }

func vectorMessageID(n byte) chat.MessageID {
	var id chat.MessageID
	copy(id[:], testonlyBytes(fmt.Sprintf("remote|message|%d", n), chat.MessageIDSize))
	return id
}

func stableCode(err error) string {
	var we *wire.Error
	if errors.As(err, &we) {
		return we.Code
	}
	return ""
}

func buildTextEventCases() ([]textEventCase, error) {
	target := vectorMessageID(0)
	events := []struct {
		name string
		ev   chat.TextEvent
	}{
		{"text", chat.TextEvent{Kind: lumosconstants.TextEventKindText, Body: []byte("Hi! Lovely meeting you tonight.")}},
		{"text_empty_body", chat.TextEvent{Kind: lumosconstants.TextEventKindText, Body: []byte{}}},
		{"text_utf8", chat.TextEvent{Kind: lumosconstants.TextEventKindText, Body: []byte("नमस्ते 👋")}},
		{"edit", chat.TextEvent{Kind: lumosconstants.TextEventKindEdit, Target: target, Body: []byte("Hi! Lovely meeting you.")}},
		{"delete_for_everyone", chat.TextEvent{Kind: lumosconstants.TextEventKindDeleteForEveryone, Target: target}},
		{"reaction", chat.TextEvent{Kind: lumosconstants.TextEventKindReaction, Target: target, Body: []byte("❤️")}},
	}
	var out []textEventCase
	for _, e := range events {
		b, err := e.ev.Encode()
		if err != nil {
			return nil, err
		}
		back, err := chat.DecodeTextEvent(b)
		if err != nil || back.Kind != e.ev.Kind {
			return nil, fmt.Errorf("text event %s does not round-trip: %v", e.name, err)
		}
		c := textEventCase{Name: e.name, Kind: int(e.ev.Kind), EncodedHex: hex.EncodeToString(b)}
		if e.ev.Kind != lumosconstants.TextEventKindText {
			c.TargetHex = hex.EncodeToString(e.ev.Target[:])
		}
		if e.ev.Kind != lumosconstants.TextEventKindDeleteForEveryone {
			c.BodyHex = hex.EncodeToString(e.ev.Body)
		}
		out = append(out, c)
	}
	return out, nil
}

func checkedNegatives(decode func([]byte) error, cases []bytesNegative) ([]bytesNegative, error) {
	for _, c := range cases {
		b, err := hex.DecodeString(c.EncodedHex)
		if err != nil {
			return nil, err
		}
		if got := stableCode(decode(b)); got != c.ExpectedError {
			return nil, fmt.Errorf("negative %s: reference says %q, vector says %q", c.Name, got, c.ExpectedError)
		}
	}
	return cases, nil
}

func buildTextEventNegatives() ([]bytesNegative, error) {
	target := vectorMessageID(0)
	edit := []byte{byte(lumosconstants.TextEventKindEdit)}
	return checkedNegatives(func(b []byte) error { _, err := chat.DecodeTextEvent(b); return err }, []bytesNegative{
		{"empty", "", lumoserrors.ErrPayloadMalformed},
		{"unknown_kind", "7f00000000", lumoserrors.ErrTextUnknownEventKind},
		{"edit_without_target", hex.EncodeToString(append(edit, 1, 2, 3)), lumoserrors.ErrTextMissingTarget},
		{"body_length_exceeds_bytes", "000000000961", lumoserrors.ErrPayloadMalformed},
		{"body_length_short_of_bytes", "00000000016161", lumoserrors.ErrPayloadMalformed},
		{"body_length_huge", "00ffffffff", lumoserrors.ErrPayloadMalformed},
		{"delete_with_trailing_byte", hex.EncodeToString(append(append([]byte{byte(lumosconstants.TextEventKindDeleteForEveryone)}, target[:]...), 0)), lumoserrors.ErrPayloadMalformed},
	})
}

func buildEnvelopeNegatives() ([]bytesNegative, error) {
	h := ratchet.Header{PN: 1, N: 2}.Bytes()
	norm := append([]byte{chat.FormatNormal}, h...)
	return checkedNegatives(func(b []byte) error { _, err := chat.DecodeEnvelope(b); return err }, []bytesNegative{
		{"empty", "", lumoserrors.ErrPayloadMalformed},
		{"unknown_format", hex.EncodeToString(append([]byte{0x09}, append(h, 1)...)), lumoserrors.ErrPayloadMalformed},
		{"prekey_fields_truncated", hex.EncodeToString(append([]byte{chat.FormatPrekey}, make([]byte, 2*32+64)...)), lumoserrors.ErrPayloadMalformed},
		{"normal_header_truncated", hex.EncodeToString(norm[:20]), lumoserrors.ErrPayloadMalformed},
		{"normal_without_ciphertext", hex.EncodeToString(norm), lumoserrors.ErrPayloadMalformed},
	})
}

// sessionKeys builds both sides' recorded keys.
type sessionKeys struct {
	aliceEd, bobEd                  ed25519.PrivateKey
	aliceDH, bobDH, eph, aliceFirst *ecdh.PrivateKey
	bobSPK, bobOPK                  *ecdh.PrivateKey
	aliceTurns, bobTurns            *listKP
	file                            remoteSession
}

func newSessionKeys() (*sessionKeys, error) {
	k := &sessionKeys{aliceEd: edFromSeed("remote|alice|ed"), bobEd: edFromSeed("remote|bob|ed")}
	var err error
	for label, dst := range map[string]**ecdh.PrivateKey{
		"remote|alice|dh": &k.aliceDH, "remote|bob|dh": &k.bobDH, "remote|alice|ephemeral": &k.eph,
		"remote|alice|ratchet-first": &k.aliceFirst, "remote|bob|spk": &k.bobSPK, "remote|bob|opk": &k.bobOPK,
	} {
		if *dst, err = dhFromSeed(label); err != nil {
			return nil, err
		}
	}
	var aliceTurnHex, bobTurnHex []string
	if k.aliceTurns, aliceTurnHex, err = turnKeys("remote|alice", 4); err != nil {
		return nil, err
	}
	if k.bobTurns, bobTurnHex, err = turnKeys("remote|bob", 4); err != nil {
		return nil, err
	}
	h := func(b []byte) string { return hex.EncodeToString(b) }
	k.file = remoteSession{
		MatchIDHex: h(vectorMatchID[:]),
		Now:        1_700_000_000,
		Alice: remoteAlice{
			remoteParty:      remoteParty{IdentityEdSeedHex: h(k.aliceEd.Seed()), IdentityDHPrivHex: h(k.aliceDH.Bytes()), TurnPrivsHex: aliceTurnHex},
			EphemeralPrivHex: h(k.eph.Bytes()), FirstRatchetPrivHex: h(k.aliceFirst.Bytes()),
		},
		Bob: remoteBob{
			remoteParty: remoteParty{IdentityEdSeedHex: h(k.bobEd.Seed()), IdentityDHPrivHex: h(k.bobDH.Bytes()), TurnPrivsHex: bobTurnHex},
			SPKPrivHex:  h(k.bobSPK.Bytes()), SPKID: 7, OPKPrivHex: h(k.bobOPK.Bytes()), OPKID: 42,
		},
	}
	return k, nil
}

func (k *sessionKeys) bobBundle() *x3dh.Bundle {
	return &x3dh.Bundle{
		IdentityEd: k.bobEd.Public().(ed25519.PublicKey), IdentityDH: k.bobDH.PublicKey(),
		IdentityDHSig: x3dh.SignIdentityDH(k.bobEd, k.bobDH.PublicKey()),
		SPKID:         k.file.Bob.SPKID, SPK: k.bobSPK.PublicKey(),
		SPKSig: x3dh.SignPrekey(k.bobEd, k.file.Bob.SPKID, k.bobSPK.PublicKey()),
		OPKID:  k.file.Bob.OPKID, OPK: k.bobOPK.PublicKey(),
	}
}

// sessionScript is the conversation, in send order.
func sessionScript() []struct {
	from string
	p    chat.Plaintext
} {
	first := vectorMessageID(10)
	second := vectorMessageID(11)
	mk := func(n byte, ev chat.TextEvent) chat.Plaintext {
		return chat.Plaintext{MessageID: vectorMessageID(n), Event: ev}
	}
	return []struct {
		from string
		p    chat.Plaintext
	}{
		{"alice", mk(10, chat.TextEvent{Kind: lumosconstants.TextEventKindText, Body: []byte("Hi! Lovely meeting you tonight.")})},
		{"alice", mk(11, chat.TextEvent{Kind: lumosconstants.TextEventKindText, Body: []byte("Coffee on Saturday?")})},
		{"bob", mk(12, chat.TextEvent{Kind: lumosconstants.TextEventKindReaction, Target: first, Body: []byte("❤️")})},
		{"bob", mk(13, chat.TextEvent{Kind: lumosconstants.TextEventKindText, Body: []byte("Yes! 11am?")})},
		{"alice", mk(14, chat.TextEvent{Kind: lumosconstants.TextEventKindEdit, Target: second, Body: []byte("Coffee on Sunday?")})},
		{"alice", mk(15, chat.TextEvent{Kind: lumosconstants.TextEventKindDeleteForEveryone, Target: first})},
	}
}

// remoteSteps is the order of actions: Bob is offline for messages 0 and 1
// and opens them out of order; Alice opens Bob's two out of order too.
var remoteSteps = []remoteStep{
	{"alice", "seal", 0}, {"alice", "seal", 1},
	{"bob", "open", 1}, {"bob", "open", 0},
	{"bob", "seal", 2}, {"bob", "seal", 3},
	{"alice", "open", 3}, {"alice", "open", 2},
	{"alice", "seal", 4}, {"alice", "seal", 5},
	{"bob", "open", 4}, {"bob", "open", 5},
}

// runRemoteSession plays the script against the reference and records it.
func runRemoteSession(k *sessionKeys) error {
	script := sessionScript()
	matchID := vectorMatchID
	alice := chat.Identity{Ed: k.aliceEd, DH: k.aliceDH}
	bob := chat.Identity{Ed: k.bobEd, DH: k.bobDH}
	aState, prekey, err := chat.Initiate(alice, k.bobBundle(), matchID, k.eph, k.aliceFirst)
	if err != nil {
		return err
	}
	var bState *ratchet.State
	aliceHeardBack := false
	msgs := make([]remoteMessage, len(script))
	envs := make([][]byte, len(script))
	now := k.file.Now
	for _, s := range remoteSteps {
		p := script[s.Message].p
		switch {
		case s.Op == "seal" && s.Actor == "alice":
			pk := prekey
			if aliceHeardBack {
				pk = nil
			}
			if envs[s.Message], err = chat.Seal(aState, p, pk, now); err != nil {
				return err
			}
		case s.Op == "seal":
			if envs[s.Message], err = chat.Seal(bState, p, nil, now); err != nil {
				return err
			}
		default:
			e, err := chat.DecodeEnvelope(envs[s.Message])
			if err != nil {
				return err
			}
			var got chat.Plaintext
			if s.Actor == "bob" {
				if bState == nil {
					keys := chat.ResponderKeys{SPK: k.bobSPK, SPKID: k.file.Bob.SPKID, OPK: k.bobOPK, OPKID: k.file.Bob.OPKID}
					if bState, err = chat.Respond(bob, keys, k.aliceEd.Public().(ed25519.PublicKey), *e.Prekey, matchID); err != nil {
						return err
					}
				}
				got, err = chat.Open(bState, e, p.MessageID, now, k.bobTurns)
			} else {
				got, err = chat.Open(aState, e, p.MessageID, now, k.aliceTurns)
				aliceHeardBack = true
			}
			if err != nil {
				return fmt.Errorf("step %+v: %w", s, err)
			}
			want, _ := p.Encode()
			back, _ := got.Encode()
			if hex.EncodeToString(want) != hex.EncodeToString(back) {
				return fmt.Errorf("step %+v: plaintext mismatch", s)
			}
		}
	}
	for i, s := range script {
		pt, err := s.p.Encode()
		if err != nil {
			return err
		}
		e, _ := chat.DecodeEnvelope(envs[i])
		msgs[i] = remoteMessage{From: s.from, MessageIDHex: hex.EncodeToString(s.p.MessageID[:]),
			PlaintextHex: hex.EncodeToString(pt), Prekey: e.Prekey != nil, EnvelopeHex: hex.EncodeToString(envs[i])}
	}
	k.file.Messages, k.file.Steps = msgs, remoteSteps
	return nil
}

// buildRemoteTextVectors emits chat/remote_text.json.
func buildRemoteTextVectors(constantsHash string) (*remoteTextFile, error) {
	events, err := buildTextEventCases()
	if err != nil {
		return nil, err
	}
	evNeg, err := buildTextEventNegatives()
	if err != nil {
		return nil, err
	}
	envNeg, err := buildEnvelopeNegatives()
	if err != nil {
		return nil, err
	}
	k, err := newSessionKeys()
	if err != nil {
		return nil, err
	}
	if err := runRemoteSession(k); err != nil {
		return nil, fmt.Errorf("remote session: %w", err)
	}
	k.file.Description = "Alice starts a session from Bob's bundle (with a one-time prekey) while Bob is offline, " +
		"sends two PREKEY envelopes, Bob opens them out of order and replies; the rest are NORMAL envelopes " +
		"carrying a reaction, an edit and a delete-for-everyone. Replay the steps in order: a seal must " +
		"reproduce envelope_hex exactly, an open must yield plaintext_hex exactly. Alice drops the PREKEY " +
		"fields once she has opened any envelope from Bob."
	return &remoteTextFile{
		fileMeta: fileMeta{
			SchemaVersion: 1, Generator: GeneratorName, ConstantsHash: constantsHash,
			Implements: []string{"SRS-RMT-001", "SRS-RMT-004", "SRS-RMT-008", "SRS-RMT-009", "SRS-RMT-011",
				"TRS-CRY-001", "TRS-CRY-002", "TRS-PROTO-007"},
			KeyProvenance: KeyProvenance,
			Notes: "The remote-text wire (protocol/chat/remote.go): text_event is event_kind u8 | " +
				"target_message_id[16] (edit, delete, reaction) | body_len u32 BE | body (text, edit, reaction); " +
				"the ratchet plaintext is message_id[16] | text_event; the relay envelope is 0x01 PREKEY | " +
				"identity_dh[32] | identity_dh_sig[64] | ephemeral[32] | spk_id u32 | opk_id u32 | header[40] | " +
				"ciphertext, or 0x02 NORMAL | header[40] | ciphertext.",
		},
		TextEvents:         events,
		TextEventNegatives: evNeg,
		EnvelopeNegatives:  envNeg,
		Session:            k.file,
	}, nil
}
