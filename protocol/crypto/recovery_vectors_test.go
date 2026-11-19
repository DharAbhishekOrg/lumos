// Lumos - protocol/crypto / Go consumption of the RecoveryRecord vectors.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-TST-001, SRS-ONB-007, SRS-DAT-004, TRS-CRY-013, SRS-NFR-CMP-004
// ADRs: ADR-001, ADR-003
// Threading: any
// Notes: The Go third of the recovery conformance contract. Go is also the
// reference the vectors were generated FROM, which makes this file weaker
// evidence than its Java and Swift counterparts on its own - it would still
// pass if the reference and the generator were wrong together. What it is
// actually for is the reverse direction: it fails the moment the reference
// drifts from the committed bytes, so the file the other two platforms are held
// to cannot quietly change underneath them.
package crypto_test

import (
	"bytes"
	"errors"
	"testing"

	"github.com/dharabhishekorg/lumos/protocol/crypto/recovery"
	"github.com/dharabhishekorg/lumos/protocol/wire"
)

const recoveryVectorPath = "test-vectors/crypto/recovery_record.json"

type recoveryVectorParams struct {
	MemoryKiB   uint32 `json:"memory_kib"`
	Iterations  uint32 `json:"iterations"`
	Parallelism uint8  `json:"parallelism"`
}

func (p recoveryVectorParams) params() recovery.Params {
	return recovery.Params{MemoryKiB: p.MemoryKiB, Iterations: p.Iterations, Parallelism: p.Parallelism}
}

type recoveryVectorFile struct {
	ConstantsHash string `json:"constants_hash"`
	Format        struct {
		MagicHex                 string `json:"magic_hex"`
		FormatVersion            int    `json:"format_version"`
		AuthenticatedHeaderBytes int    `json:"authenticated_header_bytes"`
		HeaderBytes              int    `json:"header_bytes"`
		SaltBytes                int    `json:"salt_bytes"`
		NonceBytes               int    `json:"nonce_bytes"`
		KeyBytes                 int    `json:"key_bytes"`
		TagBytes                 int    `json:"tag_bytes"`
	} `json:"format"`
	Limits struct {
		RegisterMemoryKiBMin    uint32 `json:"register_memory_kib_min"`
		RegisterIterationsMin   uint32 `json:"register_iterations_min"`
		MaxAcceptableMemoryKiB  uint32 `json:"max_acceptable_memory_kib"`
		MaxAcceptableIterations uint32 `json:"max_acceptable_iterations"`
		SupportedParallelism    uint8  `json:"supported_parallelism"`
	} `json:"limits"`
	Derivation []struct {
		Name          string               `json:"name"`
		PassphraseHex string               `json:"passphrase_hex"`
		SaltHex       string               `json:"salt_hex"`
		Params        recoveryVectorParams `json:"params"`
		DerivedKeyHex string               `json:"derived_key_hex"`
	} `json:"derivation"`
	Positive []struct {
		Name                   string               `json:"name"`
		PassphraseHex          string               `json:"passphrase_hex"`
		SaltHex                string               `json:"salt_hex"`
		NonceHex               string               `json:"nonce_hex"`
		Params                 recoveryVectorParams `json:"params"`
		ContentHex             string               `json:"content_hex"`
		AuthenticatedHeaderHex string               `json:"authenticated_header_hex"`
		RecordHex              string               `json:"record_hex"`
	} `json:"positive"`
	Negative []struct {
		Name                   string `json:"name"`
		Family                 string `json:"family"`
		PassphraseHex          string `json:"passphrase_hex"`
		RecordHex              string `json:"record_hex"`
		ExpectedError          string `json:"expected_error"`
		RejectBeforeDerivation bool   `json:"reject_before_derivation"`
	} `json:"negative"`
}

func loadRecoveryVectors(t *testing.T) recoveryVectorFile {
	t.Helper()
	var vf recoveryVectorFile
	load(t, recoveryVectorPath, &vf)
	if vf.ConstantsHash != regHash(t) {
		t.Fatalf("stale vector file: constants_hash %s, register %s", vf.ConstantsHash, regHash(t))
	}
	if len(vf.Derivation) == 0 || len(vf.Positive) == 0 || len(vf.Negative) == 0 {
		t.Fatal("the recovery vector family is empty in at least one part - a skipped file is a failed gate")
	}
	return vf
}

// TestConformanceRecoveryFormatAndLimits pins the numbers the other two
// platforms will hard-code. A platform can only agree with a layout it was told;
// publishing it here means a disagreement is reported as a disagreement rather
// than as an unopenable record.
func TestConformanceRecoveryFormatAndLimits(t *testing.T) {
	vf := loadRecoveryVectors(t)

	if got := hexDecode(t, vf.Format.MagicHex); !bytes.Equal(got, recovery.Magic[:]) {
		t.Errorf("magic %x, this build writes %x", got, recovery.Magic)
	}
	if vf.Format.FormatVersion != int(recovery.FormatVersion) {
		t.Errorf("format_version %d, this build writes %d", vf.Format.FormatVersion, recovery.FormatVersion)
	}
	if vf.Format.HeaderBytes != recovery.HeaderLen {
		t.Errorf("header_bytes %d, this build uses %d", vf.Format.HeaderBytes, recovery.HeaderLen)
	}
	if vf.Format.AuthenticatedHeaderBytes+vf.Format.NonceBytes != recovery.HeaderLen {
		t.Errorf("authenticated header %d + nonce %d does not reach the %d-byte header",
			vf.Format.AuthenticatedHeaderBytes, vf.Format.NonceBytes, recovery.HeaderLen)
	}

	if vf.Limits.MaxAcceptableMemoryKiB != recovery.MaxAcceptableMemoryKiB {
		t.Errorf("max_acceptable_memory_kib %d, this build enforces %d",
			vf.Limits.MaxAcceptableMemoryKiB, recovery.MaxAcceptableMemoryKiB)
	}
	if vf.Limits.MaxAcceptableIterations != recovery.MaxAcceptableIterations {
		t.Errorf("max_acceptable_iterations %d, this build enforces %d",
			vf.Limits.MaxAcceptableIterations, recovery.MaxAcceptableIterations)
	}
	if vf.Limits.SupportedParallelism != recovery.SupportedParallelism {
		t.Errorf("supported_parallelism %d, this build enforces %d",
			vf.Limits.SupportedParallelism, recovery.SupportedParallelism)
	}
}

