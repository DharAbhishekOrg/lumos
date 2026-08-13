// Lumos - protocol/wire / Canonical frame codec: encode and decode per TRS Sec. 9.1.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-PROTO-001, TRS-PROTO-002, TRS-PROTO-004, TRS-PROTO-006, TRS-PROTO-007, TRS-PROTO-013
// ADRs: ADR-004, ADR-007
// Threading: any
// Notes: Every numeric this codec compares against comes from generated code -
// lumosconstants for the layout and lumoserrors for the stable codes. The decode
// validation ORDER below is part of the wire contract, because the negative
// golden vectors assert exact error codes: magic, truncation, version major,
// header_length, unknown-critical type, reserved flags, reserved byte, class
// payload bound, payload truncation, trailing bytes. Bounds are checked before
// any payload allocation (TRS-PROTO-007).
package wire

import (
	"encoding/binary"
	"fmt"

	"github.com/dharabhishekemail-ops/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekemail-ops/lumos/protocol/gen/go/lumoserrors"
)

// headerLen is the fixed authenticated header length for protocol v1 frames.
const headerLen = lumosconstants.FrameHeaderLenBytesDefault

// Error is a protocol failure carrying a stable machine-readable code
// (TRS-PROTO-013). Code is always a member of the generated registry.
type Error struct {
	Code   string
	Detail string
}

func (e *Error) Error() string {
	return e.Code + ": " + e.Detail
}

func wireErr(code, format string, args ...any) *Error {
	return &Error{Code: code, Detail: fmt.Sprintf(format, args...)}
}

// Header is the canonical TRS Sec. 9.1 frame header. Magic, header_length and
// payload_length are not represented: magic and header_length are fixed by the
// protocol version, and payload_length is derived from the payload itself, so
// none of the three can be set inconsistently by a caller.
type Header struct {
	Version       uint8
	Type          lumosconstants.MessageClass
	Flags         uint16
	SessionID     [16]byte
	MessageID     [16]byte
	Sequence      uint32
	AckBase       uint32
	ExpirySeconds uint32
	HopLimit      uint8
}

// Frame is a decoded or to-be-encoded canonical frame.
type Frame struct {
	Header  Header
	Payload []byte
}

// Critical reports whether the frame demands fail-closed handling of anything
// the receiver does not understand (TRS-PROTO-006).
func (h Header) Critical() bool {
	return h.Flags&lumosconstants.FrameFlagCriticalDefault != 0
}

// LocalOnly reports whether the frame must never traverse the internet relay
// (SRS-TRN-009).
func (h Header) LocalOnly() bool {
	return h.Flags&lumosconstants.FrameFlagLocalOnlyDefault != 0
}

// Encode serializes the frame in network byte order. The version byte defaults
// to this build's protocol version when zero. Encoding enforces the same class
// payload bound the decoder does, so an oversized frame cannot be produced,
// only rejected.
func Encode(f *Frame) ([]byte, error) {
	if f.Header.Version == 0 {
		f.Header.Version = lumosconstants.ProtocolVersionEncoded
	}
	if !f.Header.Type.Known() {
		return nil, wireErr(lumoserrors.ErrFrameUnknownType,
			"cannot encode unknown message class 0x%02X", uint8(f.Header.Type))
	}
	max, ok := lumosconstants.MaxPayloadBytes(f.Header.Type)
	if !ok {
		return nil, wireErr(lumoserrors.ErrFrameUnknownType,
			"no payload bound for class %s", f.Header.Type)
	}
	if int64(len(f.Payload)) > max {
		return nil, wireErr(lumoserrors.ErrFramePayloadTooLarge,
			"payload %d bytes exceeds class %s bound %d", len(f.Payload), f.Header.Type, max)
	}

	out := make([]byte, headerLen+len(f.Payload))
	binary.BigEndian.PutUint16(out[0:2], lumosconstants.FrameMagicDefault)
	out[2] = f.Header.Version
	out[3] = uint8(f.Header.Type)
	binary.BigEndian.PutUint16(out[4:6], f.Header.Flags)
	binary.BigEndian.PutUint16(out[6:8], headerLen)
	copy(out[8:24], f.Header.SessionID[:])
	copy(out[24:40], f.Header.MessageID[:])
	binary.BigEndian.PutUint32(out[40:44], f.Header.Sequence)
	binary.BigEndian.PutUint32(out[44:48], f.Header.AckBase)
	// #nosec G115 -- len(f.Payload) is bounded above by the class payload check
	// a few lines up, and every MAX_PAYLOAD in the Sec. 23 register is far below
	// 2^32; the bound is asserted by TestMaxPayloadBoundsFitTheWireField.
	binary.BigEndian.PutUint32(out[48:52], uint32(len(f.Payload)))
	binary.BigEndian.PutUint32(out[52:56], f.Header.ExpirySeconds)
	out[56] = f.Header.HopLimit
	out[57] = 0 // reserved, always zero on encode
	copy(out[headerLen:], f.Payload)
	return out, nil
}

