// Lumos - backend/internal/push / The dispatcher's rules, with fake providers.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-BCK-006, SRS-IDN-008, TRS-TST-006, TRS-TST-001
// ADRs: -
// Threading: test-only
// Notes: Routes come from a real registry service on the memory repository,
// so "a dead token drops the route, a stale report does not" is checked
// against the registry's own rule, not a mock of it.
package push

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"errors"
	"strings"
	"sync"
	"testing"

	"github.com/dharabhishekorg/lumos/backend/internal/registry"
)

type fakeSender struct {
	mu     sync.Mutex
	tokens []string
	err    error
}

func (f *fakeSender) Send(_ context.Context, token string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.tokens = append(f.tokens, token)
	return f.err
}

func (f *fakeSender) sent() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]string(nil), f.tokens...)
}

func newRegistry(t *testing.T) (*registry.Service, registry.Device) {
	t.Helper()
	svc := registry.NewService(registry.NewMemoryRepository(), func() int64 { return 1_700_000_000 }, rand.Reader)
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	ch, err := svc.IssueChallenge()
	if err != nil {
		t.Fatal(err)
	}
	var dp [32]byte
	copy(dp[:], pub)
	reg, err := svc.Register(context.Background(), ch, dp, ed25519.Sign(priv, registry.AuthMessage(ch, dp)))
	if err != nil {
		t.Fatal(err)
	}
	d, err := svc.Authenticate(context.Background(), reg.Credential[:])
	if err != nil {
		t.Fatal(err)
	}
	return svc, d
}

const testFCMToken = "fcm:token-1"

func TestAWakeGoesToTheDevicesOneRoute(t *testing.T) {
	reg, d := newRegistry(t)
	ctx := context.Background()
	fcm, apns := &fakeSender{}, &fakeSender{}
	disp := NewDispatcher(reg, map[registry.PushProvider]Sender{registry.PushFCM: fcm, registry.PushAPNs: apns}, nil)
	if err := disp.Wake(ctx, d.ID); err != nil || len(fcm.sent())+len(apns.sent()) != 0 {
		t.Fatalf("a device without a route is not woken: err=%v", err)
	}
	if err := reg.SetPushRoute(ctx, d, registry.PushFCM, testFCMToken); err != nil {
		t.Fatal(err)
	}
	if err := disp.Wake(ctx, d.ID); err != nil {
		t.Fatal(err)
	}
	if got := fcm.sent(); len(got) != 1 || got[0] != testFCMToken || len(apns.sent()) != 0 {
		t.Fatalf("one wake to the stored FCM token, got fcm=%v apns=%v", got, apns.sent())
	}
}

func TestAnUnconfiguredProviderIsSkipped(t *testing.T) {
	reg, d := newRegistry(t)
	ctx := context.Background()
	if err := reg.SetPushRoute(ctx, d, registry.PushAPNs, "abcd"); err != nil {
		t.Fatal(err)
	}
	disp := NewDispatcher(reg, map[registry.PushProvider]Sender{registry.PushFCM: &fakeSender{}}, nil)
	if err := disp.Wake(ctx, d.ID); err != nil {
		t.Fatalf("an environment without APNs skips iPhone wakes quietly: %v", err)
	}
	if _, err := reg.PushRoute(ctx, d.ID); err != nil {
		t.Fatal("skipping must not drop the route")
	}
}

func TestADeadTokenDropsTheRoute(t *testing.T) {
	reg, d := newRegistry(t)
	ctx := context.Background()
	if err := reg.SetPushRoute(ctx, d, registry.PushFCM, testFCMToken); err != nil {
		t.Fatal(err)
	}
	disp := NewDispatcher(reg, map[registry.PushProvider]Sender{registry.PushFCM: &fakeSender{err: ErrTokenGone}}, nil)
	if err := disp.Wake(ctx, d.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := reg.PushRoute(ctx, d.ID); !errors.Is(err, registry.ErrNotFound) {
		t.Fatalf("a token the provider calls dead is dropped, got %v", err)
	}
}

func TestBackgroundWakesReportErrorsWithoutTheToken(t *testing.T) {
	reg, d := newRegistry(t)
	ctx := context.Background()
	if err := reg.SetPushRoute(ctx, d, registry.PushFCM, testFCMToken); err != nil {
		t.Fatal(err)
	}
	var mu sync.Mutex
	var reported []error
	disp := NewDispatcher(reg, map[registry.PushProvider]Sender{registry.PushFCM: &fakeSender{err: errors.New("push: fcm status 503")}},
		func(err error) { mu.Lock(); reported = append(reported, err); mu.Unlock() })
	disp.Notify(d.ID)
	disp.Notify(d.ID)
	disp.Close()
	mu.Lock()
	defer mu.Unlock()
	if len(reported) != 2 {
		t.Fatalf("each failed background wake is reported once, got %d", len(reported))
	}
	for _, err := range reported {
		if strings.Contains(err.Error(), testFCMToken) {
			t.Fatal("a reported error must never carry the token (TRS-BCK-002)")
		}
	}
	if _, err := reg.PushRoute(ctx, d.ID); err != nil {
		t.Fatal("a transient provider failure must not drop the route")
	}
}
