// Lumos - backend/internal/registry / A throwaway PostgreSQL schema per test.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-BCK-008, TRS-TST-001
// ADRs: -
// Threading: test-only
// Notes: The contract suite runs every repository property against PostgreSQL
// when LUMOS_TEST_POSTGRES_DSN names a server. CI sets it AND sets
// LUMOS_REQUIRE_POSTGRES=1, which turns a missing database into a failure
// rather than a skip, so the PostgreSQL half can never silently stop running
// while the suite still reports green (TRS-AI-008). Each test gets its own
// schema, migrated from empty and dropped afterwards, so tests cannot see one
// another's rows.
package registry

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"os"
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"
)

// postgresPool returns a pool bound to a fresh, migrated schema.
func postgresPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	dsn := os.Getenv("LUMOS_TEST_POSTGRES_DSN")
	if dsn == "" {
		if os.Getenv("LUMOS_REQUIRE_POSTGRES") == "1" {
			t.Fatal("LUMOS_REQUIRE_POSTGRES=1 but LUMOS_TEST_POSTGRES_DSN is empty")
		}
		t.Skip("no LUMOS_TEST_POSTGRES_DSN: PostgreSQL contract half not run here")
	}
	ctx := context.Background()
	var suffix [6]byte
	if _, err := rand.Read(suffix[:]); err != nil {
		t.Fatal(err)
	}
	schema := "t_" + hex.EncodeToString(suffix[:])
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
	cfg.MaxConns = 32
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		pool.Close()
		_, _ = admin.Exec(context.Background(), "DROP SCHEMA "+schema+" CASCADE")
		admin.Close()
	})
	if err := Migrate(ctx, pool); err != nil {
		t.Fatal(err)
	}
	return pool
}

func TestMigrationIsIdempotentAndRefusesANewerSchema(t *testing.T) {
	pool := postgresPool(t)
	ctx := context.Background()
	if err := Migrate(ctx, pool); err != nil {
		t.Fatalf("a second migration of an up-to-date schema must be a no-op: %v", err)
	}
	if _, err := pool.Exec(ctx, `UPDATE registry_schema SET version = $1`, schemaVersion+1); err != nil {
		t.Fatal(err)
	}
	if err := Migrate(ctx, pool); err == nil {
		t.Fatal("a database ahead of the binary must be refused, not guessed at")
	}
}
