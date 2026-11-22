// Lumos - backend/cmd/lumosd / Tests for what the process serves under each configuration.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-REP-007, TRS-BCK-002, TRS-BCK-001, TRS-BCK-008
// ADRs: -
// Threading: any
// Notes: TestHealthCarriesNoUserData is the one that matters. A health endpoint
// is where operational convenience quietly erodes a privacy invariant, so the
// payload is asserted to be build metadata and nothing else - by field name, so
// adding a user-shaped field fails rather than merely being noticed in review.
package main

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/dharabhishekorg/lumos/backend/internal/push"
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
	b, err := openBackend(context.Background(), Config{})
	if err != nil || b != nil {
		t.Fatalf("an empty DSN must mean 'health only', got backend=%v err=%v", b, err)
	}
	b.Close() // a nil backend closes as a no-op
}

func TestARedisURLWithoutADatabaseIsRefused(t *testing.T) {
	_, err := openBackend(context.Background(), Config{RedisURL: "redis://:not-a-real-secret@127.0.0.1:1/0"})
	if err == nil {
		t.Fatal("a relay without the registry cannot authenticate anyone; startup must stop")
	}
	if strings.Contains(err.Error(), "not-a-real-secret") {
		t.Fatal("a startup error must never echo a credential")
	}
}

func TestAnUnreachableDatabaseFailsStartup(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, err := openBackend(ctx, Config{DatabaseURL: "postgres://lumos:not-a-real-secret@127.0.0.1:1/none?sslmode=disable&connect_timeout=2"})
	if err == nil {
		t.Fatal("a database that cannot be reached must stop startup, not degrade silently")
	}
	if strings.Contains(err.Error(), "not-a-real-secret") {
		t.Fatal("a startup error must never echo the DSN's password")
	}
}

// freshSchemaDSN returns the test DSN bound to a new schema dropped at cleanup.
func freshSchemaDSN(t *testing.T) string {
	t.Helper()
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
	schema := fmt.Sprintf("lumosd_%d", time.Now().UnixNano())
	if _, err := admin.Exec(ctx, "CREATE SCHEMA "+schema); err != nil {
		admin.Close()
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_, _ = admin.Exec(context.Background(), "DROP SCHEMA "+schema+" CASCADE")
		admin.Close()
	})
	return dsn + " search_path=" + schema
}

func serve(b *Backend, method, path string) *httptest.ResponseRecorder {
	rec := httptest.NewRecorder()
	NewMux(b).ServeHTTP(rec, httptest.NewRequest(method, path, nil))
	return rec
}

func TestWithADatabaseTheRegistryRoutesAreServed(t *testing.T) {
	b, err := openBackend(context.Background(), Config{DatabaseURL: freshSchemaDSN(t)})
	if err != nil || b == nil || b.Registry == nil {
		t.Fatalf("openBackend: %v", err)
	}
	defer b.Close()
	if b.Relay != nil {
		t.Fatal("without LUMOS_REDIS_URL the relay must not be served")
	}
	if rec := serve(b, http.MethodPost, "/v1/auth/challenge"); rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), "challenge") {
		t.Fatalf("challenge over PostgreSQL: %d %s", rec.Code, rec.Body.String())
	}
	if rec := serve(b, http.MethodGet, "/v1/envelopes"); rec.Code != http.StatusNotFound {
		t.Fatalf("relay route served without Redis: %d", rec.Code)
	}
	if rec := serve(b, http.MethodGet, "/healthz"); rec.Code != http.StatusOK {
		t.Fatalf("health must still be served alongside the registry: %d", rec.Code)
	}
}

func TestWithADatabaseAndRedisTheRelayIsServed(t *testing.T) {
	dsn := freshSchemaDSN(t)
	addr := os.Getenv("LUMOS_TEST_REDIS_ADDR")
	if addr == "" {
		if os.Getenv("LUMOS_REQUIRE_REDIS") == "1" {
			t.Fatal("LUMOS_REQUIRE_REDIS=1 but LUMOS_TEST_REDIS_ADDR is empty")
		}
		t.Skip("no LUMOS_TEST_REDIS_ADDR")
	}
	b, err := openBackend(context.Background(), Config{DatabaseURL: dsn, RedisURL: "redis://" + addr + "/0"})
	if err != nil || b == nil || b.Relay == nil {
		t.Fatalf("openBackend: %v", err)
	}
	defer b.Close()
	// Authentication runs first, so an anonymous fetch proves the route exists
	// and is guarded without touching any mailbox.
	if rec := serve(b, http.MethodGet, "/v1/envelopes"); rec.Code != http.StatusUnauthorized {
		t.Fatalf("an anonymous fetch must be refused by the relay, got %d", rec.Code)
	}
}

