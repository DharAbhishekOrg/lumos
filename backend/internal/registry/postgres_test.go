// Lumos - backend/internal/registry / A throwaway PostgreSQL schema per test.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-BCK-008, TRS-TST-001, SRS-DAT-001
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

// TestTheDatabaseHoldsKeysProofsAndRoutesNeverUserContent is SRS-DAT-001's
// schema audit, run against the migrated database itself rather than the
// migration text: no profile, match media, chat or voice-note plaintext may be
// stored centrally. Every column the backend's one schema holds is listed
// here with its type, so any new column fails this test until someone has
// checked it against SRS-DAT-001 and added it on purpose. Message envelopes
// are not here at all: they live in Redis as opaque ciphertext with a TTL
// (TRS-BCK-008, TestTheEnvelopeRecordIsTheTRSRecordAndHoldsNoCapability).
func TestTheDatabaseHoldsKeysProofsAndRoutesNeverUserContent(t *testing.T) {
	pool := postgresPool(t)
	want := map[string]string{
		// A device: random id, PUBLIC device key, SHA-256 of its credential.
		"devices.id": "bytea", "devices.device_key": "bytea", "devices.credential_hash": "bytea",
		"devices.revoked": "boolean", "devices.created_at": "bigint",
		// Its signed bundle: PUBLIC keys and their signatures only.
		"signed_bundles.device_id": "bytea", "signed_bundles.identity_ed": "bytea",
		"signed_bundles.identity_dh": "bytea", "signed_bundles.identity_dh_sig": "bytea",
		"signed_bundles.spk_id": "bigint", "signed_bundles.spk": "bytea", "signed_bundles.spk_sig": "bytea",
		"signed_bundles.uploaded_at": "bigint",
		// One-time prekeys (PUBLIC) and the ids ever stocked, so none is reissued.
		"one_time_prekeys.device_id": "bytea", "one_time_prekeys.key_id": "bigint",
		"one_time_prekeys.public_key": "bytea",
		"issued_prekey_ids.device_id": "bytea", "issued_prekey_ids.key_id": "bigint",
		// Send capabilities, as SHA-256 proofs only.
		"relay_capabilities.mailbox": "bytea", "relay_capabilities.proof": "bytea",
		// The one text column: a push provider's token, needed to wake the
		// device. It names a device to FCM/APNs and carries no user content.
		"push_routes.device_id": "bytea", "push_routes.provider": "smallint",
		"push_routes.token": "text", "push_routes.updated_at": "bigint",
		"registry_schema.version": "integer",
	}
	rows, err := pool.Query(context.Background(),
		`SELECT table_name, column_name, data_type FROM information_schema.columns
		 WHERE table_schema = current_schema()`)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	got := map[string]string{}
	for rows.Next() {
		var table, column, kind string
		if err := rows.Scan(&table, &column, &kind); err != nil {
			t.Fatal(err)
		}
		got[table+"."+column] = kind
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
	for col, kind := range got {
		if want[col] != kind {
			t.Errorf("column %s (%s) is not in the SRS-DAT-001 audit; check it holds no user content, then list it", col, kind)
		}
	}
	for col := range want {
		if _, ok := got[col]; !ok {
			t.Errorf("audited column %s is gone; update the audit with the schema", col)
		}
	}
}
