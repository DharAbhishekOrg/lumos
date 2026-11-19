// Lumos - tools/vectorgen / The cross-language HELLO/HELLO_ACK exchange family.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-PROTO-017, TRS-PROTO-018, TRS-ENV-002, SRS-IDN-002, TRS-TST-001
// ADRs: ADR-002, ADR-008
// Threading: any
// Notes: The canonical trust point gets the full vector treatment because it
// is where TRS-ENV-002's zero-false-positive rule is won or lost: the family
// pins a complete dual exchange byte for byte (Ed25519 signing is
// deterministic, so Go, the JCA and libsodium must reproduce every signature;
// CryptoKit randomises and verifies instead, the identity_binding precedent),
// the SRS-IDN-002 peer identifiers it yields, and a negative set shaped like
// the attacks: truncation, version skew, a transplanted ADR-008 binding, a
// spoofed ACK signature, a stale ACK against a fresh nonce, and the bare
// replay with nothing outstanding. Every expectation is run against the
// reference implementation before it may be committed.
//
// This file is NOT prescribed by TRS Sec. 18.4; see ExtensionFiles in suite.go.
package main

import (
	"crypto/ecdh"
	"crypto/ed25519"
	"encoding/hex"
	"errors"
	"fmt"

	"github.com/dharabhishekorg/lumos/protocol/crypto/x3dh"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumoserrors"
	"github.com/dharabhishekorg/lumos/protocol/hello"
	"github.com/dharabhishekorg/lumos/protocol/wire"
)

// helloParty is one side's deterministic identity and field set.
type helloParty struct {
	Name           string `json:"name"`
	EdSeedHex      string `json:"testonly_ed_seed_hex"`
	XScalarHex     string `json:"testonly_x_scalar_hex"`
	CapabilityBits int    `json:"capability_bits"`
	VenueHint      int64  `json:"venue_hint"`
	EidHex         string `json:"eid_hex"`
	EdPublicHex    string `json:"ed_public_hex"`
	XPublicHex     string `json:"x_public_hex"`
	BindingSigHex  string `json:"binding_sig_hex"`
	PeerIDHex      string `json:"peer_id_hex"`
}

// helloExchange is the pinned dual exchange.
type helloExchange struct {
	InitiatorNonceHex string `json:"initiator_nonce_hex"`
	InitiatorHelloHex string `json:"initiator_hello_hex"`
	ResponderAckHex   string `json:"responder_ack_hex"`
	ResponderNonceHex string `json:"responder_nonce_hex"`
	ResponderHelloHex string `json:"responder_hello_hex"`
	InitiatorAckHex   string `json:"initiator_ack_hex"`
}

// helloNegative is one rejection case.
type helloNegative struct {
	Name string `json:"name"`
	// Mode is parse_hello, verify_ack or ack_without_outstanding.
	Mode          string `json:"mode"`
	OwnHelloHex   string `json:"own_hello_hex,omitempty"`
	PayloadHex    string `json:"payload_hex"`
	ExpectedError string `json:"expected_error"`
	Description   string `json:"description"`
}

// helloFile is the emitted session/hello_exchange.json.
type helloFile struct {
	fileMeta
	Initiator helloParty      `json:"initiator"`
	Responder helloParty      `json:"responder"`
	Exchange  helloExchange   `json:"exchange"`
	Negatives []helloNegative `json:"negatives"`
}

// implementsIDs echoes fileMeta for the vectorDoc registration.
func (f *helloFile) implementsIDs() []string { return f.Implements }

// builtParty pairs the emitted party with its live keys.
type builtParty struct {
	party  helloParty
	ed     ed25519.PrivateKey
	fields hello.Fields
}

