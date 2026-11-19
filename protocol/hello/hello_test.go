// Lumos - protocol/hello / Exchange semantics: trust, spoofing, replay.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-PROTO-017, TRS-PROTO-018, TRS-ENV-002, SRS-IDN-002, TRS-TST-001
// ADRs: ADR-002, ADR-008
// Threading: any
// Notes: The golden-vector family pins the cross-language bytes; these tests
// pin the SEMANTICS the vectors are generated from - above all the
// TRS-ENV-002 shape: every way an attacker can present identity bytes without
// holding the identity ends in a stable code and no Peer, and the error never
// tells the attacker which check caught it... every rejection is silent at
// the radio in production, the code exists for this side's own accounting.
package hello

import (
	"bytes"
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/sha256"
	"errors"
	"testing"

	"github.com/dharabhishekorg/lumos/protocol/crypto/x3dh"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumoserrors"
	"github.com/dharabhishekorg/lumos/protocol/wire"
)

// ecdhKey builds an X25519 private key from a raw scalar.
func ecdhKey(scalar []byte) (*ecdh.PrivateKey, error) {
	return ecdh.X25519().NewPrivateKey(scalar)
}

// testParty builds a deterministic identity and its hello fields.
type testParty struct {
	ed     ed25519.PrivateKey
	fields Fields
	ex     *Exchange
}

func newParty(t *testing.T, seedByte byte) *testParty {
	t.Helper()
	seed := bytes.Repeat([]byte{seedByte}, ed25519.SeedSize)
	ed := ed25519.NewKeyFromSeed(seed)

	// A deterministic X25519 scalar; the binding is what matters here.
	scalar := sha256.Sum256(append([]byte("TESTONLY-hello-x"), seedByte))
	xPriv, err := ecdhKey(scalar[:])
	if err != nil {
		t.Fatal(err)
	}

	f := Fields{
		ProtocolVersion: lumosconstants.ProtocolVersionEncoded,
		CapabilityBits:  0x0003,
		VenueHint:       0,
	}
	copy(f.Ed25519Public[:], ed.Public().(ed25519.PublicKey))
	copy(f.X25519Public[:], xPriv.PublicKey().Bytes())
	copy(f.BindingSig[:], x3dh.SignIdentityDH(ed, xPriv.PublicKey()))
	return &testParty{ed: ed, fields: f, ex: NewExchange(ed, f)}
}

func nonce(b byte) (n [16]byte) {
	for i := range n {
		n[i] = b
	}
	return n
}

func code(t *testing.T, err error) string {
	t.Helper()
	var we *wire.Error
	if !errors.As(err, &we) {
		t.Fatalf("error %v carries no stable code", err)
	}
	return we.Code
}

func TestTheDualExchangeAuthenticatesBothSides(t *testing.T) {
	alice := newParty(t, 1)
	bob := newParty(t, 2)

	// Alice initiates her direction; Bob answers; Alice gains Bob.
	aliceHello := alice.ex.Start(nonce(0xA1))
	bobAck, err := bob.ex.OnHello(aliceHello)
	if err != nil {
		t.Fatal(err)
	}
	peerOfAlice, err := alice.ex.OnAck(bobAck)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(peerOfAlice.Fields.Ed25519Public[:], bob.fields.Ed25519Public[:]) {
		t.Fatal("alice authenticated someone other than bob")
	}
	if !bytes.Equal(peerOfAlice.ID, PeerID(bob.fields.Ed25519Public[:])) {
		t.Fatal("peer id must derive from identity material (SRS-IDN-002)")
	}

	// Bob runs his own direction for his own presence of Alice.
	bobHello := bob.ex.Start(nonce(0xB2))
	aliceAck, err := alice.ex.OnHello(bobHello)
	if err != nil {
		t.Fatal(err)
	}
	peerOfBob, err := bob.ex.OnAck(aliceAck)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(peerOfBob.Fields.Ed25519Public[:], alice.fields.Ed25519Public[:]) {
		t.Fatal("bob authenticated someone other than alice")
	}
}

func TestAHintlessPeerWalksTheSamePath(t *testing.T) {
	// TRS-PROTO-018: every iOS peer, and any degraded Android peer, has a
	// zero EID and must behave identically.
	alice := newParty(t, 1)
	ios := newParty(t, 3) // EID stays zero in its fields
	h := alice.ex.Start(nonce(0x01))
	ack, err := ios.ex.OnHello(h)
	if err != nil {
		t.Fatal(err)
	}
	peer, err := alice.ex.OnAck(ack)
	if err != nil {
		t.Fatal(err)
	}
	if peer.Fields.EID != [12]byte{} {
		t.Fatal("test premise broken: the hintless peer carried an EID")
	}
}

