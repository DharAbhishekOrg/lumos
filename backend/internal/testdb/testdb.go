// Lumos - backend/internal/testdb / Throwaway PostgreSQL schemas and Redis namespaces for tests.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-BCK-008, TRS-TST-001
// ADRs: -
// Threading: test-only
// Notes: Imported only by _test files. The contract suites run every storage
// property against real servers when LUMOS_TEST_POSTGRES_DSN and
// LUMOS_TEST_REDIS_ADDR name them. CI sets both AND sets LUMOS_REQUIRE_POSTGRES=1
// and LUMOS_REQUIRE_REDIS=1, which turn a missing server into a failure rather
// than a skip, so a server half can never silently stop running while the
// suite still reports green (TRS-AI-008). Each test gets its own PostgreSQL
// schema and its own Redis key namespace, removed afterwards, so tests cannot
// see one another's data.
package testdb

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"os"
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"
)

// poolConns lets the concurrency tests run their goroutines against real
// connection contention rather than queueing on a tiny pool.
const poolConns = 32

func suffix(t *testing.T) string {
	t.Helper()
	var b [6]byte
	if _, err := rand.Read(b[:]); err != nil {
		t.Fatal(err)
	}
	return hex.EncodeToString(b[:])
}

func need(t *testing.T, env, require string) string {
	t.Helper()
	v := os.Getenv(env)
	if v == "" {
		if os.Getenv(require) == "1" {
			t.Fatalf("%s=1 but %s is empty", require, env)
		}
		t.Skipf("no %s: this server half is not run here", env)
	}
	return v
}

// Postgres returns a pool bound to a fresh, empty schema; the caller migrates.
func Postgres(t *testing.T) *pgxpool.Pool {
	t.Helper()
	dsn := need(t, "LUMOS_TEST_POSTGRES_DSN", "LUMOS_REQUIRE_POSTGRES")
	ctx := context.Background()
	schema := "t_" + suffix(t)
	admin, err := pgxpool.New(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := admin.Exec(ctx, "CREATE SCHEMA "+schema); err != nil {
		admin.Close()
		t.Fatal(err)
	}
	cfg, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		t.Fatal(err)
	}
	cfg.ConnConfig.RuntimeParams["search_path"] = schema
	cfg.MaxConns = poolConns
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		pool.Close()
		_, _ = admin.Exec(context.Background(), "DROP SCHEMA "+schema+" CASCADE")
		admin.Close()
	})
	return pool
}

// Redis returns a client and a key namespace no other test uses; every key
// under the namespace is deleted when the test ends.
func Redis(t *testing.T) (*redis.Client, string) {
	t.Helper()
	addr := need(t, "LUMOS_TEST_REDIS_ADDR", "LUMOS_REQUIRE_REDIS")
	client := redis.NewClient(&redis.Options{Addr: addr, PoolSize: poolConns})
	ctx := context.Background()
	if err := client.Ping(ctx).Err(); err != nil {
		_ = client.Close()
		t.Fatalf("redis at %s: %v", addr, err)
	}
	ns := "lumos:test:" + suffix(t) + ":"
	t.Cleanup(func() {
		ctx := context.Background()
		iter := client.Scan(ctx, 0, ns+"*", 0).Iterator()
		for iter.Next(ctx) {
			_ = client.Del(ctx, iter.Val()).Err()
		}
		_ = client.Close()
	})
	return client, ns
}
