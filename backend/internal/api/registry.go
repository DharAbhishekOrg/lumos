// Lumos - backend/internal/api / The registry's HTTP surface.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-BCK-001, TRS-BCK-002, TRS-BCK-005, TRS-BCK-007
// ADRs: ADR-001
// Threading: concurrent-safe
// Notes: Five routes, JSON with lowercase hex for bytes. Everything but the
// challenge and registration needs "Authorization: Bearer <credential hex>".
// Failures carry only a stable registry code - never a cause, never a key -
// and nothing here logs a request body (TRS-BCK-002). An unknown, revoked or
// bundle-less claim target is one 404, so the directory cannot be used to
// test which routing ids exist. Bodies are capped before decoding.
package api

import (
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"

	"github.com/dharabhishekorg/lumos/backend/internal/registry"
	"github.com/dharabhishekorg/lumos/backend/internal/relay"
	"github.com/dharabhishekorg/lumos/protocol/crypto/x3dh"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumoserrors"
	"github.com/dharabhishekorg/lumos/protocol/wire"
)

// maxBody bounds any registry request: a full prekey upload at the register
// ceiling is ~20 KiB of hex, so 64 KiB leaves room without inviting abuse.
const maxBody = 64 << 10

// Registry serves the registry routes over a service.
type Registry struct {
	svc *registry.Service
}

// NewRegistry builds the handler set.
func NewRegistry(svc *registry.Service) *Registry { return &Registry{svc: svc} }

// Register adds the routes to mux.
func (h *Registry) Register(mux *http.ServeMux) {
	mux.HandleFunc("POST /v1/auth/challenge", h.challenge)
	mux.HandleFunc("POST /v1/devices", h.registerDevice)
	mux.HandleFunc("PUT /v1/prekeys", h.authed(h.publish))
	mux.HandleFunc("GET /v1/prekeys/status", h.authed(h.status))
	mux.HandleFunc("POST /v1/prekeys/{routing_id}/claim", h.authed(h.claim))
	mux.HandleFunc("PUT /v1/push/route", h.authed(h.setPushRoute))
	mux.HandleFunc("DELETE /v1/push/route", h.authed(h.clearPushRoute))
	mux.HandleFunc("DELETE /v1/devices/self", h.authed(h.revokeSelf))
}

type errorBody struct {
	Code string `json:"code"`
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v) // a failed write is the client's disconnect
}

// fail maps an error to its status and stable code; anything unrecognised is
// a 500 that says nothing.
func fail(w http.ResponseWriter, err error) {
	var we *wire.Error
	var rl *relay.RateLimitedError
	switch {
	case errors.As(err, &rl):
		// RFC 9110 Retry-After in seconds: the end of the sender's window.
		w.Header().Set("Retry-After", strconv.FormatInt(rl.RetryAfter, 10))
		writeJSON(w, http.StatusTooManyRequests, errorBody{lumoserrors.ErrRelayRateLimited})
	case errors.As(err, &we) && we.Code == lumoserrors.ErrAuthRejected:
		writeJSON(w, http.StatusUnauthorized, errorBody{we.Code})
	case errors.As(err, &we):
		writeJSON(w, http.StatusBadRequest, errorBody{we.Code})
	case errors.Is(err, registry.ErrNotFound):
		writeJSON(w, http.StatusNotFound, errorBody{"NOT_FOUND"})
	default:
		writeJSON(w, http.StatusInternalServerError, errorBody{"INTERNAL"})
	}
}

func malformed(w http.ResponseWriter) {
	writeJSON(w, http.StatusBadRequest, errorBody{lumoserrors.ErrPayloadMalformed})
}

func decode(w http.ResponseWriter, r *http.Request, v any) bool {
	return decodeCapped(w, r, v, maxBody)
}

func decodeCapped(w http.ResponseWriter, r *http.Request, v any, limit int64) bool {
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, limit))
	dec.DisallowUnknownFields()
	if err := dec.Decode(v); err != nil {
		malformed(w)
		return false
	}
	return true
}

func hex32(s string) ([32]byte, bool) {
	var out [32]byte
	b, err := hex.DecodeString(s)
	if err != nil || len(b) != len(out) {
		return out, false
	}
	copy(out[:], b)
	return out, true
}

type authedHandler func(http.ResponseWriter, *http.Request, registry.Device)

func (h *Registry) authed(next authedHandler) http.HandlerFunc {
	return withDevice(h.svc, next)
}

// withDevice resolves "Authorization: Bearer <credential hex>" to a live
// device before next runs. Every route that acts as a device uses it, so the
// registry and the relay authenticate identically and fail identically.
func withDevice(auth *registry.Service, next authedHandler) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		token, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
		cred, err := hex.DecodeString(token)
		if !ok || err != nil {
			fail(w, &wire.Error{Code: lumoserrors.ErrAuthRejected, Detail: "authentication rejected"})
			return
		}
		d, err := auth.Authenticate(r.Context(), cred)
		if err != nil {
			fail(w, err)
			return
		}
		next(w, r, d)
	}
}

