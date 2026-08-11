// Lumos - protocol/crypto/ratchet / Double Ratchet state machine per the Signal spec.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CRY-002, TRS-CRY-006, TRS-CRY-007, TRS-CRY-011, TRS-CRY-013
// ADRs: ADR-001, ADR-008
// Threading: serial-executor
// Notes: State is root key, DH pairs, sending/receiving chain keys, Ns, Nr, PN
// and bounded skipped keys - exactly the TRS-CRY-002 inventory. Message keys
// are zeroized and discarded after one use (TRS-CRY-006); skipped keys are
// bounded three ways per the register: MAX_SKIP per chain, a per-session count
// cap, and a TTL pruned against a caller-supplied clock so the state machine
// itself stays deterministic for vector generation. Time never comes from the
// machine here.
package ratchet

import (
	"crypto/ecdh"
	"crypto/hkdf"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/binary"

	"github.com/dharabhishekemail-ops/lumos/protocol/crypto/aead"
	"github.com/dharabhishekemail-ops/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekemail-ops/lumos/protocol/gen/go/lumoserrors"
	"github.com/dharabhishekemail-ops/lumos/protocol/wire"
)

// HeaderSize is the fixed ratchet header length (register RATCHET_HEADER_LAYOUT).
const HeaderSize = lumosconstants.RatchetHeaderLayoutTotalLen

// Header is the authenticated ratchet message header: the sender's current DH
// public key, the previous chain length (PN) and the message number (N).
type Header struct {
	DH [32]byte
	PN uint32
	N  uint32
}

// Bytes renders the canonical dh(32) | pn(4,BE) | n(4,BE) layout.
func (h Header) Bytes() []byte {
	out := make([]byte, HeaderSize)
	copy(out[:32], h.DH[:])
	binary.BigEndian.PutUint32(out[32:36], h.PN)
	binary.BigEndian.PutUint32(out[36:40], h.N)
	return out
}

// ParseHeader reads the canonical layout.
func ParseHeader(b []byte) (Header, error) {
	if len(b) != HeaderSize {
		return Header{}, &wire.Error{Code: lumoserrors.ErrPayloadMalformed, Detail: "ratchet header must be 40 bytes"}
	}
	var h Header
	copy(h.DH[:], b[:32])
	h.PN = binary.BigEndian.Uint32(b[32:36])
	h.N = binary.BigEndian.Uint32(b[36:40])
	return h, nil
}

// KeyProvider supplies fresh ratchet DH keys. Production uses the platform
// CSPRNG (TRS-CRY-012); vector generation injects a deterministic provider.
type KeyProvider interface {
	GenerateDH() (*ecdh.PrivateKey, error)
}

// Bounds are the register's denial-of-service limits. Zero values mean "use
// the register defaults"; tests may narrow them, never widen.
type Bounds struct {
	MaxSkip       int
	SkippedCap    int
	SkippedTTLSec int64
}

func (b Bounds) withDefaults() Bounds {
	if b.MaxSkip == 0 {
		b.MaxSkip = lumosconstants.MaxSkipPerChainDefault
	}
	if b.SkippedCap == 0 {
		b.SkippedCap = lumosconstants.SkippedKeysPerSessionCapDefault
	}
	if b.SkippedTTLSec == 0 {
		b.SkippedTTLSec = int64(lumosconstants.SkippedKeyTTLDaysDefault) * 24 * 60 * 60
	}
	return b
}

type skippedKey struct {
	dh [32]byte
	n  uint32
}

type skippedEntry struct {
	mk       [32]byte
	storedAt int64
}

// State is one side of a Double Ratchet session.
type State struct {
	dhs    *ecdh.PrivateKey
	dhr    *ecdh.PublicKey
	rk     []byte
	cks    []byte
	ckr    []byte
	ns, nr uint32
	pn     uint32
	ad     []byte
	skip   map[skippedKey]skippedEntry
	bounds Bounds
}

