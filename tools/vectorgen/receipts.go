// Lumos - tools/vectorgen / The chat/receipts.json family: RELAY_ROUTE payloads and sealed receipts.
// Author: dhar.abhishek.email@gmail.com
// Implements: SRS-RMT-005, SRS-RMT-014, SRS-RMT-009, SRS-RMT-001, TRS-PROTO-007, TRS-TST-001
// ADRs: ADR-001, ADR-002
// Threading: main-safe
// Notes: CR-005 (owner-accepted 2026-11-23). Three sections. routes: the
// 49-byte RELAY_ROUTE payload and its refusals. receipts: the receipt
// plaintext at the legal counts and its refusals. session: a recorded
// conversation on the remote-text keys - Alice sends two PREKEY texts while
// Bob is offline, Bob opens them and answers with a delivered receipt for
// both and a read receipt for the first, and Alice opens both. Every seal
// must reproduce envelope_hex and every open must yield plaintext_hex. The
// relabels are a relay's moves against the session: each names a message,
// the class and id a relay delivers it under instead, and the stable code
// the open must fail with. A refused open must not advance the ratchet, so a
// consumer replays the steps up to that open on fresh state and then tries
// the relabelled one.
package main

import (
	"crypto/ed25519"
	"encoding/hex"
	"fmt"

	"github.com/dharabhishekorg/lumos/protocol/chat"
	"github.com/dharabhishekorg/lumos/protocol/crypto/ratchet"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumoserrors"
)

type routeCase struct {
	Name          string `json:"name"`
	RoutingIDHex  string `json:"routing_id_hex"`
	CapabilityHex string `json:"capability_hex"`
	EncodedHex    string `json:"encoded_hex"`
}

type receiptCase struct {
	Name         string   `json:"name"`
	ReceiptIDHex string   `json:"receipt_id_hex"`
	Kind         int      `json:"kind"`
	AckedHex     []string `json:"acked_hex"`
	EncodedHex   string   `json:"encoded_hex"`
}

type receiptMessage struct {
	From         string `json:"from"`
	Class        int    `json:"class"`
	MessageIDHex string `json:"message_id_hex"`
	PlaintextHex string `json:"plaintext_hex"`
	Prekey       bool   `json:"prekey"`
	EnvelopeHex  string `json:"envelope_hex"`
}

type receiptRelabel struct {
	Name              string `json:"name"`
	Message           int    `json:"message"`
	DeliveredClass    int    `json:"delivered_class"`
	DeliveredIDHex    string `json:"delivered_id_hex"`
	ExpectedErrorCode string `json:"expected_error"`
}

type receiptSession struct {
	Description string           `json:"description"`
	MatchIDHex  string           `json:"match_id_hex"`
	Now         int64            `json:"now"`
	Alice       remoteAlice      `json:"alice"`
	Bob         remoteBob        `json:"bob"`
	Messages    []receiptMessage `json:"messages"`
	Steps       []remoteStep     `json:"steps"`
	Relabels    []receiptRelabel `json:"relabels"`
}

// receiptsFile is the emitted chat/receipts.json.
type receiptsFile struct {
	fileMeta
	Routes           []routeCase     `json:"routes"`
	RouteNegatives   []bytesNegative `json:"route_negatives"`
	Receipts         []receiptCase   `json:"receipts"`
	ReceiptNegatives []bytesNegative `json:"receipt_negatives"`
	Session          receiptSession  `json:"session"`
}

func (f *receiptsFile) implementsIDs() []string { return f.Implements }

func vectorRoute(label string) chat.Route {
	var r chat.Route
	copy(r.RoutingID[:], testonlyBytes("route|"+label+"|routing", chat.RoutingIDSize))
	copy(r.Capability[:], testonlyBytes("route|"+label+"|capability", chat.CapabilitySize))
	return r
}

