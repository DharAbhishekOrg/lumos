// Lumos - tools/vectorgen / Crypto golden vectors: x3dh, ratchet, aead, safety number.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-TST-001, TRS-CRY-002, TRS-CRY-006, TRS-CRY-007, TRS-CRY-011, TRS-CRY-014, TRS-CRY-015
// ADRs: ADR-001, ADR-005, ADR-008
// Threading: any
// Notes: These vectors are produced by driving the real protocol/crypto packages
// with deterministic TESTONLY keys, so the committed bytes are exactly what the
// production code computes - Java and Swift then reproduce them. Nothing here
// invents its own crypto. The AEAD tamper cases mutate the REAL frame-header AAD
// from S4a's codec, per the owner's instruction, each pinned to its exact code.
package main

import (
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/hex"
	"fmt"

	"github.com/dharabhishekemail-ops/lumos/protocol/crypto/aead"
	"github.com/dharabhishekemail-ops/lumos/protocol/crypto/ratchet"
	"github.com/dharabhishekemail-ops/lumos/protocol/crypto/safety"
	"github.com/dharabhishekemail-ops/lumos/protocol/crypto/x3dh"
	"github.com/dharabhishekemail-ops/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekemail-ops/lumos/protocol/wire"
)

// cryptoSeed derives deterministic key material for a labelled role.
func cryptoSeed(label string) []byte {
	h := sha256.Sum256(fmt.Appendf(nil, "%s|crypto|%s", KeyProvenance, label))
	return h[:]
}

func edFromSeed(label string) ed25519.PrivateKey {
	return ed25519.NewKeyFromSeed(cryptoSeed(label))
}

func dhFromSeed(label string) (*ecdh.PrivateKey, error) {
	return ecdh.X25519().NewPrivateKey(cryptoSeed(label))
}

// deterministicKP replays a fixed sequence of ratchet keys so ratchet vectors
// reproduce byte-for-byte. TESTONLY only - production uses the CSPRNG.
type deterministicKP struct {
	label string
	n     int
}

func (k *deterministicKP) GenerateDH() (*ecdh.PrivateKey, error) {
	key, err := dhFromSeed(fmt.Sprintf("%s|ratchet-kp|%d", k.label, k.n))
	k.n++
	return key, err
}

// ---- crypto/x3dh_kat.json ----

type x3dhKAT struct {
	fileMeta
	MatchIDHex string     `json:"match_id_hex"`
	Cases      []x3dhCase `json:"cases"`
}

type x3dhCase struct {
	Name             string `json:"name"`
	Description      string `json:"description"`
	WithOPK          bool   `json:"with_one_time_prekey"`
	InitiatorIDEdHex string `json:"initiator_identity_ed_pub_hex"`
	InitiatorIDDHHex string `json:"initiator_identity_dh_pub_hex"`
	InitiatorEphHex  string `json:"initiator_ephemeral_pub_hex"`
	ResponderIDEdHex string `json:"responder_identity_ed_pub_hex"`
	ResponderIDDHHex string `json:"responder_identity_dh_pub_hex"`
	SPKID            uint32 `json:"signed_prekey_id"`
	ResponderSPKHex  string `json:"responder_signed_prekey_pub_hex"`
	OPKID            uint32 `json:"one_time_prekey_id"`
	ResponderOPKHex  string `json:"responder_one_time_prekey_pub_hex,omitempty"`
	SharedSecretHex  string `json:"shared_secret_hex"`
	TranscriptADHex  string `json:"transcript_ad_hex"`
}

var vectorMatchID = [16]byte{0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7, 0xA8, 0xB1, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8}

