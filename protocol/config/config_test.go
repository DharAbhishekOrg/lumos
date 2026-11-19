// Lumos - protocol/config / Unit tests for signed config and voucher validation.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CFG-001, TRS-CRY-009, TRS-CONST-005, TRS-ARCH-010
// ADRs: ADR-001, ADR-004
// Threading: any
// Notes: TestValidationOrderIsDeterministic pins the gate ORDER, not just the
// codes: a package failing several checks at once must report the first gate,
// otherwise the negative vectors would be ambiguous about which failure a
// consumer is expected to surface.
package config

import (
	"crypto/ed25519"
	"crypto/sha256"
	"errors"
	"strings"
	"testing"

	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumoserrors"
	"github.com/dharabhishekorg/lumos/protocol/wire"
)

const testHash = "sha256:0000000000000000000000000000000000000000000000000000000000000001"

func testKey(t *testing.T, label string) ed25519.PrivateKey {
	t.Helper()
	seed := sha256.Sum256([]byte("TESTONLY-config-unit|" + label))
	return ed25519.NewKeyFromSeed(seed[:])
}

func goodPackage() *Package {
	return &Package{
		Version:       5,
		Environment:   EnvStaging,
		ConstantsHash: testHash,
		ValidFrom:     1000,
		ValidUntil:    100000,
		Overrides:     map[string]int64{"EID_ROTATION_SEC": 600, "MESH_HOP_DEFAULT": 3},
		KillSwitches:  map[string]bool{"disable_venue_rooms": true},
	}
}

func goodContext(t *testing.T, priv ed25519.PrivateKey) ValidationContext {
	t.Helper()
	return ValidationContext{
		TrustAnchor:   priv.Public().(ed25519.PublicKey),
		Environment:   EnvStaging,
		ConstantsHash: testHash,
		Now:           50000,
		ActiveVersion: 4,
	}
}

func assertCode(t *testing.T, err error, want string) {
	t.Helper()
	var we *wire.Error
	if !errors.As(err, &we) || we.Code != want {
		t.Fatalf("expected %s, got %v", want, err)
	}
}

func TestValidPackageAccepted(t *testing.T) {
	priv := testKey(t, "signer")
	p := goodPackage()
	if err := Validate(p, p.Sign(priv), goodContext(t, priv)); err != nil {
		t.Fatalf("valid package rejected: %v", err)
	}
}

func TestCanonicalBytesAreDeterministicAndSorted(t *testing.T) {
	a := goodPackage().CanonicalBytes()
	b := goodPackage().CanonicalBytes()
	if string(a) != string(b) {
		t.Fatal("canonical encoding is not deterministic")
	}
	lines := strings.Split(strings.TrimSuffix(string(a), "\n"), "\n")
	for i := 1; i < len(lines); i++ {
		if lines[i-1] > lines[i] {
			t.Errorf("canonical lines are not sorted at %d: %q then %q", i, lines[i-1], lines[i])
		}
	}
	if !strings.HasSuffix(string(a), "\n") || strings.HasSuffix(string(a), "\n\n") {
		t.Error("canonical form must end with exactly one newline")
	}
	if h := goodPackage().PayloadHash(); !strings.HasPrefix(h, "sha256:") || len(h) != 71 {
		t.Errorf("payload hash %q is malformed", h)
	}
}

