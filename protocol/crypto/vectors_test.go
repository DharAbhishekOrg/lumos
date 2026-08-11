// Lumos - protocol/crypto / Go conformance consumption of the committed crypto vectors.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-TST-001, TRS-CRY-015, SRS-NFR-CMP-004
// ADRs: ADR-001, ADR-005, ADR-008
// Threading: any
// Notes: The Go third of the crypto conformance contract, run under
// 'go test ./protocol/...'. It consumes the COMMITTED files and reconstructs
// each result from public inputs plus its own secret halves - it does not trust
// the recorded outputs, it recomputes them. A missing file is t.Fatal: a skipped
// vector file is a failed gate (TRS Sec. 18.4).
package crypto_test

import (
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"

	"github.com/dharabhishekemail-ops/lumos/protocol/crypto/aead"
	"github.com/dharabhishekemail-ops/lumos/protocol/crypto/ratchet"
	"github.com/dharabhishekemail-ops/lumos/protocol/crypto/safety"
	"github.com/dharabhishekemail-ops/lumos/protocol/crypto/x3dh"
	"github.com/dharabhishekemail-ops/lumos/protocol/schema"
	"github.com/dharabhishekemail-ops/lumos/protocol/wire"
)

func root(t *testing.T) string {
	t.Helper()
	p, err := filepath.Abs(filepath.Join("..", ".."))
	if err != nil {
		t.Fatalf("resolve repo root: %v", err)
	}
	return p
}

func load(t *testing.T, rel string, into any) {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join(root(t), filepath.FromSlash(rel)))
	if err != nil {
		t.Fatalf("committed vector %s unreadable - a missing vector file is a failed gate: %v", rel, err)
	}
	if err := json.Unmarshal(raw, into); err != nil {
		t.Fatalf("parse %s: %v", rel, err)
	}
}

func hexDecode(t *testing.T, s string) []byte {
	t.Helper()
	b, err := hex.DecodeString(s)
	if err != nil {
		t.Fatalf("bad hex %q: %v", s, err)
	}
	return b
}

func regHash(t *testing.T) string {
	t.Helper()
	reg, err := schema.LoadRegister(root(t))
	if err != nil {
		t.Fatalf("LoadRegister: %v", err)
	}
	h, err := reg.String("constants_hash")
	if err != nil {
		t.Fatalf("constants_hash: %v", err)
	}
	return h
}

func pubDH(t *testing.T, h string) *ecdh.PublicKey {
	t.Helper()
	k, err := ecdh.X25519().NewPublicKey(hexDecode(t, h))
	if err != nil {
		t.Fatalf("bad X25519 public key: %v", err)
	}
	return k
}

