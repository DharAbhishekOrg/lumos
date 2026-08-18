// Lumos - tools/vectorgen / The cross-language RecoveryRecord vector family.
// Author: dhar.abhishek.email@gmail.com
// Implements: SRS-ONB-007, SRS-DAT-004, TRS-CRY-001, TRS-CRY-013, TRS-TST-001
// ADRs: ADR-001, ADR-003
// Threading: any
// Notes: The Go package in /protocol/crypto/recovery is the reference; this file
// pins what it produces so Android and iOS can be held to the same bytes rather
// than to a prose description of them.
//
// The family has three parts and each exists for a different failure.
//
//	derivation - Argon2id alone: passphrase, salt and work factors in, key out.
//	  Without it a platform whose binding disagreed about Argon2 would fail with
//	  an authentication error indistinguishable from a framing bug, and nobody
//	  would know which half was wrong.
//	positive   - whole sealed records with the salt and nonce fixed, plus the
//	  authenticated header on its own so an endianness mistake in the parameter
//	  fields is caught exactly where it happens.
//	negative   - the two attacks the format is shaped around. DOWNGRADE: edit
//	  the header to claim cheap work factors. INFLATION: edit it to claim absurd
//	  ones and let the reader allocate them. Both must be refused, and the
//	  inflation cases must be refused BEFORE any derivation is attempted - a
//	  consumer that validates afterwards passes the assertion and hangs.
//
// This file is NOT prescribed by TRS Sec. 18.4; see ExtensionFiles in suite.go.
package main

import (
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"math"

	"golang.org/x/crypto/chacha20poly1305"

	"github.com/dharabhishekemail-ops/lumos/protocol/crypto/recovery"
	"github.com/dharabhishekemail-ops/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekemail-ops/lumos/protocol/gen/go/lumoserrors"
	"github.com/dharabhishekemail-ops/lumos/protocol/wire"
)

// errorCodeOf pulls the stable code out of a coded error, via errors.As so a
// wrapped error still resolves.
func errorCodeOf(err error) string {
	var werr *wire.Error
	if errors.As(err, &werr) {
		return werr.Code
	}
	return fmt.Sprintf("uncoded error (%T)", err)
}

// Record layout offsets, restated here so the generator can splice a header
// without the recovery package exposing its internals. recoveryLayoutIsIntact
// fails generation if the restatement and the real format ever part company.
const (
	recMagicLen       = 8
	recVersionLen     = 1
	recMemoryOff      = recMagicLen + recVersionLen
	recIterationsOff  = recMemoryOff + 4
	recParallelismOff = recIterationsOff + 4
	recSaltOff        = recParallelismOff + 1
)

// Passphrases and content. Self-describing words rather than hex blobs: the
// secret scanner flags hex-shaped fixtures, and a reader who greps one of these
// out of a failure message should be able to see it is a test value.
//
// The non-ASCII passphrase is written with \u escapes rather than literal
// characters, so no source file in this repository carries a byte above 0x7F
// while the vector still exercises one.
var (
	recPassphrase        = []byte("TESTONLY-lumos-recovery-passphrase")
	recWrongPassphrase   = []byte("TESTONLY-lumos-recovery-wrong-passphrase")
	recUnicodePassphrase = []byte("TESTONLY-lumos-recovery-p\u00e4ssphrase-\u2602")
	recContent           = []byte("TESTONLY-lumos-recovery-content: alias, block list, settings")
)

type recoveryParams struct {
	MemoryKiB   uint32 `json:"memory_kib"`
	Iterations  uint32 `json:"iterations"`
	Parallelism uint8  `json:"parallelism"`
}

func fromRecovery(p recovery.Params) recoveryParams {
	return recoveryParams{MemoryKiB: p.MemoryKiB, Iterations: p.Iterations, Parallelism: p.Parallelism}
}

