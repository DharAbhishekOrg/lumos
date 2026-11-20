// Lumos - protocol/match / The in-venue verification token.
// Author: dhar.abhishek.email@gmail.com
// Implements: SRS-MAT-008
// ADRs: ADR-005, ADR-009
// Threading: any
// Notes: Both halves of a match derive the token from the match identifier
// alone, so no message carries it and the venue comparison is just two
// screens held side by side: the same emoji pair, the same named colour
// pulse. SHA-256(MATCH_TOKEN_KDF_INFO | match_id); the first two 6-bit
// groups index the frozen ADR-009 emoji alphabet, the next 3 bits the named
// colour palette. A human co-presence check, not a secret - what it defeats
// is the stranger claiming "I'm your match" across a crowded room; the
// cryptographic verification underneath is the safety number (SRS-IDN-005).

package match

import (
	"crypto/sha256"
	"fmt"

	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
)

// Token is the SRS-MAT-008 shared verification token.
type Token struct {
	// EmojiIndices are the two alphabet positions (0..63).
	EmojiIndices [2]int
	// ColorIndex is the palette position (0..7).
	ColorIndex int
}

// Emoji are the pair's glyphs from the frozen alphabet.
func (t Token) Emoji() [2]string {
	return [2]string{
		lumosconstants.SafetyNumberEmojiAlphabetGlyphs[t.EmojiIndices[0]],
		lumosconstants.SafetyNumberEmojiAlphabetGlyphs[t.EmojiIndices[1]],
	}
}

// ColorName is the pulse colour's accessible name.
func (t Token) ColorName() string {
	return lumosconstants.MatchTokenColorsNames[t.ColorIndex]
}

// ColorHex is the pulse colour's displayed value.
func (t Token) ColorHex() string {
	return lumosconstants.MatchTokenColorsHex[t.ColorIndex]
}

// DeriveToken computes the token for a match identifier.
func DeriveToken(matchID []byte) (Token, error) {
	if len(matchID) != lumosconstants.MatchIDLenDefault {
		return Token{}, fmt.Errorf("match: token needs a %d-byte match id, got %d",
			lumosconstants.MatchIDLenDefault, len(matchID))
	}
	h := sha256.New()
	h.Write([]byte(lumosconstants.MatchTokenKdfInfoValue))
	h.Write(matchID)
	sum := h.Sum(nil)

	// 15 bits, big-endian bit order: 6 + 6 + 3.
	bits := int(sum[0])<<8 | int(sum[1])
	return Token{
		EmojiIndices: [2]int{(bits >> 10) & 0x3F, (bits >> 4) & 0x3F},
		ColorIndex:   (bits >> 1) & 0x07,
	}, nil
}
