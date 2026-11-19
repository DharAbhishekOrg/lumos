// Lumos - protocol/crypto/recovery / The ADR-003 content-only RecoveryRecord.
// Author: dhar.abhishek.email@gmail.com
// Implements: SRS-ONB-007, SRS-DAT-004, TRS-CRY-001, TRS-CRY-013
// ADRs: ADR-001, ADR-003
// Threading: any
// Notes: A passphrase-protected envelope, and the reference implementation the
// cross-language vectors pin. Three design points carry the requirements.
//
// THE RECORD KEY IS DELIBERATELY INDEPENDENT OF THE DEVICE KEK. It has to be:
// the whole purpose of a recovery record is to open on a DIFFERENT device,
// and anything the device keystore wrapped would be unopenable there by
// construction. So the key comes from the passphrase and nothing else, which
// also means the record's security is exactly the passphrase's - a limitation
// the threat model records as RR-5 rather than one this code can fix.
//
// THE PARAMETERS TRAVEL WITH THE RECORD, AND ARE AUTHENTICATED. A record must
// still open after the register's defaults move, so it carries the parameters
// it was written with. That invites a downgrade: edit the file to say "one
// pass, 8 KiB" and a guessing attack gets cheap. Two things prevent it. The
// parameters are part of the AEAD associated data, so an edited header fails
// authentication rather than producing a cheaper derivation; and Open refuses
// parameters below the register's minima outright, which also catches a record
// genuinely written by a build whose defaults were too low.
//
// CONTENT ONLY. Nothing here knows what key material looks like, and that is
// the point: this package seals whatever bytes it is handed, and the callers
// that assemble those bytes are the ones tested for never including private
// keys. RecoveryRecordTest scans an exported blob for the identity's own key
// bytes, which is the Article-9 forensic pattern applied to key material.

package recovery

import (
	"crypto/rand"
	"encoding/binary"
	"fmt"

	"golang.org/x/crypto/argon2"
	"golang.org/x/crypto/chacha20poly1305"

	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumoserrors"
	"github.com/dharabhishekorg/lumos/protocol/wire"
)

// Magic identifies a Lumos recovery record. Eight bytes, so a file-type sniff
// is unambiguous and a truncated record cannot be mistaken for a short one.
var Magic = [8]byte{'L', 'U', 'M', 'O', 'S', 'R', 'E', 'C'}

// FormatVersion is the only record layout this build writes or reads.
const FormatVersion uint8 = 1

// Layout of the authenticated header, in bytes.
const (
	magicLen       = 8
	versionLen     = 1
	memoryKiBLen   = 4
	iterationsLen  = 4
	parallelismLen = 1
	saltLen        = lumosconstants.RecoveryArgon2idSaltBytes
	nonceLen       = chacha20poly1305.NonceSize
	keyLen         = lumosconstants.RecoveryArgon2idKeyBytes

	// HeaderLen is everything before the ciphertext: the authenticated header
	// plus the nonce.
	HeaderLen = magicLen + versionLen + memoryKiBLen + iterationsLen + parallelismLen + saltLen + nonceLen
)

// Params are the Argon2id work factors a record was written with.
type Params struct {
	// MemoryKiB is the memory cost.
	MemoryKiB uint32
	// Iterations is the time cost.
	Iterations uint32
	// Parallelism is the lane count.
	Parallelism uint8
}

// DefaultParams are the register's frozen values.
func DefaultParams() Params {
	return Params{
		MemoryKiB:   uint32(lumosconstants.RecoveryArgon2idMemoryKib),
		Iterations:  uint32(lumosconstants.RecoveryArgon2idIterations),
		Parallelism: uint8(lumosconstants.RecoveryArgon2idParallelism),
	}
}

// Resource ceilings, distinct from the register's tuning bounds.
//
// These exist because the first version of Validate enforced only the minima,
// on the reasoning that "more work is expensive, not weak, and refusing it
// would destroy a user's data". That reasoning was incomplete and the tests
// found it: flipping one bit in the memory field of a record turns 64 MiB into
// 16 GiB, and Open then sits there trying to allocate it. An attacker who can
// hand the user a file gets a hang or an out-of-memory kill for free - the same
// resource-exhaustion shape as a decompression bomb, which this project already
// guards against in media.
//
// So there is a ceiling, and it is deliberately NOT the register's maximum. The
// register's max is a tuning bound for what THIS build writes; a record written
// by a later build with raised defaults must still open here, or every upgrade
// would strand its own backups. These are absolute limits on what any record
// may ask this process to do: far above any plausible parameter set, far below
// anything that threatens the device. Named local constants with rationale,
// which TRS-CODE-005 permits.
const (
	// MaxAcceptableMemoryKiB is 1 GiB. Two orders of magnitude above the
	// register default and beyond any parameter set a real device would write.
	MaxAcceptableMemoryKiB = 1024 * 1024
	// MaxAcceptableIterations bounds the time cost.
	MaxAcceptableIterations = 64
)

