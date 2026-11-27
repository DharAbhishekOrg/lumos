/*
 * Lumos - android-app/app / Presence: authenticated peers, and nothing else.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-ENV-002, SRS-NBY-006, SRS-NBY-007, SRS-IDN-002, TRS-PROTO-017
 * ADRs: ADR-002, ADR-008
 * Threading: serial-executor
 * Notes: TRS-ENV-002's zero-false-positive rule, enforced by construction: the
 * ONLY way into this table is a HelloExchange.Peer, and the only way to hold
 * one of those is a verified HELLO_ACK to one's own fresh HELLO. No sighting,
 * no candidate, no parsed-but-unverified payload has a path here - the type
 * system is the proof the synthetic spoof tests then exercise. Cards key on
 * the SRS-IDN-002 peer identifier, so repeat sightings of one rotating peer
 * stay one card however many EIDs it burns (SRS-NBY-006), and stale cards age
 * out on the register bound (SRS-NBY-007). The card carries what the Nearby
 * surfaces may know; the raw identity key and the link token stay out of it
 * (SRS-NBY-008's data half).
 */

package com.lumos.app.ble;

import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.hello.HelloExchange;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Authenticated nearby presence (TRS-ENV-002). */
public final class PresenceTable {

    /** One present peer, as the Nearby surfaces may know it. */
    public static final class Card {

        private final String peerKey;
        private final int capabilityBits;
        private final long venueHint;
        private final long confirmedAtMillis;

        private Card(final String peerKey, final int capabilityBits, final long venueHint,
                final long confirmedAtMillis) {
            this.peerKey = peerKey;
            this.capabilityBits = capabilityBits;
            this.venueHint = venueHint;
            this.confirmedAtMillis = confirmedAtMillis;
        }

        /** @return the hex SRS-IDN-002 identifier - the dedup key, not UI copy */
        public String peerKey() {
            return peerKey;
        }

        /** @return the authenticated capability bits */
        public int capabilityBits() {
            return capabilityBits;
        }

        /** @return the authenticated venue hint */
        public long venueHint() {
            return venueHint;
        }

        /** @return when the peer last completed an authenticated HELLO */
        public long confirmedAtMillis() {
            return confirmedAtMillis;
        }
    }

    private final Map<String, Card> cards = new LinkedHashMap<>();

    /**
     * Records a completed, authenticated exchange. The parameter type is the
     * requirement: nothing but a HelloExchange.Peer can ever become presence.
     *
     * @param peer the authenticated peer
     * @param nowMillis the caller's clock
     * @return the card, new or refreshed
     */
    public Card record(final HelloExchange.Peer peer, final long nowMillis) {
        final String key = keyFor(peer.id());
        final Card card = new Card(key, peer.fields().capabilityBits(),
                peer.fields().venueHint(), nowMillis);
        cards.put(key, card);
        return card;
    }

    /**
     * Drops cards not re-confirmed within the register age-out bound
     * (SRS-NBY-007; the TRS-ENV-002 stale bound).
     *
     * @param nowMillis the caller's clock
     * @return how many cards aged out
     */
    public int prune(final long nowMillis) {
        final long bound = (long) LumosConstants.PRESENCE_AGEOUT_SEC_DEFAULT * 1000;
        final int before = cards.size();
        cards.values().removeIf(c -> nowMillis - c.confirmedAtMillis() > bound);
        return before - cards.size();
    }

    /** Forgets every card at once (discovery stopped: nothing stale may linger). */
    public void clear() {
        cards.clear();
    }

    /** @return every live card, most recently confirmed first */
    public List<Card> cards() {
        final List<Card> out = new ArrayList<>(cards.values());
        out.sort(Comparator.comparingLong(Card::confirmedAtMillis).reversed());
        return out;
    }

    /** @return how many peers are currently present */
    public int size() {
        return cards.size();
    }

    /**
     * The card key for a peer identifier - the one hidden and blocked lists
     * are keyed by, so a refusal can be decided before a card exists.
     *
     * @param peerId the peer identifier (Hello.peerId of its signing key)
     * @return the key, hex
     */
    public static String keyFor(final byte[] peerId) {
        return hex(peerId);
    }

    private static String hex(final byte[] b) {
        final StringBuilder sb = new StringBuilder(b.length * 2);
        for (final byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }
}
