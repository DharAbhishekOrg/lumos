/*
 * Lumos - android-app/app / The hidden-peer list: silent, identity-keyed.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-NBY-012, SRS-NBY-013, SRS-DAT-002
 * ADRs: ADR-003
 * Threading: serial-executor
 * Notes: Hiding a card is a one-sided act: nothing is sent, nothing changes
 * on the peer's side, and the hidden peer cannot observe any of it - the
 * record exists only to keep the card gone. The key is the authenticated
 * 16-byte peer identifier (SRS-IDN-002), never advertisement bytes, so a
 * hidden peer stays hidden across every EID rotation by construction - the
 * same reasoning SRS-NBY-013 applies to blocks. Lookups go through the
 * store's blind index: the file answers "is this peer hidden?" without the
 * identifier being readable in it. The canonical natural-key definition lives in
 * store.PeerKeys; this class and every other peer list consume it.
 */

package com.lumos.app.nearby;

import com.lumos.app.store.LocalStore;
import com.lumos.app.store.PeerKeys;
import com.lumos.app.store.RecordKind;
import com.lumos.app.store.StoreException;
import java.nio.charset.StandardCharsets;

/** Peers the user hid from the Nearby feed (SRS-NBY-012). */
public final class HiddenPeers {

    /** Payload format version line; the record carries only when. */
    private static final String VERSION_LINE = "lumos-hidden-peer/1";

    private final LocalStore store;

    /**
     * Binds the list to its store.
     *
     * @param store the opened local store
     */
    public HiddenPeers(final LocalStore store) {
        this.store = store;
    }

    /**
     * Hides a peer. Idempotent by effect: hiding twice leaves one hidden
     * peer, because lookups ask for existence, not count.
     *
     * @param peerKey the authenticated peer identifier, hex (a presence
     *     card's key)
     * @param atUtcMillis when the user chose it
     * @throws StoreException on low storage or engine failure
     */
    public void hide(final String peerKey, final long atUtcMillis) throws StoreException {
        final String payload = VERSION_LINE + "\n" + atUtcMillis + "\n";
        store.put(RecordKind.HIDDEN_PEER, null,
                payload.getBytes(StandardCharsets.UTF_8), naturalKey(peerKey));
    }

    /**
     * Whether a peer is hidden.
     *
     * @param peerKey the authenticated peer identifier, hex
     * @return true when a hidden-peer record exists for this identity
     * @throws StoreException on engine failure
     */
    public boolean isHidden(final String peerKey) throws StoreException {
        return !store.findByNaturalKey(RecordKind.HIDDEN_PEER, naturalKey(peerKey)).isEmpty();
    }

    /**
     * The canonical peer-list natural key, from the one shared definition.
     *
     * @param peerKeyHex the identifier as a presence card spells it
     * @return the raw bytes the blind index is computed over
     */
    static byte[] naturalKey(final String peerKeyHex) {
        return PeerKeys.naturalKey(peerKeyHex);
    }
}
