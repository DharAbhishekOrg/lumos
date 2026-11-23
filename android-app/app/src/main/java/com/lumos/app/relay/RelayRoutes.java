/*
 * Lumos - android-app/app / Each conversation's relay route: where to send, with what, and what to accept.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-001, SRS-RMT-016, TRS-BCK-003, TRS-LOC-001
 * ADRs: ADR-001, ADR-003
 * Threading: serial-executor
 * Notes: A route has two halves. This device's half is a receive capability
 * it mints for one peer: 32 random bytes the peer presents when it sends, and
 * whose SHA-256 (the proof) this device registers with the relay. The peer's
 * half is the peer's routing id and the capability the peer minted for this
 * device. The halves cross over the authenticated nearby session at the
 * match (WP-05 S7e); here they are stored, sealed, one row per conversation.
 *
 * Because each peer gets its own capability, a fetched envelope's sender
 * proof names the conversation without trial decryption, and revoking one
 * capability stops exactly one peer (SRS-RMT-016). allowedFor records the
 * registration a proof was allowed under: after a fresh registration (a new,
 * empty mailbox) every route reads as not yet allowed and is allowed again.
 *
 * Ending a conversation (unmatch, block) marks its route as ending in the
 * same transaction that removes the conversation. An ending route accepts
 * nothing and is never allowed again; the next relay pass revokes its proof
 * and then forgets it, so going offline at the moment of an unmatch cannot
 * leave the peer able to reach this device. Format 1 rows (written before
 * the mark existed) read as not ending.
 */

package com.lumos.app.relay;

import com.lumos.app.store.LocalStore;
import com.lumos.app.store.RecordKind;
import com.lumos.app.store.StoreException;
import com.lumos.protocol.wire.LumosCrypto;
import java.io.ByteArrayOutputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The conversations' relay routes. */
public final class RelayRoutes {

    private static final int FORMAT_V1 = 1;
    private static final int FORMAT = 2;
    private static final int MAX_FIELD = 64;
    private static final byte[] NONE = new byte[0];

    private final LocalStore store;
    private final SecureRandom random;

    private RelayRoutes(final LocalStore store, final SecureRandom random) {
        this.store = store;
        this.random = random;
    }

    /**
     * Binds the routes to the store.
     *
     * @param store the sealed local store
     * @param random the platform CSPRNG, for minting capabilities
     * @return the routes
     */
    public static RelayRoutes over(final LocalStore store, final SecureRandom random) {
        return new RelayRoutes(store, random);
    }

    /**
     * One conversation's route.
     *
     * @param matchId the conversation
     * @param receiveCapability the capability this device minted for the peer
     * @param peerRoutingId the peer's routing id, or empty until the peer's half arrives
     * @param sendCapability the capability the peer minted for this device, or empty
     * @param allowedFor this device's routing id when the proof was allowed, or empty
     * @param ending true once the conversation has ended: revoke, then forget
     */
    public record Route(byte[] matchId, byte[] receiveCapability, byte[] peerRoutingId, byte[] sendCapability,
            byte[] allowedFor, boolean ending) {

        /**
         * Copies.
         *
         * @param matchId see the record
         * @param receiveCapability see the record
         * @param peerRoutingId see the record
         * @param sendCapability see the record
         * @param allowedFor see the record
         * @param ending see the record
         */
        public Route {
            matchId = matchId.clone();
            receiveCapability = receiveCapability.clone();
            peerRoutingId = peerRoutingId.clone();
            sendCapability = sendCapability.clone();
            allowedFor = allowedFor.clone();
        }

        /** @return a copy of the match id */
        @Override
        public byte[] matchId() {
            return matchId.clone();
        }

        /** @return a copy of the capability this device minted */
        @Override
        public byte[] receiveCapability() {
            return receiveCapability.clone();
        }

        /** @return a copy of the peer's routing id, empty until known */
        @Override
        public byte[] peerRoutingId() {
            return peerRoutingId.clone();
        }

        /** @return a copy of the capability to send with, empty until known */
        @Override
        public byte[] sendCapability() {
            return sendCapability.clone();
        }

        /** @return a copy of the routing id the proof was allowed under, or empty */
        @Override
        public byte[] allowedFor() {
            return allowedFor.clone();
        }

        /** @return true once the peer's half has arrived */
        public boolean canSend() {
            return peerRoutingId.length == RelayClient.ROUTING_ID_SIZE
                    && sendCapability.length == RelayClient.KEY_SIZE;
        }

        /** @return SHA-256 of the receive capability: what the relay stores and echoes */
        public byte[] proof() {
            return LumosCrypto.sha256(receiveCapability);
        }

        byte[] encode() {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(FORMAT);
            for (final byte[] f : new byte[][] {matchId, receiveCapability, peerRoutingId, sendCapability,
                allowedFor}) {
                out.write(f.length);
                out.write(f, 0, f.length);
            }
            out.write(ending ? 1 : 0);
            return out.toByteArray();
        }

        static Route decode(final byte[] b) throws StoreException {
            try {
                final ByteBuffer in = ByteBuffer.wrap(b);
                final int format = in.get();
                if (format != FORMAT && format != FORMAT_V1) {
                    throw unreadable();
                }
                final byte[] match = field(in);
                final byte[] receive = field(in);
                final byte[] peer = field(in);
                final byte[] send = field(in);
                final byte[] allowed = field(in);
                final boolean ending = format == FORMAT && flag(in);
                final Route r = new Route(match, receive, peer, send, allowed, ending);
                if (in.hasRemaining()) {
                    throw unreadable();
                }
                return r;
            } catch (final BufferUnderflowException e) {
                throw unreadable();
            }
        }

        private static boolean flag(final ByteBuffer in) throws StoreException {
            final byte f = in.get();
            if (f != 0 && f != 1) {
                throw unreadable();
            }
            return f == 1;
        }

        private static byte[] field(final ByteBuffer in) throws StoreException {
            final int n = in.get() & 0xFF;
            if (n > MAX_FIELD) {
                throw unreadable();
            }
            final byte[] f = new byte[n];
            in.get(f);
            return f;
        }
    }