// TestEveryFieldIsSigned: mutating any field must invalidate the signature.
func TestEveryFieldIsSigned(t *testing.T) {
	priv := testKey(t, "signer")
	base := goodPackage()
	sig := base.Sign(priv)
	ctx := goodContext(t, priv)

	mutations := map[string]func(*Package){
		"version":        func(p *Package) { p.Version = 6 },
		"environment":    func(p *Package) { p.Environment = EnvProduction },
		"constants hash": func(p *Package) { p.ConstantsHash = strings.Replace(testHash, "1", "2", 1) },
		"valid_from":     func(p *Package) { p.ValidFrom = 999 },
		"valid_until":    func(p *Package) { p.ValidUntil = 99999 },
		"an override":    func(p *Package) { p.Overrides["EID_ROTATION_SEC"] = 601 },
		"a kill switch":  func(p *Package) { p.KillSwitches["disable_venue_rooms"] = false },
		"added override": func(p *Package) { p.Overrides["MESH_HOP_DEFAULT"] = 4 },
	}
	for name, mutate := range mutations {
		t.Run(name, func(t *testing.T) {
			p := goodPackage()
			mutate(p)
			if err := Validate(p, sig, ctx); err == nil {
				t.Fatalf("mutating %s did not invalidate the package", name)
			}
		})
	}
}

func TestNegativeGates(t *testing.T) {
	priv := testKey(t, "signer")

	t.Run("tampered signature", func(t *testing.T) {
		p := goodPackage()
		sig := p.Sign(priv)
		sig[0] ^= 1
		assertCode(t, Validate(p, sig, goodContext(t, priv)), lumoserrors.ErrConfigSignatureInvalid)
	})
	t.Run("foreign signer", func(t *testing.T) {
		p := goodPackage()
		other := testKey(t, "mallory")
		assertCode(t, Validate(p, p.Sign(other), goodContext(t, priv)), lumoserrors.ErrConfigSignatureInvalid)
	})
	t.Run("constants hash mismatch", func(t *testing.T) {
		p := goodPackage()
		p.ConstantsHash = strings.Replace(testHash, "1", "9", 1)
		assertCode(t, Validate(p, p.Sign(priv), goodContext(t, priv)), lumoserrors.ErrConfigConstantsHashMismatch)
	})
	t.Run("wrong environment", func(t *testing.T) {
		p := goodPackage()
		p.Environment = EnvProduction
		assertCode(t, Validate(p, p.Sign(priv), goodContext(t, priv)), lumoserrors.ErrConfigWrongEnvironment)
	})
	t.Run("expired", func(t *testing.T) {
		p := goodPackage()
		ctx := goodContext(t, priv)
		ctx.Now = p.ValidUntil + DefaultSkew() + 1
		assertCode(t, Validate(p, p.Sign(priv), ctx), lumoserrors.ErrConfigExpired)
	})
	t.Run("not yet valid", func(t *testing.T) {
		p := goodPackage()
		ctx := goodContext(t, priv)
		ctx.Now = p.ValidFrom - DefaultSkew() - 1
		assertCode(t, Validate(p, p.Sign(priv), ctx), lumoserrors.ErrConfigExpired)
	})
	t.Run("rollback", func(t *testing.T) {
		p := goodPackage()
		ctx := goodContext(t, priv)
		ctx.ActiveVersion = p.Version // not strictly greater
		assertCode(t, Validate(p, p.Sign(priv), ctx), lumoserrors.ErrConfigRollback)
	})
	t.Run("older version", func(t *testing.T) {
		p := goodPackage()
		ctx := goodContext(t, priv)
		ctx.ActiveVersion = p.Version + 3
		assertCode(t, Validate(p, p.Sign(priv), ctx), lumoserrors.ErrConfigRollback)
	})
}

// TestClockSkewToleranceIsApplied: a package marginally outside its window is
// still accepted within the register's tolerance, so a slightly-off device
// clock does not lock a venue out.
func TestClockSkewToleranceIsApplied(t *testing.T) {
	priv := testKey(t, "signer")
	p := goodPackage()
	ctx := goodContext(t, priv)
	ctx.Now = p.ValidUntil + DefaultSkew() - 1
	if err := Validate(p, p.Sign(priv), ctx); err != nil {
		t.Fatalf("within skew tolerance must be accepted: %v", err)
	}
}

