/*
 * Lumos - android-app/app / Bounded scan duty cycling from the register windows.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-NBY-005, SRS-NFR-BAT-001, SRS-NFR-BAT-004, TRS-AND-005
 * ADRs: ADR-002
 * Threading: serial-executor
 * Notes: Continuous scanning is a battery requirement violation, not a
 * performance tweak: the register's SCAN_WINDOW_FG_MS (4 s on / 6 s off,
 * foreground) and SCAN_WINDOW_VENUE_MS (6 s on / 4 s off) are the calibrated
 * windows, and the duty percentage each implies is checked against the
 * register's own duty bounds at construction - a signed-config override that
 * violated its bounds would be refused here even if schema validation missed
 * it. Timing runs on the injected scheduler, so the whole cycle is scripted
 * in JVM tests; a scan failure stops the cycle and surfaces the typed state
 * (TRS-AND-005) instead of hot-looping against a refusing stack.
 */

package com.lumos.app.ble;

import com.lumos.protocol.LumosConstants;

/** Runs the on/off scan cycle for a discovery mode. */
public final class ScanDutyCycle {

    /** The two calibrated scan profiles. */
    public enum Mode {
        /** Everyday foreground discovery: SCAN_WINDOW_FG_MS. */
        FOREGROUND(LumosConstants.SCAN_WINDOW_FG_MS_ON, LumosConstants.SCAN_WINDOW_FG_MS_OFF,
                LumosConstants.SCAN_WINDOW_FG_MS_DUTY_MIN_PCT, LumosConstants.SCAN_WINDOW_FG_MS_DUTY_MAX_PCT),
        /** Venue mode: SCAN_WINDOW_VENUE_MS, denser but still bounded. */
        VENUE(LumosConstants.SCAN_WINDOW_VENUE_MS_ON, LumosConstants.SCAN_WINDOW_VENUE_MS_OFF,
                0, LumosConstants.SCAN_WINDOW_VENUE_MS_DUTY_MAX_PCT);

        private final int onMillis;
        private final int offMillis;
        private final int dutyMinPct;
        private final int dutyMaxPct;

        Mode(final int onMillis, final int offMillis, final int dutyMinPct, final int dutyMaxPct) {
            this.onMillis = onMillis;
            this.offMillis = offMillis;
            this.dutyMinPct = dutyMinPct;
            this.dutyMaxPct = dutyMaxPct;
        }

        /** @return the scan-on window in milliseconds */
        public int onMillis() {
            return onMillis;
        }

        /** @return the scan-off window in milliseconds */
        public int offMillis() {
            return offMillis;
        }

        /** @return the duty percentage this mode's windows imply */
        public int dutyPct() {
            // lumoslint:allow-literal - the percent scale, not a register value.
            return 100 * onMillis / (onMillis + offMillis);
        }

        /** @return the register's lower duty bound; zero when unbounded below */
        public int dutyMinPct() {
            return dutyMinPct;
        }

        /** @return the register's upper duty bound */
        public int dutyMaxPct() {
            return dutyMaxPct;
        }
    }

    private final BleScanner scanner;
    private final TaskScheduler scheduler;
    private final Mode mode;

    private BleScanner.Listener listener;
    private TaskScheduler.Scheduled phaseHandle;
    private boolean running;
    private boolean scanning;

    /**
     * @param scanner the scanning seam
     * @param scheduler the deferred-execution seam the windows run on
     * @param mode the calibrated profile
     * @throws IllegalStateException when the mode's windows violate the
     *     register's own duty bounds - fail at construction, not in the field
     */
    public ScanDutyCycle(final BleScanner scanner, final TaskScheduler scheduler, final Mode mode) {
        final int duty = mode.dutyPct();
        if (duty < mode.dutyMinPct() || duty > mode.dutyMaxPct()) {
            throw new IllegalStateException(
                    "scan windows imply a duty cycle outside the register bounds");
        }
        this.scanner = scanner;
        this.scheduler = scheduler;
        this.mode = mode;
    }

    /** @return the profile this cycle runs */
    public Mode mode() {
        return mode;
    }

    /** @return whether the cycle is currently in a scan-on window */
    public boolean scanning() {
        return scanning;
    }

    /**
     * Starts cycling. Idempotent while running.
     *
     * @param sightings where sightings and failures go; a failure stops the
     *     cycle before it is delivered
     */
    public void start(final BleScanner.Listener sightings) {
        if (running) {
            return;
        }
        running = true;
        listener = new BleScanner.Listener() {
            @Override
            public void onSighting(final ScanSighting sighting) {
                sightings.onSighting(sighting);
            }

            @Override
            public void onScanFailed(final BlePermissions.ScanFailure state) {
                stop();
                sightings.onScanFailed(state);
            }
        };
        beginOnWindow();
    }

    /** Stops cycling and the radio scan; idempotent. */
    public void stop() {
        running = false;
        if (phaseHandle != null) {
            phaseHandle.cancel();
            phaseHandle = null;
        }
        if (scanning) {
            scanning = false;
            scanner.stop();
        }
    }

    private void beginOnWindow() {
        if (!running) {
            return;
        }
        scanning = true;
        scanner.start(listener);
        phaseHandle = scheduler.schedule(this::beginOffWindow, mode.onMillis());
    }

    private void beginOffWindow() {
        if (!running) {
            return;
        }
        scanning = false;
        scanner.stop();
        phaseHandle = scheduler.schedule(this::beginOnWindow, mode.offMillis());
    }
}
