/*
 * Lumos - android-app/app / Visible Nearby's radio: advertising and discovery start and stop together.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-NBY-003, SRS-NBY-005, SRS-NBY-017
 * ADRs: ADR-002, ADR-013
 * Threading: main (VisibleNearbyController drives it there)
 * Notes: The owner's rule (2026-11-28): a person looks for and connects to
 * others only while they can be seen themselves and the app is open.
 * VisibleNearbyController already runs the advertiser exactly then - visible
 * AND in the foreground - so discovery rides on the same calls: whatever
 * starts advertising starts discovery, whatever stops it (off, the window
 * ending, leaving the app, a radio failure, the one-action stop) stops
 * discovery too. Discovery arrives a moment after the advertiser (the
 * identity is unlocked off the main thread); if the radio is already on by
 * then, discovery starts as it arrives.
 */

package com.lumos.app.nearby;

import com.lumos.app.ble.BleAdvertiser;

/** A BleAdvertiser that also runs NearbyDiscovery. */
public final class DiscoveringAdvertiser implements BleAdvertiser {

    private final BleAdvertiser advertiser;
    private NearbyDiscovery discovery;
    private boolean on;

    /**
     * @param advertiser the platform advertiser
     */
    public DiscoveringAdvertiser(final BleAdvertiser advertiser) {
        this.advertiser = advertiser;
    }

    /**
     * Attaches discovery once it exists; starts it if the radio is already on.
     *
     * @param ready the app's discovery
     */
    public void attach(final NearbyDiscovery ready) {
        this.discovery = ready;
        if (on) {
            ready.start();
        }
    }

    @Override
    public void startHint(final byte[] serviceData, final byte[] scanResponse, final Listener listener) {
        advertiser.startHint(serviceData, scanResponse, listener);
        started();
    }

    @Override
    public void startDegraded(final Listener listener) {
        advertiser.startDegraded(listener);
        started();
    }

    @Override
    public void stop() {
        on = false;
        if (discovery != null) {
            discovery.stop();
        }
        advertiser.stop();
    }

    private void started() {
        on = true;
        if (discovery != null) {
            discovery.start();
        }
    }
}
