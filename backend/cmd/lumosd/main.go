// Lumos - backend/cmd/lumosd / Empty backend shell with a health check only.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-REP-001, TRS-REP-004, TRS-REP-007, TRS-BCK-002
// ADRs: -
// Threading: main-safe
// Notes: WP-01 scope is explicitly "no backend endpoint beyond a health check".
// There is no relay, no registry, no storage and no auth here, and adding one
// belongs to WP-05. The health response deliberately carries build provenance
// and NOTHING about users: TRS-BCK-002 forbids plaintext profile, chat, media or
// key material anywhere in the backend, and a health endpoint is exactly the
// kind of place operational convenience quietly erodes that.
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"time"

	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
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

// NewMux wires the only route this shell serves.
func NewMux() *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", HealthHandler)
	return mux
}

func main() {
	addr := flag.String("addr", "127.0.0.1:8080", "listen address")
	flag.Parse()

	srv := &http.Server{
		Addr:              *addr,
		Handler:           NewMux(),
		ReadHeaderTimeout: 5 * time.Second,
	}
	// The banner is diagnostic output on a stream the process does not own; a
	// closed or full stdout must not stop the server from listening.
	_, _ = fmt.Fprintf(os.Stdout, "lumosd %s (%s) listening on %s\n", version, buildEnv, *addr)
	if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatalf("lumosd: %v", err)
	}
}
