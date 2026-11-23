// Lumos - backend/integration / The composed staging backend, driven over HTTP like a phone would.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-TST-006, TRS-BCK-001, TRS-BCK-003, TRS-BCK-004, SRS-RMT-003, SRS-RMT-015, SRS-RMT-016, SRS-DAT-003, TRS-CRY-005
// ADRs: ADR-001
// Threading: test-only
// Notes: WP-05 S8. TRS-TST-006 against the REAL stack: the lumosd image,
// PostgreSQL and Redis from deploy/staging/docker-compose.yml, reached only
// over HTTP. The suite is black-box on purpose: it defines its own request
// shapes instead of importing the server's, so a contract drift fails here.
// Every test enrols its own fresh devices, so tests are independent.
//
// Scenarios: offline queue, TTL expiry (enforced by Redis, the envelope's key
// shortened through the test overlay's loopback Redis port), replay refusal,
// duplicate stored once, block (the sender is told nothing), atomic one-time
// prekey claims under concurrency, the rate limit, local-only classes refused,
// device revocation, and mail surviving a restart of lumosd and Redis (two
// phases the CI job runs either side of `docker compose restart`).
//
// Without LUMOS_STAGING_URL every test skips; CI sets LUMOS_REQUIRE_STAGING=1,
// which turns the skips into failures, as for the PostgreSQL and Redis tests.
package integration_test

import (
	"bytes"
	"context"
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/dharabhishekorg/lumos/protocol/crypto/x3dh"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumoserrors"
	"github.com/redis/go-redis/v9"
)

const deviceAuthDomain = lumosconstants.DeviceAuthSigningInfoValue

func stagingURL(t *testing.T) string {
	t.Helper()
	u := os.Getenv("LUMOS_STAGING_URL")
	if u == "" {
		if os.Getenv("LUMOS_REQUIRE_STAGING") == "1" {
			t.Fatal("LUMOS_REQUIRE_STAGING=1 but LUMOS_STAGING_URL is empty")
		}
		t.Skip("no LUMOS_STAGING_URL")
	}
	return strings.TrimRight(u, "/")
}

// device is one enrolled phone, as the registry sees it.
type device struct {
	t          *testing.T
	base       string
	routingID  string
	credential string
	header     http.Header
}

func (d *device) do(method, path string, body any) (int, []byte) {
	d.t.Helper()
	var rd *bytes.Reader
	if body == nil {
		rd = bytes.NewReader(nil)
	} else {
		b, err := json.Marshal(body)
		if err != nil {
			d.t.Fatal(err)
		}
		rd = bytes.NewReader(b)
	}
	req, err := http.NewRequest(method, d.base+path, rd)
	if err != nil {
		d.t.Fatal(err)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if d.credential != "" {
		req.Header.Set("Authorization", "Bearer "+d.credential)
	}
	resp, err := (&http.Client{Timeout: 20 * time.Second}).Do(req)
	if err != nil {
		d.t.Fatal(err)
	}
	defer resp.Body.Close()
	d.header = resp.Header
	var buf bytes.Buffer
	if _, err := buf.ReadFrom(resp.Body); err != nil {
		d.t.Fatal(err)
	}
	return resp.StatusCode, buf.Bytes()
}

func randBytes(t *testing.T, n int) []byte {
	t.Helper()
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		t.Fatal(err)
	}
	return b
}

// enrol registers a fresh device key by challenge proof (TRS-BCK-007).
func enrol(t *testing.T) *device {
	t.Helper()
	d := &device{t: t, base: stagingURL(t)}
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	status, body := d.do("POST", "/v1/auth/challenge", nil)
	if status != http.StatusOK {
		t.Fatalf("challenge: %d %s", status, body)
	}
	var ch struct{ Challenge string }
	if err := json.Unmarshal(body, &ch); err != nil {
		t.Fatal(err)
	}
	chal, _ := hex.DecodeString(ch.Challenge)
	msg := append(append([]byte(deviceAuthDomain), chal...), pub...)
	status, body = d.do("POST", "/v1/devices", map[string]string{
		"challenge": ch.Challenge, "device_key": hex.EncodeToString(pub),
		"signature": hex.EncodeToString(ed25519.Sign(priv, msg)),
	})
	if status != http.StatusCreated {
		t.Fatalf("register: %d %s", status, body)
	}
	var reg struct {
		RoutingID  string `json:"routing_id"`
		Credential string `json:"credential"`
	}
	if err := json.Unmarshal(body, &reg); err != nil {
		t.Fatal(err)
	}
	d.routingID, d.credential = reg.RoutingID, reg.Credential
	return d
}

