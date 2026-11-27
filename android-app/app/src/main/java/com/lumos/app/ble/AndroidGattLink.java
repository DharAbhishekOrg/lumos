/*
 * Lumos - android-app/app / The GATT client and server that carry discovery links.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-TRN-002, TRS-TRN-003, TRS-PROTO-017, TRS-AND-005, SRS-NBY-005
 * ADRs: ADR-002, ADR-013
 * Threading: serial-executor (every platform callback is hopped onto the one link executor)
 * Notes: ADR-013's layout on Android, as the DiscoveryEngine's Radio. Two
 * roles, one link type:
 *
 * - Central (this phone connects): connect over LE, ask for the register's
 *   ATT MTU, discover the Lumos service, enable notifications on LINK_OUT
 *   through its 0x2902 descriptor, then tell the engine the link is ready;
 *   frames go out as writes to LINK_IN and come in as LINK_OUT notifications.
 * - Peripheral (a phone connects here): a GATT server hosting the service; a
 *   central enabling LINK_OUT notifications opens a link in the engine; frames
 *   come in as LINK_IN writes and go out as LINK_OUT notifications.
 *
 * The identifiers and the service shape live in GattLayout.
 *
 * Every frame is fragmented by FrameLink at the negotiated MTU and every
 * platform operation goes through that link's GattOperationQueue, one at a
 * time with epoch-stamped completions (TRS-TRN-003). Binder-thread callbacks
 * never touch the engine directly: each is posted to the serial link executor
 * the engine also runs on. A revoked permission or a vanished adapter ends the
 * link quietly (TRS-AND-005); nothing here logs, stores or shows an address,
 * a handle or a byte of payload. This class is radio glue: its evidence is
 * the owner's two-phone test and the G-01 campaign, not a JVM test.
 */

package com.lumos.app.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.os.Build;
import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.wire.Frame;
import com.lumos.protocol.wire.FragmentReassembler;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/** DiscoveryEngine.Radio over Android's GATT client and server. */
public final class AndroidGattLink implements LinkRadio {

    /** Prefix of the handles this class gives links that peers open to us. */
    static final String PERIPHERAL_PREFIX = "p";

    private final Context context;
    private final BluetoothAdapter adapter;
    private final BluetoothManager manager;
    private final Executor executor;
    private final TaskScheduler scheduler;
    private final LongSupplier clock;
    private final Map<String, Link> links = new HashMap<>();
    private final Map<String, String> peripheralByAddress = new HashMap<>();
    private DiscoveryEngine engine;
    private BluetoothGattServer server;
    private BluetoothGattCharacteristic serverOut;
    private long nextPeripheral;

    /** One link in either role. */
    private final class Link {
        final GattOperationQueue queue;
        final FrameLink frames;
        BluetoothGatt gatt;
        BluetoothGattCharacteristic remoteIn;
        BluetoothDevice device;
        int mtu = GattLayout.DEFAULT_ATT_MTU;

        Link(final String handle, final FrameLink.FragmentWriter writer) {
            this.queue = new GattOperationQueue(scheduler, bound -> 0L);
            this.frames = new FrameLink(queue, writer,
                    new FragmentReassembler((long) LumosConstants.REASSEMBLY_TIMEOUT_SEC_BLE * 1000),
                    blob -> {
                        if (engine != null && links.get(handle) == this) {
                            engine.onFrame(handle, blob, clock.getAsLong());
                        }
                    });
        }
    }

