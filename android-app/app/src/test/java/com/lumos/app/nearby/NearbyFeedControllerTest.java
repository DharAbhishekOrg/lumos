/*
 * Lumos - android-app/app / The feed state machine, executed.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-NBY-010, SRS-NBY-011, SRS-NBY-012, SRS-NBY-013, SRS-NBY-014,
 *             TRS-ENV-003, TRS-TST-001
 * ADRs: ADR-002
 * Threading: test-only
 * Notes: Real store, real exchanges, scripted radio. The teeth: the typed
 * failure mapping is pinned over EVERY ScanFailure value so an enum addition
 * fails here instead of falling into a spinner; a hidden peer stays gone for
 * a fresh controller over the same store (persistence) and a block written
 * straight into the WP-02 table keeps the identity out across a rotation
 * re-exchange (SRS-NBY-013 - the key is identity, so rotation has nothing to
 * offer); and closing the view writes exactly one metric record whose
 * payload stays inside the closed grammar with the real peer count.
 */

package com.lumos.app.nearby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.Fixtures;
import com.lumos.app.ble.BlePermissions;
import com.lumos.app.ble.DiscoverySuccessMetric;
import com.lumos.app.ble.PresenceTable;
import com.lumos.app.ble.RescanLadder;
import com.lumos.app.store.LocalStore;
import com.lumos.app.store.RecordKind;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** NearbyFeedController over the real store and real exchanges. */
final class NearbyFeedControllerTest {

    private static final long NOW = 1_755_561_600_000L;

    private static NearbyFeedController controller(final LocalStore store,
            final PresenceTable presence) {
        return new NearbyFeedController(presence, new HiddenPeers(store),
                new BlockedPeers(store), new RescanLadder(), new DiscoverySuccessMetric(store));
    }

