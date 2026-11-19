// Lumos - backend/cmd/lumosd / Tests for the health-check shell.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-REP-007, TRS-BCK-002
// ADRs: -
// Threading: any
// Notes: TestHealthCarriesNoUserData is the one that matters. A health endpoint
// is where operational convenience quietly erodes a privacy invariant, so the
// payload is asserted to be build metadata and nothing else - by field name, so
// adding a user-shaped field fails rather than merely being noticed in review.
package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
)

func TestHealthEndpointRespondsOK(t *testing.T) {
	rec := httptest.NewRecorder()
	NewMux().ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/healthz", nil))

	if rec.Code != http.StatusOK {
		t.Fatalf("status %d, want 200", rec.Code)
	}
	if ct := rec.Header().Get("Content-Type"); ct != "application/json" {
		t.Errorf("content type %q", ct)
	}

	var h Health
	if err := json.Unmarshal(rec.Body.Bytes(), &h); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if h.Status != "ok" {
		t.Errorf("status %q", h.Status)
	}
	if h.ConstantsHash != lumosconstants.ConstantsHash {
		t.Errorf("constants hash %q does not match the build", h.ConstantsHash)
	}
	if h.ProtocolMajor != lumosconstants.ProtocolVersionMajor {
		t.Errorf("protocol major %d", h.ProtocolMajor)
	}
}

// TestHealthCarriesNoUserData enforces TRS-BCK-002 at the only endpoint that
// exists. The field set is closed: a new field must be added here deliberately.
func TestHealthCarriesNoUserData(t *testing.T) {
	rec := httptest.NewRecorder()
	NewMux().ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/healthz", nil))

	var raw map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &raw); err != nil {
		t.Fatalf("decode: %v", err)
	}

	allowed := map[string]bool{
		"status": true, "version": true, "revision": true, "environment": true,
		"built_at": true, "protocol_version_major": true,
		"protocol_version_minor": true, "constants_hash": true,
	}
	for k := range raw {
		if !allowed[k] {
			t.Errorf("health payload carries unexpected field %q; the backend must never expose user data (TRS-BCK-002)", k)
		}
	}

	// A blunt guard against the obvious shapes drifting in later.
	body := strings.ToLower(rec.Body.String())
	for _, forbidden := range []string{"alias", "profile", "email", "phone", "token", "session", "user"} {
		if strings.Contains(body, forbidden) {
			t.Errorf("health payload mentions %q", forbidden)
		}
	}
}

// TestReleaseProvenanceFieldsExist covers TRS-REP-007: every artefact reports
// its version, protocol version, constants-key set and source revision.
func TestReleaseProvenanceFieldsExist(t *testing.T) {
	h := CurrentHealth()
	if h.Version == "" || h.Revision == "" || h.Environment == "" || h.BuiltAt == "" {
		t.Errorf("build provenance is incomplete: %+v", h)
	}
	if h.ConstantsHash == "" {
		t.Error("constants-key set version is missing")
	}
}

func TestNonGetIsRejected(t *testing.T) {
	rec := httptest.NewRecorder()
	NewMux().ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/healthz", nil))
	if rec.Code != http.StatusMethodNotAllowed {
		t.Errorf("status %d, want 405", rec.Code)
	}
}

// TestOnlyHealthIsServed pins the WP-01 scope boundary: no endpoint beyond the
// health check exists, and adding one is a scope change, not a detail.
func TestOnlyHealthIsServed(t *testing.T) {
	for _, path := range []string{"/", "/relay", "/api", "/config", "/billing"} {
		rec := httptest.NewRecorder()
		NewMux().ServeHTTP(rec, httptest.NewRequest(http.MethodGet, path, nil))
		if rec.Code != http.StatusNotFound {
			t.Errorf("%s returned %d; WP-01 permits no endpoint beyond the health check", path, rec.Code)
		}
	}
}
