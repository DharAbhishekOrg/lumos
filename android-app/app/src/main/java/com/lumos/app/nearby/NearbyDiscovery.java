/*
 * Lumos - android-app/app / Live discovery for the app: scanner, links, engine and feed, on their threads.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-NBY-003, SRS-NBY-005, SRS-NBY-010, SRS-NBY-012, SRS-NBY-013, SRS-NBY-017,
 *             SRS-SAF-002, TRS-ENV-001, TRS-ENV-002
 * ADRs: ADR-002, ADR-013
 * Threading: main for start, stop, state and hideCard; the engine side runs on the
 *            serial link executor and the feed side on the store's serial executor
 * Notes: NB-1 step 3c. Discovery runs exactly while Visible Nearby's radio
 * runs: started and stopped with the advertiser, so the person looks for and
 * connects to others only while they can be seen themselves and the app is
 * open (owner ruling, 2026-11-28). Connecting sends this person's HELLO, and a
 * HELLO is what puts a card on someone else's screen, so looking while hidden
 * would break "Nobody can see you nearby until you turn on Visible Nearby".
 *
 * Two threads, each owning its own state, never sharing a table:
 * - the link executor runs the scanner callbacks, the radio and the
 *   DiscoveryEngine (with its own presence table for "already found");
 * - the store's executor runs the feed: every peer a link verifies is copied
 *   into the feed's presence table there, and NearbyFeedController applies
 *   the hidden and blocked vetoes against the store and decides the state.
 * The block list is the engine's refusals (SRS-SAF-002): each HELLO's key is
 * checked on the store's executor with a bounded wait, and anything but a
 * clear "not blocked" refuses (fails closed). A new feed controller is made
 * at every start, so a block placed elsewhere in the meantime always applies.
 * Stopping forgets every card at once and closes every link; nothing about
 * anyone met is kept past the presence age-out.
 */

package com.lumos.app.nearby;

import com.lumos.app.ble.BlePermissions;
import com.lumos.app.ble.BleScanner;
import com.lumos.app.ble.DiscoveryEngine;
import com.lumos.app.ble.DiscoverySuccessMetric;
import com.lumos.app.ble.LinkRadio;
import com.lumos.app.ble.PresenceTable;
import com.lumos.app.ble.RescanLadder;
import com.lumos.app.ble.ScanSighting;
import com.lumos.app.store.LocalStore;
import com.lumos.app.store.StoreException;
import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.hello.Hello;
import java.security.SecureRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/** The app's live discovery. */
public final class NearbyDiscovery {

    /** How often links are ticked (timeouts, triage) and the feed is refreshed. */
    static final long TICK_MILLIS = 1_000L;

    /**
     * How long a HELLO waits for the block-list answer before it is refused.
     * Well inside the handshake timeout, so a busy store refuses rather than
     * stalls the link budget.
     */
    static final long REFUSAL_WAIT_MILLIS = 2_000L;

    private final LinkRadio link;
    private final BleScanner scanner;
    private final LocalStore store;
    private final ScheduledExecutorService linkExecutor;
    private final Executor storage;
    private final LongSupplier clock;
    private final Runnable changed;
    private final DiscoveryEngine engine;
    private final BlockedPeers blocked;
    private final SecureRandom random = new SecureRandom();

    // Store-executor state.
    private final PresenceTable feedPresence = new PresenceTable();
    private NearbyFeedController feed;

    // Main-thread state.
    private volatile boolean running;
    private volatile FeedState latest = FeedState.notVisible();
    private ScheduledFuture<?> ticker;