// recoveryFormat is the wire layout, so a consumer can assert its own constants
// rather than rediscover them from a hex dump.
type recoveryFormat struct {
	MagicHex                 string `json:"magic_hex"`
	FormatVersion            int    `json:"format_version"`
	AuthenticatedHeaderBytes int    `json:"authenticated_header_bytes"`
	HeaderBytes              int    `json:"header_bytes"`
	SaltBytes                int    `json:"salt_bytes"`
	NonceBytes               int    `json:"nonce_bytes"`
	KeyBytes                 int    `json:"key_bytes"`
	TagBytes                 int    `json:"tag_bytes"`
}

// recoveryLimits are the bounds every implementation must agree on. They are
// published so a consumer can assert its own values match, which catches the
// drift a negative case alone would not: a platform whose ceiling is merely
// different still rejects the one absurd record in this file.
type recoveryLimits struct {
	RegisterMemoryKiBMin    uint32 `json:"register_memory_kib_min"`
	RegisterIterationsMin   uint32 `json:"register_iterations_min"`
	MaxAcceptableMemoryKiB  uint32 `json:"max_acceptable_memory_kib"`
	MaxAcceptableIterations uint32 `json:"max_acceptable_iterations"`
	SupportedParallelism    uint8  `json:"supported_parallelism"`
}

type recoveryDerivation struct {
	Name          string         `json:"name"`
	Description   string         `json:"description"`
	PassphraseHex string         `json:"passphrase_hex"`
	SaltHex       string         `json:"salt_hex"`
	Params        recoveryParams `json:"params"`
	DerivedKeyHex string         `json:"derived_key_hex"`
}

type recoveryPositive struct {
	Name                   string         `json:"name"`
	Description            string         `json:"description"`
	PassphraseHex          string         `json:"passphrase_hex"`
	SaltHex                string         `json:"salt_hex"`
	NonceHex               string         `json:"nonce_hex"`
	Params                 recoveryParams `json:"params"`
	ContentHex             string         `json:"content_hex"`
	AuthenticatedHeaderHex string         `json:"authenticated_header_hex"`
	RecordHex              string         `json:"record_hex"`
}

type recoveryNegative struct {
	Name          string `json:"name"`
	Family        string `json:"family"`
	Description   string `json:"description"`
	PassphraseHex string `json:"passphrase_hex"`
	RecordHex     string `json:"record_hex"`
	ExpectedError string `json:"expected_error"`
	// RejectBeforeDerivation marks a record that must be refused from its header
	// alone. A consumer that derives first still returns the right error and
	// still passes - after allocating what the header asked for.
	RejectBeforeDerivation bool `json:"reject_before_derivation"`
}

type recoveryFile struct {
	fileMeta
	Format     recoveryFormat       `json:"format"`
	Limits     recoveryLimits       `json:"limits"`
	Derivation []recoveryDerivation `json:"derivation"`
	Positive   []recoveryPositive   `json:"positive"`
	Negative   []recoveryNegative   `json:"negative"`
}

// recoveryLayoutIsIntact checks the offsets restated above against the format
// the recovery package actually writes. Generation stops rather than emitting
// vectors that splice the wrong bytes.
func recoveryLayoutIsIntact() error {
	if len(recovery.Magic) != recMagicLen {
		return fmt.Errorf("recovery magic is %d bytes, the generator assumes %d",
			len(recovery.Magic), recMagicLen)
	}
	want := recSaltOff + lumosconstants.RecoveryArgon2idSaltBytes + chacha20poly1305.NonceSize
	if recovery.HeaderLen != want {
		return fmt.Errorf("recovery.HeaderLen is %d, the generator's layout gives %d",
			recovery.HeaderLen, want)
	}
	return nil
}

// editMemory, editIterations, editParallelism and editByte rewrite a COPY of a
// record, which is exactly what an attacker holding the file can do.
func editMemory(record []byte, kib uint32) []byte {
	out := append([]byte(nil), record...)
	binary.BigEndian.PutUint32(out[recMemoryOff:], kib)
	return out
}

func editIterations(record []byte, iterations uint32) []byte {
	out := append([]byte(nil), record...)
	binary.BigEndian.PutUint32(out[recIterationsOff:], iterations)
	return out
}

func editParallelism(record []byte, lanes uint8) []byte {
	out := append([]byte(nil), record...)
	out[recParallelismOff] = lanes
	return out
}