// TestConformanceX3DHKAT reconstructs the transcript from public inputs and
// checks it matches; the shared secret itself is validated transitively through
// the ratchet KAT, which decrypts real ciphertext under keys derived from it.
func TestConformanceX3DHKAT(t *testing.T) {
	var vf struct {
		ConstantsHash string `json:"constants_hash"`
		MatchIDHex    string `json:"match_id_hex"`
		Cases         []struct {
			Name             string `json:"name"`
			WithOPK          bool   `json:"with_one_time_prekey"`
			InitiatorIDEdHex string `json:"initiator_identity_ed_pub_hex"`
			InitiatorIDDHHex string `json:"initiator_identity_dh_pub_hex"`
			ResponderIDEdHex string `json:"responder_identity_ed_pub_hex"`
			ResponderIDDHHex string `json:"responder_identity_dh_pub_hex"`
			SPKID            uint32 `json:"signed_prekey_id"`
			OPKID            uint32 `json:"one_time_prekey_id"`
			TranscriptADHex  string `json:"transcript_ad_hex"`
		} `json:"cases"`
		Negatives []struct {
			Name             string `json:"name"`
			ResponderIDEdHex string `json:"responder_identity_ed_pub_hex"`
			ResponderIDDHHex string `json:"responder_identity_dh_pub_hex"`
			IdentityDHSigHex string `json:"identity_dh_sig_hex"`
			SPKID            uint32 `json:"signed_prekey_id"`
			ResponderSPKHex  string `json:"responder_signed_prekey_pub_hex"`
			SPKSigHex        string `json:"signed_prekey_sig_hex"`
			ExpectedError    string `json:"expected_error"`
		} `json:"negatives"`
	}
	load(t, "test-vectors/crypto/x3dh_kat.json", &vf)
	if vf.ConstantsHash != regHash(t) {
		t.Fatal("stale x3dh vectors (TRS-CONST-005)")
	}
	if len(vf.Cases) == 0 {
		t.Fatal("no x3dh cases")
	}

	var matchID [16]byte
	copy(matchID[:], hexDecode(t, vf.MatchIDHex))

	for _, c := range vf.Cases {
		t.Run(c.Name, func(t *testing.T) {
			got := x3dh.Transcript(
				ed25519.PublicKey(hexDecode(t, c.InitiatorIDEdHex)),
				ed25519.PublicKey(hexDecode(t, c.ResponderIDEdHex)),
				pubDH(t, c.InitiatorIDDHHex), pubDH(t, c.ResponderIDDHHex),
				c.SPKID, c.OPKID, matchID)
			if hex.EncodeToString(got) != c.TranscriptADHex {
				t.Errorf("transcript mismatch:\n got %s\nwant %s", hex.EncodeToString(got), c.TranscriptADHex)
			}
		})
	}

	// ADR-008 condition 2: each negative bundle must be rejected by signature
	// verification with its exact stable code.
	if len(vf.Negatives) == 0 {
		t.Fatal("x3dh vectors carry no negative (ADR-008 condition 2 requires at least one)")
	}
	for _, n := range vf.Negatives {
		t.Run("negative/"+n.Name, func(t *testing.T) {
			bundle, err := x3dh.ParsePublicBundle(x3dh.PublicBundle{
				IdentityEd:    hexDecode(t, n.ResponderIDEdHex),
				IdentityDH:    hexDecode(t, n.ResponderIDDHHex),
				IdentityDHSig: hexDecode(t, n.IdentityDHSigHex),
				SPKID:         n.SPKID,
				SPK:           hexDecode(t, n.ResponderSPKHex),
				SPKSig:        hexDecode(t, n.SPKSigHex),
			})
			if err == nil {
				err = x3dh.VerifyBundle(bundle)
			}
			var we *wire.Error
			if !errors.As(err, &we) || we.Code != n.ExpectedError {
				t.Fatalf("negative %s: expected %s, got %v", n.Name, n.ExpectedError, err)
			}
		})
	}
}

// TestConformanceRatchetSequences replays each sequence in delivery order and
// checks every plaintext, then drives the MAX_SKIP negative to its exact code.
func TestConformanceRatchetSequences(t *testing.T) {
	var vf struct {
		ConstantsHash   string `json:"constants_hash"`
		SharedSecretHex string `json:"shared_secret_hex"`
		ADHex           string `json:"associated_data_hex"`
		Sequences       []struct {
			Name     string `json:"name"`
			Messages []struct {
				Direction     string `json:"direction"`
				HeaderHex     string `json:"header_hex"`
				CiphertextHex string `json:"ciphertext_hex"`
				PlaintextHex  string `json:"plaintext_hex"`
				DeliverIndex  int    `json:"deliver_index"`
			} `json:"messages"`
		} `json:"sequences"`
		Negatives []struct {
			Name          string `json:"name"`
			ExpectedError string `json:"expected_error"`
			Gap           int    `json:"gap"`
		} `json:"negatives"`
	}
	load(t, "test-vectors/crypto/ratchet_sequences.json", &vf)
	if vf.ConstantsHash != regHash(t) {
		t.Fatal("stale ratchet vectors (TRS-CONST-005)")
	}

	sk := hexDecode(t, vf.SharedSecretHex)
	ad := hexDecode(t, vf.ADHex)

	for _, seq := range vf.Sequences {
		t.Run(seq.Name, func(t *testing.T) {
			// A full two-party replay: the receiver of an a2b message is Bob, of a
			// b2a message is Alice. Single-direction sequences simply never build
			// Alice's receiving side. Each party keeps its own deterministic KP,
			// matching the generator's kpA/kpB.
			alice := newInitiator(t, sk, ad)
			bob := newResponder(t, sk, ad)
			kpA := &seededKP{label: "alice"}
			kpB := &seededKP{label: "bob"}

			for _, m := range seq.Messages {
				h, err := ratchet.ParseHeader(hexDecode(t, m.HeaderHex))
				if err != nil {
					t.Fatalf("ParseHeader: %v", err)
				}
				var receiver *ratchet.State
				var kp ratchet.KeyProvider
				switch m.Direction {
				case "a2b":
					receiver, kp = bob, kpB
				case "b2a":
					receiver, kp = alice, kpA
				default:
					t.Fatalf("unknown direction %q", m.Direction)
				}
				pt, err := receiver.Decrypt(h, hexDecode(t, m.CiphertextHex), 1_700_000_000, kp)
				if err != nil {
					t.Fatalf("Decrypt %s (%s): %v", seq.Name, m.Direction, err)
				}
				if hex.EncodeToString(pt) != m.PlaintextHex {
					t.Errorf("plaintext mismatch in %s: got %x want %s", seq.Name, pt, m.PlaintextHex)
				}
			}
		})
	}

	for _, neg := range vf.Negatives {
		t.Run(neg.Name, func(t *testing.T) {
			alice := newInitiator(t, sk, ad)
			var lastH ratchet.Header
			var lastCT []byte
			for i := 0; i <= neg.Gap; i++ {
				h, ct, err := alice.Encrypt([]byte("x"), 1_700_000_000)
				if err != nil {
					t.Fatalf("Encrypt: %v", err)
				}
				lastH, lastCT = h, ct
			}
			bob := newResponder(t, sk, ad)
			_, err := bob.Decrypt(lastH, lastCT, 1_700_000_000, &seededKP{label: "bob"})
			var we *wire.Error
			if !errors.As(err, &we) || we.Code != neg.ExpectedError {
				t.Fatalf("gap %d: expected %s, got %v", neg.Gap, neg.ExpectedError, err)
			}
		})
	}
}

