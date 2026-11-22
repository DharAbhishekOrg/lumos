/*
 * Lumos - android-app/app / The RELAY_ROUTE swap on one peer's nearby session.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-001, SRS-RMT-016, SRS-MAT-005, SRS-MAT-006
 * ADRs: ADR-002
 * Threading: serial-executor
 * Notes: CR-005 on a live session. Bound to one peer's InterestSession, it
 * sends this device's RELAY_ROUTE as soon as the two are matched, and again
 * only when the payload changes - which happens when a re-registration gives
 * this device a new routing id - so an unchanged route is never resent on
 * every frame. It takes the peer's RELAY_ROUTE from the session's
 * conversation sink; the session has already applied the consent gate, and
 * RouteExchange applies it again. The session's state is read afresh on
 * every call, so an unmatch in between stops the offer.
 */

package com.lumos.app.relay;

import com.lumos.app.match.InterestSession;
import com.lumos.app.store.StoreException;
import com.lumos.protocol.MessageClass;
import com.lumos.protocol.match.MatchMachine;
import com.lumos.protocol.wire.Frame;
import java.util.Arrays;
import java.util.Optional;

/** One peer's RELAY_ROUTE swap. */
public final class NearbyRouteSwap {

    private final InterestSession session;
    private final RouteExchange exchange;
    private final byte[] matchId;
    private byte[] lastOffered = new byte[0];
    private RouteExchange.Taken lastTaken;

    private NearbyRouteSwap(final InterestSession session, final RouteExchange exchange, final byte[] matchId) {
        this.session = session;
        this.exchange = exchange;
        this.matchId = matchId.clone();
    }

    /**
     * Binds the swap to a session and starts listening for the peer's route.
     *
     * @param session the peer's nearby session
     * @param exchange the route exchange
     * @param matchId the conversation this peer's match derives
     * @return the swap
     */
    public static NearbyRouteSwap on(final InterestSession session, final RouteExchange exchange,
            final byte[] matchId) {
        final NearbyRouteSwap swap = new NearbyRouteSwap(session, exchange, matchId);
        session.onConversation(swap::onConversation);
        return swap;
    }

    /**
     * Offers this device's route when matched and not yet offered as it is.
     * Call after every local action and inbound frame.
     *
     * @return true when a RELAY_ROUTE frame was sent
     * @throws StoreException on store failure
     * @throws Frame.WireException if the frame cannot be encoded
     */
    public boolean offerIfMatched() throws StoreException, Frame.WireException {
        final MatchMachine.State state = session.state();
        final Optional<byte[]> offer = exchange.offer(matchId, state);
        if (!offer.isPresent() || Arrays.equals(offer.get(), lastOffered)) {
            return false;
        }
        if (!session.sendConversation(MessageClass.RELAY_ROUTE, offer.get())) {
            return false;
        }
        lastOffered = offer.get();
        return true;
    }

    private void onConversation(final MessageClass messageClass, final byte[] payload,
            final MatchMachine.State state) throws StoreException {
        if (messageClass == MessageClass.RELAY_ROUTE) {
            lastTaken = exchange.take(matchId, state, payload);
        }
    }

    /** @return what the last RELAY_ROUTE from the peer did, or null when none came */
    public RouteExchange.Taken lastTaken() {
        return lastTaken;
    }
}
