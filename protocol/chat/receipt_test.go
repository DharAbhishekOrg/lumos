// Lumos - protocol/chat / Receipts and relay routes: round trips, refusals, and a relay that cannot relabel.
// Author: dhar.abhishek.email@gmail.com
// Implements: SRS-RMT-005, SRS-RMT-014, SRS-RMT-009, SRS-RMT-001, TRS-PROTO-007, TRS-TST-001
// ADRs: ADR-001, ADR-002
// Threading: test-only
// Notes: The session test runs a real X3DH and ratchet between two fresh
// parties: Alice sends text, Bob acknowledges it with a delivered and then a
// read receipt, and Alice opens both. Then the relay's three relabelling
// moves - another class, another id, a receipt passed off as text - each
// fail with the stable code.
package chat

import (
	"bytes"
	"crypto/ed25519"
	"testing"

	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumoserrors"
)

func ids(n int) []MessageID {
	out := make([]MessageID, n)
	for i := range out {
		out[i] = mid(byte(i + 1))
	}
	return out
}

func TestAReceiptRoundTripsAtEveryLegalCount(t *testing.T) {
	for _, kind := range []lumosconstants.MessageClass{lumosconstants.MessageClassDeliveryReceipt,
		lumosconstants.MessageClassReadReceipt} {
		for _, n := range []int{1, 2, lumosconstants.ReceiptIDSPerEnvelopeDefault} {
			r := Receipt{ID: mid(0xEE), Kind: kind, Acked: ids(n)}
			b, err := r.Encode()
			if err != nil {
				t.Fatal(err)
			}
			if len(b) != MessageIDSize+2+n*MessageIDSize {
				t.Fatalf("length %d for %d ids", len(b), n)
			}
			got, err := DecodeReceipt(b)
			if err != nil || got.ID != r.ID || got.Kind != kind || len(got.Acked) != n || got.Acked[n-1] != r.Acked[n-1] {
				t.Fatalf("round trip %v %d: %+v %v", kind, n, got, err)
			}
		}
	}
}

func TestReceiptsOutsideTheLayoutAreRefused(t *testing.T) {
	if _, err := (Receipt{ID: mid(1), Kind: lumosconstants.MessageClassTextMessage, Acked: ids(1)}).Encode(); codeOf(err) != lumoserrors.ErrPayloadMalformed {
		t.Fatalf("a text class is not a receipt kind: %v", err)
	}
	if _, err := (Receipt{ID: mid(1), Kind: lumosconstants.MessageClassReadReceipt}).Encode(); codeOf(err) != lumoserrors.ErrPayloadMalformed {
		t.Fatalf("an empty receipt is refused: %v", err)
	}
	over := Receipt{ID: mid(1), Kind: lumosconstants.MessageClassReadReceipt, Acked: ids(lumosconstants.ReceiptIDSPerEnvelopeDefault + 1)}
	if _, err := over.Encode(); codeOf(err) != lumoserrors.ErrPayloadMalformed {
		t.Fatalf("an encoder sends at most the default: %v", err)
	}
	header := append(mid(1).bytes(), byte(lumosconstants.MessageClassDeliveryReceipt))
	cases := map[string][]byte{
		"short":         mid(1).bytes(),
		"zero count":    append(append([]byte(nil), header...), 0),
		"over max":      append(append(append([]byte(nil), header...), byte(lumosconstants.ReceiptIDSPerEnvelopeMax+1)), make([]byte, (lumosconstants.ReceiptIDSPerEnvelopeMax+1)*MessageIDSize)...),
		"count too big": append(append(append([]byte(nil), header...), 2), make([]byte, MessageIDSize)...),
		"trailing":      append(append(append([]byte(nil), header...), 1), make([]byte, MessageIDSize+1)...),
		"text kind":     append(append(mid(1).bytes(), byte(lumosconstants.MessageClassTextMessage), 1), make([]byte, MessageIDSize)...),
	}
	for name, b := range cases {
		if _, err := DecodeReceipt(b); codeOf(err) != lumoserrors.ErrPayloadMalformed {
			t.Errorf("%s: want ERR_PAYLOAD_MALFORMED, got %v", name, err)
		}
	}
	atMax := append(append(append([]byte(nil), header...), byte(lumosconstants.ReceiptIDSPerEnvelopeMax)), make([]byte, lumosconstants.ReceiptIDSPerEnvelopeMax*MessageIDSize)...)
	if r, err := DecodeReceipt(atMax); err != nil || len(r.Acked) != lumosconstants.ReceiptIDSPerEnvelopeMax {
		t.Fatalf("a decoder accepts up to the max: %v", err)
	}
}

