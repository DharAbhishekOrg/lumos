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
 * The live discovery link (HELLO over GATT, NB-1) is not wired yet, so the
 * feed is NOT_YET: the screen says finding people arrives in a coming update
 * and that nothing is being sent or looked for, rather than claiming to scan.
 * Nothing advertises, so the visibility indicator never shows; there are no
 * cards to hide; "stop" has nothing running to stop.
 */

package com.lumos.app;

import com.lumos.app.nearby.FeedState;
import com.lumos.app.nearby.NearbyHost;

/** The app as a NearbyHost. */
public final class AppHost implements NearbyHost {

    private volatile boolean onboarded;

    /**
     * Records whether this install has finished onboarding.
     *
     * @param done true once onboarding completed (read from disk at launch, or just now)
     */
    public void setOnboarded(final boolean done) {
        onboarded = done;
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
        return false;
    }

    @Override
    public void hideCard(final String peerKey) {
        // No cards exist until NB-1; nothing to hide.
    }

    @Override
    public void stopNearby() {
        // Nothing runs until NB-1; nothing to stop.
    }
}
