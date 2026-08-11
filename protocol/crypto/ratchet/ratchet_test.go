// Lumos - protocol/crypto/ratchet / Unit tests: conversation, bounds, deletion.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CRY-002, TRS-CRY-006, TRS-CRY-007, TRS-CRY-013
// ADRs: ADR-001
// Threading: any
// Notes: The deletion tests are the TRS-CRY-006 evidence the owner asked for
// explicitly: a consumed skipped key leaves the store, replaying the same
// ciphertext fails, and advancing a chain destroys the prior chain key. The
// cap test drives real skips across DH turns until the register's session cap
// trips - 2000 stored keys - rather than trusting a mocked counter.
package ratchet

import (
	"bytes"
	"crypto/ecdh"
	"crypto/sha256"
	"errors"
	"fmt"
	"testing"

	"github.com/dharabhishekemail-ops/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekemail-ops/lumos/protocol/gen/go/lumoserrors"
	"github.com/dharabhishekemail-ops/lumos/protocol/wire"
)

// seededProvider hands out deterministic X25519 keys, one per call.
type seededProvider struct {
	label string
	n     int
}

func (p *seededProvider) GenerateDH() (*ecdh.PrivateKey, error) {
	h := sha256.Sum256([]byte(fmt.Sprintf("TESTONLY-ratchet-unit|%s|%d", p.label, p.n)))
	p.n++
	return ecdh.X25519().NewPrivateKey(h[:])
}

func dhFrom(t *testing.T, label string) *ecdh.PrivateKey {
	t.Helper()
	h := sha256.Sum256([]byte("TESTONLY-ratchet-unit|" + label))
	k, err := ecdh.X25519().NewPrivateKey(h[:])
	if err != nil {
		t.Fatalf("NewPrivateKey: %v", err)
	}
	return k
}

var (
	testSK = bytes.Repeat([]byte{0x42}, 32)
	testAD = []byte("TESTONLY-ratchet-unit-ad")
)

// newPair wires an initiator and responder sharing SK/AD.
func newPair(t *testing.T, bounds Bounds) (*State, *State) {
	t.Helper()
	bobRatchet := dhFrom(t, "bob-ratchet-0")
	aliceRatchet := dhFrom(t, "alice-ratchet-0")
	alice, err := NewInitiator(testSK, testAD, bobRatchet.PublicKey(), aliceRatchet, bounds)
	if err != nil {
		t.Fatalf("NewInitiator: %v", err)
	}
	bob := NewResponder(testSK, testAD, bobRatchet, bounds)
	return alice, bob
}

func mustEncrypt(t *testing.T, s *State, msg string) (Header, []byte) {
	t.Helper()
	h, ct, err := s.Encrypt([]byte(msg), 1000)
	if err != nil {
		t.Fatalf("Encrypt(%q): %v", msg, err)
	}
	return h, ct
}

func mustDecrypt(t *testing.T, s *State, h Header, ct []byte, want string) {
	t.Helper()
	pt, err := s.Decrypt(h, ct, 1000, &seededProvider{label: "kp"})
	if err != nil {
		t.Fatalf("Decrypt(%q): %v", want, err)
	}
	if string(pt) != want {
		t.Fatalf("got %q, want %q", pt, want)
	}
}

func TestConversationWithDHTurns(t *testing.T) {
	alice, bob := newPair(t, Bounds{})
	kpA, kpB := &seededProvider{label: "alice"}, &seededProvider{label: "bob"}

	for i := 0; i < 3; i++ {
		h, ct := mustEncrypt(t, alice, fmt.Sprintf("a->b %d", i))
		pt, err := bob.Decrypt(h, ct, 1000, kpB)
		if err != nil || string(pt) != fmt.Sprintf("a->b %d", i) {
			t.Fatalf("bob decrypt %d: %v %q", i, err, pt)
		}
	}
	// Bob replies: his decrypts performed a DH turn, so he has a sending chain.
	for i := 0; i < 2; i++ {
		h, ct := mustEncrypt(t, bob, fmt.Sprintf("b->a %d", i))
		pt, err := alice.Decrypt(h, ct, 1000, kpA)
		if err != nil || string(pt) != fmt.Sprintf("b->a %d", i) {
			t.Fatalf("alice decrypt %d: %v %q", i, err, pt)
		}
	}
	// And back again - a second full turn.
	h, ct := mustEncrypt(t, alice, "another turn")
	mustDecrypt(t, bob, h, ct, "another turn")
}