func TestAnUnreachableRedisFailsStartup(t *testing.T) {
	dsn := freshSchemaDSN(t)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, err := openBackend(ctx, Config{DatabaseURL: dsn, RedisURL: "redis://:not-a-real-secret@127.0.0.1:1/0?dial_timeout=2s"})
	if err == nil {
		t.Fatal("a Redis that cannot be reached must stop startup")
	}
	if strings.Contains(err.Error(), "not-a-real-secret") {
		t.Fatal("a startup error must never echo the Redis password")
	}
}

// redisAddrOrSkip returns the test Redis address, or skips/fails per the
// LUMOS_REQUIRE_REDIS rule.
func redisAddrOrSkip(t *testing.T) string {
	t.Helper()
	addr := os.Getenv("LUMOS_TEST_REDIS_ADDR")
	if addr == "" {
		if os.Getenv("LUMOS_REQUIRE_REDIS") == "1" {
			t.Fatal("LUMOS_REQUIRE_REDIS=1 but LUMOS_TEST_REDIS_ADDR is empty")
		}
		t.Skip("no LUMOS_TEST_REDIS_ADDR")
	}
	return addr
}

func env(m map[string]string) func(string) string { return func(k string) string { return m[k] } }

func testP8File(t *testing.T) string {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	der, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "AuthKey_TEST.p8")
	if err := os.WriteFile(path, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestAPNsIsConfiguredWhollyOrNotAtAll(t *testing.T) {
	cfg, err := configFromEnv(env(map[string]string{"LUMOS_APNS_KEY_ID": "K"}))
	if err == nil || cfg.APNs != nil {
		t.Fatal("a partial APNs configuration must stop startup")
	}
	path := testP8File(t)
	cfg, err = configFromEnv(env(map[string]string{
		"LUMOS_APNS_KEY_FILE": path, "LUMOS_APNS_KEY_ID": "KEY1234567",
		"LUMOS_APNS_TEAM_ID": "TEAM123456", "LUMOS_APNS_TOPIC": "com.lumos.app",
	}))
	if err != nil || cfg.APNs == nil || cfg.APNs.Production || cfg.APNs.Topic != "com.lumos.app" {
		t.Fatalf("a complete APNs configuration is read, sandbox by default: %+v %v", cfg.APNs, err)
	}
	_, err = configFromEnv(env(map[string]string{
		"LUMOS_APNS_KEY_FILE": path + ".missing", "LUMOS_APNS_KEY_ID": "K",
		"LUMOS_APNS_TEAM_ID": "T", "LUMOS_APNS_TOPIC": "x",
	}))
	if err == nil || strings.Contains(err.Error(), path) {
		t.Fatalf("an unreadable key file stops startup without naming its path: %v", err)
	}
}

func TestPushWithoutTheRelayIsRefused(t *testing.T) {
	if _, err := openBackend(context.Background(), Config{FCMProject: "lumos-staging-515413"}); err == nil {
		t.Fatal("push without a relay has nothing to wake anyone for; startup must stop")
	}
	if _, err := openBackend(context.Background(), Config{DatabaseURL: freshSchemaDSN(t), FCMProject: "lumos-staging-515413"}); err == nil {
		t.Fatal("push without Redis has no relay; startup must stop")
	}
}

func TestABadAPNsKeyStopsStartup(t *testing.T) {
	bad := &push.APNsConfig{KeyPEM: []byte("not a key"), KeyID: "K", TeamID: "T", Topic: "x"}
	if _, err := openBackend(context.Background(), Config{APNs: bad}); err == nil {
		t.Fatal("an unusable APNs key must stop startup, not fail at the first message")
	}
}

func TestWithPushConfiguredTheRelayWakesThroughIt(t *testing.T) {
	dsn := freshSchemaDSN(t)
	addr := redisAddrOrSkip(t)
	cfg, err := configFromEnv(env(map[string]string{
		"LUMOS_APNS_KEY_FILE": testP8File(t), "LUMOS_APNS_KEY_ID": "KEY1234567",
		"LUMOS_APNS_TEAM_ID": "TEAM123456", "LUMOS_APNS_TOPIC": "com.lumos.app",
	}))
	if err != nil {
		t.Fatal(err)
	}
	cfg.DatabaseURL, cfg.RedisURL, cfg.FCMProject = dsn, "redis://"+addr+"/0", "lumos-staging-515413"
	b, err := openBackend(context.Background(), cfg)
	if err != nil || b.Relay == nil {
		t.Fatalf("openBackend: %v", err)
	}
	defer b.Close()
	if strings.Join(b.Push, ",") != "fcm,apns" {
		t.Fatalf("both providers must be wired, got %v", b.Push)
	}
	if rec := serve(b, http.MethodPut, "/v1/push/route"); rec.Code != http.StatusUnauthorized {
		t.Fatalf("the push-route route is served and guarded, got %d", rec.Code)
	}
}