// allow makes owner accept a fresh capability and returns it, hex.
func allow(t *testing.T, owner *device) string {
	t.Helper()
	capability := randBytes(t, 32)
	proof := sha256.Sum256(capability)
	if status, body := owner.do("PUT", "/v1/mailbox/capabilities",
		map[string]string{"proof": hex.EncodeToString(proof[:])}); status != http.StatusNoContent {
		t.Fatalf("allow: %d %s", status, body)
	}
	return hex.EncodeToString(capability)
}

func send(from, to *device, id []byte, class lumosconstants.MessageClass, capability string, payload []byte) (int, []byte) {
	return from.do("POST", "/v1/envelopes", map[string]any{
		"recipient": to.routingID, "message_id": hex.EncodeToString(id), "class": int(class),
		"capability": capability, "payload": base64.StdEncoding.EncodeToString(payload),
	})
}

type envelope struct {
	MessageID   string `json:"message_id"`
	Class       int    `json:"class"`
	SenderProof string `json:"sender_proof"`
	CreatedAt   int64  `json:"created_at"`
	ExpiresAt   int64  `json:"expires_at"`
	Payload     string `json:"payload"`
}

func fetch(t *testing.T, d *device) []envelope {
	t.Helper()
	status, body := d.do("GET", "/v1/envelopes?limit=100", nil)
	if status != http.StatusOK {
		t.Fatalf("fetch: %d %s", status, body)
	}
	var out struct{ Envelopes []envelope }
	if err := json.Unmarshal(body, &out); err != nil {
		t.Fatal(err)
	}
	return out.Envelopes
}

func ack(t *testing.T, d *device, ids ...string) int {
	t.Helper()
	status, body := d.do("POST", "/v1/envelopes/ack", map[string]any{"message_ids": ids})
	if status != http.StatusOK {
		t.Fatalf("ack: %d %s", status, body)
	}
	var out struct{ Acknowledged int }
	_ = json.Unmarshal(body, &out)
	return out.Acknowledged
}

func codeOf(body []byte) string {
	var e struct{ Code string }
	_ = json.Unmarshal(body, &e)
	return e.Code
}

func TestTheStagingBackendRunsThisBuildsRegister(t *testing.T) {
	d := &device{t: t, base: stagingURL(t)}
	status, body := d.do("GET", "/healthz", nil)
	if status != http.StatusOK {
		t.Fatalf("health: %d %s", status, body)
	}
	var h struct {
		Status        string `json:"status"`
		Environment   string `json:"environment"`
		ConstantsHash string `json:"constants_hash"`
	}
	if err := json.Unmarshal(body, &h); err != nil {
		t.Fatal(err)
	}
	if h.Status != "ok" || h.Environment != "staging" {
		t.Fatalf("health = %+v, want ok/staging", h)
	}
	if h.ConstantsHash != lumosconstants.ConstantsHash {
		t.Fatalf("the deployed register %s is not this build's %s", h.ConstantsHash, lumosconstants.ConstantsHash)
	}
}

func TestTheOfflineQueueWaitsInOrderUntilFetchedAndAcknowledged(t *testing.T) {
	alice, bob := enrol(t), enrol(t)
	capability := allow(t, bob)
	var ids []string
	var payloads []string
	for i := range 5 {
		id, payload := randBytes(t, 16), randBytes(t, 40+i)
		if status, body := send(alice, bob, id, lumosconstants.MessageClassTextMessage, capability, payload); status != http.StatusAccepted {
			t.Fatalf("send %d: %d %s", i, status, body)
		}
		ids = append(ids, hex.EncodeToString(id))
		payloads = append(payloads, base64.StdEncoding.EncodeToString(payload))
	}
	got := fetch(t, bob)
	if len(got) != 5 {
		t.Fatalf("bob fetched %d envelopes, want the 5 queued while he was away", len(got))
	}
	for i, e := range got {
		if e.MessageID != ids[i] || e.Payload != payloads[i] {
			t.Fatalf("envelope %d is not the %d-th sent, byte for byte", i, i)
		}
		if want := int64(lumosconstants.RelayEnvelopeTTLDaysDefault) * 24 * 3600; e.ExpiresAt-e.CreatedAt != want {
			t.Fatalf("expiry window %d s, want the register's %d", e.ExpiresAt-e.CreatedAt, want)
		}
	}
	if n := ack(t, bob, ids...); n != 5 {
		t.Fatalf("acknowledged %d, want 5", n)
	}
	if left := fetch(t, bob); len(left) != 0 {
		t.Fatalf("%d envelopes outlived their acknowledgement (SRS-RMT-015)", len(left))
	}
}

