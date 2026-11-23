// Lumos - backend/internal/relay / The relay's rules, each pinned by a test.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-BCK-003, TRS-BCK-004, SRS-RMT-009, SRS-RMT-015, SRS-RMT-016, SRS-DAT-003, SRS-TRN-009, TRS-TST-001
// ADRs: -
// Threading: test-only
// Notes: Driven through the Service with an injected clock, so TTL is tested
// by moving time, not by waiting. Every test runs twice (eachStore): over the
// reference MemoryStore, and over PostgreSQL capabilities plus Redis
// mailboxes, the pair TRS-BCK-008 deploys, so the two cannot drift.
package relay

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"errors"
	"sync"
	"testing"

	"github.com/dharabhishekorg/lumos/backend/internal/registry"
	"github.com/dharabhishekorg/lumos/backend/internal/testdb"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumoserrors"
	"github.com/dharabhishekorg/lumos/protocol/wire"
)

type fixture struct {
	svc       *Service
	now       int64
	owner     registry.Device
	sender    registry.Device
	allowed   [32]byte
	ctx       context.Context
	nextIDSeq byte
}

// storePair builds the two storage halves for one test.
type storePair func(t *testing.T, owner registry.DeviceID) (Capabilities, Mailboxes, RateLimiter)

func memoryPair(*testing.T, registry.DeviceID) (Capabilities, Mailboxes, RateLimiter) {
	m := NewMemoryStore()
	return m, m, m
}

// serverPair is PostgreSQL capabilities and Redis mailboxes, each isolated to
// this test. The owner gets a device row because capabilities reference it.
func serverPair(t *testing.T, owner registry.DeviceID) (Capabilities, Mailboxes, RateLimiter) {
	t.Helper()
	pool := testdb.Postgres(t)
	ctx := context.Background()
	if err := registry.Migrate(ctx, pool); err != nil {
		t.Fatal(err)
	}
	var key, cred [32]byte
	cred[0] = owner[0]
	if _, err := pool.Exec(ctx, `INSERT INTO devices (id, device_key, credential_hash, created_at) VALUES ($1, $2, $3, 0)`,
		owner[:], key[:], cred[:]); err != nil {
		t.Fatal(err)
	}
	rdb, ns := testdb.Redis(t)
	mail := NewRedisMailboxes(rdb, ns)
	return NewPostgresCapabilities(pool), mail, mail
}

// unlimitedRate lifts the send rate for tests that need more sends from one
// device in one minute than RELAY_SEND_RATE_PER_MIN allows.
const unlimitedRate = 1 << 30

// eachStore runs one contract against every storage implementation.
func eachStore(t *testing.T, run func(t *testing.T, f *fixture)) {
	t.Helper()
	t.Run("memory", func(t *testing.T) { run(t, newFixture(t, memoryPair)) })
	t.Run("postgres+redis", func(t *testing.T) { run(t, newFixture(t, serverPair)) })
}

func newFixture(t *testing.T, pair storePair) *fixture {
	t.Helper()
	f := &fixture{now: 1_700_000_000, ctx: context.Background()}
	f.owner.ID[0], f.sender.ID[0] = 1, 2
	caps, mail, limiter := pair(t, f.owner.ID)
	f.svc = NewService(caps, mail, limiter, func() int64 { return f.now })
	if _, err := rand.Read(f.allowed[:]); err != nil {
		t.Fatal(err)
	}
	if err := f.svc.Allow(f.ctx, f.owner, ProofOf(f.allowed)); err != nil {
		t.Fatal(err)
	}
	return f
}

func (f *fixture) id() MessageID {
	f.nextIDSeq++
	var id MessageID
	id[0] = f.nextIDSeq
	return id
}

func (f *fixture) send(id MessageID, payload []byte) error {
	return f.svc.Submit(f.ctx, f.sender, Send{
		Recipient: f.owner.ID, ID: id, Class: lumosconstants.MessageClassTextMessage,
		Capability: f.allowed, Payload: payload,
	})
}

func (f *fixture) mailbox(t *testing.T) []Envelope {
	t.Helper()
	got, err := f.svc.Fetch(f.ctx, f.owner, FetchMax)
	if err != nil {
		t.Fatal(err)
	}
	return got
}

func code(err error) string {
	var we *wire.Error
	if errors.As(err, &we) {
		return we.Code
	}
	if err != nil {
		return err.Error()
	}
	return ""
}