// SupportedParallelism is the only Argon2id lane count this format admits, and
// the reason is portability rather than resource cost.
//
// S4b found it. libsodium's crypto_pwhash fixes Argon2's lane and thread count
// at one - it exposes no multi-lane entry point at all - and libsodium IS the
// binding on both shipping platforms (Lazysodium on Android, swift-sodium on
// iOS). A record declaring two lanes would therefore open in this Go reference
// and be unopenable on every device the app actually ships to: a recovery
// record no user could recover from, which is worse than one refused at the
// door.
//
// So all three implementations refuse anything but a single lane, and the
// cross-language vectors pin the refusal. Note what this is NOT: it is not a
// resource ceiling in the sense of the two above, which sit far ABOVE the
// register's tuning maximum so a later build's stronger record still opens.
// This is a portability floor, and CR-001 made the register say so: the entry
// carried parallelism_min/parallelism_max until S4c removed them, because a
// range of legal lane counts never existed. RECOVERY_ARGON2ID.parallelism is
// now a fixed constant, and TestTheRegisterAgreesWithTheSingleLaneRule pins
// this value to it.
const SupportedParallelism uint8 = 1

// Validate refuses work factors below the register's minima, above the absolute
// resource ceilings above, and outside the single-lane portability rule.
func (p Params) Validate() error {
	if p.MemoryKiB > MaxAcceptableMemoryKiB {
		return &wire.Error{Code: lumoserrors.ErrCryptoRecoveryParamsRejected,
			Detail: "memory cost beyond the acceptable ceiling"}
	}
	if p.Iterations > MaxAcceptableIterations {
		return &wire.Error{Code: lumoserrors.ErrCryptoRecoveryParamsRejected,
			Detail: "time cost beyond the acceptable ceiling"}
	}
	if p.Parallelism != SupportedParallelism {
		return &wire.Error{Code: lumoserrors.ErrCryptoRecoveryParamsRejected,
			Detail: "only a single Argon2id lane is executable on every platform"}
	}
	if p.MemoryKiB < uint32(lumosconstants.RecoveryArgon2idMemoryKibMin) {
		return &wire.Error{Code: lumoserrors.ErrCryptoRecoveryParamsRejected,
			Detail: "memory cost below the register minimum"}
	}
	if p.Iterations < uint32(lumosconstants.RecoveryArgon2idIterationsMin) {
		return &wire.Error{Code: lumoserrors.ErrCryptoRecoveryParamsRejected,
			Detail: "time cost below the register minimum"}
	}
	// No separate parallelism minimum to check: CR-001 removed the bounds, and
	// the single-lane rule above admits exactly the register's fixed value.
	return nil
}

// DeriveKey runs Argon2id over a passphrase and salt.
//
// Exported because the cross-language vectors pin the derivation itself, not
// only the sealed record: a platform whose binding disagreed here would
// otherwise fail with an indistinguishable authentication error and no clue
// which half was wrong.
func DeriveKey(passphrase []byte, salt []byte, p Params) []byte {
	return argon2.IDKey(passphrase, salt, p.Iterations, p.MemoryKiB, p.Parallelism, keyLen)
}

// Export seals content under a passphrase-derived key.
//
// The salt is fresh per export, so exporting the same content twice with the
// same passphrase produces different bytes - which is required, and which the
// vectors accommodate by fixing the salt rather than the output.
func Export(passphrase []byte, content []byte) ([]byte, error) {
	salt := make([]byte, saltLen)
	if _, err := rand.Read(salt); err != nil {
		return nil, &wire.Error{Code: lumoserrors.ErrCryptoRecoveryOpenFailed, Detail: "salt generation failed"}
	}
	nonce := make([]byte, nonceLen)
	if _, err := rand.Read(nonce); err != nil {
		return nil, &wire.Error{Code: lumoserrors.ErrCryptoRecoveryOpenFailed, Detail: "nonce generation failed"}
	}
	return ExportWith(passphrase, content, salt, nonce, DefaultParams())
}