func TestAnExpiredEnvelopeIsNeverServed(t *testing.T) {
	addr := os.Getenv("LUMOS_STAGING_REDIS_ADDR")
	stagingURL(t)
	if addr == "" {
		if os.Getenv("LUMOS_REQUIRE_STAGING") == "1" {
			t.Fatal("LUMOS_REQUIRE_STAGING=1 but LUMOS_STAGING_REDIS_ADDR is empty")
		}
		t.Skip("no LUMOS_STAGING_REDIS_ADDR (the test overlay publishes it)")
	}
	alice, bob := enrol(t), enrol(t)
	capability := allow(t, bob)
	expiring, kept := randBytes(t, 16), randBytes(t, 16)
	for _, id := range [][]byte{expiring, kept} {
		if status, body := send(alice, bob, id, lumosconstants.MessageClassTextMessage, capability, randBytes(t, 32)); status != http.StatusAccepted {
			t.Fatalf("send: %d %s", status, body)
		}
	}
	// 30 days pass for one envelope: Redis's own expiry deletes its record.
	rdb := redis.NewClient(&redis.Options{Addr: addr})
	defer rdb.Close()
	ctx := context.Background()
	var found []string
	iter := rdb.Scan(ctx, 0, "lumos:relay:*:e:*", 1000).Iterator()
	for iter.Next(ctx) {
		if strings.HasSuffix(iter.Val(), ":e:"+string(expiring)) {
			found = append(found, iter.Val())
		}
	}
	if err := iter.Err(); err != nil {
		t.Fatal(err)
	}
	if len(found) != 1 {
		t.Fatalf("found %d records for the envelope, want 1", len(found))
	}
	if err := rdb.PExpire(ctx, found[0], time.Millisecond).Err(); err != nil {
		t.Fatal(err)
	}
	time.Sleep(50 * time.Millisecond)
	got := fetch(t, bob)
	if len(got) != 1 || got[0].MessageID != hex.EncodeToString(kept) {
		t.Fatalf("fetched %d envelopes; the expired one must never be served and the other must be (SRS-DAT-003)", len(got))
	}
}

func TestAReplayIsRefusedAndADuplicateIsStoredOnce(t *testing.T) {
	alice, bob := enrol(t), enrol(t)
	capability := allow(t, bob)
	id, payload := randBytes(t, 16), randBytes(t, 64)
	for range 2 {
		if status, body := send(alice, bob, id, lumosconstants.MessageClassTextMessage, capability, payload); status != http.StatusAccepted {
			t.Fatalf("a retry of the same envelope must be the same success: %d %s", status, body)
		}
	}
	status, body := send(alice, bob, id, lumosconstants.MessageClassTextMessage, capability, randBytes(t, 64))
	if status != http.StatusBadRequest || codeOf(body) != lumoserrors.ErrCryptoReplay {
		t.Fatalf("same id, other bytes: %d %s, want 400 %s", status, body, lumoserrors.ErrCryptoReplay)
	}
	got := fetch(t, bob)
	if len(got) != 1 || got[0].Payload != base64.StdEncoding.EncodeToString(payload) {
		t.Fatalf("bob has %d envelopes, want the original once (TRS-BCK-004)", len(got))
	}
	ack(t, bob, got[0].MessageID)
	_, _ = send(alice, bob, id, lumosconstants.MessageClassTextMessage, capability, payload)
	if again := fetch(t, bob); len(again) != 0 {
		t.Fatal("an acknowledged envelope came back on a resend: the tombstone must hold it")
	}
}

