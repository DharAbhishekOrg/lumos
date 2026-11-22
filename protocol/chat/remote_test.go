// Lumos - protocol/chat / The remote-text wire and sessions, pinned by tests.
// Author: dhar.abhishek.email@gmail.com
// Implements: SRS-RMT-001, SRS-RMT-004, SRS-RMT-008, SRS-RMT-009, SRS-RMT-011, TRS-PROTO-007, TRS-CRY-005, TRS-TST-001
// ADRs: ADR-001, ADR-008
// Threading: test-only
// Notes: The session test is the offline-peer story: Alice sends three PREKEY
// envelopes while Bob is away, Bob opens them out of order, replies, and from
// then on both sides speak NORMAL envelopes. The negatives cover every way a
// relay or an attacker could bend an envelope. The fuzz targets feed raw
// bytes to both decoders: nothing may panic or allocate past the input.
package chat

import (
	"bytes"
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/rand"
	"errors"
	"testing"

	"github.com/dharabhishekorg/lumos/protocol/crypto/ratchet"
	"github.com/dharabhishekorg/lumos/protocol/crypto/x3dh"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumoserrors"
	"github.com/dharabhishekorg/lumos/protocol/wire"
)

func codeOf(err error) string {
	var we *wire.Error
	if errors.As(err, &we) {
		return we.Code
	}
	if err != nil {
		return err.Error()
	}
	return ""
}

func mid(b byte) MessageID {
	var m MessageID
	m[0], m[15] = b, ^b
	return m
}

func TestEveryTextEventKindRoundTrips(t *testing.T) {
	for _, e := range []TextEvent{
		{Kind: lumosconstants.TextEventKindText, Body: []byte("hello")},
		{Kind: lumosconstants.TextEventKindText, Body: []byte{}},
		{Kind: lumosconstants.TextEventKindEdit, Target: mid(1), Body: []byte("hello, edited")},
		{Kind: lumosconstants.TextEventKindDeleteForEveryone, Target: mid(2)},
		{Kind: lumosconstants.TextEventKindReaction, Target: mid(3), Body: []byte("❤")},
	} {
		b, err := e.Encode()
		if err != nil {
			t.Fatalf("%v: %v", e.Kind, err)
		}
		got, err := DecodeTextEvent(b)
		if err != nil || got.Kind != e.Kind || got.Target != e.Target || !bytes.Equal(got.Body, e.Body) {
			t.Fatalf("%v round trip: %+v %v", e.Kind, got, err)
		}
	}
}

func TestMalformedTextEventsAreRefusedWithTheirCodes(t *testing.T) {
	text, _ := TextEvent{Kind: lumosconstants.TextEventKindText, Body: []byte("abc")}.Encode()
	edit, _ := TextEvent{Kind: lumosconstants.TextEventKindEdit, Target: mid(1), Body: []byte("x")}.Encode()
	cases := map[string]struct {
		in   []byte
		code string
	}{
		"empty":                   {nil, lumoserrors.ErrPayloadMalformed},
		"unknown kind":            {[]byte{0x7F, 0, 0, 0, 0}, lumoserrors.ErrTextUnknownEventKind},
		"edit without target":     {[]byte{byte(lumosconstants.TextEventKindEdit), 1, 2}, lumoserrors.ErrTextMissingTarget},
		"body length too long":    {append(text[:1], 0, 0, 0, 9, 'a', 'b', 'c'), lumoserrors.ErrPayloadMalformed},
		"body length too short":   {append(text[:1], 0, 0, 0, 1, 'a', 'b', 'c'), lumoserrors.ErrPayloadMalformed},
		"huge declared length":    {append(text[:1], 0xFF, 0xFF, 0xFF, 0xFF), lumoserrors.ErrPayloadMalformed},
		"body length truncated":   {edit[:1+MessageIDSize+2], lumoserrors.ErrPayloadMalformed},
		"delete with extra bytes": {append([]byte{byte(lumosconstants.TextEventKindDeleteForEveryone)}, append(mid(1).bytes(), 0)...), lumoserrors.ErrPayloadMalformed},
	}
	for name, c := range cases {
		if _, err := DecodeTextEvent(c.in); codeOf(err) != c.code {
			t.Errorf("%s: want %s, got %v", name, c.code, err)
		}
	}
	if _, err := (TextEvent{Kind: lumosconstants.TextEventKindDeleteForEveryone, Body: []byte("x")}).Encode(); err == nil {
		t.Error("a delete-for-everyone cannot carry a body")
	}
}

func (m MessageID) bytes() []byte { return m[:] }

