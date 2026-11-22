// Lumos - backend/cmd/lumosd / The backend process: health check, and the registry when a database is configured.
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
// in-memory registry that forgets every device on restart.
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
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
	"github.com/jackc/pgx/v5/pgxpool"
)

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

// NewMux wires the health check, and the registry routes when reg is non-nil.
func NewMux(reg *api.Registry) *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", HealthHandler)
	if reg != nil {
		reg.Register(mux)
	}
	return mux
}

// openRegistry connects, migrates and builds the registry over PostgreSQL. An
// empty DSN is not an error: it means "no registry", and the caller serves
// health only.
func openRegistry(ctx context.Context, dsn string) (*api.Registry, func(), error) {
	if dsn == "" {
		return nil, func() {}, nil
	}
	pool, err := pgxpool.New(ctx, dsn)
	if err != nil {
		return nil, nil, fmt.Errorf("database config: %w", err)
	}
	if err := pool.Ping(ctx); err != nil {
		pool.Close()
		return nil, nil, fmt.Errorf("database unreachable: %w", err)
	}
	if err := registry.Migrate(ctx, pool); err != nil {
		pool.Close()
		return nil, nil, err
	}
	svc := registry.NewService(registry.NewPostgresRepository(pool),
		func() int64 { return time.Now().Unix() }, rand.Reader)
	return api.NewRegistry(svc), pool.Close, nil
}

func main() {
	addr := flag.String("addr", "127.0.0.1:8080", "listen address")
	flag.Parse()

	startCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	reg, closeDB, err := openRegistry(startCtx, os.Getenv("LUMOS_DATABASE_URL"))
	cancel()
	if err != nil {
		// The error names the stage that failed; pgx never puts the password
		// of a DSN into its error text.
		log.Fatalf("lumosd: %v", err)
	}
	defer closeDB()

	srv := &http.Server{
		Addr:              *addr,
		Handler:           NewMux(reg),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       60 * time.Second,
	}
	mode := "health only (no LUMOS_DATABASE_URL)"
	if reg != nil {
		mode = "health + registry"
	}
	// The banner is diagnostic output on a stream the process does not own; a
	// closed or full stdout must not stop the server from listening.
	_, _ = fmt.Fprintf(os.Stdout, "lumosd %s (%s) listening on %s: %s\n", version, buildEnv, *addr, mode)
	if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatalf("lumosd: %v", err)
	}
}