func TestOnlyRelayEligibleClassesCrossTheInternet(t *testing.T) {
	eachStore(t, func(t *testing.T, f *fixture) {
		for _, c := range []lumosconstants.MessageClass{
			lumosconstants.MessageClassTextMessage, lumosconstants.MessageClassDeliveryReceipt,
			lumosconstants.MessageClassReadReceipt,
		} {
			err := f.svc.Submit(f.ctx, f.sender, Send{Recipient: f.owner.ID, ID: f.id(), Class: c, Capability: f.allowed, Payload: []byte{1}})
			if err != nil {
				t.Fatalf("%v must be relay-eligible: %v", c, err)
			}
		}
		for _, c := range []lumosconstants.MessageClass{
			lumosconstants.MessageClassObjectChunk, lumosconstants.MessageClassObjectMeta,
			lumosconstants.MessageClassVoiceMeta, lumosconstants.MessageClassProfileFull,
		} {
			err := f.svc.Submit(f.ctx, f.sender, Send{Recipient: f.owner.ID, ID: f.id(), Class: c, Capability: f.allowed, Payload: []byte{1}})
			if code(err) != lumoserrors.ErrTransportLocalOnlyViolation {
				t.Fatalf("%v is local-only (SRS-TRN-009); got %v", c, err)
			}
		}
		err := f.svc.Submit(f.ctx, f.sender, Send{Recipient: f.owner.ID, ID: f.id(), Class: 0xEE, Capability: f.allowed, Payload: []byte{1}})
		if code(err) != lumoserrors.ErrPayloadMalformed {
			t.Fatalf("an unknown class is malformed, got %v", err)
		}
		if n := len(f.mailbox(t)); n != 3 {
			t.Fatalf("only the three eligible envelopes may be stored, found %d", n)
		}
	})
}

func TestSizeLimitsComeFromTheRegister(t *testing.T) {
	eachStore(t, func(t *testing.T, f *fixture) {
		text, _ := lumosconstants.MaxPayloadBytes(lumosconstants.MessageClassTextMessage)
		if err := f.send(f.id(), make([]byte, text)); err != nil {
			t.Fatalf("exactly the text limit must pass: %v", err)
		}
		if got := code(f.send(f.id(), make([]byte, text+1))); got != lumoserrors.ErrFramePayloadTooLarge {
			t.Fatalf("one byte over the text limit: want %s, got %s", lumoserrors.ErrFramePayloadTooLarge, got)
		}
		receipt, _ := lumosconstants.MaxPayloadBytes(lumosconstants.MessageClassDeliveryReceipt)
		err := f.svc.Submit(f.ctx, f.sender, Send{Recipient: f.owner.ID, ID: f.id(),
			Class: lumosconstants.MessageClassDeliveryReceipt, Capability: f.allowed, Payload: make([]byte, receipt+1)})
		if code(err) != lumoserrors.ErrFramePayloadTooLarge {
			t.Fatalf("a receipt has its own, smaller limit: got %v", err)
		}
		if got := code(f.send(f.id(), nil)); got != lumoserrors.ErrPayloadMalformed {
			t.Fatalf("an empty payload is malformed, got %s", got)
		}
	})
}

func TestABlockedSenderIsToldNothingAndDeliversNothing(t *testing.T) {
	eachStore(t, func(t *testing.T, f *fixture) {
		if err := f.send(f.id(), []byte("before")); err != nil {
			t.Fatal(err)
		}
		if err := f.svc.Revoke(f.ctx, f.owner, ProofOf(f.allowed)); err != nil {
			t.Fatal(err)
		}
		if err := f.send(f.id(), []byte("after block")); err != nil {
			t.Fatalf("a blocked sender must get the same success as anyone else (SRS-MAT-003), got %v", err)
		}
		var invented [32]byte
		err := f.svc.Submit(f.ctx, f.sender, Send{Recipient: f.owner.ID, ID: f.id(),
			Class: lumosconstants.MessageClassTextMessage, Capability: invented, Payload: []byte("probe")})
		if err != nil {
			t.Fatalf("a never-issued capability is indistinguishable from a revoked one, got %v", err)
		}
		got := f.mailbox(t)
		if len(got) != 1 || !bytes.Equal(got[0].Payload, []byte("before")) {
			t.Fatalf("only the pre-block message may be delivered (SRS-RMT-016), got %d envelopes", len(got))
		}
	})
}

