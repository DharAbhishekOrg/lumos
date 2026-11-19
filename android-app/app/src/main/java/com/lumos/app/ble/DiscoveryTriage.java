/*
 * Lumos - android-app/app / RSSI-sorted candidate triage with a bounded connect budget.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-ENV-001, TRS-ENV-002, TRS-PROTO-017, SRS-NBY-005
 * ADRs: ADR-002
 * Threading: serial-executor
 * Notes: A venue puts a hundred-plus advertisers in radio range (TRS-ENV-001),
 * and the failure shape to prevent is unbounded connection attempts. This
 * table turns raw sightings into CANDIDATES: structurally valid hints keyed on
 * their rotating EID, hintless UUID-only sightings (every iOS peer, degraded
 * Android peers) keyed on their link token, RSSI-banded, aged out on the
 * register bound, and handed to the connector in RSSI order never exceeding
 * MAX_CONCURRENT_GATT_LINKS minus what is already connected.
 *
 * What a candidate is NOT is presence (TRS-ENV-002): nothing here may be
 * rendered as a nearby card. A candidate is "worth spending a connection on";
 * presence exists only after an authenticated HELLO, which is WP-03 S4's
 * layer. The cheap discard is Adv.parse - structure only, because a stranger's
 * authTag is not verifiable and a verified tag would still not be identity
 * (TRS-PROTO-017).
 */

package com.lumos.app.ble;

import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.adv.Adv;
import com.lumos.protocol.wire.Frame;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ingests sightings, maintains candidates, enforces the connect budget. */
public final class DiscoveryTriage {

    /** The register RSSI bands (RSSI_BANDS_DBM). */
    public enum RssiBand {
        /** rssi {@code >=} very_close_gte. */
        VERY_CLOSE,
        /** rssi {@code >=} nearby_gte. */
        NEARBY,
        /** everything weaker. */
        FAR
    }

    /** One connect-worthy sighting, keyed and banded. */
    public static final class Candidate {

        private final String key;
        private final String linkToken;
        private final boolean hinted;
        private final int rssiDbm;
        private final long lastSeenMillis;

        private Candidate(final String key, final String linkToken, final boolean hinted,
                final int rssiDbm, final long lastSeenMillis) {
            this.key = key;
            this.linkToken = linkToken;
            this.hinted = hinted;
            this.rssiDbm = rssiDbm;
            this.lastSeenMillis = lastSeenMillis;
        }

        /** @return the triage key: EID hex for hinted peers, link token otherwise */
        public String key() {
            return key;
        }

        /** @return the transport's opaque connect handle */
        public String linkToken() {
            return linkToken;
        }

        /** @return whether this candidate carried a structurally valid adv_v1 hint */
        public boolean hinted() {
            return hinted;
        }

        /** @return the strongest recent signal strength */
        public int rssiDbm() {
            return rssiDbm;
        }

        /** @return when this candidate was last sighted */
        public long lastSeenMillis() {
            return lastSeenMillis;
        }

        /** @return the register band for the current signal strength */
        public RssiBand band() {
            if (rssiDbm >= LumosConstants.RSSI_BANDS_DBM_VERY_CLOSE_GTE) {
                return RssiBand.VERY_CLOSE;
            }
            if (rssiDbm >= LumosConstants.RSSI_BANDS_DBM_NEARBY_GTE) {
                return RssiBand.NEARBY;
            }
            return RssiBand.FAR;
        }
    }

    private final Map<String, Candidate> candidates = new LinkedHashMap<>();
    private long structurallyDiscarded;

    /**
     * Ingests one sighting: cheap structural discard, then candidate upsert.
     *
     * @param sighting the sighting
     * @return the candidate it became, or null when the hint was discarded
     */
    public Candidate ingest(final ScanSighting sighting) {
        final byte[] serviceData = sighting.serviceData();
        String key = sighting.linkToken();
        boolean hinted = false;
        if (serviceData != null) {
            try {
                final Adv.Payload payload = Adv.parse(serviceData);
                key = hex(payload.eid());
                hinted = true;
            } catch (final Frame.WireException discarded) {
                // Pre-connection noise rejection (ADR-002): not Lumos-shaped,
                // silently dropped, counted so the synthetic harness can
                // assert the discard path actually ran.
                structurallyDiscarded++;
                return null;
            }
        }
        final Candidate existing = candidates.get(key);
        final int rssi = existing == null
                ? sighting.rssiDbm()
                : Math.max(existing.rssiDbm(), sighting.rssiDbm());
        final Candidate merged =
                new Candidate(key, sighting.linkToken(), hinted, rssi, sighting.atMillis());
        candidates.put(key, merged);
        return merged;
    }

    /**
     * Drops candidates not sighted within the register age-out bound.
     *
     * @param nowMillis the caller's clock
     * @return how many candidates aged out
     */
    public int prune(final long nowMillis) {
        final long bound = (long) LumosConstants.PRESENCE_AGEOUT_SEC_DEFAULT * 1000;
        final int before = candidates.size();
        candidates.values().removeIf(c -> nowMillis - c.lastSeenMillis() > bound);
        return before - candidates.size();
    }

    /** @return every live candidate, strongest signal first */
    public List<Candidate> candidates() {
        final List<Candidate> out = new ArrayList<>(candidates.values());
        out.sort(Comparator.comparingInt(Candidate::rssiDbm).reversed());
        return out;
    }

    /** @return how many candidates are currently live */
    public int size() {
        return candidates.size();
    }

    /** @return how many sightings the structural cheap discard rejected */
    public long structurallyDiscarded() {
        return structurallyDiscarded;
    }

    /**
     * The candidates worth a connection right now: RSSI order, never more than
     * the register link budget leaves free (TRS-ENV-001's bounded connect
     * budget, in place of unbounded attempts).
     *
     * @param activeLinks how many GATT links are already established
     * @return the next candidates to connect, possibly empty
     */
    public List<Candidate> nextConnects(final int activeLinks) {
        final int budget =
                Math.max(0, LumosConstants.MAX_CONCURRENT_GATT_LINKS_DEFAULT - activeLinks);
        final List<Candidate> ordered = candidates();
        return ordered.subList(0, Math.min(budget, ordered.size()));
    }

    /**
     * Takes a candidate out of rotation - the connector calls this the moment
     * it spends a link on one, so the next wave is the next strongest peers
     * rather than the same four forever. A later sighting makes the peer a
     * candidate again; whether it is worth a SECOND connection is the
     * connector's question to answer against the presence table, not this
     * one's.
     *
     * @param key the candidate's triage key
     * @return whether a candidate was removed
     */
    public boolean remove(final String key) {
        return candidates.remove(key) != null;
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
