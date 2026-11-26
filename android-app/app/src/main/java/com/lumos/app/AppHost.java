/*
 * Lumos - android-app/app / The running app as the Nearby and home surfaces see it.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-001, SRS-NBY-010
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
 * The live discovery link (HELLO over GATT) is not wired yet, so the feed is
 * NOT_YET: the screen says finding people arrives in a coming update rather
 * than claiming to scan. There are no cards to hide.
 */

package com.lumos.app;

import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.lumos.app.ble.AndroidBleAdvertiser;
import com.lumos.app.nearby.FeedState;
import com.lumos.app.nearby.NearbyHost;
import com.lumos.app.nearby.VisibleNearbyController;
import com.lumos.app.onboarding.DiscoveryVisibility;
import com.lumos.app.store.LocalStore;

/** The app as a NearbyHost. */
public final class AppHost implements NearbyHost {

    private volatile boolean onboarded;
    private volatile VisibleNearbyController visibleNearby;
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
     * Attaches Visible Nearby to the onboarded install's store. Idempotent.
     *
     * @param context any context of the app
     * @param store the install's open store
     */
    public synchronized void attach(final Context context, final LocalStore store) {
        if (visibleNearby != null) {
            return;
        }
        final BluetoothManager bluetooth = context.getSystemService(BluetoothManager.class);
        visibleNearby = new VisibleNearbyController(new DiscoveryVisibility(store),
                new AndroidBleAdvertiser(bluetooth == null ? null : bluetooth.getAdapter()),
                System::currentTimeMillis,
                failure -> main.post(() -> {
                    final VisibleNearbyController controller = visibleNearby;
                    if (controller != null) {
                        controller.radioFailed(failure);
                        final Runnable listener = visibilityChanged;
                        if (listener != null) {
                            listener.run();
                        }
                    }
                }));
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

    @Override
    public FeedState currentState() {
        return FeedState.notYet();
    }

    @Override
    public boolean advertisingActive() {
        final VisibleNearbyController controller = visibleNearby;
        return controller != null && controller.advertising();
    }

    @Override
    public void hideCard(final String peerKey) {
        // No cards exist until NB-1; nothing to hide.
    }

    @Override
    public void stopNearby() {
        final VisibleNearbyController controller = visibleNearby;
        if (controller != null) {
            controller.turnOff();
        }
    }
}
