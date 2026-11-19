/*
 * Lumos - android-app/app / One radio sighting, as the scan seam reports it.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-PROTO-009, TRS-PROTO-017, SRS-NBY-005
 * ADRs: ADR-002
 * Threading: any
 * Notes: The value object that crosses the BleScanner seam, whether the source
 * is the Android radio or the synthetic harness. serviceData may be null: every
 * iOS peer, and any Android peer in degraded mode, advertises the service UUID
 * alone (ADR-002), and such a sighting is a first-class candidate - a hintless
 * peer must behave identically in discovery (TRS-PROTO-017). linkToken is the
 * transport's own opaque handle for connecting (on Android, the device
 * address); it is radio plumbing, never identity, and it is NEVER shown,
 * logged or persisted.
 */

package com.lumos.app.ble;

/** One advertisement sighting crossing the scanner seam. */
public final class ScanSighting {

    private final String linkToken;
    private final byte[] serviceData;
    private final byte[] scanResponse;
    private final int rssiDbm;
    private final long atMillis;

    /**
     * @param linkToken the transport's opaque connect handle; never identity
     * @param serviceData the adv_v1 service data, or null for a UUID-only peer
     * @param scanResponse the scan response bytes, or null when absent
     * @param rssiDbm received signal strength
     * @param atMillis the sighting time on the caller's clock
     */
    public ScanSighting(final String linkToken, final byte[] serviceData,
            final byte[] scanResponse, final int rssiDbm, final long atMillis) {
        this.linkToken = linkToken;
        this.serviceData = serviceData == null ? null : serviceData.clone();
        this.scanResponse = scanResponse == null ? null : scanResponse.clone();
        this.rssiDbm = rssiDbm;
        this.atMillis = atMillis;
    }

    /** @return the transport's opaque connect handle */
    public String linkToken() {
        return linkToken;
    }

    /** @return a copy of the adv_v1 service data, or null for a hintless peer */
    public byte[] serviceData() {
        return serviceData == null ? null : serviceData.clone();
    }

    /** @return a copy of the scan response, or null when absent */
    public byte[] scanResponse() {
        return scanResponse == null ? null : scanResponse.clone();
    }

    /** @return received signal strength in dBm */
    public int rssiDbm() {
        return rssiDbm;
    }

    /** @return when the sighting happened, caller's clock */
    public long atMillis() {
        return atMillis;
    }
}
