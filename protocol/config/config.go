// Lumos - protocol/config / Signed configuration and voucher validation.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CFG-001, TRS-CRY-009, TRS-CONST-002, TRS-CONST-005, TRS-ARCH-006, TRS-ARCH-010
// ADRs: ADR-001, ADR-004, ADR-006
// Threading: any
// Notes: Validation is FAIL CLOSED and ordered so the cheapest structural checks
// cannot be skipped by a signature that happens to verify: signature, then
// constants hash, then environment, then validity window, then rollback. Each
// failure carries its own stable code, which is what the negative vectors pin.
// Rejecting a package leaves the last-known-good active - there is no partial
// activation and no fail-open path (TRS-ARCH-010). Canonical encoding is a
// deterministic sorted key=value form, not JSON, for the same cross-language
// reproducibility reason as the constants hash.
package config

import (
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"sort"
	"strconv"
	"strings"

	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumoserrors"
	"github.com/dharabhishekorg/lumos/protocol/wire"
)

// Environment values a package may target.
const (
	EnvDevelopment = "development"
	EnvTest        = "test"
	EnvStaging     = "staging"
	EnvProduction  = "production"
)

// Package is a signed configuration package's payload.
type Package struct {
	Version       int
	Environment   string
	ConstantsHash string
	ValidFrom     int64
	ValidUntil    int64
	// Overrides are constant-name -> integer value. Bounds validation against
	// the generated override schema is the config client's job (WP-08); this
	// package proves envelope integrity.
	Overrides map[string]int64
	// KillSwitches are feature disablements; they may only turn things OFF.
	KillSwitches map[string]bool
}

// CanonicalBytes renders the deterministic signing form: one sorted
// "key=value" line per field, newline-joined, with a trailing newline. Sorted
// flat text rather than JSON so Java and Swift reproduce it without depending
// on any library's key ordering.
func (p *Package) CanonicalBytes() []byte {
	lines := []string{
		"constants_hash=" + p.ConstantsHash,
		"environment=" + p.Environment,
		"valid_from=" + strconv.FormatInt(p.ValidFrom, 10),
		"valid_until=" + strconv.FormatInt(p.ValidUntil, 10),
		"version=" + strconv.Itoa(p.Version),
	}
	for k, v := range p.Overrides {
		lines = append(lines, "overrides."+k+"="+strconv.FormatInt(v, 10))
	}
	for k, v := range p.KillSwitches {
		lines = append(lines, "kill_switches."+k+"="+strconv.FormatBool(v))
	}
	sort.Strings(lines)
	return []byte(strings.Join(lines, "\n") + "\n")
}

// PayloadHash is the SHA-256 of the canonical bytes, in the register's
// "sha256:<hex>" form.
func (p *Package) PayloadHash() string {
	sum := sha256.Sum256(p.CanonicalBytes())
	return "sha256:" + hex.EncodeToString(sum[:])
}

// signingMessage is what the Ed25519 signature actually covers: a domain prefix
// followed by the canonical bytes, so a config signature can never be replayed
// as a voucher signature.
func (p *Package) signingMessage() []byte {
	return append([]byte(lumosconstants.ConfigSigningInfoValue), p.CanonicalBytes()...)
}

// Sign produces the package signature. TESTONLY keys only in this repository -
// production signing keys live in owner-controlled KMS/HSM (gate G-05).
func (p *Package) Sign(priv ed25519.PrivateKey) []byte {
	return ed25519.Sign(priv, p.signingMessage())
}

// ValidationContext is what the client checks a package against.
type ValidationContext struct {
	TrustAnchor      ed25519.PublicKey
	Environment      string
	ConstantsHash    string
	Now              int64
	ActiveVersion    int
	ClockSkewToleran int64
}

// DefaultSkew returns the register's clock-skew tolerance.
func DefaultSkew() int64 { return int64(lumosconstants.ClockSkewToleranceSecDefault) }

