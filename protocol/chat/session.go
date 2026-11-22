// Lumos - protocol/chat / Remote-text sessions: X3DH establishment and sealed envelopes over the Double Ratchet.
// Author: dhar.abhishek.email@gmail.com
// Implements: SRS-RMT-001, SRS-RMT-007, SRS-RMT-009, TRS-CRY-001, TRS-CRY-002, TRS-CRY-005, TRS-CRY-015
// ADRs: ADR-001, ADR-008
// Threading: serial-executor
// Notes: The reference the apps follow, step for step. The initiator runs X3DH
// against the peer's fetched bundle (signatures verified first, fail closed)
// and starts the ratchet with the peer's signed prekey as the first remote
// ratchet key, as the Signal specification does. Its first envelopes carry
// the PREKEY fields so a responder that was offline can derive the same
// secret later. The responder checks the binding signature over the
// initiator's identity-DH key against the identity it already knows from the
// match (ADR-008), insists the one-time prekey named is the one it supplies,
// and starts its ratchet on the signed prekey. Open refuses an envelope whose
// authenticated message id differs from the id the relay delivered it under.
package chat

import (
	"crypto/ecdh"
	"crypto/ed25519"

	"github.com/dharabhishekorg/lumos/protocol/crypto/ratchet"
	"github.com/dharabhishekorg/lumos/protocol/crypto/x3dh"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumoserrors"
	"github.com/dharabhishekorg/lumos/protocol/wire"
)

// Identity is one device's long-term keys.
type Identity struct {
	Ed ed25519.PrivateKey
	DH *ecdh.PrivateKey
}

// Initiate starts a session with a matched peer from its fetched bundle.
// ephemeral and ratchetKey are fresh X25519 keys from the platform CSPRNG
// (injected so vectors are deterministic).
func Initiate(own Identity, bundle *x3dh.Bundle, matchID [16]byte,
	ephemeral, ratchetKey *ecdh.PrivateKey) (*ratchet.State, *PrekeyFields, error) {

	res, err := x3dh.Initiate(own.Ed.Public().(ed25519.PublicKey), own.DH, ephemeral, bundle, matchID)
	if err != nil {
		return nil, nil, err
	}
	st, err := ratchet.NewInitiator(res.SK, res.AD, bundle.SPK, ratchetKey, ratchet.Bounds{})
	if err != nil {
		return nil, nil, err
	}
	p := &PrekeyFields{SPKID: bundle.SPKID, OPKID: uint32(lumosconstants.X3dhOpkIDNoneDefault)}
	if bundle.OPK != nil {
		p.OPKID = bundle.OPKID
	}
	copy(p.InitiatorIdentityDH[:], own.DH.PublicKey().Bytes())
	copy(p.InitiatorIdentityDHSig[:], x3dh.SignIdentityDH(own.Ed, own.DH.PublicKey()))
	copy(p.Ephemeral[:], ephemeral.PublicKey().Bytes())
	return st, p, nil
}

// ResponderKeys are the responder's secret prekeys the PREKEY fields name.
type ResponderKeys struct {
	SPK   *ecdh.PrivateKey
	SPKID uint32
	OPK   *ecdh.PrivateKey // nil when the envelope names X3DH_OPK_ID_NONE
	OPKID uint32
}

func rejected(detail string) error {
	return &wire.Error{Code: lumoserrors.ErrCryptoPrekeySignatureInvalid, Detail: detail}
}

// Respond accepts a PREKEY envelope from initiatorEd, the identity the match
// already pinned. The caller must delete the consumed one-time prekey once
// the first message opens (TRS-CRY-005).
func Respond(own Identity, keys ResponderKeys, initiatorEd ed25519.PublicKey, p PrekeyFields,
	matchID [16]byte) (*ratchet.State, error) {

	initiatorDH, err := ecdh.X25519().NewPublicKey(p.InitiatorIdentityDH[:])
	if err != nil {
		return nil, rejected("bad initiator identity-dh key")
	}
	binding := append([]byte(lumosconstants.X3dhIdentityBindingInfoValue), p.InitiatorIdentityDH[:]...)
	if !ed25519.Verify(initiatorEd, binding, p.InitiatorIdentityDHSig[:]) {
		return nil, rejected("initiator identity binding rejected")
	}
	ephemeral, err := ecdh.X25519().NewPublicKey(p.Ephemeral[:])
	if err != nil {
		return nil, rejected("bad ephemeral key")
	}
	if p.SPKID != keys.SPKID {
		return nil, rejected("signed prekey id unknown")
	}
	none := uint32(lumosconstants.X3dhOpkIDNoneDefault)
	if (p.OPKID == none) != (keys.OPK == nil) || (keys.OPK != nil && p.OPKID != keys.OPKID) {
		return nil, rejected("one-time prekey id mismatch")
	}
	res, err := x3dh.Respond(own.Ed.Public().(ed25519.PublicKey), own.DH, keys.SPK, keys.SPKID,
		keys.OPK, keys.OPKID, initiatorEd, initiatorDH, ephemeral, matchID)
	if err != nil {
		return nil, err
	}
	return ratchet.NewResponder(res.SK, res.AD, keys.SPK, ratchet.Bounds{}), nil
}

// Seal encrypts one plaintext. prekey is non-nil while the initiator has not
// yet heard back from the responder.
func Seal(st *ratchet.State, p Plaintext, prekey *PrekeyFields, now int64) ([]byte, error) {
	pt, err := p.Encode()
	if err != nil {
		return nil, err
	}
	h, ct, err := st.Encrypt(pt, now)
	if err != nil {
		return nil, err
	}
	return Envelope{Prekey: prekey, Header: h, Ciphertext: ct}.Encode(), nil
}

// Open decrypts one envelope delivered under outerID. A mismatch between the
// authenticated id and outerID is refused: the relay cannot relabel messages.
func Open(st *ratchet.State, e Envelope, outerID MessageID, now int64, kp ratchet.KeyProvider) (Plaintext, error) {
	pt, err := st.Decrypt(e.Header, e.Ciphertext, now, kp)
	if err != nil {
		return Plaintext{}, err
	}
	p, err := DecodePlaintext(pt)
	if err != nil {
		return Plaintext{}, err
	}
	if p.MessageID != outerID {
		return Plaintext{}, malformed("authenticated message id differs from the delivered id")
	}
	return p, nil
}
