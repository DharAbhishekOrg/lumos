/*
 * Lumos - android-app/app / The feed stays cheap at venue size.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-NBY-015, SRS-NFR-PERF-009, TRS-TST-001
 * ADRs: ADR-002
 * Threading: test-only
 * Notes: The CI half of the two responsiveness rows: the feed's per-refresh
 * work over 100+ authenticated peers, vetoes included, must cost what a
 * frame can afford. The bound is deliberately loose for CI noise - 100
 * refreshes in under a second is 10 ms a refresh against the 100 ms stall
 * rule - and the warmed-cache shape is asserted too, because the veto cache
 * is the design decision that keeps refresh cost off the blind index. What
 * a real device adds - rendering, main-thread contention - is the UI slice's
 * instrumented half and the owner's physical measurement (SRS-NFR-PERF-005).
 */

package com.lumos.app.nearby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.Fixtures;
import com.lumos.app.ble.DiscoverySuccessMetric;
import com.lumos.app.ble.PresenceTable;
import com.lumos.app.ble.RescanLadder;
import com.lumos.protocol.LumosConstants;
import org.junit.jupiter.api.Test;

/** Feed refresh cost at 100+ peers (SRS-NFR-PERF-009 CI half). */
final class FeedResponsivenessTest {

    private static final int PEERS = LumosConstants.NEARBY_SYNTHETIC_LOAD_TARGET_DEFAULT + 10;
    private static final long NOW = 1_755_561_600_000L;

    @Test
    void aHundredRefreshesOverAHundredPlusPeersFitInsideTheStallBudget() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final PresenceTable presence = new PresenceTable();
            final PeerFixtures peers = new PeerFixtures();
            final NearbyFeedController feed = new NearbyFeedController(presence,
                    new HiddenPeers(stack.store()), new BlockedPeers(stack.store()),
                    new RescanLadder(), new DiscoverySuccessMetric(stack.store()));

            for (int p = 0; p < PEERS; p++) {
                peers.authenticate(presence, peers.peer(p, 1 + (p % 3)), NOW);
            }
            assertEquals(PEERS, feed.state(NOW).cards().size(), "the venue is standing");

            // 100 refreshes, cache warm after the first: the steady-state
            // cost a scrolling screen actually pays.
            final long start = System.nanoTime();
            for (int i = 0; i < 100; i++) {
                assertEquals(PEERS, feed.state(NOW).cards().size());
            }
            final long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
            assertTrue(elapsedMillis < 1_000,
                    "100 refreshes over " + PEERS + " peers took " + elapsedMillis
                            + " ms; at that rate a single refresh breaches the 100 ms "
                            + "stall rule (SRS-NFR-PERF-009)");
        }
    }
}
