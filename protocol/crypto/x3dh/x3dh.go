// Lumos - protocol/crypto/x3dh / X3DH-compatible asynchronous session establishment.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CRY-001, TRS-CRY-005, TRS-CRY-014, TRS-PROTO-005
// ADRs: ADR-001, ADR-008
// Threading: any
// Notes: Per ADR-008 (provisional), each device holds an Ed25519 identity
// SIGNING key and a separate X25519 identity DH key whose public half is
// Ed25519-signed under a domain-separated prefix. This keeps every operation on
// the primitives ADR-001 lists - no Ed25519->X25519 conversion, which neither
// the Go standard library nor Apple CryptoKit exposes. The DH structure is
// otherwise exactly X3DH: DH1..DH4 with the 0xFF/32 F prefix, HKDF-SHA-256 and
// a transcript hash binding identity keys, signed-prekey ID, one-time-prekey
// ID, protocol version and match ID (TRS-CRY-014).
package x3dh

import (
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/hkdf"
	"crypto/sha256"
	"encoding/binary"

	"github.com/dharabhishekemail-ops/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekemail-ops/lumos/protocol/gen/go/lumoserrors"
	"github.com/dharabhishekemail-ops/lumos/protocol/wire"
)

// SKSize is the derived shared-secret length.
const SKSize = 32

// Bundle is the public prekey material a responder publishes
// (protocol/schemas/prekey_bundle.schema.json).
type Bundle struct {
	IdentityEd    ed25519.PublicKey
	IdentityDH    *ecdh.PublicKey
	IdentityDHSig []byte
	SPKID         uint32
	SPK           *ecdh.PublicKey
	SPKSig        []byte
	// OPK is nil when the responder had no one-time prekey available; the
	// transcript then records X3DH_OPK_ID_NONE (TRS-CRY-005 documented fallback).
	OPKID uint32
	OPK   *ecdh.PublicKey
}

// Result is the agreed session material.
type Result struct {
	SK []byte
	// AD is the transcript hash; it becomes the associated data of the first
	// ratchet message (TRS Sec. 10.1 first-message binding).
	AD      []byte
	UsedOPK bool
}

// SignIdentityDH binds an identity DH public key to the Ed25519 identity.
func SignIdentityDH(identityEd ed25519.PrivateKey, dhPub *ecdh.PublicKey) []byte {
	return ed25519.Sign(identityEd, append([]byte(lumosconstants.X3dhIdentityBindingInfoValue), dhPub.Bytes()...))
}

// SignPrekey signs (info | spk_id | spk_pub) with the Ed25519 identity.
func SignPrekey(identityEd ed25519.PrivateKey, spkID uint32, spk *ecdh.PublicKey) []byte {
	return ed25519.Sign(identityEd, prekeyMessage(spkID, spk))
}

func prekeyMessage(spkID uint32, spk *ecdh.PublicKey) []byte {
	msg := []byte(lumosconstants.X3dhPrekeySigningInfoValue)
	var id [4]byte
	binary.BigEndian.PutUint32(id[:], spkID)
	msg = append(msg, id[:]...)
	return append(msg, spk.Bytes()...)
}

// VerifyBundle checks both signatures. Failure is fail-closed with the stable
// prekey-signature code; no detail distinguishes which signature failed.
func VerifyBundle(b *Bundle) error {
	binding := append([]byte(lumosconstants.X3dhIdentityBindingInfoValue), b.IdentityDH.Bytes()...)
	if !ed25519.Verify(b.IdentityEd, binding, b.IdentityDHSig) {
		return &wire.Error{Code: lumoserrors.ErrCryptoPrekeySignatureInvalid, Detail: "bundle signature rejected"}
	}
	if !ed25519.Verify(b.IdentityEd, prekeyMessage(b.SPKID, b.SPK), b.SPKSig) {
		return &wire.Error{Code: lumoserrors.ErrCryptoPrekeySignatureInvalid, Detail: "bundle signature rejected"}
	}
	return nil
}

// Initiate runs the initiator ("Alice") side against a responder bundle.
// identityDH and ephemeral are the initiator's X25519 keys; identityEd is the
// initiator's Ed25519 public identity, entering the transcript.
func Initiate(identityEd ed25519.PublicKey, identityDH, ephemeral *ecdh.PrivateKey,
	bundle *Bundle, matchID [16]byte) (*Result, error) {

	if err := VerifyBundle(bundle); err != nil {
		return nil, err
	}

	dh1, err := identityDH.ECDH(bundle.SPK) // DH(IK_A, SPK_B)
	if err != nil {
		return nil, dhErr(err)
	}
	dh2, err := ephemeral.ECDH(bundle.IdentityDH) // DH(EK_A, IK_B)
	if err != nil {
		return nil, dhErr(err)
	}
	dh3, err := ephemeral.ECDH(bundle.SPK) // DH(EK_A, SPK_B)
	if err != nil {
		return nil, dhErr(err)
	}
	var dh4 []byte
	if bundle.OPK != nil {
		if dh4, err = ephemeral.ECDH(bundle.OPK); err != nil { // DH(EK_A, OPK_B)
			return nil, dhErr(err)
		}
	}

	sk, err := deriveSK(dh1, dh2, dh3, dh4)
	if err != nil {
		return nil, err
	}
	ad := Transcript(identityEd, bundle.IdentityEd,
		identityDH.PublicKey(), bundle.IdentityDH,
		bundle.SPKID, opkIDOrNone(bundle), matchID)
	return &Result{SK: sk, AD: ad, UsedOPK: bundle.OPK != nil}, nil
}

