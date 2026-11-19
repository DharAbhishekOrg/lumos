/*
 * Lumos - android-app/app / The feed-side read of the block list.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-NBY-013, SRS-DAT-002
 * ADRs: ADR-003
 * Threading: serial-executor
 * Notes: The blocked-identities-never-reappear rule needs exactly one
 * question answered at feed time: "is this authenticated identity blocked?"
 * - over WP-02's blind-indexed block_entry table, keyed by the raw peer
 * identifier under the canonical definition HiddenPeers fixes. The WRITE
 * side of blocking (the user action, its confirmation, its propagation
 * hooks) belongs to the match domain in S3; building the reader first means
 * the feed filter exists before any block can be placed, instead of blocks
 * existing that no feed respects. Rotation resistance is free here: the key
 * is identity, and presence already deduplicates on identity, so there is no
 * advertisement-shaped way back into the feed (SRS-NBY-013).
 */

package com.lumos.app.nearby;

import com.lumos.app.store.LocalStore;
import com.lumos.app.store.RecordKind;
import com.lumos.app.store.StoreException;

/** Answers whether an authenticated identity is blocked (SRS-NBY-013). */
public final class BlockedPeers {

    private final LocalStore store;

    /**
     * Binds the reader to its store.
     *
     * @param store the opened local store
     */
    public BlockedPeers(final LocalStore store) {
        this.store = store;
    }

    /**
     * Whether a peer is blocked.
     *
     * @param peerKey the authenticated peer identifier, hex
     * @return true when a block entry exists for this identity
     * @throws StoreException on engine failure
     */
    public boolean isBlocked(final String peerKey) throws StoreException {
        return !store.findByNaturalKey(RecordKind.BLOCK_ENTRY,
                HiddenPeers.naturalKey(peerKey)).isEmpty();
    }
}