// seededKP mirrors the generator's deterministic key provider so the receiver
// reproduces the same DH turns.
type seededKP struct {
	label string
	n     int
}

func (k *seededKP) GenerateDH() (*ecdh.PrivateKey, error) {
	seed := sha256.Sum256([]byte(cryptoLabel(k.label, k.n)))
	k.n++
	return ecdh.X25519().NewPrivateKey(seed[:])
}

func cryptoLabel(label string, n int) string {
	// Must match tools/vectorgen: KeyProvenance|crypto|<label>|ratchet-kp|<n>.
	return "TESTONLY-lumos-vectors-v1|crypto|" + label + "|ratchet-kp|" + itoa(n)
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var b []byte
	for n > 0 {
		b = append([]byte{byte('0' + n%10)}, b...)
		n /= 10
	}
	return string(b)
}

func newInitiator(t *testing.T, sk, ad []byte) *ratchet.State {
	t.Helper()
	bob := dhSeed(t, "TESTONLY-lumos-vectors-v1|crypto|ratchet|bob-0")
	alice := dhSeed(t, "TESTONLY-lumos-vectors-v1|crypto|ratchet|alice-0")
	s, err := ratchet.NewInitiator(sk, ad, bob.PublicKey(), alice, ratchet.Bounds{})
	if err != nil {
		t.Fatalf("NewInitiator: %v", err)
	}
	return s
}

func newResponder(t *testing.T, sk, ad []byte) *ratchet.State {
	t.Helper()
	bob := dhSeed(t, "TESTONLY-lumos-vectors-v1|crypto|ratchet|bob-0")
	return ratchet.NewResponder(sk, ad, bob, ratchet.Bounds{})
}

func dhSeed(t *testing.T, label string) *ecdh.PrivateKey {
	t.Helper()
	seed := sha256.Sum256([]byte(label))
	k, err := ecdh.X25519().NewPrivateKey(seed[:])
	if err != nil {
		t.Fatalf("NewPrivateKey: %v", err)
	}
	return k
}