// ExportWith is the deterministic core of Export, with the salt, nonce and work
// factors supplied rather than generated.
//
// Exported for one caller only: tools/vectorgen, which cannot pin a record
// whose salt and nonce are fresh per call. Application code wants Export - a
// caller that chooses its own salt can repeat one, and a repeated (key, nonce)
// pair under ChaCha20-Poly1305 is a total break rather than a weakness.
func ExportWith(passphrase, content, salt, nonce []byte, p Params) ([]byte, error) {
	if len(salt) != saltLen || len(nonce) != nonceLen {
		return nil, &wire.Error{Code: lumoserrors.ErrCryptoRecoveryOpenFailed, Detail: "bad salt or nonce length"}
	}
	header := authenticatedHeader(p, salt)
	key := DeriveKey(passphrase, salt, p)
	aead, err := chacha20poly1305.New(key)
	if err != nil {
		return nil, &wire.Error{Code: lumoserrors.ErrCryptoRecoveryOpenFailed, Detail: "aead construction failed"}
	}
	out := make([]byte, 0, HeaderLen+len(content)+aead.Overhead())
	out = append(out, header...)
	out = append(out, nonce...)
	return aead.Seal(out, nonce, content, header), nil
}

// Open recovers the content, refusing anything it cannot fully authenticate.
//
// Every failure returns the same code and no distinguishing detail: a wrong
// passphrase, a tampered header and a corrupted ciphertext are one answer,
// because telling them apart is an oracle over the passphrase (TRS-CRY-013).
// The one exception is a parameter set below the register minima, which is
// reported separately - that is a property of the FILE, learnable by reading
// it, so naming it discloses nothing and telling the user their old export is
// too weak to trust is worth more than the uniformity.
func Open(passphrase []byte, record []byte) ([]byte, error) {
	if len(record) < HeaderLen {
		return nil, &wire.Error{Code: lumoserrors.ErrCryptoRecoveryOpenFailed, Detail: "record could not be opened"}
	}
	if [8]byte(record[:magicLen]) != Magic {
		return nil, &wire.Error{Code: lumoserrors.ErrCryptoRecoveryOpenFailed, Detail: "record could not be opened"}
	}
	if record[magicLen] != FormatVersion {
		return nil, &wire.Error{Code: lumoserrors.ErrCryptoRecoveryOpenFailed, Detail: "record could not be opened"}
	}

	at := magicLen + versionLen
	p := Params{
		MemoryKiB:   binary.BigEndian.Uint32(record[at : at+memoryKiBLen]),
		Iterations:  binary.BigEndian.Uint32(record[at+memoryKiBLen : at+memoryKiBLen+iterationsLen]),
		Parallelism: record[at+memoryKiBLen+iterationsLen],
	}
	if err := p.Validate(); err != nil {
		return nil, err
	}

	salt := record[at+memoryKiBLen+iterationsLen+parallelismLen : HeaderLen-nonceLen]
	nonce := record[HeaderLen-nonceLen : HeaderLen]
	header := record[:HeaderLen-nonceLen]

	aead, err := chacha20poly1305.New(DeriveKey(passphrase, salt, p))
	if err != nil {
		return nil, &wire.Error{Code: lumoserrors.ErrCryptoRecoveryOpenFailed, Detail: "record could not be opened"}
	}
	content, err := aead.Open(nil, nonce, record[HeaderLen:], header)
	if err != nil {
		return nil, &wire.Error{Code: lumoserrors.ErrCryptoRecoveryOpenFailed, Detail: "record could not be opened"}
	}
	return content, nil
}

// ParamsOf reads a record's declared work factors without opening it, so a
// caller can warn about an old weak export before asking for a passphrase.
func ParamsOf(record []byte) (Params, error) {
	if len(record) < HeaderLen {
		return Params{}, &wire.Error{Code: lumoserrors.ErrCryptoRecoveryOpenFailed, Detail: "record could not be opened"}
	}
	at := magicLen + versionLen
	return Params{
		MemoryKiB:   binary.BigEndian.Uint32(record[at : at+memoryKiBLen]),
		Iterations:  binary.BigEndian.Uint32(record[at+memoryKiBLen : at+memoryKiBLen+iterationsLen]),
		Parallelism: record[at+memoryKiBLen+iterationsLen],
	}, nil
}

// authenticatedHeader is the AEAD associated data: magic, version, parameters
// and salt. Binding the parameters is what makes a downgrade edit fail
// authentication instead of producing a cheaper key.
func authenticatedHeader(p Params, salt []byte) []byte {
	header := make([]byte, 0, HeaderLen-nonceLen)
	header = append(header, Magic[:]...)
	header = append(header, FormatVersion)
	header = binary.BigEndian.AppendUint32(header, p.MemoryKiB)
	header = binary.BigEndian.AppendUint32(header, p.Iterations)
	header = append(header, p.Parallelism)
	return append(header, salt...)
}

// Describe renders a record's header for diagnostics. It never touches the
// ciphertext, so it is safe to log.
func Describe(record []byte) string {
	p, err := ParamsOf(record)
	if err != nil {
		return "unreadable recovery record"
	}
	return fmt.Sprintf("recovery record: argon2id m=%d KiB t=%d p=%d", p.MemoryKiB, p.Iterations, p.Parallelism)
}
