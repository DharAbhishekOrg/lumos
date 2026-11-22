// Lumos - backend/internal/registry / A throwaway PostgreSQL schema per test.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-BCK-008, TRS-TST-001
// ADRs: -
// Threading: test-only
// Notes: The contract suite runs every repository property against PostgreSQL
// through testdb, which owns the skip-or-fail rule (TRS-AI-008). Each test
// gets its own schema, migrated from empty here and dropped afterwards.
package registry

import (
	"context"
	"testing"

	"github.com/dharabhishekorg/lumos/backend/internal/testdb"
	"github.com/jackc/pgx/v5/pgxpool"
)

// postgresPool returns a pool bound to a fresh, migrated schema.
func postgresPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	pool := testdb.Postgres(t)
	if err := Migrate(context.Background(), pool); err != nil {
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
