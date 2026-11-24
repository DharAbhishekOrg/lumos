// Lumos - protocol/chat / The remote-text wire: AMD-005 text events, the ratchet plaintext and the relay envelope.
// Author: dhar.abhishek.email@gmail.com
// Implements: SRS-RMT-011, SRS-RMT-004, SRS-RMT-009, SRS-UX-011, TRS-PROTO-007, TRS-CRY-001, TRS-CRY-002
// ADRs: ADR-001, ADR-008
// Threading: any
// Notes: Three layers, innermost first, fixed here under the TRS Sec. 19 AI
// delivery rule because the specification names the fields and not their
// bytes (the same footing as the fragment layout and the frame flag bits).
//
// TextEvent (AMD-005, protocol/schemas/message_payloads.schema.json):
//
//	event_kind u8 | target_message_id [16] (EDIT, DELETE, REACTION, REPLY only)
//	              | body_len u32 BE | body (TEXT, EDIT, REACTION, REPLY only)
//
// REPLY (CR-009) is a new text answering an earlier message: target is the
// answered message's id and body the new words. The quote is never sent;
// each device draws it from its own copy of the target.
//
// Plaintext, what the Double Ratchet encrypts:
//
//	message_id [16] | TextEvent
//
// The message id rides INSIDE the ciphertext so it is authenticated: the
// relay sees the same id in the clear for idempotency, and a receiver rejects
// an envelope whose outer id disagrees, so a relay cannot swap ids between
// messages and point an edit at the wrong one (SRS-RMT-004, SRS-RMT-009).
//
// Envelope, the relay payload of class TEXT_MESSAGE:
//
//	format u8 = 0x01 PREKEY | initiator_identity_dh [32] | identity_dh_sig [64]
//	                        | ephemeral [32] | spk_id u32 BE | opk_id u32 BE
//	                        | ratchet header [40] | ciphertext
//	format u8 = 0x02 NORMAL | ratchet header [40] | ciphertext
//
// The initiator sends PREKEY envelopes until the responder's first reply
// arrives; every field there is bound into the X3DH transcript that becomes
// the session's associated data (TRS Sec. 10.1). The initiator's identity is
// known to the responder from the match, so only its DH key and the Ed25519
// signature binding that key travel (ADR-008). Every length is checked
// against what remains BEFORE anything is allocated (TRS-PROTO-007). An
// unknown event kind is reported as ERR_TEXT_UNKNOWN_EVENT_KIND so a client
// can ignore it safely (SRS-RMT-011); everything else malformed is
// ERR_PAYLOAD_MALFORMED.
package chat

import (
	"encoding/binary"

	"github.com/dharabhishekorg/lumos/protocol/crypto/ratchet"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumoserrors"
	"github.com/dharabhishekorg/lumos/protocol/wire"
)

// MessageIDSize is MESSAGE_ID_LEN_BYTES.
const MessageIDSize = lumosconstants.MessageIDLenBytesDefault

// MessageID identifies one message or event globally (SRS-RMT-004).
type MessageID [MessageIDSize]byte

// Envelope formats.
const (
	FormatPrekey byte = 0x01
	FormatNormal byte = 0x02
)

// Field widths of the envelope, fixed by this layout.
const (
	keySize       = 32
	sigSize       = 64
	u32Size       = 4
	prekeyFields  = keySize + sigSize + keySize + u32Size + u32Size
	bodyLenSize   = u32Size
	eventKindSize = 1
	formatSize    = 1
)

func malformed(detail string) error {
	return &wire.Error{Code: lumoserrors.ErrPayloadMalformed, Detail: detail}
}

// TextEvent is one AMD-005 inner event.
type TextEvent struct {
	Kind   lumosconstants.TextEventKind
	Target MessageID // EDIT, DELETE_FOR_EVERYONE, REACTION, REPLY
	Body   []byte    // TEXT, EDIT, REACTION, REPLY
}

func hasTarget(k lumosconstants.TextEventKind) bool {
	return k == lumosconstants.TextEventKindEdit || k == lumosconstants.TextEventKindDeleteForEveryone ||
		k == lumosconstants.TextEventKindReaction || k == lumosconstants.TextEventKindReply
}

func hasBody(k lumosconstants.TextEventKind) bool {
	return k == lumosconstants.TextEventKindText || k == lumosconstants.TextEventKindEdit ||
		k == lumosconstants.TextEventKindReaction || k == lumosconstants.TextEventKindReply
}

func knownKind(k lumosconstants.TextEventKind) bool { return hasTarget(k) || hasBody(k) }

// Encode renders the event. A DELETE carries no body and a TEXT no target;
// supplying one is a caller bug and is refused.
func (e TextEvent) Encode() ([]byte, error) {
	if !knownKind(e.Kind) {
		return nil, &wire.Error{Code: lumoserrors.ErrTextUnknownEventKind, Detail: "unknown event kind"}
	}
	if !hasBody(e.Kind) && len(e.Body) != 0 {
		return nil, malformed("this event kind carries no body")
	}
	out := []byte{byte(e.Kind)}
	if hasTarget(e.Kind) {
		out = append(out, e.Target[:]...)
	}
	if hasBody(e.Kind) {
		out = binary.BigEndian.AppendUint32(out, uint32(len(e.Body))) // #nosec G115 -- bounded by the envelope maximum
		out = append(out, e.Body...)
	}
	return out, nil
}

