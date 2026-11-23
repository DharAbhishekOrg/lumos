/*
 * Lumos - android-app/app / Keeps every screen out of the app switcher's thumbnails (ADR-011 ruling 1).
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-NFR-SEC-013, SRS-SAF-004
 * ADRs: ADR-011
 * Threading: main
 * Notes: The one place the ruling is applied, so every activity applies it
 * the same way. On API 33 and later the activity opts out of recents
 * screenshots when it is created. Below API 33 there is no such switch, so
 * FLAG_SECURE is set only while the activity is leaving (onPause) and cleared
 * when it returns (onResume): the snapshot the system takes on the way out is
 * blank, and a screenshot the person takes inside the app is never blocked
 * (ruling 3). Extracted from NearbyActivity (CS2c) when the conversation
 * screen was found not to apply it.
 */

package com.lumos.app.safety;

import android.app.Activity;
import android.os.Build;
import android.view.WindowManager;
import com.lumos.app.onboarding.AppSurface;

/** Applies ADR-011's recents redaction to an activity. */
public final class RecentsRedaction {

    private RecentsRedaction() {
        throw new AssertionError("no instances");
    }

    /**
     * Call first in onCreate, before anything is inflated.
     *
     * @param activity the activity
     * @param surface what it shows
     */
    public static void created(final Activity activity, final AppSurface surface) {
        if (ScreenPrivacyPolicy.recentsRedacted(surface) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(false);
        }
    }

    /**
     * Call with true in onPause and false in onResume.
     *
     * @param activity the activity
     * @param surface what it shows
     * @param leaving true while it leaves the screen
     */
    public static void leaving(final Activity activity, final AppSurface surface, final boolean leaving) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || !ScreenPrivacyPolicy.recentsRedacted(surface)) {
            return;
        }
        if (leaving) {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }
}
