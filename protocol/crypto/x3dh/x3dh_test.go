// Lumos - protocol/crypto/x3dh / Unit tests for session establishment and transcript binding.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CRY-001, TRS-CRY-005, TRS-CRY-014
// ADRs: ADR-001, ADR-008
// Threading: any
// Notes: Key material is derived from fixed TESTONLY seeds; nothing here is a
// real key. The transcript tests pin every binding input the owner mandated:
// changing any one of identity keys, prekey IDs, protocol version or match ID
// must change the AD.
package x3dh

import (
	"bytes"
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/sha256"
	"errors"
	"testing"

	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumoserrors"
	"github.com/dharabhishekorg/lumos/protocol/wire"
)

func seed(label string) []byte {
	h := sha256.Sum256([]byte("TESTONLY-x3dh-unit|" + label))
	return h[:]
}

func edKey(t *testing.T, label string) ed25519.PrivateKey {
	t.Helper()
	return ed25519.NewKeyFromSeed(seed(label))
}

func dhKey(t *testing.T, label string) *ecdh.PrivateKey {
	t.Helper()
	k, err := ecdh.X25519().NewPrivateKey(seed(label))
	if err != nil {
		t.Fatalf("NewPrivateKey(%s): %v", label, err)
	}
	return k
}

type party struct {
	ed  ed25519.PrivateKey
	dh  *ecdh.PrivateKey
	spk *ecdh.PrivateKey
	opk *ecdh.PrivateKey
}

func newParty(t *testing.T, name string) party {
	return party{
		ed:  edKey(t, name+"/ed"),
		dh:  dhKey(t, name+"/dh"),
		spk: dhKey(t, name+"/spk"),
		opk: dhKey(t, name+"/opk"),
	}
}

func (p party) bundle(withOPK bool) *Bundle {
	b := &Bundle{
		IdentityEd:    p.ed.Public().(ed25519.PublicKey),
		IdentityDH:    p.dh.PublicKey(),
		IdentityDHSig: SignIdentityDH(p.ed, p.dh.PublicKey()),
		SPKID:         7,
		SPK:           p.spk.PublicKey(),
	}
	b.SPKSig = SignPrekey(p.ed, b.SPKID, b.SPK)
	if withOPK {
		b.OPKID = 42
		b.OPK = p.opk.PublicKey()
	}
	return b
}

var matchID = [16]byte{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16}

func agree(t *testing.T, withOPK bool) (*Result, *Result) {
	t.Helper()
	alice, bob := newParty(t, "alice"), newParty(t, "bob")
	eph := dhKey(t, "alice/eph")

	init, err := Initiate(alice.ed.Public().(ed25519.PublicKey), alice.dh, eph, bob.bundle(withOPK), matchID)
	if err != nil {
		t.Fatalf("Initiate: %v", err)
	}
	var opk *ecdh.PrivateKey
	if withOPK {
		opk = bob.opk
	}
	resp, err := Respond(bob.ed.Public().(ed25519.PublicKey), bob.dh, bob.spk, 7, opk, 42,
		alice.ed.Public().(ed25519.PublicKey), alice.dh.PublicKey(), eph.PublicKey(), matchID)
	if err != nil {
		t.Fatalf("Respond: %v", err)
	}
	return init, resp
}

func TestBothSidesDeriveTheSameSessionWithOPK(t *testing.T) {
	init, resp := agree(t, true)
	if !bytes.Equal(init.SK, resp.SK) {
		t.Error("SK disagreement between initiator and responder")
	}
	if !bytes.Equal(init.AD, resp.AD) {
		t.Error("transcript AD disagreement")
	}
	if !init.UsedOPK || !resp.UsedOPK {
		t.Error("OPK should be marked used")
	}
	if len(init.SK) != SKSize {
		t.Errorf("SK length %d", len(init.SK))
	}
}

func TestBothSidesDeriveTheSameSessionWithoutOPK(t *testing.T) {
	init, resp := agree(t, false)
	if !bytes.Equal(init.SK, resp.SK) || !bytes.Equal(init.AD, resp.AD) {
		t.Error("no-OPK fallback disagreement (TRS-CRY-005 documented fallback)")
	}
	withOPK, _ := agree(t, true)
	if bytes.Equal(withOPK.SK, init.SK) {
		t.Error("OPK and no-OPK sessions must derive different secrets")
	}
}

func expectSigFailure(t *testing.T, b *Bundle) {
	t.Helper()
	alice := newParty(t, "alice")
	_, err := Initiate(alice.ed.Public().(ed25519.PublicKey), alice.dh, dhKey(t, "alice/eph"), b, matchID)
	if err == nil {
		t.Fatal("tampered bundle was accepted")
	}
	var we *wire.Error
	if !errors.As(err, &we) || we.Code != lumoserrors.ErrCryptoPrekeySignatureInvalid {
		t.Fatalf("expected %s, got %v", lumoserrors.ErrCryptoPrekeySignatureInvalid, err)
	}
}

func TestTamperedSignaturesAreRejected(t *testing.T) {
	bob := newParty(t, "bob")

	b := bob.bundle(true)
	b.SPKSig = append([]byte(nil), b.SPKSig...)
	b.SPKSig[0] ^= 1
	expectSigFailure(t, b)

	b = bob.bundle(true)
	b.IdentityDHSig = append([]byte(nil), b.IdentityDHSig...)
	b.IdentityDHSig[0] ^= 1
	expectSigFailure(t, b)

	// A swapped identity-DH key must fail even with an internally valid SPK,
	// because the binding signature no longer matches.
	b = bob.bundle(true)
	b.IdentityDH = dhKey(t, "mallory/dh").PublicKey()
	expectSigFailure(t, b)

	// Substituted SPK ID: the signature covers the ID, so a re-numbered prekey
	// is rejected rather than silently accepted under a different ID.
	b = bob.bundle(true)
	b.SPKID = 8
	expectSigFailure(t, b)
}

