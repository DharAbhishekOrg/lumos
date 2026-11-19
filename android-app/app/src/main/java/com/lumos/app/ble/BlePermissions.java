/*
 * Lumos - android-app/app / The version-aware BLE permission matrix and failure states.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-AND-005
 * ADRs: ADR-002
 * Threading: any
 * Notes: TRS-AND-005 has two halves and this class is both. The permission
 * matrix: API 31+ uses the scoped runtime trio (BLUETOOTH_SCAN with
 * neverForLocation in the manifest, BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT);
 * API 26-30 uses legacy BLUETOOTH/BLUETOOTH_ADMIN plus ACCESS_FINE_LOCATION,
 * which pre-31 Android requires for scan RESULTS to be delivered at all - the
 * app does not compute location, and on 31+ the neverForLocation flag makes
 * that claim enforceable, so location is never requested there. The failure
 * states: every platform scan/advertise error code maps to a typed state, with
 * the OEM long tail (vendor stacks return codes outside the documented set)
 * mapped explicitly rather than crashing or being mistaken for success.
 */

package com.lumos.app.ble;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.ScanCallback;
import android.os.Build;
import java.util.List;

/**
 * Runtime permissions and typed radio failure states (TRS-AND-005).
 *
 * <p>InlinedApi is suppressed for this class deliberately: it references
 * permission names and failure codes ADDED after minSdk as plain compile-time
 * constants, which is the point - the 31+ names are only ever requested behind
 * the version check below, and an OEM stack returning a 33-era failure code on
 * an older device must still map to its typed state rather than crash. The
 * inlined values are stable AOSP constants by contract.
 */
@SuppressLint("InlinedApi")
public final class BlePermissions {

    private BlePermissions() {
        throw new AssertionError("no instances");
    }

    /** Typed scan failure states, OEM long tail included. */
    public enum ScanFailure {
        /** A scan was already running under this app. */
        ALREADY_STARTED,
        /** The platform could not register the scan client. */
        APPLICATION_REGISTRATION_FAILED,
        /** The adapter does not support the requested scan. */
        FEATURE_UNSUPPORTED,
        /** An internal stack error. */
        INTERNAL_ERROR,
        /** The controller is out of hardware scan slots. */
        OUT_OF_HARDWARE_RESOURCES,
        /** The app started and stopped scans too often; back off. */
        SCANNING_TOO_FREQUENTLY,
        /** A required runtime permission is missing or was revoked. */
        PERMISSION_MISSING,
        /** Bluetooth is off or the adapter is unavailable. */
        ADAPTER_UNAVAILABLE,
        /** A code outside the documented set - the OEM long tail. */
        OEM_UNDOCUMENTED
    }

    /** Typed advertise failure states. */
    public enum AdvertiseFailure {
        /** Advertising was already running under this app. */
        ALREADY_STARTED,
        /** The payload exceeds what this adapter can carry; degrade to UUID-only. */
        DATA_TOO_LARGE,
        /** The adapter has no free advertising instance. */
        TOO_MANY_ADVERTISERS,
        /** An internal stack error. */
        INTERNAL_ERROR,
        /** The adapter cannot advertise at all. */
        FEATURE_UNSUPPORTED,
        /** A required runtime permission is missing or was revoked. */
        PERMISSION_MISSING,
        /** Bluetooth is off or the adapter is unavailable. */
        ADAPTER_UNAVAILABLE,
        /** A code outside the documented set - the OEM long tail. */
        OEM_UNDOCUMENTED
    }

    /**
     * The runtime permissions BLE discovery needs on a given OS version.
     *
     * @param sdkInt the device's Build.VERSION.SDK_INT
     * @return the permissions to hold before starting scan or advertise
     */
    public static List<String> requiredRuntimePermissions(final int sdkInt) {
        if (sdkInt >= Build.VERSION_CODES.S) {
            return List.of(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT);
        }
        // 26..30: the legacy pair is install-time; fine location is the one
        // runtime grant, and it exists solely because pre-31 Android withholds
        // scan results without it.
        return List.of(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    /**
     * Whether notifications need a runtime grant on this OS version - the
     * TRS-AND-006 foreground notification is user-visible by requirement.
     *
     * @param sdkInt the device's Build.VERSION.SDK_INT
     * @return true from API 33 on
     */
    public static boolean needsNotificationPermission(final int sdkInt) {
        return sdkInt >= Build.VERSION_CODES.TIRAMISU;
    }

    /**
     * Maps a platform scan failure code to its typed state.
     *
     * @param platformCode the code the ScanCallback delivered
     * @return the typed state; never null, never a crash (TRS-AND-005)
     */
    public static ScanFailure scanFailure(final int platformCode) {
        switch (platformCode) {
            case ScanCallback.SCAN_FAILED_ALREADY_STARTED:
                return ScanFailure.ALREADY_STARTED;
            case ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                return ScanFailure.APPLICATION_REGISTRATION_FAILED;
            case ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED:
                return ScanFailure.FEATURE_UNSUPPORTED;
            case ScanCallback.SCAN_FAILED_INTERNAL_ERROR:
                return ScanFailure.INTERNAL_ERROR;
            case ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES:
                return ScanFailure.OUT_OF_HARDWARE_RESOURCES;
            case ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY:
                return ScanFailure.SCANNING_TOO_FREQUENTLY;
            default:
                return ScanFailure.OEM_UNDOCUMENTED;
        }
    }

    /**
     * Maps a platform advertise failure code to its typed state.
     *
     * @param platformCode the code the AdvertiseCallback delivered
     * @return the typed state; never null, never a crash (TRS-AND-005)
     */
    public static AdvertiseFailure advertiseFailure(final int platformCode) {
        switch (platformCode) {
            case AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED:
                return AdvertiseFailure.ALREADY_STARTED;
            case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE:
                return AdvertiseFailure.DATA_TOO_LARGE;
            case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                return AdvertiseFailure.TOO_MANY_ADVERTISERS;
            case AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR:
                return AdvertiseFailure.INTERNAL_ERROR;
            case AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                return AdvertiseFailure.FEATURE_UNSUPPORTED;
            default:
                return AdvertiseFailure.OEM_UNDOCUMENTED;
        }
    }
}