func TestOutOfOrderWithinChain(t *testing.T) {
	alice, bob := newPair(t, Bounds{})
	type m struct {
		h  Header
		ct []byte
	}
	var msgs []m
	for i := 0; i < 4; i++ {
		h, ct := mustEncrypt(t, alice, fmt.Sprintf("m%d", i))
		msgs = append(msgs, m{h, ct})
	}
	mustDecrypt(t, bob, msgs[0].h, msgs[0].ct, "m0")
	mustDecrypt(t, bob, msgs[3].h, msgs[3].ct, "m3") // skips 1,2
	if bob.SkippedCount() != 2 {
		t.Fatalf("skipped store = %d, want 2", bob.SkippedCount())
	}
	mustDecrypt(t, bob, msgs[1].h, msgs[1].ct, "m1")
	mustDecrypt(t, bob, msgs[2].h, msgs[2].ct, "m2")
	if bob.SkippedCount() != 0 {
		t.Fatalf("skipped keys must be deleted after use (TRS-CRY-006); %d remain", bob.SkippedCount())
	}
}

func TestSkippedAcrossDHTurn(t *testing.T) {
	alice, bob := newPair(t, Bounds{})
	kpA := &seededProvider{label: "alice"}

	h0, ct0 := mustEncrypt(t, alice, "chain1-m0")
	h1, ct1 := mustEncrypt(t, alice, "chain1-m1") // withheld
	mustDecrypt(t, bob, h0, ct0, "chain1-m0")

	hb, ctb := mustEncrypt(t, bob, "reply")
	if _, err := alice.Decrypt(hb, ctb, 1000, kpA); err != nil {
		t.Fatalf("alice reply decrypt: %v", err)
	}

	h2, ct2 := mustEncrypt(t, alice, "chain2-m0")
	mustDecrypt(t, bob, h2, ct2, "chain2-m0") // PN=2 forces storing chain1-m1's key
	if bob.SkippedCount() != 1 {
		t.Fatalf("cross-turn skip store = %d, want 1", bob.SkippedCount())
	}
	mustDecrypt(t, bob, h1, ct1, "chain1-m1")
	if bob.SkippedCount() != 0 {
		t.Fatal("cross-turn skipped key must be deleted after use")
	}
}

// TestMessageKeyDeletionByReplay: after a successful decrypt the message key no
// longer exists anywhere, so replaying the identical ciphertext must fail.
func TestMessageKeyDeletionByReplay(t *testing.T) {
	alice, bob := newPair(t, Bounds{})
	h, ct := mustEncrypt(t, alice, "once only")
	mustDecrypt(t, bob, h, ct, "once only")

	_, err := bob.Decrypt(h, ct, 1000, &seededProvider{label: "kp"})
	if err == nil {
		t.Fatal("replayed ciphertext decrypted; message key survived its use (TRS-CRY-006)")
	}
	var we *wire.Error
	if !errors.As(err, &we) || we.Code != lumoserrors.ErrCryptoAeadAuthFailed {
		t.Fatalf("replay must die as an auth failure with no oracle detail, got %v", err)
	}
}

func TestMaxSkipBreach(t *testing.T) {
	alice, bob := newPair(t, Bounds{})

	// Advance Alice far beyond MAX_SKIP, keeping only the last message.
	var lastH Header
	var lastCT []byte
	limit := lumosconstants.MaxSkipPerChainDefault + 2
	for i := 0; i < limit; i++ {
		lastH, lastCT = mustEncrypt(t, alice, "flood")
	}
	_, err := bob.Decrypt(lastH, lastCT, 1000, &seededProvider{label: "kp"})
	var we *wire.Error
	if !errors.As(err, &we) || we.Code != lumoserrors.ErrCryptoMaxSkipExceeded {
		t.Fatalf("gap of %d must fail with %s, got %v", limit-1, lumoserrors.ErrCryptoMaxSkipExceeded, err)
	}
}

