// Lumos - backend/internal/push / The APNs HTTP/2 adapter with token-based (.p8) authentication.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-BCK-006, SRS-RMT-006, TRS-BCK-002
// ADRs: -
// Threading: concurrent-safe
// Notes: The notification is fixed: an alert whose text is a localisation key,
// LUMOS_PUSH_WAKE, so the words are the app's own generic text and Apple sees
// none (SRS-RMT-006). mutable-content lets the app's extension adjust it on the
// device, and one collapse id merges a burst into one alert. The provider
// token is an ES256 JWT signed with the owner's .p8 key, read once from a file
// the deployment mounts from Secret Manager; the key never enters the
// repository or a log. Apple accepts a JWT for an hour and rejects refreshing
// more than once per 20 minutes, so it is reused for 40. Go's HTTP client
// speaks HTTP/2 over TLS, which APNs requires. The adapter is complete and
// tested against a fake HTTP/2 server; real delivery needs the owner's key,
// which is an owner-executed step.
package push

import (
	"bytes"
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
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"sync"
	"time"
)

const (
	apnsSandbox    = "https://api.sandbox.push.apple.com"
	apnsProduction = "https://api.push.apple.com"
	// apnsJWTReuse sits between Apple's 20-minute refresh floor and its
	// 60-minute expiry.
	apnsJWTReuse = 40 * time.Minute
	// apnsCoordBytes is the size of one P-256 coordinate in a JOSE ES256
	// signature (RFC 7518 Sec. 3.4).
	apnsCoordBytes = 32
)

// apnsWake is the whole notification body.
const apnsWake = `{"aps":{"alert":{"loc-key":"LUMOS_PUSH_WAKE"},"sound":"default","mutable-content":1}}`

// APNs sends wakes through the Apple Push Notification service.
type APNs struct {
	base   string
	topic  string
	keyID  string
	teamID string
	key    *ecdsa.PrivateKey
	client *http.Client
	now    func() time.Time

	mu       sync.Mutex
	jwt      string
	issuedAt time.Time
}

// APNsConfig names the owner's key and the app.
type APNsConfig struct {
	KeyPEM     []byte // the .p8 file's contents
	KeyID      string
	TeamID     string
	Topic      string // the app's bundle id
	Production bool
}

// NewAPNs parses the key; an unusable key is an error at startup, not at the
// first message.
func NewAPNs(cfg APNsConfig, client *http.Client) (*APNs, error) {
	if cfg.KeyID == "" || cfg.TeamID == "" || cfg.Topic == "" {
		return nil, errors.New("push: apns needs a key id, team id and topic")
	}
	block, _ := pem.Decode(cfg.KeyPEM)
	if block == nil {
		return nil, errors.New("push: apns key is not PEM")
	}
	parsed, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, errors.New("push: apns key is not a PKCS#8 private key")
	}
	key, ok := parsed.(*ecdsa.PrivateKey)
	if !ok || key.Curve != elliptic.P256() {
		return nil, errors.New("push: apns key is not a P-256 key")
	}
	base := apnsSandbox
	if cfg.Production {
		base = apnsProduction
	}
	return &APNs{base: base, topic: cfg.Topic, keyID: cfg.KeyID, teamID: cfg.TeamID,
		key: key, client: client, now: time.Now}, nil
}

func b64(b []byte) string { return base64.RawURLEncoding.EncodeToString(b) }

// providerToken returns the cached JWT or signs a new one.
func (a *APNs) providerToken() (string, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	now := a.now()
	if a.jwt != "" && now.Sub(a.issuedAt) < apnsJWTReuse {
		return a.jwt, nil
	}
	header, err := json.Marshal(map[string]string{"alg": "ES256", "kid": a.keyID})
	if err != nil {
		return "", err
	}
	claims, err := json.Marshal(map[string]any{"iss": a.teamID, "iat": now.Unix()})
	if err != nil {
		return "", err
	}
	signing := b64(header) + "." + b64(claims)
	digest := sha256.Sum256([]byte(signing))
	r, s, err := ecdsa.Sign(rand.Reader, a.key, digest[:])
	if err != nil {
		return "", err
	}
	sig := make([]byte, 2*apnsCoordBytes)
	r.FillBytes(sig[:apnsCoordBytes])
	s.FillBytes(sig[apnsCoordBytes:])
	a.jwt, a.issuedAt = signing+"."+b64(sig), now
	return a.jwt, nil
}

func (a *APNs) forgetToken() {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.jwt = ""
}

// Send implements Sender.
func (a *APNs) Send(ctx context.Context, token string) error {
	jwt, err := a.providerToken()
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		a.base+"/3/device/"+url.PathEscape(token), bytes.NewReader([]byte(apnsWake)))
	if err != nil {
		return errors.New("push: apns request could not be built")
	}
	req.Header.Set("Authorization", "bearer "+jwt)
	req.Header.Set("apns-topic", a.topic)
	req.Header.Set("apns-push-type", "alert")
	req.Header.Set("apns-priority", strconv.Itoa(apnsPriorityImmediate))
	req.Header.Set("apns-collapse-id", collapseKey)
	req.Header.Set("Content-Type", "application/json")
	resp, err := a.client.Do(req)
	if err != nil {
		// The URL holds the device token: never let it into the error.
		return fmt.Errorf("push: apns unreachable: %w", redactURL(err))
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusOK {
		return nil
	}
	var reply struct {
		Reason string `json:"reason"`
	}
	_ = json.NewDecoder(io.LimitReader(resp.Body, maxProviderBody)).Decode(&reply)
	switch {
	case resp.StatusCode == http.StatusGone,
		reply.Reason == "BadDeviceToken", reply.Reason == "DeviceTokenNotForTopic", reply.Reason == "Unregistered":
		return ErrTokenGone
	case reply.Reason == "ExpiredProviderToken", reply.Reason == "InvalidProviderToken":
		a.forgetToken()
	}
	return fmt.Errorf("push: apns status %d", resp.StatusCode)
}

// apnsPriorityImmediate is APNs priority 10: deliver now.
const apnsPriorityImmediate = 10
