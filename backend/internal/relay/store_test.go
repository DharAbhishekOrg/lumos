// Lumos - backend/internal/relay / Storage properties the Service tests cannot see.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-BCK-008, TRS-BCK-002, SRS-DAT-003, SRS-RMT-016, TRS-TST-001
// ADRs: -
// Threading: test-only
// Notes: The ceiling race is driven against the Capabilities seam directly
// with a small ceiling, so it is fast on PostgreSQL. The Redis tests look at
// the keys themselves: payload deletion must not depend on a sweep, and an
// acknowledged envelope must leave nothing but its tombstone digest.
package relay

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/dharabhishekorg/lumos/backend/internal/registry"
	"github.com/dharabhishekorg/lumos/backend/internal/testdb"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumoserrors"
)

func TestConcurrentAllowsCannotOvershootTheCeiling(t *testing.T) {
	eachStore(t, func(t *testing.T, f *fixture) {
		const ceiling, racers = 8, 40
		// The fixture already registered one capability for the owner.
		var wg sync.WaitGroup
		var mu sync.Mutex
		accepted, full := 0, 0
		for i := range racers {
			wg.Add(1)
			go func() {
				defer wg.Done()
				var p [32]byte
				p[0], p[1] = 0xC0, byte(i) // #nosec G115 -- i < racers
				err := f.svc.caps.RegisterCapability(f.ctx, f.owner.ID, p, ceiling)
				mu.Lock()
				defer mu.Unlock()
				switch {
				case err == nil:
					accepted++
				case errors.Is(err, ErrMailboxFull):
					full++
				default:
					t.Errorf("unexpected: %v", err)
				}
			}()
		}
		wg.Wait()
		if accepted != ceiling-1 || full != racers-(ceiling-1) {
			t.Fatalf("with one already held, exactly %d of %d racers fit under a ceiling of %d; accepted %d, refused %d",
				ceiling-1, racers, ceiling, accepted, full)
		}
	})
}

func redisFixture(t *testing.T) (*RedisMailboxes, func(string) string, registry.DeviceID) {
	t.Helper()
	rdb, ns := testdb.Redis(t)
	m := NewRedisMailboxes(rdb, ns)
	var owner registry.DeviceID
	owner[0] = 7
	return m, func(suffix string) string { return m.prefix(owner) + suffix }, owner
}

func TestRedisDeletesThePayloadOnItsOwnClock(t *testing.T) {
	m, key, owner := redisFixture(t)
	ctx := context.Background()
	now := int64(1_700_000_000)
	ttl := int64(lumosconstants.RelayEnvelopeTTLDaysDefault) * secondsPerDay
	var id MessageID
	id[0] = 1
	e := Envelope{ID: id, Recipient: owner, Class: lumosconstants.MessageClassTextMessage,
		CreatedAt: now, ExpiresAt: now + ttl, Size: 3, Payload: []byte("abc")}
	if _, err := m.Put(ctx, e, Quota{Envelopes: 1, Bytes: 3}, now); err != nil {
		t.Fatal(err)
	}
	left, err := m.rdb.PTTL(ctx, key("e:"+string(id[:]))).Result()
	if err != nil {
		t.Fatal(err)
	}
	want := time.Duration(ttl+1) * time.Second
	if left <= 0 || left > want || want-left > time.Minute {
		t.Fatalf("the payload key must expire by itself at the envelope's TTL (SRS-DAT-003): PTTL %v, want about %v", left, want)
	}
	for _, k := range []string{"q", "x", "s", "b"} {
		if l, _ := m.rdb.PTTL(ctx, key(k)).Result(); l <= 0 || l > want {
			t.Fatalf("mailbox key %s must expire with its last envelope: PTTL %v", k, l)
		}
	}
}

func TestAnAcknowledgedEnvelopeLeavesOnlyItsDigest(t *testing.T) {
	m, key, owner := redisFixture(t)
	ctx := context.Background()
	now := int64(1_700_000_000)
	var id MessageID
	id[0] = 2
	e := Envelope{ID: id, Recipient: owner, Class: lumosconstants.MessageClassTextMessage,
		CreatedAt: now, ExpiresAt: now + secondsPerDay, Size: 6, Payload: []byte("secret")}
	e.PayloadDigest[0] = 0xD1
	if _, err := m.Put(ctx, e, Quota{Envelopes: 1, Bytes: 6}, now); err != nil {
		t.Fatal(err)
	}
	if n, err := m.Ack(ctx, owner, []MessageID{id}); err != nil || n != 1 {
		t.Fatalf("ack: n=%d err=%v", n, err)
	}
	if n, _ := m.rdb.Exists(ctx, key("e:"+string(id[:]))).Result(); n != 0 {
		t.Fatal("the payload record must be deleted on acknowledgement (SRS-RMT-015)")
	}
	tomb, err := m.rdb.HGetAll(ctx, key("t:"+string(id[:]))).Result()
	if err != nil {
		t.Fatal(err)
	}
	if len(tomb) != 2 || tomb["d"] != string(e.PayloadDigest[:]) || tomb["x"] == "" {
		t.Fatalf("the tombstone holds the digest and expiry and nothing else, got fields %d", len(tomb))
	}
	if l, _ := m.rdb.PTTL(ctx, key("t:"+string(id[:]))).Result(); l <= 0 {
		t.Fatal("the tombstone must expire by itself")
	}
	if used, _ := m.rdb.Get(ctx, key("b")).Int(); used != 0 {
		t.Fatalf("byte accounting must return to zero, got %d", used)
	}
}