func buildX3DHKAT(constantsHash string) (*x3dhKAT, error) {
	file := &x3dhKAT{
		fileMeta: fileMeta{
			SchemaVersion: 1, Generator: GeneratorName, ConstantsHash: constantsHash,
			Implements:    []string{"TRS-CRY-001", "TRS-CRY-005", "TRS-CRY-014", "TRS-CRY-015"},
			KeyProvenance: KeyProvenance,
			Notes: "X3DH known-answer vectors (ADR-001/ADR-008). Consumers reconstruct the " +
				"shared secret and transcript AD from the public inputs and their own secret " +
				"halves; both must match exactly. The no-OPK case is the TRS-CRY-005 fallback.",
		},
		MatchIDHex: hex.EncodeToString(vectorMatchID[:]),
	}

	for _, withOPK := range []bool{true, false} {
		c, err := oneX3DHCase(withOPK)
		if err != nil {
			return nil, err
		}
		file.Cases = append(file.Cases, c)
	}
	return file, nil
}

func oneX3DHCase(withOPK bool) (x3dhCase, error) {
	suffix := "no_opk"
	if withOPK {
		suffix = "with_opk"
	}
	aliceEd := edFromSeed("alice|" + suffix + "|ed")
	aliceDH, err := dhFromSeed("alice|" + suffix + "|dh")
	if err != nil {
		return x3dhCase{}, err
	}
	aliceEph, err := dhFromSeed("alice|" + suffix + "|eph")
	if err != nil {
		return x3dhCase{}, err
	}
	bobEd := edFromSeed("bob|" + suffix + "|ed")
	bobDH, err := dhFromSeed("bob|" + suffix + "|dh")
	if err != nil {
		return x3dhCase{}, err
	}
	bobSPK, err := dhFromSeed("bob|" + suffix + "|spk")
	if err != nil {
		return x3dhCase{}, err
	}
	bobOPK, err := dhFromSeed("bob|" + suffix + "|opk")
	if err != nil {
		return x3dhCase{}, err
	}

	const spkID, opkID = 7, 42
	bundle := &x3dh.Bundle{
		IdentityEd:    bobEd.Public().(ed25519.PublicKey),
		IdentityDH:    bobDH.PublicKey(),
		IdentityDHSig: x3dh.SignIdentityDH(bobEd, bobDH.PublicKey()),
		SPKID:         spkID,
		SPK:           bobSPK.PublicKey(),
	}
	bundle.SPKSig = x3dh.SignPrekey(bobEd, spkID, bobSPK.PublicKey())
	if withOPK {
		bundle.OPKID = opkID
		bundle.OPK = bobOPK.PublicKey()
	}

	init, err := x3dh.Initiate(aliceEd.Public().(ed25519.PublicKey), aliceDH, aliceEph, bundle, vectorMatchID)
	if err != nil {
		return x3dhCase{}, err
	}
	var opk *ecdh.PrivateKey
	if withOPK {
		opk = bobOPK
	}
	resp, err := x3dh.Respond(bobEd.Public().(ed25519.PublicKey), bobDH, bobSPK, spkID, opk, opkID,
		aliceEd.Public().(ed25519.PublicKey), aliceDH.PublicKey(), aliceEph.PublicKey(), vectorMatchID)
	if err != nil {
		return x3dhCase{}, err
	}
	if hex.EncodeToString(init.SK) != hex.EncodeToString(resp.SK) {
		return x3dhCase{}, fmt.Errorf("x3dh %s: initiator/responder SK disagree", suffix)
	}

	c := x3dhCase{
		Name:             "x3dh_" + suffix,
		Description:      "X3DH agreement " + suffix + "; both sides derive the same secret and transcript.",
		WithOPK:          withOPK,
		InitiatorIDEdHex: hex.EncodeToString(aliceEd.Public().(ed25519.PublicKey)),
		InitiatorIDDHHex: hex.EncodeToString(aliceDH.PublicKey().Bytes()),
		InitiatorEphHex:  hex.EncodeToString(aliceEph.PublicKey().Bytes()),
		ResponderIDEdHex: hex.EncodeToString(bobEd.Public().(ed25519.PublicKey)),
		ResponderIDDHHex: hex.EncodeToString(bobDH.PublicKey().Bytes()),
		SPKID:            spkID,
		ResponderSPKHex:  hex.EncodeToString(bobSPK.PublicKey().Bytes()),
		SharedSecretHex:  hex.EncodeToString(init.SK),
		TranscriptADHex:  hex.EncodeToString(init.AD),
	}
	if withOPK {
		c.OPKID = opkID
		c.ResponderOPKHex = hex.EncodeToString(bobOPK.PublicKey().Bytes())
	} else {
		c.OPKID = uint32(lumosconstants.X3dhOpkIDNoneDefault)
	}
	return c, nil
}

