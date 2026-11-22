/*
 * Lumos - android-app/app / Swapping relay routes with a matched peer over the nearby session (CR-005).
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-001, SRS-RMT-016, SRS-MAT-005, SRS-MAT-006
 * ADRs: ADR-002
 * Threading: serial-executor
 * Notes: After a match each side sends a RELAY_ROUTE (0x07) over the
 * authenticated, encrypted nearby session: its routing id and the
 * capability it minted for this one peer. Both directions are gated by the
 * consent gate, so a route can neither be offered nor taken before mutual
 * acceptance (SRS-MAT-005/006), and the class is never put on the relay.
 * Taking a peer's route also mints this device's half if it does not exist
 * yet, so the reply can always be sent. A device with no registration yet
 * (offline since install) has no routing id to offer and sends nothing; it
 * offers its route on the next nearby contact once it has one, and again
 * after any re-registration, which changes the routing id.
 */

package com.lumos.app.relay;

import com.lumos.app.store.StoreException;
import com.lumos.protocol.MessageClass;
import com.lumos.protocol.chat.RelayRoute;
import com.lumos.protocol.match.ConsentGate;
import com.lumos.protocol.match.MatchMachine;
import com.lumos.protocol.wire.Frame;
import java.util.Optional;

/** The RELAY_ROUTE exchange. */
public final class RouteExchange {

    /** What taking a peer's route did. */
    public enum Taken {
        /** Stored: this device can now send to the peer over the relay. */
        STORED,
        /** Refused by the consent gate: not matched with this peer. */
        NOT_MATCHED,
        /** Refused: the payload is not a RELAY_ROUTE. */
        MALFORMED
    }

    private final RelayRoutes routes;
    private final RelayAccount account;

    private RouteExchange(final RelayRoutes routes, final RelayAccount account) {
        this.routes = routes;
        this.account = account;
    }

    /**
     * Binds the exchange.
     *
     * @param routes the conversations' routes
     * @param account the registration
     * @return the exchange
     */
    public static RouteExchange over(final RelayRoutes routes, final RelayAccount account) {
        return new RouteExchange(routes, account);
    }

    /**
     * This device's RELAY_ROUTE payload for a matched peer.
     *
     * @param matchId the conversation
     * @param state where the match with this peer stands
     * @return the payload, or empty when not matched or not yet registered
     * @throws StoreException on engine failure
     */
    public Optional<byte[]> offer(final byte[] matchId, final MatchMachine.State state) throws StoreException {
        if (!ConsentGate.permits(MessageClass.RELAY_ROUTE, state)) {
            return Optional.empty();
        }
        final Optional<RelayTypes.Registration> reg = account.current();
        if (!reg.isPresent()) {
            return Optional.empty();
        }
        final RelayRoutes.Route own = routes.issue(matchId);
        return Optional.of(new RelayRoute(reg.get().routingId(), own.receiveCapability()).encode());
    }

    /**
     * Takes a peer's RELAY_ROUTE payload.
     *
     * @param matchId the conversation it arrived in
     * @param state where the match with this peer stands
     * @param payload the payload
     * @return what happened
     * @throws StoreException on engine failure
     */
    public Taken take(final byte[] matchId, final MatchMachine.State state, final byte[] payload)
            throws StoreException {
        if (!ConsentGate.permits(MessageClass.RELAY_ROUTE, state)) {
            return Taken.NOT_MATCHED;
        }
        final RelayRoute peer;
        try {
            peer = RelayRoute.decode(payload);
        } catch (final Frame.WireException e) {
            return Taken.MALFORMED;
        }
        routes.issue(matchId);
        routes.setPeer(matchId, peer.routingId(), peer.capability());
        return Taken.STORED;
    }
}