// HeaderBytes returns the complete authenticated header of an encoded frame -
// exactly the AEAD associated data of TRS-PROTO-003. It fails on input too
// short to contain a header rather than returning a partial slice.
func HeaderBytes(encoded []byte) ([]byte, error) {
	if len(encoded) < headerLen {
		return nil, wireErr(lumoserrors.ErrFrameTruncated,
			"%d bytes, header needs %d", len(encoded), headerLen)
	}
	return encoded[:headerLen], nil
}

// validateHeader applies the header rules that do not depend on the payload, in
// the order the negative golden vectors assert: version major, header_length,
// then the three conditions that are fatal ONLY on a critical frame - unknown
// class, reserved flag bits and a non-zero reserved byte. That ordering is part
// of the wire contract, because each vector pins the exact error code returned
// for a frame that violates several rules at once (TRS-PROTO-006, ADR-007).
func validateHeader(h Header, declaredHeaderLen uint16, reserved byte) error {
	if h.Version>>4 != lumosconstants.ProtocolVersionMajor {
		return wireErr(lumoserrors.ErrFrameUnsupportedVersion,
			"version 0x%02X, this build speaks major %d", h.Version, lumosconstants.ProtocolVersionMajor)
	}
	if int(declaredHeaderLen) != headerLen {
		return wireErr(lumoserrors.ErrFrameBadHeaderLength,
			"declared %d, protocol v1 header is %d", declaredHeaderLen, headerLen)
	}
	if !h.Critical() {
		return nil
	}
	if !h.Type.Known() {
		return wireErr(lumoserrors.ErrFrameUnknownType,
			"unknown class 0x%02X on a critical frame", uint8(h.Type))
	}
	if h.Flags&lumosconstants.FrameFlagsReservedMaskDefault != 0 {
		return wireErr(lumoserrors.ErrFrameReservedFlag,
			"reserved flag bits 0x%04X on a critical frame", h.Flags&lumosconstants.FrameFlagsReservedMaskDefault)
	}
	if reserved != 0 {
		return wireErr(lumoserrors.ErrFrameReservedField,
			"reserved byte 0x%02X on a critical frame", reserved)
	}
	return nil
}

// Decode parses one complete frame. A frame whose message class is unknown but
// non-critical, or whose reserved bits are set but non-critical, decodes
// successfully - the caller inspects Header.Type.Known() and skips per
// TRS-PROTO-006. All other rule violations return an *Error with a stable code.
func Decode(data []byte) (*Frame, error) {
	if len(data) < 2 {
		return nil, wireErr(lumoserrors.ErrFrameTruncated, "%d bytes, need 2 for magic", len(data))
	}
	if binary.BigEndian.Uint16(data[0:2]) != lumosconstants.FrameMagicDefault {
		return nil, wireErr(lumoserrors.ErrFrameBadMagic, "magic 0x%04X", binary.BigEndian.Uint16(data[0:2]))
	}
	if len(data) < headerLen {
		return nil, wireErr(lumoserrors.ErrFrameTruncated, "%d bytes, header needs %d", len(data), headerLen)
	}

	var h Header
	h.Version = data[2]
	h.Type = lumosconstants.MessageClass(data[3])
	h.Flags = binary.BigEndian.Uint16(data[4:6])
	declaredHeaderLen := binary.BigEndian.Uint16(data[6:8])
	copy(h.SessionID[:], data[8:24])
	copy(h.MessageID[:], data[24:40])
	h.Sequence = binary.BigEndian.Uint32(data[40:44])
	h.AckBase = binary.BigEndian.Uint32(data[44:48])
	payloadLen := binary.BigEndian.Uint32(data[48:52])
	h.ExpirySeconds = binary.BigEndian.Uint32(data[52:56])
	h.HopLimit = data[56]
	reserved := data[57]

	if err := validateHeader(h, declaredHeaderLen, reserved); err != nil {
		return nil, err
	}
	if h.Type.Known() {
		// Bound checked before any allocation (TRS-PROTO-007).
		max, _ := lumosconstants.MaxPayloadBytes(h.Type)
		if int64(payloadLen) > max {
			return nil, wireErr(lumoserrors.ErrFramePayloadTooLarge,
				"declared payload %d exceeds class %s bound %d", payloadLen, h.Type, max)
		}
	}
	// Both sides widen into int64 rather than meeting in uint64. len(data) is at
	// least headerLen by the truncation check above, so present cannot be
	// negative, and a uint32 always fits an int64 - so neither conversion can
	// wrap, and the subtraction below cannot underflow into a vast trailing-byte
	// count the way an unsigned one would on a malformed frame.
	present := int64(len(data) - headerLen)
	declared := int64(payloadLen)
	if present < declared {
		return nil, wireErr(lumoserrors.ErrFrameTruncated,
			"payload declares %d bytes, %d present", payloadLen, present)
	}
	if present > declared {
		return nil, wireErr(lumoserrors.ErrPayloadMalformed,
			"%d trailing bytes after the declared payload", present-declared)
	}

	payload := make([]byte, payloadLen)
	copy(payload, data[headerLen:])
	return &Frame{Header: h, Payload: payload}, nil
}
