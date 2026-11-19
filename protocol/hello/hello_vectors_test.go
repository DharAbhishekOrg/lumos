// Lumos - protocol/hello / Go conformance consumption of the committed exchange.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-PROTO-017, TRS-PROTO-015, TRS-TST-001, TRS-CONST-005
// ADRs: ADR-002, ADR-008
// Threading: any
// Notes: The committed-file direction (the WP-01 S5 lesson): the dual exchange
// replays through the real Exchange type from the committed seeds and nonces,
// every payload byte-compares, every negative fails with its exact code
// through the mode the file names. A regenerated-but-uncommitted family, or a
// drift in the construction, goes red here before Java or Swift see it.
package hello

import (
	"crypto/ecdh"
	"crypto/ed25519"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"

	"github.com/dharabhishekorg/lumos/protocol/crypto/x3dh"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekorg/lumos/protocol/schema"
	"github.com/dharabhishekorg/lumos/protocol/wire"
)

type helloVectorParty struct {
	EdSeedHex      string `json:"testonly_ed_seed_hex"`
	XScalarHex     string `json:"testonly_x_scalar_hex"`
	CapabilityBits uint16 `json:"capability_bits"`
	VenueHint      uint32 `json:"venue_hint"`
	EidHex         string `json:"eid_hex"`
	PeerIDHex      string `json:"peer_id_hex"`
}

type helloVectorFile struct {
	ConstantsHash string           `json:"constants_hash"`
	Initiator     helloVectorParty `json:"initiator"`
	Responder     helloVectorParty `json:"responder"`
	Exchange      struct {
		InitiatorNonceHex string `json:"initiator_nonce_hex"`
		InitiatorHelloHex string `json:"initiator_hello_hex"`
		ResponderAckHex   string `json:"responder_ack_hex"`
		ResponderNonceHex string `json:"responder_nonce_hex"`
		ResponderHelloHex string `json:"responder_hello_hex"`
		InitiatorAckHex   string `json:"initiator_ack_hex"`
	} `json:"exchange"`
	Negatives []struct {
		Name          string `json:"name"`
		Mode          string `json:"mode"`
		OwnHelloHex   string `json:"own_hello_hex"`
		PayloadHex    string `json:"payload_hex"`
		ExpectedError string `json:"expected_error"`
	} `json:"negatives"`
}

func loadHelloVectors(t *testing.T) helloVectorFile {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("..", "..",
		"test-vectors", "session", "hello_exchange.json"))
	if err != nil {
		t.Fatalf("committed vector file must exist: %v", err)
	}
	var file helloVectorFile
	if err := json.Unmarshal(raw, &file); err != nil {
		t.Fatalf("unparseable vector file: %v", err)
	}
	reg, err := schema.LoadRegister(filepath.Join("..", ".."))
	if err != nil {
		t.Fatal(err)
	}
	declared, err := reg.String("constants_hash")
	if err != nil {
		t.Fatal(err)
	}
	if file.ConstantsHash != declared {
		t.Fatal("vector constants_hash does not match the register - stale vectors (TRS-CONST-005)")
	}
	if len(file.Negatives) == 0 {
		t.Fatal("a family without negatives proves nothing")
	}
	return file
}

func unhexHello(t *testing.T, what, v string) []byte {
	t.Helper()
	out, err := hex.DecodeString(v)
	if err != nil {
		t.Fatalf("%s is not hex: %v", what, err)
	}
	return out
}

func partyFromVector(t *testing.T, p helloVectorParty) (*Exchange, ed25519.PrivateKey) {
	t.Helper()
	ed := ed25519.NewKeyFromSeed(unhexHello(t, "ed seed", p.EdSeedHex))
	xPriv, err := ecdh.X25519().NewPrivateKey(unhexHello(t, "x scalar", p.XScalarHex))
	if err != nil {
		t.Fatal(err)
	}
	f := Fields{
		ProtocolVersion: lumosconstants.ProtocolVersionEncoded,
		CapabilityBits:  p.CapabilityBits,
		VenueHint:       p.VenueHint,
	}
	copy(f.EID[:], unhexHello(t, "eid", p.EidHex))
	copy(f.Ed25519Public[:], ed.Public().(ed25519.PublicKey))
	copy(f.X25519Public[:], xPriv.PublicKey().Bytes())
	copy(f.BindingSig[:], x3dh.SignIdentityDH(ed, xPriv.PublicKey()))
	return NewExchange(ed, f), ed
}

func TestConformanceHelloExchangeReplaysByteForByte(t *testing.T) {
	file := loadHelloVectors(t)
	iEx, _ := partyFromVector(t, file.Initiator)
	rEx, _ := partyFromVector(t, file.Responder)

	var iNonce, rNonce [16]byte
	copy(iNonce[:], unhexHello(t, "i nonce", file.Exchange.InitiatorNonceHex))
	copy(rNonce[:], unhexHello(t, "r nonce", file.Exchange.ResponderNonceHex))

	iHello := iEx.Start(iNonce)
	if hex.EncodeToString(iHello) != file.Exchange.InitiatorHelloHex {
		t.Fatal("initiator hello differs from the committed bytes")
	}
	rAck, err := rEx.OnHello(iHello)
	if err != nil {
		t.Fatal(err)
	}
	if hex.EncodeToString(rAck) != file.Exchange.ResponderAckHex {
		t.Fatal("responder ack differs from the committed bytes")
	}
	iPeer, err := iEx.OnAck(rAck)
	if err != nil {
		t.Fatal(err)
	}
	if hex.EncodeToString(iPeer.ID) != file.Responder.PeerIDHex {
		t.Fatal("peer id of the responder disagrees")
	}

	rHello := rEx.Start(rNonce)
	if hex.EncodeToString(rHello) != file.Exchange.ResponderHelloHex {
		t.Fatal("responder hello differs from the committed bytes")
	}
	iAck, err := iEx.OnHello(rHello)
	if err != nil {
		t.Fatal(err)
	}
	if hex.EncodeToString(iAck) != file.Exchange.InitiatorAckHex {
		t.Fatal("initiator ack differs from the committed bytes")
	}
	rPeer, err := rEx.OnAck(iAck)
	if err != nil {
		t.Fatal(err)
	}
	if hex.EncodeToString(rPeer.ID) != file.Initiator.PeerIDHex {
		t.Fatal("peer id of the initiator disagrees")
	}
}

func TestConformanceHelloNegativesFailWithTheirCodes(t *testing.T) {
	file := loadHelloVectors(t)
	for _, n := range file.Negatives {
		payload := unhexHello(t, n.Name, n.PayloadHex)
		var got error
		switch n.Mode {
		case "parse_hello":
			_, got = ParseHello(payload)
		case "verify_ack":
			_, got = VerifyAck(unhexHello(t, n.Name+" own hello", n.OwnHelloHex), payload)
		case "ack_without_outstanding":
			_, got = NewExchange(nil, Fields{}).OnAck(payload)
		default:
			t.Fatalf("%s: unknown mode %s", n.Name, n.Mode)
		}
		var we *wire.Error
		if got == nil || !errors.As(got, &we) || we.Code != n.ExpectedError {
			t.Fatalf("%s: expected %s, got %v", n.Name, n.ExpectedError, got)
		}
	}
}