func TestReceiptsTravelTheSessionAndCannotBeRelabelled(t *testing.T) {
	alice, bob := newParty(t, false), newParty(t, true)
	aState, prekey, err := Initiate(alice.id, bob.bundle, matchID, newX25519(t), newX25519(t))
	if err != nil {
		t.Fatal(err)
	}
	const now = 1_700_000_000
	sent, _ := Seal(aState, text(mid(1), "hello"), prekey, now)
	e, _ := DecodeEnvelope(sent)
	keys := ResponderKeys{SPK: bob.spk, SPKID: bob.spkID, OPK: bob.opk, OPKID: bob.opkID}
	bState, err := Respond(bob.id, keys, alice.id.Ed.Public().(ed25519.PublicKey), *e.Prekey, matchID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := Open(bState, e, mid(1), now, randKeys{}); err != nil {
		t.Fatal(err)
	}

	delivered := Receipt{ID: mid(0xD0), Kind: lumosconstants.MessageClassDeliveryReceipt, Acked: []MessageID{mid(1)}}
	read := Receipt{ID: mid(0xD1), Kind: lumosconstants.MessageClassReadReceipt, Acked: []MessageID{mid(1)}}
	dEnv, err := SealReceipt(bState, delivered, nil, now)
	if err != nil {
		t.Fatal(err)
	}
	rEnv, _ := SealReceipt(bState, read, nil, now)
	de, _ := DecodeEnvelope(dEnv)
	if de.Prekey != nil {
		t.Fatal("the responder's receipts are NORMAL envelopes")
	}
	got, err := OpenReceipt(aState, de, delivered.ID, lumosconstants.MessageClassDeliveryReceipt, now, randKeys{})
	if err != nil || got.Kind != lumosconstants.MessageClassDeliveryReceipt || got.Acked[0] != mid(1) {
		t.Fatalf("alice opens the delivered receipt: %+v %v", got, err)
	}

	// The relay relabels the read receipt as a delivered one: refused.
	re, _ := DecodeEnvelope(rEnv)
	if _, err := OpenReceipt(aState, re, read.ID, lumosconstants.MessageClassDeliveryReceipt, now, randKeys{}); codeOf(err) != lumoserrors.ErrPayloadMalformed {
		t.Fatalf("a receipt relabelled to another kind is refused: %v", err)
	}
	// A fresh read receipt under a different id: refused.
	r2Env, _ := SealReceipt(bState, Receipt{ID: mid(0xD2), Kind: lumosconstants.MessageClassReadReceipt, Acked: []MessageID{mid(1)}}, nil, now)
	r2, _ := DecodeEnvelope(r2Env)
	if _, err := OpenReceipt(aState, r2, mid(0xD3), lumosconstants.MessageClassReadReceipt, now, randKeys{}); codeOf(err) != lumoserrors.ErrPayloadMalformed {
		t.Fatalf("a receipt relabelled to another id is refused: %v", err)
	}
	// A receipt passed off as a text message: its kind byte sits where a text
	// event's kind does, and 17/18 are reserved there (CR-005), so it decodes
	// as an unknown event kind, which the text path ignores - nothing shows.
	r3Env, _ := SealReceipt(bState, Receipt{ID: mid(0xD4), Kind: lumosconstants.MessageClassReadReceipt, Acked: []MessageID{mid(1)}}, nil, now)
	r3, _ := DecodeEnvelope(r3Env)
	if _, err := Open(aState, r3, mid(0xD4), now, randKeys{}); codeOf(err) != lumoserrors.ErrTextUnknownEventKind {
		t.Fatalf("a receipt opened as text is an unknown event kind: %v", err)
	}
	// And a text passed off as a receipt: refused.
	tEnv, _ := Seal(bState, text(mid(0xD5), "hi"), nil, now)
	te, _ := DecodeEnvelope(tEnv)
	if _, err := OpenReceipt(aState, te, mid(0xD5), lumosconstants.MessageClassReadReceipt, now, randKeys{}); codeOf(err) != lumoserrors.ErrPayloadMalformed {
		t.Fatalf("a text opened as a receipt is refused: %v", err)
	}
}

func TestARouteRoundTripsAndAnythingElseIsRefused(t *testing.T) {
	var r Route
	for i := range r.RoutingID {
		r.RoutingID[i] = byte(i + 1)
	}
	for i := range r.Capability {
		r.Capability[i] = byte(0xF0 ^ i)
	}
	b := r.Encode()
	if len(b) != RouteSize || b[0] != 1 {
		t.Fatalf("route encoding %x", b)
	}
	if got, err := DecodeRoute(b); err != nil || got != r {
		t.Fatalf("round trip: %+v %v", got, err)
	}
	zeroID := bytes.Clone(b)
	copy(zeroID[1:1+RoutingIDSize], make([]byte, RoutingIDSize))
	zeroCap := bytes.Clone(b)
	copy(zeroCap[1+RoutingIDSize:], make([]byte, CapabilitySize))
	v2 := bytes.Clone(b)
	v2[0] = 2
	for name, bad := range map[string][]byte{
		"empty": nil, "short": b[:RouteSize-1], "long": append(bytes.Clone(b), 0),
		"version 2": v2, "zero routing id": zeroID, "zero capability": zeroCap,
	} {
		if _, err := DecodeRoute(bad); codeOf(err) != lumoserrors.ErrPayloadMalformed {
			t.Errorf("%s: want ERR_PAYLOAD_MALFORMED, got %v", name, err)
		}
	}
}

func FuzzReceiptAndRouteDecoders(f *testing.F) {
	ok, _ := Receipt{ID: mid(1), Kind: lumosconstants.MessageClassReadReceipt, Acked: ids(3)}.Encode()
	f.Add(ok)
	f.Add(Route{RoutingID: [16]byte{1}, Capability: [32]byte{2}}.Encode())
	f.Add([]byte{})
	f.Fuzz(func(t *testing.T, b []byte) {
		if r, err := DecodeReceipt(b); err == nil {
			again, err := r.Encode()
			if err == nil && !bytes.Equal(again, b) {
				t.Fatalf("receipt re-encodes differently: %x vs %x", again, b)
			}
		}
		if r, err := DecodeRoute(b); err == nil && !bytes.Equal(r.Encode(), b) {
			t.Fatalf("route re-encodes differently: %x vs %x", r.Encode(), b)
		}
	})
}
