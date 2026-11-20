/*
 * Lumos - android-app/protocol-java / The stable match identifier, mirrored.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-MAT-007, SRS-MAT-014
 * ADRs: ADR-008
 * Threading: any
 * Notes: The Go reference's derivation, byte for byte and vector-pinned:
 * SHA-256(MATCH_ID_KDF_INFO | lower | higher) over the two PEER_ID
 * identifiers in lexicographic byte order, truncated to MATCH_ID_LEN.
 * Symmetric, so both sides of an accept-accept race derive the SAME
 * identifier without a message; independent of every rotating EID because
 * its inputs are. The same ordering is the race tiebreak: the
 * lexicographically lower identifier acts as session initiator.
 */

package com.lumos.protocol.match;

import com.lumos.protocol.LumosConstants;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Derives the SRS-MAT-007 stable match identifier. */
public final class MatchId {

    private MatchId() {
        throw new AssertionError("no instances");
    }

    /**
     * Derives the identifier; argument order does not matter.
     *
     * @param a one peer identifier, PEER_ID_LEN bytes
     * @param b the other peer identifier, PEER_ID_LEN bytes
     * @return MATCH_ID_LEN bytes
     */
    public static byte[] derive(final byte[] a, final byte[] b) {
        if (a.length != LumosConstants.PEER_ID_LEN_DEFAULT
                || b.length != LumosConstants.PEER_ID_LEN_DEFAULT) {
            throw new IllegalArgumentException("peer ids must be "
                    + LumosConstants.PEER_ID_LEN_DEFAULT + " bytes");
        }
        if (Arrays.equals(a, b)) {
            throw new IllegalArgumentException("a match needs two distinct peers");
        }
        final byte[] lower = compare(a, b) <= 0 ? a : b;
        final byte[] higher = lower == a ? b : a;
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(LumosConstants.MATCH_ID_KDF_INFO_VALUE
                    .getBytes(StandardCharsets.UTF_8));
            digest.update(lower);
            digest.update(higher);
            return Arrays.copyOf(digest.digest(), LumosConstants.MATCH_ID_LEN_DEFAULT);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every platform", e);
        }
    }

    /**
     * The accept-accept race tiebreak: which peer acts as session initiator.
     *
     * @param a one peer identifier
     * @param b the other peer identifier
     * @return the lexicographically lower of the two
     */
    public static byte[] initiatorOnRace(final byte[] a, final byte[] b) {
        return compare(a, b) <= 0 ? a : b;
    }

    private static int compare(final byte[] a, final byte[] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            final int diff = (a[i] & 0xFF) - (b[i] & 0xFF);
            if (diff != 0) {
                return diff;
            }
        }
        return a.length - b.length;
    }
}
