// Lumos - backend/internal/api / A device sets or clears its push token, or revokes itself.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-BCK-001, TRS-BCK-006, SRS-IDN-008
// ADRs: -
// Threading: concurrent-safe
// Notes: PUT /v1/push/route {"provider":"fcm"|"apns","token":...} replaces the
// device's route; DELETE /v1/push/route removes it. Both are authenticated
// and act only on the caller's own device. The token is never echoed or
// logged. DELETE /v1/devices/self is SRS-IDN-008's authenticated revocation:
// the credential dies, the bundle leaves the directory and the push route is
// dropped, in the registry's one revoke step.
package api

import (
	"net/http"

	"github.com/dharabhishekorg/lumos/backend/internal/registry"
)

// PushRouteJSON is a device's push route on the wire.
type PushRouteJSON struct {
	Provider string `json:"provider"`
	Token    string `json:"token"`
}

var providersByName = map[string]registry.PushProvider{"fcm": registry.PushFCM, "apns": registry.PushAPNs}

func (h *Registry) setPushRoute(w http.ResponseWriter, r *http.Request, d registry.Device) {
	var req PushRouteJSON
	if !decode(w, r, &req) {
		return
	}
	p, ok := providersByName[req.Provider]
	if !ok {
		malformed(w)
		return
	}
	if err := h.svc.SetPushRoute(r.Context(), d, p, req.Token); err != nil {
		fail(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *Registry) clearPushRoute(w http.ResponseWriter, r *http.Request, d registry.Device) {
	if err := h.svc.ClearPushRoute(r.Context(), d); err != nil {
		fail(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *Registry) revokeSelf(w http.ResponseWriter, r *http.Request, d registry.Device) {
	if err := h.svc.Revoke(r.Context(), d.ID); err != nil {
		fail(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
