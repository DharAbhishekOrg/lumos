/*
 * Lumos - android-app/protocol-java / The in-venue verification token, mirrored.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-MAT-008
 * ADRs: ADR-005, ADR-009
 * Threading: any
 * Notes: The Go reference bit for bit: SHA-256(MATCH_TOKEN_KDF_INFO |
 * match_id), first two 6-bit groups into the frozen emoji alphabet, next 3
 * bits into the named colour palette. Both sides derive it from the match
 * identifier alone; the venue comparison is two screens side by side.
 */

package com.lumos.protocol.match;

import com.lumos.protocol.LumosConstants;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** The SRS-MAT-008 shared verification token. */
public final class MatchToken {

    private final int emojiFirst;
    private final int emojiSecond;
    private final int colorIndex;

    private MatchToken(final int emojiFirst, final int emojiSecond, final int colorIndex) {
        this.emojiFirst = emojiFirst;
        this.emojiSecond = emojiSecond;
        this.colorIndex = colorIndex;
    }

    /**
     * Derives the token for a match identifier.
     *
     * @param matchId MATCH_ID_LEN bytes
     * @return the token
     */
    public static MatchToken derive(final byte[] matchId) {
        if (matchId.length != LumosConstants.MATCH_ID_LEN_DEFAULT) {
            throw new IllegalArgumentException("token needs a "
                    + LumosConstants.MATCH_ID_LEN_DEFAULT + "-byte match id");
        }
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(LumosConstants.MATCH_TOKEN_KDF_INFO_VALUE
                    .getBytes(StandardCharsets.UTF_8));
            digest.update(matchId);
            final byte[] sum = digest.digest();
            final int bits = ((sum[0] & 0xFF) << 8) | (sum[1] & 0xFF);
            return new MatchToken((bits >> 10) & 0x3F, (bits >> 4) & 0x3F, (bits >> 1) & 0x07);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every platform", e);
        }
    }

    /** @return the first emoji's alphabet position (0..63) */
    public int emojiFirstIndex() {
        return emojiFirst;
    }

    /** @return the second emoji's alphabet position (0..63) */
    public int emojiSecondIndex() {
        return emojiSecond;
    }

    /** @return the palette position (0..7) */
    public int colorIndex() {
        return colorIndex;
    }

    /** @return the pair's glyphs from the frozen alphabet */
    public String[] emoji() {
        return new String[] {
            LumosConstants.SAFETY_NUMBER_EMOJI_ALPHABET_GLYPHS.get(emojiFirst),
            LumosConstants.SAFETY_NUMBER_EMOJI_ALPHABET_GLYPHS.get(emojiSecond),
        };
    }

    /** @return the pulse colour's accessible name */
    public String colorName() {
        return LumosConstants.MATCH_TOKEN_COLORS_NAMES.get(colorIndex);
    }

    /** @return the pulse colour's displayed value */
    public String colorHex() {
        return LumosConstants.MATCH_TOKEN_COLORS_HEX.get(colorIndex);
    }
}