func TestConformanceAEADKAT(t *testing.T) {
	var vf struct {
		ConstantsHash string `json:"constants_hash"`
		Positive      []struct {
			Name          string `json:"name"`
			MessageKeyHex string `json:"message_key_hex"`
			NonceHex      string `json:"nonce_hex"`
			AADHex        string `json:"aad_hex"`
			PlaintextHex  string `json:"plaintext_hex"`
			CiphertextHex string `json:"ciphertext_hex"`
		} `json:"positive"`
		Tamper []struct {
			Name          string `json:"name"`
			MessageKeyHex string `json:"message_key_hex"`
			NonceHex      string `json:"nonce_hex"`
			AADHex        string `json:"aad_hex"`
			CiphertextHex string `json:"ciphertext_hex"`
			ExpectedError string `json:"expected_error"`
		} `json:"tamper"`
	}
	load(t, "test-vectors/crypto/aead_kat.json", &vf)
	if vf.ConstantsHash != regHash(t) {
		t.Fatal("stale aead vectors (TRS-CONST-005)")
	}

	for _, c := range vf.Positive {
		t.Run("positive/"+c.Name, func(t *testing.T) {
			keys, err := aead.DeriveMessageKeys(hexDecode(t, c.MessageKeyHex))
			if err != nil {
				t.Fatalf("DeriveMessageKeys: %v", err)
			}
			if hex.EncodeToString(keys.Nonce[:]) != c.NonceHex {
				t.Errorf("derived nonce mismatch:\n got %s\nwant %s", hex.EncodeToString(keys.Nonce[:]), c.NonceHex)
			}
			got, err := aead.Seal(keys.Key[:], keys.Nonce[:], hexDecode(t, c.PlaintextHex), hexDecode(t, c.AADHex))
			if err != nil {
				t.Fatalf("Seal: %v", err)
			}
			if hex.EncodeToString(got) != c.CiphertextHex {
				t.Errorf("ciphertext mismatch:\n got %s\nwant %s", hex.EncodeToString(got), c.CiphertextHex)
			}
			back, err := aead.Open(keys.Key[:], keys.Nonce[:], hexDecode(t, c.CiphertextHex), hexDecode(t, c.AADHex))
			if err != nil {
				t.Fatalf("Open: %v", err)
			}
			if hex.EncodeToString(back) != c.PlaintextHex {
				t.Error("roundtrip lost plaintext")
			}
		})
	}

	for _, c := range vf.Tamper {
		t.Run("tamper/"+c.Name, func(t *testing.T) {
			keys, err := aead.DeriveMessageKeys(hexDecode(t, c.MessageKeyHex))
			if err != nil {
				t.Fatalf("DeriveMessageKeys: %v", err)
			}
			_, err = aead.Open(keys.Key[:], hexDecode(t, c.NonceHex), hexDecode(t, c.CiphertextHex), hexDecode(t, c.AADHex))
			var we *wire.Error
			if !errors.As(err, &we) || we.Code != c.ExpectedError {
				t.Fatalf("tamper %s: expected %s, got %v", c.Name, c.ExpectedError, err)
			}
		})
	}
}

func TestConformanceSafetyNumber(t *testing.T) {
	var vf struct {
		ConstantsHash string   `json:"constants_hash"`
		AlphabetSize  int      `json:"alphabet_size"`
		Alphabet      []string `json:"alphabet"`
		Cases         []struct {
			Name           string   `json:"name"`
			IdentityAEdHex string   `json:"identity_a_ed_pub_hex"`
			IdentityBEdHex string   `json:"identity_b_ed_pub_hex"`
			Digits         string   `json:"digits"`
			EmojiIndices   []int    `json:"emoji_indices"`
			Emoji          []string `json:"emoji"`
		} `json:"cases"`
	}
	load(t, "test-vectors/crypto/safety_number.json", &vf)
	if vf.ConstantsHash != regHash(t) {
		t.Fatal("stale safety vectors (TRS-CONST-005)")
	}
	if vf.AlphabetSize != 64 || len(vf.Alphabet) != 64 {
		t.Fatalf("alphabet size %d / %d, want 64", vf.AlphabetSize, len(vf.Alphabet))
	}

	for _, c := range vf.Cases {
		t.Run(c.Name, func(t *testing.T) {
			num, err := safety.Derive(
				ed25519.PublicKey(hexDecode(t, c.IdentityAEdHex)),
				ed25519.PublicKey(hexDecode(t, c.IdentityBEdHex)))
			if err != nil {
				t.Fatalf("Derive: %v", err)
			}
			if num.Digits != c.Digits {
				t.Errorf("digits mismatch:\n got %s\nwant %s", num.Digits, c.Digits)
			}
			for i := range num.Emoji {
				if num.Emoji[i] != c.Emoji[i] || num.EmojiIndices[i] != c.EmojiIndices[i] {
					t.Errorf("emoji %d mismatch: got %s(%d) want %s(%d)",
						i, num.Emoji[i], num.EmojiIndices[i], c.Emoji[i], c.EmojiIndices[i])
				}
			}
		})
	}
}
