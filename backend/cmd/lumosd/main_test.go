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
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
	"github.com/jackc/pgx/v5/pgxpool"
)

func TestHealthEndpointRespondsOK(t *testing.T) {
	rec := httptest.NewRecorder()
	NewMux(nil).ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/healthz", nil))

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
	NewMux(nil).ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/healthz", nil))

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
	NewMux(nil).ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/healthz", nil))
	if rec.Code != http.StatusMethodNotAllowed {
		t.Errorf("status %d, want 405", rec.Code)
	}
}

// TestOnlyHealthIsServed pins the boundary that holds without a database: the
// health check and nothing else, registry routes included.
func TestOnlyHealthIsServed(t *testing.T) {
	for _, path := range []string{"/", "/relay", "/api", "/config", "/billing", "/v1/prekeys/status"} {
		rec := httptest.NewRecorder()
		NewMux(nil).ServeHTTP(rec, httptest.NewRequest(http.MethodGet, path, nil))
		if rec.Code != http.StatusNotFound {
			t.Errorf("%s returned %d; without a database only the health check exists", path, rec.Code)
		}
	}
	rec := httptest.NewRecorder()
	NewMux(nil).ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/v1/auth/challenge", nil))
	if rec.Code != http.StatusNotFound {
		t.Errorf("registry route served without a database: %d", rec.Code)
	}
}

func TestNoDatabaseURLMeansNoRegistry(t *testing.T) {
	reg, closeDB, err := openRegistry(context.Background(), "")
	if err != nil || reg != nil {
		t.Fatalf("an empty DSN must mean 'health only', got reg=%v err=%v", reg, err)
	}
	closeDB()
}

func TestAnUnreachableDatabaseFailsStartup(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, _, err := openRegistry(ctx, "postgres://lumos:not-a-real-secret@127.0.0.1:1/none?sslmode=disable&connect_timeout=2")
	if err == nil {
		t.Fatal("a database that cannot be reached must stop startup, not degrade silently")
	}
	if strings.Contains(err.Error(), "not-a-real-secret") {
		t.Fatal("a startup error must never echo the DSN's password")
	}
}

func TestWithADatabaseTheRegistryRoutesAreServed(t *testing.T) {
	dsn := os.Getenv("LUMOS_TEST_POSTGRES_DSN")
	if dsn == "" {
		if os.Getenv("LUMOS_REQUIRE_POSTGRES") == "1" {
			t.Fatal("LUMOS_REQUIRE_POSTGRES=1 but LUMOS_TEST_POSTGRES_DSN is empty")
		}
		t.Skip("no LUMOS_TEST_POSTGRES_DSN")
	}
	ctx := context.Background()
	admin, err := pgxpool.New(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer admin.Close()
	schema := fmt.Sprintf("lumosd_%d", time.Now().UnixNano())
	if _, err := admin.Exec(ctx, "CREATE SCHEMA "+schema); err != nil {
		t.Fatal(err)
	}
	defer func() { _, _ = admin.Exec(ctx, "DROP SCHEMA "+schema+" CASCADE") }()

	reg, closeDB, err := openRegistry(ctx, dsn+" search_path="+schema)
	if err != nil || reg == nil {
		t.Fatalf("openRegistry: %v", err)
	}
	defer closeDB()
	rec := httptest.NewRecorder()
	NewMux(reg).ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/v1/auth/challenge", nil))
	if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), "challenge") {
		t.Fatalf("challenge over PostgreSQL: %d %s", rec.Code, rec.Body.String())
	}
	rec = httptest.NewRecorder()
	NewMux(reg).ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/healthz", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("health must still be served alongside the registry: %d", rec.Code)
	}
}
