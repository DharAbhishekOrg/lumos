// Lumos - protocol/config / Go conformance consumption of config and voucher vectors.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-TST-001, TRS-CFG-001, TRS-CFG-009, TRS-CRY-009, TRS-PROTO-015
// ADRs: ADR-001, ADR-004
// Threading: any
// Notes: Every case reconstructs the package from its recorded fields and
// re-derives the canonical bytes, so a consumer whose canonical encoding differs
// fails here rather than shipping a signature nobody else can verify. A missing
// file is t.Fatal (TRS Sec. 18.4).
package config

import (
	"crypto/ed25519"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"

	"github.com/dharabhishekemail-ops/lumos/protocol/gen/go/lumoserrors"
	"github.com/dharabhishekemail-ops/lumos/protocol/schema"
	"github.com/dharabhishekemail-ops/lumos/protocol/wire"
)

type vectorPayload struct {
	Version       int              `json:"version"`
	Environment   string           `json:"environment"`
	ConstantsHash string           `json:"constants_hash"`
	ValidFrom     int64            `json:"valid_from"`
	ValidUntil    int64            `json:"valid_until"`
	Overrides     map[string]int64 `json:"overrides"`
	KillSwitches  map[string]bool  `json:"kill_switches"`
}

func (p vectorPayload) toPackage() *Package {
	return &Package{
		Version: p.Version, Environment: p.Environment, ConstantsHash: p.ConstantsHash,
		ValidFrom: p.ValidFrom, ValidUntil: p.ValidUntil,
		Overrides: p.Overrides, KillSwitches: p.KillSwitches,
	}
}

func loadVec(t *testing.T, rel string, into any) {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("..", "..", filepath.FromSlash(rel)))
	if err != nil {
		t.Fatalf("committed vector %s unreadable - a missing vector file is a failed gate: %v", rel, err)
	}
	if err := json.Unmarshal(raw, into); err != nil {
		t.Fatalf("parse %s: %v", rel, err)
	}
}

func registerHash(t *testing.T) string {
	t.Helper()
	reg, err := schema.LoadRegister(filepath.Join("..", ".."))
	if err != nil {
		t.Fatalf("LoadRegister: %v", err)
	}
	h, err := reg.String("constants_hash")
	if err != nil {
		t.Fatalf("constants_hash: %v", err)
	}
	return h
}

func anchor(t *testing.T, h string) ed25519.PublicKey {
	t.Helper()
	b, err := hex.DecodeString(h)
	if err != nil || len(b) != ed25519.PublicKeySize {
		t.Fatalf("bad trust anchor %q", h)
	}
	return ed25519.PublicKey(b)
}

func TestConformanceConfigValid(t *testing.T) {
	var vf struct {
		ConstantsHash  string `json:"constants_hash"`
		TrustAnchorHex string `json:"trust_anchor_ed25519_pub_hex"`
		Cases          []struct {
			Name           string        `json:"name"`
			Payload        vectorPayload `json:"payload"`
			CanonicalBytes string        `json:"canonical_bytes"`
			PayloadHash    string        `json:"payload_hash"`
			SignatureHex   string        `json:"signature_hex"`
			NowUnix        int64         `json:"now_unix"`
			ActiveVersion  int           `json:"active_version"`
		} `json:"cases"`
	}
	loadVec(t, "test-vectors/config/package_valid.json", &vf)
	if vf.ConstantsHash != registerHash(t) {
		t.Fatal("stale config vectors (TRS-CONST-005)")
	}
	if len(vf.Cases) == 0 {
		t.Fatal("no valid config cases")
	}
	pub := anchor(t, vf.TrustAnchorHex)

	for _, c := range vf.Cases {
		t.Run(c.Name, func(t *testing.T) {
			p := c.Payload.toPackage()
			// Canonical encoding must be reproducible, or signatures are useless
			// across languages.
			if string(p.CanonicalBytes()) != c.CanonicalBytes {
				t.Errorf("canonical bytes mismatch:\n got %q\nwant %q", p.CanonicalBytes(), c.CanonicalBytes)
			}
			if p.PayloadHash() != c.PayloadHash {
				t.Errorf("payload hash %s, vector says %s", p.PayloadHash(), c.PayloadHash)
			}
			sig, err := hex.DecodeString(c.SignatureHex)
			if err != nil {
				t.Fatalf("bad signature hex: %v", err)
			}
			ctx := ValidationContext{
				TrustAnchor:   pub,
				Environment:   p.Environment,
				ConstantsHash: p.ConstantsHash,
				Now:           c.NowUnix,
				ActiveVersion: c.ActiveVersion,
			}
			if err := Validate(p, sig, ctx); err != nil {
				t.Fatalf("valid package rejected: %v", err)
			}
		})
	}
}

