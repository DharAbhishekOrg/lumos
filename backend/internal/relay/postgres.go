// Lumos - backend/internal/relay / Send capabilities on PostgreSQL.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-BCK-008, SRS-RMT-016, TRS-BCK-002
// ADRs: -
// Threading: concurrent-safe
// Notes: Capabilities are durable: if they were lost, every sender would be
// silently dropped (by design a revoked and a missing capability look the
// same), so they live in PostgreSQL, not in the Redis TTL queue
// (TRS-BCK-008). The table is created by registry.Migrate (schema v2), the
// backend database's single forward-only ladder. Only the SHA-256 proof is
// stored, never the capability. RegisterCapability takes a per-mailbox
// transaction-scoped advisory lock so that the ceiling check and the insert
// are one step for concurrent registrations on the same mailbox.
package relay

import (
	"context"

	"github.com/dharabhishekorg/lumos/backend/internal/registry"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// PostgresCapabilities implements Capabilities over the backend database.
type PostgresCapabilities struct {
	pool *pgxpool.Pool
}

// NewPostgresCapabilities wraps a pool whose schema registry.Migrate built.
func NewPostgresCapabilities(pool *pgxpool.Pool) *PostgresCapabilities {
	return &PostgresCapabilities{pool: pool}
}

// RegisterCapability implements Capabilities.
func (p *PostgresCapabilities) RegisterCapability(ctx context.Context, mailbox registry.DeviceID, proof [32]byte, ceiling int) error {
	return pgx.BeginFunc(ctx, p.pool, func(tx pgx.Tx) error {
		// The lock key is the mailbox's own bytes hashed by PostgreSQL; it
		// serialises registrations for this mailbox only.
		if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtextextended(encode($1, 'hex'), 0))`, mailbox[:]); err != nil {
			return err
		}
		var exists bool
		if err := tx.QueryRow(ctx,
			`SELECT EXISTS (SELECT 1 FROM relay_capabilities WHERE mailbox = $1 AND proof = $2)`,
			mailbox[:], proof[:]).Scan(&exists); err != nil {
			return err
		}
		if exists {
			return nil
		}
		var n int
		if err := tx.QueryRow(ctx, `SELECT count(*) FROM relay_capabilities WHERE mailbox = $1`, mailbox[:]).Scan(&n); err != nil {
			return err
		}
		if n >= ceiling {
			return ErrMailboxFull
		}
		_, err := tx.Exec(ctx, `INSERT INTO relay_capabilities (mailbox, proof) VALUES ($1, $2)`, mailbox[:], proof[:])
		return err
	})
}

// RevokeCapability implements Capabilities.
func (p *PostgresCapabilities) RevokeCapability(ctx context.Context, mailbox registry.DeviceID, proof [32]byte) error {
	_, err := p.pool.Exec(ctx, `DELETE FROM relay_capabilities WHERE mailbox = $1 AND proof = $2`, mailbox[:], proof[:])
	return err
}

// CapabilityValid implements Capabilities.
func (p *PostgresCapabilities) CapabilityValid(ctx context.Context, mailbox registry.DeviceID, proof [32]byte) (bool, error) {
	var ok bool
	err := p.pool.QueryRow(ctx,
		`SELECT EXISTS (SELECT 1 FROM relay_capabilities WHERE mailbox = $1 AND proof = $2)`,
		mailbox[:], proof[:]).Scan(&ok)
	return ok, err
}