// Respond runs the responder ("Bob") side once the initiator's identity and
// ephemeral public keys arrive with the first message. opk is nil when no
// one-time prekey was consumed; a consumed OPK must never be reused
// (TRS-CRY-005) - deleting it is the caller's persistence duty.
func Respond(identityEd ed25519.PublicKey, identityDH, spk *ecdh.PrivateKey, spkID uint32,
	opk *ecdh.PrivateKey, opkID uint32,
	initiatorEd ed25519.PublicKey, initiatorDH, initiatorEphemeral *ecdh.PublicKey,
	matchID [16]byte) (*Result, error) {

	dh1, err := spk.ECDH(initiatorDH) // DH(SPK_B, IK_A) = DH(IK_A, SPK_B)
	if err != nil {
		return nil, dhErr(err)
	}
	dh2, err := identityDH.ECDH(initiatorEphemeral) // DH(IK_B, EK_A)
	if err != nil {
		return nil, dhErr(err)
	}
	dh3, err := spk.ECDH(initiatorEphemeral) // DH(SPK_B, EK_A)
	if err != nil {
		return nil, dhErr(err)
	}
	var dh4 []byte
	usedOPKID := uint32(lumosconstants.X3dhOpkIDNoneDefault)
	if opk != nil {
		if dh4, err = opk.ECDH(initiatorEphemeral); err != nil {
			return nil, dhErr(err)
		}
		usedOPKID = opkID
	}

	sk, err := deriveSK(dh1, dh2, dh3, dh4)
	if err != nil {
		return nil, err
	}
	ad := Transcript(initiatorEd, identityEd,
		initiatorDH, identityDH.PublicKey(),
		spkID, usedOPKID, matchID)
	return &Result{SK: sk, AD: ad, UsedOPK: opk != nil}, nil
}

func opkIDOrNone(b *Bundle) uint32 {
	if b.OPK == nil {
		return uint32(lumosconstants.X3dhOpkIDNoneDefault)
	}
	return b.OPKID
}

// deriveSK computes HKDF(F || DH1 || DH2 || DH3 [|| DH4]) with a zero salt and
// the register's info string, per the X3DH specification.
func deriveSK(dh1, dh2, dh3, dh4 []byte) ([]byte, error) {
	ikm := make([]byte, 0, lumosconstants.X3dhFPrefixLen+4*32)
	for i := 0; i < lumosconstants.X3dhFPrefixLen; i++ {
		ikm = append(ikm, byte(lumosconstants.X3dhFPrefixFillByte))
	}
	ikm = append(ikm, dh1...)
	ikm = append(ikm, dh2...)
	ikm = append(ikm, dh3...)
	ikm = append(ikm, dh4...) // no-op when nil
	sk, err := hkdf.Key(sha256.New, ikm, make([]byte, 32), lumosconstants.X3dhKdfInfoValue, SKSize)
	if err != nil {
		return nil, &wire.Error{Code: lumoserrors.ErrCryptoAeadAuthFailed, Detail: "hkdf: " + err.Error()}
	}
	for i := range ikm {
		ikm[i] = 0
	}
	return sk, nil
}

// Transcript computes the binding hash of TRS-CRY-014: domain prefix, both
// Ed25519 identities, both identity-DH keys, signed-prekey ID, one-time-prekey
// ID (or the NONE sentinel), the encoded protocol version and the match ID.
// Field order is initiator-first, fixed forever once vectors freeze it.
func Transcript(initiatorEd, responderEd ed25519.PublicKey,
	initiatorDH, responderDH *ecdh.PublicKey,
	spkID, opkID uint32, matchID [16]byte) []byte {

	h := sha256.New()
	h.Write([]byte(lumosconstants.X3dhTranscriptInfoValue))
	h.Write(initiatorEd)
	h.Write(responderEd)
	h.Write(initiatorDH.Bytes())
	h.Write(responderDH.Bytes())
	var be [4]byte
	binary.BigEndian.PutUint32(be[:], spkID)
	h.Write(be[:])
	binary.BigEndian.PutUint32(be[:], opkID)
	h.Write(be[:])
	h.Write([]byte{lumosconstants.ProtocolVersionEncoded})
	h.Write(matchID[:])
	return h.Sum(nil)
}

func dhErr(err error) error {
	return &wire.Error{Code: lumoserrors.ErrCryptoAeadAuthFailed, Detail: "dh: " + err.Error()}
}
