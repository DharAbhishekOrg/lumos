/*
 * Lumos - android-app/app / The running app as the Nearby and home surfaces see it.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-001, SRS-NBY-003, SRS-NBY-005, SRS-NBY-010, SRS-NBY-017
 * ADRs: ADR-012
 * Threading: main-safe (the entry answer is cached; the disk is read off the main thread)
 * Notes: The composition root's NearbyHost, installed at process start. Entry
 * is allowed only once this install has finished onboarding (SRS-ONB-001,
 * AT-001): before that the home and Nearby activities finish without
 * inflating anything, exactly as they do with no host.
 *
 * Visible Nearby (NB-1 step 1) is attached once the store is open: its
 * controller drives the advertiser (UUID only), and a radio failure arriving
 * on a binder thread is hopped to the main thread before the controller sees
 * it. The one-action stop (SRS-NBY-017) turns Visible Nearby off.
 *
 * Live discovery (NB-1 step 3c) rides on Visible Nearby's radio: the
 * advertiser handed to the controller is a DiscoveringAdvertiser, so
 * scanning, connecting and answering run exactly while the person is visible
 * and the app is open (owner ruling, 2026-11-28). The HELLO identity is
 * unlocked on the storage executor after the controller loads; until then,
 * or on an install whose identity cannot be unlocked, the feed says NOT_YET
 * while visible rather than claiming to look. Feed changes arrive on the
 * storage executor and are hopped to the main thread.
 */

package com.lumos.app;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.lumos.app.ble.AndroidBleAdvertiser;
import com.lumos.app.ble.AndroidBleScanner;
import com.lumos.app.ble.AndroidGattLink;
import com.lumos.app.ble.NearbyIdentity;
import com.lumos.app.ble.TaskScheduler;
import com.lumos.app.media.BitmapReencoder;
import com.lumos.app.media.MediaObjectStore;
import com.lumos.app.nearby.DiscoveringAdvertiser;
import com.lumos.app.nearby.FeedState;
import com.lumos.app.nearby.NearbyDiscovery;
import com.lumos.app.nearby.NearbyHost;
import com.lumos.app.nearby.VisibleNearbyController;
import com.lumos.app.onboarding.DiscoveryVisibility;
import com.lumos.app.profile.ProfileRepository;
import com.lumos.app.profile.ProfilePhotos;
import com.lumos.app.profile.ProfileSource;
import com.lumos.app.store.LocalStore;
import com.lumos.app.store.StoreException;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** The app as a NearbyHost. */
public final class AppHost implements NearbyHost {

    private volatile boolean onboarded;
    private volatile VisibleNearbyController visibleNearby;
    private volatile NearbyDiscovery discovery;
    private volatile ProfileSource profiles;
    private volatile ProfilePhotos photos;
    private volatile Executor storage;
    private boolean attached;
    private volatile Runnable visibilityChanged;
    private final Handler main = new Handler(Looper.getMainLooper());

    /**
     * Records whether this install has finished onboarding.
     *
     * @param done true once onboarding completed (read from disk at launch, or just now)
     */
    public void setOnboarded(final boolean done) {
        onboarded = done;
    }

