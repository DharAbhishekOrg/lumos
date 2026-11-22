/*
 * Lumos - android-app/app / Starting a conversation's session from the peer's published bundle.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-001, SRS-RMT-007, TRS-CRY-005, TRS-BCK-005
 * ADRs: ADR-001, ADR-008
 * Threading: serial-executor
 * Notes: The initiator claims the peer's bundle - and with it one one-time
 * prekey the registry will never hand out again (TRS-CRY-005) - and starts
 * the X3DH session against the identity the match pinned; a bundle for any
 * other identity is refused by ChatStore.startSession, so a lying registry
 * gets nowhere. The peer may be offline: the bundle is all that is needed,
 * and the first envelopes carry the PREKEY fields until it replies. If the
 * process dies after the claim and before the session is saved, that one
 * one-time prekey is wasted and the next attempt claims another; nothing
 * else is lost. Which side initiates is the match tiebreak's business (the
 * lower identifier, protocol/match); simultaneous initiation is a known
 * limit recorded in the WP-05 plan.
 */

package com.lumos.app.relay;

import com.lumos.app.chat.ChatStore;
import com.lumos.app.store.StoreException;
import com.lumos.protocol.wire.Frame;
import com.lumos.protocol.x3dh.X3dh;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Optional;

/** Starts sessions over the relay. */
public final class RelaySessions {

    private final ChatStore chat;
    private final RelayRoutes routes;
    private final RelayAccount account;
    private final RelayClient anonymous;
    private final SecureRandom random;

    private RelaySessions(final ChatStore chat, final RelayRoutes routes, final RelayAccount account,
            final RelayClient anonymous, final SecureRandom random) {
        this.chat = chat;
        this.routes = routes;
        this.account = account;
        this.anonymous = anonymous;
        this.random = random;
    }

    /**
     * Binds the starter.
     *
     * @param chat the chat store
     * @param routes the conversations' routes
     * @param account the registration
     * @param anonymous a client with no credential, over the transport
     * @param random the platform CSPRNG
     * @return the starter
     */
    public static RelaySessions over(final ChatStore chat, final RelayRoutes routes, final RelayAccount account,
            final RelayClient anonymous, final SecureRandom random) {
        return new RelaySessions(chat, routes, account, anonymous, random);
    }

    /**
     * Starts the session with a matched peer, unless there already is one.
     *
     * @param matchId the conversation
     * @param peerIdentityEd the identity the match pinned
     * @return true when a session exists afterwards; false while the peer's
     *         route or bundle is not there yet
     * @throws RelayException when the registry could not be reached or refused
     * @throws StoreException on engine failure
     * @throws GeneralSecurityException when the device key could not be made
     * @throws Frame.WireException when the bundle is not the matched peer's or does not verify
     */
    public boolean start(final byte[] matchId, final byte[] peerIdentityEd)
            throws RelayException, StoreException, GeneralSecurityException, Frame.WireException {
        if (chat.hasSession(matchId)) {
            return true;
        }
        final Optional<RelayRoutes.Route> route = routes.forMatch(matchId);
        if (!route.isPresent() || !route.get().canSend()) {
            return false;
        }
        final RelayTypes.Registration reg = account.ensure(anonymous);
        final Optional<X3dh.Bundle> bundle = anonymous.as(reg.credential()).claim(route.get().peerRoutingId());
        if (!bundle.isPresent()) {
            return false;
        }
        final byte[] ephemeral = scalar();
        final byte[] ratchet = scalar();
        try {
            chat.startSession(matchId, peerIdentityEd, bundle.get(), ephemeral, ratchet);
        } finally {
            Arrays.fill(ephemeral, (byte) 0);
            Arrays.fill(ratchet, (byte) 0);
        }
        return true;
    }

    private byte[] scalar() {
        final byte[] b = new byte[X3dh.SK_SIZE];
        random.nextBytes(b);
        return b;
    }
}