func buildRoutes() ([]routeCase, []bytesNegative, error) {
	var cases []routeCase
	for _, label := range []string{"alice", "bob"} {
		r := vectorRoute(label)
		enc := r.Encode()
		back, err := chat.DecodeRoute(enc)
		if err != nil || back != r {
			return nil, nil, fmt.Errorf("route %s does not round-trip: %w", label, err)
		}
		cases = append(cases, routeCase{Name: label, RoutingIDHex: hex.EncodeToString(r.RoutingID[:]),
			CapabilityHex: hex.EncodeToString(r.Capability[:]), EncodedHex: hex.EncodeToString(enc)})
	}
	good := vectorRoute("alice").Encode()
	with := func(f func(b []byte) []byte) []byte { return f(append([]byte(nil), good...)) }
	negs, err := checkedNegatives(func(b []byte) error { _, err := chat.DecodeRoute(b); return err }, allMalformed([]bytesNegative{
		{Name: "empty", EncodedHex: ""},
		{Name: "one byte short", EncodedHex: hex.EncodeToString(good[:chat.RouteSize-1])},
		{Name: "one byte long", EncodedHex: hex.EncodeToString(append(append([]byte(nil), good...), 0))},
		{Name: "unknown version", EncodedHex: hex.EncodeToString(with(func(b []byte) []byte { b[0] = 2; return b }))},
		{Name: "all-zero routing id", EncodedHex: hex.EncodeToString(with(func(b []byte) []byte {
			copy(b[1:1+chat.RoutingIDSize], make([]byte, chat.RoutingIDSize))
			return b
		}))},
		{Name: "all-zero capability", EncodedHex: hex.EncodeToString(with(func(b []byte) []byte {
			copy(b[1+chat.RoutingIDSize:], make([]byte, chat.CapabilitySize))
			return b
		}))},
	}))
	return cases, negs, err
}

// allMalformed marks every case with the one code both decoders use.
func allMalformed(cases []bytesNegative) []bytesNegative {
	for i := range cases {
		cases[i].ExpectedError = lumoserrors.ErrPayloadMalformed
	}
	return cases
}

func ackedIDs(n int) []chat.MessageID {
	out := make([]chat.MessageID, n)
	for i := range out {
		out[i] = vectorMessageID(byte(0x80 + i))
	}
	return out
}

func buildReceiptCases() ([]receiptCase, []bytesNegative, error) {
	var cases []receiptCase
	for _, c := range []struct {
		name string
		kind lumosconstants.MessageClass
		n    int
	}{
		{"delivered, one id", lumosconstants.MessageClassDeliveryReceipt, 1},
		{"read, three ids", lumosconstants.MessageClassReadReceipt, 3},
		{"delivered, the default batch", lumosconstants.MessageClassDeliveryReceipt, lumosconstants.ReceiptIDSPerEnvelopeDefault},
	} {
		r := chat.Receipt{ID: vectorMessageID(90), Kind: c.kind, Acked: ackedIDs(c.n)}
		enc, err := r.Encode()
		if err != nil {
			return nil, nil, err
		}
		acked := make([]string, c.n)
		for i, id := range r.Acked {
			acked[i] = hex.EncodeToString(id[:])
		}
		cases = append(cases, receiptCase{Name: c.name, ReceiptIDHex: hex.EncodeToString(r.ID[:]), Kind: int(c.kind),
			AckedHex: acked, EncodedHex: hex.EncodeToString(enc)})
	}
	id := vectorMessageID(90)
	head := func(kind lumosconstants.MessageClass, count int) []byte {
		return append(append([]byte(nil), id[:]...), byte(kind), byte(count))
	}
	body := func(n int) []byte { return make([]byte, n*chat.MessageIDSize) }
	over := lumosconstants.ReceiptIDSPerEnvelopeMax + 1
	negs, err := checkedNegatives(func(b []byte) error { _, err := chat.DecodeReceipt(b); return err }, allMalformed([]bytesNegative{
		{Name: "shorter than the header", EncodedHex: hex.EncodeToString(id[:])},
		{Name: "a text class as the kind", EncodedHex: hex.EncodeToString(append(head(lumosconstants.MessageClassTextMessage, 1), body(1)...))},
		{Name: "zero ids", EncodedHex: hex.EncodeToString(head(lumosconstants.MessageClassReadReceipt, 0))},
		{Name: "more ids than the max", EncodedHex: hex.EncodeToString(append(head(lumosconstants.MessageClassReadReceipt, over), body(over)...))},
		{Name: "count larger than the ids present", EncodedHex: hex.EncodeToString(append(head(lumosconstants.MessageClassDeliveryReceipt, 2), body(1)...))},
		{Name: "trailing byte", EncodedHex: hex.EncodeToString(append(append(head(lumosconstants.MessageClassDeliveryReceipt, 1), body(1)...), 0))},
	}))
	return cases, negs, err
}