func editByte(record []byte, at int, mask byte) []byte {
	out := append([]byte(nil), record...)
	out[at] ^= mask
	return out
}

// buildRecoveryVectors emits the whole family.
func buildRecoveryVectors(constantsHash string) (*recoveryFile, error) {
	if err := recoveryLayoutIsIntact(); err != nil {
		return nil, err
	}

	defaults := recovery.DefaultParams()
	minima := recovery.Params{
		MemoryKiB:   uint32(lumosconstants.RecoveryArgon2idMemoryKibMin),
		Iterations:  uint32(lumosconstants.RecoveryArgon2idIterationsMin),
		Parallelism: recovery.SupportedParallelism,
	}

	file := &recoveryFile{
		fileMeta: fileMeta{
			SchemaVersion: 1, Generator: GeneratorName, ConstantsHash: constantsHash,
			Implements: []string{
				"SRS-ONB-007", "SRS-DAT-004",
				"TRS-CRY-001", "TRS-CRY-013", "TRS-TST-001",
			},
			KeyProvenance: KeyProvenance,
			Notes: "The ADR-003 content-only RecoveryRecord, pinned across Go, Java and Swift. " +
				"Argon2id (v1.3) then ChaCha20-Poly1305 (IETF, 12-byte nonce) with the " +
				"authenticated header - magic, version, work factors and salt - as associated " +
				"data. Salt and nonce are FIXED here; a real export draws both fresh, which is " +
				"why the vectors pin inputs rather than an output of Export. The negative set " +
				"carries both attacks the format is shaped around: downgrade (claim cheap work " +
				"factors) and inflation (claim absurd ones and let the reader allocate them). " +
				"Cases flagged reject_before_derivation must be refused from the header alone.",
		},
		Format: recoveryFormat{
			MagicHex:                 hex.EncodeToString(recovery.Magic[:]),
			FormatVersion:            int(recovery.FormatVersion),
			AuthenticatedHeaderBytes: recovery.HeaderLen - chacha20poly1305.NonceSize,
			HeaderBytes:              recovery.HeaderLen,
			SaltBytes:                lumosconstants.RecoveryArgon2idSaltBytes,
			NonceBytes:               chacha20poly1305.NonceSize,
			KeyBytes:                 lumosconstants.RecoveryArgon2idKeyBytes,
			TagBytes:                 chacha20poly1305.Overhead,
		},
		Limits: recoveryLimits{
			RegisterMemoryKiBMin:    uint32(lumosconstants.RecoveryArgon2idMemoryKibMin),
			RegisterIterationsMin:   uint32(lumosconstants.RecoveryArgon2idIterationsMin),
			MaxAcceptableMemoryKiB:  recovery.MaxAcceptableMemoryKiB,
			MaxAcceptableIterations: recovery.MaxAcceptableIterations,
			SupportedParallelism:    recovery.SupportedParallelism,
		},
	}

	file.Derivation = recoveryDerivations(defaults, minima)

	positives, err := recoveryPositives(defaults, minima)
	if err != nil {
		return nil, err
	}
	file.Positive = positives

	negatives, err := recoveryNegatives(defaults)
	if err != nil {
		return nil, err
	}
	file.Negative = negatives

	return file, nil
}