type party struct {
	id     Identity
	spk    *ecdh.PrivateKey
	spkID  uint32
	opk    *ecdh.PrivateKey
	opkID  uint32
	bundle *x3dh.Bundle
}

func newX25519(t *testing.T) *ecdh.PrivateKey {
	t.Helper()
	k, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	return k
}

func newParty(t *testing.T, withOPK bool) party {
	t.Helper()
	_, ed, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	p := party{id: Identity{Ed: ed, DH: newX25519(t)}, spk: newX25519(t), spkID: 7}
	p.bundle = &x3dh.Bundle{
		IdentityEd: ed.Public().(ed25519.PublicKey), IdentityDH: p.id.DH.PublicKey(),
		IdentityDHSig: x3dh.SignIdentityDH(ed, p.id.DH.PublicKey()),
		SPKID:         p.spkID, SPK: p.spk.PublicKey(), SPKSig: x3dh.SignPrekey(ed, p.spkID, p.spk.PublicKey()),
	}
	if withOPK {
		p.opk, p.opkID = newX25519(t), 42
		p.bundle.OPK, p.bundle.OPKID = p.opk.PublicKey(), p.opkID
	}
	return p
}

type randKeys struct{}

func (randKeys) GenerateDH() (*ecdh.PrivateKey, error) { return ecdh.X25519().GenerateKey(rand.Reader) }

func text(id MessageID, s string) Plaintext {
	return Plaintext{MessageID: id, Event: TextEvent{Kind: lumosconstants.TextEventKindText, Body: []byte(s)}}
}

var matchID = [16]byte{0xAA, 1, 2, 3}

func TestAnOfflinePeerOpensPrekeyEnvelopesOutOfOrderAndTheSessionSettles(t *testing.T) {
	for _, withOPK := range []bool{true, false} {
		alice, bob := newParty(t, false), newParty(t, withOPK)
		aState, prekey, err := Initiate(alice.id, bob.bundle, matchID, newX25519(t), newX25519(t))
		if err != nil {
			t.Fatal(err)
		}
		const now = 1_700_000_000
		var sent [][]byte
		for i := range 3 {
			env, err := Seal(aState, text(mid(byte(i)), "while you were away"), prekey, now)
			if err != nil {
				t.Fatal(err)
			}
			sent = append(sent, env)
		}
		// Bob comes online and reads the third envelope first.
		first, err := DecodeEnvelope(sent[2])
		if err != nil || first.Prekey == nil {
			t.Fatalf("a first-contact envelope is PREKEY: %v", err)
		}
		keys := ResponderKeys{SPK: bob.spk, SPKID: bob.spkID, OPK: bob.opk, OPKID: bob.opkID}
		bState, err := Respond(bob.id, keys, alice.id.Ed.Public().(ed25519.PublicKey), *first.Prekey, matchID)
		if err != nil {
			t.Fatalf("withOPK=%v respond: %v", withOPK, err)
		}
		for _, i := range []int{2, 0, 1} {
			env, _ := DecodeEnvelope(sent[i])
			p, err := Open(bState, env, mid(byte(i)), now, randKeys{})
			if err != nil || p.MessageID != mid(byte(i)) || string(p.Event.Body) != "while you were away" {
				t.Fatalf("withOPK=%v open %d: %+v %v", withOPK, i, p, err)
			}
		}
		// Bob replies; Alice drops the PREKEY fields once she hears back.
		reply, err := Seal(bState, text(mid(9), "back now"), nil, now)
		if err != nil {
			t.Fatal(err)
		}
		renv, _ := DecodeEnvelope(reply)
		if renv.Prekey != nil {
			t.Fatal("the responder's envelopes are NORMAL")
		}
		if p, err := Open(aState, renv, mid(9), now, randKeys{}); err != nil || string(p.Event.Body) != "back now" {
			t.Fatalf("alice opens the reply: %v", err)
		}
		next, _ := Seal(aState, text(mid(10), "great"), nil, now)
		nenv, _ := DecodeEnvelope(next)
		if p, err := Open(bState, nenv, mid(10), now, randKeys{}); err != nil || string(p.Event.Body) != "great" {
			t.Fatalf("bob opens a NORMAL envelope: %v", err)
		}
		// The same envelope again is a replay: its message key is gone.
		if _, err := Open(bState, nenv, mid(10), now, randKeys{}); err == nil {
			t.Fatal("a replayed envelope must not open twice")
		}
	}
}

