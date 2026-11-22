// Lumos - backend/internal/api / Two devices meet through the registry over HTTP.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-BCK-001, TRS-BCK-002, TRS-BCK-005, TRS-BCK-007, TRS-CRY-005, TRS-TST-001
// ADRs: ADR-001
// Threading: test-only
// Notes: The protocol integration test TRS-BCK-005 names: Bob registers with a
// key proof and publishes a signed bundle plus one-time prekeys; Alice
// registers, claims Bob's bundle over HTTP and runs X3DH against it; Bob runs
// the responder side with the one-time prekey the server handed out - and both
// arrive at the same secret. Then the guarantees at the HTTP edge: a second
// claim never repeats the one-time prekey, a missing or bad credential is one
// 401 code, an unknown routing id is the same 404 as a revoked one, and no
// response ever carries a private key or the stored credential hash.
package api

import (
	"bytes"
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/dharabhishekorg/lumos/backend/internal/registry"
	"github.com/dharabhishekorg/lumos/protocol/crypto/x3dh"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumoserrors"
)

type client struct {
	t          *testing.T
	srv        *httptest.Server
	routingID  string
	credential string
	lastHeader http.Header // response headers of the most recent do
}

func (c *client) do(method, path string, body any) (int, []byte) {
	c.t.Helper()
	var rd *bytes.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			c.t.Fatal(err)
		}
		rd = bytes.NewReader(b)
	} else {
		rd = bytes.NewReader(nil)
	}
	req, err := http.NewRequest(method, c.srv.URL+path, rd)
	if err != nil {
		c.t.Fatal(err)
	}
	if c.credential != "" {
		req.Header.Set("Authorization", "Bearer "+c.credential)
	}
	resp, err := c.srv.Client().Do(req)
	if err != nil {
		c.t.Fatal(err)
	}
	defer resp.Body.Close()
	c.lastHeader = resp.Header
	var buf bytes.Buffer
	if _, err := buf.ReadFrom(resp.Body); err != nil {
		c.t.Fatal(err)
	}
	return resp.StatusCode, buf.Bytes()
}

func newServer(t *testing.T) *httptest.Server {
	t.Helper()
	svc := registry.NewService(registry.NewMemoryRepository(), func() int64 { return 1_700_000_000 }, rand.Reader)
	mux := http.NewServeMux()
	NewRegistry(svc).Register(mux)
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return srv
}

// enrol registers a fresh device key over HTTP.
func enrol(t *testing.T, srv *httptest.Server) *client {
	t.Helper()
	c := &client{t: t, srv: srv}
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	status, body := c.do("POST", "/v1/auth/challenge", nil)
	if status != http.StatusOK {
		t.Fatalf("challenge: %d %s", status, body)
	}
	var ch struct{ Challenge string }
	_ = json.Unmarshal(body, &ch)
	chal, _ := hex.DecodeString(ch.Challenge)
	var c32, p32 [32]byte
	copy(c32[:], chal)
	copy(p32[:], pub)
	status, body = c.do("POST", "/v1/devices", map[string]string{
		"challenge":  ch.Challenge,
		"device_key": hex.EncodeToString(pub),
		"signature":  hex.EncodeToString(ed25519.Sign(priv, registry.AuthMessage(c32, p32))),
	})
	if status != http.StatusCreated {
		t.Fatalf("register: %d %s", status, body)
	}
	var reg struct {
		RoutingID  string `json:"routing_id"`
		Credential string `json:"credential"`
	}
	_ = json.Unmarshal(body, &reg)
	c.routingID, c.credential = reg.RoutingID, reg.Credential
	return c
}

type bobKeys struct {
	identity ed25519.PrivateKey
	idDH     *ecdh.PrivateKey
	spk      *ecdh.PrivateKey
	opks     map[uint32]*ecdh.PrivateKey
}

func publishBob(t *testing.T, bob *client, nOPK int) bobKeys {
	t.Helper()
	k := bobKeys{opks: map[uint32]*ecdh.PrivateKey{}}
	_, k.identity, _ = ed25519.GenerateKey(rand.Reader)
	k.idDH, _ = ecdh.X25519().GenerateKey(rand.Reader)
	k.spk, _ = ecdh.X25519().GenerateKey(rand.Reader)
	req := publishRequest{Bundle: &BundleJSON{
		IdentityKey:   hex.EncodeToString(k.identity.Public().(ed25519.PublicKey)),
		IdentityDH:    hex.EncodeToString(k.idDH.PublicKey().Bytes()),
		IdentityDHSig: hex.EncodeToString(x3dh.SignIdentityDH(k.identity, k.idDH.PublicKey())),
		SPKID:         1,
		SPK:           hex.EncodeToString(k.spk.PublicKey().Bytes()),
		SPKSig:        hex.EncodeToString(x3dh.SignPrekey(k.identity, 1, k.spk.PublicKey())),
	}}
	for i := range nOPK {
		id := uint32(100 + i) // #nosec G115 -- small positive test ids
		priv, _ := ecdh.X25519().GenerateKey(rand.Reader)
		k.opks[id] = priv
		req.OneTime = append(req.OneTime, OneTimeJSON{KeyID: id, PublicKey: hex.EncodeToString(priv.PublicKey().Bytes())})
	}
	if status, body := bob.do("PUT", "/v1/prekeys", req); status != http.StatusNoContent {
		t.Fatalf("publish: %d %s", status, body)
	}
	return k
}

func claim(t *testing.T, as *client, routingID string) (claimResponse, int, []byte) {
	t.Helper()
	status, body := as.do("POST", "/v1/prekeys/"+routingID+"/claim", nil)
	var cr claimResponse
	if status == http.StatusOK {
		if err := json.Unmarshal(body, &cr); err != nil {
			t.Fatal(err)
		}
	}
	return cr, status, body
}

