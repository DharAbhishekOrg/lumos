// Lumos - backend/internal/relay / Envelope mailboxes on Redis.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-BCK-008, TRS-BCK-002, TRS-BCK-003, TRS-BCK-004, SRS-RMT-002, SRS-RMT-015, SRS-DAT-003
// ADRs: -
// Threading: concurrent-safe
// Notes: TRS-BCK-008 puts TTL queues in Redis. Each operation is one Lua
// script, so it runs atomically on the server: the duplicate, replay and
// quota checks and the insert cannot interleave with another sender's. All
// of a mailbox's keys share one hash tag, {<mailbox hex>}, so they live in one
// slot. Per mailbox:
//
//	q      sorted set  id -> arrival   (fetch order: submission order, WP-05 S8)
//	n      string      arrival counter (INCR in the same script as the insert)
//	x      sorted set  id -> expires   (expiry sweep)
//	s      hash        id -> size      (byte accounting)
//	b      string      bytes pending
//	e:<id> hash        the TRS-BCK-003 record: c x s k a d p
//	t:<id> hash        tombstone after ack: d x
//
// Expiry is decided by the caller's clock (now), exactly as in MemoryStore,
// so the two run one contract suite. Redis's own key expiry is set as well,
// relative to that clock: an envelope's payload key is deleted by Redis when
// its TTL ends even if no sweep ever runs, so SRS-DAT-003 does not depend on
// a background job. A mailbox that goes quiet expires whole with its last
// envelope. The deployment must use a no-eviction policy: eviction would
// drop pending envelopes silently.
package relay

import (
	"context"
	"encoding/hex"
	"errors"
	"strconv"
	"time"

	"github.com/dharabhishekorg/lumos/backend/internal/registry"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
	"github.com/redis/go-redis/v9"
)

// sweepLua removes the mailbox's envelopes whose expiry is before now.
const sweepLua = `
local function sweep(q, x, s, b, P, now)
  local gone = redis.call('ZRANGEBYSCORE', x, '-inf', '(' .. now)
  for _, g in ipairs(gone) do
    local sz = redis.call('HGET', s, g)
    if sz then redis.call('DECRBY', b, sz) end
    redis.call('HDEL', s, g)
    redis.call('ZREM', q, g)
    redis.call('ZREM', x, g)
    redis.call('DEL', P .. 'e:' .. g)
  end
  return #gone
end
`

// KEYS q x s b; ARGV P now id created expires size class proof digest payload
// quotaEnvelopes quotaBytes ttlMillis. Returns 0 stored, 1 duplicate, -1 full,
// -2 replay.
var putScript = redis.NewScript(sweepLua + `
local q, x, s, b = KEYS[1], KEYS[2], KEYS[3], KEYS[4]
local P, now, id = ARGV[1], tonumber(ARGV[2]), ARGV[3]
local size, digest = tonumber(ARGV[6]), ARGV[9]
local ttl = tonumber(ARGV[13])
sweep(q, x, s, b, P, now)
local e = P .. 'e:' .. id
if redis.call('ZSCORE', x, id) then
  local d = redis.call('HGET', e, 'd')
  if d then
    if d == digest then return 1 end
    return -2
  end
  -- Redis already expired the payload key on its own clock: drop the stub.
  local sz = redis.call('HGET', s, id)
  if sz then redis.call('DECRBY', b, sz) end
  redis.call('HDEL', s, id)
  redis.call('ZREM', q, id)
  redis.call('ZREM', x, id)
end
local t = redis.call('HMGET', P .. 't:' .. id, 'd', 'x')
if t[1] and tonumber(t[2]) >= now then
  if t[1] == digest then return 1 end
  return -2
end
local used = tonumber(redis.call('GET', b) or '0')
if redis.call('ZCARD', q) + 1 > tonumber(ARGV[11]) or used + size > tonumber(ARGV[12]) then
  return -1
end
redis.call('HSET', e, 'c', ARGV[4], 'x', ARGV[5], 's', ARGV[6], 'k', ARGV[7], 'a', ARGV[8], 'd', digest, 'p', ARGV[10])
redis.call('PEXPIRE', e, ttl)
local n = P .. 'n'
redis.call('ZADD', q, redis.call('INCR', n), id)
redis.call('ZADD', x, ARGV[5], id)
redis.call('HSET', s, id, ARGV[6])
redis.call('INCRBY', b, size)
for _, k in ipairs({q, x, s, b, n}) do
  if redis.call('PTTL', k) < ttl then redis.call('PEXPIRE', k, ttl) end
end
return 0
`)