    /**
     * Attaches Visible Nearby to the onboarded install's store: the stored
     * state is read on the storage executor, then the controller is published
     * on the main thread. Idempotent.
     *
     * @param context any context of the app
     * @param store the install's open store
     * @param storage runs the store's reads and writes off the main thread, in order
     */
    public synchronized void attach(final Context context, final LocalStore store, final Executor storage) {
        if (attached) {
            return;
        }
        attached = true;
        final BluetoothManager bluetooth = context.getSystemService(BluetoothManager.class);
        final BluetoothAdapter adapter = bluetooth == null ? null : bluetooth.getAdapter();
        final DiscoveringAdvertiser radio = new DiscoveringAdvertiser(new AndroidBleAdvertiser(adapter));
        final VisibleNearbyController controller = new VisibleNearbyController(new DiscoveryVisibility(store),
                radio,
                System::currentTimeMillis,
                failure -> main.post(() -> {
                    final VisibleNearbyController current = visibleNearby;
                    if (current != null) {
                        current.radioFailed(failure);
                        changed();
                    }
                }), storage);
        profiles = new ProfileSource.Stored(new ProfileRepository(store));
        this.storage = storage;
        storage.execute(() -> {
            controller.load();
            main.post(() -> {
                visibleNearby = controller;
                changed();
            });
            openPhotos(context);
            final Optional<NearbyIdentity> identity = unlock(context, store);
            if (identity.isPresent()) {
                final ScheduledExecutorService links = Executors.newSingleThreadScheduledExecutor();
                final TaskScheduler retries = (task, delayMillis) -> {
                    final ScheduledFuture<?> pending = links.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
                    return () -> pending.cancel(false);
                };
                final NearbyDiscovery live = new NearbyDiscovery(
                        new AndroidGattLink(context, bluetooth, links, retries, System::currentTimeMillis),
                        new AndroidBleScanner(adapter, System::currentTimeMillis), identity.get(), store, links,
                        storage, System::currentTimeMillis, () -> main.post(this::changed));
                main.post(() -> {
                    discovery = live;
                    radio.attach(live);
                    changed();
                });
            }
        });
    }

    private void changed() {
        final Runnable listener = visibilityChanged;
        if (listener != null) {
            listener.run();
        }
    }

    @Override
    public VisibleNearbyController visibleNearby() {
        return visibleNearby;
    }

    @Override
    public void setOnVisibilityChanged(final Runnable listener) {
        visibilityChanged = listener;
    }

    @Override
    public boolean entryAllowed() {
        return onboarded;
    }

    /**
     * The person's profile, through the install's store; use it on the
     * storage executor only.
     *
     * @return the source, or null before the store is attached
     */
    public ProfileSource profiles() {
        return profiles;
    }

    /**
     * The person's profile photos; use them on the storage executor only.
     *
     * @return the photos, or null until the media store is open (or when it cannot open)
     */
    public ProfilePhotos photos() {
        return photos;
    }

    private void openPhotos(final Context context) {
        try {
            final Optional<MediaObjectStore> media = AppBoot.openMediaStore(context);
            if (media.isPresent()) {
                final ProfilePhotos opened = new ProfilePhotos(new BitmapReencoder(),
                        ProfilePhotos.vaultOf(media.get()), profiles);
                // A crash between a photo's two steps can leave an encrypted
                // object nobody references; clear it before the tab shows.
                opened.sweep();
                photos = opened;
            }
        } catch (final IOException | GeneralSecurityException closed) {
            // No photos this run; the Profile tab says so.
            photos = null;
        }
    }

    @Override
    public void checkProfileShowable(final Consumer<Boolean> answer) {
        final ProfileSource source = profiles;
        final Executor store = storage;
        if (source == null || store == null) {
            answer.accept(Boolean.FALSE);
            return;
        }
        store.execute(() -> {
            // Unreadable reads as not showable: fail closed (SRS-PRO-010).
            final boolean showable = source.load().map(e -> e.profile().isShowable()).orElse(false);
            main.post(() -> answer.accept(showable));
        });
    }

    private static Optional<NearbyIdentity> unlock(final Context context, final LocalStore store) {
        try {
            return AppBoot.nearbyIdentity(context, store);
        } catch (final IOException | StoreException | GeneralSecurityException locked) {
            return Optional.empty();
        }
    }

    @Override
    public FeedState currentState() {
        final NearbyDiscovery live = discovery;
        if (live != null) {
            return live.state();
        }
        return advertisingActive() ? FeedState.notYet() : FeedState.notVisible();
    }

    @Override
    public boolean advertisingActive() {
        final VisibleNearbyController controller = visibleNearby;
        return controller != null && controller.advertising();
    }

    @Override
    public void hideCard(final String peerKey) {
        final NearbyDiscovery live = discovery;
        if (live != null) {
            live.hideCard(peerKey);
        }
    }

    @Override
    public void stopNearby() {
        final VisibleNearbyController controller = visibleNearby;
        if (controller != null) {
            controller.turnOff();
        }
        final NearbyDiscovery live = discovery;
        if (live != null) {
            live.stop();
        }
    }
}
