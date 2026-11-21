// Lumos - backend/internal/registry / The storage contract and its in-memory implementation.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-BCK-005, TRS-BCK-007, TRS-CRY-005
// ADRs: ADR-001
// Threading: concurrent-safe
// Notes: Every store the registry service uses is behind Repository, so the
// PostgreSQL adapter TRS-BCK-008 requires (WP-05 S4b) is a second
// implementation held to the same contract tests, not a rewrite. The one
// property no implementation may weaken is ClaimBundle's: a one-time prekey is
// removed in the same atomic step that hands it out, so two concurrent claims
// can never receive the same key (TRS-CRY-005). The repository never sees a
// raw access credential - only its SHA-256 - so a dump of the store cannot be
// replayed as logins. It stores only PUBLIC key material (TRS-BCK-002).
package registry

import (
	"context"
	"errors"
	"sync"
)

// DeviceID is the opaque routing identifier the backend assigns at
// registration. It is random, not derived from any key, so it links nothing.
type DeviceID [16]byte

// Device is one registered installation.
type Device struct {
	ID             DeviceID
	DeviceKey      [32]byte // Ed25519 public key the device proved it holds
	CredentialHash [32]byte // SHA-256 of the access credential; never the credential
	Revoked        bool
	CreatedAt      int64
}

// SignedBundle is a device's long-lived public prekey material.
type SignedBundle struct {
	IdentityEd    [32]byte
	IdentityDH    [32]byte
	IdentityDHSig [64]byte
	SPKID         uint32
	SPK           [32]byte
	SPKSig        [64]byte
	UploadedAt    int64
}

// OneTimePrekey is a single-use public prekey.
type OneTimePrekey struct {
	ID  uint32
	Key [32]byte
}

// Repository errors. They are internal sentinels: the service maps them to
// stable registry codes before anything leaves the process.
var (
	ErrNotFound     = errors.New("registry: not found")
	ErrDuplicate    = errors.New("registry: duplicate")
	ErrStockCeiling = errors.New("registry: one-time prekey stock ceiling")
)

// Repository is the registry's storage contract.
type Repository interface {
	CreateDevice(ctx context.Context, d Device) error
	DeviceByCredential(ctx context.Context, credentialHash [32]byte) (Device, error)
	Device(ctx context.Context, id DeviceID) (Device, error)
	RevokeDevice(ctx context.Context, id DeviceID) error

	PutSignedBundle(ctx context.Context, id DeviceID, b SignedBundle) error
	// CurrentBundle reads the signed bundle without touching one-time stock.
	CurrentBundle(ctx context.Context, id DeviceID) (SignedBundle, error)
	// AddOneTimePrekeys appends keys atomically: all of them or none. It
	// refuses an id already in stock or ever issued (ErrDuplicate) and a batch
	// that would take the stock above ceiling (ErrStockCeiling).
	AddOneTimePrekeys(ctx context.Context, id DeviceID, keys []OneTimePrekey, ceiling int) error
	// ClaimBundle returns the signed bundle and, when stock remains, removes
	// and returns exactly one one-time prekey in the same atomic step. A nil
	// prekey is the documented X3DH fallback (TRS-CRY-005).
	ClaimBundle(ctx context.Context, id DeviceID) (SignedBundle, *OneTimePrekey, error)
	OneTimePrekeyCount(ctx context.Context, id DeviceID) (int, error)
}

type deviceRecord struct {
	device Device
	bundle *SignedBundle
	stock  []OneTimePrekey
	issued map[uint32]struct{} // ids ever stocked: never accepted again
}

// MemoryRepository is the in-process implementation, for tests and local
// development. One mutex serialises every operation, which is what makes
// ClaimBundle atomic here; the PostgreSQL adapter gets the same property from
// a single DELETE ... RETURNING.
type MemoryRepository struct {
	mu      sync.Mutex
	devices map[DeviceID]*deviceRecord
	byCred  map[[32]byte]DeviceID
}

// NewMemoryRepository returns an empty store.
func NewMemoryRepository() *MemoryRepository {
	return &MemoryRepository{devices: map[DeviceID]*deviceRecord{}, byCred: map[[32]byte]DeviceID{}}
}

// CreateDevice implements Repository.
func (m *MemoryRepository) CreateDevice(_ context.Context, d Device) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.devices[d.ID]; ok {
		return ErrDuplicate
	}
	if _, ok := m.byCred[d.CredentialHash]; ok {
		return ErrDuplicate
	}
	m.devices[d.ID] = &deviceRecord{device: d, issued: map[uint32]struct{}{}}
	m.byCred[d.CredentialHash] = d.ID
	return nil
}

// DeviceByCredential implements Repository.
func (m *MemoryRepository) DeviceByCredential(_ context.Context, h [32]byte) (Device, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	id, ok := m.byCred[h]
	if !ok {
		return Device{}, ErrNotFound
	}
	return m.devices[id].device, nil
}

// Device implements Repository.
func (m *MemoryRepository) Device(_ context.Context, id DeviceID) (Device, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	r, ok := m.devices[id]
	if !ok {
		return Device{}, ErrNotFound
	}
	return r.device, nil
}

// RevokeDevice implements Repository.
func (m *MemoryRepository) RevokeDevice(_ context.Context, id DeviceID) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	r, ok := m.devices[id]
	if !ok {
		return ErrNotFound
	}
	r.device.Revoked = true
	return nil
}

// PutSignedBundle implements Repository.
func (m *MemoryRepository) PutSignedBundle(_ context.Context, id DeviceID, b SignedBundle) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	r, ok := m.devices[id]
	if !ok {
		return ErrNotFound
	}
	r.bundle = &b
	return nil
}

// CurrentBundle implements Repository.
func (m *MemoryRepository) CurrentBundle(_ context.Context, id DeviceID) (SignedBundle, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	r, ok := m.devices[id]
	if !ok || r.bundle == nil {
		return SignedBundle{}, ErrNotFound
	}
	return *r.bundle, nil
}

// AddOneTimePrekeys implements Repository.
func (m *MemoryRepository) AddOneTimePrekeys(_ context.Context, id DeviceID, keys []OneTimePrekey, ceiling int) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	r, ok := m.devices[id]
	if !ok {
		return ErrNotFound
	}
	if len(r.stock)+len(keys) > ceiling {
		return ErrStockCeiling
	}
	seen := map[uint32]struct{}{}
	for _, k := range keys {
		if _, dup := r.issued[k.ID]; dup {
			return ErrDuplicate
		}
		if _, dup := seen[k.ID]; dup {
			return ErrDuplicate
		}
		seen[k.ID] = struct{}{}
	}
	for _, k := range keys {
		r.issued[k.ID] = struct{}{}
		r.stock = append(r.stock, k)
	}
	return nil
}

// ClaimBundle implements Repository.
func (m *MemoryRepository) ClaimBundle(_ context.Context, id DeviceID) (SignedBundle, *OneTimePrekey, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	r, ok := m.devices[id]
	if !ok || r.bundle == nil || r.device.Revoked {
		return SignedBundle{}, nil, ErrNotFound
	}
	if len(r.stock) == 0 {
		return *r.bundle, nil, nil
	}
	k := r.stock[0]
	r.stock = r.stock[1:]
	return *r.bundle, &k, nil
}

// OneTimePrekeyCount implements Repository.
func (m *MemoryRepository) OneTimePrekeyCount(_ context.Context, id DeviceID) (int, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	r, ok := m.devices[id]
	if !ok {
		return 0, ErrNotFound
	}
	return len(r.stock), nil
}