// Validate applies every gate in order and fails closed on the first failure.
// The order is deliberate and is asserted by the negative vectors: a package
// that is both expired and wrong-environment reports the environment mismatch,
// because environment is checked first.
func Validate(p *Package, sig []byte, ctx ValidationContext) error {
	if !ed25519.Verify(ctx.TrustAnchor, p.signingMessage(), sig) {
		return &wire.Error{Code: lumoserrors.ErrConfigSignatureInvalid, Detail: "package signature rejected"}
	}
	if p.ConstantsHash != ctx.ConstantsHash {
		return &wire.Error{
			Code:   lumoserrors.ErrConfigConstantsHashMismatch,
			Detail: "package built against a different constants register",
		}
	}
	if p.Environment != ctx.Environment {
		return &wire.Error{Code: lumoserrors.ErrConfigWrongEnvironment, Detail: "environment mismatch"}
	}
	skew := ctx.ClockSkewToleran
	if skew == 0 {
		skew = DefaultSkew()
	}
	if ctx.Now+skew < p.ValidFrom || ctx.Now-skew > p.ValidUntil {
		return &wire.Error{Code: lumoserrors.ErrConfigExpired, Detail: "outside validity window"}
	}
	if p.Version <= ctx.ActiveVersion {
		return &wire.Error{
			Code:   lumoserrors.ErrConfigRollback,
			Detail: fmt.Sprintf("version %d does not supersede active %d", p.Version, ctx.ActiveVersion),
		}
	}
	return nil
}

// ---- vouchers ----

// Voucher is a signed single-use voucher token payload.
type Voucher struct {
	VoucherID  string
	VenueID    string
	Nonce      string
	ValidFrom  int64
	ValidUntil int64
	PerkID     string
	Revocable  bool
}

// CanonicalBytes renders the voucher's deterministic signing form.
func (v *Voucher) CanonicalBytes() []byte {
	lines := []string{
		"nonce=" + v.Nonce,
		"perk_id=" + v.PerkID,
		"revocable=" + strconv.FormatBool(v.Revocable),
		"valid_from=" + strconv.FormatInt(v.ValidFrom, 10),
		"valid_until=" + strconv.FormatInt(v.ValidUntil, 10),
		"venue_id=" + v.VenueID,
		"voucher_id=" + v.VoucherID,
	}
	sort.Strings(lines)
	return []byte(strings.Join(lines, "\n") + "\n")
}

func (v *Voucher) signingMessage() []byte {
	return append([]byte(lumosconstants.VoucherSigningInfoValue), v.CanonicalBytes()...)
}

// Sign produces the voucher signature (TESTONLY keys in this repository).
func (v *Voucher) Sign(priv ed25519.PrivateKey) []byte {
	return ed25519.Sign(priv, v.signingMessage())
}

// RedemptionContext is the state a client redeems a voucher against.
type RedemptionContext struct {
	TrustAnchor ed25519.PublicKey
	Now         int64
	// SeenNonces is the replay cache (VOUCHER_NONCE_CACHE bounds it in the client).
	SeenNonces map[string]bool
	// Revoked is the revocation set consulted for revocable vouchers.
	Revoked map[string]bool
	Skew    int64
}

// Redeem validates a voucher for single use, failing closed with stable codes.
func Redeem(v *Voucher, sig []byte, ctx RedemptionContext) error {
	if !ed25519.Verify(ctx.TrustAnchor, v.signingMessage(), sig) {
		return &wire.Error{Code: lumoserrors.ErrConfigSignatureInvalid, Detail: "voucher signature rejected"}
	}
	if v.Revocable && ctx.Revoked[v.VoucherID] {
		return &wire.Error{Code: lumoserrors.ErrVoucherRevoked, Detail: "voucher revoked"}
	}
	skew := ctx.Skew
	if skew == 0 {
		skew = DefaultSkew()
	}
	if ctx.Now+skew < v.ValidFrom || ctx.Now-skew > v.ValidUntil {
		return &wire.Error{Code: lumoserrors.ErrConfigExpired, Detail: "outside validity window"}
	}
	if ctx.SeenNonces[v.Nonce] {
		return &wire.Error{Code: lumoserrors.ErrVoucherNonceReplay, Detail: "nonce already redeemed"}
	}
	return nil
}
