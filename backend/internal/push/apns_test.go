// Lumos - backend/internal/push / The APNs adapter against a fake HTTP/2 APNs.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-BCK-006, SRS-RMT-006, TRS-BCK-002, TRS-TST-001
// ADRs: -
// Threading: test-only
// Notes: The test makes its own P-256 key in PKCS#8 PEM, the format of an
// Apple .p8 file, so no real key is involved. The fake server requires
// HTTP/2, checks every header APNs uses, verifies the JWT's ES256 signature
// with the public half of that key, and checks the body is exactly the
// generic alert.
package push

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"io"
	"math/big"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// testAPNsToken is a fake device token of the real shape, built at run time
// so no high-entropy literal sits in the repository for the secret scanner.
var testAPNsToken = strings.Repeat("0a1b", 16)

func testP8(t *testing.T) ([]byte, *ecdsa.PublicKey) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	der, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	return pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}), &key.PublicKey
}

// verifyJWT checks an ES256 JWT and returns its header and claims.
func verifyJWT(t *testing.T, jwt string, pub *ecdsa.PublicKey) (map[string]any, map[string]any) {
	t.Helper()
	parts := strings.Split(jwt, ".")
	if len(parts) != 3 {
		t.Fatalf("not a JWT: %d parts", len(parts))
	}
	sig, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil || len(sig) != 2*apnsCoordBytes {
		t.Fatalf("ES256 signatures are 64 raw bytes, got %d (%v)", len(sig), err)
	}
	digest := sha256.Sum256([]byte(parts[0] + "." + parts[1]))
	r := new(big.Int).SetBytes(sig[:apnsCoordBytes])
	s := new(big.Int).SetBytes(sig[apnsCoordBytes:])
	if !ecdsa.Verify(pub, digest[:], r, s) {
		t.Fatal("the provider token's signature does not verify with the .p8 key")
	}
	var h, c map[string]any
	hb, _ := base64.RawURLEncoding.DecodeString(parts[0])
	cb, _ := base64.RawURLEncoding.DecodeString(parts[1])
	if json.Unmarshal(hb, &h) != nil || json.Unmarshal(cb, &c) != nil {
		t.Fatal("JWT parts are not JSON")
	}
	return h, c
}

type apnsFixture struct {
	apns     *APNs
	pub      *ecdsa.PublicKey
	clock    time.Time
	status   atomic.Int32
	reason   atomic.Value // string
	lastJWT  atomic.Value // string
	requests atomic.Int32
}

func newAPNsFixture(t *testing.T) *apnsFixture {
	t.Helper()
	f := &apnsFixture{clock: time.Unix(1_700_000_000, 0)}
	f.status.Store(http.StatusOK)
	f.reason.Store("")
	p8, pub := testP8(t)
	f.pub = pub
	srv := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.requests.Add(1)
		if r.ProtoMajor != 2 {
			t.Errorf("APNs requires HTTP/2, got %s", r.Proto)
		}
		if r.Method != http.MethodPost || r.URL.Path != "/3/device/"+testAPNsToken {
			t.Errorf("bad request %s %s", r.Method, r.URL.Path)
		}
		for h, want := range map[string]string{
			"apns-topic": "com.lumos.app", "apns-push-type": "alert",
			"apns-priority": "10", "apns-collapse-id": "lumos-wake",
		} {
			if got := r.Header.Get(h); got != want {
				t.Errorf("%s: want %q, got %q", h, want, got)
			}
		}
		auth := r.Header.Get("Authorization")
		if !strings.HasPrefix(auth, "bearer ") {
			t.Errorf("authorization must be a bearer provider token, got %q", auth)
		}
		f.lastJWT.Store(strings.TrimPrefix(auth, "bearer "))
		body, _ := io.ReadAll(r.Body)
		if string(body) != apnsWake {
			t.Errorf("the APNs body must be exactly the generic alert, got %s", body)
		}
		w.WriteHeader(int(f.status.Load()))
		if reason := f.reason.Load().(string); reason != "" {
			_, _ = io.WriteString(w, `{"reason":"`+reason+`"}`)
		}
	}))
	srv.EnableHTTP2 = true
	srv.StartTLS()
	t.Cleanup(srv.Close)
	a, err := NewAPNs(APNsConfig{KeyPEM: p8, KeyID: "KEY1234567", TeamID: "TEAM123456", Topic: "com.lumos.app"}, srv.Client())
	if err != nil {
		t.Fatal(err)
	}
	if a.base != apnsSandbox {
		t.Fatal("without Production the sandbox host is used")
	}
	a.base = srv.URL
	a.now = func() time.Time { return f.clock }
	f.apns = a
	return f
}

