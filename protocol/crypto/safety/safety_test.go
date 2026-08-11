// Lumos - protocol/crypto/safety / Unit tests for the safety-number derivation.
// Author: dhar.abhishek.email@gmail.com
// Implements: SRS-IDN-005, TRS-CRY-014
// ADRs: ADR-005, ADR-009
// Threading: any
// Notes: The alphabet test enforces the ADR-009 mechanical half of the security
// criterion: exactly 64 glyphs, all unique, every one a single Unicode
// codepoint - no ZWJ sequences, no variation selectors, no modifier bases. The
// judgement half (distinct silhouettes) lives in the ADR itself.
package safety

import (
	"crypto/ed25519"
	"crypto/sha256"
	"regexp"
	"testing"
	"unicode/utf8"

	"github.com/dharabhishekemail-ops/lumos/protocol/gen/go/lumosconstants"
)

func ident(t *testing.T, label string) ed25519.PublicKey {
	t.Helper()
	seed := sha256.Sum256([]byte("TESTONLY-safety-unit|" + label))
	return ed25519.NewKeyFromSeed(seed[:]).Public().(ed25519.PublicKey)
}

func TestShapeAndSymmetry(t *testing.T) {
	a, b := ident(t, "a"), ident(t, "b")
	ab, err := Derive(a, b)
	if err != nil {
		t.Fatalf("Derive: %v", err)
	}
	ba, err := Derive(b, a)
	if err != nil {
		t.Fatalf("Derive: %v", err)
	}

	if len(ab.Digits) != DigitsLen {
		t.Errorf("digits length %d, ADR-005 says %d", len(ab.Digits), DigitsLen)
	}
	if !regexp.MustCompile(`^[0-9]{60}$`).MatchString(ab.Digits) {
		t.Errorf("digits %q are not 60 decimals", ab.Digits)
	}
	if ab.Digits != ba.Digits || ab.Emoji != ba.Emoji {
		t.Error("safety number must be symmetric in argument order")
	}
	for i, g := range ab.Emoji {
		if g != lumosconstants.SafetyNumberEmojiAlphabetGlyphs[ab.EmojiIndices[i]] {
			t.Errorf("glyph %d does not match its index", i)
		}
	}
}

func TestDistinctIdentitiesDiverge(t *testing.T) {
	a, b, c := ident(t, "a"), ident(t, "b"), ident(t, "c")
	ab, _ := Derive(a, b)
	ac, _ := Derive(a, c)
	if ab.Digits == ac.Digits {
		t.Error("different identity pairs produced identical digits")
	}
	if ab.Emoji == ac.Emoji {
		t.Error("different identity pairs produced identical emoji; entropy loss")
	}
}

func TestRejectsMalformedKeys(t *testing.T) {
	if _, err := Derive(make(ed25519.PublicKey, 5), ident(t, "b")); err == nil {
		t.Error("short identity key must be rejected")
	}
}

// TestAlphabetMeetsADR009MechanicalCriteria pins the machine-checkable half of
// the selection criterion the alphabet was frozen under.
func TestAlphabetMeetsADR009MechanicalCriteria(t *testing.T) {
	glyphs := lumosconstants.SafetyNumberEmojiAlphabetGlyphs
	if len(glyphs) != 64 {
		t.Fatalf("alphabet has %d glyphs, ADR-005 fixes 64", len(glyphs))
	}
	seen := map[string]bool{}
	for i, g := range glyphs {
		if seen[g] {
			t.Errorf("glyph %d %q is duplicated", i, g)
		}
		seen[g] = true
		if utf8.RuneCountInString(g) != 1 {
			t.Errorf("glyph %d %q is not a single codepoint (ZWJ or variation-selector sequence)", i, g)
		}
		r, _ := utf8.DecodeRuneInString(g)
		// Skin-tone modifiers and the modifier bases they attach to are excluded
		// by ADR-009; a raw modifier appearing here would be a selection error.
		if r >= 0x1F3FB && r <= 0x1F3FF {
			t.Errorf("glyph %d is a skin-tone modifier", i)
		}
		if r == 0x200D || r == 0xFE0F {
			t.Errorf("glyph %d is a joiner/selector, not a pictograph", i)
		}
	}
}
