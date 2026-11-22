// Lumos - backend/internal/registry / Push routes, held to one contract on memory and PostgreSQL.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-BCK-006, SRS-IDN-008, TRS-TST-001
// ADRs: -
// Threading: test-only
// Notes: A token rotation replaces the route and leaves the device (and so its
// mailbox) untouched; a stale "token dead" report cannot erase the token that
// replaced it; revocation drops the route; and only tokens that are safe to
// put in a provider request are accepted.
package registry

import (
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumoserrors"
)

// Fake tokens of the right shape. The APNs one is built at run time: a
// 64-character hex literal next to the word "token" is exactly what the
// repository's secret scanner is right to flag.
var apnsToken = strings.Repeat("0a1b", 16)

const fcmToken = "dXk1:APA91bH-example_token-value"

func TestARotatedPushTokenReplacesTheRouteAndKeepsTheDevice(t *testing.T) {
	eachRepo(t, func(t *testing.T, f *fixture) {
		ctx := context.Background()
		reg, _ := f.register(t)
		d := f.device(t, reg)
		if _, err := f.svc.PushRoute(ctx, d.ID); !errors.Is(err, ErrNotFound) {
			t.Fatalf("a new device has no push route, got %v", err)
		}
		if err := f.svc.SetPushRoute(ctx, d, PushFCM, fcmToken); err != nil {
			t.Fatal(err)
		}
		f.now += 60
		if err := f.svc.SetPushRoute(ctx, d, PushAPNs, apnsToken); err != nil {
			t.Fatal(err)
		}
		r, err := f.svc.PushRoute(ctx, d.ID)
		if err != nil || r.Provider != PushAPNs || r.Token != apnsToken || r.UpdatedAt != f.now {
			t.Fatalf("the newest token is the route: %+v %v", r, err)
		}
		if again := f.device(t, reg); again.ID != d.ID {
			t.Fatal("rotating a token must not change the device's routing id (its mailbox)")
		}
		// The provider reports the OLD token dead after the rotation.
		if err := f.svc.DropPushToken(ctx, d.ID, fcmToken); err != nil {
			t.Fatal(err)
		}
		if r, err := f.svc.PushRoute(ctx, d.ID); err != nil || r.Token != apnsToken {
			t.Fatalf("a stale dead-token report must not erase the current token: %+v %v", r, err)
		}
		if err := f.svc.DropPushToken(ctx, d.ID, apnsToken); err != nil {
			t.Fatal(err)
		}
		if _, err := f.svc.PushRoute(ctx, d.ID); !errors.Is(err, ErrNotFound) {
			t.Fatalf("the current token reported dead is removed, got %v", err)
		}
	})
}

func TestClearingThePushRouteStopsWakes(t *testing.T) {
	eachRepo(t, func(t *testing.T, f *fixture) {
		ctx := context.Background()
		reg, _ := f.register(t)
		d := f.device(t, reg)
		if err := f.svc.SetPushRoute(ctx, d, PushFCM, fcmToken); err != nil {
			t.Fatal(err)
		}
		if err := f.svc.ClearPushRoute(ctx, d); err != nil {
			t.Fatal(err)
		}
		if _, err := f.svc.PushRoute(ctx, d.ID); !errors.Is(err, ErrNotFound) {
			t.Fatalf("a cleared route is gone, got %v", err)
		}
	})
}

func TestRevokingADeviceDropsItsPushRoute(t *testing.T) {
	eachRepo(t, func(t *testing.T, f *fixture) {
		ctx := context.Background()
		reg, _ := f.register(t)
		d := f.device(t, reg)
		if err := f.svc.SetPushRoute(ctx, d, PushAPNs, apnsToken); err != nil {
			t.Fatal(err)
		}
		if err := f.svc.Revoke(ctx, d.ID); err != nil {
			t.Fatal(err)
		}
		if _, err := f.svc.PushRoute(ctx, d.ID); !errors.Is(err, ErrNotFound) {
			t.Fatalf("revocation drops the push route (SRS-IDN-008), got %v", err)
		}
		if got := code(f.svc.SetPushRoute(ctx, d, PushAPNs, apnsToken)); got != lumoserrors.ErrAuthRejected {
			t.Fatalf("a revoked device cannot set a route again: got %s", got)
		}
	})
}

func TestOnlyTokensSafeForAProviderRequestAreAccepted(t *testing.T) {
	eachRepo(t, func(t *testing.T, f *fixture) {
		ctx := context.Background()
		reg, _ := f.register(t)
		d := f.device(t, reg)
		bad := []struct {
			p     PushProvider
			token string
		}{
			{PushAPNs, ""},
			{PushAPNs, "zz" + apnsToken[2:]},                // not hex
			{PushAPNs, apnsToken[:10] + "/../" + apnsToken}, // path traversal into the URL
			{PushFCM, "abc def"},                            // space
			{PushFCM, "abc\r\nX-Injected: 1"},               // header injection
			{PushFCM, strings.Repeat("a", maxPushTokenLen+1)},
			{0, fcmToken},
			{3, fcmToken},
		}
		for _, b := range bad {
			if got := code(f.svc.SetPushRoute(ctx, d, b.p, b.token)); got != lumoserrors.ErrPayloadMalformed {
				t.Errorf("provider %d token %.20q: want %s, got %s", b.p, b.token, lumoserrors.ErrPayloadMalformed, got)
			}
		}
		if _, err := f.svc.PushRoute(ctx, d.ID); !errors.Is(err, ErrNotFound) {
			t.Fatal("no rejected token may be stored")
		}
	})
}
