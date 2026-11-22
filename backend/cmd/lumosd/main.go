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
// anyone without the registry. Since WP-05 S6 push providers wake the
// recipients of relayed messages: FCM when LUMOS_FCM_PROJECT names the
// Firebase project (authenticated by the runtime's service account, so no key
// file exists), and APNs when LUMOS_APNS_KEY_FILE, _KEY_ID, _TEAM_ID and
// _TOPIC are all set (the key file is mounted from Secret Manager).
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
	"github.com/dharabhishekorg/lumos/backend/internal/push"
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
	Push     []string // providers that wake devices, for the banner
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

// Config is what the environment asks this process to serve.
type Config struct {
	DatabaseURL string
	RedisURL    string
	FCMProject  string           // Firebase project id; FCM authenticates by workload identity
	APNs        *push.APNsConfig // nil: no APNs in this environment
}

// apnsEnv are the variables that configure APNs; all or none.
var apnsEnv = []string{"LUMOS_APNS_KEY_FILE", "LUMOS_APNS_KEY_ID", "LUMOS_APNS_TEAM_ID", "LUMOS_APNS_TOPIC"}

// configFromEnv reads the configuration. The APNs key is read from the file
// the deployment mounts from Secret Manager; its contents never appear in an
// error.
func configFromEnv(getenv func(string) string) (Config, error) {
	cfg := Config{
		DatabaseURL: getenv("LUMOS_DATABASE_URL"),
		RedisURL:    getenv("LUMOS_REDIS_URL"),
		FCMProject:  getenv("LUMOS_FCM_PROJECT"),
	}
	set := 0
	for _, k := range apnsEnv {
		if getenv(k) != "" {
			set++
		}
	}
	switch {
	case set == 0:
		return cfg, nil
	case set < len(apnsEnv):
		return Config{}, fmt.Errorf("APNs needs all of %v or none of them", apnsEnv)
	}
	key, err := os.ReadFile(getenv("LUMOS_APNS_KEY_FILE"))
	if err != nil {
		return Config{}, errors.New("APNs key file could not be read")
	}
	cfg.APNs = &push.APNsConfig{
		KeyPEM: key, KeyID: getenv("LUMOS_APNS_KEY_ID"), TeamID: getenv("LUMOS_APNS_TEAM_ID"),
		Topic: getenv("LUMOS_APNS_TOPIC"), Production: getenv("LUMOS_APNS_PRODUCTION") == "true",
	}
	return cfg, nil
}

// providerTimeout bounds one call to a push provider or the metadata server.
const providerTimeout = 10 * time.Second

// openPush builds the providers the configuration names; nil when none.
func openPush(cfg Config) (map[registry.PushProvider]push.Sender, []string, error) {
	senders := map[registry.PushProvider]push.Sender{}
	var names []string
	client := &http.Client{Timeout: providerTimeout}
	if cfg.FCMProject != "" {
		senders[registry.PushFCM] = push.NewFCM(cfg.FCMProject, push.NewMetadataTokenSource(client), client)
		names = append(names, "fcm")
	}
	if cfg.APNs != nil {
		a, err := push.NewAPNs(*cfg.APNs, client)
		if err != nil {
			return nil, nil, err
		}
		senders[registry.PushAPNs] = a
		names = append(names, "apns")
	}
	if len(senders) == 0 {
		return nil, nil, nil
	}
	return senders, names, nil
}

// openBackend connects, migrates and builds what the configuration asks for.
// No DSN means health only. A DSN alone means the registry. A DSN and a Redis
// URL mean the registry and the relay, and push providers, if configured,
// wake recipients of relayed messages. A Redis URL without a DSN, or push
// without the relay, is a configuration error. Errors name the stage that
// failed and never the credentials.
func openBackend(ctx context.Context, cfg Config) (*Backend, error) {
	senders, names, err := openPush(cfg)
	if err != nil {
		return nil, err
	}
	if cfg.DatabaseURL == "" {
		if cfg.RedisURL != "" {
			return nil, errors.New("LUMOS_REDIS_URL is set but LUMOS_DATABASE_URL is not: the relay needs the registry")
		}
		if senders != nil {
			return nil, errors.New("push is configured but the relay is not: there is nothing to wake anyone for")
		}
		return nil, nil
	}
	b := &Backend{}
	pool, err := pgxpool.New(ctx, cfg.DatabaseURL)
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
	if cfg.RedisURL == "" {
		if senders != nil {
			b.Close()
			return nil, errors.New("push is configured but LUMOS_REDIS_URL is not: there is no relay to wake anyone for")
		}
		return b, nil
	}
	opts, err := redis.ParseURL(cfg.RedisURL)
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
	if senders != nil {
		// Push errors carry provider names and HTTP statuses only.
		disp := push.NewDispatcher(auth, senders, func(err error) { log.Printf("lumosd: %v", err) })
		svc.WakeWith(disp)
		b.close = append(b.close, disp.Close)
		b.Push = names
	}
	b.Relay = api.NewRelay(svc, auth)
	return b, nil
}

func main() {
	addr := flag.String("addr", "127.0.0.1:8080", "listen address")
	flag.Parse()

	cfg, err := configFromEnv(os.Getenv)
	if err != nil {
		log.Fatalf("lumosd: %v", err)
	}
	startCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	backend, err := openBackend(startCtx, cfg)
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
	case backend != nil && backend.Relay != nil && len(backend.Push) > 0:
		mode = fmt.Sprintf("health + registry + relay + push %v", backend.Push)
	case backend != nil && backend.Relay != nil:
		mode = "health + registry + relay (no push provider configured)"
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
