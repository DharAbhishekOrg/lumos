// Lumos - backend/internal/registry / Push routes: how to wake a device.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-BCK-006, SRS-IDN-008, TRS-BCK-002
// ADRs: -
// Threading: concurrent-safe
// Notes: A device registers the one token its push service issued it. The
// token is opaque to Lumos and is never logged. It is validated strictly
// because it is later placed in a request: an APNs token goes into the URL
// path of the provider call, so only hex is accepted for APNs, and only the
// URL-safe characters FCM uses for FCM. A device has at most one route;
// setting a new token replaces the old one, which is how token rotation works
// without ever detaching the mailbox, which is keyed on the device.
package registry

import (
	"context"
	"errors"

	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumoserrors"
	"github.com/dharabhishekorg/lumos/protocol/wire"
)

// maxPushTokenLen bounds a stored token. Neither provider documents a
// maximum; APNs tokens are 64 hex characters today and FCM tokens under 200.
// 4 KiB is an order of magnitude above both and still bounds abuse.
const maxPushTokenLen = 4096

func isHex(c byte) bool {
	return c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F'
}

// isFCMChar is the URL-safe set FCM registration tokens are made of.
func isFCMChar(c byte) bool {
	return c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == ':' || c == '-' || c == '_'
}

func pushTokenValid(p PushProvider, token string) bool {
	var ok func(byte) bool
	switch p {
	case PushAPNs:
		ok = isHex
	case PushFCM:
		ok = isFCMChar
	default:
		return false
	}
	if token == "" || len(token) > maxPushTokenLen {
		return false
	}
	for i := 0; i < len(token); i++ {
		if !ok(token[i]) {
			return false
		}
	}
	return true
}

// SetPushRoute stores the authenticated device's push token, replacing any
// previous one.
func (s *Service) SetPushRoute(ctx context.Context, d Device, p PushProvider, token string) error {
	if !pushTokenValid(p, token) {
		return &wire.Error{Code: lumoserrors.ErrPayloadMalformed, Detail: "push route invalid"}
	}
	err := s.repo.SetPushRoute(ctx, d.ID, PushRoute{Provider: p, Token: token, UpdatedAt: s.now()})
	if errors.Is(err, ErrNotFound) {
		return rejected() // revoked between authentication and now
	}
	return err
}

// ClearPushRoute removes the authenticated device's push route.
func (s *Service) ClearPushRoute(ctx context.Context, d Device) error {
	return s.repo.ClearPushRoute(ctx, d.ID, "")
}

// PushRoute looks up how to wake a device; ErrNotFound when it has no route.
func (s *Service) PushRoute(ctx context.Context, id DeviceID) (PushRoute, error) {
	return s.repo.PushRoute(ctx, id)
}

// DropPushToken removes a route whose token the provider reported dead, but
// only if it is still the stored token.
func (s *Service) DropPushToken(ctx context.Context, id DeviceID, token string) error {
	return s.repo.ClearPushRoute(ctx, id, token)
}