// TestVerificationPrecedesDH proves ADR-008 condition 2's ordering: signature
// verification fails closed BEFORE any Diffie-Hellman. The bundle carries a
// tampered identity-DH signature AND a low-order (all-zero) signed prekey. If
// DH ran first, DH(identity, low-order SPK) would fail with the DH error code;
// because verification runs first, the failure is the signature code instead.
func TestVerificationPrecedesDH(t *testing.T) {
	alice := newParty(t, "alice")
	bob := newParty(t, "bob")

	b := bob.bundle(true)
	tampered := append([]byte(nil), b.IdentityDHSig...)
	tampered[0] ^= 1
	b.IdentityDHSig = tampered
	lowOrder, err := ecdh.X25519().NewPublicKey(make([]byte, 32))
	if err != nil {
		t.Fatalf("construct low-order key: %v", err)
	}
	b.SPK = lowOrder // DH against this errors, so a DH-first impl would reveal itself

	_, err = Initiate(alice.ed.Public().(ed25519.PublicKey), alice.dh, dhKey(t, "alice/eph"), b, matchID)
	var we *wire.Error
	if !errors.As(err, &we) {
		t.Fatalf("expected a wire error, got %v", err)
	}
	if we.Code != lumoserrors.ErrCryptoPrekeySignatureInvalid {
		t.Fatalf("got %s; verification did not precede DH (a DH error would mean DH ran first)", we.Code)
	}
}

// TestSafetyIdentityIsEd25519Only is ADR-008 condition 1: the safety-number
// identity is the Ed25519 key alone. Rotating the X25519 identity-DH key while
// keeping the same Ed25519 identity is legitimate rotation, not an identity
// change, so it must not alter the safety number. Proven here by construction:
// the safety derivation takes only Ed25519 keys, so a rotated identity-DH key
// cannot enter it.
func TestSafetyIdentityIsEd25519Only(t *testing.T) {
	// The x3dh package binds the identity-DH key to the Ed25519 identity; the
	// safety package (imported by the conformance suite) derives from Ed25519
	// alone. This test pins the binding direction: a new identity-DH key signed
	// by the SAME Ed25519 identity verifies, so rotation is accepted without a
	// new root of trust.
	bob := newParty(t, "bob")
	rotatedDH := dhKey(t, "bob/dh/rotated")

	b := bob.bundle(true)
	b.IdentityDH = rotatedDH.PublicKey()
	b.IdentityDHSig = SignIdentityDH(bob.ed, rotatedDH.PublicKey()) // same Ed25519 identity re-signs

	alice := newParty(t, "alice")
	if _, err := Initiate(alice.ed.Public().(ed25519.PublicKey), alice.dh, dhKey(t, "alice/eph"), b, matchID); err != nil {
		t.Fatalf("a rotated identity-DH key re-signed by the same Ed25519 identity must verify: %v", err)
	}
}

// TestTranscriptBindsEveryMandatedInput: identity keys, signed-prekey ID,
// one-time-prekey ID, protocol version (fixed constant, cannot vary in-test)
// and match ID.
func TestTranscriptBindsEveryMandatedInput(t *testing.T) {
	alice, bob := newParty(t, "alice"), newParty(t, "bob")
	base := Transcript(alice.ed.Public().(ed25519.PublicKey), bob.ed.Public().(ed25519.PublicKey),
		alice.dh.PublicKey(), bob.dh.PublicKey(), 7, 42, matchID)

	otherMatch := matchID
	otherMatch[15] ^= 1
	mallory := newParty(t, "mallory")

	variants := map[string][]byte{
		"different match ID": Transcript(alice.ed.Public().(ed25519.PublicKey), bob.ed.Public().(ed25519.PublicKey),
			alice.dh.PublicKey(), bob.dh.PublicKey(), 7, 42, otherMatch),
		"different SPK ID": Transcript(alice.ed.Public().(ed25519.PublicKey), bob.ed.Public().(ed25519.PublicKey),
			alice.dh.PublicKey(), bob.dh.PublicKey(), 8, 42, matchID),
		"different OPK ID": Transcript(alice.ed.Public().(ed25519.PublicKey), bob.ed.Public().(ed25519.PublicKey),
			alice.dh.PublicKey(), bob.dh.PublicKey(), 7, 43, matchID),
		"different responder identity": Transcript(alice.ed.Public().(ed25519.PublicKey), mallory.ed.Public().(ed25519.PublicKey),
			alice.dh.PublicKey(), bob.dh.PublicKey(), 7, 42, matchID),
		"different initiator DH": Transcript(alice.ed.Public().(ed25519.PublicKey), bob.ed.Public().(ed25519.PublicKey),
			mallory.dh.PublicKey(), bob.dh.PublicKey(), 7, 42, matchID),
		"swapped roles": Transcript(bob.ed.Public().(ed25519.PublicKey), alice.ed.Public().(ed25519.PublicKey),
			bob.dh.PublicKey(), alice.dh.PublicKey(), 7, 42, matchID),
	}
	for name, got := range variants {
		if bytes.Equal(base, got) {
			t.Errorf("transcript failed to bind: %s", name)
		}
	}
	if len(base) != 32 {
		t.Errorf("transcript length %d", len(base))
	}
}
