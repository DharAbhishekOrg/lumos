/*
 * Lumos - android-app/app / Sightings to cards: triage, budgeted links, both HELLOs, presence.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-ENV-001, TRS-ENV-002, TRS-PROTO-017, SRS-NBY-005, SRS-NBY-006, SRS-NBY-007, SRS-NBY-017,
 *             SRS-SAF-002
 * ADRs: ADR-002, ADR-013
 * Threading: serial-executor (one thread drives every call)
 * Notes: The composition the venue path test proved layer by layer, as the
 * one object a device runs: sightings go into DiscoveryTriage; each tick
 * spends the connect budget (MAX_CONCURRENT_GATT_LINKS, counting links in any
 * state and either role) on the strongest candidates; each link runs a
 * LinkSession; a DONE link's Peer goes into PresenceTable, which is the only
 * way a card exists (TRS-ENV-002); finished and failed links are closed at
 * once, so the budget is never held by a dead link. A peer already on a
 * fresh card is not connected again under the same advertisement key
 * (ADR-013 §6); presence and triage both age out on the register bound. One
 * slot of the budget is never spent on outgoing connects, so a room of
 * phones all connecting at once can still accept each other.
 *
 * Blocking (SRS-SAF-002): every link carries the person's refusals, so a
 * blocked identity is never answered or recorded, in either role. A sighting
 * whose link was refused is not connected again until the presence age-out
 * bound passes, so this phone does not keep opening links (and saying HELLO)
 * to someone the person blocked.
 *
 * The radio is the Radio seam: the GATT client and server adapters on a
 * device (once CR-010 names the characteristics), an in-memory air in the
 * tests. They report whole frames - fragmentation is FrameLink's - and
 * opaque link handles, which never leave this class and are never shown,
 * logged or stored. stop() is the one-action stop: every link closes and
 * nothing new starts.
 */

package com.lumos.app.ble;

import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.hello.HelloExchange;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One device's discovery. */
public final class DiscoveryEngine {

    /** The radio, as discovery uses it. */
    public interface Radio {
        /**
         * Connects out to a sighted peer as the central. The adapter later
         * reports {@link #onLinkReady} (subscribed to LINK_OUT), frames, and
         * {@link #onLinkClosed} under the same handle.
         *
         * @param handle this engine's name for the link
         * @param linkToken the sighting's transport handle
         */
        void connect(String handle, String linkToken);

        /**
         * Sends one whole frame on a link (the adapter fragments it).
         *
         * @param handle the link
         * @param frame the encoded frame
         */
        void send(String handle, byte[] frame);

        /**
         * Closes a link. Idempotent; the adapter need not report it back.
         *
         * @param handle the link
         */
        void disconnect(String handle);
    }

    /** This device's identity, as a fresh exchange per link. */
    public interface Identity {
        /**
         * @return a fresh HELLO exchange for one link
         */
        HelloExchange freshExchange();
    }

    /** Links kept free for peers that connect to us. */
    static final int INBOUND_RESERVE = 1;

    private final Radio radio;
    private final Identity identity;
    private final LinkSession.Nonces nonces;
    private final long handshakeTimeoutMillis;
    private final DiscoveryTriage triage = new DiscoveryTriage();
    private final PresenceTable presence;
    private final Map<String, LinkSession> links = new LinkedHashMap<>();
    private final Map<String, String> peerByCandidate = new HashMap<>();
    private final Map<String, String> candidateByLink = new HashMap<>();
    private final Map<String, Long> refusedUntil = new HashMap<>();
    private final LinkSession.Refusals refusals;
    private boolean running;
    private long nextHandle;

    /**
     * @param radio the radio seam
     * @param identity this device's identity
     * @param nonces fresh HELLO nonces
     * @param handshakeTimeoutMillis how long a link may take (CR-010's register value on a device)
     */
    public DiscoveryEngine(final Radio radio, final Identity identity, final LinkSession.Nonces nonces,
            final long handshakeTimeoutMillis) {
        this(radio, identity, nonces, handshakeTimeoutMillis, new PresenceTable());
    }

    /**
     * An engine that writes into a presence table it shares with the Nearby
     * feed, so the screen's vetoes (hidden, blocked, interest) apply to what
     * discovery finds; the engine is still the table's only writer.
     *
     * @param radio the radio seam
     * @param identity this device's identity
     * @param nonces fresh HELLO nonces
     * @param handshakeTimeoutMillis how long a link may take (CR-010's register value on a device)
     * @param presence the presence table the feed reads
     */
    public DiscoveryEngine(final Radio radio, final Identity identity, final LinkSession.Nonces nonces,
            final long handshakeTimeoutMillis, final PresenceTable presence) {
        this(radio, identity, nonces, handshakeTimeoutMillis, presence, LinkSession.Refusals.NONE);
    }

    /**
     * The device engine: shared presence, and the person's block list.
     *
     * @param radio the radio seam
     * @param identity this device's identity
     * @param nonces fresh HELLO nonces
     * @param handshakeTimeoutMillis how long a link may take (CR-010's register value on a device)
     * @param presence the presence table the feed reads
     * @param refusals identities never answered or recorded (the block list)
     */
    public DiscoveryEngine(final Radio radio, final Identity identity, final LinkSession.Nonces nonces,
            final long handshakeTimeoutMillis, final PresenceTable presence, final LinkSession.Refusals refusals) {
        this.refusals = refusals;
        this.radio = radio;
        this.identity = identity;
        this.nonces = nonces;
        this.handshakeTimeoutMillis = handshakeTimeoutMillis;
        this.presence = presence;
    }

    /** Starts discovery: sightings count and links may open. */
    public void start() {
        running = true;
    }