// KEYS q x; ARGV P now limit. Returns a flat list, 8 entries per envelope:
// id c x s k a d p.
var fetchScript = redis.NewScript(`
local P, now, limit = ARGV[1], tonumber(ARGV[2]), tonumber(ARGV[3])
local out, n = {}, 0
for _, id in ipairs(redis.call('ZRANGE', KEYS[1], 0, -1)) do
  if n >= limit then break end
  local xs = redis.call('ZSCORE', KEYS[2], id)
  if xs and tonumber(xs) >= now then
    local f = redis.call('HMGET', P .. 'e:' .. id, 'c', 'x', 's', 'k', 'a', 'd', 'p')
    if f[1] then
      table.insert(out, id)
      for i = 1, 7 do table.insert(out, f[i]) end
      n = n + 1
    end
  end
end
return out
`)

// KEYS q x s b; ARGV P id... Returns how many were pending.
var ackScript = redis.NewScript(`
local q, x, s, b, P = KEYS[1], KEYS[2], KEYS[3], KEYS[4], ARGV[1]
local n = 0
for i = 2, #ARGV do
  local id = ARGV[i]
  local xs = redis.call('ZSCORE', x, id)
  if xs then
    local e = P .. 'e:' .. id
    local d = redis.call('HGET', e, 'd')
    local left = redis.call('PTTL', e)
    if d and left > 0 then
      local t = P .. 't:' .. id
      redis.call('HSET', t, 'd', d, 'x', xs)
      redis.call('PEXPIRE', t, left)
    end
    local sz = redis.call('HGET', s, id)
    if sz then redis.call('DECRBY', b, sz) end
    redis.call('HDEL', s, id)
    redis.call('ZREM', q, id)
    redis.call('ZREM', x, id)
    redis.call('DEL', e)
    n = n + 1
  end
end
return n
`)

// KEYS q x s b; ARGV P now. Returns envelopes removed.
var purgeScript = redis.NewScript(sweepLua + `
return sweep(KEYS[1], KEYS[2], KEYS[3], KEYS[4], ARGV[1], tonumber(ARGV[2]))
`)

// errCorrupt means a stored record does not have the shape this code writes.
var errCorrupt = errors.New("relay: stored envelope record is malformed")

// RedisMailboxes implements Mailboxes on Redis.
type RedisMailboxes struct {
	rdb redis.UniversalClient
	ns  string
}

// NewRedisMailboxes keeps every key under namespace (for example "lumos:").
func NewRedisMailboxes(rdb redis.UniversalClient, namespace string) *RedisMailboxes {
	return &RedisMailboxes{rdb: rdb, ns: namespace}
}

func (r *RedisMailboxes) prefix(m registry.DeviceID) string {
	return r.ns + "relay:{" + hex.EncodeToString(m[:]) + "}:"
}

func mailboxKeys(p string) []string { return []string{p + "q", p + "x", p + "s", p + "b"} }

func itoa(v int64) string { return strconv.FormatInt(v, 10) }