// ---- crypto/ratchet_sequences.json ----

type ratchetMsg struct {
	Direction     string `json:"direction"`
	HeaderHex     string `json:"header_hex"`
	CiphertextHex string `json:"ciphertext_hex"`
	PlaintextHex  string `json:"plaintext_hex"`
	DeliverIndex  int    `json:"deliver_index"`
}

type ratchetSequence struct {
	Name        string       `json:"name"`
	Description string       `json:"description"`
	Messages    []ratchetMsg `json:"messages"`
}

type ratchetNegative struct {
	Name          string `json:"name"`
	Description   string `json:"description"`
	ExpectedError string `json:"expected_error"`
	Gap           int    `json:"gap"`
}

type ratchetFile struct {
	fileMeta
	SharedSecretHex string            `json:"shared_secret_hex"`
	ADHex           string            `json:"associated_data_hex"`
	Sequences       []ratchetSequence `json:"sequences"`
	Negatives       []ratchetNegative `json:"negatives"`
}

func buildRatchetSequences(constantsHash string) (*ratchetFile, error) {
	sk := cryptoSeed("ratchet|sk")
	ad := cryptoSeed("ratchet|ad")

	file := &ratchetFile{
		fileMeta: fileMeta{
			SchemaVersion: 1, Generator: GeneratorName, ConstantsHash: constantsHash,
			Implements:    []string{"TRS-CRY-002", "TRS-CRY-006", "TRS-CRY-007", "TRS-CRY-011", "TRS-CRY-015"},
			KeyProvenance: KeyProvenance,
			Notes: "Double Ratchet sequences. deliver_index is the order in which a consumer " +
				"feeds messages to its receiver to reproduce out-of-order and skip behaviour; " +
				"the header carries dh|pn|n. The negative asserts the MAX_SKIP breach fails " +
				"with its exact stable code.",
		},
		SharedSecretHex: hex.EncodeToString(sk),
		ADHex:           hex.EncodeToString(ad),
	}

	inOrder, err := ratchetInOrder(sk, ad)
	if err != nil {
		return nil, err
	}
	file.Sequences = append(file.Sequences, inOrder)

	skipSeq, err := ratchetSkipped(sk, ad)
	if err != nil {
		return nil, err
	}
	file.Sequences = append(file.Sequences, skipSeq)

	turns, err := ratchetDHTurns(sk, ad)
	if err != nil {
		return nil, err
	}
	file.Sequences = append(file.Sequences, turns)

	file.Negatives = append(file.Negatives, ratchetNegative{
		Name:          "max_skip_breach",
		Description:   "A within-chain gap exceeding MAX_SKIP_PER_CHAIN is refused before any key is stored.",
		ExpectedError: "ERR_CRYPTO_MAX_SKIP_EXCEEDED",
		Gap:           lumosconstants.MaxSkipPerChainDefault + 1,
	})
	return file, nil
}

func newRatchetPair(sk, ad []byte) (*ratchet.State, *ratchet.State, error) {
	bobRatchet, err := dhFromSeed("ratchet|bob-0")
	if err != nil {
		return nil, nil, err
	}
	aliceRatchet, err := dhFromSeed("ratchet|alice-0")
	if err != nil {
		return nil, nil, err
	}
	alice, err := ratchet.NewInitiator(sk, ad, bobRatchet.PublicKey(), aliceRatchet, ratchet.Bounds{})
	if err != nil {
		return nil, nil, err
	}
	bob := ratchet.NewResponder(sk, ad, bobRatchet, ratchet.Bounds{})
	return alice, bob, nil
}

