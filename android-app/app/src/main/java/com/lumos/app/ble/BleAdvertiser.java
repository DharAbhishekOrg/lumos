/*
 * Lumos - android-app/app / The advertising seam: adv_v1 hint or degraded UUID-only.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-AND-005, TRS-PROTO-009, TRS-PROTO-017
 * ADRs: ADR-002
 * Threading: main-safe
 * Notes: Two supported modes per ADR-002: the 18-byte Service-Data hint with
 * its 6-byte scan response, and degraded UUID-only advertising for radios or
 * OEM stacks that refuse service data. Degraded is a SUPPORTED MODE, not a
 * failure: a hintless Lumos peer is discovered by UUID and established in
 * HELLO exactly as every iOS peer is (TRS-PROTO-017).
 */

package com.lumos.app.ble;

/** Starts and stops Lumos advertising. */
public interface BleAdvertiser {

    /** Receives advertising failures. */
    interface Listener {

        /**
         * The platform refused to start or sustain advertising.
         *
         * @param state the typed failure state (TRS-AND-005)
         */
        void onAdvertiseFailed(BlePermissions.AdvertiseFailure state);
    }

    /**
     * Starts hint-mode advertising: service UUID + adv_v1 service data + scan
     * response.
     *
     * @param serviceData the 18-byte adv_v1 hint for the current epoch
     * @param scanResponse the 6-byte scan response
     * @param listener where failures go
     */
    void startHint(byte[] serviceData, byte[] scanResponse, Listener listener);

    /**
     * Starts degraded UUID-only advertising (ADR-002 supported mode).
     *
     * @param listener where failures go
     */
    void startDegraded(Listener listener);

    /** Stops advertising; a no-op when not advertising. */
    void stop();
}