    /**
     * @param link the radio (AndroidGattLink on a device)
     * @param scanner the advertisement scanner
     * @param identity this install's HELLO identity
     * @param store the open store (hidden and blocked lists, the metric)
     * @param linkExecutor the serial executor the radio and engine run on
     * @param storage the store's serial executor
     * @param clock the millisecond clock
     * @param changed called after the feed state changes, on the store's executor
     */
    public NearbyDiscovery(final LinkRadio link, final BleScanner scanner, final DiscoveryEngine.Identity identity,
            final LocalStore store, final ScheduledExecutorService linkExecutor, final Executor storage,
            final LongSupplier clock, final Runnable changed) {
        this.link = link;
        this.scanner = scanner;
        this.store = store;
        this.linkExecutor = linkExecutor;
        this.storage = storage;
        this.clock = clock;
        this.changed = changed;
        this.blocked = new BlockedPeers(store);
        this.engine = new DiscoveryEngine(link, identity, this::nonce,
                (long) LumosConstants.HELLO_HANDSHAKE_TIMEOUT_SEC_DEFAULT * 1000, new PresenceTable(),
                this::refuses);
        engine.setOnFound((peer, now) -> storage.execute(() -> {
            feedPresence.record(peer, now);
            publish();
        }));
        linkExecutor.execute(() -> link.bind(engine));
    }

    /** Starts looking, connecting and answering. Main thread; idempotent. */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        latest = FeedState.scanningEmpty();
        storage.execute(() -> {
            feedPresence.clear();
            feed = new NearbyFeedController(feedPresence, new HiddenPeers(store), blocked, new RescanLadder(),
                    new DiscoverySuccessMetric(store));
            feed.viewStarted();
            feed.visibilityChanged(true);
            publish();
        });
        linkExecutor.execute(() -> {
            link.openServer();
            engine.start();
            scanner.start(new BleScanner.Listener() {
                @Override
                public void onSighting(final ScanSighting sighting) {
                    linkExecutor.execute(() -> engine.onSighting(sighting));
                }

                @Override
                public void onScanFailed(final BlePermissions.ScanFailure state) {
                    storage.execute(() -> {
                        if (feed != null) {
                            feed.scanFailed(state);
                        }
                        publish();
                    });
                }
            });
        });
        ticker = linkExecutor.scheduleWithFixedDelay(() -> {
            engine.tick(clock.getAsLong());
            storage.execute(this::publish);
        }, TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS);
    }

    /** Stops everything at once: scanning, every link, every card. Main thread; idempotent. */
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        latest = FeedState.notVisible();
        if (ticker != null) {
            ticker.cancel(false);
            ticker = null;
        }
        linkExecutor.execute(() -> {
            scanner.stop();
            engine.stop();
            link.close();
        });
        storage.execute(() -> {
            if (feed != null) {
                try {
                    feed.viewEnded(clock.getAsLong());
                } catch (final StoreException metricNotWritten) {
                    // The session metric is best-effort; stopping is not.
                }
                feed = null;
            }
            feedPresence.clear();
            changed.run();
        });
    }

    /** @return what the Nearby screen shows now; main-safe */
    public FeedState state() {
        return running ? latest : FeedState.notVisible();
    }

    /** @return true while discovery runs */
    public boolean running() {
        return running;
    }

    /**
     * Hides one card, silently (SRS-NBY-012). Main thread.
     *
     * @param peerKey the card's identifier
     */
    public void hideCard(final String peerKey) {
        storage.execute(() -> {
            if (feed == null) {
                return;
            }
            try {
                feed.hideCard(peerKey, clock.getAsLong());
            } catch (final StoreException notStored) {
                // Nothing was hidden; the card stays, which is the truth.
            }
            publish();
        });
    }

    /** Store executor: recompute and hand the state to the screen. */
    private void publish() {
        if (!running || feed == null) {
            return;
        }
        try {
            latest = feed.state(clock.getAsLong());
        } catch (final StoreException vetoUnreadable) {
            // A veto that cannot be read shows nobody rather than someone hidden.
            latest = FeedState.scanningEmpty();
        }
        changed.run();
    }

    /** Link executor: the block list, read where the store lives, failing closed. */
    private boolean refuses(final String peerKey) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return blocked.isBlocked(peerKey);
                } catch (final StoreException unreadable) {
                    return Boolean.TRUE;
                }
            }, storage).get(REFUSAL_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return true;
        } catch (final ExecutionException | TimeoutException noAnswer) {
            return true;
        }
    }

    private byte[] nonce() {
        final byte[] nonce = new byte[Hello.NONCE_LEN];
        random.nextBytes(nonce);
        return nonce;
    }
}