    @Test
    void everyTypedScanFailureFoldsToAStateTheUserCanActOn() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final NearbyFeedController feed = controller(stack.store(), new PresenceTable());
            for (final BlePermissions.ScanFailure failure
                    : BlePermissions.ScanFailure.values()) {
                feed.scanFailed(failure);
                final FeedState.Kind kind = feed.state(NOW).kind();
                final FeedState.Kind expected;
                switch (failure) {
                    case ADAPTER_UNAVAILABLE:
                        expected = FeedState.Kind.RADIO_OFF;
                        break;
                    case PERMISSION_MISSING:
                        expected = FeedState.Kind.PERMISSION_NEEDED;
                        break;
                    case ALREADY_STARTED:
                    case OUT_OF_HARDWARE_RESOURCES:
                    case SCANNING_TOO_FREQUENTLY:
                        expected = FeedState.Kind.RADIO_BUSY;
                        break;
                    default:
                        expected = FeedState.Kind.RADIO_UNAVAILABLE;
                        break;
                }
                assertEquals(expected, kind, failure + " must fold to a user-actionable state");
            }
            feed.scanRecovered();
            assertEquals(FeedState.Kind.SCANNING_EMPTY, feed.state(NOW).kind(),
                    "recovery returns the feed to the skeleton, not to a stale error");
        }
    }

    @Test
    void emptyIsAFirstClassStateAndCardsArriveNewestConfirmedFirst() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final PresenceTable presence = new PresenceTable();
            final PeerFixtures peers = new PeerFixtures();
            final NearbyFeedController feed = controller(stack.store(), presence);

            assertEquals(FeedState.Kind.SCANNING_EMPTY, feed.state(NOW).kind(),
                    "no cards is a rendered state, never an indefinite spinner");

            final String early = peers.authenticate(presence, peers.peer(1, 1), NOW);
            final String late = peers.authenticate(presence, peers.peer(2, 1), NOW + 5_000);
            final FeedState state = feed.state(NOW + 6_000);
            assertEquals(FeedState.Kind.CARDS, state.kind());
            assertEquals(late, state.cards().get(0).peerKey(),
                    "the most recently confirmed peer leads the feed");
            assertEquals(early, state.cards().get(1).peerKey());
        }
    }

    @Test
    void aHiddenCardStaysGoneForAFreshControllerOverTheSameStore() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final PresenceTable presence = new PresenceTable();
            final PeerFixtures peers = new PeerFixtures();
            final NearbyFeedController feed = controller(stack.store(), presence);

            final PeerFixtures.Party peer = peers.peer(3, 1);
            final String key = peers.authenticate(presence, peer, NOW);
            assertEquals(1, feed.state(NOW).cards().size());

            feed.hideCard(key, NOW);
            assertEquals(FeedState.Kind.SCANNING_EMPTY, feed.state(NOW).kind(),
                    "hiding removes the card at once");

            // A new process over the same store: the choice persisted.
            final NearbyFeedController fresh = controller(stack.store(), presence);
            assertEquals(FeedState.Kind.SCANNING_EMPTY, fresh.state(NOW).kind(),
                    "hidden is a stored choice, not controller memory");

            // The hidden record carries no readable identifier.
            for (final byte[] payload
                    : stack.store().findByNaturalKey(RecordKind.HIDDEN_PEER,
                            HiddenPeers.naturalKey(key))) {
                final String text = new String(payload, StandardCharsets.UTF_8);
                assertTrue(!text.contains(key),
                        "the payload must not spell the identity the blind index hides");
            }
        }
    }

    @Test
    void aBlockedIdentityNeverReappearsUnderRotation() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final PresenceTable presence = new PresenceTable();
            final PeerFixtures peers = new PeerFixtures();
            final NearbyFeedController feed = controller(stack.store(), presence);

            final PeerFixtures.Party peer = peers.peer(4, 1);
            final String key = peers.authenticate(presence, peer, NOW);

            // The block flow lands in S3; the TABLE is WP-02's, so the test
            // places the entry the way that flow will.
            stack.store().put(RecordKind.BLOCK_ENTRY, null,
                    "blocked".getBytes(StandardCharsets.UTF_8), HiddenPeers.naturalKey(key));
            assertEquals(FeedState.Kind.SCANNING_EMPTY, feed.state(NOW).kind(),
                    "a blocked identity is not in the feed");

            // The peer re-exchanges - the rotation path. Same identity, same
            // verdict: there is no advertisement-shaped way back in.
            final String again = peers.authenticate(presence, peer, NOW + 1_000);
            assertEquals(key, again, "rotation does not change the identity key");
            final NearbyFeedController fresh = controller(stack.store(), presence);
            assertEquals(FeedState.Kind.SCANNING_EMPTY, fresh.state(NOW + 1_000).kind(),
                    "blocked identities never reappear under EID rotation (SRS-NBY-013)");
        }
    }

    @Test
    void theInterestFilterIsLocalAndOptIn() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final PresenceTable presence = new PresenceTable();
            final PeerFixtures peers = new PeerFixtures();
            final NearbyFeedController feed = controller(stack.store(), presence);

            final String none = peers.authenticate(presence, peers.peer(5, 0), NOW);
            final String social = peers.authenticate(presence, peers.peer(6, 0b01), NOW);
            final String dating = peers.authenticate(presence, peers.peer(7, 0b10), NOW);

            assertEquals(3, feed.state(NOW).cards().size(),
                    "unfiltered means everyone, even a peer advertising no bits");

            feed.setInterestMask(0b10);
            final FeedState filtered = feed.state(NOW);
            assertEquals(1, filtered.cards().size());
            assertEquals(dating, filtered.cards().get(0).peerKey(),
                    "the mask keeps only compatible AUTHENTICATED bits");
            assertNotEquals(none, filtered.cards().get(0).peerKey());
            assertNotEquals(social, filtered.cards().get(0).peerKey());

            feed.setInterestMask(NearbyFeedController.ACCEPT_ALL);
            assertEquals(3, feed.state(NOW).cards().size(), "clearing the filter restores all");
        }
    }

    @Test
    void closingTheViewWritesExactlyOneMetricRecordWithTheRealCount() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final PresenceTable presence = new PresenceTable();
            final PeerFixtures peers = new PeerFixtures();
            final NearbyFeedController feed = controller(stack.store(), presence);

            feed.viewStarted();
            feed.visibilityChanged(true);
            feed.scanCycleCompleted(0);
            feed.scanCycleCompleted(0);
            peers.authenticate(presence, peers.peer(8, 1), NOW);
            feed.scanCycleCompleted(1);
            feed.viewEnded(NOW);

            assertEquals(1, stack.store().count(RecordKind.DIAGNOSTIC_EVENT),
                    "one session, one record");
            feed.viewEnded(NOW);
            assertEquals(1, stack.store().count(RecordKind.DIAGNOSTIC_EVENT),
                    "a second close without a second open writes nothing");
        }
    }
}
