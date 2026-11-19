/*
 * Lumos - android-app/app / The foreground service active discovery runs inside.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-AND-006, SRS-NFR-BAT-001
 * ADRs: ADR-002
 * Threading: main-safe
 * Notes: TRS-AND-006: venue and mesh operation that must keep executing runs
 * in a foreground service with a user-visible notification. This service is
 * the container, not the brain - controllers are wired into it by later
 * slices; what it owns is the Android lifecycle contract: the connectedDevice
 * foreground type (enforced from API 34), the notification channel, and the
 * promise that stopping the service stops the radio work. The notification is
 * deliberately boring and always present: a user can see Lumos is active and
 * make it stop, which is the honest shape of background radio use.
 */

package com.lumos.app.ble;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import com.lumos.app.R;

/** Foreground container for active nearby discovery (TRS-AND-006). */
public final class ProximityService extends Service {

    /** The notification channel id; stable so user muting survives updates. */
    public static final String CHANNEL_ID = "lumos.nearby";

    /** The ongoing notification id. */
    public static final int NOTIFICATION_ID = 1001;

    /**
     * Starts the service in the foreground.
     *
     * @param context any context
     */
    public static void start(final Context context) {
        // minSdk 26: startForegroundService exists on every supported device.
        context.startForegroundService(new Intent(context, ProximityService.class));
    }

    /**
     * Stops the service and its notification.
     *
     * @param context any context
     */
    public static void stop(final Context context) {
        context.stopService(new Intent(context, ProximityService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        final NotificationManager manager = getSystemService(NotificationManager.class);
        final NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.nearby_channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.nearby_channel_description));
        manager.createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        final Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.nearby_notification_title))
                .setContentText(getString(R.string.nearby_notification_body))
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        // Discovery must not silently restart after the system kills the
        // process: visibility is a user decision (SRS-ONB-008's spirit), so
        // the service only runs while explicitly started.
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(final Intent intent) {
        return null;
    }
}
