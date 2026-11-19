/*
 * Lumos - android-app/app / The radio-backed scanner adapter.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-AND-005, TRS-PROTO-009
 * ADRs: ADR-002
 * Threading: main-safe
 * Notes: Deliberately thin: filter on the Lumos service UUID, lift what the
 * platform saw into a ScanSighting, map failures through the TRS-AND-005
 * table, and nothing else - every decision lives above the seam where JVM
 * tests execute it. The service-data filter is on the UUID alone, never on
 * hint content: a UUID-only iOS or degraded peer must be sighted
 * (TRS-PROTO-018), and content judgment is the triage's cheap discard.
 * This adapter is radio glue; its execution evidence is the G-01 owner
 * campaign, and it claims nothing else.
 */

package com.lumos.app.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.ParcelUuid;
import com.lumos.protocol.LumosConstants;
import java.util.List;
import java.util.function.LongSupplier;

/** BleScanner over the Android radio. */
public final class AndroidBleScanner implements BleScanner {

    /** The Lumos service UUID from the register. */
    public static final ParcelUuid SERVICE_UUID =
            ParcelUuid.fromString(LumosConstants.SERVICE_UUID);

    private final BluetoothAdapter adapter;
    private final LongSupplier millisClock;

    private ScanCallback active;

    /**
     * @param adapter the device adapter, or null when the device has none
     * @param millisClock the injected clock sightings are stamped with
     */
    public AndroidBleScanner(final BluetoothAdapter adapter, final LongSupplier millisClock) {
        this.adapter = adapter;
        this.millisClock = millisClock;
    }

    @Override
    public void start(final Listener listener) {
        final BluetoothLeScanner scanner =
                adapter == null ? null : adapter.getBluetoothLeScanner();
        if (scanner == null) {
            listener.onScanFailed(BlePermissions.ScanFailure.ADAPTER_UNAVAILABLE);
            return;
        }
        final ScanCallback callback = new ScanCallback() {
            @Override
            public void onScanResult(final int callbackType, final ScanResult result) {
                listener.onSighting(sighting(result));
            }

            @Override
            public void onScanFailed(final int errorCode) {
                listener.onScanFailed(BlePermissions.scanFailure(errorCode));
            }
        };
        try {
            scanner.startScan(
                    List.of(new ScanFilter.Builder().setServiceUuid(SERVICE_UUID).build()),
                    new ScanSettings.Builder()
                            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                            .build(),
                    callback);
            active = callback;
        } catch (final SecurityException revoked) {
            // A revoked runtime grant mid-session is an OEM reality, not a
            // crash (TRS-AND-005).
            listener.onScanFailed(BlePermissions.ScanFailure.PERMISSION_MISSING);
        } catch (final IllegalStateException adapterOff) {
            listener.onScanFailed(BlePermissions.ScanFailure.ADAPTER_UNAVAILABLE);
        }
    }

    @Override
    public void stop() {
        if (active == null) {
            return;
        }
        final BluetoothLeScanner scanner =
                adapter == null ? null : adapter.getBluetoothLeScanner();
        final ScanCallback callback = active;
        active = null;
        if (scanner == null) {
            return;
        }
        try {
            scanner.stopScan(callback);
        } catch (final SecurityException | IllegalStateException alreadyGone) {
            // Stopping a scan the platform already tore down is success.
        }
    }

    private ScanSighting sighting(final ScanResult result) {
        final byte[] serviceData = result.getScanRecord() == null
                ? null
                : result.getScanRecord().getServiceData(SERVICE_UUID);
        // The scan-response capability/venue bytes are consumed by the venue
        // features of later WP-03 slices; discovery and HELLO need none of
        // them (TRS-PROTO-017), so this adapter does not surface them yet.
        // How OEM parsers merge same-UUID service data across the primary PDU
        // and the scan response is a G-01 measurement that will shape that
        // code; a 6- or 24-byte blob here simply fails the 18-byte structural
        // parse and is counted as a discard, never mistaken for a hint.
        return new ScanSighting(
                result.getDevice().getAddress(),
                serviceData,
                null,
                result.getRssi(),
                millisClock.getAsLong());
    }
}
