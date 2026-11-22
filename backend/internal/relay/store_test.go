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