// receiptScript is the conversation: two texts, then Bob's two receipts.
func receiptScript() []receiptMessage {
	return []receiptMessage{
		{From: "alice", Class: int(lumosconstants.MessageClassTextMessage)},
		{From: "alice", Class: int(lumosconstants.MessageClassTextMessage)},
		{From: "bob", Class: int(lumosconstants.MessageClassDeliveryReceipt)},
		{From: "bob", Class: int(lumosconstants.MessageClassReadReceipt)},
	}
}

var receiptSteps = []remoteStep{
	{"alice", "seal", 0}, {"alice", "seal", 1},
	{"bob", "open", 0}, {"bob", "open", 1},
	{"bob", "seal", 2}, {"bob", "seal", 3},
	{"alice", "open", 2}, {"alice", "open", 3},
}

func receiptPlaintexts() ([][]byte, error) {
	texts := []chat.Plaintext{
		{MessageID: vectorMessageID(20), Event: chat.TextEvent{Kind: lumosconstants.TextEventKindText, Body: []byte("Did you get home okay?")}},
		{MessageID: vectorMessageID(21), Event: chat.TextEvent{Kind: lumosconstants.TextEventKindText, Body: []byte("Lovely evening.")}},
	}
	receipts := []chat.Receipt{
		{ID: vectorMessageID(22), Kind: lumosconstants.MessageClassDeliveryReceipt, Acked: []chat.MessageID{texts[0].MessageID, texts[1].MessageID}},
		{ID: vectorMessageID(23), Kind: lumosconstants.MessageClassReadReceipt, Acked: []chat.MessageID{texts[0].MessageID}},
	}
	out := make([][]byte, 0, 4)
	for _, p := range texts {
		b, err := p.Encode()
		if err != nil {
			return nil, err
		}
		out = append(out, b)
	}
	for _, r := range receipts {
		b, err := r.Encode()
		if err != nil {
			return nil, err
		}
		out = append(out, b)
	}
	return out, nil
}

// receiptPlay runs the script on fresh state, stopping before step stop
// (len(receiptSteps) runs it all).
type receiptPlay struct {
	k      *sessionKeys
	aState *ratchet.State
	bState *ratchet.State
	prekey *chat.PrekeyFields
	heard  bool
	pts    [][]byte
	envs   [][]byte
}

func newReceiptPlay() (*receiptPlay, error) {
	k, err := newSessionKeys()
	if err != nil {
		return nil, err
	}
	pts, err := receiptPlaintexts()
	if err != nil {
		return nil, err
	}
	aState, prekey, err := chat.Initiate(chat.Identity{Ed: k.aliceEd, DH: k.aliceDH}, k.bobBundle(), vectorMatchID, k.eph, k.aliceFirst)
	if err != nil {
		return nil, err
	}
	return &receiptPlay{k: k, aState: aState, prekey: prekey, pts: pts, envs: make([][]byte, len(pts))}, nil
}

