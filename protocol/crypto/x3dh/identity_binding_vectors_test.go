// Lumos - protocol/crypto/x3dh / Conformance to the identity-binding vectors.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CRY-001, TRS-TST-001, SRS-IDN-001
// ADRs: ADR-001, ADR-008
// Threading: any
// Notes: Go is the generator's language, so on its own this test would only
// prove vectorgen agrees with itself - the WP-01 S5 lesson. Its value is the
// other direction: it consumes the COMMITTED file through the real
// SignIdentityDH path, so a regenerated-but-uncommitted vector set, or an edit
// to the binding construction, goes red here before Java, Swift or an Android
// runtime ever see the drift.
package x3dh

import (
	"bytes"
	"crypto/ecdh"
	"crypto/ed25519"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type identityBindingFile struct {
	BindingInfo string `json:"binding_info"`
	Positive    []struct {
		Name                string `json:"name"`
		EdSeedHex           string `json:"testonly_ed_seed_hex"`
		XScalarHex          string `json:"testonly_x_scalar_hex"`
		EdPublicHex         string `json:"ed_public_hex"`
		XPublicHex          string `json:"x_public_hex"`
		BindingMessageHex   string `json:"binding_message_hex"`
		BindingSignatureHex string `json:"binding_signature_hex"`
	} `json:"positive"`
	Negative []struct {
		Name                string `json:"name"`
		EdPublicHex         string `json:"ed_public_hex"`
		XPublicHex          string `json:"x_public_hex"`
		BindingSignatureHex string `json:"binding_signature_hex"`
	} `json:"negative"`
}

func loadIdentityBinding(t *testing.T) identityBindingFile {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("..", "..", "..", "test-vectors", "crypto", "identity_binding.json"))
	if err != nil {
		t.Fatalf("committed vector file must exist: %v", err)
	}
	var file identityBindingFile
	if err := json.Unmarshal(raw, &file); err != nil {
		t.Fatalf("unparseable vector file: %v", err)
	}
	if len(file.Positive) == 0 || len(file.Negative) == 0 {
		t.Fatal("a vector family without positives and negatives proves nothing")
	}
	return file
}

func unhex(t *testing.T, field, value string) []byte {
	t.Helper()
	out, err := hex.DecodeString(value)
	if err != nil {
		t.Fatalf("%s is not hex: %v", field, err)
	}
	return out
}

func TestIdentityBindingVectorsReproduceThroughTheRealPath(t *testing.T) {
	file := loadIdentityBinding(t)
	for _, c := range file.Positive {
		seed := unhex(t, c.Name+" ed_seed", c.EdSeedHex)
		scalar := unhex(t, c.Name+" x_scalar", c.XScalarHex)

		edPriv := ed25519.NewKeyFromSeed(seed)
		if !bytes.Equal(edPriv.Public().(ed25519.PublicKey), unhex(t, c.Name, c.EdPublicHex)) {
			t.Fatalf("%s: ed_public disagrees", c.Name)
		}
		xPriv, err := ecdh.X25519().NewPrivateKey(scalar)
		if err != nil {
			t.Fatalf("%s: %v", c.Name, err)
		}
		if !bytes.Equal(xPriv.PublicKey().Bytes(), unhex(t, c.Name, c.XPublicHex)) {
			t.Fatalf("%s: x_public disagrees", c.Name)
		}
		signature := SignIdentityDH(edPriv, xPriv.PublicKey())
		if !bytes.Equal(signature, unhex(t, c.Name, c.BindingSignatureHex)) {
			t.Fatalf("%s: binding signature disagrees with the committed bytes", c.Name)
		}
		if !ed25519.Verify(edPriv.Public().(ed25519.PublicKey),
			unhex(t, c.Name, c.BindingMessageHex), signature) {
			t.Fatalf("%s: own signature fails verification", c.Name)
		}
	}
}

func TestIdentityBindingNegativesAllFailVerification(t *testing.T) {
	file := loadIdentityBinding(t)
	info := []byte(file.BindingInfo)
	for _, c := range file.Negative {
		message := append(append([]byte{}, info...), unhex(t, c.Name, c.XPublicHex)...)
		if ed25519.Verify(ed25519.PublicKey(unhex(t, c.Name, c.EdPublicHex)),
			message, unhex(t, c.Name, c.BindingSignatureHex)) {
			t.Fatalf("%s: a forged or transplanted binding verified", c.Name)
		}
	}
}