func TestABlockedSenderIsToldNothingAndNothingArrives(t *testing.T) {
	alice, bob := enrol(t), enrol(t)
	capability := allow(t, bob)
	raw, _ := hex.DecodeString(capability)
	proof := sha256.Sum256(raw)
	if status, body := bob.do("DELETE", "/v1/mailbox/capabilities/"+hex.EncodeToString(proof[:]), nil); status != http.StatusNoContent {
		t.Fatalf("revoke: %d %s", status, body)
	}
	for _, c := range []string{capability, hex.EncodeToString(randBytes(t, 32))} {
		if status, body := send(alice, bob, randBytes(t, 16), lumosconstants.MessageClassTextMessage, c, randBytes(t, 32)); status != http.StatusAccepted {
			t.Fatalf("a blocked or unknown sender must get the same 202: %d %s (SRS-MAT-003)", status, body)
		}
	}
	if got := fetch(t, bob); len(got) != 0 {
		t.Fatalf("%d envelopes from a blocked sender arrived (SRS-RMT-016)", len(got))
	}
}

func publish(t *testing.T, owner *device, oneTime int) {
	t.Helper()
	_, identity, _ := ed25519.GenerateKey(rand.Reader)
	idDH, _ := ecdh.X25519().GenerateKey(rand.Reader)
	spk, _ := ecdh.X25519().GenerateKey(rand.Reader)
	var keys []map[string]any
	for i := range oneTime {
		k, _ := ecdh.X25519().GenerateKey(rand.Reader)
		keys = append(keys, map[string]any{"key_id": 1000 + i, "public_key": hex.EncodeToString(k.PublicKey().Bytes())})
	}
	status, body := owner.do("PUT", "/v1/prekeys", map[string]any{
		"bundle": map[string]any{
			"identity_key":    hex.EncodeToString(identity.Public().(ed25519.PublicKey)),
			"identity_dh":     hex.EncodeToString(idDH.PublicKey().Bytes()),
			"identity_dh_sig": hex.EncodeToString(x3dh.SignIdentityDH(identity, idDH.PublicKey())),
			"spk_id":          1,
			"spk":             hex.EncodeToString(spk.PublicKey().Bytes()),
			"spk_sig":         hex.EncodeToString(x3dh.SignPrekey(identity, 1, spk.PublicKey())),
		},
		"one_time": keys,
	})
	if status != http.StatusNoContent {
		t.Fatalf("publish: %d %s", status, body)
	}
}

func TestOneTimePrekeysAreClaimedAtomicallyUnderConcurrency(t *testing.T) {
	const stock, claimers = 10, 25
	bob := enrol(t)
	publish(t, bob, stock)
	devices := make([]*device, claimers)
	for i := range devices {
		devices[i] = enrol(t)
	}
	var mu sync.Mutex
	issued := map[int64]int{}
	without := 0
	var wg sync.WaitGroup
	for _, d := range devices {
		wg.Add(1)
		go func(d *device) {
			defer wg.Done()
			status, body := d.do("POST", "/v1/prekeys/"+bob.routingID+"/claim", nil)
			if status != http.StatusOK {
				t.Errorf("claim: %d %s", status, body)
				return
			}
			var r struct {
				OneTime *struct {
					KeyID int64 `json:"key_id"`
				} `json:"one_time"`
			}
			_ = json.Unmarshal(body, &r)
			mu.Lock()
			defer mu.Unlock()
			if r.OneTime == nil {
				without++
			} else {
				issued[r.OneTime.KeyID]++
			}
		}(d)
	}
	wg.Wait()
	for id, n := range issued {
		if n != 1 {
			t.Fatalf("one-time prekey %d was issued %d times (TRS-CRY-005)", id, n)
		}
	}
	if len(issued) != stock || without != claimers-stock {
		t.Fatalf("%d issued once and %d bundles without one; want %d and %d", len(issued), without, stock, claimers-stock)
	}
}

func TestASenderOverTheRateGets429WithRetryAfter(t *testing.T) {
	alice, bob := enrol(t), enrol(t)
	capability := allow(t, bob)
	for i := 0; i <= lumosconstants.RelaySendRatePerMinDefault+5; i++ {
		status, body := send(alice, bob, randBytes(t, 16), lumosconstants.MessageClassTextMessage, capability, randBytes(t, 16))
		if status == http.StatusTooManyRequests {
			if codeOf(body) != lumoserrors.ErrRelayRateLimited || alice.header.Get("Retry-After") == "" {
				t.Fatalf("429 without its code or Retry-After: %s", body)
			}
			if i < lumosconstants.RelaySendRatePerMinDefault {
				t.Fatalf("limited after %d sends, before the register's %d a minute", i, lumosconstants.RelaySendRatePerMinDefault)
			}
			return
		}
		if status != http.StatusAccepted {
			t.Fatalf("send %d: %d %s", i, status, body)
		}
	}
	t.Fatalf("never limited after %d sends", lumosconstants.RelaySendRatePerMinDefault+5)
}

