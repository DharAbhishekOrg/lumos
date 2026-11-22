// Lumos - backend/internal/api / Two devices exchange envelopes through the relay over HTTP.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-BCK-001, TRS-BCK-002, TRS-BCK-004, SRS-RMT-002, SRS-RMT-009, SRS-RMT-015, SRS-RMT-016, TRS-TST-006, TRS-TST-001
// ADRs: -
// Threading: test-only
// Notes: The relay half of TRS-TST-006 at the HTTP edge: offline queue (send
// while the recipient is away, fetch later), duplicate, replay,
// acknowledgement and block, each over memory and over the deployed pairing
// (PostgreSQL + Redis). Push is S6 and expiry is pinned in the relay
// package with an injected clock. The log audit captures every log line the
// whole flow produces and proves no payload, capability or credential is in it
// (TRS-BCK-002). The fuzz target feeds arbitrary bytes to the send route: no
// input may panic it or earn a 5xx.
package api

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"log"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/dharabhishekorg/lumos/backend/internal/registry"
	"github.com/dharabhishekorg/lumos/backend/internal/relay"
	"github.com/dharabhishekorg/lumos/backend/internal/testdb"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumoserrors"
)

func newRelayServer(t *testing.T) *httptest.Server {
	t.Helper()
	store := relay.NewMemoryStore()
	return relayServerOver(t, registry.NewMemoryRepository(), store, store)
}

// newServerRelayServer is the deployed pairing: registry and capabilities on
// PostgreSQL, envelopes on Redis.
func newServerRelayServer(t *testing.T) *httptest.Server {
	t.Helper()
	pool := testdb.Postgres(t)
	if err := registry.Migrate(context.Background(), pool); err != nil {
		t.Fatal(err)
	}
	rdb, ns := testdb.Redis(t)
	return relayServerOver(t, registry.NewPostgresRepository(pool),
		relay.NewPostgresCapabilities(pool), relay.NewRedisMailboxes(rdb, ns))
}

func relayServerOver(t *testing.T, repo registry.Repository, caps relay.Capabilities, mail relay.Mailboxes) *httptest.Server {
	t.Helper()
	now := func() int64 { return 1_700_000_000 }
	auth := registry.NewService(repo, now, rand.Reader)
	mux := http.NewServeMux()
	NewRegistry(auth).Register(mux)
	NewRelay(relay.NewService(caps, mail, now), auth).Register(mux)
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return srv
}

// eachRelayServer runs an end-to-end flow over memory and over the servers.
func eachRelayServer(t *testing.T, run func(t *testing.T, srv *httptest.Server)) {
	t.Helper()
	t.Run("memory", func(t *testing.T) { run(t, newRelayServer(t)) })
	t.Run("postgres+redis", func(t *testing.T) { run(t, newServerRelayServer(t)) })
}

func randHex(t *testing.T, n int) string {
	t.Helper()
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		t.Fatal(err)
	}
	return hex.EncodeToString(b)
}

// issue makes owner grant a fresh capability and returns its raw hex.
func issue(t *testing.T, owner *client) string {
	t.Helper()
	raw := randHex(t, 32)
	b, _ := hex.DecodeString(raw)
	var c [32]byte
	copy(c[:], b)
	p := relay.ProofOf(c)
	if status, body := owner.do("PUT", "/v1/mailbox/capabilities", map[string]string{"proof": hex.EncodeToString(p[:])}); status != http.StatusNoContent {
		t.Fatalf("allow: %d %s", status, body)
	}
	return raw
}

func sendText(sender *client, to *client, id, capability string, payload []byte) (int, []byte) {
	return sender.do("POST", "/v1/envelopes", SubmitJSON{
		Recipient: to.routingID, MessageID: id, Class: int(lumosconstants.MessageClassTextMessage),
		Capability: capability, Payload: base64.StdEncoding.EncodeToString(payload),
	})
}

func fetchAll(t *testing.T, c *client) []EnvelopeJSON {
	t.Helper()
	status, body := c.do("GET", "/v1/envelopes", nil)
	if status != http.StatusOK {
		t.Fatalf("fetch: %d %s", status, body)
	}
	var out struct{ Envelopes []EnvelopeJSON }
	if err := json.Unmarshal(body, &out); err != nil {
		t.Fatal(err)
	}
	return out.Envelopes
}

