/*
 * Lumos - android-app/app / ADR-013's GATT layout: identifiers, the hosted service, enabling notify.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-PROTO-017, TRS-TRN-002, SRS-NBY-005
 * ADRs: ADR-002, ADR-013
 * Threading: any
 * Notes: The shape both roles agree on, kept apart from AndroidGattLink's
 * link handling: the service UUID (ADR-002), LINK_IN and LINK_OUT from the
 * register (CR-010, never written by hand), the Bluetooth SIG 0x2902
 * descriptor, the service a peripheral hosts, and the API-level split for
 * enabling notifications. Every platform call that needs a runtime
 * permission catches its revocation here, so callers see a plain false.
 */

package com.lumos.app.ble;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothStatusCodes;
import android.os.Build;
import com.lumos.protocol.LumosConstants;
import java.util.UUID;

/** The Lumos GATT layout (ADR-013). */
final class GattLayout {

    /** The Lumos service (ADR-002). */
    static final UUID SERVICE = UUID.fromString(LumosConstants.SERVICE_UUID);
    /** The peripheral receives here (CR-010). */
    static final UUID LINK_IN = UUID.fromString(LumosConstants.GATT_LINK_IN_CHAR_UUID_VALUE);
    /** The peripheral sends here (CR-010). */
    static final UUID LINK_OUT = UUID.fromString(LumosConstants.GATT_LINK_OUT_CHAR_UUID_VALUE);
    /** The Bluetooth SIG client characteristic configuration descriptor. */
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    /** The ATT MTU every link starts with before negotiation. */
    static final int DEFAULT_ATT_MTU = 23;

    private GattLayout() {
    }

    /**
     * @return the service a peripheral hosts: LINK_IN (write, write without
     *     response) and LINK_OUT (notify, with its 0x2902 descriptor)
     */
    static BluetoothGattService service() {
        final BluetoothGattService service =
                new BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        final BluetoothGattCharacteristic in = new BluetoothGattCharacteristic(LINK_IN,
                BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        final BluetoothGattCharacteristic out = new BluetoothGattCharacteristic(LINK_OUT,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ);
        out.addDescriptor(new BluetoothGattDescriptor(CCCD,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
        service.addCharacteristic(in);
        service.addCharacteristic(out);
        return service;
    }

    /**
     * Writes "notify on" to LINK_OUT's descriptor, on either side of API 33.
     *
     * @param gatt the central's connection
     * @param cccd LINK_OUT's 0x2902 descriptor
     * @return true when the write was issued
     */
    @SuppressWarnings("deprecation")
    static boolean enableNotifications(final BluetoothGatt gatt, final BluetoothGattDescriptor cccd) {
        final byte[] enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return gatt.writeDescriptor(cccd, enable) == BluetoothStatusCodes.SUCCESS;
            }
            cccd.setValue(enable);
            return gatt.writeDescriptor(cccd);
        } catch (final SecurityException revoked) {
            return false;
        }
    }

    /**
     * Closes a connection, quietly.
     *
     * @param gatt the connection
     */
    static void closeQuietly(final BluetoothGatt gatt) {
        try {
            gatt.close();
        } catch (final SecurityException alreadyGone) {
            // Nothing left to close.
        }
    }
}
