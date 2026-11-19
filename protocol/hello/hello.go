// Lumos - protocol/hello / The canonical trust point: HELLO and HELLO_ACK.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-PROTO-017, TRS-PROTO-018, TRS-ENV-002, SRS-IDN-002
// ADRs: ADR-002, ADR-008
// Threading: serial-per-link
// Notes: TRS-PROTO-017 is the whole design: advertisement content is a routing
// hint, and EVERYTHING it hinted - EID, capabilities, protocol version, venue
// context - is established or re-confirmed here, authenticated, before any
// presence card or higher activity. The layouts come from the register
// (HELLO_V1_LAYOUT / HELLO_ACK_V1_LAYOUT, fixed under the TRS Sec. 19 AI
// delivery rule). An ACK's auth signature covers the SHA-256 of the exact
// HELLO bytes it answers, so freshness rides the initiator's nonce and a
// replayed message proves nothing; both sides run the exchange once per link
// direction, and presence is granted only to a peer whose ACK answered one's
// own fresh HELLO (TRS-ENV-002's zero-false-positive rule, enforced by
// construction in the Exchange type). A hintless peer - every iOS peer, any
// degraded Android peer - walks exactly this path with a zero EID
// (TRS-PROTO-018). The peer identifier is PEER_ID_LEN bytes of
// SHA-256(PEER_ID_KDF_INFO | Ed25519 public): cryptographic identity material
// and nothing else (SRS-IDN-002).
package hello

import (
	"bytes"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/binary"

	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumoserrors"
	"github.com/dharabhishekorg/lumos/protocol/wire"
)

// Layout widths, all from the register.
const (
	// HelloLen is the hello_v1 payload length.
	HelloLen = lumosconstants.HelloV1LayoutTotalLen
	// AckLen is the hello_ack_v1 payload length.
	AckLen = lumosconstants.HelloAckV1LayoutTotalLen
	// EIDLen is the rotating EID width inside the payload.
	EIDLen = lumosconstants.HelloV1LayoutEIDLen
	// NonceLen is the freshness nonce width.
	NonceLen = lumosconstants.HelloV1LayoutNonceLen
	// PeerIDLen is the derived peer identifier width.
	PeerIDLen = lumosconstants.PeerIDLenDefault

	authSigLen = lumosconstants.HelloAckV1LayoutAuthSigLen
	helloV1    = 1
)

// Fields is the hello_v1 content HELLO and HELLO_ACK share.
type Fields struct {
	ProtocolVersion uint8
	CapabilityBits  uint16
	VenueHint       uint32
	EID             [12]byte
	Ed25519Public   [32]byte
	X25519Public    [32]byte
	BindingSig      [64]byte
	Nonce           [16]byte
}

// PeerID derives the public peer identifier from identity material alone
// (SRS-IDN-002): PEER_ID_LEN bytes of SHA-256(PEER_ID_KDF_INFO | edPublic).
func PeerID(edPublic []byte) []byte {
	h := sha256.New()
	h.Write([]byte(lumosconstants.PeerIDKdfInfoValue))
	h.Write(edPublic)
	return h.Sum(nil)[:PeerIDLen]
}

// Encode serialises the shared fields in register layout order.
func (f *Fields) Encode() []byte {
	out := make([]byte, 0, HelloLen)
	out = append(out, f.ProtocolVersion, helloV1)
	out = binary.BigEndian.AppendUint16(out, f.CapabilityBits)
	out = binary.BigEndian.AppendUint32(out, f.VenueHint)
	out = append(out, f.EID[:]...)
	out = append(out, f.Ed25519Public[:]...)
	out = append(out, f.X25519Public[:]...)
	out = append(out, f.BindingSig[:]...)
	out = append(out, f.Nonce[:]...)
	return out
}

// parseFields reads the shared prefix of either payload.
func parseFields(b []byte) (*Fields, error) {
	if b[1] != helloV1 {
		return nil, wireErr(lumoserrors.ErrHelloMalformed, "unsupported hello version")
	}
	f := &Fields{
		ProtocolVersion: b[0],
		CapabilityBits:  binary.BigEndian.Uint16(b[2:4]),
		VenueHint:       binary.BigEndian.Uint32(b[4:8]),
	}
	copy(f.EID[:], b[8:20])
	copy(f.Ed25519Public[:], b[20:52])
	copy(f.X25519Public[:], b[52:84])
	copy(f.BindingSig[:], b[84:148])
	copy(f.Nonce[:], b[148:HelloLen])
	if f.ProtocolVersion>>4 != lumosconstants.ProtocolVersionMajor {
		// The TRS-PROTO-017 in-band re-confirmation, same rule as the frame
		// header (ADR-007): a differing MAJOR nibble fails closed.
		return nil, wireErr(lumoserrors.ErrHelloVersionUnsupported, "major version")
	}
	return f, nil
}

// verifyBinding checks the ADR-008 identity binding carried in the fields.
func verifyBinding(f *Fields) error {
	message := append([]byte(lumosconstants.X3dhIdentityBindingInfoValue), f.X25519Public[:]...)
	if !ed25519.Verify(f.Ed25519Public[:], message, f.BindingSig[:]) {
		return wireErr(lumoserrors.ErrHelloBindingInvalid, "identity binding")
	}
	return nil
}