func TestAMessageWaitsUntilItsRecipientFetchesAndAcknowledgesIt(t *testing.T) {
	eachRelayServer(t, func(t *testing.T, srv *httptest.Server) {
		alice, bob := enrol(t, srv), enrol(t, srv)
		capability := issue(t, bob)
		id := randHex(t, 16)
		ciphertext := []byte("opaque ratchet output")

		for range 2 {
			if status, body := sendText(alice, bob, id, capability, ciphertext); status != http.StatusAccepted {
				t.Fatalf("send (and its retry): %d %s", status, body)
			}
		}
		got := fetchAll(t, bob)
		if len(got) != 1 || got[0].MessageID != id {
			t.Fatalf("a send and its retry must arrive exactly once, got %d", len(got))
		}
		payload, _ := base64.StdEncoding.DecodeString(got[0].Payload)
		capBytes, _ := hex.DecodeString(capability)
		var c [32]byte
		copy(c[:], capBytes)
		wantProof := relay.ProofOf(c)
		if !bytes.Equal(payload, ciphertext) || got[0].SenderProof != hex.EncodeToString(wantProof[:]) {
			t.Fatal("the recipient must get the exact bytes and the proof of the capability it issued")
		}
		if len(fetchAll(t, alice)) != 0 {
			t.Fatal("a device sees only its own mailbox")
		}

		status, body := bob.do("POST", "/v1/envelopes/ack", map[string][]string{"message_ids": {id}})
		if status != http.StatusOK || !strings.Contains(string(body), `"acknowledged":1`) {
			t.Fatalf("ack: %d %s", status, body)
		}
		if len(fetchAll(t, bob)) != 0 {
			t.Fatal("an acknowledged envelope is deleted (SRS-RMT-015)")
		}
		if status, _ := sendText(alice, bob, id, capability, ciphertext); status != http.StatusAccepted || len(fetchAll(t, bob)) != 0 {
			t.Fatal("a late retry is accepted but never redelivered")
		}
		status, body = sendText(alice, bob, id, capability, []byte("different bytes"))
		if status != http.StatusBadRequest || !strings.Contains(string(body), lumoserrors.ErrCryptoReplay) {
			t.Fatalf("the same id with new bytes is a replay: %d %s", status, body)
		}
	})
}

func TestABlockedSenderGetsTheSameAnswerAndNothingArrives(t *testing.T) {
	eachRelayServer(t, func(t *testing.T, srv *httptest.Server) {
		alice, bob := enrol(t, srv), enrol(t, srv)
		capability := issue(t, bob)
		acceptedStatus, acceptedBody := sendText(alice, bob, randHex(t, 16), capability, []byte("hi"))

		capBytes, _ := hex.DecodeString(capability)
		var c [32]byte
		copy(c[:], capBytes)
		p := relay.ProofOf(c)
		if status, body := bob.do("DELETE", "/v1/mailbox/capabilities/"+hex.EncodeToString(p[:]), nil); status != http.StatusNoContent {
			t.Fatalf("revoke: %d %s", status, body)
		}
		blockedStatus, blockedBody := sendText(alice, bob, randHex(t, 16), capability, []byte("still there?"))
		if blockedStatus != acceptedStatus || !bytes.Equal(blockedBody, acceptedBody) {
			t.Fatalf("a blocked send must look exactly like a delivered one: %d %q vs %d %q",
				blockedStatus, blockedBody, acceptedStatus, acceptedBody)
		}
		if got := fetchAll(t, bob); len(got) != 1 {
			t.Fatalf("only the message from before the block may arrive (SRS-RMT-016), got %d", len(got))
		}
	})
}

