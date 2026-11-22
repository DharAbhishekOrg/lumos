// Lumos - backend/cmd/lumosd / The backend process: health check, registry and relay as configured.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-REP-001, TRS-REP-004, TRS-REP-007, TRS-BCK-002, TRS-BCK-001, TRS-BCK-008
// ADRs: -
// Threading: main-safe
// Notes: The health response deliberately carries build provenance and NOTHING
// about users: TRS-BCK-002 forbids plaintext profile, chat, media or key
// material anywhere in the backend, and a health endpoint is exactly the kind
// of place operational convenience quietly erodes that. Since WP-05 S4b the
// registry routes are served too, but ONLY over PostgreSQL (TRS-BCK-008): the
// DSN comes from LUMOS_DATABASE_URL, which in a deployed environment is
// injected from the secret manager (TRS-BCK-012) and never appears in code,
// flags or logs. Without it the process is the health-check shell it always
// was, so a misconfigured deploy fails visibly instead of quietly running an
// in-memory registry that forgets every device on restart. Since WP-05 S5b the
// relay is served too, when LUMOS_REDIS_URL is ALSO set: its envelopes live in
// Redis and its send capabilities in the same PostgreSQL. A Redis URL without
// a database is refused at startup, because the relay cannot authenticate
// anyone without the registry.
package main

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"time"

	"github.com/dharabhishekorg/lumos/backend/internal/api"
	"github.com/dharabhishekorg/lumos/backend/internal/registry"
	"github.com/dharabhishekorg/lumos/backend/internal/relay"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"
)

// redisNamespace prefixes every key the backend writes to Redis.
const redisNamespace = "lumos:"

// Build provenance, injected at link time by the release build (TRS-REP-007).
// The defaults name the state honestly rather than pretending to be a release.
var (
	version   = "0.0.0-dev"
	revision  = "unknown"
	buildEnv  = "development"
	buildTime = "unset"
)

// Health is the health-check payload. Every field is build metadata; no field
// is, or may become, user data.
type Health struct {
	Status        string `json:"status"`
	Version       string `json:"version"`
	Revision      string `json:"revision"`
	Environment   string `json:"environment"`
	BuiltAt       string `json:"built_at"`
	ProtocolMajor int    `json:"protocol_version_major"`
	ProtocolMinor int    `json:"protocol_version_minor"`
	ConstantsHash string `json:"constants_hash"`
}

// CurrentHealth builds the payload from the linked build metadata.
func CurrentHealth() Health {
	return Health{
		Status:        "ok",
		Version:       version,
		Revision:      revision,
		Environment:   buildEnv,
		BuiltAt:       buildTime,
		ProtocolMajor: lumosconstants.ProtocolVersionMajor,
		ProtocolMinor: lumosconstants.ProtocolVersionMinor,
		ConstantsHash: lumosconstants.ConstantsHash,
	}
}

// HealthHandler serves the health check.
func HealthHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.Header().Set("Allow", http.MethodGet)
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	if err := json.NewEncoder(w).Encode(CurrentHealth()); err != nil {
		log.Printf("health encode failed: %v", err)
	}
}

// Backend is the set of route groups this process serves beyond health.
type Backend struct {
	Registry *api.Registry
	Relay    *api.Relay
	close    []func()
}

// Close releases the connections the backend opened.
func (b *Backend) Close() {
	if b == nil {
		return
	}
	for i := len(b.close) - 1; i >= 0; i-- {
		b.close[i]()
	}
}

// NewMux wires the health check and whichever route groups b holds.
func NewMux(b *Backend) *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", HealthHandler)
	if b != nil && b.Registry != nil {
		b.Registry.Register(mux)
	}
	if b != nil && b.Relay != nil {
		b.Relay.Register(mux)
	}
	return mux
}

func unixNow() int64 { return time.Now().Unix() }

// openBackend connects, migrates and builds what the configuration asks for.
// No DSN means health only. A DSN alone means the registry. A DSN and a Redis
// URL mean the registry and the relay. A Redis URL alone is a configuration
// error. Errors name the stage that failed and never the credentials.
func openBackend(ctx context.Context, dsn, redisURL string) (*Backend, error) {
	if dsn == "" {
		if redisURL != "" {
			return nil, errors.New("LUMOS_REDIS_URL is set but LUMOS_DATABASE_URL is not: the relay needs the registry")
		}
		return nil, nil
	}
	b := &Backend{}
	pool, err := pgxpool.New(ctx, dsn)
	if err != nil {
		return nil, fmt.Errorf("database config: %w", err)
	}
	b.close = append(b.close, pool.Close)
	if err := pool.Ping(ctx); err != nil {
		b.Close()
		return nil, fmt.Errorf("database unreachable: %w", err)
	}
	if err := registry.Migrate(ctx, pool); err != nil {
		b.Close()
		return nil, err
	}
	auth := registry.NewService(registry.NewPostgresRepository(pool), unixNow, rand.Reader)
	b.Registry = api.NewRegistry(auth)
	if redisURL == "" {
		return b, nil
	}
	opts, err := redis.ParseURL(redisURL)
	if err != nil {
		b.Close()
		return nil, errors.New("redis config: LUMOS_REDIS_URL is not a valid redis:// or rediss:// URL")
	}
	rdb := redis.NewClient(opts)
	b.close = append(b.close, func() { _ = rdb.Close() })
	if err := rdb.Ping(ctx).Err(); err != nil {
		b.Close()
		return nil, fmt.Errorf("redis unreachable: %w", err)
	}
	mail := relay.NewRedisMailboxes(rdb, redisNamespace)
	svc := relay.NewService(relay.NewPostgresCapabilities(pool), mail, mail, unixNow)
	b.Relay = api.NewRelay(svc, auth)
	return b, nil
}

func main() {
	addr := flag.String("addr", "127.0.0.1:8080", "listen address")
	flag.Parse()

	startCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	backend, err := openBackend(startCtx, os.Getenv("LUMOS_DATABASE_URL"), os.Getenv("LUMOS_REDIS_URL"))
	cancel()
	if err != nil {
		// The error names the stage that failed; neither pgx nor go-redis puts
		// a password into its error text, and a bad Redis URL is reported
		// without echoing it.
		log.Fatalf("lumosd: %v", err)
	}
	defer backend.Close()

	srv := &http.Server{
		Addr:              *addr,
		Handler:           NewMux(backend),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       60 * time.Second,
	}
	mode := "health only (no LUMOS_DATABASE_URL)"
	switch {
	case backend != nil && backend.Relay != nil:
		mode = "health + registry + relay"
	case backend != nil:
		mode = "health + registry (no LUMOS_REDIS_URL)"
	}
	// The banner is diagnostic output on a stream the process does not own; a
	// closed or full stdout must not stop the server from listening.
	_, _ = fmt.Fprintf(os.Stdout, "lumosd %s (%s) listening on %s: %s\n", version, buildEnv, *addr, mode)
	if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatalf("lumosd: %v", err)
	}
}