func TestConformanceConfigInvalid(t *testing.T) {
	var vf struct {
		ConstantsHash  string `json:"constants_hash"`
		TrustAnchorHex string `json:"trust_anchor_ed25519_pub_hex"`
		Cases          []struct {
			Name          string        `json:"name"`
			Payload       vectorPayload `json:"payload"`
			SignatureHex  string        `json:"signature_hex"`
			NowUnix       int64         `json:"now_unix"`
			ActiveVersion int           `json:"active_version"`
			Environment   string        `json:"expected_environment"`
			ExpectedError string        `json:"expected_error"`
		} `json:"cases"`
	}
	loadVec(t, "test-vectors/config/package_invalid.json", &vf)
	if vf.ConstantsHash != registerHash(t) {
		t.Fatal("stale config vectors (TRS-CONST-005)")
	}
	if len(vf.Cases) == 0 {
		t.Fatal("no invalid config cases")
	}
	pub := anchor(t, vf.TrustAnchorHex)

	for _, c := range vf.Cases {
		t.Run(c.Name, func(t *testing.T) {
			p := c.Payload.toPackage()
			sig, err := hex.DecodeString(c.SignatureHex)
			if err != nil {
				t.Fatalf("bad signature hex: %v", err)
			}
			ctx := ValidationContext{
				TrustAnchor:   pub,
				Environment:   c.Environment,
				ConstantsHash: vf.ConstantsHash,
				Now:           c.NowUnix,
				ActiveVersion: c.ActiveVersion,
			}
			err = Validate(p, sig, ctx)
			var we *wire.Error
			if !errors.As(err, &we) || we.Code != c.ExpectedError {
				t.Fatalf("expected %s, got %v", c.ExpectedError, err)
			}
			if !lumoserrors.Known(we.Code) {
				t.Errorf("%s is not in the stable registry", we.Code)
			}
		})
	}
}

func TestConformanceVoucherTokens(t *testing.T) {
	var vf struct {
		ConstantsHash  string `json:"constants_hash"`
		TrustAnchorHex string `json:"trust_anchor_ed25519_pub_hex"`
		Cases          []struct {
			Name    string `json:"name"`
			Payload struct {
				VoucherID  string `json:"voucher_id"`
				VenueID    string `json:"venue_id"`
				Nonce      string `json:"nonce"`
				ValidFrom  int64  `json:"valid_from"`
				ValidUntil int64  `json:"valid_until"`
				PerkID     string `json:"perk_id"`
				Revocable  bool   `json:"revocable"`
			} `json:"payload"`
			CanonicalBytes string   `json:"canonical_bytes"`
			SignatureHex   string   `json:"signature_hex"`
			NowUnix        int64    `json:"now_unix"`
			SeenNonces     []string `json:"seen_nonces"`
			RevokedIDs     []string `json:"revoked_voucher_ids"`
			ExpectedError  string   `json:"expected_error"`
		} `json:"cases"`
	}
	loadVec(t, "test-vectors/voucher/voucher_tokens.json", &vf)
	if vf.ConstantsHash != registerHash(t) {
		t.Fatal("stale voucher vectors (TRS-CONST-005)")
	}
	if len(vf.Cases) == 0 {
		t.Fatal("no voucher cases")
	}
	pub := anchor(t, vf.TrustAnchorHex)

	sawPositive, sawNegative := false, false
	for _, c := range vf.Cases {
		t.Run(c.Name, func(t *testing.T) {
			v := &Voucher{
				VoucherID: c.Payload.VoucherID, VenueID: c.Payload.VenueID,
				Nonce: c.Payload.Nonce, ValidFrom: c.Payload.ValidFrom,
				ValidUntil: c.Payload.ValidUntil, PerkID: c.Payload.PerkID,
				Revocable: c.Payload.Revocable,
			}
			if string(v.CanonicalBytes()) != c.CanonicalBytes {
				t.Errorf("canonical bytes mismatch:\n got %q\nwant %q", v.CanonicalBytes(), c.CanonicalBytes)
			}
			sig, err := hex.DecodeString(c.SignatureHex)
			if err != nil {
				t.Fatalf("bad signature hex: %v", err)
			}
			ctx := RedemptionContext{
				TrustAnchor: pub, Now: c.NowUnix,
				SeenNonces: map[string]bool{}, Revoked: map[string]bool{},
			}
			for _, n := range c.SeenNonces {
				ctx.SeenNonces[n] = true
			}
			for _, id := range c.RevokedIDs {
				ctx.Revoked[id] = true
			}

			err = Redeem(v, sig, ctx)
			if c.ExpectedError == "" {
				sawPositive = true
				if err != nil {
					t.Fatalf("voucher that must redeem was rejected: %v", err)
				}
				return
			}
			sawNegative = true
			var we *wire.Error
			if !errors.As(err, &we) || we.Code != c.ExpectedError {
				t.Fatalf("expected %s, got %v", c.ExpectedError, err)
			}
		})
	}
	if !sawPositive || !sawNegative {
		t.Errorf("voucher vectors must contain both redeemable and refused cases (positive=%v negative=%v)",
			sawPositive, sawNegative)
	}
}