    /**
     * @param context the application context
     * @param manager the platform Bluetooth manager, or null on a device without one
     * @param executor the serial executor the engine runs on; every callback lands here
     * @param scheduler delays for the operation queues' retries, on that executor
     * @param clock the engine's millisecond clock
     */
    public AndroidGattLink(final Context context, final BluetoothManager manager, final Executor executor,
            final TaskScheduler scheduler, final LongSupplier clock) {
        this.context = context.getApplicationContext();
        this.manager = manager;
        this.adapter = manager == null ? null : manager.getAdapter();
        this.executor = executor;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    /**
     * Binds the engine the links report to. Call on the link executor.
     *
     * @param discovery the engine
     */
    @Override
    public void bind(final DiscoveryEngine discovery) {
        this.engine = discovery;
    }

    /**
     * Opens the GATT server so peers can connect here. Call on the link executor.
     *
     * @return false when there is no adapter or the permission is missing
     */
    @Override
    public boolean openServer() {
        if (server != null) {
            return true;
        }
        if (manager == null || adapter == null) {
            return false;
        }
        try {
            final BluetoothGattServer opened = manager.openGattServer(context, serverCallback);
            if (opened == null) {
                return false;
            }
            final BluetoothGattService service = GattLayout.service();
            opened.addService(service);
            server = opened;
            serverOut = service.getCharacteristic(GattLayout.LINK_OUT);
            return true;
        } catch (final SecurityException revoked) {
            return false;
        }
    }

    /** Closes the GATT server and every link in either role. Call on the link executor. */
    @Override
    public void close() {
        for (final String handle : links.keySet().toArray(new String[0])) {
            disconnect(handle);
        }
        if (server != null) {
            try {
                server.close();
            } catch (final SecurityException alreadyGone) {
                // Closing a server the platform already dropped is success.
            }
        }
        server = null;
        serverOut = null;
    }

    // ---- DiscoveryEngine.Radio, called on the link executor ----

    @Override
    public void connect(final String handle, final String linkToken) {
        final Link link = new Link(handle, (fragment, epoch) -> writeToPeripheral(handle, fragment, epoch));
        links.put(handle, link);
        try {
            if (adapter == null || !BluetoothAdapter.checkBluetoothAddress(linkToken)) {
                dropped(handle);
                return;
            }
            final BluetoothDevice device = adapter.getRemoteDevice(linkToken);
            link.device = device;
            link.gatt = device.connectGatt(context, false, centralCallback(handle), BluetoothDevice.TRANSPORT_LE);
            if (link.gatt == null) {
                dropped(handle);
            }
        } catch (final SecurityException | IllegalArgumentException refused) {
            dropped(handle);
        }
    }

    @Override
    public void send(final String handle, final byte[] frame) {
        final Link link = links.get(handle);
        if (link == null) {
            return;
        }
        try {
            link.frames.send(frame, link.mtu, status -> {
                if (status != GattOperationQueue.STATUS_SUCCESS) {
                    disconnect(handle);
                    dropped(handle);
                }
            });
        } catch (final Frame.WireException tooLarge) {
            disconnect(handle);
            dropped(handle);
        }
    }

    @Override
    public void disconnect(final String handle) {
        final Link link = links.remove(handle);
        if (link == null) {
            return;
        }
        link.queue.advanceEpoch(BluetoothGatt.GATT_FAILURE);
        try {
            if (link.gatt != null) {
                link.gatt.disconnect();
                link.gatt.close();
            } else if (link.device != null && server != null) {
                peripheralByAddress.remove(link.device.getAddress());
                server.cancelConnection(link.device);
            }
        } catch (final SecurityException alreadyGone) {
            // A link the platform already tore down is closed.
        }
    }

    // ---- central role ----

    private BluetoothGattCallback centralCallback(final String handle) {
        return new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(final BluetoothGatt gatt, final int status, final int state) {
                executor.execute(() -> centralState(handle, gatt, status, state));
            }

            @Override
            public void onMtuChanged(final BluetoothGatt gatt, final int mtu, final int status) {
                executor.execute(() -> centralMtu(handle, gatt, mtu, status));
            }

            @Override
            public void onServicesDiscovered(final BluetoothGatt gatt, final int status) {
                executor.execute(() -> centralServices(handle, gatt, status));
            }

            @Override
            public void onDescriptorWrite(final BluetoothGatt gatt, final BluetoothGattDescriptor descriptor,
                    final int status) {
                executor.execute(() -> centralSubscribed(handle, status));
            }

            @Override
            public void onCharacteristicWrite(final BluetoothGatt gatt,
                    final BluetoothGattCharacteristic characteristic, final int status) {
                executor.execute(() -> {
                    final Link link = links.get(handle);
                    if (link != null) {
                        link.queue.operationCompleted(link.queue.epoch(), status);
                    }
                });
            }

            @Override
            public void onCharacteristicChanged(final BluetoothGatt gatt,
                    final BluetoothGattCharacteristic characteristic, final byte[] value) {
                // API 33+: the value is handed over, never shared.
                received(handle, characteristic.getUuid(), value);
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onCharacteristicChanged(final BluetoothGatt gatt,
                    final BluetoothGattCharacteristic characteristic) {
                // Below API 33 the value lives on the shared object: copy it now.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    final byte[] value = characteristic.getValue();
                    received(handle, characteristic.getUuid(), value == null ? new byte[0] : value.clone());
                }
            }
        };
    }