const vectorClock = 1_700_000_000 // fixed unix seconds; never the machine clock

func ratchetInOrder(sk, ad []byte) (ratchetSequence, error) {
	alice, _, err := newRatchetPair(sk, ad)
	if err != nil {
		return ratchetSequence{}, err
	}
	seq := ratchetSequence{Name: "in_order", Description: "Three in-order A->B messages on the first sending chain."}
	for i := 0; i < 3; i++ {
		pt := fmt.Appendf(nil, "in-order-%d", i)
		h, ct, err := alice.Encrypt(pt, vectorClock)
		if err != nil {
			return ratchetSequence{}, err
		}
		seq.Messages = append(seq.Messages, ratchetMsg{
			Direction: "a2b", HeaderHex: hex.EncodeToString(h.Bytes()),
			CiphertextHex: hex.EncodeToString(ct), PlaintextHex: hex.EncodeToString(pt), DeliverIndex: i,
		})
	}
	return seq, nil
}

func ratchetSkipped(sk, ad []byte) (ratchetSequence, error) {
	alice, _, err := newRatchetPair(sk, ad)
	if err != nil {
		return ratchetSequence{}, err
	}
	// Encrypt four; deliver 0, then 3 (skips 1,2), then 1, then 2.
	order := []int{0, 3, 1, 2}
	produced := make([]ratchetMsg, 4)
	for i := 0; i < 4; i++ {
		pt := fmt.Appendf(nil, "skip-%d", i)
		h, ct, err := alice.Encrypt(pt, vectorClock)
		if err != nil {
			return ratchetSequence{}, err
		}
		produced[i] = ratchetMsg{
			Direction: "a2b", HeaderHex: hex.EncodeToString(h.Bytes()),
			CiphertextHex: hex.EncodeToString(ct), PlaintextHex: hex.EncodeToString(pt),
		}
	}
	seq := ratchetSequence{Name: "out_of_order_and_skipped",
		Description: "Deliver order 0,3,1,2: message 3 stores skipped keys for 1 and 2, later consumed."}
	for pos, idx := range order {
		m := produced[idx]
		m.DeliverIndex = pos
		seq.Messages = append(seq.Messages, m)
	}
	return seq, nil
}

func ratchetDHTurns(sk, ad []byte) (ratchetSequence, error) {
	alice, bob, err := newRatchetPair(sk, ad)
	if err != nil {
		return ratchetSequence{}, err
	}
	kpA := &deterministicKP{label: "alice"}
	kpB := &deterministicKP{label: "bob"}
	seq := ratchetSequence{Name: "dh_ratchet_turns",
		Description: "A->B, B->A, A->B: two DH ratchet turns. Consumers replay in delivery order."}

	deliver := 0
	emit := func(dir string, h ratchet.Header, ct, pt []byte) {
		seq.Messages = append(seq.Messages, ratchetMsg{
			Direction: dir, HeaderHex: hex.EncodeToString(h.Bytes()),
			CiphertextHex: hex.EncodeToString(ct), PlaintextHex: hex.EncodeToString(pt), DeliverIndex: deliver,
		})
		deliver++
	}

	pt := []byte("turn a->b #1")
	h, ct, err := alice.Encrypt(pt, vectorClock)
	if err != nil {
		return ratchetSequence{}, err
	}
	if _, err := bob.Decrypt(h, ct, vectorClock, kpB); err != nil {
		return ratchetSequence{}, err
	}
	emit("a2b", h, ct, pt)

	pt = []byte("turn b->a #1")
	h, ct, err = bob.Encrypt(pt, vectorClock)
	if err != nil {
		return ratchetSequence{}, err
	}
	if _, err := alice.Decrypt(h, ct, vectorClock, kpA); err != nil {
		return ratchetSequence{}, err
	}
	emit("b2a", h, ct, pt)

	pt = []byte("turn a->b #2")
	h, ct, err = alice.Encrypt(pt, vectorClock)
	if err != nil {
		return ratchetSequence{}, err
	}
	if _, err := bob.Decrypt(h, ct, vectorClock, kpB); err != nil {
		return ratchetSequence{}, err
	}
	emit("a2b", h, ct, pt)
	return seq, nil
}

