/*
 * Lumos - android-app/app / Visible Nearby's radio rules, with a scripted advertiser.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-004, SRS-ONB-008, SRS-NBY-001, SRS-NBY-002, SRS-NBY-003
 * ADRs: ADR-002
 * Threading: any
 * Notes: The Java half of the iOS VisibleNearbyControllerTests, over a real
 * SQLite store, including the case the iOS tests caught: a manual "Turn off"
 * must not be reported as "turned itself off".
 */

package com.lumos.app.nearby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.Fixtures;
import com.lumos.app.ble.BleAdvertiser;
import com.lumos.app.ble.BlePermissions;
import com.lumos.app.onboarding.DiscoveryVisibility;
import com.lumos.protocol.LumosConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Visible Nearby's controller. */
final class VisibleNearbyControllerTest {

    private static final long NOW = 1_792_917_000_000L;
    private static final long HOUR = 1000L * LumosConstants.VISIBLE_NEARBY_DURATION_SEC_DEFAULT;

    /** A radio that records what it was told. */
    private static final class ScriptedAdvertiser implements BleAdvertiser {
        boolean running;
        int starts;
        boolean hint;

        @Override
        public void startHint(final byte[] serviceData, final byte[] scanResponse, final Listener listener) {
            hint = true;
            running = true;
            starts++;
        }

        @Override
        public void startDegraded(final Listener listener) {
            running = true;
            starts++;
        }

        @Override
        public void stop() {
            running = false;
        }
    }

    private final Fixtures.TestClock clock = new Fixtures.TestClock(NOW);
    private final ScriptedAdvertiser radio = new ScriptedAdvertiser();
    private Fixtures.Stack stack;
    private VisibleNearbyController controller;

    @BeforeEach
    void open() throws Exception {
        stack = Fixtures.openInMemory(clock);
        controller = new VisibleNearbyController(new DiscoveryVisibility(stack.store()), radio, clock, f -> { });
    }

    @AfterEach
    void close() throws Exception {
        stack.close();
    }

    @Test
    void theRadioStaysSilentUntilThePersonTurnsItOn() {
        controller.refresh();
        controller.setForeground(true);
        assertEquals(0, radio.starts, "no launch, refresh or foregrounding starts the radio");
        assertTrue(controller.turnOn(DiscoveryVisibility.Selection.defaultSession()));
        assertTrue(radio.running, "the person's own tap starts it");
        assertFalse(radio.hint, "UUID only in this step: nothing about the person");
        assertTrue(controller.visible());
        assertEquals(NOW + HOUR, controller.expiresAtMillis());
    }

    @Test
    void theRadioStopsTheMomentTheWindowEndsAndSaysSoOnce() {
        controller.turnOn(DiscoveryVisibility.Selection.defaultSession());
        clock.advance(HOUR - 1);
        controller.refresh();
        assertTrue(radio.running);
        clock.advance(1);
        controller.refresh();
        assertFalse(radio.running, "off on time, by evaluation");
        assertFalse(controller.visible());
        assertTrue(controller.endedOnItsOwn(), "the screen can say it turned itself off");
        controller.turnOn(DiscoveryVisibility.Selection.defaultSession());
        assertFalse(controller.endedOnItsOwn());
    }

    @Test
    void turningOffAndLeavingTheScreenBothStopTheRadio() {
        controller.turnOn(DiscoveryVisibility.Selection.defaultSession());
        controller.setForeground(false);
        assertFalse(radio.running, "no advertising while the app is off screen");
        assertTrue(controller.visible(), "the chosen window keeps running");
        controller.setForeground(true);
        assertTrue(radio.running, "back on screen inside the window: on again");
        controller.turnOff();
        assertFalse(radio.running);
        assertFalse(controller.endedOnItsOwn(), "turned off by the person, not by itself");
    }

    @Test
    void aRefusedPermissionOrAnUnusableRadioTurnsVisibilityOffAndSaysWhy() {
        controller.turnOn(DiscoveryVisibility.Selection.defaultSession());
        controller.permissionRefused();
        assertEquals(VisibleNearbyController.RadioProblem.NOT_ALLOWED, controller.problem());
        assertFalse(controller.visible(), "never shown as visible when the radio cannot be");
        assertFalse(radio.running);
        assertFalse(controller.endedOnItsOwn());
        controller.turnOn(DiscoveryVisibility.Selection.defaultSession());
        assertNull(controller.problem(), "trying again clears the old reason");
        for (final BlePermissions.AdvertiseFailure failure : new BlePermissions.AdvertiseFailure[] {
            BlePermissions.AdvertiseFailure.PERMISSION_MISSING, BlePermissions.AdvertiseFailure.FEATURE_UNSUPPORTED,
            BlePermissions.AdvertiseFailure.INTERNAL_ERROR, BlePermissions.AdvertiseFailure.OEM_UNDOCUMENTED}) {
            controller.turnOn(DiscoveryVisibility.Selection.defaultSession());
            controller.radioFailed(failure);
            assertFalse(controller.visible(), failure + " turns it off");
        }
    }

    @Test
    void bluetoothSwitchedOffKeepsTheWindowAndSaysNobodyCanSeeYou() {
        controller.turnOn(DiscoveryVisibility.Selection.defaultSession());
        controller.radioFailed(BlePermissions.AdvertiseFailure.ADAPTER_UNAVAILABLE);
        assertEquals(VisibleNearbyController.RadioProblem.BLUETOOTH_OFF, controller.problem());
        assertTrue(controller.visible(), "the person's choice stands; the screen says why it is silent");
        controller.radioFailed(BlePermissions.AdvertiseFailure.ALREADY_STARTED);
        assertTrue(controller.visible(), "already running is not a failure");
    }
}
