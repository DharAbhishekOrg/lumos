/*
 * Lumos - android-app/app / A discovery radio with a server side and a lifecycle.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-TRN-002, SRS-NBY-005
 * ADRs: ADR-013
 * Threading: serial-executor (the engine's)
 * Notes: What NearbyDiscovery needs from the radio beyond the engine's
 * connect/send/disconnect: bind the engine it reports to, open the GATT
 * server so peers can connect, and close everything. AndroidGattLink is the
 * device one; tests use an in-memory air.
 */

package com.lumos.app.ble;

/** DiscoveryEngine.Radio plus binding and lifecycle. */
public interface LinkRadio extends DiscoveryEngine.Radio {

    /**
     * @param discovery the engine the links report to
     */
    void bind(DiscoveryEngine discovery);

    /**
     * Opens the server side so peers can connect here.
     *
     * @return false when the radio cannot (no adapter, no permission)
     */
    boolean openServer();

    /** Closes the server side and every link. */
    void close();
}
