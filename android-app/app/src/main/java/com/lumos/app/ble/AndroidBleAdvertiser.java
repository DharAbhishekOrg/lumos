/*
 * Lumos - android-app/app / The radio-backed advertiser adapter.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-AND-005, TRS-PROTO-009, TRS-PROTO-018
 * ADRs: ADR-002
 * Threading: main-safe
 * Notes: Hint mode uses EXTENDED advertising (API 26+): a 128-bit service-data
 * AD structure already costs 18 bytes of header, so the 18-byte adv_v1 hint
 * cannot ride a legacy 31-byte PDU at all - which is the budget arithmetic
 * behind ADR-002's split. A controller without LE extended advertising reports
 * FEATURE_UNSUPPORTED and the AdvertisingController downgrades to degraded
 * UUID-only legacy advertising, where the bare UUID does fit. Which real
 * controllers and OEM stacks carry the extended PDU, and how the scan response
 * survives their parsers, is exactly what the G-01 physical radio campaign
 * measures; this adapter claims compilation and mapping, not radio truth.
 */

package com.lumos.app.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.AdvertisingSet;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.BluetoothLeAdvertiser;

/** BleAdvertiser over the Android radio. */
public final class AndroidBleAdvertiser implements BleAdvertiser {

    /** Maps extended-set start failures onto the TRS-AND-005 table. */
    private static final class SetCallback extends AdvertisingSetCallback {

        private final Listener listener;

        private SetCallback(final Listener listener) {
            this.listener = listener;
        }

        @Override
        public void onAdvertisingSetStarted(final AdvertisingSet set, final int txPower,
                final int status) {
            if (status != AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                // The two callback families share their code space.
                listener.onAdvertiseFailed(BlePermissions.advertiseFailure(status));
            }
        }
    }

    /** Maps legacy advertising failures onto the TRS-AND-005 table. */
    private static final class LegacyCallback extends AdvertiseCallback {

        private final Listener listener;

        private LegacyCallback(final Listener listener) {
            this.listener = listener;
        }

        @Override
        public void onStartFailure(final int errorCode) {
            listener.onAdvertiseFailed(BlePermissions.advertiseFailure(errorCode));
        }
    }

    private final BluetoothAdapter adapter;

    private AdvertisingSetCallback activeSet;
    private AdvertiseCallback activeLegacy;

    /**
     * @param adapter the device adapter, or null when the device has none
     */
    public AndroidBleAdvertiser(final BluetoothAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public void startHint(final byte[] serviceData, final byte[] scanResponse,
            final Listener listener) {
        final BluetoothLeAdvertiser advertiser =
                adapter == null ? null : adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            listener.onAdvertiseFailed(BlePermissions.AdvertiseFailure.ADAPTER_UNAVAILABLE);
            return;
        }
        if (!adapter.isLeExtendedAdvertisingSupported()) {
            // The hint does not fit a legacy PDU; the controller downgrades.
            listener.onAdvertiseFailed(BlePermissions.AdvertiseFailure.FEATURE_UNSUPPORTED);
            return;
        }
        final AdvertisingSetParameters parameters = new AdvertisingSetParameters.Builder()
                .setLegacyMode(false)
                .setScannable(true)
                .setConnectable(true)
                .setInterval(AdvertisingSetParameters.INTERVAL_MEDIUM)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
                .build();
        final AdvertiseData primary = new AdvertiseData.Builder()
                .addServiceUuid(AndroidBleScanner.SERVICE_UUID)
                .addServiceData(AndroidBleScanner.SERVICE_UUID, serviceData)
                .setIncludeDeviceName(false)
                .build();
        final AdvertiseData response = new AdvertiseData.Builder()
                .addServiceData(AndroidBleScanner.SERVICE_UUID, scanResponse)
                .setIncludeDeviceName(false)
                .build();
        final AdvertisingSetCallback callback = new SetCallback(listener);
        try {
            advertiser.startAdvertisingSet(parameters, primary, response, null, null, callback);
            activeSet = callback;
        } catch (final SecurityException revoked) {
            listener.onAdvertiseFailed(BlePermissions.AdvertiseFailure.PERMISSION_MISSING);
        } catch (final IllegalStateException | IllegalArgumentException refused) {
            listener.onAdvertiseFailed(BlePermissions.AdvertiseFailure.FEATURE_UNSUPPORTED);
        }
    }

    @Override
    public void startDegraded(final Listener listener) {
        final BluetoothLeAdvertiser advertiser =
                adapter == null ? null : adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            listener.onAdvertiseFailed(BlePermissions.AdvertiseFailure.ADAPTER_UNAVAILABLE);
            return;
        }
        final AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(true)
                .build();
        final AdvertiseData uuidOnly = new AdvertiseData.Builder()
                .addServiceUuid(AndroidBleScanner.SERVICE_UUID)
                .setIncludeDeviceName(false)
                .build();
        final AdvertiseCallback callback = new LegacyCallback(listener);
        try {
            advertiser.startAdvertising(settings, uuidOnly, callback);
            activeLegacy = callback;
        } catch (final SecurityException revoked) {
            listener.onAdvertiseFailed(BlePermissions.AdvertiseFailure.PERMISSION_MISSING);
        } catch (final IllegalStateException adapterOff) {
            listener.onAdvertiseFailed(BlePermissions.AdvertiseFailure.ADAPTER_UNAVAILABLE);
        }
    }

    @Override
    public void stop() {
        final BluetoothLeAdvertiser advertiser =
                adapter == null ? null : adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            activeSet = null;
            activeLegacy = null;
            return;
        }
        try {
            if (activeSet != null) {
                advertiser.stopAdvertisingSet(activeSet);
            }
            if (activeLegacy != null) {
                advertiser.stopAdvertising(activeLegacy);
            }
        } catch (final SecurityException | IllegalStateException alreadyGone) {
            // Stopping what the platform already tore down is success.
        }
        activeSet = null;
        activeLegacy = null;
    }
}
