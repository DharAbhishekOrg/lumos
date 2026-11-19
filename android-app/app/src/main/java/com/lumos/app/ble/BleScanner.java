/*
 * Lumos - android-app/app / The scanning seam the discovery pipeline runs on.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-AND-005, TRS-ENV-001, SRS-NBY-005
 * ADRs: ADR-002
 * Threading: main-safe
 * Notes: The seam exists so everything above the radio - duty cycling, cheap
 * discard, triage, the hundred-peer load shape of TRS-ENV-001 - executes in JVM
 * tests against a synthetic implementation. AndroidBleScanner is the thin
 * radio-backed implementation, and thin is the design: logic in the adapter
 * would be logic the emulator can never execute (the radio half is G-01
 * owner evidence).
 */

package com.lumos.app.ble;

/** Starts and stops BLE scanning, reporting sightings and failures. */
public interface BleScanner {

    /** Receives what the scanner sees. */
    interface Listener {

        /**
         * One advertisement sighting; may arrive at any rate (TRS-ENV-001).
         *
         * @param sighting the sighting
         */
        void onSighting(ScanSighting sighting);

        /**
         * The platform refused or stopped the scan.
         *
         * @param state the typed failure state (TRS-AND-005)
         */
        void onScanFailed(BlePermissions.ScanFailure state);
    }

    /**
     * Starts scanning for Lumos advertisements.
     *
     * @param listener where sightings and failures go
     */
    void start(Listener listener);

    /** Stops scanning; a no-op when not scanning. */
    void stop();
}