// buildHelloParty derives one deterministic identity.
func buildHelloParty(name string, caps uint16, venue uint32, eid []byte) (*builtParty, error) {
	seed := testonlyBytes("hello/"+name+"/ed-seed", ed25519.SeedSize)
	scalar := testonlyBytes("hello/"+name+"/x-scalar", 32)
	ed := ed25519.NewKeyFromSeed(seed)
	xPriv, err := ecdh.X25519().NewPrivateKey(scalar)
	if err != nil {
		return nil, fmt.Errorf("party %s: %w", name, err)
	}

	f := hello.Fields{
		ProtocolVersion: lumosconstants.ProtocolVersionEncoded,
		CapabilityBits:  caps,
		VenueHint:       venue,
	}
	copy(f.EID[:], eid)
	copy(f.Ed25519Public[:], ed.Public().(ed25519.PublicKey))
	copy(f.X25519Public[:], xPriv.PublicKey().Bytes())
	copy(f.BindingSig[:], x3dh.SignIdentityDH(ed, xPriv.PublicKey()))

	return &builtParty{
		party: helloParty{
			Name:           name,
			EdSeedHex:      hex.EncodeToString(seed),
			XScalarHex:     hex.EncodeToString(scalar),
			CapabilityBits: int(caps),
			VenueHint:      int64(venue),
			EidHex:         hex.EncodeToString(f.EID[:]),
			EdPublicHex:    hex.EncodeToString(f.Ed25519Public[:]),
			XPublicHex:     hex.EncodeToString(f.X25519Public[:]),
			BindingSigHex:  hex.EncodeToString(f.BindingSig[:]),
			PeerIDHex:      hex.EncodeToString(hello.PeerID(f.Ed25519Public[:])),
		},
		ed:     ed,
		fields: f,
	}, nil
}

// expectHelloError runs a negative through the reference and demands the code.
func expectHelloError(n helloNegative) error {
	payload, err := hex.DecodeString(n.PayloadHex)
	if err != nil {
		return err
	}
	var got error
	switch n.Mode {
	case "parse_hello":
		_, got = hello.ParseHello(payload)
	case "verify_ack":
		own, err := hex.DecodeString(n.OwnHelloHex)
		if err != nil {
			return err
		}
		_, got = hello.VerifyAck(own, payload)
	case "ack_without_outstanding":
		ex := hello.NewExchange(nil, hello.Fields{})
		_, got = ex.OnAck(payload)
	default:
		return fmt.Errorf("negative %s: unknown mode %s", n.Name, n.Mode)
	}
	var we *wire.Error
	if got == nil || !errors.As(got, &we) || we.Code != n.ExpectedError {
		return fmt.Errorf("negative %s: expected %s, got %w", n.Name, n.ExpectedError, got)
	}
	return nil
}