// ParseHello decodes and authenticates a received HELLO payload: structure,
// version, and the ADR-008 binding. What it does NOT give is presence - a
// valid HELLO proves an identity exists, not that it is live on this link.
func ParseHello(b []byte) (*Fields, error) {
	if len(b) != HelloLen {
		return nil, wireErr(lumoserrors.ErrHelloMalformed, "hello length")
	}
	f, err := parseFields(b)
	if err != nil {
		return nil, err
	}
	if err := verifyBinding(f); err != nil {
		return nil, err
	}
	return f, nil
}

// AckAuthMessage is the exact byte string the ACK signature covers. Exported
// because the ports and the vector generator must build the SAME transcript -
// a forgery vector signed over a strawman message would prove nothing.
func AckAuthMessage(helloBytes, ackFields []byte) []byte {
	return ackAuthMessage(helloBytes, ackFields)
}

// ackAuthMessage is the exact byte string the ACK signature covers.
func ackAuthMessage(helloBytes, ackFields []byte) []byte {
	helloHash := sha256.Sum256(helloBytes)
	msg := make([]byte, 0, len(lumosconstants.HelloAuthInfoValue)+len(helloHash)+len(ackFields))
	msg = append(msg, []byte(lumosconstants.HelloAuthInfoValue)...)
	msg = append(msg, helloHash[:]...)
	return append(msg, ackFields...)
}

// BuildAck answers a received HELLO with this side's fields, signed by its
// Ed25519 identity over the initiator's exact HELLO bytes.
func BuildAck(responder *Fields, helloBytes []byte, identity ed25519.PrivateKey) []byte {
	fields := responder.Encode()
	sig := ed25519.Sign(identity, ackAuthMessage(helloBytes, fields))
	return append(fields, sig...)
}

// VerifyAck authenticates a received HELLO_ACK against one's OWN sent HELLO
// bytes: structure, version, the ADR-008 binding, and the auth signature whose
// freshness is the nonce inside ownHelloBytes. On success the returned fields
// are an authenticated, live peer - the only thing presence may be built from.
func VerifyAck(ownHelloBytes, ackBytes []byte) (*Fields, error) {
	if len(ackBytes) != AckLen {
		return nil, wireErr(lumoserrors.ErrHelloMalformed, "ack length")
	}
	f, err := parseFields(ackBytes[:HelloLen])
	if err != nil {
		return nil, err
	}
	if err := verifyBinding(f); err != nil {
		return nil, err
	}
	if !ed25519.Verify(f.Ed25519Public[:],
		ackAuthMessage(ownHelloBytes, ackBytes[:HelloLen]), ackBytes[HelloLen:]) {
		return nil, wireErr(lumoserrors.ErrHelloSignatureInvalid, "ack auth signature")
	}
	return f, nil
}

// Peer is an authenticated, live peer: the only product of a completed
// exchange, and the only input a presence layer accepts (TRS-ENV-002).
type Peer struct {
	Fields *Fields
	// ID is the SRS-IDN-002 identifier, stable across EID rotations.
	ID []byte
}

// Exchange runs one link direction's HELLO/HELLO_ACK as the initiator, and
// answers the peer's as the responder. It is not safe for concurrent use: the
// serialized link is its caller.
type Exchange struct {
	identity ed25519.PrivateKey
	own      Fields

	outstanding []byte
}

// NewExchange prepares an exchange for one link.
func NewExchange(identity ed25519.PrivateKey, own Fields) *Exchange {
	return &Exchange{identity: identity, own: own}
}

// Start emits this side's HELLO with the given fresh nonce and remembers it;
// the nonce source is the caller's so tests and vectors script it.
func (e *Exchange) Start(nonce [16]byte) []byte {
	f := e.own
	f.Nonce = nonce
	e.outstanding = f.Encode()
	return e.outstanding
}

// OnHello answers the peer's HELLO as the responder. The returned ACK proves
// this side's identity and liveness to THEM; it grants nothing here.
func (e *Exchange) OnHello(helloBytes []byte) ([]byte, error) {
	if _, err := ParseHello(helloBytes); err != nil {
		return nil, err
	}
	return BuildAck(&e.own, helloBytes, e.identity), nil
}

// OnAck completes this side's exchange: the ACK must answer the HELLO this
// side actually has outstanding. An ACK with nothing outstanding is the replay
// shape and grants nothing (TRS-ENV-002).
func (e *Exchange) OnAck(ackBytes []byte) (*Peer, error) {
	if e.outstanding == nil {
		return nil, wireErr(lumoserrors.ErrHelloNonceMismatch, "no outstanding hello")
	}
	f, err := VerifyAck(e.outstanding, ackBytes)
	if err != nil {
		return nil, err
	}
	if bytes.Equal(f.Ed25519Public[:], e.own.Ed25519Public[:]) {
		// A mirror of our own identity is not a peer.
		return nil, wireErr(lumoserrors.ErrHelloSignatureInvalid, "own identity echoed")
	}
	e.outstanding = nil
	return &Peer{Fields: f, ID: PeerID(f.Ed25519Public[:])}, nil
}

// wireErr builds the package's stable-coded error.
func wireErr(code, detail string) error {
	return &wire.Error{Code: code, Detail: detail}
}