func TestAPNsSendsTheGenericAlertOverHTTP2WithASignedProviderToken(t *testing.T) {
	f := newAPNsFixture(t)
	if err := f.apns.Send(context.Background(), testAPNsToken); err != nil {
		t.Fatal(err)
	}
	h, c := verifyJWT(t, f.lastJWT.Load().(string), f.pub)
	if h["alg"] != "ES256" || h["kid"] != "KEY1234567" {
		t.Fatalf("JWT header must name ES256 and the key id: %v", h)
	}
	if c["iss"] != "TEAM123456" || c["iat"] != float64(1_700_000_000) {
		t.Fatalf("JWT claims must be the team id and issue time: %v", c)
	}
}

func TestTheProviderTokenIsReusedThenRenewed(t *testing.T) {
	f := newAPNsFixture(t)
	ctx := context.Background()
	_ = f.apns.Send(ctx, testAPNsToken)
	first := f.lastJWT.Load().(string)
	f.clock = f.clock.Add(apnsJWTReuse - time.Second)
	_ = f.apns.Send(ctx, testAPNsToken)
	if f.lastJWT.Load().(string) != first {
		t.Fatal("Apple refuses refreshes more often than every 20 minutes: the token must be reused")
	}
	f.clock = f.clock.Add(2 * time.Second)
	_ = f.apns.Send(ctx, testAPNsToken)
	if f.lastJWT.Load().(string) == first {
		t.Fatal("a token older than the reuse window must be replaced before Apple's 60-minute expiry")
	}
	// An expired-token rejection forces a new one at once.
	f.status.Store(http.StatusForbidden)
	f.reason.Store("ExpiredProviderToken")
	second := f.lastJWT.Load().(string)
	f.clock = f.clock.Add(time.Second)
	_ = f.apns.Send(ctx, testAPNsToken)
	f.status.Store(http.StatusOK)
	f.reason.Store("")
	_ = f.apns.Send(ctx, testAPNsToken)
	if f.lastJWT.Load().(string) == second {
		t.Fatal("after ExpiredProviderToken the next send signs a new token")
	}
}

func TestAPNsReportsDeadTokensAsGoneAndOutagesAsErrors(t *testing.T) {
	f := newAPNsFixture(t)
	ctx := context.Background()
	for _, c := range []struct {
		status int
		reason string
	}{{http.StatusGone, "Unregistered"}, {http.StatusBadRequest, "BadDeviceToken"}, {http.StatusBadRequest, "DeviceTokenNotForTopic"}} {
		f.status.Store(int32(c.status))
		f.reason.Store(c.reason)
		if err := f.apns.Send(ctx, testAPNsToken); !errors.Is(err, ErrTokenGone) {
			t.Fatalf("%d %s means the token is gone, got %v", c.status, c.reason, err)
		}
	}
	f.status.Store(http.StatusServiceUnavailable)
	f.reason.Store("ServiceUnavailable")
	err := f.apns.Send(ctx, testAPNsToken)
	if err == nil || errors.Is(err, ErrTokenGone) || strings.Contains(err.Error(), testAPNsToken) {
		t.Fatalf("an outage is an error that never names the token: %v", err)
	}
}

func TestAnUnreachableAPNsErrorNeverCarriesTheTokenFromTheURL(t *testing.T) {
	p8, _ := testP8(t)
	a, err := NewAPNs(APNsConfig{KeyPEM: p8, KeyID: "K", TeamID: "T", Topic: "com.lumos.app"}, &http.Client{Timeout: time.Second})
	if err != nil {
		t.Fatal(err)
	}
	a.base = "https://127.0.0.1:1"
	err = a.Send(context.Background(), testAPNsToken)
	if err == nil || strings.Contains(err.Error(), testAPNsToken) {
		t.Fatalf("a transport error must not echo the URL, which holds the device token: %v", err)
	}
}

func TestABadKeyFailsAtStartup(t *testing.T) {
	rsaLike := pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: []byte("not a key")})
	for name, cfg := range map[string]APNsConfig{
		"not PEM":    {KeyPEM: []byte("nope"), KeyID: "K", TeamID: "T", Topic: "x"},
		"not PKCS#8": {KeyPEM: rsaLike, KeyID: "K", TeamID: "T", Topic: "x"},
		"no key id":  {KeyPEM: rsaLike, TeamID: "T", Topic: "x"},
	} {
		if _, err := NewAPNs(cfg, http.DefaultClient); err == nil {
			t.Errorf("%s: an unusable APNs configuration must fail at startup", name)
		}
	}
}
