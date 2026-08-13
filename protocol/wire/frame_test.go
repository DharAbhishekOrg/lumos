// Lumos - protocol/wire / Unit tests for the canonical frame codec.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-PROTO-001, TRS-PROTO-002, TRS-PROTO-004, TRS-PROTO-006, TRS-PROTO-007, TRS-PROTO-013
// ADRs: ADR-007
// Threading: any
// Notes: TestEncodeMatchesHandComputedBytes assembles its expected frame hex BY
// HAND from TRS Sec. 9.1 field widths. Without it, the codec and the vector
// generator - which share this package - could only ever agree with themselves;
// this is the independent anchor the Go side contributes until the Java and
// Swift consumers arrive in S5.
package wire

import (
	"encoding/hex"
	"errors"
	"strings"
	"testing"

	"github.com/dharabhishekemail-ops/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekemail-ops/lumos/protocol/gen/go/lumoserrors"
)

func mustHex(t *testing.T, s string) []byte {
	t.Helper()
	b, err := hex.DecodeString(s)
	if err != nil {
		t.Fatalf("bad hex fixture: %v", err)
	}
	return b
}

func fixedIDs() ([16]byte, [16]byte) {
	var session, message [16]byte
	for i := 0; i < 16; i++ {
		session[i] = byte(i)
		message[i] = byte(0x10 + i)
	}
	return session, message
}

// TestEncodeMatchesHandComputedBytes is the independent byte-level anchor.
// Expected hex assembled by hand from TRS Sec. 9.1:
// magic 4C4D | version 10 | type 01 | flags 0000 | header_length 003A (58) |
// session 00..0F | message 10..1F | sequence 00000001 | ack_base 00000000 |
// payload_length 00000003 | expiry 0000003C | hop 00 | reserved 00 | payload ABCDEF
func TestEncodeMatchesHandComputedBytes(t *testing.T) {
	session, message := fixedIDs()
	f := &Frame{
		Header: Header{
			Type:          lumosconstants.MessageClassHello,
			SessionID:     session,
			MessageID:     message,
			Sequence:      1,
			ExpirySeconds: 60,
		},
		Payload: mustHex(t, "abcdef"),
	}
	got, err := Encode(f)
	if err != nil {
		t.Fatalf("Encode: %v", err)
	}
	want := "4c4d" + "10" + "01" + "0000" + "003a" +
		"000102030405060708090a0b0c0d0e0f" +
		"101112131415161718191a1b1c1d1e1f" +
		"00000001" + "00000000" + "00000003" + "0000003c" + "00" + "00" +
		"abcdef"
	if hex.EncodeToString(got) != want {
		t.Errorf("encoded frame differs from hand-computed bytes:\n got %s\nwant %s", hex.EncodeToString(got), want)
	}

	back, err := Decode(got)
	if err != nil {
		t.Fatalf("Decode: %v", err)
	}
	if back.Header != f.Header || hex.EncodeToString(back.Payload) != "abcdef" {
		t.Error("decode did not restore the encoded frame")
	}
}

func TestRoundtripEveryKnownClass(t *testing.T) {
	session, message := fixedIDs()
	for mc := range lumosconstants.MessageClassNames {
		f := &Frame{
			Header:  Header{Type: mc, SessionID: session, MessageID: message, Sequence: 7, HopLimit: 3},
			Payload: []byte("roundtrip"),
		}
		encoded, err := Encode(f)
		if err != nil {
			t.Errorf("%s: Encode: %v", mc, err)
			continue
		}
		decoded, err := Decode(encoded)
		if err != nil {
			t.Errorf("%s: Decode: %v", mc, err)
			continue
		}
		if decoded.Header != f.Header || string(decoded.Payload) != "roundtrip" {
			t.Errorf("%s: roundtrip mismatch", mc)
		}
	}
}