func TestTwoDevicesAgreeOnASecretThroughTheRegistry(t *testing.T) {
	srv := newServer(t)
	bob := enrol(t, srv)
	keys := publishBob(t, bob, 2)
	alice := enrol(t, srv)

	cr, status, body := claim(t, alice, bob.routingID)
	if status != http.StatusOK || cr.OneTime == nil {
		t.Fatalf("claim: %d %s", status, body)
	}
	dec := func(s string) []byte { b, _ := hex.DecodeString(s); return b }
	bundle, err := x3dh.ParsePublicBundle(x3dh.PublicBundle{
		IdentityEd: dec(cr.Bundle.IdentityKey), IdentityDH: dec(cr.Bundle.IdentityDH),
		IdentityDHSig: dec(cr.Bundle.IdentityDHSig), SPKID: cr.Bundle.SPKID,
		SPK: dec(cr.Bundle.SPK), SPKSig: dec(cr.Bundle.SPKSig),
	})
	if err != nil {
		t.Fatal(err)
	}
	bundle.OPKID = cr.OneTime.KeyID
	if bundle.OPK, err = ecdh.X25519().NewPublicKey(dec(cr.OneTime.PublicKey)); err != nil {
		t.Fatal(err)
	}

	aliceEdPub, _, _ := ed25519.GenerateKey(rand.Reader)
	aliceDH, _ := ecdh.X25519().GenerateKey(rand.Reader)
	aliceEph, _ := ecdh.X25519().GenerateKey(rand.Reader)
	var matchID [16]byte
	copy(matchID[:], "TESTONLY-match01")
	a, err := x3dh.Initiate(aliceEdPub, aliceDH, aliceEph, bundle, matchID)
	if err != nil {
		t.Fatalf("Initiate against the served bundle: %v", err)
	}
	b, err := x3dh.Respond(keys.identity.Public().(ed25519.PublicKey), keys.idDH, keys.spk, 1,
		keys.opks[cr.OneTime.KeyID], cr.OneTime.KeyID, aliceEdPub, aliceDH.PublicKey(), aliceEph.PublicKey(), matchID)
	if err != nil {
		t.Fatalf("Respond: %v", err)
	}
	if !bytes.Equal(a.SK, b.SK) || !bytes.Equal(a.AD, b.AD) || !a.UsedOPK {
		t.Fatal("both sides must derive the same secret and transcript, using the one-time prekey")
	}

	second, _, _ := claim(t, alice, bob.routingID)
	if second.OneTime == nil || second.OneTime.KeyID == cr.OneTime.KeyID {
		t.Fatal("a second claim must receive a different one-time prekey")
	}
	third, status, _ := claim(t, alice, bob.routingID)
	if status != http.StatusOK || third.OneTime != nil {
		t.Fatal("with stock exhausted the claim falls back to the signed prekey alone (TRS-CRY-005)")
	}
}

func TestAuthFailuresAreOneCodeAndUnknownTargetsLookRevoked(t *testing.T) {
	srv := newServer(t)
	alice := enrol(t, srv)

	for name, cred := range map[string]string{
		"missing": "", "not hex": "zz", "invented": strings.Repeat("ab", 32),
	} {
		c := &client{t: t, srv: srv, credential: cred}
		status, body := c.do("GET", "/v1/prekeys/status", nil)
		if status != http.StatusUnauthorized || !strings.Contains(string(body), lumoserrors.ErrAuthRejected) {
			t.Fatalf("%s credential: %d %s", name, status, body)
		}
	}

	_, unknownStatus, unknownBody := claim(t, alice, strings.Repeat("00", 16))
	bob := enrol(t, srv)
	_, noBundleStatus, noBundleBody := claim(t, alice, bob.routingID)
	if unknownStatus != http.StatusNotFound || unknownStatus != noBundleStatus || !bytes.Equal(unknownBody, noBundleBody) {
		t.Fatalf("an unknown id (%d %s) and a known one without a bundle (%d %s) must be indistinguishable",
			unknownStatus, unknownBody, noBundleStatus, noBundleBody)
	}
}

func TestNoResponseCarriesPrivateOrStoredSecrets(t *testing.T) {
	srv := newServer(t)
	bob := enrol(t, srv)
	keys := publishBob(t, bob, 1)
	alice := enrol(t, srv)
	_, _, body := claim(t, alice, bob.routingID)
	_, statusBody := bob.do("GET", "/v1/prekeys/status", nil)
	all := string(body) + string(statusBody)
	secrets := []string{
		hex.EncodeToString(keys.identity.Seed()),
		hex.EncodeToString(keys.idDH.Bytes()),
		hex.EncodeToString(keys.spk.Bytes()),
		bob.credential,
	}
	for _, opk := range keys.opks {
		secrets = append(secrets, hex.EncodeToString(opk.Bytes()))
	}
	for _, s := range secrets {
		if strings.Contains(all, s) {
			t.Fatal("a response carried private key material or a credential")
		}
	}
}

func TestOversizedAndUnknownFieldsAreRefusedBeforeTheService(t *testing.T) {
	srv := newServer(t)
	bob := enrol(t, srv)
	status, body := bob.do("PUT", "/v1/prekeys", map[string]string{"private_key": "00"})
	if status != http.StatusBadRequest || !strings.Contains(string(body), lumoserrors.ErrPayloadMalformed) {
		t.Fatalf("an unknown field (a private key, say) must be refused: %d %s", status, body)
	}
	huge := map[string]string{"pad": strings.Repeat("a", maxBody+1)}
	if status, _ := bob.do("PUT", "/v1/prekeys", huge); status != http.StatusBadRequest {
		t.Fatalf("a body above the cap must be refused, got %d", status)
	}
}