// ---- crypto/aead_kat.json ----

type aeadCase struct {
	Name          string `json:"name"`
	Description   string `json:"description"`
	MessageKeyHex string `json:"message_key_hex"`
	DerivedKeyHex string `json:"derived_key_hex"`
	NonceHex      string `json:"nonce_hex"`
	AADHex        string `json:"aad_hex"`
	PlaintextHex  string `json:"plaintext_hex"`
	CiphertextHex string `json:"ciphertext_hex"`
}

type aeadTamper struct {
	Name          string `json:"name"`
	Description   string `json:"description"`
	MessageKeyHex string `json:"message_key_hex"`
	NonceHex      string `json:"nonce_hex"`
	AADHex        string `json:"aad_hex"`
	CiphertextHex string `json:"ciphertext_hex"`
	ExpectedError string `json:"expected_error"`
}

type aeadFile struct {
	fileMeta
	Positive []aeadCase   `json:"positive"`
	Tamper   []aeadTamper `json:"tamper"`
}

func buildAEADKAT(constantsHash string) (*aeadFile, error) {
	file := &aeadFile{
		fileMeta: fileMeta{
			SchemaVersion: 1, Generator: GeneratorName, ConstantsHash: constantsHash,
			Implements:    []string{"TRS-CRY-003", "TRS-CRY-011", "TRS-CRY-013", "TRS-PROTO-003", "TRS-CRY-015"},
			KeyProvenance: KeyProvenance,
			Notes: "ChaCha20-Poly1305 with the REAL frame header as associated data (TRS-PROTO-003). " +
				"The message key is expanded to (key,nonce) via HKDF with the register info string. " +
				"Every tamper case mutates the genuine S4a frame-header AAD and must fail with the " +
				"exact stable code.",
		},
	}

	// A real encrypted TEXT_MESSAGE frame: its header is the AAD.
	session := testonly16("aead|session")
	message := testonly16("aead|message")
	frameHeader := &wire.Frame{
		Header: wire.Header{
			Type:      lumosconstants.MessageClassTextMessage,
			Flags:     lumosconstants.FrameFlagCriticalDefault,
			SessionID: session, MessageID: message, Sequence: 1,
		},
	}
	encodedEmpty, err := wire.Encode(frameHeader)
	if err != nil {
		return nil, err
	}
	aad, err := wire.HeaderBytes(encodedEmpty)
	if err != nil {
		return nil, err
	}

	messageKey := cryptoSeed("aead|mk")
	keys, err := aead.DeriveMessageKeys(messageKey)
	if err != nil {
		return nil, err
	}
	plaintext := []byte("the quick brown fox")
	ct, err := aead.Seal(keys.Key[:], keys.Nonce[:], plaintext, aad)
	if err != nil {
		return nil, err
	}

	file.Positive = append(file.Positive, aeadCase{
		Name: "text_message_header_aad", Description: "AEAD over a real TEXT_MESSAGE frame header.",
		MessageKeyHex: hex.EncodeToString(messageKey), DerivedKeyHex: hex.EncodeToString(keys.Key[:]),
		NonceHex: hex.EncodeToString(keys.Nonce[:]), AADHex: hex.EncodeToString(aad),
		PlaintextHex: hex.EncodeToString(plaintext), CiphertextHex: hex.EncodeToString(ct),
	})

	// Tamper: flip a header byte (the AAD), flip a ciphertext byte, flip the tag.
	badAAD := append([]byte(nil), aad...)
	badAAD[24] ^= 0x01 // inside message_id, part of the authenticated header
	file.Tamper = append(file.Tamper, aeadTamper{
		Name: "mutated_header_aad", Description: "One byte of the frame-header AAD flipped; authentication must fail.",
		MessageKeyHex: hex.EncodeToString(messageKey), NonceHex: hex.EncodeToString(keys.Nonce[:]),
		AADHex: hex.EncodeToString(badAAD), CiphertextHex: hex.EncodeToString(ct),
		ExpectedError: "ERR_CRYPTO_AEAD_AUTH_FAILED",
	})
	badCT := append([]byte(nil), ct...)
	badCT[0] ^= 0x01
	file.Tamper = append(file.Tamper, aeadTamper{
		Name: "mutated_ciphertext", Description: "One ciphertext byte flipped.",
		MessageKeyHex: hex.EncodeToString(messageKey), NonceHex: hex.EncodeToString(keys.Nonce[:]),
		AADHex: hex.EncodeToString(aad), CiphertextHex: hex.EncodeToString(badCT),
		ExpectedError: "ERR_CRYPTO_AEAD_AUTH_FAILED",
	})
	badTag := append([]byte(nil), ct...)
	badTag[len(badTag)-1] ^= 0x01
	file.Tamper = append(file.Tamper, aeadTamper{
		Name: "mutated_tag", Description: "The Poly1305 tag flipped.",
		MessageKeyHex: hex.EncodeToString(messageKey), NonceHex: hex.EncodeToString(keys.Nonce[:]),
		AADHex: hex.EncodeToString(aad), CiphertextHex: hex.EncodeToString(badTag),
		ExpectedError: "ERR_CRYPTO_AEAD_AUTH_FAILED",
	})
	return file, nil
}