func TestRetriesAreIdempotentAndAReusedIDIsAReplay(t *testing.T) {
	eachStore(t, func(t *testing.T, f *fixture) {
		id := f.id()
		for range 3 {
			if err := f.send(id, []byte("hello")); err != nil {
				t.Fatalf("a retry of the same envelope must succeed: %v", err)
			}
		}
		if n := len(f.mailbox(t)); n != 1 {
			t.Fatalf("three retries must store one envelope, found %d", n)
		}
		if got := code(f.send(id, []byte("forged"))); got != lumoserrors.ErrCryptoReplay {
			t.Fatalf("the same id with other bytes is a replay: got %s", got)
		}
		if _, err := f.svc.Ack(f.ctx, f.owner, []MessageID{id}); err != nil {
			t.Fatal(err)
		}
		if err := f.send(id, []byte("hello")); err != nil {
			t.Fatalf("a late retry after acknowledgement must still succeed: %v", err)
		}
		if n := len(f.mailbox(t)); n != 0 {
			t.Fatalf("a late retry must not redeliver an acknowledged message, found %d", n)
		}
		if got := code(f.send(id, []byte("forged"))); got != lumoserrors.ErrCryptoReplay {
			t.Fatalf("the tombstone must still catch a replay after acknowledgement: got %s", got)
		}
	})
}

func TestAcknowledgedOrExpiredEnvelopesAreGone(t *testing.T) {
	eachStore(t, func(t *testing.T, f *fixture) {
		acked, kept := f.id(), f.id()
		_ = f.send(acked, []byte("a"))
		_ = f.send(kept, []byte("b"))
		n, err := f.svc.Ack(f.ctx, f.owner, []MessageID{acked})
		if err != nil || n != 1 {
			t.Fatalf("ack: n=%d err=%v", n, err)
		}
		got := f.mailbox(t)
		if len(got) != 1 || got[0].ID != kept {
			t.Fatal("an acknowledged envelope is deleted at once (SRS-RMT-015)")
		}
		ttl := int64(lumosconstants.RelayEnvelopeTTLDaysDefault) * secondsPerDay
		f.now += ttl
		if len(f.mailbox(t)) != 1 {
			t.Fatal("an envelope lives exactly RELAY_ENVELOPE_TTL_DAYS")
		}
		f.now++
		if len(f.mailbox(t)) != 0 {
			t.Fatal("one second past its TTL an envelope is never served")
		}
		purged, err := f.svc.Purge(f.ctx)
		if err != nil || purged != 1 {
			t.Fatalf("purge must delete the expired envelope: n=%d err=%v", purged, err)
		}
	})
}

func TestTheEnvelopeQuotaIsAtomicUnderConcurrentSenders(t *testing.T) {
	eachStore(t, func(t *testing.T, f *fixture) {
		f.svc.rate = unlimitedRate // this test is about the mailbox, not the sender
		limit := lumosconstants.RelayMailboxQuotaEnvelopes
		extra := 100
		ids := make([]MessageID, limit+extra)
		for i := range ids {
			var id MessageID
			id[0], id[1] = byte(i>>8), byte(i) // #nosec G115 -- i < 2^16 here
			id[2] = 0xAA
			ids[i] = id
		}
		var mu sync.Mutex
		full := 0
		var wg sync.WaitGroup
		for _, id := range ids {
			wg.Add(1)
			go func() {
				defer wg.Done()
				err := f.send(id, []byte("x"))
				mu.Lock()
				defer mu.Unlock()
				switch code(err) {
				case "":
				case lumoserrors.ErrRelayMailboxFull:
					full++
				default:
					t.Errorf("unexpected: %v", err)
				}
			}()
		}
		wg.Wait()
		if full != extra {
			t.Fatalf("exactly %d sends past RELAY_MAILBOX_QUOTA.envelopes must be refused, got %d", extra, full)
		}
	})
}

func TestTheByteQuotaRefusesWhatWouldNotFit(t *testing.T) {
	eachStore(t, func(t *testing.T, f *fixture) {
		f.svc.rate = unlimitedRate // this test is about the mailbox, not the sender
		size, _ := lumosconstants.MaxPayloadBytes(lumosconstants.MessageClassTextMessage)
		fits := lumosconstants.RelayMailboxQuotaBytes / int(size)
		for i := range fits {
			if err := f.send(f.idN(i), make([]byte, size)); err != nil {
				t.Fatalf("envelope %d of %d must fit: %v", i+1, fits, err)
			}
		}
		if got := code(f.send(f.idN(fits), make([]byte, size))); got != lumoserrors.ErrRelayMailboxFull {
			t.Fatalf("the next full-size envelope exceeds RELAY_MAILBOX_QUOTA.bytes: got %s", got)
		}
	})
}

