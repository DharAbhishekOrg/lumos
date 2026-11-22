// Lumos - backend/internal/registry / The PostgreSQL implementation of Repository.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-BCK-005, TRS-BCK-007, TRS-BCK-008, TRS-CRY-005
// ADRs: ADR-001
// Threading: concurrent-safe
// Notes: TRS-BCK-008 puts durable registry records in PostgreSQL. This adapter
// is held to the SAME contract suite as MemoryRepository (contract_test.go),
// so behaviour cannot drift between the two. The property that matters most,
// TRS-CRY-005's "a one-time prekey is issued once", comes from a single DELETE
// ... RETURNING over a row picked with FOR UPDATE SKIP LOCKED: concurrent
// claimers lock different rows, each row is deleted by exactly one of them, and
// a claimer that finds every remaining row locked by another in-flight claim
// gets the documented signed-prekey fallback rather than a duplicate. Ids ever
// stocked live in their own table, so a consumed id can never be uploaded
// again. Only PUBLIC key material and the SHA-256 of a credential are stored
// (TRS-BCK-002). The schema is forward-only and versioned in-database.
package registry

import (
	"context"
	"errors"
	"fmt"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

// schemaVersion is the newest step of the ladder below.
const schemaVersion = 1

// migrations is the forward-only ladder; index i moves version i to i+1.
var migrations = []string{
	`CREATE TABLE devices (
		id              bytea   PRIMARY KEY CHECK (octet_length(id) = 16),
		device_key      bytea   NOT NULL CHECK (octet_length(device_key) = 32),
		credential_hash bytea   NOT NULL UNIQUE CHECK (octet_length(credential_hash) = 32),
		revoked         boolean NOT NULL DEFAULT false,
		created_at      bigint  NOT NULL
	);
	CREATE TABLE signed_bundles (
		device_id       bytea   PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
		identity_ed     bytea   NOT NULL CHECK (octet_length(identity_ed) = 32),
		identity_dh     bytea   NOT NULL CHECK (octet_length(identity_dh) = 32),
		identity_dh_sig bytea   NOT NULL CHECK (octet_length(identity_dh_sig) = 64),
		spk_id          bigint  NOT NULL,
		spk             bytea   NOT NULL CHECK (octet_length(spk) = 32),
		spk_sig         bytea   NOT NULL CHECK (octet_length(spk_sig) = 64),
		uploaded_at     bigint  NOT NULL
	);
	CREATE TABLE one_time_prekeys (
		device_id  bytea  NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
		key_id     bigint NOT NULL,
		public_key bytea  NOT NULL CHECK (octet_length(public_key) = 32),
		PRIMARY KEY (device_id, key_id)
	);
	CREATE TABLE issued_prekey_ids (
		device_id bytea  NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
		key_id    bigint NOT NULL,
		PRIMARY KEY (device_id, key_id)
	);`,
}

// Migrate brings the schema to schemaVersion, one transaction per step. A
// database ahead of this binary is refused: forward-only never means "guess".
func Migrate(ctx context.Context, pool *pgxpool.Pool) error {
	if _, err := pool.Exec(ctx, `CREATE TABLE IF NOT EXISTS registry_schema (version integer NOT NULL)`); err != nil {
		return fmt.Errorf("registry: schema table: %w", err)
	}
	var current int
	err := pool.QueryRow(ctx, `SELECT version FROM registry_schema`).Scan(&current)
	switch {
	case errors.Is(err, pgx.ErrNoRows):
		if _, err := pool.Exec(ctx, `INSERT INTO registry_schema (version) VALUES (0)`); err != nil {
			return fmt.Errorf("registry: schema row: %w", err)
		}
	case err != nil:
		return fmt.Errorf("registry: schema version: %w", err)
	}
	if current > schemaVersion {
		return fmt.Errorf("registry: database schema %d is newer than this binary's %d", current, schemaVersion)
	}
	for v := current; v < schemaVersion; v++ {
		if err := pgx.BeginFunc(ctx, pool, func(tx pgx.Tx) error {
			if _, err := tx.Exec(ctx, migrations[v]); err != nil {
				return err
			}
			_, err := tx.Exec(ctx, `UPDATE registry_schema SET version = $1`, v+1)
			return err
		}); err != nil {
			return fmt.Errorf("registry: migrating to %d: %w", v+1, err)
		}
	}
	return nil
}

// PostgresRepository stores the registry in PostgreSQL.
type PostgresRepository struct {
	pool *pgxpool.Pool
}

// NewPostgresRepository wraps a migrated pool.
func NewPostgresRepository(pool *pgxpool.Pool) *PostgresRepository {
	return &PostgresRepository{pool: pool}
}

func isUniqueViolation(err error) bool {
	var pgErr *pgconn.PgError
	return errors.As(err, &pgErr) && pgErr.Code == "23505"
}

func to32(b []byte) [32]byte {
	var out [32]byte
	copy(out[:], b)
	return out
}

func to64(b []byte) [64]byte {
	var out [64]byte
	copy(out[:], b)
	return out
}

// CreateDevice implements Repository.
func (p *PostgresRepository) CreateDevice(ctx context.Context, d Device) error {
	_, err := p.pool.Exec(ctx,
		`INSERT INTO devices (id, device_key, credential_hash, revoked, created_at) VALUES ($1, $2, $3, $4, $5)`,
		d.ID[:], d.DeviceKey[:], d.CredentialHash[:], d.Revoked, d.CreatedAt)
	if isUniqueViolation(err) {
		return ErrDuplicate
	}
	return err
}

func (p *PostgresRepository) device(ctx context.Context, where string, arg []byte) (Device, error) {
	var d Device
	var id, key, cred []byte
	err := p.pool.QueryRow(ctx,
		`SELECT id, device_key, credential_hash, revoked, created_at FROM devices WHERE `+where+` = $1`, arg).
		Scan(&id, &key, &cred, &d.Revoked, &d.CreatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return Device{}, ErrNotFound
	}
	if err != nil {
		return Device{}, err
	}
	copy(d.ID[:], id)
	d.DeviceKey = to32(key)
	d.CredentialHash = to32(cred)
	return d, nil
}

// DeviceByCredential implements Repository.
func (p *PostgresRepository) DeviceByCredential(ctx context.Context, h [32]byte) (Device, error) {
	return p.device(ctx, "credential_hash", h[:])
}

// Device implements Repository.
func (p *PostgresRepository) Device(ctx context.Context, id DeviceID) (Device, error) {
	return p.device(ctx, "id", id[:])
}

// RevokeDevice implements Repository.
func (p *PostgresRepository) RevokeDevice(ctx context.Context, id DeviceID) error {
	tag, err := p.pool.Exec(ctx, `UPDATE devices SET revoked = true WHERE id = $1`, id[:])
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

// PutSignedBundle implements Repository.
func (p *PostgresRepository) PutSignedBundle(ctx context.Context, id DeviceID, b SignedBundle) error {
	tag, err := p.pool.Exec(ctx, `
		INSERT INTO signed_bundles (device_id, identity_ed, identity_dh, identity_dh_sig, spk_id, spk, spk_sig, uploaded_at)
		SELECT $1, $2, $3, $4, $5, $6, $7, $8 WHERE EXISTS (SELECT 1 FROM devices WHERE id = $1)
		ON CONFLICT (device_id) DO UPDATE SET
			identity_ed = EXCLUDED.identity_ed, identity_dh = EXCLUDED.identity_dh,
			identity_dh_sig = EXCLUDED.identity_dh_sig, spk_id = EXCLUDED.spk_id,
			spk = EXCLUDED.spk, spk_sig = EXCLUDED.spk_sig, uploaded_at = EXCLUDED.uploaded_at`,
		id[:], b.IdentityEd[:], b.IdentityDH[:], b.IdentityDHSig[:], int64(b.SPKID), b.SPK[:], b.SPKSig[:], b.UploadedAt)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func scanBundle(row pgx.Row) (SignedBundle, error) {
	var b SignedBundle
	var ed, dh, dhSig, spk, spkSig []byte
	var spkID int64
	if err := row.Scan(&ed, &dh, &dhSig, &spkID, &spk, &spkSig, &b.UploadedAt); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return SignedBundle{}, ErrNotFound
		}
		return SignedBundle{}, err
	}
	b.IdentityEd, b.IdentityDH, b.SPK = to32(ed), to32(dh), to32(spk)
	b.IdentityDHSig, b.SPKSig = to64(dhSig), to64(spkSig)
	b.SPKID = uint32(spkID) // #nosec G115 -- written from a uint32 by PutSignedBundle
	return b, nil
}

const bundleColumns = `identity_ed, identity_dh, identity_dh_sig, spk_id, spk, spk_sig, uploaded_at`

// CurrentBundle implements Repository.
func (p *PostgresRepository) CurrentBundle(ctx context.Context, id DeviceID) (SignedBundle, error) {
	return scanBundle(p.pool.QueryRow(ctx,
		`SELECT `+bundleColumns+` FROM signed_bundles WHERE device_id = $1`, id[:]))
}

// AddOneTimePrekeys implements Repository: all of the batch or none of it.
func (p *PostgresRepository) AddOneTimePrekeys(ctx context.Context, id DeviceID, keys []OneTimePrekey, ceiling int) error {
	return pgx.BeginFunc(ctx, p.pool, func(tx pgx.Tx) error {
		// Lock the device row so concurrent uploads for one device serialise
		// on the ceiling check instead of both passing it.
		var exists bool
		err := tx.QueryRow(ctx, `SELECT true FROM devices WHERE id = $1 FOR UPDATE`, id[:]).Scan(&exists)
		if errors.Is(err, pgx.ErrNoRows) {
			return ErrNotFound
		}
		if err != nil {
			return err
		}
		var stock int
		if err := tx.QueryRow(ctx, `SELECT count(*) FROM one_time_prekeys WHERE device_id = $1`, id[:]).Scan(&stock); err != nil {
			return err
		}
		if stock+len(keys) > ceiling {
			return ErrStockCeiling
		}
		for _, k := range keys {
			if _, err := tx.Exec(ctx, `INSERT INTO issued_prekey_ids (device_id, key_id) VALUES ($1, $2)`,
				id[:], int64(k.ID)); err != nil {
				if isUniqueViolation(err) {
					return ErrDuplicate
				}
				return err
			}
			if _, err := tx.Exec(ctx, `INSERT INTO one_time_prekeys (device_id, key_id, public_key) VALUES ($1, $2, $3)`,
				id[:], int64(k.ID), k.Key[:]); err != nil {
				return err
			}
		}
		return nil
	})
}

// ClaimBundle implements Repository.
func (p *PostgresRepository) ClaimBundle(ctx context.Context, id DeviceID) (SignedBundle, *OneTimePrekey, error) {
	var bundle SignedBundle
	var claimed *OneTimePrekey
	err := pgx.BeginFunc(ctx, p.pool, func(tx pgx.Tx) error {
		b, err := scanBundle(tx.QueryRow(ctx, `
			SELECT `+bundleColumns+` FROM signed_bundles s JOIN devices d ON d.id = s.device_id
			WHERE s.device_id = $1 AND NOT d.revoked`, id[:]))
		if err != nil {
			return err
		}
		bundle = b
		var keyID int64
		var key []byte
		err = tx.QueryRow(ctx, `
			DELETE FROM one_time_prekeys WHERE (device_id, key_id) = (
				SELECT device_id, key_id FROM one_time_prekeys WHERE device_id = $1
				ORDER BY key_id LIMIT 1 FOR UPDATE SKIP LOCKED)
			RETURNING key_id, public_key`, id[:]).Scan(&keyID, &key)
		if errors.Is(err, pgx.ErrNoRows) {
			return nil // the documented X3DH fallback (TRS-CRY-005)
		}
		if err != nil {
			return err
		}
		claimed = &OneTimePrekey{ID: uint32(keyID), Key: to32(key)} // #nosec G115 -- written from a uint32
		return nil
	})
	if err != nil {
		return SignedBundle{}, nil, err
	}
	return bundle, claimed, nil
}

// OneTimePrekeyCount implements Repository.
func (p *PostgresRepository) OneTimePrekeyCount(ctx context.Context, id DeviceID) (int, error) {
	if _, err := p.Device(ctx, id); err != nil {
		return 0, err
	}
	var n int
	err := p.pool.QueryRow(ctx, `SELECT count(*) FROM one_time_prekeys WHERE device_id = $1`, id[:]).Scan(&n)
	return n, err
}

// credentialHashes returns every stored credential hash, for the contract
// test that proves the store never holds a raw credential.
func (p *PostgresRepository) credentialHashes(ctx context.Context) ([][32]byte, error) {
	rows, err := p.pool.Query(ctx, `SELECT credential_hash FROM devices`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out [][32]byte
	for rows.Next() {
		var b []byte
		if err := rows.Scan(&b); err != nil {
			return nil, err
		}
		out = append(out, to32(b))
	}
	return out, rows.Err()
}