// NewInitiator builds the sender-first side: SK from X3DH, the responder's
// signed ratchet public key, and the initiator's own first ratchet key pair
// (injected so vectors stay deterministic).
func NewInitiator(sk, ad []byte, remoteRatchetPub *ecdh.PublicKey, ownRatchet *ecdh.PrivateKey, bounds Bounds) (*State, error) {
	s := &State{
		dhs:    ownRatchet,
		dhr:    remoteRatchetPub,
		rk:     append([]byte(nil), sk...),
		ad:     append([]byte(nil), ad...),
		skip:   map[skippedKey]skippedEntry{},
		bounds: bounds.withDefaults(),
	}
	dhOut, err := s.dhs.ECDH(s.dhr)
	if err != nil {
		return nil, &wire.Error{Code: lumoserrors.ErrCryptoAeadAuthFailed, Detail: "dh: " + err.Error()}
	}
	if s.rk, s.cks, err = kdfRK(s.rk, dhOut); err != nil {
		return nil, err
	}
	return s, nil
}

// NewResponder builds the receiver-first side with its published ratchet key.
func NewResponder(sk, ad []byte, ownRatchet *ecdh.PrivateKey, bounds Bounds) *State {
	return &State{
		dhs:    ownRatchet,
		rk:     append([]byte(nil), sk...),
		ad:     append([]byte(nil), ad...),
		skip:   map[skippedKey]skippedEntry{},
		bounds: bounds.withDefaults(),
	}
}

// SkippedCount reports how many out-of-order message keys are stored; tests
// use it to prove deletion (TRS-CRY-006).
func (s *State) SkippedCount() int { return len(s.skip) }

// Encrypt produces the next message. now is the caller's clock (unix seconds),
// used only to prune expired skipped keys.
func (s *State) Encrypt(plaintext []byte, now int64) (Header, []byte, error) {
	s.pruneExpired(now)
	if s.cks == nil {
		return Header{}, nil, &wire.Error{Code: lumoserrors.ErrCryptoAeadAuthFailed, Detail: "no sending chain yet"}
	}
	mk, next := kdfCK(s.cks)
	aead.Zero(s.cks)
	s.cks = next

	var h Header
	copy(h.DH[:], s.dhs.PublicKey().Bytes())
	h.PN = s.pn
	h.N = s.ns

	ct, err := sealWith(mk, s.ad, h, plaintext)
	zero32(&mk)
	if err != nil {
		return Header{}, nil, err
	}
	s.ns++
	return h, ct, nil
}

// Decrypt consumes one message. A skipped-key hit consumes and deletes the
// stored key; a new remote DH key triggers a DH ratchet turn using kp for the
// fresh sending pair. All failures are terminal for the frame (TRS-CRY-013)
// and leave a stable code.
func (s *State) Decrypt(h Header, ciphertext []byte, now int64, kp KeyProvider) ([]byte, error) {
	s.pruneExpired(now)

	if entry, ok := s.skip[skippedKey{h.DH, h.N}]; ok {
		delete(s.skip, skippedKey{h.DH, h.N})
		pt, err := openWith(entry.mk[:], s.ad, h, ciphertext)
		zero32(&entry.mk)
		return pt, err
	}

	remote := s.dhr
	newRemote := remote == nil || h.DH != to32(remote.Bytes())
	if newRemote {
		// Catch up the outgoing receiving chain to the sender's declared PN
		// before turning; a nil receiving chain skips nothing by construction.
		if err := s.skipMessageKeys(h.PN, now); err != nil {
			return nil, err
		}
		if err := s.dhRatchet(h, kp); err != nil {
			return nil, err
		}
	}
	if err := s.skipMessageKeys(h.N, now); err != nil {
		return nil, err
	}

	mk, next := kdfCK(s.ckr)
	aead.Zero(s.ckr)
	s.ckr = next
	s.nr++

	pt, err := openWith(mk[:], s.ad, h, ciphertext)
	zero32(&mk)
	return pt, err
}

// skipMessageKeys advances the receiving chain to 'until', storing skipped
// message keys under the three register bounds.
func (s *State) skipMessageKeys(until uint32, now int64) error {
	if s.ckr == nil {
		return nil
	}
	if until > s.nr && int(until-s.nr) > s.bounds.MaxSkip {
		return &wire.Error{
			Code:   lumoserrors.ErrCryptoMaxSkipExceeded,
			Detail: "gap exceeds MAX_SKIP_PER_CHAIN",
		}
	}
	for s.nr < until {
		if len(s.skip) >= s.bounds.SkippedCap {
			return &wire.Error{
				Code:   lumoserrors.ErrCryptoSkippedKeyCap,
				Detail: "session skipped-key cap reached",
			}
		}
		mk, next := kdfCK(s.ckr)
		aead.Zero(s.ckr)
		s.ckr = next
		s.skip[skippedKey{to32(s.dhr.Bytes()), s.nr}] = skippedEntry{mk: mk, storedAt: now}
		s.nr++
	}
	return nil
}