func (r *receiptPlay) sealStep(s remoteStep) error {
	now := r.k.file.Now
	var err error
	switch {
	case s.Message >= 2:
		var rc chat.Receipt
		if rc, err = chat.DecodeReceipt(r.pts[s.Message]); err == nil {
			r.envs[s.Message], err = chat.SealReceipt(r.bState, rc, nil, now)
		}
	default:
		var p chat.Plaintext
		if p, err = chat.DecodePlaintext(r.pts[s.Message]); err == nil {
			pk := r.prekey
			if r.heard {
				pk = nil
			}
			r.envs[s.Message], err = chat.Seal(r.aState, p, pk, now)
		}
	}
	return err
}

func (r *receiptPlay) openStep(s remoteStep, class lumosconstants.MessageClass, id chat.MessageID) ([]byte, error) {
	e, err := chat.DecodeEnvelope(r.envs[s.Message])
	if err != nil {
		return nil, err
	}
	now := r.k.file.Now
	if s.Actor == "bob" {
		if r.bState == nil {
			k := r.k
			keys := chat.ResponderKeys{SPK: k.bobSPK, SPKID: k.file.Bob.SPKID, OPK: k.bobOPK, OPKID: k.file.Bob.OPKID}
			if r.bState, err = chat.Respond(chat.Identity{Ed: k.bobEd, DH: k.bobDH}, keys,
				k.aliceEd.Public().(ed25519.PublicKey), *e.Prekey, vectorMatchID); err != nil {
				return nil, err
			}
		}
		p, err := chat.Open(r.bState, e, id, now, r.k.bobTurns)
		if err != nil {
			return nil, err
		}
		return p.Encode()
	}
	r.heard = true
	if class == lumosconstants.MessageClassTextMessage {
		p, err := chat.Open(r.aState, e, id, now, r.k.aliceTurns)
		if err != nil {
			return nil, err
		}
		return p.Encode()
	}
	rc, err := chat.OpenReceipt(r.aState, e, id, class, now, r.k.aliceTurns)
	if err != nil {
		return nil, err
	}
	return rc.Encode()
}

func (r *receiptPlay) run(stop int) error {
	for i, s := range receiptSteps[:stop] {
		if s.Op == "seal" {
			if err := r.sealStep(s); err != nil {
				return fmt.Errorf("step %d: %w", i, err)
			}
			continue
		}
		class := lumosconstants.MessageClass(receiptScript()[s.Message].Class)
		var id chat.MessageID
		copy(id[:], r.pts[s.Message][:chat.MessageIDSize])
		got, err := r.openStep(s, class, id)
		if err != nil {
			return fmt.Errorf("step %d: %w", i, err)
		}
		if hex.EncodeToString(got) != hex.EncodeToString(r.pts[s.Message]) {
			return fmt.Errorf("step %d: plaintext mismatch", i)
		}
	}
	return nil
}

// relabelled replays up to the step that opens message m, then opens it as
// a relay would deliver it, and returns the stable code.
func relabelled(m int, class lumosconstants.MessageClass, id chat.MessageID) (string, error) {
	r, err := newReceiptPlay()
	if err != nil {
		return "", err
	}
	stop := -1
	for i, s := range receiptSteps {
		if s.Op == "open" && s.Message == m {
			stop = i
			break
		}
	}
	if err := r.run(stop); err != nil {
		return "", err
	}
	_, err = r.openStep(receiptSteps[stop], class, id)
	if err == nil {
		return "", fmt.Errorf("relabelled message %d opened", m)
	}
	return stableCode(err), nil
}

