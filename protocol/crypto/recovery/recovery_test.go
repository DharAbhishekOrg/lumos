// Lumos - protocol/crypto/recovery / RecoveryRecord properties.
// Author: dhar.abhishek.email@gmail.com
// Implements: SRS-ONB-007, SRS-IDN-008, SRS-DAT-004, TRS-CRY-013
// ADRs: ADR-001, ADR-003
// Threading: any
// Notes: The two properties that matter most are negative ones - a record
// carries no key material, and a downgraded header does not produce a cheaper
// key - so most of this file is about what must NOT be true.
package recovery

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"testing"

	"github.com/dharabhishekemail-ops/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekemail-ops/lumos/protocol/gen/go/lumoserrors"
	"github.com/dharabhishekemail-ops/lumos/protocol/wire"
)

var passphrase = []byte("correct horse battery staple")

// codeOf extracts the stable error code, via errors.As rather than a type
// assertion so a wrapped error still resolves.
func codeOf(t *testing.T, err error) string {
	t.Helper()
	var werr *wire.Error
	if !errors.As(err, &werr) {
		t.Fatalf("expected a coded *wire.Error, got %T: %v", err, err)
	}
	return werr.Code
}

func TestExportOpenRoundTrips(t *testing.T) {
	content := []byte("profile content, block list, conversation history")
	record, err := Export(passphrase, content)
	if err != nil {
		t.Fatalf("Export: %v", err)
	}
	got, err := Open(passphrase, record)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	if !bytes.Equal(got, content) {
		t.Error("content did not survive the round trip")
	}
}

// TestTheRecordCarriesNoKeyMaterial is the Article-9 forensic pattern applied
// to key bytes: generate a real identity, export content that does NOT include
// it, and scan the whole blob for the private key. ADR-003 makes this a
// property of the format, and a scan is what a person with the file could do.
func TestTheRecordCarriesNoKeyMaterial(t *testing.T) {
	public, private, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("generate identity: %v", err)
	}
	content := []byte("alias=Sam\nbio=Reads maps for fun")

	record, err := Export(passphrase, content)
	if err != nil {
		t.Fatalf("Export: %v", err)
	}
	if bytes.Contains(record, private.Seed()) {
		t.Error("the identity private seed appears in the exported record (ADR-003 forbids it)")
	}
	if bytes.Contains(record, private) {
		t.Error("the identity private key appears in the exported record")
	}
	if bytes.Contains(record, public) {
		t.Error("even the identity public key must not ride along; restore mints a fresh identity")
	}
}

func TestTwoExportsOfTheSameContentDiffer(t *testing.T) {
	first, err := Export(passphrase, []byte("same"))
	if err != nil {
		t.Fatalf("Export: %v", err)
	}
	second, err := Export(passphrase, []byte("same"))
	if err != nil {
		t.Fatalf("Export: %v", err)
	}
	if bytes.Equal(first, second) {
		t.Error("a fresh salt and nonce per export are what stop two exports being linkable")
	}
}

func TestAWrongPassphraseFailsWithTheSameCodeAsTampering(t *testing.T) {
	record, err := Export(passphrase, []byte("content"))
	if err != nil {
		t.Fatalf("Export: %v", err)
	}

	_, wrongPass := Open([]byte("not the passphrase"), record)
	if got := codeOf(t, wrongPass); got != lumoserrors.ErrCryptoRecoveryOpenFailed {
		t.Errorf("wrong passphrase: got %s", got)
	}

	tampered := append([]byte(nil), record...)
	tampered[len(tampered)-1] ^= 0x01
	_, tamperErr := Open(passphrase, tampered)
	if got := codeOf(t, tamperErr); got != lumoserrors.ErrCryptoRecoveryOpenFailed {
		t.Errorf("tampered ciphertext: got %s", got)
	}
	// TRS-CRY-013: the two must be indistinguishable to a caller.
	if wrongPass.Error() != tamperErr.Error() {
		t.Errorf("a wrong passphrase and a tampered record must be indistinguishable:\n  %v\n  %v",
			wrongPass, tamperErr)
	}
}

func TestTamperingAnywhereInTheHeaderIsRejected(t *testing.T) {
	record, err := Export(passphrase, []byte("content"))
	if err != nil {
		t.Fatalf("Export: %v", err)
	}
	// Magic, version, each parameter field, and the salt.
	for _, at := range []int{0, 7, magicLen, magicLen + 1, magicLen + 6, magicLen + 9, HeaderLen - nonceLen - 1} {
		bent := append([]byte(nil), record...)
		bent[at] ^= 0x01
		if _, err := Open(passphrase, bent); err == nil {
			t.Errorf("a header edit at offset %d was accepted", at)
		}
	}
}

// TestADowngradedHeaderCannotProduceACheaperKey is the attack the parameter
// binding exists to stop: rewrite the work factors downward so a guessing
// attack is cheap. Two defences fire - the header is AEAD-authenticated, and
// Validate refuses anything below the register minima.
func TestADowngradedHeaderCannotProduceACheaperKey(t *testing.T) {
	record, err := Export(passphrase, []byte("content"))
	if err != nil {
		t.Fatalf("Export: %v", err)
	}
	weak := append([]byte(nil), record...)
	binary.BigEndian.PutUint32(weak[magicLen+versionLen:], 8) // 8 KiB
	binary.BigEndian.PutUint32(weak[magicLen+versionLen+memoryKiBLen:], 1)
	weak[magicLen+versionLen+memoryKiBLen+iterationsLen] = 1

	_, err = Open(passphrase, weak)
	if err == nil {
		t.Fatal("a downgraded record was accepted")
	}
	if got := codeOf(t, err); got != lumoserrors.ErrCryptoRecoveryParamsRejected {
		t.Errorf("expected the parameter rejection to fire first, got %s", got)
	}
}

