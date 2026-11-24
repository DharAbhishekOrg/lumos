// Lumos - protocol/chat / The committed remote-text vectors, replayed against the reference.
// Author: dhar.abhishek.email@gmail.com
// Implements: SRS-RMT-004, SRS-RMT-009, SRS-RMT-011, TRS-CRY-001, TRS-CRY-002, TRS-TST-001
// ADRs: ADR-001, ADR-008
// Threading: test-only
// Notes: Reads the COMMITTED file the way the Java and Swift conformance
// suites do, from the recorded keys only, so a hand-edited vector or a
// reference change that the generator did not re-emit fails here.

package chat

import (
	"bytes"
	"crypto/ecdh"
	"crypto/ed25519"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"testing"

	"github.com/dharabhishekorg/lumos/protocol/crypto/ratchet"
	"github.com/dharabhishekorg/lumos/protocol/crypto/x3dh"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekorg/lumos/protocol/schema"
)

type remoteVectorFile struct {
	ConstantsHash string `json:"constants_hash"`
	TextEvents    []struct {
		Name       string `json:"name"`
		Kind       int    `json:"kind"`
		TargetHex  string `json:"target_message_id_hex"`
		BodyHex    string `json:"body_hex"`
		EncodedHex string `json:"encoded_hex"`
	} `json:"text_events"`
	TextEventNegatives []vecNegative `json:"text_event_negatives"`
	EnvelopeNegatives  []vecNegative `json:"envelope_negatives"`
	Session            struct {
		MatchIDHex string `json:"match_id_hex"`
		Now        int64  `json:"now"`
		Alice      struct {
			EdSeed    string   `json:"testonly_identity_ed_seed_hex"`
			DH        string   `json:"testonly_identity_dh_priv_hex"`
			Turns     []string `json:"testonly_ratchet_turn_privs_hex"`
			Ephemeral string   `json:"testonly_ephemeral_priv_hex"`
			First     string   `json:"testonly_first_ratchet_priv_hex"`
		} `json:"alice"`
		Bob struct {
			EdSeed string   `json:"testonly_identity_ed_seed_hex"`
			DH     string   `json:"testonly_identity_dh_priv_hex"`
			Turns  []string `json:"testonly_ratchet_turn_privs_hex"`
			SPK    string   `json:"testonly_spk_priv_hex"`
			SPKID  uint32   `json:"spk_id"`
			OPK    string   `json:"testonly_opk_priv_hex"`
			OPKID  uint32   `json:"opk_id"`
		} `json:"bob"`
		Messages []struct {
			From         string `json:"from"`
			MessageIDHex string `json:"message_id_hex"`
			PlaintextHex string `json:"plaintext_hex"`
			Prekey       bool   `json:"prekey"`
			EnvelopeHex  string `json:"envelope_hex"`
		} `json:"messages"`
		Steps []struct {
			Actor   string `json:"actor"`
			Op      string `json:"op"`
			Message int    `json:"message"`
		} `json:"steps"`
	} `json:"session"`
}

type vecNegative struct {
	Name          string `json:"name"`
	EncodedHex    string `json:"encoded_hex"`
	ExpectedError string `json:"expected_error"`
}

func unhex(t *testing.T, s string) []byte {
	t.Helper()
	b, err := hex.DecodeString(s)
	if err != nil {
		t.Fatalf("bad hex %q: %v", s, err)
	}
	return b
}

func loadRemoteVectors(t *testing.T) remoteVectorFile {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("..", "..", "test-vectors", "chat", "remote_text.json"))
	if err != nil {
		t.Fatal(err)
	}
	var f remoteVectorFile
	if err := json.Unmarshal(raw, &f); err != nil {
		t.Fatal(err)
	}
	reg, err := schema.LoadRegister(filepath.Join("..", ".."))
	if err != nil {
		t.Fatal(err)
	}
	declared, err := reg.String("constants_hash")
	if err != nil {
		t.Fatal(err)
	}
	if f.ConstantsHash != declared {
		t.Fatal("remote_text.json constants_hash does not match the register - stale vectors (TRS-CONST-005)")
	}
	if len(f.TextEvents) == 0 || len(f.TextEventNegatives) == 0 || len(f.EnvelopeNegatives) == 0 || len(f.Session.Steps) == 0 {
		t.Fatal("an empty section proves nothing")
	}
	return f
}

func TestCommittedTextEventsDecodeAndReencode(t *testing.T) {
	f := loadRemoteVectors(t)
	for _, c := range f.TextEvents {
		b := unhex(t, c.EncodedHex)
		e, err := DecodeTextEvent(b)
		if err != nil || int(e.Kind) != c.Kind || hex.EncodeToString(e.Body) != c.BodyHex {
			t.Fatalf("%s: %+v %v", c.Name, e, err)
		}
		if c.TargetHex != "" && hex.EncodeToString(e.Target[:]) != c.TargetHex {
			t.Fatalf("%s: target", c.Name)
		}
		again, _ := e.Encode()
		if !bytes.Equal(again, b) {
			t.Fatalf("%s: re-encoding differs", c.Name)
		}
	}
	for _, n := range f.TextEventNegatives {
		if _, err := DecodeTextEvent(unhex(t, n.EncodedHex)); codeOf(err) != n.ExpectedError {
			t.Errorf("%s: want %s, got %v", n.Name, n.ExpectedError, err)
		}
	}
	for _, n := range f.EnvelopeNegatives {
		if _, err := DecodeEnvelope(unhex(t, n.EncodedHex)); codeOf(err) != n.ExpectedError {
			t.Errorf("%s: want %s, got %v", n.Name, n.ExpectedError, err)
		}
	}
}

