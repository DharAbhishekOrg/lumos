// Lumos - backend/internal/api / The relay's HTTP surface.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-BCK-001, TRS-BCK-002, TRS-BCK-004, SRS-RMT-002, SRS-RMT-009, SRS-RMT-015, SRS-RMT-016
// ADRs: ADR-001
// Threading: concurrent-safe
// Notes: Every route acts as an authenticated device (withDevice). A send that
// is stored, a retry of one already accepted, and a send on a revoked or
// invented capability all answer the same 202 with the same empty body, so the
// response cannot tell a sender it was blocked (SRS-MAT-003). Payloads travel
// as base64 and are never logged, echoed in an error, or inspected beyond
// their length (TRS-BCK-002). A fetched envelope carries its sender proof - the
// hash of the capability the recipient issued - so the recipient routes it to
// the right conversation without trial decryption; the relay never learns
// which conversation that is.
package api

import (
	"encoding/base64"
	"encoding/hex"
	"net/http"
	"strconv"

	"github.com/dharabhishekorg/lumos/backend/internal/registry"
	"github.com/dharabhishekorg/lumos/backend/internal/relay"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
)

// relayMaxBody bounds a send: the largest text payload the register allows
// (RELAY_TEXT_ENVELOPE_MAX_BYTES.max) grows by a third in base64, plus the
// JSON around it. 128 KiB covers that with room and nothing more.
const relayMaxBody = 128 << 10

// Relay serves the relay routes.
type Relay struct {
	svc  *relay.Service
	auth *registry.Service
}

// NewRelay builds the handler set; auth resolves device credentials.
func NewRelay(svc *relay.Service, auth *registry.Service) *Relay {
	return &Relay{svc: svc, auth: auth}
}

// Register adds the routes to mux.
func (h *Relay) Register(mux *http.ServeMux) {
	mux.HandleFunc("PUT /v1/mailbox/capabilities", withDevice(h.auth, h.allow))
	mux.HandleFunc("DELETE /v1/mailbox/capabilities/{proof}", withDevice(h.auth, h.revoke))
	mux.HandleFunc("POST /v1/envelopes", withDevice(h.auth, h.submit))
	mux.HandleFunc("GET /v1/envelopes", withDevice(h.auth, h.fetch))
	mux.HandleFunc("POST /v1/envelopes/ack", withDevice(h.auth, h.ack))
}

func hexN(s string, n int) ([]byte, bool) {
	b, err := hex.DecodeString(s)
	return b, err == nil && len(b) == n
}

type proofRequest struct {
	Proof string `json:"proof"`
}

func (h *Relay) allow(w http.ResponseWriter, r *http.Request, d registry.Device) {
	var req proofRequest
	if !decode(w, r, &req) {
		return
	}
	p, ok := hex32(req.Proof)
	if !ok {
		malformed(w)
		return
	}
	if err := h.svc.Allow(r.Context(), d, p); err != nil {
		fail(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *Relay) revoke(w http.ResponseWriter, r *http.Request, d registry.Device) {
	p, ok := hex32(r.PathValue("proof"))
	if !ok {
		malformed(w)
		return
	}
	if err := h.svc.Revoke(r.Context(), d, p); err != nil {
		fail(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// SubmitJSON is a send on the wire.
type SubmitJSON struct {
	Recipient  string `json:"recipient"`
	MessageID  string `json:"message_id"`
	Class      int    `json:"class"`
	Capability string `json:"capability"`
	Payload    string `json:"payload"`
}

// parseSubmit turns a wire send into a relay.Send, or reports it malformed.
func parseSubmit(req SubmitJSON) (relay.Send, bool) {
	var in relay.Send
	rcpt, ok1 := hexN(req.Recipient, len(in.Recipient))
	id, ok2 := hexN(req.MessageID, len(in.ID))
	capability, ok3 := hex32(req.Capability)
	payload, err := base64.StdEncoding.DecodeString(req.Payload)
	if !ok1 || !ok2 || !ok3 || err != nil || req.Class < 0 || req.Class > 0xFF {
		return relay.Send{}, false
	}
	copy(in.Recipient[:], rcpt)
	copy(in.ID[:], id)
	in.Class = lumosconstants.MessageClass(req.Class) // #nosec G115 -- range-checked above
	in.Capability = capability
	in.Payload = payload
	return in, true
}

func (h *Relay) submit(w http.ResponseWriter, r *http.Request, d registry.Device) {
	var req SubmitJSON
	if !decodeCapped(w, r, &req, relayMaxBody) {
		return
	}
	in, ok := parseSubmit(req)
	if !ok {
		malformed(w)
		return
	}
	if err := h.svc.Submit(r.Context(), d, in); err != nil {
		fail(w, err)
		return
	}
	w.WriteHeader(http.StatusAccepted)
}

// EnvelopeJSON is one fetched envelope.
type EnvelopeJSON struct {
	MessageID   string `json:"message_id"`
	Class       int    `json:"class"`
	SenderProof string `json:"sender_proof"`
	CreatedAt   int64  `json:"created_at"`
	ExpiresAt   int64  `json:"expires_at"`
	Payload     string `json:"payload"`
}

func (h *Relay) fetch(w http.ResponseWriter, r *http.Request, d registry.Device) {
	limit := relay.FetchMax
	if q := r.URL.Query().Get("limit"); q != "" {
		n, err := strconv.Atoi(q)
		if err != nil || n <= 0 {
			malformed(w)
			return
		}
		limit = n
	}
	envs, err := h.svc.Fetch(r.Context(), d, limit)
	if err != nil {
		fail(w, err)
		return
	}
	out := make([]EnvelopeJSON, 0, len(envs))
	for _, e := range envs {
		out = append(out, EnvelopeJSON{
			MessageID: hex.EncodeToString(e.ID[:]), Class: int(e.Class),
			SenderProof: hex.EncodeToString(e.AuthProof[:]),
			CreatedAt:   e.CreatedAt, ExpiresAt: e.ExpiresAt,
			Payload: base64.StdEncoding.EncodeToString(e.Payload),
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{"envelopes": out})
}

type ackRequest struct {
	MessageIDs []string `json:"message_ids"`
}

func (h *Relay) ack(w http.ResponseWriter, r *http.Request, d registry.Device) {
	var req ackRequest
	if !decode(w, r, &req) {
		return
	}
	ids := make([]relay.MessageID, 0, len(req.MessageIDs))
	for _, s := range req.MessageIDs {
		var id relay.MessageID
		b, ok := hexN(s, len(id))
		if !ok {
			malformed(w)
			return
		}
		copy(id[:], b)
		ids = append(ids, id)
	}
	n, err := h.svc.Ack(r.Context(), d, ids)
	if err != nil {
		fail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]int{"acknowledged": n})
}
