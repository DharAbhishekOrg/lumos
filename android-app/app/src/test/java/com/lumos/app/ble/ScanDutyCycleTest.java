/*
 * Lumos - android-app/app / The register duty cycle, executed on virtual time.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-NBY-005, SRS-NFR-BAT-001, SRS-NFR-BAT-004, TRS-AND-005, TRS-TST-001
 * ADRs: ADR-002
 * Threading: test-only
 * Notes: Battery discipline as an executed property: the cycle alternates
 * exactly on the register windows, both calibrated profiles sit inside their
 * own duty bounds, and a refusing radio stops the cycle with its typed state
 * instead of hot-looping - a day of cycling costs one advanceTo call.
 */

package com.lumos.app.ble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ScanDutyCycle, held to the register windows. */
final class ScanDutyCycleTest {

    /** Records start/stop transitions and can inject failures. */
    private static final class FakeScanner implements BleScanner {
        private final List<String> transitions = new ArrayList<>();
        private Listener listener;

        @Override
        public void start(final Listener l) {
            transitions.add("start");
            listener = l;
        }

        @Override
        public void stop() {
            transitions.add("stop");
        }
    }

    private final FakeScanner scanner = new FakeScanner();
    private final FakeScheduler scheduler = new FakeScheduler();

    private static final class SilentListener implements BleScanner.Listener {
        private BlePermissions.ScanFailure failure;

        @Override
        public void onSighting(final ScanSighting sighting) {
            // Not under test here.
        }

        @Override
        public void onScanFailed(final BlePermissions.ScanFailure state) {
            failure = state;
        }
    }

    @Test
    void bothProfilesSitInsideTheirOwnDutyBounds() {
        for (final ScanDutyCycle.Mode mode : ScanDutyCycle.Mode.values()) {
            final int duty = mode.dutyPct();
            assertTrue(duty >= mode.dutyMinPct() && duty <= mode.dutyMaxPct(),
                    mode + " windows imply " + duty + "% duty, outside its register bounds");
        }
    }

    @Test
    void theCycleAlternatesOnTheRegisterWindows() {
        final ScanDutyCycle cycle =
                new ScanDutyCycle(scanner, scheduler, ScanDutyCycle.Mode.FOREGROUND);
        cycle.start(new SilentListener());
        assertTrue(cycle.scanning(), "the cycle opens scanning");
        assertEquals(List.of("start"), scanner.transitions);

        scheduler.advanceBy(ScanDutyCycle.Mode.FOREGROUND.onMillis());
        assertFalse(cycle.scanning(), "the on window ends on time");
        assertEquals(List.of("start", "stop"), scanner.transitions);

        scheduler.advanceBy(ScanDutyCycle.Mode.FOREGROUND.offMillis());
        assertTrue(cycle.scanning(), "the off window ends on time");
        assertEquals(List.of("start", "stop", "start"), scanner.transitions);
    }

    @Test
    void sustainedCyclingMakesExactlyTheBudgetedWindows() {
        // SRS-NFR-BAT-001: the window count is the battery spend.
        final ScanDutyCycle.Mode mode = ScanDutyCycle.Mode.FOREGROUND;
        final ScanDutyCycle cycle = new ScanDutyCycle(scanner, scheduler, mode);
        cycle.start(new SilentListener());

        final int periods = 6;
        scheduler.advanceBy((long) periods * (mode.onMillis() + mode.offMillis()));
        final long starts = scanner.transitions.stream().filter("start"::equals).count();
        assertEquals(periods + 1, starts, "one scan window per period, never more");
        cycle.stop();
    }

    @Test
    void aScanFailureStopsTheCycleAndSurfacesTheTypedState() {
        final ScanDutyCycle cycle =
                new ScanDutyCycle(scanner, scheduler, ScanDutyCycle.Mode.VENUE);
        final SilentListener listener = new SilentListener();
        cycle.start(listener);
        scanner.listener.onScanFailed(BlePermissions.ScanFailure.SCANNING_TOO_FREQUENTLY);

        assertEquals(BlePermissions.ScanFailure.SCANNING_TOO_FREQUENTLY, listener.failure);
        assertFalse(cycle.scanning(), "a refusing radio must not be hot-looped");
        final int transitions = scanner.transitions.size();
        scheduler.advanceBy(45_000);
        assertEquals(transitions, scanner.transitions.size(), "the cycle stays stopped");
    }

    @Test
    void stopIsIdempotentAndCancelsTheNextPhase() {
        final ScanDutyCycle cycle =
                new ScanDutyCycle(scanner, scheduler, ScanDutyCycle.Mode.FOREGROUND);
        cycle.start(new SilentListener());
        cycle.stop();
        cycle.stop();
        scheduler.advanceBy(45_000);
        assertEquals(List.of("start", "stop"), scanner.transitions,
                "no phase may fire after stop");
    }
}