// recoveryDerivations pins Argon2id on its own, so a binding that disagrees
// about the KDF is told so instead of reporting a generic open failure.
func recoveryDerivations(defaults, minima recovery.Params) []recoveryDerivation {
	saltA := testonlyBytes("recovery|derive|salt|a", lumosconstants.RecoveryArgon2idSaltBytes)
	saltB := testonlyBytes("recovery|derive|salt|b", lumosconstants.RecoveryArgon2idSaltBytes)

	specs := []struct {
		name, description string
		passphrase, salt  []byte
		params            recovery.Params
	}{
		{
			"register_defaults",
			"The register's frozen work factors: 64 MiB, 3 passes, one lane.",
			recPassphrase, saltA, defaults,
		},
		{
			"register_minima",
			"The weakest parameters an import still accepts. The boundary is inclusive, " +
				"so an implementation that wrote > where it meant >= fails here rather " +
				"than in the field.",
			recPassphrase, saltB, minima,
		},
		{
			"empty_passphrase",
			"Argon2id accepts a zero-length password. Pinned because a binding that " +
				"rejects it, or that silently substitutes something, would otherwise " +
				"surface only as an unopenable record for the one user who did it.",
			nil, saltA, defaults,
		},
		{
			"non_ascii_passphrase",
			"A passphrase with characters outside ASCII, as UTF-8. Not decoration: the " +
				"obvious Swift binding for crypto_pwhash maps the password bytes through " +
				"Int8(_:), which TRAPS on any byte above 0x7F - a crash, not an error, for " +
				"every user whose passphrase has an accent in it.",
			recUnicodePassphrase, saltB, defaults,
		},
	}

	out := make([]recoveryDerivation, 0, len(specs))
	for _, s := range specs {
		out = append(out, recoveryDerivation{
			Name:          s.name,
			Description:   s.description,
			PassphraseHex: hex.EncodeToString(s.passphrase),
			SaltHex:       hex.EncodeToString(s.salt),
			Params:        fromRecovery(s.params),
			DerivedKeyHex: hex.EncodeToString(recovery.DeriveKey(s.passphrase, s.salt, s.params)),
		})
	}
	return out
}

// recoveryPositives pins whole records, and the authenticated header alongside
// each one so a parameter-encoding mistake is located rather than merely
// detected.
func recoveryPositives(defaults, minima recovery.Params) ([]recoveryPositive, error) {
	specs := []struct {
		name, description     string
		passphrase            []byte
		saltLabel, nonceLabel string
		params                recovery.Params
		content               []byte
	}{
		{
			"defaults_roundtrip",
			"An ordinary export at the register's defaults. Sealing must reproduce these " +
				"exact bytes from these exact inputs; opening must return the content.",
			recPassphrase, "recovery|seal|salt|defaults", "recovery|seal|nonce|defaults",
			defaults, recContent,
		},
		{
			"empty_content",
			"A record sealing nothing. The ciphertext is the tag alone, which is the " +
				"length arithmetic most likely to be wrong on a platform that assumed a " +
				"non-empty plaintext.",
			recPassphrase, "recovery|seal|salt|empty", "recovery|seal|nonce|empty",
			defaults, nil,
		},
		{
			"register_minima_accepted",
			"An authentic record written at the minima. It must OPEN, not merely " +
				"validate - the pair to downgrade_authentic_weak_record below, which is one " +
				"step weaker and must be refused.",
			recPassphrase, "recovery|seal|salt|minima", "recovery|seal|nonce|minima",
			minima, recContent,
		},
	}

	out := make([]recoveryPositive, 0, len(specs))
	for _, s := range specs {
		salt := testonlyBytes(s.saltLabel, lumosconstants.RecoveryArgon2idSaltBytes)
		nonce := testonlyBytes(s.nonceLabel, chacha20poly1305.NonceSize)
		record, err := recovery.ExportWith(s.passphrase, s.content, salt, nonce, s.params)
		if err != nil {
			return nil, fmt.Errorf("recovery positive %s: %w", s.name, err)
		}
		// The generator is also the first consumer: a record it cannot reopen is
		// never written to disk.
		got, err := recovery.Open(s.passphrase, record)
		if err != nil {
			return nil, fmt.Errorf("recovery positive %s does not reopen: %w", s.name, err)
		}
		if string(got) != string(s.content) {
			return nil, fmt.Errorf("recovery positive %s reopened to different content", s.name)
		}
		out = append(out, recoveryPositive{
			Name:                   s.name,
			Description:            s.description,
			PassphraseHex:          hex.EncodeToString(s.passphrase),
			SaltHex:                hex.EncodeToString(salt),
			NonceHex:               hex.EncodeToString(nonce),
			Params:                 fromRecovery(s.params),
			ContentHex:             hex.EncodeToString(s.content),
			AuthenticatedHeaderHex: hex.EncodeToString(record[:recovery.HeaderLen-chacha20poly1305.NonceSize]),
			RecordHex:              hex.EncodeToString(record),
		})
	}
	return out, nil
}

