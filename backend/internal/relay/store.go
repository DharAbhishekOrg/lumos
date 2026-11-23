// Lumos - backend/internal/relay / The mailbox storage contract and its in-memory implementation.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-BCK-002, TRS-BCK-003, TRS-BCK-004, SRS-RMT-002, SRS-RMT-015, SRS-RMT-016, SRS-DAT-003
// ADRs: ADR-001
// Threading: concurrent-safe
// Notes: An Envelope is exactly the TRS-BCK-003 record: message id, recipient
// routing id, sender authorisation proof, created, expiry, size, class and
// state - and the payload as OPAQUE bytes (TRS-BCK-002). The sender's device is
// deliberately NOT stored: the authorisation proof is the SHA-256 of a send
// capability the recipient issued to one matched peer, so a dump of the store
// shows that "some holder of capability X" wrote to a mailbox, not who.
// Storage is two seams because TRS-BCK-008 splits it: send capabilities are
// durable records (PostgreSQL, PostgresCapabilities) and envelopes are a TTL
// queue (Redis, RedisMailboxes). MemoryStore implements both and is the
// contract's reference; every implementation runs the same tests. Put is
// atomic: the duplicate check, the quota check and the insert happen as one
// step, so concurrent senders can neither double-store an id nor overshoot
// RELAY_MAILBOX_QUOTA. RegisterCapability is atomic the same way against the
// capability ceiling. Put also sweeps that mailbox's expired envelopes, so an
// active mailbox never carries expired metadata; Purge sweeps everything.
package relay

import (
	"context"
	"errors"
	"sort"
	"sync"

	"github.com/dharabhishekorg/lumos/backend/internal/registry"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
)

// MessageID is the sender-chosen 16-byte message identifier (MESSAGE_ID_LEN_BYTES).
type MessageID [lumosconstants.MessageIDLenBytesDefault]byte

// Envelope is one stored message: metadata in the clear, payload opaque.
type Envelope struct {
	ID            MessageID
	Recipient     registry.DeviceID
	AuthProof     [32]byte // SHA-256 of the send capability; never the capability
	Class         lumosconstants.MessageClass
	CreatedAt     int64
	ExpiresAt     int64
	Size          int
	PayloadDigest [32]byte // SHA-256 of Payload, for idempotency without re-reading it
	Payload       []byte
}

// Quota is a mailbox's ceiling (RELAY_MAILBOX_QUOTA).
type Quota struct {
	Envelopes int
	Bytes     int
}

// PutResult says what Put did with an accepted envelope.
type PutResult int

const (
	// Stored means the envelope is new and now waits in the mailbox.
	Stored PutResult = iota
	// Duplicate means this exact envelope was already accepted (pending or
	// acknowledged): the sender's retry succeeds and nothing is stored twice.
	Duplicate
)

// Store errors; the service maps them to registry codes.
var (
	ErrMailboxFull = errors.New("relay: mailbox full")
	ErrReplay      = errors.New("relay: message id reused with different content")
)

// Capabilities is the durable half: who may write to which mailbox.
type Capabilities interface {
	// RegisterCapability lets holders of the capability hashing to proof
	// write to mailbox. Idempotent; ErrMailboxFull if the mailbox already
	// holds ceiling capabilities and this one is new.
	RegisterCapability(ctx context.Context, mailbox registry.DeviceID, proof [32]byte, ceiling int) error
	// RevokeCapability withdraws it (block or unmatch). Idempotent.
	RevokeCapability(ctx context.Context, mailbox registry.DeviceID, proof [32]byte) error
	CapabilityValid(ctx context.Context, mailbox registry.DeviceID, proof [32]byte) (bool, error)
}

// Mailboxes is the TTL-queue half: the envelopes themselves.
type Mailboxes interface {
	// Put stores e unless its id was already accepted for that mailbox.
	Put(ctx context.Context, e Envelope, q Quota, now int64) (PutResult, error)
	// Fetch returns up to limit unexpired envelopes, oldest first.
	Fetch(ctx context.Context, mailbox registry.DeviceID, now int64, limit int) ([]Envelope, error)
	// Ack deletes the payloads of ids in mailbox and keeps a tombstone until
	// the original expiry, so a late retry is a Duplicate, not a redelivery.
	Ack(ctx context.Context, mailbox registry.DeviceID, ids []MessageID) (int, error)
	// Purge deletes everything expired; returns envelopes removed.
	Purge(ctx context.Context, now int64) (int, error)
}

type key struct {
	mailbox registry.DeviceID
	id      MessageID
}

type tombstone struct {
	digest    [32]byte
	expiresAt int64
}

