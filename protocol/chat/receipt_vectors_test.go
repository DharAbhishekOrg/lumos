// Lumos - protocol/chat / The reference against the committed chat/receipts.json.
// Author: dhar.abhishek.email@gmail.com
// Implements: SRS-RMT-005, SRS-RMT-014, SRS-RMT-009, SRS-RMT-001, TRS-PROTO-007, TRS-CONST-005
// ADRs: ADR-001, ADR-002
// Threading: test-only
// Notes: Reads the committed file, not the generator, so a change to the
// codecs that silently changes bytes fails here before any app sees it.
package chat

import (
	"crypto/ed25519"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/dharabhishekorg/lumos/protocol/crypto/ratchet"
	"github.com/dharabhishekorg/lumos/protocol/crypto/x3dh"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekorg/lumos/protocol/schema"
)

type receiptVectorFile struct {
	ConstantsHash string `json:"constants_hash"`
	Routes        []struct {
		RoutingIDHex  string `json:"routing_id_hex"`
		CapabilityHex string `json:"capability_hex"`
		EncodedHex    string `json:"encoded_hex"`
	} `json:"routes"`
	RouteNegatives []vecNegative `json:"route_negatives"`
	Receipts       []struct {
		ReceiptIDHex string   `json:"receipt_id_hex"`
		Kind         int      `json:"kind"`
		AckedHex     []string `json:"acked_hex"`
		EncodedHex   string   `json:"encoded_hex"`
	} `json:"receipts"`
	ReceiptNegatives []vecNegative `json:"receipt_negatives"`
	Session          struct {
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
			Class        int    `json:"class"`
			MessageIDHex string `json:"message_id_hex"`
			PlaintextHex string `json:"plaintext_hex"`
			EnvelopeHex  string `json:"envelope_hex"`
		} `json:"messages"`
		Steps []struct {
			Actor   string `json:"actor"`
			Op      string `json:"op"`
			Message int    `json:"message"`
		} `json:"steps"`
		Relabels []struct {
			Name           string `json:"name"`
			Message        int    `json:"message"`
			DeliveredClass int    `json:"delivered_class"`
			DeliveredIDHex string `json:"delivered_id_hex"`
			ExpectedError  string `json:"expected_error"`
		} `json:"relabels"`
	} `json:"session"`
}

func loadReceiptVectors(t *testing.T) receiptVectorFile {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("..", "..", "test-vectors", "chat", "receipts.json"))
	if err != nil {
		t.Fatal(err)
	}
	var f receiptVectorFile
	if err := json.Unmarshal(raw, &f); err != nil {
		t.Fatal(err)
	}
	reg, err := schema.LoadRegister(filepath.Join("..", ".."))
	if err != nil {
		t.Fatal(err)
	}
	if declared, _ := reg.String("constants_hash"); f.ConstantsHash != declared {
		t.Fatal("receipts.json constants_hash does not match the register - stale vectors (TRS-CONST-005)")
	}
	if len(f.Routes) == 0 || len(f.RouteNegatives) == 0 || len(f.Receipts) == 0 || len(f.ReceiptNegatives) == 0 ||
		len(f.Session.Steps) == 0 || len(f.Session.Relabels) == 0 {
		t.Fatal("an empty section proves nothing")
	}
	return f
}

func TestCommittedRoutesAndReceiptsDecodeAndReencode(t *testing.T) {
	f := loadReceiptVectors(t)
	for _, c := range f.Routes {
		r, err := DecodeRoute(unhex(t, c.EncodedHex))
		if err != nil || hex.EncodeToString(r.RoutingID[:]) != c.RoutingIDHex || hex.EncodeToString(r.Capability[:]) != c.CapabilityHex {
			t.Fatalf("route %s: %v", c.EncodedHex, err)
		}
		if hex.EncodeToString(r.Encode()) != c.EncodedHex {
			t.Fatal("route must re-encode byte for byte")
		}
	}
	for _, n := range f.RouteNegatives {
		if _, err := DecodeRoute(unhex(t, n.EncodedHex)); codeOf(err) != n.ExpectedError {
			t.Errorf("route negative %s: want %s, got %v", n.Name, n.ExpectedError, err)
		}
	}
	for _, c := range f.Receipts {
		r, err := DecodeReceipt(unhex(t, c.EncodedHex))
		if err != nil || int(r.Kind) != c.Kind || len(r.Acked) != len(c.AckedHex) || hex.EncodeToString(r.ID[:]) != c.ReceiptIDHex {
			t.Fatalf("receipt %s: %v", c.EncodedHex, err)
		}
		if back, _ := r.Encode(); hex.EncodeToString(back) != c.EncodedHex {
			t.Fatal("receipt must re-encode byte for byte")
		}
	}
	for _, n := range f.ReceiptNegatives {
		if _, err := DecodeReceipt(unhex(t, n.EncodedHex)); codeOf(err) != n.ExpectedError {
			t.Errorf("receipt negative %s: want %s, got %v", n.Name, n.ExpectedError, err)
		}
	}
}

// receiptReplay runs the committed session on fresh state.
type receiptReplay struct {
	t              *testing.T
	f              *receiptVectorFile
	aState, bState *ratchet.State
	prekey         *PrekeyFields
	heard          bool
	aTurns, bTurns *recordedKeys
	bob            Identity
	aliceEd        ed25519.PrivateKey
	keys           ResponderKeys
	match          [16]byte
}