func code(t *testing.T, err error) string {
	t.Helper()
	if err == nil {
		t.Fatal("expected a wire error, got nil")
	}
	var we *Error
	if !errors.As(err, &we) {
		t.Fatalf("expected *wire.Error, got %T: %v", err, err)
	}
	if !lumoserrors.Known(we.Code) {
		t.Fatalf("error code %q is not in the stable registry", we.Code)
	}
	return we.Code
}

// validFrame returns a well-formed encoded HELLO frame to mutate.
func validFrame(t *testing.T, flags uint16, payload []byte) []byte {
	t.Helper()
	session, message := fixedIDs()
	encoded, err := Encode(&Frame{
		Header:  Header{Type: lumosconstants.MessageClassHello, Flags: flags, SessionID: session, MessageID: message},
		Payload: payload,
	})
	if err != nil {
		t.Fatalf("Encode fixture: %v", err)
	}
	return encoded
}

func TestDecodeErrorPaths(t *testing.T) {
	critical := uint16(lumosconstants.FrameFlagCriticalDefault)

	cases := []struct {
		name     string
		mutate   func(t *testing.T) []byte
		wantCode string
	}{
		{"empty input", func(t *testing.T) []byte { return nil }, lumoserrors.ErrFrameTruncated},
		{"one byte", func(t *testing.T) []byte { return []byte{0x4C} }, lumoserrors.ErrFrameTruncated},
		{"bad magic", func(t *testing.T) []byte {
			b := validFrame(t, 0, nil)
			b[0], b[1] = 0x4D, 0x4C
			return b
		}, lumoserrors.ErrFrameBadMagic},
		{"truncated header", func(t *testing.T) []byte { return validFrame(t, 0, nil)[:20] }, lumoserrors.ErrFrameTruncated},
		{"wrong major version", func(t *testing.T) []byte {
			b := validFrame(t, 0, nil)
			b[2] = 0x20
			return b
		}, lumoserrors.ErrFrameUnsupportedVersion},
		{"bad header length", func(t *testing.T) []byte {
			b := validFrame(t, 0, nil)
			b[7] = 57
			return b
		}, lumoserrors.ErrFrameBadHeaderLength},
		{"unknown critical type", func(t *testing.T) []byte {
			b := validFrame(t, critical, nil)
			b[3] = 0x77
			return b
		}, lumoserrors.ErrFrameUnknownType},
		{"reserved flag critical", func(t *testing.T) []byte {
			b := validFrame(t, critical|0x0100, nil)
			return b
		}, lumoserrors.ErrFrameReservedFlag},
		{"reserved byte critical", func(t *testing.T) []byte {
			b := validFrame(t, critical, nil)
			b[57] = 0x01
			return b
		}, lumoserrors.ErrFrameReservedField},
		{"payload over class bound", func(t *testing.T) []byte {
			b := validFrame(t, 0, nil)
			b[48], b[49], b[50], b[51] = 0x00, 0x00, 0x07, 0xD0 // declare 2000 > HELLO bound 1228
			return b
		}, lumoserrors.ErrFramePayloadTooLarge},
		{"payload truncated", func(t *testing.T) []byte {
			b := validFrame(t, 0, []byte("0123456789"))
			return b[:len(b)-4]
		}, lumoserrors.ErrFrameTruncated},
		{"trailing garbage", func(t *testing.T) []byte {
			b := validFrame(t, 0, []byte("ok"))
			return append(b, 0xEE)
		}, lumoserrors.ErrPayloadMalformed},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			_, err := Decode(tc.mutate(t))
			if got := code(t, err); got != tc.wantCode {
				t.Errorf("got %s, want %s", got, tc.wantCode)
			}
		})
	}
}