func TestLocalOnlyClassesNeverTouchTheRelay(t *testing.T) {
	alice, bob := enrol(t), enrol(t)
	capability := allow(t, bob)
	for _, class := range []lumosconstants.MessageClass{
		lumosconstants.MessageClassObjectChunk, lumosconstants.MessageClassVoiceMeta, lumosconstants.MessageClassRelayRoute,
	} {
		status, body := send(alice, bob, randBytes(t, 16), class, capability, randBytes(t, 16))
		if status != http.StatusBadRequest || codeOf(body) != lumoserrors.ErrTransportLocalOnlyViolation {
			t.Fatalf("class %d: %d %s, want 400 %s (SRS-TRN-009)", class, status, body,
				lumoserrors.ErrTransportLocalOnlyViolation)
		}
	}
	if got := fetch(t, bob); len(got) != 0 {
		t.Fatal("a local-only class reached a mailbox")
	}
}

func TestARevokedDeviceLosesItsCredentialAndItsBundle(t *testing.T) {
	alice, bob := enrol(t), enrol(t)
	publish(t, bob, 1)
	if status, body := bob.do("DELETE", "/v1/devices/self", nil); status != http.StatusNoContent {
		t.Fatalf("revoke self: %d %s", status, body)
	}
	if status, _ := bob.do("GET", "/v1/prekeys/status", nil); status != http.StatusUnauthorized {
		t.Fatalf("a revoked credential answered %d, want 401", status)
	}
	if status, _ := alice.do("POST", "/v1/prekeys/"+bob.routingID+"/claim", nil); status != http.StatusNotFound {
		t.Fatalf("a revoked device's bundle answered %d, want 404 (SRS-IDN-008)", status)
	}
}

// restartState is what the "before" phase leaves for the "after" phase.
type restartState struct {
	Base       string   `json:"base"`
	RoutingID  string   `json:"routing_id"`
	Credential string   `json:"credential"`
	IDs        []string `json:"ids"`
}

func restartPhase(t *testing.T, want string) string {
	t.Helper()
	stagingURL(t)
	if os.Getenv("LUMOS_STAGING_PHASE") != want {
		t.Skip("restart phase " + want + " runs only when LUMOS_STAGING_PHASE=" + want)
	}
	path := os.Getenv("LUMOS_STAGING_STATE")
	if path == "" {
		t.Fatal("LUMOS_STAGING_STATE names the file the two phases share")
	}
	return path
}

func TestQueuedMailSurvivesARestartBefore(t *testing.T) {
	path := restartPhase(t, "before")
	alice, bob := enrol(t), enrol(t)
	capability := allow(t, bob)
	st := restartState{Base: bob.base, RoutingID: bob.routingID, Credential: bob.credential}
	for range 3 {
		id := randBytes(t, 16)
		if status, body := send(alice, bob, id, lumosconstants.MessageClassTextMessage, capability, randBytes(t, 32)); status != http.StatusAccepted {
			t.Fatalf("send: %d %s", status, body)
		}
		st.IDs = append(st.IDs, hex.EncodeToString(id))
	}
	b, _ := json.Marshal(st)
	if err := os.WriteFile(path, b, 0o600); err != nil {
		t.Fatal(err)
	}
}

func TestQueuedMailSurvivesARestartAfter(t *testing.T) {
	path := restartPhase(t, "after")
	b, err := os.ReadFile(path) // #nosec G304 -- the path is the CI job's own state file (LUMOS_STAGING_STATE)
	if err != nil {
		t.Fatal(err)
	}
	var st restartState
	if err := json.Unmarshal(b, &st); err != nil {
		t.Fatal(err)
	}
	bob := &device{t: t, base: st.Base, routingID: st.RoutingID, credential: st.Credential}
	got := fetch(t, bob)
	if len(got) != len(st.IDs) {
		t.Fatalf("%d of %d queued envelopes survived restarting lumosd and Redis", len(got), len(st.IDs))
	}
	for i, e := range got {
		if e.MessageID != st.IDs[i] {
			t.Fatalf("envelope %d changed across the restart", i)
		}
	}
	if n := ack(t, bob, st.IDs...); n != len(st.IDs) {
		t.Fatalf("acknowledged %d, want %d", n, len(st.IDs))
	}
}
