/*
 * Lumos - android-app/app / Drives the advertiser from Visible Nearby's one evaluation.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-004, SRS-ONB-008, SRS-NBY-001, SRS-NBY-002, SRS-NBY-003
 * ADRs: ADR-002
 * Threading: main (callers hop radio failures here)
 * Notes: The Java twin of the iOS VisibleNearbyController (CS3c), so both apps
 * keep the same rules and both are tested off a device:
 * - the advertiser runs exactly while the person is visible AND the app is on
 *   screen; it stops the moment the window ends, they turn it off, or the app
 *   leaves the screen (background advertising waits for a slice that can also
 *   end a window on time while the app is stopped, SRS-NBY-002);
 * - it is first started by the person's own tap, after the screen has asked
 *   for the Bluetooth permission at that moment (SRS-ONB-004);
 * - if Bluetooth is not allowed, unsupported, or refuses, visibility turns OFF
 *   and the reason is kept for the screen; if Bluetooth is merely off, the
 *   chosen window keeps running and the screen says nobody can see them;
 * - a window that ran out is reported once ("turned itself off").
 *
 * This step advertises in the degraded, UUID-only mode: the service identifier
 * alone, nothing about the person, the same as an iPhone. The rotating hint
 * (adv_v1 with the epoch's EID, AdvertisingController.startHint) joins with
 * NB-1's discovery link, which is the first thing that reads it.
 */

package com.lumos.app.nearby;

import com.lumos.app.ble.BleAdvertiser;
import com.lumos.app.ble.BlePermissions;
import com.lumos.app.onboarding.DiscoveryVisibility;
import com.lumos.app.store.LocalStore;
import com.lumos.app.store.StoreException;

/** Visible Nearby's radio and indicator, together. */
public final class VisibleNearbyController {

    /** Why the radio is not doing what the switch says. */
    public enum RadioProblem {
        /** Bluetooth is switched off; the window keeps running. */
        BLUETOOTH_OFF,
        /** Lumos is not allowed to use Bluetooth; visibility was turned off. */
        NOT_ALLOWED,
        /** This phone cannot advertise; visibility was turned off. */
        UNSUPPORTED,
        /** Bluetooth refused; visibility was turned off. */
        REFUSED
    }

    private final DiscoveryVisibility visibility;
    private final BleAdvertiser advertiser;
    private final LocalStore.Clock clock;
    private final BleAdvertiser.Listener failures;
    private boolean visible;
    private DiscoveryVisibility.Mode mode;
    private long expiresAtMillis;
    private RadioProblem problem;
    private boolean endedOnItsOwn;
    private boolean advertising;
    private boolean foreground = true;

    /**
     * Off until the first {@link #refresh}, which reads the stored state.
     *
     * @param visibility the persisted state
     * @param advertiser the radio
     * @param clock the UTC clock
     * @param failures receives advertiser failures on the radio's thread; the
     *     caller hops them to the main thread and calls {@link #radioFailed}
     */
    public VisibleNearbyController(final DiscoveryVisibility visibility, final BleAdvertiser advertiser,
            final LocalStore.Clock clock, final BleAdvertiser.Listener failures) {
        this.visibility = visibility;
        this.advertiser = advertiser;
        this.clock = clock;
        this.failures = failures;
    }

    /** @return true while visible, from the last evaluation */
    public boolean visible() {
        return visible;
    }

    /** @return the chosen mode while visible, else null */
    public DiscoveryVisibility.Mode mode() {
        return mode;
    }

    /** @return when the window ends (UTC), or 0 when off or until venue exit */
    public long expiresAtMillis() {
        return expiresAtMillis;
    }

    /** @return the radio's problem, or null */
    public RadioProblem problem() {
        return problem;
    }

    /** @return true after a window ran out on its own, until the person acts again */
    public boolean endedOnItsOwn() {
        return endedOnItsOwn;
    }

    /** @return true while the advertiser has been asked to run */
    public boolean advertising() {
        return advertising;
    }

    /** Re-evaluates (an unreadable record reads as off), and starts or stops the radio to match. */
    public void refresh() {
        final boolean was = visible;
        DiscoveryVisibility.Indicator now;
        try {
            now = visibility.indicator(clock.nowUtcMillis());
        } catch (StoreException unreadable) {
            now = null;
        }
        visible = now != null && now.visible();
        mode = visible ? now.mode() : null;
        expiresAtMillis = visible ? now.expiresAtMillis() : 0;
        if (was && !visible && problem == null) {
            endedOnItsOwn = true;
        }
        syncRadio();
    }

    /**
     * The person chose to be visible for this while (after the screen obtained
     * the Bluetooth permission).
     *
     * @param selection what they chose
     * @return false when the state could not be saved; nothing changed then
     */
    public boolean turnOn(final DiscoveryVisibility.Selection selection) {
        problem = null;
        endedOnItsOwn = false;
        boolean saved;
        try {
            visibility.enableAfterExplicitUserRequest(selection, clock.nowUtcMillis());
            saved = true;
        } catch (StoreException notSaved) {
            saved = false;
        }
        refresh();
        return saved;
    }

    /** Off, at once. */
    public void turnOff() {
        try {
            visibility.disable(clock.nowUtcMillis());
        } catch (StoreException notSaved) {
            // The radio still stops below; the record reads off at its window's end.
        }
        refresh();
        // Turned off by the person (or a radio failure), not by the clock:
        // set after the evaluation, which would otherwise read it as expiry.
        endedOnItsOwn = false;
    }

    /**
     * The app came on screen or left it.
     *
     * @param active true while on screen
     */
    public void setForeground(final boolean active) {
        foreground = active;
        refresh();
    }

    /**
     * The person refused the Bluetooth permission when asked.
     */
    public void permissionRefused() {
        problem = RadioProblem.NOT_ALLOWED;
        turnOff();
    }

    /**
     * An advertiser failure, already back on the main thread.
     *
     * @param failure the typed failure
     */
    public void radioFailed(final BlePermissions.AdvertiseFailure failure) {
        switch (failure) {
            case ALREADY_STARTED:
                return;
            case ADAPTER_UNAVAILABLE:
                problem = RadioProblem.BLUETOOTH_OFF;
                return;
            case PERMISSION_MISSING:
                problem = RadioProblem.NOT_ALLOWED;
                break;
            case FEATURE_UNSUPPORTED:
                problem = RadioProblem.UNSUPPORTED;
                break;
            case DATA_TOO_LARGE:
            case TOO_MANY_ADVERTISERS:
            case INTERNAL_ERROR:
            case OEM_UNDOCUMENTED:
            default:
                problem = RadioProblem.REFUSED;
                break;
        }
        turnOff();
    }

    private void syncRadio() {
        final boolean want = visible && foreground;
        if (want && !advertising) {
            advertising = true;
            advertiser.startDegraded(failures);
        } else if (!want && advertising) {
            advertising = false;
            advertiser.stop();
        }
    }
}