// MemoryStore is the in-process reference for every relay storage seam,
// one mutex over everything.
type MemoryStore struct {
	mu           sync.Mutex
	envelopes    map[key]Envelope
	arrivals     map[key]uint64 // submission order; ties in CreatedAt never fall back to random ids
	arrived      uint64
	tombstones   map[key]tombstone
	capabilities map[registry.DeviceID]map[[32]byte]struct{}
	rates        map[registry.DeviceID]rateWindow
}

// NewMemoryStore returns an empty store.
func NewMemoryStore() *MemoryStore {
	return &MemoryStore{
		envelopes:    map[key]Envelope{},
		arrivals:     map[key]uint64{},
		tombstones:   map[key]tombstone{},
		capabilities: map[registry.DeviceID]map[[32]byte]struct{}{},
		rates:        map[registry.DeviceID]rateWindow{},
	}
}

// RegisterCapability implements Capabilities.
func (m *MemoryStore) RegisterCapability(_ context.Context, mailbox registry.DeviceID, proof [32]byte, ceiling int) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	set := m.capabilities[mailbox]
	if set == nil {
		set = map[[32]byte]struct{}{}
		m.capabilities[mailbox] = set
	}
	if _, ok := set[proof]; ok {
		return nil
	}
	if len(set) >= ceiling {
		return ErrMailboxFull
	}
	set[proof] = struct{}{}
	return nil
}

// RevokeCapability implements Capabilities.
func (m *MemoryStore) RevokeCapability(_ context.Context, mailbox registry.DeviceID, proof [32]byte) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.capabilities[mailbox], proof)
	return nil
}

// CapabilityValid implements Capabilities.
func (m *MemoryStore) CapabilityValid(_ context.Context, mailbox registry.DeviceID, proof [32]byte) (bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	_, ok := m.capabilities[mailbox][proof]
	return ok, nil
}

// Put implements Mailboxes.
func (m *MemoryStore) Put(_ context.Context, e Envelope, q Quota, now int64) (PutResult, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	k := key{e.Recipient, e.ID}
	if prev, ok := m.envelopes[k]; ok && prev.ExpiresAt >= now {
		if prev.PayloadDigest == e.PayloadDigest {
			return Duplicate, nil
		}
		return 0, ErrReplay
	}
	if t, ok := m.tombstones[k]; ok && t.expiresAt >= now {
		if t.digest == e.PayloadDigest {
			return Duplicate, nil
		}
		return 0, ErrReplay
	}
	count, bytes := 0, 0
	for k2, v := range m.envelopes {
		if k2.mailbox != e.Recipient {
			continue
		}
		if v.ExpiresAt < now {
			delete(m.envelopes, k2) // sweep this mailbox's expired envelopes
			delete(m.arrivals, k2)
			continue
		}
		count++
		bytes += v.Size
	}
	if count+1 > q.Envelopes || bytes+e.Size > q.Bytes {
		return 0, ErrMailboxFull
	}
	stored := e
	stored.Payload = append([]byte(nil), e.Payload...)
	m.envelopes[k] = stored
	m.arrived++
	m.arrivals[k] = m.arrived
	return Stored, nil
}

// Fetch implements Mailboxes.
func (m *MemoryStore) Fetch(_ context.Context, mailbox registry.DeviceID, now int64, limit int) ([]Envelope, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	var keys []key
	for k, v := range m.envelopes {
		if k.mailbox == mailbox && v.ExpiresAt >= now {
			keys = append(keys, k)
		}
	}
	// Submission order (S8): a burst inside one second keeps the order it was sent in.
	sort.Slice(keys, func(i, j int) bool { return m.arrivals[keys[i]] < m.arrivals[keys[j]] })
	out := make([]Envelope, 0, len(keys))
	for _, k := range keys {
		v := m.envelopes[k]
		v.Payload = append([]byte(nil), v.Payload...)
		out = append(out, v)
	}
	if len(out) > limit {
		out = out[:limit]
	}
	return out, nil
}

// Ack implements Mailboxes.
func (m *MemoryStore) Ack(_ context.Context, mailbox registry.DeviceID, ids []MessageID) (int, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	n := 0
	for _, id := range ids {
		k := key{mailbox, id}
		if v, ok := m.envelopes[k]; ok {
			m.tombstones[k] = tombstone{digest: v.PayloadDigest, expiresAt: v.ExpiresAt}
			delete(m.envelopes, k)
			delete(m.arrivals, k)
			n++
		}
	}
	return n, nil
}

// Purge implements Mailboxes.
func (m *MemoryStore) Purge(_ context.Context, now int64) (int, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	n := 0
	for k, v := range m.envelopes {
		if v.ExpiresAt < now {
			delete(m.envelopes, k)
			delete(m.arrivals, k)
			n++
		}
	}
	for k, t := range m.tombstones {
		if t.expiresAt < now {
			delete(m.tombstones, k)
		}
	}
	return n, nil
}