// recoveryNegatives builds every record that must be refused, and checks each
// one against the reference before emitting it: a "negative" the reference
// happens to accept would be a vector that teaches the wrong thing.
func recoveryNegatives(defaults recovery.Params) ([]recoveryNegative, error) {
	salt := testonlyBytes("recovery|negative|salt", lumosconstants.RecoveryArgon2idSaltBytes)
	nonce := testonlyBytes("recovery|negative|nonce", chacha20poly1305.NonceSize)
	sound, err := recovery.ExportWith(recPassphrase, recContent, salt, nonce, defaults)
	if err != nil {
		return nil, err
	}

	// An authentic record written with genuinely weak factors: the AEAD verifies,
	// so only the minima check can catch it. Built with ExportWith directly,
	// because no Lumos build would ever produce it.
	weak, err := recovery.ExportWith(recPassphrase, recContent, salt, nonce,
		recovery.Params{MemoryKiB: 8, Iterations: 1, Parallelism: recovery.SupportedParallelism})
	if err != nil {
		return nil, err
	}

	specs := []struct {
		name, family, description string
		passphrase, record        []byte
		expected                  string
		early                     bool
	}{
		{
			"downgrade_memory_below_minimum", "downgrade",
			"The header rewritten to claim 8 KiB. Refused on the register minimum, " +
				"before the cheap derivation the attacker was shopping for.",
			recPassphrase, editMemory(sound, 8),
			lumoserrors.ErrCryptoRecoveryParamsRejected, true,
		},
		{
			"downgrade_iterations_below_minimum", "downgrade",
			"The header rewritten to claim a single pass.",
			recPassphrase, editIterations(sound, 1),
			lumoserrors.ErrCryptoRecoveryParamsRejected, true,
		},
		{
			"downgrade_authentic_weak_record", "downgrade",
			"Not an edit: a record genuinely SEALED at 8 KiB and one pass, so the AEAD " +
				"verifies and only the minima check stands between the user and a backup " +
				"worth guessing at. This is the case a tamper check alone would pass.",
			recPassphrase, weak,
			lumoserrors.ErrCryptoRecoveryParamsRejected, true,
		},
		{
			"downgrade_within_bounds_fails_authentication", "downgrade",
			"The subtle half. The header is rewritten from 64 MiB to the 32 MiB minimum " +
				"- still legal parameters, so the bounds check has nothing to say. What " +
				"refuses it is that the work factors are AEAD associated data: the edited " +
				"header derives a different key AND authenticates different bytes, so the " +
				"attacker gets a failure rather than a cheaper guess.",
			recPassphrase, editMemory(sound, uint32(lumosconstants.RecoveryArgon2idMemoryKibMin)),
			lumoserrors.ErrCryptoRecoveryOpenFailed, false,
		},
		{
			"inflation_memory_above_ceiling", "inflation",
			"The header rewritten to demand 8 GiB. One flipped bit in this field turns " +
				"64 MiB into 16 GiB, and a reader that derives before it validates hangs or " +
				"is OOM-killed - denial of service for anyone who can hand the user a file.",
			recPassphrase, editMemory(sound, recovery.MaxAcceptableMemoryKiB*8),
			lumoserrors.ErrCryptoRecoveryParamsRejected, true,
		},
		{
			"inflation_memory_uint32_max", "inflation",
			"The memory field set to the largest value it can hold. It is here for the " +
				"sign bit rather than the size: a reader that holds this field in a SIGNED " +
				"32-bit integer sees -1, which is below every ceiling and above nothing, so a " +
				"ceiling check written the obvious way lets it through. Reject it, and reject " +
				"it as a parameter rejection rather than by accident.",
			recPassphrase, editMemory(sound, math.MaxUint32),
			lumoserrors.ErrCryptoRecoveryParamsRejected, true,
		},
		{
			"inflation_iterations_uint32_max", "inflation",
			"The same sign trap in the time-cost field.",
			recPassphrase, editIterations(sound, math.MaxUint32),
			lumoserrors.ErrCryptoRecoveryParamsRejected, true,
		},
		{
			"inflation_iterations_above_ceiling", "inflation",
			"The same attack in the time dimension: passes beyond the absolute ceiling.",
			recPassphrase, editIterations(sound, recovery.MaxAcceptableIterations+1),
			lumoserrors.ErrCryptoRecoveryParamsRejected, true,
		},
		{
			"inflation_parallelism_unsupported", "inflation",
			"Two lanes. libsodium's crypto_pwhash has no multi-lane entry point and it " +
				"is the binding on both shipping platforms, so a two-lane record would open " +
				"in Go and nowhere a user could use it. All three refuse it identically.",
			recPassphrase, editParallelism(sound, 2),
			lumoserrors.ErrCryptoRecoveryParamsRejected, true,
		},
		{
			"wrong_passphrase", "authentication",
			"The everyday failure. It must be reported with the same code and the same " +
				"detail as a tampered record: telling them apart is an oracle (TRS-CRY-013).",
			recWrongPassphrase, sound,
			lumoserrors.ErrCryptoRecoveryOpenFailed, false,
		},
		{
			"ciphertext_bit_flip", "authentication",
			"The last ciphertext byte flipped.",
			recPassphrase, editByte(sound, len(sound)-1, 0x01),
			lumoserrors.ErrCryptoRecoveryOpenFailed, false,
		},
		{
			"salt_edited", "authentication",
			"A byte of the salt flipped. The salt is inside the associated data, so this " +
				"fails authentication rather than quietly deriving from a different salt.",
			recPassphrase, editByte(sound, recSaltOff, 0x01),
			lumoserrors.ErrCryptoRecoveryOpenFailed, false,
		},
		{
			"foreign_magic", "format",
			"A file that is not a record at all. Refused on the magic, without a " +
				"passphrase prompt and without reading further.",
			recPassphrase, editByte(sound, 0, 0xFF),
			lumoserrors.ErrCryptoRecoveryOpenFailed, true,
		},
		{
			"unknown_format_version", "format",
			"A record claiming a layout this build does not know. Refused rather than " +
				"parsed optimistically.",
			recPassphrase, editByte(sound, recMagicLen, 0xFF),
			lumoserrors.ErrCryptoRecoveryOpenFailed, true,
		},
		{
			"truncated_to_header", "format",
			"Everything after the header removed. There is no ciphertext to open and no " +
				"tag to check.",
			recPassphrase, append([]byte(nil), sound[:recovery.HeaderLen]...),
			lumoserrors.ErrCryptoRecoveryOpenFailed, false,
		},
		{
			"truncated_mid_header", "format",
			"A record shorter than its own header. A reader that indexes before it " +
				"length-checks crashes here instead of returning an error.",
			recPassphrase, append([]byte(nil), sound[:recovery.HeaderLen-1]...),
			lumoserrors.ErrCryptoRecoveryOpenFailed, true,
		},
	}

	out := make([]recoveryNegative, 0, len(specs))
	for _, s := range specs {
		if err := recoveryRefusesAs(s.passphrase, s.record, s.expected); err != nil {
			return nil, fmt.Errorf("recovery negative %s: %w", s.name, err)
		}
		out = append(out, recoveryNegative{
			Name:                   s.name,
			Family:                 s.family,
			Description:            s.description,
			PassphraseHex:          hex.EncodeToString(s.passphrase),
			RecordHex:              hex.EncodeToString(s.record),
			ExpectedError:          s.expected,
			RejectBeforeDerivation: s.early,
		})
	}
	return out, nil
}

// recoveryRefusesAs runs the reference over a negative case before it is
// emitted, so the file cannot claim a refusal the reference does not make.
func recoveryRefusesAs(passphrase, record []byte, want string) error {
	if _, err := recovery.Open(passphrase, record); err != nil {
		if got := errorCodeOf(err); got != want {
			return fmt.Errorf("the reference reports %s, the vector claims %s", got, want)
		}
		return nil
	}
	return fmt.Errorf("the reference implementation ACCEPTED it")
}
