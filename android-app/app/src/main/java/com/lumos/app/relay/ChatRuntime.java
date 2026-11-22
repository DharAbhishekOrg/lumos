/*
 * Lumos - android-app/app / The chat and relay parts, assembled once for a running app.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-001, SRS-RMT-003, SRS-RMT-018, TRS-TRN-012, TRS-PAT-004
 * ADRs: ADR-001, ADR-003, ADR-010
 * Threading: serial-executor
 * Notes: The composition the Android boot (and any test) builds: one chat
 * store, one set of routes, one registration, one prekey publisher, one
 * relay pass and one route exchange, all over the same sealed LocalStore and
 * the same unlocked identity. It is plain Java so the whole graph is tested
 * on the JVM; Android only supplies the store, the KEK, the libsodium
 * backend and an https transport. Every operation is meant to run on one
 * serial executor - the relay job's thread - which is what makes ChatStore's
 * transactions the only concurrency rule there is.
 *
 * send starts the session on demand: the side that sends first claims the
 * peer's bundle. Both sides doing so at once is the simultaneous-initiation
 * limit recorded in the WP-05 plan.
 */

package com.lumos.app.relay;

import com.lumos.app.chat.ChatStore;
import com.lumos.app.identity.ChatIdentity;
import com.lumos.app.identity.IdentityCrypto;
import com.lumos.app.store.LocalStore;
import com.lumos.app.store.StoreException;
import com.lumos.protocol.chat.RemoteText;
import com.lumos.protocol.wire.Frame;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Optional;

/** The assembled chat runtime. */
public final class ChatRuntime {

    private final ChatStore chat;
    private final RelaySync sync;
    private final RelaySessions sessions;
    private final RouteExchange exchange;

    private ChatRuntime(final ChatStore chat, final RelaySync sync, final RelaySessions sessions,
            final RouteExchange exchange) {
        this.chat = chat;
        this.sync = sync;
        this.sessions = sessions;
        this.exchange = exchange;
    }

    /**
     * Assembles the runtime.
     *
     * @param store the sealed local store
     * @param identity the unlocked identity
     * @param crypto the identity backend, for the relay device key (libsodium on Android)
     * @param transport the relay transport
     * @param clock the UTC clock
     * @param random the platform CSPRNG
     * @return the runtime
     */
    public static ChatRuntime assemble(final LocalStore store, final ChatIdentity identity, final IdentityCrypto crypto,
            final HttpTransport transport, final LocalStore.Clock clock, final SecureRandom random) {
        final ChatStore chat = ChatStore.over(store, identity.identity(), clock, () -> {
            final byte[] b = new byte[RelayClient.KEY_SIZE];
            random.nextBytes(b);
            return b;
        });
        final RelayRoutes routes = RelayRoutes.over(store, random);
        final RelayAccount account = RelayAccount.over(store, crypto);
        final PrekeyPublisher prekeys = PrekeyPublisher.over(store, chat, identity.identity(), identity::sign, random);
        final RelayClient anonymous = RelayClient.anonymous(transport);
        return new ChatRuntime(chat, RelaySync.over(chat, routes, account, prekeys, anonymous, clock),
                RelaySessions.over(chat, routes, account, anonymous, random), RouteExchange.over(routes, account));
    }

    /** @return the one chat store, shared with the UI by design (see the SpotBugs filter) */
    public ChatStore chat() {
        return chat;
    }

    /** @return the relay pass */
    public RelaySync sync() {
        return sync;
    }

    /** @return the RELAY_ROUTE exchange, for the nearby sessions */
    public RouteExchange exchange() {
        return exchange;
    }

    /**
     * Queues a text event, starting the session first when there is none.
     *
     * @param matchId the conversation
     * @param peerIdentityEd the identity the match pinned
     * @param event the event
     * @return the new message id, or empty while no session can start yet (no route or no bundle)
     * @throws RelayException when the registry could not be reached to start the session
     * @throws StoreException on engine failure
     * @throws GeneralSecurityException when the device key could not be made
     * @throws Frame.WireException when the peer's bundle is not the pinned identity's, or sealing fails
     */
    public Optional<byte[]> send(final byte[] matchId, final byte[] peerIdentityEd, final RemoteText.TextEvent event)
            throws RelayException, StoreException, GeneralSecurityException, Frame.WireException {
        if (!sessions.start(matchId, peerIdentityEd)) {
            return Optional.empty();
        }
        return Optional.of(chat.send(matchId, event));
    }
}