func newReceiptReplay(t *testing.T, f *receiptVectorFile) *receiptReplay {
	s := f.Session
	r := &receiptReplay{t: t, f: f}
	copy(r.match[:], unhex(t, s.MatchIDHex))
	r.aliceEd = ed25519.NewKeyFromSeed(unhex(t, s.Alice.EdSeed))
	bobEd := ed25519.NewKeyFromSeed(unhex(t, s.Bob.EdSeed))
	r.bob = Identity{Ed: bobEd, DH: x25519(t, s.Bob.DH)}
	spk, opk := x25519(t, s.Bob.SPK), x25519(t, s.Bob.OPK)
	r.keys = ResponderKeys{SPK: spk, SPKID: s.Bob.SPKID, OPK: opk, OPKID: s.Bob.OPKID}
	bundle := &x3dh.Bundle{
		IdentityEd: bobEd.Public().(ed25519.PublicKey), IdentityDH: r.bob.DH.PublicKey(),
		IdentityDHSig: x3dh.SignIdentityDH(bobEd, r.bob.DH.PublicKey()),
		SPKID:         s.Bob.SPKID, SPK: spk.PublicKey(), SPKSig: x3dh.SignPrekey(bobEd, s.Bob.SPKID, spk.PublicKey()),
		OPKID: s.Bob.OPKID, OPK: opk.PublicKey(),
	}
	r.aTurns, r.bTurns = &recordedKeys{}, &recordedKeys{}
	for _, h := range s.Alice.Turns {
		r.aTurns.keys = append(r.aTurns.keys, x25519(t, h))
	}
	for _, h := range s.Bob.Turns {
		r.bTurns.keys = append(r.bTurns.keys, x25519(t, h))
	}
	var err error
	alice := Identity{Ed: r.aliceEd, DH: x25519(t, s.Alice.DH)}
	if r.aState, r.prekey, err = Initiate(alice, bundle, r.match, x25519(t, s.Alice.Ephemeral), x25519(t, s.Alice.First)); err != nil {
		t.Fatal(err)
	}
	return r
}

func (r *receiptReplay) seal(i int) {
	t, s := r.t, r.f.Session
	st := s.Steps[i]
	m := s.Messages[st.Message]
	var env []byte
	var err error
	if lumosconstants.MessageClass(m.Class) == lumosconstants.MessageClassTextMessage {
		p, _ := DecodePlaintext(unhex(t, m.PlaintextHex))
		pk := r.prekey
		if r.heard {
			pk = nil
		}
		env, err = Seal(r.aState, p, pk, s.Now)
	} else {
		rc, _ := DecodeReceipt(unhex(t, m.PlaintextHex))
		env, err = SealReceipt(r.bState, rc, nil, s.Now)
	}
	if err != nil || hex.EncodeToString(env) != m.EnvelopeHex {
		t.Fatalf("step %d: seal must reproduce the committed envelope (err %v)", i, err)
	}
}

// open opens step i's message under class and id, returning its plaintext.
func (r *receiptReplay) open(i int, class lumosconstants.MessageClass, id MessageID) ([]byte, error) {
	t, s := r.t, r.f.Session
	st := s.Steps[i]
	e, err := DecodeEnvelope(unhex(t, s.Messages[st.Message].EnvelopeHex))
	if err != nil {
		t.Fatal(err)
	}
	if st.Actor == "bob" {
		if r.bState == nil {
			if r.bState, err = Respond(r.bob, r.keys, r.aliceEd.Public().(ed25519.PublicKey), *e.Prekey, r.match); err != nil {
				t.Fatal(err)
			}
		}
		p, err := Open(r.bState, e, id, s.Now, r.bTurns)
		if err != nil {
			return nil, err
		}
		return p.Encode()
	}
	r.heard = true
	if class == lumosconstants.MessageClassTextMessage {
		p, err := Open(r.aState, e, id, s.Now, r.aTurns)
		if err != nil {
			return nil, err
		}
		return p.Encode()
	}
	rc, err := OpenReceipt(r.aState, e, id, class, s.Now, r.aTurns)
	if err != nil {
		return nil, err
	}
	return rc.Encode()
}

func (r *receiptReplay) run(stop int) {
	s := r.f.Session
	for i := 0; i < stop; i++ {
		st := s.Steps[i]
		if st.Op == "seal" {
			r.seal(i)
			continue
		}
		m := s.Messages[st.Message]
		var id MessageID
		copy(id[:], unhex(r.t, m.MessageIDHex))
		got, err := r.open(i, lumosconstants.MessageClass(m.Class), id)
		if err != nil || hex.EncodeToString(got) != m.PlaintextHex {
			r.t.Fatalf("step %d: open must yield the committed plaintext (err %v)", i, err)
		}
	}
}

func TestTheCommittedReceiptSessionReplaysAndEveryRelabelFails(t *testing.T) {
	f := loadReceiptVectors(t)
	newReceiptReplay(t, &f).run(len(f.Session.Steps))
	for _, rl := range f.Session.Relabels {
		stop := -1
		for i, st := range f.Session.Steps {
			if st.Op == "open" && st.Message == rl.Message {
				stop = i
				break
			}
		}
		r := newReceiptReplay(t, &f)
		r.run(stop)
		var id MessageID
		copy(id[:], unhex(t, rl.DeliveredIDHex))
		if _, err := r.open(stop, lumosconstants.MessageClass(rl.DeliveredClass), id); codeOf(err) != rl.ExpectedError {
			t.Errorf("%s: want %s, got %v", rl.Name, rl.ExpectedError, err)
		}
	}
}
