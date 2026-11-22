// Lumos - backend/internal/push / The FCM adapter against fake FCM and metadata servers.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-BCK-006, TRS-BCK-012, SRS-RMT-006, TRS-BCK-002, TRS-TST-001
// ADRs: -
// Threading: test-only
// Notes: The fake FCM server checks the exact request: the path, the bearer
// token from the metadata server, and a body that is the fixed generic wake
// and nothing more. The fake metadata server checks the Metadata-Flavor
// header and the FCM scope, and counts fetches to prove the token is cached.
package push

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func fakeMetadata(t *testing.T, fetches *atomic.Int32) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Metadata-Flavor") != "Google" ||
			r.URL.Path != "/computeMetadata/v1/instance/service-accounts/default/token" ||
			r.URL.Query().Get("scopes") != fcmScope {
			http.Error(w, "bad metadata request", http.StatusBadRequest)
			return
		}
		n := fetches.Add(1)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"access_token": "access-" + string(rune('0'+n)), "expires_in": 3600, "token_type": "Bearer"})
	}))
	t.Cleanup(srv.Close)
	return srv
}

type fcmFixture struct {
	fcm      *FCM
	fetches  atomic.Int32
	requests atomic.Int32
	status   atomic.Int32
	reply    atomic.Value // string
	lastAuth atomic.Value // string
}

func newFCMFixture(t *testing.T) *fcmFixture {
	t.Helper()
	f := &fcmFixture{}
	f.status.Store(http.StatusOK)
	f.reply.Store(`{"name":"projects/p/messages/1"}`)
	meta := fakeMetadata(t, &f.fetches)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.requests.Add(1)
		f.lastAuth.Store(r.Header.Get("Authorization"))
		if r.Method != http.MethodPost || r.URL.Path != "/v1/projects/lumos-staging-515413/messages:send" {
			http.Error(w, "bad path "+r.URL.Path, http.StatusNotFound)
			return
		}
		raw, _ := io.ReadAll(r.Body)
		var got map[string]any
		if err := json.Unmarshal(raw, &got); err != nil {
			http.Error(w, "bad json", http.StatusBadRequest)
			return
		}
		want := map[string]any{"message": map[string]any{
			"token":   testFCMToken,
			"data":    map[string]any{"lumos": "wake"},
			"android": map[string]any{"priority": "HIGH", "collapse_key": "lumos-wake"},
		}}
		if !reflect.DeepEqual(got, want) {
			t.Errorf("the FCM body must be exactly the generic wake:\n got %s", raw)
		}
		w.WriteHeader(int(f.status.Load()))
		_, _ = io.WriteString(w, f.reply.Load().(string))
	}))
	t.Cleanup(srv.Close)
	ts := NewMetadataTokenSource(meta.Client())
	ts.base = meta.URL
	f.fcm = NewFCM("lumos-staging-515413", ts, srv.Client())
	f.fcm.endpoint = srv.URL
	return f
}

func TestFCMSendsTheGenericWakeWithAWorkloadIdentityToken(t *testing.T) {
	f := newFCMFixture(t)
	ctx := context.Background()
	for range 3 {
		if err := f.fcm.Send(ctx, testFCMToken); err != nil {
			t.Fatal(err)
		}
	}
	if f.lastAuth.Load() != "Bearer access-1" {
		t.Fatalf("FCM must be called with the metadata server's token, got %q", f.lastAuth.Load())
	}
	if f.fetches.Load() != 1 {
		t.Fatalf("the access token is cached: want 1 metadata fetch for 3 sends, got %d", f.fetches.Load())
	}
}

func TestFCMReportsADeadTokenAndNothingElseAsGone(t *testing.T) {
	f := newFCMFixture(t)
	ctx := context.Background()
	for _, code := range []string{"UNREGISTERED", "SENDER_ID_MISMATCH"} {
		f.status.Store(http.StatusNotFound)
		f.reply.Store(`{"error":{"code":404,"status":"NOT_FOUND","details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"` + code + `"}]}}`)
		if err := f.fcm.Send(ctx, testFCMToken); !errors.Is(err, ErrTokenGone) {
			t.Fatalf("%s means the token is gone, got %v", code, err)
		}
	}
	f.status.Store(http.StatusServiceUnavailable)
	f.reply.Store(`{"error":{"code":503,"details":[{"errorCode":"UNAVAILABLE"}]}}`)
	err := f.fcm.Send(ctx, testFCMToken)
	if err == nil || errors.Is(err, ErrTokenGone) || strings.Contains(err.Error(), testFCMToken) {
		t.Fatalf("an outage is an error, not a dead token, and never names the token: %v", err)
	}
}

func TestFCMRefreshesItsAccessTokenAfterA401(t *testing.T) {
	f := newFCMFixture(t)
	ctx := context.Background()
	f.status.Store(http.StatusUnauthorized)
	f.reply.Store(`{"error":{"code":401,"status":"UNAUTHENTICATED"}}`)
	if err := f.fcm.Send(ctx, testFCMToken); err == nil {
		t.Fatal("a 401 is an error")
	}
	f.status.Store(http.StatusOK)
	f.reply.Store(`{}`)
	if err := f.fcm.Send(ctx, testFCMToken); err != nil {
		t.Fatal(err)
	}
	if f.fetches.Load() != 2 || f.lastAuth.Load() != "Bearer access-2" {
		t.Fatalf("after a 401 the next send uses a fresh token: fetches=%d auth=%q", f.fetches.Load(), f.lastAuth.Load())
	}
}

func TestTheAccessTokenIsRenewedBeforeItExpires(t *testing.T) {
	var fetches atomic.Int32
	meta := fakeMetadata(t, &fetches)
	ts := NewMetadataTokenSource(meta.Client())
	ts.base = meta.URL
	clock := time.Unix(1_700_000_000, 0)
	ts.now = func() time.Time { return clock }
	ctx := context.Background()
	if _, err := ts.Token(ctx); err != nil {
		t.Fatal(err)
	}
	clock = clock.Add(time.Hour - tokenRefreshMargin - time.Second)
	if _, err := ts.Token(ctx); err != nil || fetches.Load() != 1 {
		t.Fatalf("still fresh: fetches=%d err=%v", fetches.Load(), err)
	}
	clock = clock.Add(2 * time.Second)
	if _, err := ts.Token(ctx); err != nil || fetches.Load() != 2 {
		t.Fatalf("inside the refresh margin a new token is fetched: fetches=%d err=%v", fetches.Load(), err)
	}
}