func (h *Registry) challenge(w http.ResponseWriter, _ *http.Request) {
	c, err := h.svc.IssueChallenge()
	if err != nil {
		fail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"challenge": hex.EncodeToString(c[:])})
}

type registerRequest struct {
	Challenge string `json:"challenge"`
	DeviceKey string `json:"device_key"`
	Signature string `json:"signature"`
}

func (h *Registry) registerDevice(w http.ResponseWriter, r *http.Request) {
	var req registerRequest
	if !decode(w, r, &req) {
		return
	}
	ch, ok1 := hex32(req.Challenge)
	dk, ok2 := hex32(req.DeviceKey)
	sig, err := hex.DecodeString(req.Signature)
	if !ok1 || !ok2 || err != nil {
		malformed(w)
		return
	}
	reg, err := h.svc.Register(r.Context(), ch, dk, sig)
	if err != nil {
		fail(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, map[string]string{
		"routing_id": hex.EncodeToString(reg.ID[:]),
		"credential": hex.EncodeToString(reg.Credential[:]),
	})
}

// BundleJSON is the signed half of a prekey bundle on the wire.
type BundleJSON struct {
	IdentityKey   string `json:"identity_key"`
	IdentityDH    string `json:"identity_dh"`
	IdentityDHSig string `json:"identity_dh_sig"`
	SPKID         uint32 `json:"spk_id"`
	SPK           string `json:"spk"`
	SPKSig        string `json:"spk_sig"`
}

// OneTimeJSON is one one-time prekey on the wire.
type OneTimeJSON struct {
	KeyID     uint32 `json:"key_id"`
	PublicKey string `json:"public_key"`
}

type publishRequest struct {
	Bundle  *BundleJSON   `json:"bundle,omitempty"`
	OneTime []OneTimeJSON `json:"one_time,omitempty"`
}

func (b BundleJSON) public() (*x3dh.PublicBundle, bool) {
	var p x3dh.PublicBundle
	var errs [5]error
	p.IdentityEd, errs[0] = hex.DecodeString(b.IdentityKey)
	p.IdentityDH, errs[1] = hex.DecodeString(b.IdentityDH)
	p.IdentityDHSig, errs[2] = hex.DecodeString(b.IdentityDHSig)
	p.SPK, errs[3] = hex.DecodeString(b.SPK)
	p.SPKSig, errs[4] = hex.DecodeString(b.SPKSig)
	p.SPKID = b.SPKID
	return &p, errors.Join(errs[:]...) == nil
}

func (h *Registry) publish(w http.ResponseWriter, r *http.Request, d registry.Device) {
	var req publishRequest
	if !decode(w, r, &req) {
		return
	}
	var up registry.Upload
	if req.Bundle != nil {
		pb, ok := req.Bundle.public()
		if !ok {
			malformed(w)
			return
		}
		up.Bundle = pb
	}
	for _, k := range req.OneTime {
		key, ok := hex32(k.PublicKey)
		if !ok {
			malformed(w)
			return
		}
		up.OneTime = append(up.OneTime, registry.OneTimePrekey{ID: k.KeyID, Key: key})
	}
	if err := h.svc.PublishPrekeys(r.Context(), d, up); err != nil {
		fail(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *Registry) status(w http.ResponseWriter, r *http.Request, d registry.Device) {
	st, err := h.svc.PrekeyStatus(r.Context(), d)
	if err != nil {
		fail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"one_time_remaining": st.OneTimeRemaining,
		"needs_replenish":    st.NeedsReplenish,
	})
}

type claimResponse struct {
	Bundle  BundleJSON   `json:"bundle"`
	OneTime *OneTimeJSON `json:"one_time,omitempty"`
}

func (h *Registry) claim(w http.ResponseWriter, r *http.Request, d registry.Device) {
	raw, err := hex.DecodeString(r.PathValue("routing_id"))
	var target registry.DeviceID
	if err != nil || len(raw) != len(target) {
		malformed(w)
		return
	}
	copy(target[:], raw)
	b, k, err := h.svc.ClaimBundle(r.Context(), d, target)
	if err != nil {
		fail(w, err)
		return
	}
	resp := claimResponse{Bundle: BundleJSON{
		IdentityKey:   hex.EncodeToString(b.IdentityEd[:]),
		IdentityDH:    hex.EncodeToString(b.IdentityDH[:]),
		IdentityDHSig: hex.EncodeToString(b.IdentityDHSig[:]),
		SPKID:         b.SPKID,
		SPK:           hex.EncodeToString(b.SPK[:]),
		SPKSig:        hex.EncodeToString(b.SPKSig[:]),
	}}
	if k != nil {
		resp.OneTime = &OneTimeJSON{KeyID: k.ID, PublicKey: hex.EncodeToString(k.Key[:])}
	}
	writeJSON(w, http.StatusOK, resp)
}
