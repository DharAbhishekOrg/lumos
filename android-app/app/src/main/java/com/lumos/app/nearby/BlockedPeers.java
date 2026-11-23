/*
 * Lumos - android-app/app / The block list: placed from a conversation, read by the feed.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-NBY-013, SRS-DAT-002, SRS-SAF-002
 * ADRs: ADR-003
 * Threading: serial-executor
 * Notes: The blocked-identities-never-reappear rule needs exactly one
 * question answered at feed time: "is this authenticated identity blocked?"
 * - over WP-02's blind-indexed block_entry table, keyed by the raw peer
 * identifier under the canonical definition HiddenPeers fixes. The reader
 * came first (WP-04), so the feed filter existed before any block could be
 * placed; the first writer is the conversation's safety sheet (CS2b), which
 * calls block inside the transaction that ends the conversation. A block is
 * never lifted here: there is no unblock until the settings slice gives the
 * person a reviewed way to do it. Rotation resistance is free here: the key
 * is identity, and presence already deduplicates on identity, so there is no
 * advertisement-shaped way back into the feed (SRS-NBY-013).
 */

package com.lumos.app.nearby;

import com.lumos.app.store.LocalStore;
import com.lumos.app.store.RecordKind;
import com.lumos.app.store.StoreException;
import java.nio.charset.StandardCharsets;

/** Answers whether an authenticated identity is blocked (SRS-NBY-013). */
public final class BlockedPeers {

    private static final String VERSION_LINE = "lumos-blocked-peer/1";

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
     * Blocks a peer. Idempotent by effect: a second block of the same
     * identity is not written, because lookups ask for existence.
     *
     * @param peerKey the authenticated peer identifier, hex
     * @param atUtcMillis when the user chose it
     * @throws StoreException on low storage or engine failure
     */
    public void block(final String peerKey, final long atUtcMillis) throws StoreException {
        if (isBlocked(peerKey)) {
            return;
        }
        final String payload = VERSION_LINE + "\n" + atUtcMillis + "\n";
        store.put(RecordKind.BLOCK_ENTRY, null, payload.getBytes(StandardCharsets.UTF_8),
                HiddenPeers.naturalKey(peerKey));
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
