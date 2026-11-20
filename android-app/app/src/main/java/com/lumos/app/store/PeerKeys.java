/*
 * Lumos - android-app/app / The canonical peer-list natural key, in one place.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-NBY-013, SRS-DAT-002
 * ADRs: ADR-003
 * Threading: any
 * Notes: Every peer-keyed table (block, hidden, interest, match) computes its
 * blind index over the SAME bytes: the raw authenticated peer identifier,
 * decoded from the hex spelling presence cards carry. One definition, one
 * place - two tables disagreeing about the key encoding would be two tables
 * that silently stop finding each other's peers.
 */

package com.lumos.app.store;

/** Decodes the canonical natural key for peer-keyed records. */
public final class PeerKeys {

    private PeerKeys() {
        throw new AssertionError("no instances");
    }

    /**
     * The raw bytes the blind index is computed over.
     *
     * @param peerKeyHex the identifier as a presence card spells it
     * @return the decoded identifier bytes
     */
    public static byte[] naturalKey(final String peerKeyHex) {
        final int len = peerKeyHex.length();
        if (len % 2 != 0) {
            throw new IllegalArgumentException("peer key hex has odd length");
        }
        final byte[] raw = new byte[len / 2];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) Integer.parseInt(peerKeyHex.substring(i * 2, i * 2 + 2), 16);
        }
        return raw;
    }
}