// TestConformanceRecoveryDerivation is the Argon2id half on its own. It is the
// first thing to look at when a platform reports an open failure it cannot
// explain: if this passes there and the record still will not open, the fault
// is in the framing rather than in the KDF.
func TestConformanceRecoveryDerivation(t *testing.T) {
	vf := loadRecoveryVectors(t)
	for _, c := range vf.Derivation {
		t.Run(c.Name, func(t *testing.T) {
			got := recovery.DeriveKey(
				hexDecode(t, c.PassphraseHex), hexDecode(t, c.SaltHex), c.Params.params())
			if want := hexDecode(t, c.DerivedKeyHex); !bytes.Equal(got, want) {
				t.Errorf("derived key\n  got  %x\n  want %x", got, want)
			}
		})
	}
}

// TestConformanceRecoveryPositives checks both directions: the committed record
// must open, and re-sealing the same inputs must reproduce it byte for byte.
// Only the second direction catches an encoder that is self-consistently wrong.
func TestConformanceRecoveryPositives(t *testing.T) {
	vf := loadRecoveryVectors(t)
	for _, c := range vf.Positive {
		t.Run(c.Name, func(t *testing.T) {
			passphrase := hexDecode(t, c.PassphraseHex)
			record := hexDecode(t, c.RecordHex)
			content := hexDecode(t, c.ContentHex)

			opened, err := recovery.Open(passphrase, record)
			if err != nil {
				t.Fatalf("the committed record does not open: %v", err)
			}
			if !bytes.Equal(opened, content) {
				t.Errorf("opened content\n  got  %x\n  want %x", opened, content)
			}

			sealed, err := recovery.ExportWith(passphrase, content,
				hexDecode(t, c.SaltHex), hexDecode(t, c.NonceHex), c.Params.params())
			if err != nil {
				t.Fatalf("ExportWith: %v", err)
			}
			if !bytes.Equal(sealed, record) {
				t.Errorf("re-sealing the same inputs did not reproduce the record\n  got  %x\n  want %x",
					sealed, record)
			}

			header := hexDecode(t, c.AuthenticatedHeaderHex)
			if !bytes.Equal(record[:len(header)], header) {
				t.Errorf("authenticated header\n  got  %x\n  want %x", record[:len(header)], header)
			}
			if params, err := recovery.ParamsOf(record); err != nil {
				t.Errorf("ParamsOf: %v", err)
			} else if params != c.Params.params() {
				t.Errorf("header parameters %+v, vector says %+v", params, c.Params.params())
			}
		})
	}
}

// TestConformanceRecoveryNegatives runs every record that must be refused.
//
// The inflation cases are the reason this test can HANG rather than fail if the
// guard regresses: a build that derives before it validates will sit trying to
// allocate what the header asked for. That is stated here so the symptom is
// legible to whoever sees the timeout.
func TestConformanceRecoveryNegatives(t *testing.T) {
	vf := loadRecoveryVectors(t)

	families := map[string]int{}
	for _, c := range vf.Negative {
		families[c.Family]++
		t.Run(c.Name, func(t *testing.T) {
			_, err := recovery.Open(hexDecode(t, c.PassphraseHex), hexDecode(t, c.RecordHex))
			if err == nil {
				t.Fatal("the record was ACCEPTED")
			}
			var werr *wire.Error
			if !errors.As(err, &werr) {
				t.Fatalf("expected a coded error, got %T: %v", err, err)
			}
			if werr.Code != c.ExpectedError {
				t.Errorf("error code %s, vector expects %s", werr.Code, c.ExpectedError)
			}
		})
	}

	// Both attack families must actually be present. A vector file that lost its
	// inflation cases would leave every consumer green while testing half of what
	// the format defends against.
	for _, family := range []string{"downgrade", "inflation"} {
		if families[family] == 0 {
			t.Errorf("the vector family carries no %q cases", family)
		}
	}
}

// TestConformanceRecoveryFailuresAreIndistinguishable is TRS-CRY-013 read as a
// property of the whole negative set rather than of one pair: every case that
// reaches authentication must produce the identical message, so a caller cannot
// learn from the error which of them happened.
func TestConformanceRecoveryFailuresAreIndistinguishable(t *testing.T) {
	vf := loadRecoveryVectors(t)
	messages := map[string][]string{}
	for _, c := range vf.Negative {
		if c.ExpectedError != "ERR_CRYPTO_RECOVERY_OPEN_FAILED" {
			continue
		}
		_, err := recovery.Open(hexDecode(t, c.PassphraseHex), hexDecode(t, c.RecordHex))
		if err == nil {
			t.Fatalf("%s was accepted", c.Name)
		}
		messages[err.Error()] = append(messages[err.Error()], c.Name)
	}
	if len(messages) > 1 {
		t.Errorf("open failures are distinguishable by their message: %v", messages)
	}
}