func TestTheRelayRefusesWhatItMustAndSaysOnlyACode(t *testing.T) {
	srv := newRelayServer(t)
	alice, bob := enrol(t, srv), enrol(t, srv)
	capability := issue(t, bob)

	stranger := &client{t: t, srv: srv, credential: strings.Repeat("ab", 32)}
	if status, body := sendText(stranger, bob, randHex(t, 16), capability, []byte("x")); status != http.StatusUnauthorized ||
		!strings.Contains(string(body), lumoserrors.ErrAuthRejected) {
		t.Fatalf("an unauthenticated send: %d %s", status, body)
	}
	if status, _ := stranger.do("GET", "/v1/envelopes", nil); status != http.StatusUnauthorized {
		t.Fatalf("an unauthenticated fetch: %d", status)
	}
	status, body := alice.do("POST", "/v1/envelopes", SubmitJSON{
		Recipient: bob.routingID, MessageID: randHex(t, 16), Class: int(lumosconstants.MessageClassObjectChunk),
		Capability: capability, Payload: base64.StdEncoding.EncodeToString([]byte("photo bytes")),
	})
	if status != http.StatusBadRequest || !strings.Contains(string(body), lumoserrors.ErrTransportLocalOnlyViolation) {
		t.Fatalf("media must never ride the internet relay (SRS-TRN-009): %d %s", status, body)
	}
	limit, _ := lumosconstants.MaxPayloadBytes(lumosconstants.MessageClassTextMessage)
	if status, body := sendText(alice, bob, randHex(t, 16), capability, make([]byte, limit+1)); status != http.StatusBadRequest ||
		!strings.Contains(string(body), lumoserrors.ErrFramePayloadTooLarge) {
		t.Fatalf("oversized: %d %s", status, body)
	}
	for _, bad := range []any{
		map[string]string{"recipient": "zz"},
		SubmitJSON{Recipient: bob.routingID, MessageID: "short", Capability: capability, Payload: "AA=="},
		SubmitJSON{Recipient: bob.routingID, MessageID: randHex(t, 16), Class: 300, Capability: capability, Payload: "AA=="},
		SubmitJSON{Recipient: bob.routingID, MessageID: randHex(t, 16), Class: 16, Capability: capability, Payload: "!!not base64"},
	} {
		status, body := alice.do("POST", "/v1/envelopes", bad)
		if status != http.StatusBadRequest || !strings.Contains(string(body), lumoserrors.ErrPayloadMalformed) {
			t.Fatalf("malformed %v: %d %s", bad, status, body)
		}
		if bytes.Contains(body, []byte("payload")) || bytes.Contains(body, []byte(capability)) {
			t.Fatal("an error names a code, never echoes the request")
		}
	}
}

func TestNothingTheRelayHandlesReachesTheLog(t *testing.T) {
	var logged bytes.Buffer
	prev := log.Writer()
	log.SetOutput(&logged)
	t.Cleanup(func() { log.SetOutput(prev) })

	srv := newRelayServer(t)
	alice, bob := enrol(t, srv), enrol(t, srv)
	capability := issue(t, bob)
	secret := []byte("TESTONLY-plaintext-marker-that-must-never-be-logged")
	id := randHex(t, 16)
	sendText(alice, bob, id, capability, secret)
	sendText(alice, bob, id, capability, []byte("replayed"))
	fetchAll(t, bob)
	bob.do("POST", "/v1/envelopes/ack", map[string][]string{"message_ids": {id}})

	for _, needle := range []string{
		string(secret), base64.StdEncoding.EncodeToString(secret), capability, alice.credential, bob.credential,
	} {
		if strings.Contains(logged.String(), needle) {
			t.Fatal("the log carried a payload, a capability or a credential (TRS-BCK-002)")
		}
	}
	if logged.Len() != 0 {
		t.Fatalf("the relay logs nothing at all on these paths; got %q", logged.String())
	}
}

func FuzzTheSendRouteNeverPanicsOrFailsInternally(f *testing.F) {
	f.Add([]byte(`{"recipient":"00","message_id":"00","class":16,"capability":"00","payload":"AA=="}`))
	f.Add([]byte(`{}`))
	f.Add([]byte(`[]`))
	f.Add([]byte(`{"class":-1}`))
	f.Add([]byte(`{"payload":"` + strings.Repeat("A", 4096) + `"}`))
	f.Add([]byte("\x00\xff{"))
	auth := registry.NewService(registry.NewMemoryRepository(), func() int64 { return 1_700_000_000 }, rand.Reader)
	store := relay.NewMemoryStore()
	h := NewRelay(relay.NewService(store, store, func() int64 { return 1_700_000_000 }), auth)
	f.Fuzz(func(t *testing.T, body []byte) {
		var req SubmitJSON
		if json.Unmarshal(body, &req) == nil {
			if in, ok := parseSubmit(req); ok {
				_ = in
			}
		}
		rec := httptest.NewRecorder()
		r := httptest.NewRequest(http.MethodPost, "/v1/envelopes", bytes.NewReader(body))
		h.submit(rec, r, registry.Device{})
		if rec.Code >= 500 {
			t.Fatalf("input %q earned a %d", body, rec.Code)
		}
	})
}
