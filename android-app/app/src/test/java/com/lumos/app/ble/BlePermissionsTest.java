/*
 * Lumos - android-app/app / The TRS-AND-005 matrix and failure mapping, executed.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-AND-005, TRS-TST-001
 * ADRs: ADR-002
 * Threading: test-only
 * Notes: The permission matrix is a privacy decision as much as an API one:
 * 31+ must never ask for location, pre-31 cannot scan without it, and the two
 * sets must never blur. The failure mapping's job is the OEM long tail -
 * vendor stacks return codes outside the documented set, and every one of
 * them must land on a typed state instead of a crash or a mistaken success.
 */

package com.lumos.app.ble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.le.ScanCallback;
import android.os.Build;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * BlePermissions, held to the version matrix.
 *
 * <p>InlinedApi suppressed for the same reason as the class under test: the
 * newer-SDK names are compared as plain constant values on a JVM.
 */
@SuppressLint("InlinedApi")
final class BlePermissionsTest {

    @Test
    void modernDevicesGetTheScopedTrioAndNeverLocation() {
        for (final int sdk : new int[] {Build.VERSION_CODES.S, 34, 36}) {
            final List<String> required = BlePermissions.requiredRuntimePermissions(sdk);
            assertEquals(List.of(Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT), required);
            assertFalse(required.contains(Manifest.permission.ACCESS_FINE_LOCATION),
                    "neverForLocation means never asking for location on " + sdk);
        }
    }

    @Test
    void legacyDevicesGetFineLocationBecauseScanResultsNeedIt() {
        for (final int sdk : new int[] {26, 28, 30}) {
            assertEquals(List.of(Manifest.permission.ACCESS_FINE_LOCATION),
                    BlePermissions.requiredRuntimePermissions(sdk),
                    "pre-31 Android withholds scan results without fine location");
        }
    }

    @Test
    void theNotificationGrantStartsAtThirtyThree() {
        assertFalse(BlePermissions.needsNotificationPermission(32));
        assertTrue(BlePermissions.needsNotificationPermission(Build.VERSION_CODES.TIRAMISU));
    }

    @Test
    void everyDocumentedScanFailureMapsTyped() {
        assertEquals(BlePermissions.ScanFailure.ALREADY_STARTED,
                BlePermissions.scanFailure(ScanCallback.SCAN_FAILED_ALREADY_STARTED));
        assertEquals(BlePermissions.ScanFailure.APPLICATION_REGISTRATION_FAILED,
                BlePermissions.scanFailure(ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED));
        assertEquals(BlePermissions.ScanFailure.FEATURE_UNSUPPORTED,
                BlePermissions.scanFailure(ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED));
        assertEquals(BlePermissions.ScanFailure.INTERNAL_ERROR,
                BlePermissions.scanFailure(ScanCallback.SCAN_FAILED_INTERNAL_ERROR));
        assertEquals(BlePermissions.ScanFailure.OUT_OF_HARDWARE_RESOURCES,
                BlePermissions.scanFailure(ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES));
        assertEquals(BlePermissions.ScanFailure.SCANNING_TOO_FREQUENTLY,
                BlePermissions.scanFailure(ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY));
    }

    @Test
    void theOemLongTailLandsTypedInsteadOfCrashing() {
        // Codes no documentation names, straight from vendor stacks.
        for (final int oemCode : new int[] {7, 42, 133, -1, 255}) {
            assertEquals(BlePermissions.ScanFailure.OEM_UNDOCUMENTED,
                    BlePermissions.scanFailure(oemCode));
            assertEquals(BlePermissions.AdvertiseFailure.OEM_UNDOCUMENTED,
                    BlePermissions.advertiseFailure(oemCode));
        }
    }
}
