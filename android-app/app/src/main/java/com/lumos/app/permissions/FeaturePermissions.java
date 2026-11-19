/*
 * Lumos - android-app/app / Which permission each feature needs, and only at first use.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-004, SRS-ONB-005, SRS-ONB-003, TRS-AND-005
 * ADRs: ADR-002
 * Threading: any
 * Notes: SRS-ONB-004 names the moment - "only when the related feature is
 * first invoked" - and this table is how that stays true as features arrive:
 * a surface asks THIS class what its feature needs and requests exactly that,
 * at that moment. There is deliberately no request-everything-at-launch entry
 * point, the same construction-over-convention shape as AppSurface and
 * DiscoveryVisibility. Every feature carries the copy keys for its rationale
 * (SRS-ONB-003's plain-language explanations live in strings.xml, tested by
 * OnboardingCopyTest) and for the EXACT capability lost on denial
 * (SRS-ONB-005) - naming the loss precisely is what makes a reduced feature
 * set honest rather than mysterious.
 */

package com.lumos.app.permissions;

import android.Manifest;
import android.annotation.SuppressLint;
import android.os.Build;
import com.lumos.app.ble.BlePermissions;
import java.util.List;

/**
 * The feature-to-permission table (SRS-ONB-004/005).
 *
 * <p>InlinedApi is suppressed for the same recorded reason as BlePermissions:
 * newer-SDK permission names are used as plain constant values behind version
 * checks.
 */
@SuppressLint("InlinedApi")
public final class FeaturePermissions {

    /** The permission-gated features of the application. */
    public enum Feature {

        /** Seeing and being seen by people nearby (Bluetooth). */
        NEARBY_DISCOVERY,

        /** Taking a profile photo with the camera. */
        PROFILE_PHOTO_CAPTURE,

        /**
         * Choosing a profile photo from the library. Needs NO permission: the
         * platform photo picker hands over only what the user picked, which
         * is both the better privacy posture and the SRS-ONB-004 ideal - the
         * feature's first use IS the grant.
         */
        PROFILE_PHOTO_LIBRARY,

        /** Recording a voice note. */
        VOICE_NOTES,

        /** Showing notifications, including the TRS-AND-006 nearby notice. */
        NOTIFICATIONS
    }

    private FeaturePermissions() {
        throw new AssertionError("no instances");
    }

    /**
     * The runtime permissions a feature needs on a given OS version,
     * requested only when that feature is first invoked (SRS-ONB-004).
     *
     * @param feature the feature being invoked
     * @param sdkInt the device's Build.VERSION.SDK_INT
     * @return the permissions to hold, possibly empty
     */
    public static List<String> requiredRuntimePermissions(final Feature feature,
            final int sdkInt) {
        switch (feature) {
            case NEARBY_DISCOVERY:
                return BlePermissions.requiredRuntimePermissions(sdkInt);
            case PROFILE_PHOTO_CAPTURE:
                return List.of(Manifest.permission.CAMERA);
            case VOICE_NOTES:
                return List.of(Manifest.permission.RECORD_AUDIO);
            case NOTIFICATIONS:
                return sdkInt >= Build.VERSION_CODES.TIRAMISU
                        ? List.of(Manifest.permission.POST_NOTIFICATIONS)
                        : List.of();
            case PROFILE_PHOTO_LIBRARY:
            default:
                return List.of();
        }
    }

    /**
     * The strings.xml key explaining in plain language why the feature wants
     * its permission (SRS-ONB-003).
     *
     * @param feature the feature being invoked
     * @return the rationale copy key
     */
    public static String rationaleCopyKey(final Feature feature) {
        return "perm_" + feature.name().toLowerCase(java.util.Locale.ROOT) + "_rationale";
    }

    /**
     * The strings.xml key naming the EXACT capability that is unavailable
     * when the permission is denied (SRS-ONB-005). Everything else continues.
     *
     * @param feature the feature that was denied
     * @return the denied-capability copy key
     */
    public static String deniedCapabilityCopyKey(final Feature feature) {
        return "perm_" + feature.name().toLowerCase(java.util.Locale.ROOT) + "_denied";
    }
}
