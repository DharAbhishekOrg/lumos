// Lumos - protocol/crypto/safety / Safety-number derivation: 60 digits + 6 emoji.
// Author: dhar.abhishek.email@gmail.com
// Implements: SRS-IDN-005, TRS-CRY-014
// ADRs: ADR-005, ADR-009
// Threading: any
// Notes: The number is SYMMETRIC by construction: both parties compute it from
// the sorted pair of identity keys, so either phone shows the same value. It
// deliberately binds identities only - not the match - so a re-match between
// the same two people keeps the number stable and re-verification is only
// demanded when an identity key actually changes (TRS-CRY-004). Each side's 30
// digits come from an iterated hash hardened with the register's iteration
// count; the 6 emoji come from the combined digest and the frozen 64-glyph
// ADR-009 alphabet, 6 bits per glyph.
package safety

import (
	"bytes"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/binary"
	"fmt"

	"github.com/dharabhishekemail-ops/lumos/protocol/gen/go/lumosconstants"
)

// DigitsLen and EmojiLen are the ADR-005 output shape.
const (
	DigitsLen = 60
	EmojiLen  = 6
)

// Number is a derived safety number.
type Number struct {
	// Digits is the 60-digit decimal string: 30 digits for the lexicographically
	// smaller identity key, then 30 for the larger.
	Digits string
	// EmojiIndices are the six alphabet positions (0..63).
	EmojiIndices [EmojiLen]int
	// Emoji are the corresponding glyphs from the frozen alphabet.
	Emoji [EmojiLen]string
}

// Derive computes the safety number for a pair of Ed25519 identity keys. The
// argument order does not matter.
func Derive(a, b ed25519.PublicKey) (*Number, error) {
	if len(a) != ed25519.PublicKeySize || len(b) != ed25519.PublicKeySize {
		return nil, fmt.Errorf("identity keys must be %d bytes", ed25519.PublicKeySize)
	}
	lo, hi := a, b
	if bytes.Compare(lo, hi) > 0 {
		lo, hi = hi, lo
	}

	n := &Number{Digits: digitsFor(lo) + digitsFor(hi)}

	// Emoji derive from the combined pair, so they change if either identity does.
	h := sha256.New()
	h.Write([]byte(lumosconstants.SafetyNumberKdfInfoValue))
	h.Write(lo)
	h.Write(hi)
	combined := h.Sum(nil)
	for i := 0; i < EmojiLen; i++ {
		idx := int(combined[i]) % len(lumosconstants.SafetyNumberEmojiAlphabetGlyphs)
		n.EmojiIndices[i] = idx
		n.Emoji[i] = lumosconstants.SafetyNumberEmojiAlphabetGlyphs[idx]
	}
	return n, nil
}

// digitsFor computes one identity's 30-digit half: an iterated SHA-256
// fingerprint (SAFETY_NUMBER_ITERATIONS rounds), then six 5-byte chunks each
// reduced mod 100000 to five decimal digits.
func digitsFor(pub ed25519.PublicKey) string {
	h := sha256.New()
	h.Write([]byte(lumosconstants.SafetyNumberKdfInfoValue))
	h.Write(pub)
	digest := h.Sum(nil)
	for i := 1; i < lumosconstants.SafetyNumberIterationsDefault; i++ {
		next := sha256.New()
		next.Write(digest)
		next.Write(pub)
		digest = next.Sum(nil)
	}

	var out []byte
	for chunk := 0; chunk < 6; chunk++ {
		var v uint64
		var eight [8]byte
		copy(eight[3:], digest[chunk*5:chunk*5+5])
		v = binary.BigEndian.Uint64(eight[:]) % 100000
		out = fmt.Appendf(out, "%05d", v)
	}
	return string(out)
}
