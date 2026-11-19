/*
 * Lumos - android-app/app / Triage semantics and the TRS-ENV-001 synthetic venue.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-ENV-001, TRS-ENV-002, TRS-PROTO-017, TRS-TST-001
 * ADRs: ADR-002
 * Threading: test-only
 * Notes: The CI half of TRS-ENV-001: a synthetic venue of 100+ concurrent
 * advertisers - hinted Android peers built through the vector-pinned Adv code,
 * UUID-only iOS and degraded peers, and non-Lumos noise - driven through the
 * pipeline, asserting RSSI-sorted triage, the bounded connect budget in place
 * of unbounded attempts, structural cheap discard, EID-keyed dedup across
 * repeat sightings, and age-out on the register bound. What the radio adds on
 * a real floor - interference, OEM scan throttling - is the G-01 half of the
 * same requirement.
 */

package com.lumos.app.ble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.adv.Adv;
import java.util.List;
import org.junit.jupiter.api.Test;

/** DiscoveryTriage under venue load. */
final class DiscoveryTriageTest {

    private static final int HINTED_PEERS = 100;
    private static final int UUID_ONLY_PEERS = 15;
    private static final int NOISE_SIGHTINGS = 10;

    private final DiscoveryTriage triage = new DiscoveryTriage();

    private static byte[] kAdvFor(final int peer) {
        final byte[] k = new byte[32];
        for (int i = 0; i < k.length; i++) {
            k[i] = (byte) (peer * 31 + i);
        }
        return k;
    }

    private static ScanSighting hinted(final int peer, final int rssi, final long at)
            throws Exception {
        final byte[] hint = Adv.build(kAdvFor(peer), 0, 1);
        return new ScanSighting("aa:bb:cc:00:" + peer, hint, null, rssi, at);
    }

    @Test
    void aHundredPlusAdvertisersTriageBoundedAndSorted() throws Exception {
        // TRS-ENV-001: at least 100 concurrent advertisers in radio range.
        for (int peer = 0; peer < HINTED_PEERS; peer++) {
            triage.ingest(hinted(peer, -40 - peer, 1000));
        }
        for (int peer = 0; peer < UUID_ONLY_PEERS; peer++) {
            triage.ingest(new ScanSighting("ios:" + peer, null, null, -50 - peer, 1000));
        }
        for (int i = 0; i < NOISE_SIGHTINGS; i++) {
            triage.ingest(new ScanSighting("noise:" + i, new byte[] {1, 2, 3}, null, -30, 1000));
        }

        assertEquals(HINTED_PEERS + UUID_ONLY_PEERS, triage.size(),
                "every Lumos-shaped sighting is a candidate; noise is not");
        assertEquals(NOISE_SIGHTINGS, triage.structurallyDiscarded(),
                "the cheap discard path must actually run (ADR-002)");

        final List<DiscoveryTriage.Candidate> ordered = triage.candidates();
        for (int i = 1; i < ordered.size(); i++) {
            assertTrue(ordered.get(i - 1).rssiDbm() >= ordered.get(i).rssiDbm(),
                    "candidates must come strongest first (RSSI triage)");
        }

        // The bounded connect budget, in place of unbounded attempts.
        assertEquals(LumosConstants.MAX_CONCURRENT_GATT_LINKS_DEFAULT,
                triage.nextConnects(0).size());
        assertEquals(1, triage.nextConnects(LumosConstants.MAX_CONCURRENT_GATT_LINKS_DEFAULT - 1)
                .size());
        assertTrue(triage.nextConnects(LumosConstants.MAX_CONCURRENT_GATT_LINKS_DEFAULT).isEmpty(),
                "a full table spends nothing");
        assertTrue(triage.nextConnects(LumosConstants.MAX_CONCURRENT_GATT_LINKS_DEFAULT + 1)
                .isEmpty(), "an over-full table must not go negative");
    }

    @Test
    void repeatSightingsOfOnePeerStayOneCandidate() throws Exception {
        triage.ingest(hinted(1, -80, 1000));
        triage.ingest(hinted(1, -60, 2000));
        assertEquals(1, triage.size(), "the rotating EID is the dedup key for hinted peers");
        final DiscoveryTriage.Candidate c = triage.candidates().get(0);
        assertEquals(-60, c.rssiDbm(), "the strongest recent signal wins");
        assertEquals(2000, c.lastSeenMillis());
    }

    @Test
    void hintlessPeersAreFirstClassCandidates() {
        // TRS-PROTO-018: every iOS peer looks like this.
        final DiscoveryTriage.Candidate c =
                triage.ingest(new ScanSighting("ios:1", null, null, -50, 1000));
        assertTrue(c != null && !c.hinted());
        assertEquals(1, triage.size());
    }

    @Test
    void noiseIsDiscardedSilently() {
        assertNull(triage.ingest(new ScanSighting("x", new byte[17], null, -30, 0)),
                "a 17-byte blob is not adv_v1");
        assertEquals(0, triage.size());
        assertEquals(1, triage.structurallyDiscarded());
    }

    @Test
    void candidatesAgeOutOnTheRegisterBound() throws Exception {
        triage.ingest(hinted(1, -50, 0));
        triage.ingest(hinted(2, -50, 5_000));
        final long bound = (long) LumosConstants.PRESENCE_AGEOUT_SEC_DEFAULT * 1000;

        assertEquals(0, triage.prune(bound), "exactly at the bound is still live");
        assertEquals(2, triage.size());

        assertEquals(1, triage.prune(bound + 1), "past the bound, the stale candidate goes");
        assertEquals(1, triage.size());
        assertEquals(1, triage.candidates().size(), "peer 2 remains");
    }

    @Test
    void rssiBandsFollowTheRegisterThresholds() throws Exception {
        assertEquals(DiscoveryTriage.RssiBand.VERY_CLOSE,
                triage.ingest(hinted(1, LumosConstants.RSSI_BANDS_DBM_VERY_CLOSE_GTE, 0)).band());
        assertEquals(DiscoveryTriage.RssiBand.NEARBY,
                triage.ingest(hinted(2, LumosConstants.RSSI_BANDS_DBM_NEARBY_GTE, 0)).band());
        assertEquals(DiscoveryTriage.RssiBand.FAR,
                triage.ingest(hinted(3, LumosConstants.RSSI_BANDS_DBM_NEARBY_GTE - 1, 0)).band());
    }
}
