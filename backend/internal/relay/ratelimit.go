// Lumos - backend/internal/relay / The per-device send rate limit (CR-004).
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-BCK-004, TRS-BCK-008, SRS-SAF-006
// ADRs: ADR-004
// Threading: concurrent-safe
// Notes: RELAY_SEND_RATE_PER_MIN envelopes per sending device per fixed
// one-minute window (CR-004, owner-accepted 2026-11-22). The window is
// aligned to the minute of the caller's clock, so memory and Redis count the
// same window in the contract tests. The counter knows a device routing id
// and a number, nothing about recipients or content (TRS-BCK-002). An
// attempt over the limit still counts, so a flooding device stays limited
// for the rest of its window rather than probing it. In Redis the counter is
// one INCR with an expiry at the window's end (TRS-BCK-008); in memory it is
// the reference implementation.
package relay

import (
	"context"
	"encoding/hex"
	"strconv"
	"time"

	"github.com/dharabhishekorg/lumos/backend/internal/registry"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumoserrors"
	"github.com/dharabhishekorg/lumos/protocol/wire"
	"github.com/redis/go-redis/v9"
)

// secondsPerMinute is the CR-004 window: "per fixed one-minute window".
const secondsPerMinute = 60

// RateLimiter counts envelopes per sending device per window.
type RateLimiter interface {
	// TakeSend counts one send by device in the window containing now. It
	// reports whether the send is within limit and, if not, the seconds
	// until the window ends.
	TakeSend(ctx context.Context, device registry.DeviceID, now int64, limit int) (ok bool, retryAfter int64, err error)
}

// RateLimitedError is ERR_RELAY_RATE_LIMITED with the seconds the sender
// should wait; it unwraps to the wire error so codes read the same everywhere.
type RateLimitedError struct {
	RetryAfter int64
}

func (e *RateLimitedError) Error() string {
	return lumoserrors.ErrRelayRateLimited + ": retry after " + strconv.FormatInt(e.RetryAfter, 10) + "s"
}

// Unwrap exposes the stable code.
func (e *RateLimitedError) Unwrap() error {
	return &wire.Error{Code: lumoserrors.ErrRelayRateLimited, Detail: "send rate limit"}
}

func window(now int64) (start, end int64) {
	start = now - now%secondsPerMinute
	return start, start + secondsPerMinute
}

type rateWindow struct {
	start int64
	n     int
}

// TakeSend implements RateLimiter.
func (m *MemoryStore) TakeSend(_ context.Context, device registry.DeviceID, now int64, limit int) (bool, int64, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	start, end := window(now)
	w := m.rates[device]
	if w.start != start {
		w = rateWindow{start: start}
	}
	w.n++
	m.rates[device] = w
	if w.n > limit {
		return false, end - now, nil
	}
	return true, 0, nil
}

// KEYS counter; ARGV ttlMillis. Returns the count after this send.
var takeScript = redis.NewScript(`
local n = redis.call('INCR', KEYS[1])
if n == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
return n
`)

// TakeSend implements RateLimiter on the same Redis as the mailboxes.
func (r *RedisMailboxes) TakeSend(ctx context.Context, device registry.DeviceID, now int64, limit int) (bool, int64, error) {
	start, end := window(now)
	key := r.ns + "relay:rate:{" + hex.EncodeToString(device[:]) + "}:" + itoa(start)
	// The key outlives its window by a second, measured from the caller's
	// clock, and is never read after the window moves on.
	ttlMillis := (end - now + 1) * time.Second.Milliseconds()
	n, err := takeScript.Run(ctx, r.rdb, []string{key}, itoa(ttlMillis)).Int()
	if err != nil {
		return false, 0, err
	}
	if n > limit {
		return false, end - now, nil
	}
	return true, 0, nil
}