// dhRatchet performs the root-chain turn on a new remote DH key.
func (s *State) dhRatchet(h Header, kp KeyProvider) error {
	s.pn = s.ns
	s.ns = 0
	s.nr = 0

	remote, err := ecdh.X25519().NewPublicKey(h.DH[:])
	if err != nil {
		return &wire.Error{Code: lumoserrors.ErrCryptoAeadAuthFailed, Detail: "bad ratchet key"}
	}
	s.dhr = remote

	dhOut, err := s.dhs.ECDH(s.dhr)
	if err != nil {
		return &wire.Error{Code: lumoserrors.ErrCryptoAeadAuthFailed, Detail: "dh: " + err.Error()}
	}
	if s.rk, s.ckr, err = kdfRK(s.rk, dhOut); err != nil {
		return err
	}

	if s.dhs, err = kp.GenerateDH(); err != nil {
		return &wire.Error{Code: lumoserrors.ErrCryptoAeadAuthFailed, Detail: "keygen: " + err.Error()}
	}
	if dhOut, err = s.dhs.ECDH(s.dhr); err != nil {
		return &wire.Error{Code: lumoserrors.ErrCryptoAeadAuthFailed, Detail: "dh: " + err.Error()}
	}
	if s.rk, s.cks, err = kdfRK(s.rk, dhOut); err != nil {
		return err
	}
	return nil
}

// pruneExpired drops skipped keys older than the TTL (TRS-CRY-007). Expiry is
// silent: the key is simply gone, and a late message fails authentication.
func (s *State) pruneExpired(now int64) {
	for k, e := range s.skip {
		if now-e.storedAt > s.bounds.SkippedTTLSec {
			delete(s.skip, k)
		}
	}
}

// kdfRK is KDF_RK: HKDF-SHA-256(salt=rk, ikm=dh_out) -> (rk', ck).
func kdfRK(rk, dhOut []byte) (newRK, ck []byte, err error) {
	okm, err := hkdf.Key(sha256.New, dhOut, rk, lumosconstants.RatchetRootKdfInfoValue, 64)
	if err != nil {
		return nil, nil, &wire.Error{Code: lumoserrors.ErrCryptoAeadAuthFailed, Detail: "hkdf: " + err.Error()}
	}
	return okm[:32], okm[32:], nil
}

// kdfCK is KDF_CK: mk = HMAC(ck, seed_message), ck' = HMAC(ck, seed_next).
func kdfCK(ck []byte) (mk [32]byte, next []byte) {
	m := hmac.New(sha256.New, ck)
	m.Write([]byte{lumosconstants.ChainKeySeedsMessage})
	copy(mk[:], m.Sum(nil))
	m = hmac.New(sha256.New, ck)
	m.Write([]byte{lumosconstants.ChainKeySeedsNext})
	return mk, m.Sum(nil)
}

func sealWith(mk [32]byte, ad []byte, h Header, plaintext []byte) ([]byte, error) {
	keys, err := aead.DeriveMessageKeys(mk[:])
	if err != nil {
		return nil, err
	}
	ct, err := aead.Seal(keys.Key[:], keys.Nonce[:], plaintext, append(append([]byte(nil), ad...), h.Bytes()...))
	keys.Key = [32]byte{}
	return ct, err
}

func openWith(mk []byte, ad []byte, h Header, ciphertext []byte) ([]byte, error) {
	keys, err := aead.DeriveMessageKeys(mk)
	if err != nil {
		return nil, err
	}
	pt, err := aead.Open(keys.Key[:], keys.Nonce[:], ciphertext, append(append([]byte(nil), ad...), h.Bytes()...))
	keys.Key = [32]byte{}
	return pt, err
}

func to32(b []byte) (out [32]byte) {
	copy(out[:], b)
	return out
}

func zero32(b *[32]byte) {
	for i := range b {
		b[i] = 0
	}
}