// Put implements Mailboxes.
func (r *RedisMailboxes) Put(ctx context.Context, e Envelope, q Quota, now int64) (PutResult, error) {
	p := r.prefix(e.Recipient)
	// Redis deletes the payload one second after the last instant it is
	// valid (ExpiresAt is inclusive), measured from the caller's now.
	ttlMillis := (e.ExpiresAt - now + 1) * time.Second.Milliseconds()
	res, err := putScript.Run(ctx, r.rdb, mailboxKeys(p),
		p, itoa(now), string(e.ID[:]), itoa(e.CreatedAt), itoa(e.ExpiresAt), strconv.Itoa(e.Size),
		strconv.Itoa(int(e.Class)), string(e.AuthProof[:]), string(e.PayloadDigest[:]), string(e.Payload),
		strconv.Itoa(q.Envelopes), strconv.Itoa(q.Bytes), itoa(ttlMillis)).Int()
	if err != nil {
		return 0, err
	}
	switch res {
	case 0:
		return Stored, nil
	case 1:
		return Duplicate, nil
	case -1:
		return 0, ErrMailboxFull
	default:
		return 0, ErrReplay
	}
}

// Fetch implements Mailboxes.
func (r *RedisMailboxes) Fetch(ctx context.Context, mailbox registry.DeviceID, now int64, limit int) ([]Envelope, error) {
	p := r.prefix(mailbox)
	keys := mailboxKeys(p)
	flat, err := fetchScript.Run(ctx, r.rdb, keys[:2], p, itoa(now), strconv.Itoa(limit)).StringSlice()
	if err != nil {
		return nil, err
	}
	const fields = 8
	if len(flat)%fields != 0 {
		return nil, errCorrupt
	}
	out := make([]Envelope, 0, len(flat)/fields)
	for i := 0; i < len(flat); i += fields {
		e, err := decodeRecord(mailbox, flat[i:i+fields])
		if err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, nil
}

func decodeRecord(mailbox registry.DeviceID, f []string) (Envelope, error) {
	e := Envelope{Recipient: mailbox, Payload: []byte(f[7])}
	if len(f[0]) != len(e.ID) || len(f[5]) != len(e.AuthProof) || len(f[6]) != len(e.PayloadDigest) {
		return Envelope{}, errCorrupt
	}
	copy(e.ID[:], f[0])
	copy(e.AuthProof[:], f[5])
	copy(e.PayloadDigest[:], f[6])
	var err error
	if e.CreatedAt, err = strconv.ParseInt(f[1], 10, 64); err != nil {
		return Envelope{}, errCorrupt
	}
	if e.ExpiresAt, err = strconv.ParseInt(f[2], 10, 64); err != nil {
		return Envelope{}, errCorrupt
	}
	if e.Size, err = strconv.Atoi(f[3]); err != nil {
		return Envelope{}, errCorrupt
	}
	class, err := strconv.ParseUint(f[4], 10, 8)
	if err != nil {
		return Envelope{}, errCorrupt
	}
	e.Class = lumosconstants.MessageClass(class) // #nosec G115 -- ParseUint bounded it to 8 bits
	return e, nil
}

// Ack implements Mailboxes.
func (r *RedisMailboxes) Ack(ctx context.Context, mailbox registry.DeviceID, ids []MessageID) (int, error) {
	if len(ids) == 0 {
		return 0, nil
	}
	p := r.prefix(mailbox)
	args := make([]any, 0, len(ids)+1)
	args = append(args, p)
	for _, id := range ids {
		args = append(args, string(id[:]))
	}
	return ackScript.Run(ctx, r.rdb, mailboxKeys(p), args...).Int()
}

// Purge implements Mailboxes: it sweeps every mailbox under the namespace.
func (r *RedisMailboxes) Purge(ctx context.Context, now int64) (int, error) {
	total := 0
	iter := r.rdb.Scan(ctx, 0, r.ns+"relay:{*}:x", 0).Iterator()
	for iter.Next(ctx) {
		key := iter.Val()
		p := key[:len(key)-1] // strip the trailing "x"
		n, err := purgeScript.Run(ctx, r.rdb, mailboxKeys(p), p, itoa(now)).Int()
		if err != nil {
			return total, err
		}
		total += n
	}
	return total, iter.Err()
}
