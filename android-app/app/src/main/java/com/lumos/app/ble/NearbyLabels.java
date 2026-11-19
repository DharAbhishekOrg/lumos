/*
 * Lumos - android-app/app / Proximity bands as words, never as distances.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-PRO-013, SRS-NBY-008
 * ADRs: ADR-002
 * Threading: any
 * Notes: SRS-PRO-013 names the labels - Very close, Nearby, Around the venue
 * - and forbids the alternative: indoor RSSI cannot honestly say "3 metres
 * away", so the application never says it anywhere. The register thresholds
 * (RSSI_BANDS_DBM, calibrated by G-01) pick the band; this class is the ONLY
 * mapping from a band to a user-visible word, so no surface can invent a
 * fourth label or a numeric distance (SRS-NBY-008 keeps the raw dBm out of
 * the UI entirely). OnboardingCopyTest holds the strings themselves to the
 * no-precise-distance rule.
 */

package com.lumos.app.ble;

import com.lumos.app.R;

/** The one band-to-label mapping (SRS-PRO-013). */
public final class NearbyLabels {

    private NearbyLabels() {
        throw new AssertionError("no instances");
    }

    /**
     * The string resource for a proximity band.
     *
     * @param band the register band
     * @return the label resource id
     */
    public static int labelFor(final DiscoveryTriage.RssiBand band) {
        switch (band) {
            case VERY_CLOSE:
                return R.string.proximity_very_close;
            case NEARBY:
                return R.string.proximity_nearby;
            case FAR:
            default:
                return R.string.proximity_around_venue;
        }
    }
}