// ---- crypto/safety_number.json ----

type safetyCase struct {
	Name           string   `json:"name"`
	IdentityAEdHex string   `json:"identity_a_ed_pub_hex"`
	IdentityBEdHex string   `json:"identity_b_ed_pub_hex"`
	Digits         string   `json:"digits"`
	EmojiIndices   []int    `json:"emoji_indices"`
	Emoji          []string `json:"emoji"`
}

type safetyFile struct {
	fileMeta
	AlphabetSize int          `json:"alphabet_size"`
	Alphabet     []string     `json:"alphabet"`
	Cases        []safetyCase `json:"cases"`
}

func buildSafetyNumber(constantsHash string) (*safetyFile, error) {
	file := &safetyFile{
		fileMeta: fileMeta{
			SchemaVersion: 1, Generator: GeneratorName, ConstantsHash: constantsHash,
			Implements:    []string{"SRS-IDN-005", "TRS-CRY-014", "TRS-CRY-015"},
			KeyProvenance: KeyProvenance,
			Notes: "Safety-number KAT (ADR-005/ADR-009): 60 digits + 6 emoji from the frozen " +
				"64-glyph alphabet, symmetric in the identity pair. The alphabet is embedded so " +
				"a consumer with a different build's alphabet fails rather than rendering wrong glyphs.",
		},
		AlphabetSize: len(lumosconstants.SafetyNumberEmojiAlphabetGlyphs),
		Alphabet:     lumosconstants.SafetyNumberEmojiAlphabetGlyphs[:],
	}

	pairs := [][2]string{{"alpha", "beta"}, {"gamma", "delta"}}
	for _, p := range pairs {
		aEd := edFromSeed("safety|" + p[0])
		bEd := edFromSeed("safety|" + p[1])
		num, err := safety.Derive(aEd.Public().(ed25519.PublicKey), bEd.Public().(ed25519.PublicKey))
		if err != nil {
			return nil, err
		}
		file.Cases = append(file.Cases, safetyCase{
			Name:           "safety_" + p[0] + "_" + p[1],
			IdentityAEdHex: hex.EncodeToString(aEd.Public().(ed25519.PublicKey)),
			IdentityBEdHex: hex.EncodeToString(bEd.Public().(ed25519.PublicKey)),
			Digits:         num.Digits,
			EmojiIndices:   num.EmojiIndices[:],
			Emoji:          num.Emoji[:],
		})
	}
	return file, nil
}
