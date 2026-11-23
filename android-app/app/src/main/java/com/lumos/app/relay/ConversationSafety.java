/*
 * Lumos - android-app/app / Ending a conversation from its safety sheet: unmatch or block.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-MAT-009, SRS-MAT-010, SRS-RMT-016, SRS-SAF-001, SRS-SAF-002, SRS-NBY-013
 * ADRs: ADR-001, ADR-003
 * Threading: serial-executor
 * Notes: One local transaction does everything that must not half-happen:
 * the conversation, its messages, its session and its queued sends are
 * deleted, its relay route is marked ending, and for a block the peer's
 * identity joins the block list the Nearby feed already respects. Nothing
 * here touches the network. The relay part - revoking the capability this
 * device gave the peer, so the relay refuses anything more they send - is
 * finished by the next relay pass, which retries until it succeeds; until
 * then an envelope under that capability matches no open route and is
 * dropped unread (SRS-RMT-016). The peer is not told either way.
 *
 * Not here yet, by plan: the match ledger is not composed in production
 * (CS4), so the match record itself is ended there; the nearby Hello
 * responder that will refuse a blocked identity lands with the GATT wiring
 * (SRS-SAF-002); report with evidence is CR-007.
 */

package com.lumos.app.relay;

import com.lumos.app.chat.ChatStore;
import com.lumos.app.chat.ConversationRemoval;
import com.lumos.app.nearby.BlockedPeers;
import com.lumos.app.store.LocalStore;
import com.lumos.app.store.StoreException;
import com.lumos.protocol.hello.Hello;
import java.util.Optional;

/** Unmatch and block, as one local transaction each. */
public final class ConversationSafety {

    /** How a conversation ends. */
    public enum Ending {
        /** End the match: the conversation is deleted and the peer can no longer reach this device. */
        UNMATCH,
        /** Unmatch, and never show or accept this identity again. */
        BLOCK
    }

    private final LocalStore store;
    private final ChatStore chat;
    private final RelayRoutes routes;
    private final BlockedPeers blocked;
    private final LocalStore.Clock clock;

    private ConversationSafety(final LocalStore store, final ChatStore chat, final RelayRoutes routes,
            final LocalStore.Clock clock) {
        this.store = store;
        this.chat = chat;
        this.routes = routes;
        this.blocked = new BlockedPeers(store);
        this.clock = clock;
    }

    /**
     * Binds the safety actions to the chat stack's parts.
     *
     * @param store the sealed local store
     * @param chat the chat store
     * @param routes the conversations' routes
     * @param clock the UTC clock
     * @return the safety actions
     */
    public static ConversationSafety over(final LocalStore store, final ChatStore chat, final RelayRoutes routes,
            final LocalStore.Clock clock) {
        return new ConversationSafety(store, chat, routes, clock);
    }

    /**
     * The hex peer key a block is placed under: the same identifier a
     * nearby presence card carries for this identity.
     *
     * @param peerIdentityEd the peer's Ed25519 identity
     * @return the peer key, hex
     */
    public static String peerKeyOf(final byte[] peerIdentityEd) {
        return Hex.encode(Hello.peerId(peerIdentityEd));
    }

    /**
     * Ends a conversation on this device.
     *
     * @param matchId the conversation
     * @param how unmatch or block
     * @return true when ended; false when there was no such conversation (nothing changed)
     * @throws StoreException on engine failure; nothing changed then
     */
    public boolean end(final byte[] matchId, final Ending how) throws StoreException {
        return store.transaction(() -> {
            final Optional<byte[]> peer = chat.peerIdentity(matchId);
            if (peer.isEmpty()) {
                return false;
            }
            if (how == Ending.BLOCK) {
                blocked.block(peerKeyOf(peer.get()), clock.nowUtcMillis());
            }
            ConversationRemoval.remove(store, matchId);
            routes.markEnding(matchId);
            return true;
        });
    }
}