// TestSkippedSessionCapFromRegister accumulates genuine skipped keys across DH
// turns - each chain gap stays under MAX_SKIP - until the per-session cap of
// the register trips.
func TestSkippedSessionCapFromRegister(t *testing.T) {
	alice, bob := newPair(t, Bounds{})
	// One persistent provider per party: a fresh provider each round would
	// re-issue identical "random" ratchet keys, which real CSPRNG-backed
	// generation (TRS-CRY-012) can never do.
	kpA := &seededProvider{label: "alice"}
	kpB := &seededProvider{label: "bob"}

	cap := lumosconstants.SkippedKeysPerSessionCapDefault
	perChain := lumosconstants.MaxSkipPerChainDefault // 512 per turn

	turns := 0
	for bob.SkippedCount() < cap {
		turns++
		if turns > 10 {
			t.Fatalf("cap did not trip after %d turns; skipped=%d", turns, bob.SkippedCount())
		}
		// Alice floods one chain, Bob only sees the last message.
		var h Header
		var ct []byte
		for i := 0; i <= perChain; i++ {
			h, ct = mustEncrypt(t, alice, "skip filler")
		}
		_, err := bob.Decrypt(h, ct, 1000, kpB)
		if err != nil {
			var we *wire.Error
			if errors.As(err, &we) && we.Code == lumoserrors.ErrCryptoSkippedKeyCap {
				if bob.SkippedCount() > cap {
					t.Fatalf("store exceeded the cap: %d > %d", bob.SkippedCount(), cap)
				}
				return // cap enforced - success
			}
			t.Fatalf("unexpected error before cap: %v", err)
		}
		// Bob replies so Alice turns to a fresh chain next round.
		hb, ctb := mustEncrypt(t, bob, "ack")
		if _, err := alice.Decrypt(hb, ctb, 1000, kpA); err != nil {
			t.Fatalf("alice ack decrypt: %v", err)
		}
	}
	t.Fatalf("accumulated %d skipped keys without the cap firing", bob.SkippedCount())
}

// TestSkippedKeyTTLExpiry stores a skipped key at t0, advances the injected
// clock past the register TTL, and proves the key was pruned: the late message
// fails authentication instead of decrypting.
func TestSkippedKeyTTLExpiry(t *testing.T) {
	alice, bob := newPair(t, Bounds{})

	h0, ct0 := mustEncrypt(t, alice, "early") // n=0, withheld
	h1, ct1 := mustEncrypt(t, alice, "later") // n=1
	if _, err := bob.Decrypt(h1, ct1, 1000, &seededProvider{label: "kp"}); err != nil {
		t.Fatalf("decrypt later: %v", err)
	}
	if bob.SkippedCount() != 1 {
		t.Fatalf("expected 1 stored skipped key, have %d", bob.SkippedCount())
	}

	ttl := int64(lumosconstants.SkippedKeyTTLDaysDefault) * 24 * 60 * 60
	h2, ct2 := mustEncrypt(t, alice, "after ttl")
	if _, err := bob.Decrypt(h2, ct2, 1000+ttl+1, &seededProvider{label: "kp"}); err != nil {
		t.Fatalf("decrypt after ttl: %v", err)
	}
	if bob.SkippedCount() != 0 {
		t.Fatalf("TTL prune left %d keys (TRS-CRY-007)", bob.SkippedCount())
	}

	_, err := bob.Decrypt(h0, ct0, 1000+ttl+2, &seededProvider{label: "kp"})
	var we *wire.Error
	if !errors.As(err, &we) || we.Code != lumoserrors.ErrCryptoAeadAuthFailed {
		t.Fatalf("expired skipped message must fail closed, got %v", err)
	}
}

func TestHeaderRoundtrip(t *testing.T) {
	h := Header{PN: 7, N: 9}
	copy(h.DH[:], bytes.Repeat([]byte{0xAB}, 32))
	parsed, err := ParseHeader(h.Bytes())
	if err != nil {
		t.Fatalf("ParseHeader: %v", err)
	}
	if parsed != h {
		t.Error("header roundtrip mismatch")
	}
	if len(h.Bytes()) != HeaderSize {
		t.Errorf("header length %d, register says %d", len(h.Bytes()), HeaderSize)
	}
	if _, err := ParseHeader(h.Bytes()[:39]); err == nil {
		t.Error("short header must be rejected")
	}
}