type recordedKeys struct {
	keys []*ecdh.PrivateKey
	n    int
}

func (r *recordedKeys) GenerateDH() (*ecdh.PrivateKey, error) {
	if r.n >= len(r.keys) {
		return nil, fmt.Errorf("recorded ratchet keys exhausted")
	}
	r.n++
	return r.keys[r.n-1], nil
}

func x25519(t *testing.T, h string) *ecdh.PrivateKey {
	t.Helper()
	k, err := ecdh.X25519().NewPrivateKey(unhex(t, h))
	if err != nil {
		t.Fatal(err)
	}
	return k
}

func TestTheCommittedSessionReplaysByteForByte(t *testing.T) {
	f := loadRemoteVectors(t)
	s := f.Session
	var match [16]byte
	copy(match[:], unhex(t, s.MatchIDHex))
	aliceEd := ed25519.NewKeyFromSeed(unhex(t, s.Alice.EdSeed))
	bobEd := ed25519.NewKeyFromSeed(unhex(t, s.Bob.EdSeed))
	alice := Identity{Ed: aliceEd, DH: x25519(t, s.Alice.DH)}
	bob := Identity{Ed: bobEd, DH: x25519(t, s.Bob.DH)}
	spk, opk := x25519(t, s.Bob.SPK), x25519(t, s.Bob.OPK)
	bundle := &x3dh.Bundle{
		IdentityEd: bobEd.Public().(ed25519.PublicKey), IdentityDH: bob.DH.PublicKey(),
		IdentityDHSig: x3dh.SignIdentityDH(bobEd, bob.DH.PublicKey()),
		SPKID:         s.Bob.SPKID, SPK: spk.PublicKey(), SPKSig: x3dh.SignPrekey(bobEd, s.Bob.SPKID, spk.PublicKey()),
		OPKID: s.Bob.OPKID, OPK: opk.PublicKey(),
	}
	turns := func(hs []string) *recordedKeys {
		r := &recordedKeys{}
		for _, h := range hs {
			r.keys = append(r.keys, x25519(t, h))
		}
		return r
	}
	aliceTurns, bobTurns := turns(s.Alice.Turns), turns(s.Bob.Turns)
	aState, prekey, err := Initiate(alice, bundle, match, x25519(t, s.Alice.Ephemeral), x25519(t, s.Alice.First))
	if err != nil {
		t.Fatal(err)
	}
	var bState *ratchet.State
	heardBack := false
	for i, st := range s.Steps {
		m := s.Messages[st.Message]
		var id MessageID
		copy(id[:], unhex(t, m.MessageIDHex))
		switch {
		case st.Op == "seal":
			p, err := DecodePlaintext(unhex(t, m.PlaintextHex))
			if err != nil {
				t.Fatalf("step %d: %v", i, err)
			}
			state, pk := aState, prekey
			if st.Actor == "bob" {
				state, pk = bState, nil
			} else if heardBack {
				pk = nil
			}
			env, err := Seal(state, p, pk, s.Now)
			if err != nil || hex.EncodeToString(env) != m.EnvelopeHex {
				t.Fatalf("step %d: sealing message %d must reproduce the committed envelope (err %v)", i, st.Message, err)
			}
		case st.Actor == "bob":
			e, err := DecodeEnvelope(unhex(t, m.EnvelopeHex))
			if err != nil {
				t.Fatal(err)
			}
			if bState == nil {
				keys := ResponderKeys{SPK: spk, SPKID: s.Bob.SPKID, OPK: opk, OPKID: s.Bob.OPKID}
				if bState, err = Respond(bob, keys, aliceEd.Public().(ed25519.PublicKey), *e.Prekey, match); err != nil {
					t.Fatal(err)
				}
			}
			p, err := Open(bState, e, id, s.Now, bobTurns)
			assertPlaintext(t, i, p, err, m.PlaintextHex)
		default:
			e, err := DecodeEnvelope(unhex(t, m.EnvelopeHex))
			if err != nil {
				t.Fatal(err)
			}
			p, err := Open(aState, e, id, s.Now, aliceTurns)
			assertPlaintext(t, i, p, err, m.PlaintextHex)
			heardBack = true
		}
	}
	if !s.Messages[0].Prekey || s.Messages[2].Prekey {
		t.Fatal("first-contact envelopes are PREKEY and the responder's are NORMAL")
	}
	kinds := map[lumosconstants.TextEventKind]bool{}
	for _, m := range s.Messages {
		p, _ := DecodePlaintext(unhex(t, m.PlaintextHex))
		kinds[p.Event.Kind] = true
	}
	if len(kinds) != len(lumosconstants.TextEventKindNames) {
		t.Fatalf("the session must exercise every event kind (%d, REPLY since CR-009), got %d",
			len(lumosconstants.TextEventKindNames), len(kinds))
	}
}

func assertPlaintext(t *testing.T, step int, p Plaintext, err error, want string) {
	t.Helper()
	if err != nil {
		t.Fatalf("step %d: open: %v", step, err)
	}
	got, _ := p.Encode()
	if hex.EncodeToString(got) != want {
		t.Fatalf("step %d: plaintext differs", step)
	}
}
