/*
 * Lumos - android-app/app / The TRS-AND-005/006 declarations on a real Android runtime.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-AND-005, TRS-AND-006
 * ADRs: ADR-002
 * Threading: any
 * Notes: The emulator has no BLE radio, and nothing here pretends otherwise -
 * radio behaviour is the G-01 owner campaign. What a real Android runtime CAN
 * prove is the half the platform enforces at install and service-start time:
 * the manifest's permission matrix parses and installs exactly as written
 * (the scoped trio, the capped legacy set, the foreground-service grants),
 * and the ProximityService actually reaches the foreground with its
 * user-visible notification and leaves cleanly - TRS-AND-006's contract,
 * executed, not asserted.
 */

package com.lumos.app.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The installed permission matrix and the foreground service, on device.
 *
 * <p>InlinedApi suppressed as in BlePermissions: the newer-SDK permission
 * names are compared against the manifest as plain string values, which works
 * identically on every runtime.
 */
@SuppressLint("InlinedApi")
@RunWith(AndroidJUnit4.class)
public class BleDeclarationsInstrumentedTest {

    private final Context context = ApplicationProvider.getApplicationContext();

    @Test
    public void theInstalledManifestCarriesTheWholeMatrix() throws Exception {
        final PackageInfo info = context.getPackageManager()
                .getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS);
        final List<String> requested = Arrays.asList(info.requestedPermissions);

        for (final String permission : List.of(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.FOREGROUND_SERVICE,
                Manifest.permission.POST_NOTIFICATIONS)) {
            assertTrue(permission + " must be declared", requested.contains(permission));
        }
        assertFalse("the ad identifier permission must never appear (TRS Sec. 5.3)",
                requested.contains("com.google.android.gms.permission.AD_ID"));
    }

    @Test
    public void theDeclaredServiceIsTheConnectedDeviceForegroundType() throws Exception {
        final PackageInfo info = context.getPackageManager()
                .getPackageInfo(context.getPackageName(), PackageManager.GET_SERVICES);
        ServiceInfo found = null;
        for (final ServiceInfo service : info.services) {
            if (ProximityService.class.getName().equals(service.name)) {
                found = service;
            }
        }
        assertNotNull("ProximityService must be declared", found);
        assertFalse("never exported", found.exported);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                    found.getForegroundServiceType());
        }
    }

    @Test
    public void theServiceReachesTheForegroundWithItsNotification() throws Exception {
        final NotificationManager manager = context.getSystemService(NotificationManager.class);
        ProximityService.start(context);
        try {
            StatusBarNotification ours = null;
            final long deadline = System.currentTimeMillis() + 10_000;
            while (ours == null && System.currentTimeMillis() < deadline) {
                for (final StatusBarNotification n : manager.getActiveNotifications()) {
                    if (n.getId() == ProximityService.NOTIFICATION_ID) {
                        ours = n;
                    }
                }
                Thread.sleep(100);
            }
            assertNotNull("the user-visible notification is the TRS-AND-006 contract", ours);
            assertEquals(ProximityService.CHANNEL_ID, ours.getNotification().getChannelId());
            assertNotNull(manager.getNotificationChannel(ProximityService.CHANNEL_ID));
        } finally {
            ProximityService.stop(context);
        }
    }
}
