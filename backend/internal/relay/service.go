// Lumos - backend/internal/relay / Send, fetch, acknowledge: the relay's rules over a Store.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-BCK-002, TRS-BCK-003, TRS-BCK-004, SRS-RMT-002, SRS-RMT-009, SRS-RMT-015, SRS-RMT-016, SRS-DAT-003, SRS-TRN-009
// ADRs: ADR-001
// Threading: concurrent-safe
// Notes: Only relay-eligible classes are accepted - TEXT_MESSAGE and the two
// receipts the ticks need. Every other known class is local-only by policy and
// is refused with ERR_TRANSPORT_LOCAL_ONLY_VIOLATION, so "local-only media never
// reaches the internet" (SRS-TRN-009) holds at the server too, not only in the
// client's router. Size limits are the register's per-class bounds. A sender
// whose capability is unknown or revoked - blocked or unmatched - gets the SAME
// success as a delivered send and nothing is stored: SRS-RMT-016 stops
// delivery, and SRS-MAT-003 forbids telling the sender they were declined.
// Their message simply never earns a delivery receipt. TTL, quota and
// idempotency follow TRS-BCK-004; per-sender rate limits arrive with the Redis
// store that TRS-BCK-008 assigns them to. Nothing here logs, ever: payloads
// are opaque and metadata is the mailbox owner's business (TRS-BCK-002).
package relay

import (
	"context"
	"crypto/sha256"
	"errors"

	"github.com/dharabhishekorg/lumos/backend/internal/registry"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumoserrors"
	"github.com/dharabhishekorg/lumos/protocol/wire"
)

const secondsPerDay = 24 * 60 * 60

// FetchMax bounds one fetch. Fifty text envelopes at the register's default
// size is about 1.6 MB of ciphertext - a comfortable mobile response - and a
// full mailbox drains in twenty round trips.
const FetchMax = 50

// capabilityCeiling bounds one mailbox's send capabilities: a device cannot
// usefully have more distinct senders than its mailbox has envelope slots.
const capabilityCeiling = lumosconstants.RelayMailboxQuotaEnvelopesMax

// relayEligible is the internet-relay allow-list.
var relayEligible = map[lumosconstants.MessageClass]bool{
	lumosconstants.MessageClassTextMessage:     true,
	lumosconstants.MessageClassDeliveryReceipt: true,
	lumosconstants.MessageClassReadReceipt:     true,
}

// Clock returns unix seconds.
type Clock func() int64

// Service applies the relay's rules to a Store.
type Service struct {
	store  Store
	now    Clock
	quota  Quota
	ttlSec int64
}

// NewService builds the relay with the register's defaults.
func NewService(store Store, now Clock) *Service {
	return &Service{
		store:  store,
		now:    now,
		quota:  Quota{Envelopes: lumosconstants.RelayMailboxQuotaEnvelopes, Bytes: lumosconstants.RelayMailboxQuotaBytes},
		ttlSec: int64(lumosconstants.RelayEnvelopeTTLDaysDefault) * secondsPerDay,
	}
}

// Send is one envelope as a sender submits it.
type Send struct {
	Recipient  registry.DeviceID
	ID         MessageID
	Class      lumosconstants.MessageClass
	Capability [32]byte // the raw capability; only its SHA-256 is ever stored
	Payload    []byte
}

func wireErr(code, detail string) error { return &wire.Error{Code: code, Detail: detail} }

// ProofOf is the stored form of a send capability.
func ProofOf(capability [32]byte) [32]byte { return sha256.Sum256(capability[:]) }

// Submit accepts an envelope from an authenticated sender. A nil error means
// "accepted" whether it was stored, was a duplicate, or was silently dropped
// for a revoked capability - the three are deliberately indistinguishable.
func (s *Service) Submit(ctx context.Context, _ registry.Device, in Send) error {
	if !in.Class.Known() {
		return wireErr(lumoserrors.ErrPayloadMalformed, "unknown message class")
	}
	if !relayEligible[in.Class] {
		return wireErr(lumoserrors.ErrTransportLocalOnlyViolation, "class is not relay-eligible")
	}
	limit, _ := lumosconstants.MaxPayloadBytes(in.Class)
	if len(in.Payload) == 0 {
		return wireErr(lumoserrors.ErrPayloadMalformed, "empty payload")
	}
	if int64(len(in.Payload)) > limit {
		return wireErr(lumoserrors.ErrFramePayloadTooLarge, "payload exceeds the class limit")
	}
	proof := ProofOf(in.Capability)
	ok, err := s.store.CapabilityValid(ctx, in.Recipient, proof)
	if err != nil {
		return err
	}
	if !ok {
		return nil // blocked, unmatched or never issued: accepted, not delivered
	}
	now := s.now()
	_, err = s.store.Put(ctx, Envelope{
		ID: in.ID, Recipient: in.Recipient, AuthProof: proof, Class: in.Class,
		CreatedAt: now, ExpiresAt: now + s.ttlSec, Size: len(in.Payload),
		PayloadDigest: sha256.Sum256(in.Payload), Payload: in.Payload,
	}, s.quota, now)
	switch {
	case errors.Is(err, ErrMailboxFull):
		return wireErr(lumoserrors.ErrRelayMailboxFull, "mailbox full")
	case errors.Is(err, ErrReplay):
		return wireErr(lumoserrors.ErrCryptoReplay, "message id already used")
	}
	return err
}

// Fetch returns the owner's waiting envelopes, oldest first.
func (s *Service) Fetch(ctx context.Context, owner registry.Device, limit int) ([]Envelope, error) {
	if limit <= 0 || limit > FetchMax {
		limit = FetchMax
	}
	return s.store.Fetch(ctx, owner.ID, s.now(), limit)
}

// Ack deletes acknowledged envelopes from the owner's mailbox (SRS-RMT-015).
func (s *Service) Ack(ctx context.Context, owner registry.Device, ids []MessageID) (int, error) {
	if len(ids) > FetchMax {
		return 0, wireErr(lumoserrors.ErrPayloadMalformed, "too many ids in one ack")
	}
	return s.store.Ack(ctx, owner.ID, ids)
}

// Allow registers a capability proof the owner issued to one matched peer.
func (s *Service) Allow(ctx context.Context, owner registry.Device, proof [32]byte) error {
	if ok, err := s.store.CapabilityValid(ctx, owner.ID, proof); err != nil || ok {
		return err // idempotent
	}
	n, err := s.store.CapabilityCount(ctx, owner.ID)
	if err != nil {
		return err
	}
	if n >= capabilityCeiling {
		return wireErr(lumoserrors.ErrRelayMailboxFull, "capability ceiling reached")
	}
	return s.store.RegisterCapability(ctx, owner.ID, proof)
}

// Revoke withdraws a capability: the block or unmatch half of SRS-RMT-016.
func (s *Service) Revoke(ctx context.Context, owner registry.Device, proof [32]byte) error {
	return s.store.RevokeCapability(ctx, owner.ID, proof)
}

// Purge removes everything expired; run periodically.
func (s *Service) Purge(ctx context.Context) (int, error) {
	return s.store.Purge(ctx, s.now())
}