func TestAReplayedAckGrantsNothing(t *testing.T) {
	alice := newParty(t, 1)
	bob := newParty(t, 2)

	h1 := alice.ex.Start(nonce(0x11))
	ack1, err := bob.ex.OnHello(h1)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := alice.ex.OnAck(ack1); err != nil {
		t.Fatal(err)
	}

	// The replay shape: the same ACK again, with nothing outstanding.
	_, err = alice.ex.OnAck(ack1)
	if err == nil || code(t, err) != lumoserrors.ErrHelloNonceMismatch {
		t.Fatalf("replayed ack: %v", err)
	}

	// And against a FRESH hello, the old ACK's signature no longer covers
	// the new nonce.
	alice.ex.Start(nonce(0x12))
	_, err = alice.ex.OnAck(ack1)
	if err == nil || code(t, err) != lumoserrors.ErrHelloSignatureInvalid {
		t.Fatalf("stale ack against a fresh hello: %v", err)
	}
}

func TestASpoofedIdentityFailsClosed(t *testing.T) {
	alice := newParty(t, 1)
	bob := newParty(t, 2)
	mallory := newParty(t, 9)

	h := alice.ex.Start(nonce(0x21))
	ack, err := bob.ex.OnHello(h)
	if err != nil {
		t.Fatal(err)
	}

	// Mallory grafts Bob's identity fields onto her own signature.
	forged := append([]byte(nil), ack...)
	sig := ed25519.Sign(mallory.ed, ackAuthMessage(h, forged[:HelloLen]))
	copy(forged[HelloLen:], sig)
	_, err = alice.ex.OnAck(forged)
	if err == nil || code(t, err) != lumoserrors.ErrHelloSignatureInvalid {
		t.Fatalf("spoofed signature: %v", err)
	}

	// Mallory claims Bob's DH key under her own Ed25519 identity: the
	// ADR-008 binding does not transplant.
	cross := mallory.fields
	cross.X25519Public = bob.fields.X25519Public
	crossBytes := NewExchange(mallory.ed, cross).Start(nonce(0x22))
	_, err = ParseHello(crossBytes)
	if err == nil || code(t, err) != lumoserrors.ErrHelloBindingInvalid {
		t.Fatalf("transplanted binding: %v", err)
	}
}

func TestStructuralAndVersionRejections(t *testing.T) {
	alice := newParty(t, 1)
	h := alice.ex.Start(nonce(0x31))

	if _, err := ParseHello(h[:HelloLen-1]); err == nil ||
		code(t, err) != lumoserrors.ErrHelloMalformed {
		t.Fatal("truncated hello must be malformed")
	}

	wrongMajor := append([]byte(nil), h...)
	wrongMajor[0] = 0x20
	_, err := ParseHello(wrongMajor)
	if err == nil || code(t, err) != lumoserrors.ErrHelloVersionUnsupported {
		t.Fatalf("major version: %v", err)
	}

	wrongHelloVersion := append([]byte(nil), h...)
	wrongHelloVersion[1] = 2
	_, err = ParseHello(wrongHelloVersion)
	if err == nil || code(t, err) != lumoserrors.ErrHelloMalformed {
		t.Fatalf("hello version: %v", err)
	}
}

func TestAnEchoOfOurOwnIdentityIsNotAPeer(t *testing.T) {
	alice := newParty(t, 1)
	h := alice.ex.Start(nonce(0x41))
	// A reflector answers with Alice's own identity.
	ack := BuildAck(&alice.fields, h, alice.ed)
	_, err := alice.ex.OnAck(ack)
	if err == nil || code(t, err) != lumoserrors.ErrHelloSignatureInvalid {
		t.Fatalf("reflected identity: %v", err)
	}
}

func TestPeerIDIsStableAcrossEIDRotation(t *testing.T) {
	// SRS-NBY-006's foundation: the EID rotates, the identifier does not.
	bob := newParty(t, 2)
	id1 := PeerID(bob.fields.Ed25519Public[:])
	rotated := bob.fields
	rotated.EID = [12]byte{9, 9, 9}
	id2 := PeerID(rotated.Ed25519Public[:])
	if !bytes.Equal(id1, id2) {
		t.Fatal("peer id must not depend on the rotating EID")
	}
	if len(id1) != PeerIDLen {
		t.Fatalf("peer id length %d", len(id1))
	}
}
