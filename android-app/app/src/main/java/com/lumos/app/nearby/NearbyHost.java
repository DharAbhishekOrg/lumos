/*
 * Lumos - android-app/app / What the Nearby screen needs from the app.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-001, SRS-NBY-003, SRS-NBY-012, SRS-NBY-017
 * ADRs: ADR-002
 * Threading: main
 * Notes: The seam between the rendering layer and the running app. The
 * composition root - the slice that assembles store, radio and controllers
 * at process start - implements this against the real stack; until it
 * exists, the ONLY implementor is the instrumented state demo, and the
 * activity refuses to open without one, which is also how the AT-001 rule
 * holds: entryAllowed() must consult the onboarding machine's
 * isReachable(NEARBY_DISCOVERY), so a fresh install shows nothing of the
 * product before consent (SRS-ONB-001). Kept as an interface rather than
 * static wiring into singletons so every decision stays testable off the
 * device, which is the whole architecture of this app.
 */

package com.lumos.app.nearby;

/** The running app, as the Nearby screen sees it. */
public interface NearbyHost {

    /**
     * Whether this surface may open at all - the AT-001 gate. Implementors
     * answer from the onboarding machine, never from a flag of their own.
     *
     * @return true when onboarding has made NEARBY_DISCOVERY reachable
     */
    boolean entryAllowed();

    /**
     * What the feed shows right now.
     *
     * @return the controller's current state
     */
    FeedState currentState();

    /**
     * Whether advertising is actually active, for the SRS-NBY-003 indicator.
     * The indicator reflects the radio, not the preference: a user who is
     * visible-on-paper but not currently advertising is shown nothing.
     *
     * @return true while the advertisement is on the air
     */
    boolean advertisingActive();

    /**
     * Hides one card, silently (SRS-NBY-012).
     *
     * @param peerKey the card's authenticated identifier
     */
    void hideCard(String peerKey);

    /**
     * The one-action stop (SRS-NBY-017): advertising off, scanning off,
     * presence cleared. The screen offers nothing softer.
     */
    void stopNearby();

    /**
     * Visible Nearby's controller, when this host has one.
     *
     * @return the controller, or null (the state demo, and before onboarding)
     */
    default VisibleNearbyController visibleNearby() {
        return null;
    }

    /**
     * Asks to be told when Visible Nearby changes off the main flow (a radio failure).
     *
     * @param listener run on the main thread; null to stop
     */
    default void setOnVisibilityChanged(final Runnable listener) {
        // Hosts without Visible Nearby never change.
    }
}
