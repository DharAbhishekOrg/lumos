// Lumos - tools/vectorgen / The cross-language identity-binding vector family.
// Author: dhar.abhishek.email@gmail.com
// Implements: SRS-IDN-001, SRS-NFR-SEC-002, TRS-CRY-001, TRS-TST-001
// ADRs: ADR-001, ADR-008
// Threading: any
// Notes: WP-03 S1 gives Android a second identity backend - libsodium, because
// the platform cannot generate an Ed25519 key at any API level - and a second
// backend is exactly the self-agreement risk the vector suite exists to kill.
// This family pins the ADR-008 two-key identity across four implementations:
// Go (x/crypto + stdlib, this generator), JCA on the JVM, Lazysodium on an
// Android runtime, and CryptoKit on the macOS job.
//
// Determinism is what makes that possible without co-residence. Ed25519
// public-key derivation and RFC 8032 signing are deterministic everywhere, and
// the X25519 public key is a pure function of the scalar, so a seeded case has
// exactly one right answer:
//
//	ed_seed   -> ed_public              (all four byte-compare)
//	x_scalar  -> x_public               (all four byte-compare; clamping is
//	                                     part of the function, not the storage)
//	both      -> binding_signature      (Go, JCA and libsodium byte-compare;
//	                                     CryptoKit randomises its signing, so
//	                                     Swift VERIFIES the committed signature
//	                                     and round-trips its own instead)
//
// The X25519 case supplies the raw SCALAR, not a seed: libsodium's seeded box
// keypair hashes the seed before clamping while Go's ecdh takes the scalar
// directly, and a family built on seeds would be pinning each library's
// private derivation ritual rather than the key the protocol actually uses.
//
// This file is NOT prescribed by TRS Sec. 18.4; see ExtensionFiles in suite.go.
package main

import (
	"crypto/ecdh"
	"crypto/ed25519"
	"encoding/hex"
	"fmt"

	"github.com/dharabhishekorg/lumos/protocol/crypto/x3dh"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
)

// identityCase is one deterministic identity with its expected products.
type identityCase struct {
	Name                string `json:"name"`
	EdSeedHex           string `json:"testonly_ed_seed_hex"`
	XScalarHex          string `json:"testonly_x_scalar_hex"`
	EdPublicHex         string `json:"ed_public_hex"`
	XPublicHex          string `json:"x_public_hex"`
	BindingMessageHex   string `json:"binding_message_hex"`
	BindingSignatureHex string `json:"binding_signature_hex"`
}

// identityNegative is a verification that must fail.
type identityNegative struct {
	Name                string `json:"name"`
	Reason              string `json:"reason"`
	EdPublicHex         string `json:"ed_public_hex"`
	XPublicHex          string `json:"x_public_hex"`
	BindingSignatureHex string `json:"binding_signature_hex"`
}

// identityFile is the emitted crypto/identity_binding.json.
type identityFile struct {
	fileMeta
	BindingInfo string             `json:"binding_info"`
	Positive    []identityCase     `json:"positive"`
	Negative    []identityNegative `json:"negative"`
}

// Implements echoes fileMeta for the vectorDoc registration.
func (f *identityFile) implementsIDs() []string { return f.Implements }

// buildIdentityBinding emits the identity-binding family.
func buildIdentityBinding(constantsHash string) (*identityFile, error) {
	file := &identityFile{
		fileMeta: fileMeta{
			SchemaVersion: 1, Generator: GeneratorName, ConstantsHash: constantsHash,
			Implements: []string{
				"SRS-IDN-001", "SRS-NFR-SEC-002", "TRS-CRY-001", "TRS-TST-001",
			},
			KeyProvenance: KeyProvenance,
			Notes: "The ADR-008 two-key identity, pinned across Go, JCA, libsodium and " +
				"CryptoKit. binding_message is UTF-8(binding_info) || x_public and " +
				"binding_signature is Ed25519 over it under ed_seed's key. Platforms with " +
				"deterministic signing (Go, JCA, libsodium) must reproduce the signature " +
				"byte for byte; CryptoKit randomises signing, so the Swift consumer " +
				"verifies the committed signature and round-trips one of its own. " +
				"x_scalar_hex is the raw Curve25519 scalar - clamping happens inside the " +
				"scalar multiplication on every platform, never in storage.",
		},
		BindingInfo: lumosconstants.X3dhIdentityBindingInfoValue,
	}

	for i := 1; i <= 4; i++ {
		label := fmt.Sprintf("identity-binding-%d", i)
		seed := testonlyBytes(label+"/ed-seed", ed25519.SeedSize)
		scalar := testonlyBytes(label+"/x-scalar", 32)

		edPriv := ed25519.NewKeyFromSeed(seed)
		edPub := edPriv.Public().(ed25519.PublicKey)

		xPriv, err := ecdh.X25519().NewPrivateKey(scalar)
		if err != nil {
			return nil, fmt.Errorf("case %s: %w", label, err)
		}
		xPub := xPriv.PublicKey()

		message := append([]byte(lumosconstants.X3dhIdentityBindingInfoValue), xPub.Bytes()...)
		signature := x3dh.SignIdentityDH(edPriv, xPub)

		file.Positive = append(file.Positive, identityCase{
			Name:                label,
			EdSeedHex:           hex.EncodeToString(seed),
			XScalarHex:          hex.EncodeToString(scalar),
			EdPublicHex:         hex.EncodeToString(edPub),
			XPublicHex:          hex.EncodeToString(xPub.Bytes()),
			BindingMessageHex:   hex.EncodeToString(message),
			BindingSignatureHex: hex.EncodeToString(signature),
		})
	}

	base := file.Positive[0]
	other := file.Positive[1]

	tampered, err := hex.DecodeString(base.BindingSignatureHex)
	if err != nil {
		return nil, err
	}
	tampered[0] ^= 0x01
	file.Negative = append(file.Negative,
		identityNegative{
			Name:                "binding-signature-bit-flipped",
			Reason:              "one flipped bit in the signature must fail verification",
			EdPublicHex:         base.EdPublicHex,
			XPublicHex:          base.XPublicHex,
			BindingSignatureHex: hex.EncodeToString(tampered),
		},
		identityNegative{
			Name:                "binding-signature-for-another-dh-key",
			Reason:              "a valid signature over a DIFFERENT identity-DH key must not transplant",
			EdPublicHex:         base.EdPublicHex,
			XPublicHex:          other.XPublicHex,
			BindingSignatureHex: base.BindingSignatureHex,
		},
		identityNegative{
			Name:                "binding-signature-under-another-identity",
			Reason:              "a signature by a DIFFERENT Ed25519 identity must not verify",
			EdPublicHex:         other.EdPublicHex,
			XPublicHex:          base.XPublicHex,
			BindingSignatureHex: base.BindingSignatureHex,
		},
	)
	return file, nil
}