func TestARelayCannotRelabelAMessage(t *testing.T) {
	alice, bob := newParty(t, false), newParty(t, false)
	aState, prekey, _ := Initiate(alice.id, bob.bundle, matchID, newX25519(t), newX25519(t))
	env, _ := Seal(aState, text(mid(1), "hi"), prekey, 0)
	e, _ := DecodeEnvelope(env)
	bState, err := Respond(bob.id, ResponderKeys{SPK: bob.spk, SPKID: bob.spkID}, alice.id.Ed.Public().(ed25519.PublicKey), *e.Prekey, matchID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := Open(bState, e, mid(2), 0, randKeys{}); codeOf(err) != lumoserrors.ErrPayloadMalformed {
		t.Fatalf("a delivered id that differs from the authenticated one is refused, got %v", err)
	}
}

func TestRespondRefusesWhatTheMatchDidNotPin(t *testing.T) {
	alice, bob, mallory := newParty(t, false), newParty(t, true), newParty(t, false)
	_, prekey, err := Initiate(alice.id, bob.bundle, matchID, newX25519(t), newX25519(t))
	if err != nil {
		t.Fatal(err)
	}
	keys := ResponderKeys{SPK: bob.spk, SPKID: bob.spkID, OPK: bob.opk, OPKID: bob.opkID}
	aliceEd := alice.id.Ed.Public().(ed25519.PublicKey)
	check := func(name string, k ResponderKeys, who ed25519.PublicKey, p PrekeyFields) {
		t.Helper()
		if _, err := Respond(bob.id, k, who, p, matchID); codeOf(err) != lumoserrors.ErrCryptoPrekeySignatureInvalid {
			t.Errorf("%s: want %s, got %v", name, lumoserrors.ErrCryptoPrekeySignatureInvalid, err)
		}
	}
	check("someone other than the matched peer", keys, mallory.id.Ed.Public().(ed25519.PublicKey), *prekey)
	tampered := *prekey
	tampered.InitiatorIdentityDHSig[0] ^= 1
	check("a tampered identity binding", keys, aliceEd, tampered)
	wrongSPK := *prekey
	wrongSPK.SPKID++
	check("an unknown signed prekey", keys, aliceEd, wrongSPK)
	noOPK := keys
	noOPK.OPK = nil
	check("a named one-time prekey that is already consumed", noOPK, aliceEd, *prekey)
	otherOPK := *prekey
	otherOPK.OPKID++
	check("a one-time prekey id that is not the one supplied", keys, aliceEd, otherOPK)
}

func TestInitiateRefusesAnUnsignedBundle(t *testing.T) {
	alice, bob := newParty(t, false), newParty(t, false)
	bob.bundle.SPKSig = append([]byte(nil), bob.bundle.SPKSig...)
	bob.bundle.SPKSig[0] ^= 1
	if _, _, err := Initiate(alice.id, bob.bundle, matchID, newX25519(t), newX25519(t)); codeOf(err) != lumoserrors.ErrCryptoPrekeySignatureInvalid {
		t.Fatalf("a bundle whose signature fails is refused before any DH: %v", err)
	}
}

func TestMalformedEnvelopesAreRefused(t *testing.T) {
	h := ratchet.Header{PN: 1, N: 2}.Bytes()
	for name, in := range map[string][]byte{
		"empty":                   nil,
		"unknown format":          append([]byte{0x09}, append(h, 1)...),
		"prekey truncated":        append([]byte{FormatPrekey}, make([]byte, prekeyFields-1)...),
		"normal header truncated": append([]byte{FormatNormal}, h[:10]...),
		"normal no ciphertext":    append([]byte{FormatNormal}, h...),
	} {
		if _, err := DecodeEnvelope(in); codeOf(err) != lumoserrors.ErrPayloadMalformed {
			t.Errorf("%s: want %s, got %v", name, lumoserrors.ErrPayloadMalformed, err)
		}
	}
}

func FuzzDecoders(f *testing.F) {
	ev, _ := TextEvent{Kind: lumosconstants.TextEventKindEdit, Target: mid(1), Body: []byte("x")}.Encode()
	f.Add(ev)
	f.Add(append([]byte{FormatNormal}, append(ratchet.Header{}.Bytes(), 1, 2, 3)...))
	f.Add([]byte{FormatPrekey})
	f.Fuzz(func(t *testing.T, b []byte) {
		if e, err := DecodeTextEvent(b); err == nil {
			again, err := e.Encode()
			if err != nil || !bytes.Equal(again, b) {
				t.Fatalf("a decoded event must re-encode to the same bytes")
			}
		}
		if e, err := DecodeEnvelope(b); err == nil && !bytes.Equal(e.Encode(), b) {
			t.Fatalf("a decoded envelope must re-encode to the same bytes")
		}
		_, _ = DecodePlaintext(b)
	})
}