    private static StoreException unreadable() {
        return new StoreException(StoreException.Reason.ENGINE, "relay route row is unreadable");
    }

    /**
     * This device's half for a conversation, minted on first use and stable
     * after: the capability to hand the peer.
     *
     * @param matchId the conversation
     * @return the route, with this device's half present
     * @throws StoreException on engine failure
     */
    public Route issue(final byte[] matchId) throws StoreException {
        final Optional<Route> have = forMatch(matchId);
        if (have.isPresent()) {
            return have.get();
        }
        final byte[] capability = new byte[RelayClient.KEY_SIZE];
        random.nextBytes(capability);
        final Route r = new Route(matchId, capability, NONE, NONE, NONE, false);
        save(r);
        Arrays.fill(capability, (byte) 0);
        return r;
    }

    /**
     * Stores the peer's half, as received over the authenticated session.
     * An ending route stays as it is.
     *
     * @param matchId the conversation
     * @param peerRoutingId the peer's routing id
     * @param sendCapability the capability the peer minted for this device
     * @throws StoreException when this device has not issued its half, or on engine failure
     */
    public void setPeer(final byte[] matchId, final byte[] peerRoutingId, final byte[] sendCapability)
            throws StoreException {
        if (peerRoutingId.length != RelayClient.ROUTING_ID_SIZE || sendCapability.length != RelayClient.KEY_SIZE) {
            throw new IllegalArgumentException("a peer half is a routing id and a capability");
        }
        final Route r = forMatch(matchId).orElseThrow(
                () -> new StoreException(StoreException.Reason.ENGINE, "no route issued for this match"));
        if (r.ending()) {
            return;
        }
        save(new Route(matchId, r.receiveCapability(), peerRoutingId, sendCapability, r.allowedFor(), false));
    }

    /**
     * Records that this route's proof is allowed under a registration.
     *
     * @param matchId the conversation
     * @param routingId this device's routing id at the time
     * @throws StoreException on engine failure
     */
    public void markAllowed(final byte[] matchId, final byte[] routingId) throws StoreException {
        final Optional<Route> r = forMatch(matchId);
        if (r.isPresent() && !r.get().ending()) {
            final Route x = r.get();
            save(new Route(matchId, x.receiveCapability(), x.peerRoutingId(), x.sendCapability(), routingId, false));
        }
    }

    /**
     * Marks a conversation's route as ending: from now on nothing arrives
     * under it, and the next relay pass revokes and forgets it. Call inside
     * the transaction that removes the conversation.
     *
     * @param matchId the conversation
     * @throws StoreException on engine failure
     */
    public void markEnding(final byte[] matchId) throws StoreException {
        final Optional<Route> r = forMatch(matchId);
        if (r.isPresent() && !r.get().ending()) {
            final Route x = r.get();
            save(new Route(matchId, x.receiveCapability(), x.peerRoutingId(), x.sendCapability(), x.allowedFor(),
                    true));
        }
    }

    /**
     * A conversation's route.
     *
     * @param matchId the conversation
     * @return the route, or empty
     * @throws StoreException on engine failure
     */
    public Optional<Route> forMatch(final byte[] matchId) throws StoreException {
        final List<byte[]> rows = store.findByNaturalKey(RecordKind.RELAY_ROUTE, matchId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(Route.decode(rows.get(0)));
    }

    /**
     * Every route.
     *
     * @return the routes
     * @throws StoreException on engine failure
     */
    public List<Route> all() throws StoreException {
        final List<Route> out = new ArrayList<>();
        for (final LocalStore.Entry e : store.allOf(RecordKind.RELAY_ROUTE)) {
            out.add(Route.decode(e.content()));
        }
        return out;
    }

    /**
     * Every open conversation by the proof its peer's envelopes arrive
     * under; ending routes are left out, so their envelopes are dropped.
     *
     * @return proof hex to match id
     * @throws StoreException on engine failure
     */
    public Map<String, byte[]> byProof() throws StoreException {
        final Map<String, byte[]> out = new HashMap<>();
        for (final Route r : all()) {
            if (!r.ending()) {
                out.put(Hex.encode(r.proof()), r.matchId());
            }
        }
        return out;
    }

    /**
     * Forgets a conversation's route (unmatch, block). The caller revokes
     * the proof at the relay first.
     *
     * @param matchId the conversation
     * @throws StoreException on engine failure
     */
    public void remove(final byte[] matchId) throws StoreException {
        for (final LocalStore.Entry e : store.findEntriesByNaturalKey(RecordKind.RELAY_ROUTE, matchId)) {
            store.delete(RecordKind.RELAY_ROUTE, e.id());
        }
    }

    private void save(final Route r) throws StoreException {
        store.putByNaturalKey(RecordKind.RELAY_ROUTE, r.encode(), r.matchId());
    }
}