// DecodeTextEvent parses an event; trailing bytes are refused.
func DecodeTextEvent(b []byte) (TextEvent, error) {
	if len(b) < eventKindSize {
		return TextEvent{}, malformed("empty event")
	}
	e := TextEvent{Kind: lumosconstants.TextEventKind(b[0])}
	if !knownKind(e.Kind) {
		return TextEvent{}, &wire.Error{Code: lumoserrors.ErrTextUnknownEventKind, Detail: "unknown event kind"}
	}
	rest := b[eventKindSize:]
	if hasTarget(e.Kind) {
		if len(rest) < MessageIDSize {
			return TextEvent{}, &wire.Error{Code: lumoserrors.ErrTextMissingTarget, Detail: "target_message_id missing"}
		}
		copy(e.Target[:], rest[:MessageIDSize])
		rest = rest[MessageIDSize:]
	}
	if hasBody(e.Kind) {
		if len(rest) < bodyLenSize {
			return TextEvent{}, malformed("body length missing")
		}
		n := binary.BigEndian.Uint32(rest)
		rest = rest[bodyLenSize:]
		if uint64(n) != uint64(len(rest)) { // checked before the body is copied
			return TextEvent{}, malformed("body length disagrees with the event")
		}
		e.Body = append([]byte(nil), rest...)
		rest = nil
	}
	if len(rest) != 0 {
		return TextEvent{}, malformed("trailing bytes after the event")
	}
	return e, nil
}

// Plaintext is what one ratchet message encrypts.
type Plaintext struct {
	MessageID MessageID
	Event     TextEvent
}

// Encode renders message_id | event.
func (p Plaintext) Encode() ([]byte, error) {
	ev, err := p.Event.Encode()
	if err != nil {
		return nil, err
	}
	return append(append([]byte(nil), p.MessageID[:]...), ev...), nil
}

// DecodePlaintext parses message_id | event.
func DecodePlaintext(b []byte) (Plaintext, error) {
	if len(b) < MessageIDSize {
		return Plaintext{}, malformed("plaintext shorter than a message id")
	}
	var p Plaintext
	copy(p.MessageID[:], b[:MessageIDSize])
	ev, err := DecodeTextEvent(b[MessageIDSize:])
	if err != nil {
		return Plaintext{}, err
	}
	p.Event = ev
	return p, nil
}

// PrekeyFields are the X3DH initial-message fields of a PREKEY envelope.
type PrekeyFields struct {
	InitiatorIdentityDH    [keySize]byte
	InitiatorIdentityDHSig [sigSize]byte
	Ephemeral              [keySize]byte
	SPKID                  uint32
	OPKID                  uint32 // X3DH_OPK_ID_NONE when no one-time prekey was used
}

// Envelope is the relay payload of one TEXT_MESSAGE.
type Envelope struct {
	Prekey     *PrekeyFields // nil for NORMAL
	Header     ratchet.Header
	Ciphertext []byte
}

// Encode renders the envelope.
func (e Envelope) Encode() []byte {
	var out []byte
	if e.Prekey != nil {
		p := e.Prekey
		out = append(out, FormatPrekey)
		out = append(out, p.InitiatorIdentityDH[:]...)
		out = append(out, p.InitiatorIdentityDHSig[:]...)
		out = append(out, p.Ephemeral[:]...)
		out = binary.BigEndian.AppendUint32(out, p.SPKID)
		out = binary.BigEndian.AppendUint32(out, p.OPKID)
	} else {
		out = append(out, FormatNormal)
	}
	out = append(out, e.Header.Bytes()...)
	return append(out, e.Ciphertext...)
}

// DecodeEnvelope parses an envelope. The ciphertext must be non-empty: an
// AEAD output always carries at least its tag.
func DecodeEnvelope(b []byte) (Envelope, error) {
	if len(b) < formatSize {
		return Envelope{}, malformed("empty envelope")
	}
	var e Envelope
	rest := b[formatSize:]
	switch b[0] {
	case FormatPrekey:
		if len(rest) < prekeyFields {
			return Envelope{}, malformed("prekey fields truncated")
		}
		p := &PrekeyFields{}
		copy(p.InitiatorIdentityDH[:], rest[0:keySize])
		copy(p.InitiatorIdentityDHSig[:], rest[keySize:keySize+sigSize])
		copy(p.Ephemeral[:], rest[keySize+sigSize:2*keySize+sigSize])
		p.SPKID = binary.BigEndian.Uint32(rest[2*keySize+sigSize:])
		p.OPKID = binary.BigEndian.Uint32(rest[2*keySize+sigSize+u32Size:])
		e.Prekey = p
		rest = rest[prekeyFields:]
	case FormatNormal:
	default:
		return Envelope{}, malformed("unknown envelope format")
	}
	if len(rest) <= ratchet.HeaderSize {
		return Envelope{}, malformed("ratchet header or ciphertext missing")
	}
	h, err := ratchet.ParseHeader(rest[:ratchet.HeaderSize])
	if err != nil {
		return Envelope{}, malformed("ratchet header invalid")
	}
	e.Header = h
	e.Ciphertext = append([]byte(nil), rest[ratchet.HeaderSize:]...)
	return e, nil
}