// TestSkippableConditionsDecode covers the permissive half of TRS-PROTO-006:
// unknown or reserved information on a NON-critical frame is skippable, never
// fatal.
func TestSkippableConditionsDecode(t *testing.T) {
	t.Run("unknown type non-critical", func(t *testing.T) {
		b := validFrame(t, 0, nil)
		b[3] = 0x0F
		f, err := Decode(b)
		if err != nil {
			t.Fatalf("Decode: %v", err)
		}
		if f.Header.Type.Known() {
			t.Error("class 0x0F must be unknown")
		}
	})
	t.Run("reserved flag non-critical", func(t *testing.T) {
		b := validFrame(t, 0x0100, nil)
		if _, err := Decode(b); err != nil {
			t.Fatalf("reserved bit on a non-critical frame must be ignored: %v", err)
		}
	})
	t.Run("reserved byte non-critical", func(t *testing.T) {
		b := validFrame(t, 0, nil)
		b[57] = 0x01
		if _, err := Decode(b); err != nil {
			t.Fatalf("reserved byte on a non-critical frame must be ignored: %v", err)
		}
	})
	t.Run("minor version difference accepted", func(t *testing.T) {
		b := validFrame(t, 0, nil)
		b[2] = 0x11 // v1.1
		if _, err := Decode(b); err != nil {
			t.Fatalf("minor version difference must be accepted (ADR-007): %v", err)
		}
	})
}

func TestEncodeRejectsOversizedAndUnknown(t *testing.T) {
	session, message := fixedIDs()
	oversized := make([]byte, 1229) // HELLO bound is 1228
	_, err := Encode(&Frame{
		Header:  Header{Type: lumosconstants.MessageClassHello, SessionID: session, MessageID: message},
		Payload: oversized,
	})
	if got := code(t, err); got != lumoserrors.ErrFramePayloadTooLarge {
		t.Errorf("oversized encode: got %s", got)
	}

	_, err = Encode(&Frame{Header: Header{Type: lumosconstants.MessageClass(0x77)}})
	if got := code(t, err); got != lumoserrors.ErrFrameUnknownType {
		t.Errorf("unknown class encode: got %s", got)
	}
}

func TestHeaderBytesIsExactlyTheAAD(t *testing.T) {
	b := validFrame(t, 0, []byte("payload"))
	aad, err := HeaderBytes(b)
	if err != nil {
		t.Fatalf("HeaderBytes: %v", err)
	}
	if len(aad) != int(lumosconstants.FrameHeaderLenBytesDefault) {
		t.Errorf("AAD length %d, want %d", len(aad), lumosconstants.FrameHeaderLenBytesDefault)
	}
	if !strings.HasPrefix(hex.EncodeToString(b), hex.EncodeToString(aad)) {
		t.Error("AAD must be the leading header bytes of the encoded frame")
	}
	if _, err := HeaderBytes(b[:10]); err == nil {
		t.Error("HeaderBytes must reject input shorter than a header")
	}
}

// TestMaxPayloadBoundsFitTheWireField is the assertion Encode's #nosec G115
// annotation cites. Encode narrows len(f.Payload) to uint32 to fill the
// four-byte payload_length field, and that narrowing is safe only because the
// class bound checked immediately above it can never exceed what the field
// holds. gosec cannot see that argument, so it is written down here instead of
// being asserted in a comment: if a future register change pushes any
// MAX_PAYLOAD past 2^32-1, this test fails and the suppression stops being
// true at the same moment.
func TestMaxPayloadBoundsFitTheWireField(t *testing.T) {
	const maxUint32 = int64(^uint32(0))

	checked := 0
	for mc := range lumosconstants.MessageClassNames {
		bound, ok := lumosconstants.MaxPayloadBytes(mc)
		if !ok {
			t.Errorf("%s: every known class needs a payload bound (TRS-PROTO-007)", mc)
			continue
		}
		if bound < 0 || bound > maxUint32 {
			t.Errorf("%s: bound %d does not fit the uint32 payload_length field", mc, bound)
		}
		checked++
	}
	if checked == 0 {
		t.Fatal("no message classes were checked; the generated registry is empty")
	}
}