func buildReceiptSession() (receiptSession, error) {
	r, err := newReceiptPlay()
	if err != nil {
		return receiptSession{}, err
	}
	if err := r.run(len(receiptSteps)); err != nil {
		return receiptSession{}, err
	}
	s := receiptSession{MatchIDHex: r.k.file.MatchIDHex, Now: r.k.file.Now, Alice: r.k.file.Alice, Bob: r.k.file.Bob,
		Steps: receiptSteps}
	for i, m := range receiptScript() {
		e, _ := chat.DecodeEnvelope(r.envs[i])
		m.MessageIDHex = hex.EncodeToString(r.pts[i][:chat.MessageIDSize])
		m.PlaintextHex = hex.EncodeToString(r.pts[i])
		m.Prekey = e.Prekey != nil
		m.EnvelopeHex = hex.EncodeToString(r.envs[i])
		s.Messages = append(s.Messages, m)
	}
	readID, deliveredID := vectorMessageID(23), vectorMessageID(22)
	for _, c := range []struct {
		name  string
		m     int
		class lumosconstants.MessageClass
		id    chat.MessageID
	}{
		{"a read receipt delivered as a delivered receipt", 3, lumosconstants.MessageClassDeliveryReceipt, readID},
		{"a delivered receipt delivered as a read receipt", 2, lumosconstants.MessageClassReadReceipt, deliveredID},
		{"a read receipt delivered under another id", 3, lumosconstants.MessageClassReadReceipt, vectorMessageID(99)},
		{"a read receipt delivered as a text message", 3, lumosconstants.MessageClassTextMessage, readID},
	} {
		code, err := relabelled(c.m, c.class, c.id)
		if err != nil {
			return receiptSession{}, fmt.Errorf("%s: %w", c.name, err)
		}
		s.Relabels = append(s.Relabels, receiptRelabel{Name: c.name, Message: c.m, DeliveredClass: int(c.class),
			DeliveredIDHex: hex.EncodeToString(c.id[:]), ExpectedErrorCode: code})
	}
	for _, rl := range s.Relabels {
		if rl.ExpectedErrorCode != lumoserrors.ErrPayloadMalformed && rl.ExpectedErrorCode != lumoserrors.ErrTextUnknownEventKind {
			return receiptSession{}, fmt.Errorf("%s: unexpected code %q", rl.Name, rl.ExpectedErrorCode)
		}
	}
	return s, nil
}

// buildReceiptVectors emits chat/receipts.json.
func buildReceiptVectors(constantsHash string) (*receiptsFile, error) {
	routes, routeNeg, err := buildRoutes()
	if err != nil {
		return nil, err
	}
	receipts, receiptNeg, err := buildReceiptCases()
	if err != nil {
		return nil, err
	}
	session, err := buildReceiptSession()
	if err != nil {
		return nil, fmt.Errorf("receipt session: %w", err)
	}
	session.Description = "On the remote_text.json keys: Alice sends two PREKEY texts while Bob is offline; Bob opens " +
		"them and replies with a delivered receipt for both and a read receipt for the first (NORMAL envelopes); " +
		"Alice opens both receipts under their classes. Replay the steps in order: a seal must reproduce " +
		"envelope_hex, an open must yield plaintext_hex. Each relabel is a relay's move: replay on fresh state " +
		"up to the step that opens that message, open it under delivered_class and delivered_id_hex instead, " +
		"and expect expected_error. A text decoded from a receipt fails with ERR_TEXT_UNKNOWN_EVENT_KIND, " +
		"because text event kinds 17 and 18 are reserved (CR-005)."
	return &receiptsFile{
		fileMeta: fileMeta{
			SchemaVersion: 1, Generator: GeneratorName, ConstantsHash: constantsHash,
			Implements: []string{"SRS-RMT-001", "SRS-RMT-005", "SRS-RMT-009", "SRS-RMT-014", "TRS-PROTO-007",
				"TRS-CRY-002"},
			KeyProvenance: KeyProvenance,
			Notes: "CR-005 (protocol/chat/route.go, protocol/chat/receipt.go): RELAY_ROUTE (0x07) is version u8 = 1 | " +
				"routing_id[16] | capability[32], 49 bytes, no all-zero field. A receipt plaintext is receipt_id[16] | " +
				"kind u8 (0x11 delivered, 0x12 read) | count u8 (1..RECEIPT_IDS_PER_ENVELOPE.max) | " +
				"message_id[16] x count, sealed in the chat's ratchet in the remote_text.json envelope; the kind " +
				"must equal the delivered class and the receipt id the delivered id.",
		},
		Routes: routes, RouteNegatives: routeNeg,
		Receipts: receipts, ReceiptNegatives: receiptNeg,
		Session: session,
	}, nil
}