// TestValidationOrderIsDeterministic pins the gate order the vectors rely on.
func TestValidationOrderIsDeterministic(t *testing.T) {
	priv := testKey(t, "signer")

	// Wrong environment AND expired AND rollback: environment is checked first.
	p := goodPackage()
	p.Environment = EnvProduction
	ctx := goodContext(t, priv)
	ctx.Now = p.ValidUntil + 10_000
	ctx.ActiveVersion = 99
	assertCode(t, Validate(p, p.Sign(priv), ctx), lumoserrors.ErrConfigWrongEnvironment)

	// Bad signature outranks everything, including a hash mismatch.
	p2 := goodPackage()
	p2.ConstantsHash = strings.Replace(testHash, "1", "7", 1)
	sig := p2.Sign(priv)
	sig[0] ^= 1
	assertCode(t, Validate(p2, sig, goodContext(t, priv)), lumoserrors.ErrConfigSignatureInvalid)
}

// ---- vouchers ----

func goodVoucher() *Voucher {
	return &Voucher{
		VoucherID:  "welcome-drink",
		VenueID:    "the-blue-room",
		Nonce:      "0123456789abcdef0123456789abcdef",
		ValidFrom:  1000,
		ValidUntil: 100000,
		PerkID:     "free-first-drink",
		Revocable:  true,
	}
}

func voucherContext(t *testing.T, priv ed25519.PrivateKey) RedemptionContext {
	t.Helper()
	return RedemptionContext{
		TrustAnchor: priv.Public().(ed25519.PublicKey),
		Now:         50000,
		SeenNonces:  map[string]bool{},
		Revoked:     map[string]bool{},
	}
}

func TestVoucherLifecycle(t *testing.T) {
	priv := testKey(t, "voucher-signer")
	v := goodVoucher()
	sig := v.Sign(priv)

	t.Run("valid", func(t *testing.T) {
		if err := Redeem(v, sig, voucherContext(t, priv)); err != nil {
			t.Fatalf("valid voucher rejected: %v", err)
		}
	})
	t.Run("replayed nonce", func(t *testing.T) {
		ctx := voucherContext(t, priv)
		ctx.SeenNonces[v.Nonce] = true
		assertCode(t, Redeem(v, sig, ctx), lumoserrors.ErrVoucherNonceReplay)
	})
	t.Run("revoked", func(t *testing.T) {
		ctx := voucherContext(t, priv)
		ctx.Revoked[v.VoucherID] = true
		assertCode(t, Redeem(v, sig, ctx), lumoserrors.ErrVoucherRevoked)
	})
	t.Run("expired", func(t *testing.T) {
		ctx := voucherContext(t, priv)
		ctx.Now = v.ValidUntil + DefaultSkew() + 1
		assertCode(t, Redeem(v, sig, ctx), lumoserrors.ErrConfigExpired)
	})
	t.Run("tampered signature", func(t *testing.T) {
		bad := append([]byte(nil), sig...)
		bad[0] ^= 1
		assertCode(t, Redeem(v, bad, voucherContext(t, priv)), lumoserrors.ErrConfigSignatureInvalid)
	})
	t.Run("non-revocable ignores revocation set", func(t *testing.T) {
		nv := goodVoucher()
		nv.Revocable = false
		ctx := voucherContext(t, priv)
		ctx.Revoked[nv.VoucherID] = true
		if err := Redeem(nv, nv.Sign(priv), ctx); err != nil {
			t.Fatalf("non-revocable voucher must not consult the revocation set: %v", err)
		}
	})
}

// TestVoucherAndConfigSignaturesAreNotInterchangeable: the domain prefixes stop
// a config signature being replayed as a voucher signature and vice versa.
func TestVoucherAndConfigSignaturesAreNotInterchangeable(t *testing.T) {
	priv := testKey(t, "shared-signer")
	p := goodPackage()
	v := goodVoucher()

	if err := Redeem(v, p.Sign(priv), voucherContext(t, priv)); err == nil {
		t.Error("a config signature must not validate a voucher")
	}
	if err := Validate(p, v.Sign(priv), goodContext(t, priv)); err == nil {
		t.Error("a voucher signature must not validate a config package")
	}
}