    private void received(final String handle, final UUID characteristic, final byte[] value) {
        if (!GattLayout.LINK_OUT.equals(characteristic)) {
            return;
        }
        final byte[] copy = Arrays.copyOf(value, value.length);
        executor.execute(() -> {
            final Link link = links.get(handle);
            if (link != null) {
                link.frames.onFragmentReceived(copy, clock.getAsLong());
            }
        });
    }

    private void centralState(final String handle, final BluetoothGatt gatt, final int status, final int state) {
        final Link link = links.get(handle);
        if (link == null || link.gatt != gatt) {
            GattLayout.closeQuietly(gatt);
            return;
        }
        try {
            if (state == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (!gatt.requestMtu(LumosConstants.BLE_TARGET_ATT_MTU_DEFAULT)) {
                    gatt.discoverServices();
                }
                return;
            }
        } catch (final SecurityException revoked) {
            // Falls through: the link ends.
        }
        disconnect(handle);
        dropped(handle);
    }

    private void centralMtu(final String handle, final BluetoothGatt gatt, final int mtu, final int status) {
        final Link link = links.get(handle);
        if (link == null) {
            return;
        }
        if (status == BluetoothGatt.GATT_SUCCESS) {
            link.mtu = mtu;
        }
        try {
            if (!gatt.discoverServices()) {
                disconnect(handle);
                dropped(handle);
            }
        } catch (final SecurityException revoked) {
            disconnect(handle);
            dropped(handle);
        }
    }

    private void centralServices(final String handle, final BluetoothGatt gatt, final int status) {
        final Link link = links.get(handle);
        if (link == null) {
            return;
        }
        final BluetoothGattService service =
                status == BluetoothGatt.GATT_SUCCESS ? gatt.getService(GattLayout.SERVICE) : null;
        final BluetoothGattCharacteristic in =
                service == null ? null : service.getCharacteristic(GattLayout.LINK_IN);
        final BluetoothGattCharacteristic out =
                service == null ? null : service.getCharacteristic(GattLayout.LINK_OUT);
        final BluetoothGattDescriptor cccd = out == null ? null : out.getDescriptor(GattLayout.CCCD);
        if (in == null || cccd == null) {
            // Not a Lumos link layout: nothing to say to it.
            disconnect(handle);
            dropped(handle);
            return;
        }
        link.remoteIn = in;
        try {
            if (!gatt.setCharacteristicNotification(out, true) || !GattLayout.enableNotifications(gatt, cccd)) {
                disconnect(handle);
                dropped(handle);
            }
        } catch (final SecurityException revoked) {
            disconnect(handle);
            dropped(handle);
        }
    }

    private void centralSubscribed(final String handle, final int status) {
        if (!links.containsKey(handle)) {
            return;
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            disconnect(handle);
            dropped(handle);
            return;
        }
        if (engine != null) {
            engine.onLinkReady(handle, clock.getAsLong());
        }
    }

