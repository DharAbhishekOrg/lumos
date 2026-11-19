/*
 * Lumos - android-app/app / The Nearby feed's one state machine.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-NBY-010, SRS-NBY-011, SRS-NBY-012, SRS-NBY-013, SRS-NBY-014,
 *             SRS-NBY-015, TRS-ENV-003, SRS-NFR-PERF-009
 * ADRs: ADR-002
 * Threading: serial-executor
 * Notes: Everything the Nearby screen needs to decide, decided off-screen so
 * it all runs in JVM tests: the view lifecycle arms and disarms the
 * TRS-ENV-003 ladder and closes each session into the discovery-success
 * metric; typed scan failures fold to the SRS-NBY-010 states; and the cards
 * the screen gets are presence AFTER the three vetoes - hidden (silent,
 * identity-keyed), blocked (never reappears under rotation, because the key
 * is identity) and the capability filter (SRS-NBY-011's local half: a peer
 * whose authenticated capability bits share nothing with the user's interest
 * mask is not shown, and the full profile is never consulted, let alone
 * uploaded). state() is pure computation over small tables - the
 * SRS-NBY-015/PERF-009 CI bound in FeedResponsivenessTest holds it to
 * feed-refresh cost a frame can afford.
 */

package com.lumos.app.nearby;

import com.lumos.app.ble.BlePermissions;
import com.lumos.app.ble.DiscoverySuccessMetric;
import com.lumos.app.ble.PresenceTable;
import com.lumos.app.ble.RescanLadder;
import com.lumos.app.store.StoreException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Turns presence, vetoes and radio states into one renderable value. */
public final class NearbyFeedController {

    /** An interest mask accepting every peer; filtering is opt-in. */
    public static final int ACCEPT_ALL = ~0;

    private final PresenceTable presence;
    private final HiddenPeers hidden;
    private final BlockedPeers blocked;
    private final RescanLadder ladder;
    private final DiscoverySuccessMetric metric;

    private final Map<String, Boolean> vetoCache = new HashMap<>();

    private BlePermissions.ScanFailure failure;
    private int interestMask = ACCEPT_ALL;
    private boolean viewing;

    /**
     * Binds the controller to its collaborators.
     *
     * @param presence the authenticated presence table
     * @param hidden the hidden-peer list
     * @param blocked the block-list reader
     * @param ladder the TRS-ENV-003 rescan ladder
     * @param metric the discovery-success metric
     */
    public NearbyFeedController(final PresenceTable presence, final HiddenPeers hidden,
            final BlockedPeers blocked, final RescanLadder ladder,
            final DiscoverySuccessMetric metric) {
        this.presence = presence;
        this.hidden = hidden;
        this.blocked = blocked;
        this.ladder = ladder;
        this.metric = metric;
    }

    /** The user opened Nearby: the ladder arms with the view. */
    public void viewStarted() {
        viewing = true;
        ladder.nearbyViewStarted();
    }

    /**
     * The user left Nearby: the ladder disarms and the session closes into
     * one privacy-safe metric record.
     *
     * @param nowMillis the caller's clock, for the closing presence count
     * @throws StoreException when the metric record cannot be written
     */
    public void viewEnded(final long nowMillis) throws StoreException {
        if (!viewing) {
            return;
        }
        viewing = false;
        final int present = state(nowMillis).cards().size();
        metric.record(ladder.cyclesRun(), ladder.maxStepReached(), present);
        ladder.nearbyViewEnded();
    }

    /**
     * Visible Nearby changed; the ladder follows.
     *
     * @param visibleNearby whether the user is discoverable
     */
    public void visibilityChanged(final boolean visibleNearby) {
        ladder.visibilityChanged(visibleNearby);
    }

    /**
     * A scan cycle finished.
     *
     * @param sightingsInCycle how many Lumos-shaped sightings it saw
     */
    public void scanCycleCompleted(final int sightingsInCycle) {
        ladder.cycleCompleted(sightingsInCycle);
    }

    /**
     * Scanning failed in a typed way; the feed shows the situation.
     *
     * @param scanFailure the TRS-AND-005 typed state
     */
    public void scanFailed(final BlePermissions.ScanFailure scanFailure) {
        this.failure = scanFailure;
    }

    /** Scanning runs again; the trouble state clears. */
    public void scanRecovered() {
        this.failure = null;
    }

    /**
     * Sets the local interest filter (SRS-NBY-011). The mask is matched
     * against AUTHENTICATED capability bits only - nothing is fetched and
     * nothing leaves the device to apply it.
     *
     * @param mask capability bits the user wants; ACCEPT_ALL shows everyone
     */
    public void setInterestMask(final int mask) {
        this.interestMask = mask;
    }

    /**
     * What the screen renders right now.
     *
     * @param nowMillis the caller's clock, for presence age-out
     * @return the state, with cards filtered through every veto
     * @throws StoreException on engine failure during a veto lookup
     */
    public FeedState state(final long nowMillis) throws StoreException {
        if (failure != null) {
            return FeedState.of(failure);
        }
        presence.prune(nowMillis);
        final List<PresenceTable.Card> kept = new ArrayList<>();
        for (final PresenceTable.Card card : presence.cards()) {
            // ACCEPT_ALL means no filter at all - even a peer advertising no
            // capability bits stays visible until the user narrows things.
            if (interestMask != ACCEPT_ALL && (card.capabilityBits() & interestMask) == 0) {
                continue;
            }
            // The veto verdict is cached per identity so a feed refresh costs
            // table walks, not blind-index queries - the SRS-NFR-PERF-009
            // bound depends on it. The cache empties whenever a veto can
            // change from inside this process (hideCard); a block placed by
            // another flow lands on the next controller, which is the next
            // screen.
            Boolean vetoed = vetoCache.get(card.peerKey());
            if (vetoed == null) {
                vetoed = hidden.isHidden(card.peerKey()) || blocked.isBlocked(card.peerKey());
                vetoCache.put(card.peerKey(), vetoed);
            }
            if (vetoed) {
                continue;
            }
            kept.add(card);
        }
        if (kept.isEmpty()) {
            return FeedState.scanningEmpty();
        }
        kept.sort(Comparator.comparingLong(PresenceTable.Card::confirmedAtMillis).reversed());
        return FeedState.cards(kept);
    }

    /**
     * Hides one card, silently (SRS-NBY-012).
     *
     * @param peerKey the card's authenticated identifier
     * @param atUtcMillis when the user chose it
     * @throws StoreException on low storage or engine failure
     */
    public void hideCard(final String peerKey, final long atUtcMillis) throws StoreException {
        hidden.hide(peerKey, atUtcMillis);
        vetoCache.clear();
    }
}
