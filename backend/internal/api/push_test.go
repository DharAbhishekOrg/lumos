// Lumos - backend/internal/api / A message wakes its recipient's phone through its push route.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-BCK-006, TRS-TST-006, SRS-IDN-008, TRS-TST-001
// ADRs: -
// Threading: test-only
// Notes: The push half of TRS-TST-006 at the HTTP edge: the recipient
// registers a token, a message arrives, and exactly one generic wake goes to
// that token - over memory and over PostgreSQL + Redis. The provider is a
// fake Sender; the real adapters are pinned against fake provider servers in
// the push package.
package api

import (
	"context"
	"encoding/hex"
	"net/http"
	"strings"
	"sync"
	"testing"

	"github.com/dharabhishekorg/lumos/backend/internal/push"
	"github.com/dharabhishekorg/lumos/backend/internal/registry"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumoserrors"
)

type capturingSender struct {
	mu     sync.Mutex
	tokens []string
}

func (c *capturingSender) Send(_ context.Context, token string) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.tokens = append(c.tokens, token)
	return nil
}

const bobsToken = "dXk1:APA91bH-bobs_token"

func TestAMessageWakesItsRecipientThroughItsPushRoute(t *testing.T) {
	eachRelayStack(t, func(t *testing.T, st relayStack) {
		fcm := &capturingSender{}
		disp := push.NewDispatcher(st.auth, map[registry.PushProvider]push.Sender{registry.PushFCM: fcm}, nil)
		st.relay.WakeWith(disp)
		alice, bob := enrol(t, st.srv), enrol(t, st.srv)
		if status, body := bob.do("PUT", "/v1/push/route", PushRouteJSON{Provider: "fcm", Token: bobsToken}); status != http.StatusNoContent {
			t.Fatalf("set push route: %d %s", status, body)
		}
		capability := issue(t, bob)
		id := randHex(t, 16)
		for range 2 { // the retry is a duplicate and wakes no one
			if status, body := sendText(alice, bob, id, capability, []byte("ciphertext")); status != http.StatusAccepted {
				t.Fatalf("send: %d %s", status, body)
			}
		}
		disp.Close()
		fcm.mu.Lock()
		got := append([]string(nil), fcm.tokens...)
		fcm.mu.Unlock()
		if len(got) != 1 || got[0] != bobsToken {
			t.Fatalf("one wake to bob's token, got %v", got)
		}
		if status, _ := bob.do("DELETE", "/v1/push/route", nil); status != http.StatusNoContent {
			t.Fatalf("clear push route: %d", status)
		}
		if _, err := st.auth.PushRoute(context.Background(), deviceID(t, bob)); err == nil {
			t.Fatal("a cleared route is gone")
		}
	})
}

func TestAPushRouteMustBeValidAndAuthenticated(t *testing.T) {
	srv := newRelayServer(t)
	bob := enrol(t, srv)
	for _, bad := range []PushRouteJSON{
		{Provider: "sms", Token: bobsToken},
		{Provider: "apns", Token: "not-hex"},
		{Provider: "fcm", Token: "has space"},
	} {
		status, body := bob.do("PUT", "/v1/push/route", bad)
		if status != http.StatusBadRequest || !contains(body, lumoserrors.ErrPayloadMalformed) {
			t.Errorf("%+v: want 400 %s, got %d %s", bad, lumoserrors.ErrPayloadMalformed, status, body)
		}
	}
	anon := &client{t: t, srv: srv}
	if status, _ := anon.do("PUT", "/v1/push/route", PushRouteJSON{Provider: "fcm", Token: bobsToken}); status != http.StatusUnauthorized {
		t.Fatalf("an anonymous caller cannot set a route: %d", status)
	}
}

func deviceID(t *testing.T, c *client) registry.DeviceID {
	t.Helper()
	raw, err := hex.DecodeString(c.routingID)
	var id registry.DeviceID
	if err != nil || len(raw) != len(id) {
		t.Fatalf("routing id %q is not %d hex bytes", c.routingID, len(id))
	}
	copy(id[:], raw)
	return id
}

func contains(body []byte, s string) bool { return strings.Contains(string(body), s) }

func TestADeviceCanRevokeItselfAndEverythingGoesWithIt(t *testing.T) {
	eachRelayStack(t, func(t *testing.T, st relayStack) {
		alice, bob := enrol(t, st.srv), enrol(t, st.srv)
		publishBob(t, bob, 2)
		if status, _ := bob.do("PUT", "/v1/push/route", PushRouteJSON{Provider: "fcm", Token: bobsToken}); status != http.StatusNoContent {
			t.Fatal("set push route")
		}
		if status, body := bob.do("DELETE", "/v1/devices/self", nil); status != http.StatusNoContent {
			t.Fatalf("revoke self: %d %s", status, body)
		}
		if status, _ := bob.do("GET", "/v1/prekeys/status", nil); status != http.StatusUnauthorized {
			t.Fatal("a revoked device's credential is dead (SRS-IDN-008)")
		}
		if status, _ := alice.do("POST", "/v1/prekeys/"+bob.routingID+"/claim", nil); status == http.StatusOK {
			t.Fatal("a revoked device's prekeys are withdrawn (SRS-IDN-008)")
		}
		if _, err := st.auth.PushRoute(context.Background(), deviceID(t, bob)); err == nil {
			t.Fatal("a revoked device's push route is dropped (SRS-IDN-008)")
		}
	})
}
