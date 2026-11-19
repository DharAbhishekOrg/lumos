/*
 * Lumos - android-app/app / The privacy-safe discovery-success metric.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-ENV-003, SRS-NFR-SEC-013, SRS-DAT-002
 * ADRs: ADR-002
 * Threading: serial-executor
 * Notes: TRS-ENV-003's second half: field false-negative rates have to be
 * measurable, and the measurement must not become a surveillance record. The
 * payload is a closed grammar - a version line, two small counters, a peer
 * BUCKET and a boolean - and nothing else can get in because the only writer
 * is this formatter. No peer identifier, no EID, no RSSI, no venue, no clock
 * reading: the bucket deliberately destroys the venue-size fingerprint a raw
 * count would carry. The record lands in the sealed diagnostic_event table,
 * so even the bucket is ciphertext at rest; today it is read by nobody -
 * upload, if it ever happens, is a later work package behind its own consent
 * gate (SRS-DAT-004), and this grammar is what bounds what COULD leave.
 */

package com.lumos.app.ble;

import com.lumos.app.store.LocalStore;
import com.lumos.app.store.RecordKind;
import com.lumos.app.store.StoreException;
import java.nio.charset.StandardCharsets;

/** Writes one privacy-safe record per Nearby viewing session. */
public final class DiscoverySuccessMetric {

    /** Payload format version line. */
    public static final String VERSION_LINE = "lumos-discovery-metric/1";

    private final LocalStore store;

    /**
     * Binds the metric to its store.
     *
     * @param store the opened local store
     */
    public DiscoverySuccessMetric(final LocalStore store) {
        this.store = store;
    }

    /**
     * The coarse peer-count bucket - coarse ON PURPOSE, see the header.
     *
     * @param peersPresent how many authenticated peers the session showed
     * @return the bucket token
     */
    public static String bucket(final int peersPresent) {
        if (peersPresent <= 0) {
            return "none";
        }
        if (peersPresent == 1) {
            return "one";
        }
        if (peersPresent <= 5) {
            return "few";
        }
        if (peersPresent <= 20) {
            return "some";
        }
        return "many";
    }

    /**
     * Records one finished Nearby viewing session.
     *
     * @param cyclesRun scan cycles completed inside the TRS-ENV-003 gate
     * @param maxStepReached the highest rescan-ladder step the session hit
     * @param peersPresent authenticated peers present when the view closed
     * @return the row identifier written
     * @throws StoreException on low storage or engine failure
     */
    public String record(final int cyclesRun, final int maxStepReached, final int peersPresent)
            throws StoreException {
        final String payload = VERSION_LINE + "\n"
                + "cycles=" + Math.max(0, cyclesRun) + "\n"
                + "max_step=" + Math.max(0, maxStepReached) + "\n"
                + "peers=" + bucket(peersPresent) + "\n"
                + "success=" + (peersPresent > 0) + "\n";
        return store.put(RecordKind.DIAGNOSTIC_EVENT, null,
                payload.getBytes(StandardCharsets.UTF_8), null);
    }
}