func TestASenderIsLimitedPerMinuteAndTheLimitSaysNothingAboutBlocks(t *testing.T) {
	eachStore(t, func(t *testing.T, f *fixture) {
		f.now = 1_700_000_080 // 20 seconds before the minute boundary 1_700_000_100
		limit := lumosconstants.RelaySendRatePerMinDefault
		for i := range limit {
			if err := f.send(f.idN(i), []byte("x")); err != nil {
				t.Fatalf("send %d of %d must pass: %v", i+1, limit, err)
			}
		}
		err := f.send(f.idN(limit), []byte("x"))
		var rl *RateLimitedError
		if code(err) != lumoserrors.ErrRelayRateLimited || !errors.As(err, &rl) || rl.RetryAfter != 20 {
			t.Fatalf("send %d in one minute: want %s retrying after 20s, got %v", limit+1, lumoserrors.ErrRelayRateLimited, err)
		}
		if n := len(f.drain(t)); n != limit {
			t.Fatalf("a rate-limited send stores nothing: want %d envelopes, found %d", limit, n)
		}
		// A blocked sender over the limit gets the same answer as anyone else.
		if err := f.svc.Revoke(f.ctx, f.owner, ProofOf(f.allowed)); err != nil {
			t.Fatal(err)
		}
		if got := code(f.send(f.idN(limit+1), []byte("x"))); got != lumoserrors.ErrRelayRateLimited {
			t.Fatalf("the limit must not reveal a block (SRS-MAT-003): got %s", got)
		}
		if err := f.svc.Allow(f.ctx, f.owner, ProofOf(f.allowed)); err != nil {
			t.Fatal(err)
		}
		// Another device has its own budget.
		other := registry.Device{}
		other.ID[0] = 3
		err = f.svc.Submit(f.ctx, other, Send{Recipient: f.owner.ID, ID: f.idN(limit + 2),
			Class: lumosconstants.MessageClassTextMessage, Capability: f.allowed, Payload: []byte("x")})
		if err != nil {
			t.Fatalf("the limit is per sending device: %v", err)
		}
		// The next minute is a fresh window.
		f.now += 20
		if err := f.send(f.idN(limit+3), []byte("x")); err != nil {
			t.Fatalf("a new minute restores the budget: %v", err)
		}
	})
}

func TestTheRateCounterExpiresWithItsWindow(t *testing.T) {
	m, _, owner := redisFixture(t)
	ctx := context.Background()
	now := int64(1_700_000_090) // 10 seconds before the minute boundary 1_700_000_100
	if ok, _, err := m.TakeSend(ctx, owner, now, 1); err != nil || !ok {
		t.Fatalf("first send: ok=%v err=%v", ok, err)
	}
	keys, err := m.rdb.Keys(ctx, m.ns+"relay:rate:*").Result()
	if err != nil || len(keys) != 1 {
		t.Fatalf("one counter key, got %v (%v)", keys, err)
	}
	left, _ := m.rdb.PTTL(ctx, keys[0]).Result()
	if left <= 0 || left > 11*time.Second {
		t.Fatalf("the counter must expire at the end of its window: PTTL %v", left)
	}
	if v, _ := m.rdb.Get(ctx, keys[0]).Result(); v != "1" {
		t.Fatalf("the counter holds a count and nothing else, got %q", v)
	}
}

// drain lists every pending envelope, past the one-fetch bound.
func (f *fixture) drain(t *testing.T) []Envelope {
	t.Helper()
	got, err := f.svc.store.Fetch(f.ctx, f.owner.ID, f.now, unlimitedRate)
	if err != nil {
		t.Fatal(err)
	}
	return got
}

type recordingNotifier struct {
	mu   sync.Mutex
	woke []registry.DeviceID
}

func (r *recordingNotifier) Notify(id registry.DeviceID) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.woke = append(r.woke, id)
}

func (r *recordingNotifier) count() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return len(r.woke)
}

func TestOnlyANewlyStoredEnvelopeWakesTheRecipient(t *testing.T) {
	eachStore(t, func(t *testing.T, f *fixture) {
		n := &recordingNotifier{}
		f.svc.WakeWith(n)
		id := f.id()
		if err := f.send(id, []byte("hello")); err != nil {
			t.Fatal(err)
		}
		if n.count() != 1 || n.woke[0] != f.owner.ID {
			t.Fatalf("a stored envelope wakes its recipient once, got %d", n.count())
		}
		_ = f.send(id, []byte("hello"))  // duplicate
		_ = f.send(id, []byte("forged")) // replay
		if n.count() != 1 {
			t.Fatalf("a duplicate or replay must not wake anyone again, got %d", n.count())
		}
		if err := f.svc.Revoke(f.ctx, f.owner, ProofOf(f.allowed)); err != nil {
			t.Fatal(err)
		}
		_ = f.send(f.id(), []byte("after block"))
		if n.count() != 1 {
			t.Fatal("a blocked sender's message is dropped and must not wake the recipient")
		}
		if err := f.svc.Allow(f.ctx, f.owner, ProofOf(f.allowed)); err != nil {
			t.Fatal(err)
		}
		f.svc.rate = 1
		f.now += secondsPerMinute // a fresh window holding one send
		_ = f.send(f.id(), []byte("in budget"))
		_ = f.send(f.id(), []byte("over budget"))
		if n.count() != 2 {
			t.Fatalf("a rate-limited send must not wake anyone, got %d wakes", n.count())
		}
	})
}