    /** The one-action stop (SRS-NBY-017): every link closes, nothing new starts, candidates are forgotten. */
    public void stop() {
        running = false;
        for (final String handle : new ArrayList<>(links.keySet())) {
            radio.disconnect(handle);
        }
        links.clear();
        candidateByLink.clear();
        refusedUntil.clear();
        for (final DiscoveryTriage.Candidate c : triage.candidates()) {
            triage.remove(c.key());
        }
    }

    /** @return true between start and stop */
    public boolean running() {
        return running;
    }

    /** @return the links open now, in either role */
    public int activeLinks() {
        return links.size();
    }

    /** @return the authenticated cards, newest first */
    public List<PresenceTable.Card> cards() {
        return presence.cards();
    }

    /**
     * The scanner saw an advertiser.
     *
     * @param sighting what it saw
     */
    public void onSighting(final ScanSighting sighting) {
        if (running) {
            triage.ingest(sighting);
        }
    }

    /**
     * Advances time: ends overdue links, ages out stale candidates and cards,
     * and spends the free connect budget.
     *
     * @param nowMillis now
     */
    public void tick(final long nowMillis) {
        for (final Map.Entry<String, LinkSession> link : new ArrayList<>(links.entrySet())) {
            link.getValue().tick(nowMillis);
            settle(link.getKey(), link.getValue(), nowMillis);
        }
        triage.prune(nowMillis);
        presence.prune(nowMillis);
        refusedUntil.values().removeIf(until -> until <= nowMillis);
        if (!running) {
            return;
        }
        // One slot always stays free for a link someone else opens to us.
        // Without it, a room where every phone fills its budget with its own
        // outgoing connects at the same moment refuses every incoming link,
        // and nobody finds anybody (the venue test's deadlock).
        // A peer already on a card is dropped from rotation without spending
        // budget; otherwise the same strongest few, all known, would fill
        // every wave and starve the peers still to be found.
        int budget = LumosConstants.MAX_CONCURRENT_GATT_LINKS_DEFAULT - links.size() - INBOUND_RESERVE;
        for (final DiscoveryTriage.Candidate candidate : triage.candidates()) {
            if (budget <= 0) {
                break;
            }
            triage.remove(candidate.key());
            if (alreadyPresent(candidate.key()) || refusedUntil.containsKey(candidate.key())) {
                continue;
            }
            budget--;
            final String handle = "c" + (++nextHandle);
            links.put(handle, session(LinkSession.Role.CENTRAL, handle));
            candidateByLink.put(handle, candidate.key());
            radio.connect(handle, candidate.linkToken());
        }
    }

    private boolean alreadyPresent(final String candidateKey) {
        final String peerKey = peerByCandidate.get(candidateKey);
        if (peerKey == null) {
            return false;
        }
        for (final PresenceTable.Card card : presence.cards()) {
            if (card.peerKey().equals(peerKey)) {
                return true;
            }
        }
        peerByCandidate.remove(candidateKey);
        return false;
    }

    private LinkSession session(final LinkSession.Role role, final String handle) {
        return new LinkSession(role, identity.freshExchange(), frame -> radio.send(handle, frame), nonces,
                handshakeTimeoutMillis, refusals);
    }

    /**
     * A central link is connected and subscribed to LINK_OUT: say HELLO.
     *
     * @param handle the link
     * @param nowMillis now
     */
    public void onLinkReady(final String handle, final long nowMillis) {
        final LinkSession link = links.get(handle);
        if (link != null) {
            link.open(nowMillis);
            settle(handle, link, nowMillis);
        }
    }

    /**
     * A central subscribed to our LINK_OUT: a peripheral-role link opens and
     * says HELLO. Refused (closed) when the budget is full or discovery is off.
     *
     * @param handle the adapter's name for the link
     * @param nowMillis now
     */
    public void onCentralSubscribed(final String handle, final long nowMillis) {
        if (!running || links.containsKey(handle)
                || links.size() >= LumosConstants.MAX_CONCURRENT_GATT_LINKS_DEFAULT) {
            radio.disconnect(handle);
            return;
        }
        final LinkSession link = session(LinkSession.Role.PERIPHERAL, handle);
        links.put(handle, link);
        link.open(nowMillis);
        settle(handle, link, nowMillis);
    }

    /**
     * A whole frame arrived on a link.
     *
     * @param handle the link
     * @param frame the reassembled frame
     * @param nowMillis now
     */
    public void onFrame(final String handle, final byte[] frame, final long nowMillis) {
        final LinkSession link = links.get(handle);
        if (link != null) {
            link.onFrame(frame, nowMillis);
            settle(handle, link, nowMillis);
        }
    }

    /**
     * The link dropped (either side, or the radio).
     *
     * @param handle the link
     */
    public void onLinkClosed(final String handle) {
        links.remove(handle);
        candidateByLink.remove(handle);
    }

    private void settle(final String handle, final LinkSession link, final long nowMillis) {
        if (link.state() == LinkSession.State.DONE) {
            final PresenceTable.Card card = presence.record(link.peer(), nowMillis);
            final String candidate = candidateByLink.get(handle);
            if (candidate != null) {
                peerByCandidate.put(candidate, card.peerKey());
            }
            close(handle);
        } else if (link.state() == LinkSession.State.FAILED) {
            final String candidate = candidateByLink.get(handle);
            if (link.refused() && candidate != null) {
                refusedUntil.put(candidate, nowMillis + (long) LumosConstants.PRESENCE_AGEOUT_SEC_DEFAULT * 1000);
            }
            close(handle);
        }
    }

    private void close(final String handle) {
        links.remove(handle);
        candidateByLink.remove(handle);
        radio.disconnect(handle);
    }
}