func (f *fixture) idN(i int) MessageID {
	var id MessageID
	id[0], id[1], id[2] = byte(i>>8), byte(i), 0xBB // #nosec G115 -- i < 2^16 here
	return id
}

func TestFetchIsOldestFirstAndBounded(t *testing.T) {
	eachStore(t, func(t *testing.T, f *fixture) {
		for i := range FetchMax + 10 {
			f.now++
			if err := f.send(f.idN(i), []byte{byte(i)}); err != nil { // #nosec G115 -- small
				t.Fatal(err)
			}
		}
		got := f.mailbox(t)
		if len(got) != FetchMax {
			t.Fatalf("one fetch returns at most %d, got %d", FetchMax, len(got))
		}
		for i := 1; i < len(got); i++ {
			if got[i].CreatedAt < got[i-1].CreatedAt {
				t.Fatal("envelopes come oldest first")
			}
		}
		if got[0].Payload[0] != 0 {
			t.Fatal("the oldest envelope comes first")
		}
	})
}

// A burst inside one second must come back in the order it was sent: message
// ids are random, so ordering ties by id would shuffle "hi" and "free
// tonight?" for the recipient. Found by the S8 staging suite (2026-11-23).
func TestABurstWithinOneSecondIsFetchedInSubmissionOrder(t *testing.T) {
	eachStore(t, func(t *testing.T, f *fixture) {
		const n = 8
		for i := range n {
			// ids sort in REVERSE of send order, and the clock does not move.
			if err := f.send(f.idN(0xF0-i), []byte{byte(i)}); err != nil { // #nosec G115 -- small
				t.Fatal(err)
			}
		}
		got := f.mailbox(t)
		if len(got) != n {
			t.Fatalf("fetched %d, want %d", len(got), n)
		}
		for i, e := range got {
			if e.Payload[0] != byte(i) { // #nosec G115 -- small
				t.Fatalf("position %d holds the %d-th send: a burst must keep its order", i, e.Payload[0])
			}
		}
	})
}

func TestTheEnvelopeRecordIsTheTRSRecordAndHoldsNoCapability(t *testing.T) {
	eachStore(t, func(t *testing.T, f *fixture) {
		id := f.id()
		payload := []byte("opaque ciphertext")
		if err := f.send(id, payload); err != nil {
			t.Fatal(err)
		}
		e := f.mailbox(t)[0]
		ttl := int64(lumosconstants.RelayEnvelopeTTLDaysDefault) * secondsPerDay
		switch {
		case e.ID != id, e.Recipient != f.owner.ID, e.Class != lumosconstants.MessageClassTextMessage:
			t.Fatal("message id, recipient routing id and class must be recorded")
		case e.CreatedAt != f.now || e.ExpiresAt != f.now+ttl:
			t.Fatal("created time and expiry (created + TTL) must be recorded")
		case e.Size != len(payload) || !bytes.Equal(e.Payload, payload):
			t.Fatal("size and the opaque payload must be recorded")
		case e.AuthProof != sha256.Sum256(f.allowed[:]) || e.AuthProof == f.allowed:
			t.Fatal("the authorisation proof is the capability's hash, never the capability")
		}
	})
}

func TestACapabilityListHasACeiling(t *testing.T) {
	eachStore(t, func(t *testing.T, f *fixture) {
		for i := 1; i < capabilityCeiling; i++ {
			var p [32]byte
			p[0], p[1], p[2] = byte(i>>16), byte(i>>8), byte(i) // #nosec G115 -- bounded
			if err := f.svc.Allow(f.ctx, f.owner, p); err != nil {
				t.Fatalf("capability %d: %v", i, err)
			}
		}
		var over [32]byte
		over[31] = 0xFF
		if got := code(f.svc.Allow(f.ctx, f.owner, over)); got != lumoserrors.ErrRelayMailboxFull {
			t.Fatalf("past the ceiling: want %s, got %s", lumoserrors.ErrRelayMailboxFull, got)
		}
		if err := f.svc.Allow(f.ctx, f.owner, ProofOf(f.allowed)); err != nil {
			t.Fatalf("re-allowing an existing capability stays idempotent at the ceiling: %v", err)
		}
	})
}