func TestParamsBelowTheRegisterMinimumAreRefusedEvenWhenAuthentic(t *testing.T) {
	// An authentic record genuinely written with weak parameters - the case a
	// tamper check alone would miss, because the AEAD would verify.
	weakParams := Params{MemoryKiB: 8, Iterations: 1, Parallelism: 1}
	salt := make([]byte, saltLen)
	nonce := make([]byte, nonceLen)
	record, err := exportWith(passphrase, []byte("content"), salt, nonce, weakParams)
	if err != nil {
		t.Fatalf("exportWith: %v", err)
	}
	if _, err := Open(passphrase, record); codeOf(t, err) != lumoserrors.ErrCryptoRecoveryParamsRejected {
		t.Error("an authentic but weak record must still be refused")
	}
}

// TestStrongerThanTheRegisterMaximumIsStillAccepted pins forward
// compatibility: a record written by a later build with raised defaults must
// open here, or every upgrade would strand its own backups.
func TestStrongerThanTheRegisterMaximumIsStillAccepted(t *testing.T) {
	strong := Params{
		MemoryKiB:   uint32(lumosconstants.RecoveryArgon2idMemoryKibMax) * 2,
		Iterations:  uint32(lumosconstants.RecoveryArgon2idIterationsMax) + 1,
		Parallelism: uint8(lumosconstants.RecoveryArgon2idParallelism),
	}
	if err := strong.Validate(); err != nil {
		t.Errorf("a record above today's tuning maximum must remain openable: %v", err)
	}
}

// TestAbsurdParamsAreRefusedBeforeAnyDerivation is the resource-exhaustion
// guard. Without it, one flipped bit in the memory field turns 64 MiB into
// 16 GiB and Open sits there trying to allocate it - a hang or an OOM kill for
// anyone who can hand the user a file. The first version of this package had
// exactly that hole, and this test is what found it.
func TestAbsurdParamsAreRefusedBeforeAnyDerivation(t *testing.T) {
	cases := map[string]Params{
		"memory":      {MemoryKiB: MaxAcceptableMemoryKiB + 1, Iterations: 3, Parallelism: 1},
		"iterations":  {MemoryKiB: 65536, Iterations: MaxAcceptableIterations + 1, Parallelism: 1},
		"parallelism": {MemoryKiB: 65536, Iterations: 3, Parallelism: MaxAcceptableParallelism + 1},
	}
	for name, p := range cases {
		if err := p.Validate(); err == nil {
			t.Errorf("an absurd %s cost was accepted", name)
		}
	}

	// And end to end: a record whose header claims 16 GiB must be refused
	// rather than attempted. If this ever regresses, the test hangs instead of
	// failing - which is itself the symptom.
	record, err := Export(passphrase, []byte("content"))
	if err != nil {
		t.Fatalf("Export: %v", err)
	}
	bomb := append([]byte(nil), record...)
	binary.BigEndian.PutUint32(bomb[magicLen+versionLen:], MaxAcceptableMemoryKiB*8)
	if _, err := Open(passphrase, bomb); err == nil {
		t.Error("a memory-bomb record was accepted")
	}
}

func TestDefaultParamsAreTheRegisterValues(t *testing.T) {
	p := DefaultParams()
	if p.MemoryKiB != uint32(lumosconstants.RecoveryArgon2idMemoryKib) {
		t.Errorf("memory %d, register says %d", p.MemoryKiB, lumosconstants.RecoveryArgon2idMemoryKib)
	}
	if p.Iterations != uint32(lumosconstants.RecoveryArgon2idIterations) {
		t.Errorf("iterations %d, register says %d", p.Iterations, lumosconstants.RecoveryArgon2idIterations)
	}
	if err := p.Validate(); err != nil {
		t.Errorf("the register defaults must satisfy the register minima: %v", err)
	}
}

func TestATruncatedRecordIsRejectedRatherThanReadPastTheEnd(t *testing.T) {
	record, err := Export(passphrase, []byte("content"))
	if err != nil {
		t.Fatalf("Export: %v", err)
	}
	for _, n := range []int{0, 1, magicLen, HeaderLen - 1, HeaderLen} {
		if _, err := Open(passphrase, record[:n]); err == nil {
			t.Errorf("a %d-byte record was accepted", n)
		}
	}
}

func TestAForeignFileIsNotMistakenForARecord(t *testing.T) {
	notARecord := bytes.Repeat([]byte("PNG\r\n"), 40)
	if _, err := Open(passphrase, notARecord); err == nil {
		t.Error("a non-record was accepted")
	}
}

func TestDescribeReadsTheHeaderWithoutThePassphrase(t *testing.T) {
	record, err := Export(passphrase, []byte("content"))
	if err != nil {
		t.Fatalf("Export: %v", err)
	}
	if got := Describe(record); !bytes.Contains([]byte(got), []byte("argon2id")) {
		t.Errorf("Describe = %q", got)
	}
}