    @SuppressWarnings("deprecation")
    private void writeToPeripheral(final String handle, final byte[] fragment, final long epoch) {
        final Link link = links.get(handle);
        if (link == null || link.gatt == null || link.remoteIn == null) {
            return;
        }
        boolean issued;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                issued = link.gatt.writeCharacteristic(link.remoteIn, fragment,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS;
            } else {
                link.remoteIn.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                link.remoteIn.setValue(fragment);
                issued = link.gatt.writeCharacteristic(link.remoteIn);
            }
        } catch (final SecurityException revoked) {
            issued = false;
        }
        if (!issued) {
            link.queue.operationCompleted(epoch, BluetoothGatt.GATT_FAILURE);
        }
    }

    // ---- peripheral role ----

    private final BluetoothGattServerCallback serverCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(final BluetoothDevice device, final int status, final int state) {
            if (state == BluetoothProfile.STATE_DISCONNECTED) {
                final String address = device.getAddress();
                executor.execute(() -> {
                    final String handle = peripheralByAddress.remove(address);
                    if (handle != null && links.remove(handle) != null) {
                        dropped(handle);
                    }
                });
            }
        }

        @Override
        public void onMtuChanged(final BluetoothDevice device, final int mtu) {
            final String address = device.getAddress();
            executor.execute(() -> {
                final String handle = peripheralByAddress.get(address);
                final Link link = handle == null ? null : links.get(handle);
                if (link != null) {
                    link.mtu = mtu;
                }
            });
        }

        @Override
        public void onDescriptorWriteRequest(final BluetoothDevice device, final int requestId,
                final BluetoothGattDescriptor descriptor, final boolean preparedWrite, final boolean responseNeeded,
                final int offset, final byte[] value) {
            respond(device, requestId, responseNeeded);
            final boolean subscribe = GattLayout.CCCD.equals(descriptor.getUuid())
                    && GattLayout.LINK_OUT.equals(descriptor.getCharacteristic().getUuid())
                    && Arrays.equals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, value);
            if (subscribe) {
                executor.execute(() -> peripheralSubscribed(device));
            }
        }

        @Override
        public void onCharacteristicWriteRequest(final BluetoothDevice device, final int requestId,
                final BluetoothGattCharacteristic characteristic, final boolean preparedWrite,
                final boolean responseNeeded, final int offset, final byte[] value) {
            respond(device, requestId, responseNeeded);
            if (!GattLayout.LINK_IN.equals(characteristic.getUuid()) || value == null) {
                return;
            }
            final byte[] copy = Arrays.copyOf(value, value.length);
            final String address = device.getAddress();
            executor.execute(() -> {
                final String handle = peripheralByAddress.get(address);
                final Link link = handle == null ? null : links.get(handle);
                if (link != null) {
                    link.frames.onFragmentReceived(copy, clock.getAsLong());
                }
            });
        }

        @Override
        public void onNotificationSent(final BluetoothDevice device, final int status) {
            final String address = device.getAddress();
            executor.execute(() -> {
                final String handle = peripheralByAddress.get(address);
                final Link link = handle == null ? null : links.get(handle);
                if (link != null) {
                    link.queue.operationCompleted(link.queue.epoch(), status);
                }
            });
        }
    };

    private void respond(final BluetoothDevice device, final int requestId, final boolean responseNeeded) {
        final BluetoothGattServer current = server;
        if (!responseNeeded || current == null) {
            return;
        }
        try {
            current.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
        } catch (final SecurityException revoked) {
            // The central will time out; the handshake timer bounds the link.
        }
    }

    private void peripheralSubscribed(final BluetoothDevice device) {
        final String address = device.getAddress();
        if (peripheralByAddress.containsKey(address) || engine == null) {
            return;
        }
        nextPeripheral++;
        final String handle = PERIPHERAL_PREFIX + nextPeripheral;
        final Link link = new Link(handle, (fragment, epoch) -> notifyCentral(handle, fragment, epoch));
        link.device = device;
        links.put(handle, link);
        peripheralByAddress.put(address, handle);
        engine.onCentralSubscribed(handle, clock.getAsLong());
    }

    @SuppressWarnings("deprecation")
    private void notifyCentral(final String handle, final byte[] fragment, final long epoch) {
        final Link link = links.get(handle);
        final BluetoothGattServer current = server;
        final BluetoothGattCharacteristic out = serverOut;
        if (link == null || current == null || out == null) {
            return;
        }
        boolean issued;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                issued = current.notifyCharacteristicChanged(link.device, out, false, fragment)
                        == BluetoothStatusCodes.SUCCESS;
            } else {
                out.setValue(fragment);
                issued = current.notifyCharacteristicChanged(link.device, out, false);
            }
        } catch (final SecurityException revoked) {
            issued = false;
        }
        if (!issued) {
            link.queue.operationCompleted(epoch, BluetoothGatt.GATT_FAILURE);
        }
    }

    // ---- shared ----

    private void dropped(final String handle) {
        links.remove(handle);
        if (engine != null) {
            engine.onLinkClosed(handle);
        }
    }
}