// buildHelloVectors emits the exchange family.
func buildHelloVectors(constantsHash string) (*helloFile, error) {
	initiator, err := buildHelloParty("initiator", 0x0003, 0,
		testonlyBytes("hello/initiator/eid", hello.EIDLen))
	if err != nil {
		return nil, err
	}
	// The responder is the hintless shape of TRS-PROTO-018: zero EID, as
	// every iOS peer and every degraded Android peer presents.
	responder, err := buildHelloParty("responder-hintless", 0x0001, 0,
		make([]byte, hello.EIDLen))
	if err != nil {
		return nil, err
	}

	var iNonce, rNonce [16]byte
	copy(iNonce[:], testonlyBytes("hello/initiator/nonce", hello.NonceLen))
	copy(rNonce[:], testonlyBytes("hello/responder/nonce", hello.NonceLen))

	iEx := hello.NewExchange(initiator.ed, initiator.fields)
	rEx := hello.NewExchange(responder.ed, responder.fields)

	iHello := iEx.Start(iNonce)
	rAck, err := rEx.OnHello(iHello)
	if err != nil {
		return nil, err
	}
	iPeer, err := iEx.OnAck(rAck)
	if err != nil {
		return nil, err
	}
	rHello := rEx.Start(rNonce)
	iAck, err := iEx.OnHello(rHello)
	if err != nil {
		return nil, err
	}
	rPeer, err := rEx.OnAck(iAck)
	if err != nil {
		return nil, err
	}
	if hex.EncodeToString(iPeer.ID) != responder.party.PeerIDHex ||
		hex.EncodeToString(rPeer.ID) != initiator.party.PeerIDHex {
		return nil, fmt.Errorf("hello exchange: peer ids disagree with the party derivation")
	}

	file := &helloFile{
		fileMeta: fileMeta{
			SchemaVersion: 1, Generator: GeneratorName, ConstantsHash: constantsHash,
			Implements: []string{
				"TRS-PROTO-017", "TRS-PROTO-018", "TRS-ENV-002", "SRS-IDN-002", "TRS-TST-001",
			},
			KeyProvenance: KeyProvenance,
			Notes: "The HELLO/HELLO_ACK canonical trust point (register layouts " +
				"HELLO_V1_LAYOUT / HELLO_ACK_V1_LAYOUT). The dual exchange is pinned byte " +
				"for byte: Ed25519 signing is deterministic, so Go, the JCA and libsodium " +
				"must reproduce every payload exactly; CryptoKit randomises signing and " +
				"VERIFIES the committed bytes instead, round-tripping its own (the " +
				"identity_binding precedent). The responder is deliberately the " +
				"TRS-PROTO-018 hintless shape (zero EID). Negatives carry the mode a " +
				"consumer must drive: parse_hello, verify_ack (against own_hello_hex), or " +
				"ack_without_outstanding - the bare replay, which must fail with the nonce " +
				"code BEFORE any cryptography runs (TRS-ENV-002).",
		},
		Initiator: initiator.party,
		Responder: responder.party,
		Exchange: helloExchange{
			InitiatorNonceHex: hex.EncodeToString(iNonce[:]),
			InitiatorHelloHex: hex.EncodeToString(iHello),
			ResponderAckHex:   hex.EncodeToString(rAck),
			ResponderNonceHex: hex.EncodeToString(rNonce[:]),
			ResponderHelloHex: hex.EncodeToString(rHello),
			InitiatorAckHex:   hex.EncodeToString(iAck),
		},
	}

	// ---- negatives, every one validated against the reference -------------
	mallory, err := buildHelloParty("mallory", 0x0003, 0,
		testonlyBytes("hello/mallory/eid", hello.EIDLen))
	if err != nil {
		return nil, err
	}

	truncated := iHello[:len(iHello)-1]
	wrongHelloVersion := append([]byte(nil), iHello...)
	wrongHelloVersion[1] = 2
	wrongMajor := append([]byte(nil), iHello...)
	wrongMajor[0] = 0x20

	transplanted := mallory.fields
	transplanted.X25519Public = responder.fields.X25519Public
	transplantedBytes := hello.NewExchange(mallory.ed, transplanted).Start(iNonce)

	spoofed := append([]byte(nil), rAck...)
	copy(spoofed[hello.HelloLen:],
		ed25519.Sign(mallory.ed, hello.AckAuthMessage(iHello, spoofed[:hello.HelloLen])))

	freshHello := iEx.Start(nonceFill(0x5F))

	file.Negatives = []helloNegative{
		{Name: "truncated_hello", Mode: "parse_hello",
			PayloadHex:    hex.EncodeToString(truncated),
			ExpectedError: lumoserrors.ErrHelloMalformed,
			Description:   "One byte short of HELLO_V1_LAYOUT.total_len."},
		{Name: "wrong_hello_version", Mode: "parse_hello",
			PayloadHex:    hex.EncodeToString(wrongHelloVersion),
			ExpectedError: lumoserrors.ErrHelloMalformed,
			Description:   "hello_version 2 does not exist."},
		{Name: "wrong_major_version", Mode: "parse_hello",
			PayloadHex:    hex.EncodeToString(wrongMajor),
			ExpectedError: lumoserrors.ErrHelloVersionUnsupported,
			Description:   "Version 0x20 (v2.0): the in-band TRS-PROTO-017 re-confirmation fails closed on a differing major."},
		{Name: "transplanted_binding", Mode: "parse_hello",
			PayloadHex:    hex.EncodeToString(transplantedBytes),
			ExpectedError: lumoserrors.ErrHelloBindingInvalid,
			Description:   "Another identity's DH key under one's own Ed25519: the ADR-008 binding does not transplant."},
		{Name: "spoofed_ack_signature", Mode: "verify_ack",
			OwnHelloHex:   hex.EncodeToString(iHello),
			PayloadHex:    hex.EncodeToString(spoofed),
			ExpectedError: lumoserrors.ErrHelloSignatureInvalid,
			Description:   "An ACK carrying the responder's identity but signed by someone else."},
		{Name: "stale_ack_fresh_hello", Mode: "verify_ack",
			OwnHelloHex:   hex.EncodeToString(freshHello),
			PayloadHex:    hex.EncodeToString(rAck),
			ExpectedError: lumoserrors.ErrHelloSignatureInvalid,
			Description:   "A genuine ACK for an earlier nonce, replayed against a fresh HELLO: the signature no longer covers the transcript."},
		{Name: "ack_without_outstanding", Mode: "ack_without_outstanding",
			PayloadHex:    hex.EncodeToString(rAck),
			ExpectedError: lumoserrors.ErrHelloNonceMismatch,
			Description:   "The bare replay: an ACK arriving with no HELLO outstanding grants nothing, before any cryptography runs (TRS-ENV-002)."},
	}
	for _, n := range file.Negatives {
		if err := expectHelloError(n); err != nil {
			return nil, err
		}
	}
	return file, nil
}

// nonceFill builds a filled nonce.
func nonceFill(b byte) (n [16]byte) {
	for i := range n {
		n[i] = b
	}
	return n
}
