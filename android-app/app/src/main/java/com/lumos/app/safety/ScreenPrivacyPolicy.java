/*
 * Lumos - android-app/app / The ADR-011 screen-capture policy, as a table.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-NFR-SEC-013, SRS-SAF-004
 * ADRs: ADR-011
 * Threading: any
 * Notes: ADR-011's three rulings, encoded where every activity will read them
 * rather than remember them. One: the recents/app-switcher snapshot is ALWAYS
 * redacted, on every surface - the OS writes that thumbnail to disk, so it is
 * at-rest data, not a display concern. Two: screenshot DETECTION with a
 * warning applies to conversations, where a peer's trust is in play. Three:
 * capture is NEVER BLOCKED - no global FLAG_SECURE anywhere - because
 * SRS-SAF-004's evidence capture must remain possible: a safety feature that
 * blocks collecting evidence of harassment protects the harasser. The policy
 * test walks every AppSurface value, so a surface added next year gets a row
 * by construction.
 */

package com.lumos.app.safety;

import com.lumos.app.onboarding.AppSurface;
import java.util.Objects;

/** Per-surface screen-capture policy (ADR-011). */
public final class ScreenPrivacyPolicy {

    private ScreenPrivacyPolicy() {
        throw new AssertionError("no instances");
    }

    /**
     * Whether the recents snapshot must be redacted for a surface. It must,
     * for every surface, unconditionally: the thumbnail is at-rest data.
     *
     * @param surface the surface being shown
     * @return true, always
     */
    public static boolean recentsRedacted(final AppSurface surface) {
        Objects.requireNonNull(surface, "surface");
        return true;
    }

    /**
     * Whether taking a screenshot of a surface is prevented. It never is
     * (ADR-011 ruling 3): the user's own evidence capture (SRS-SAF-004)
     * outranks shoulder-surfing prevention on every surface.
     *
     * @param surface the surface being shown
     * @return false, always
     */
    public static boolean captureBlocked(final AppSurface surface) {
        Objects.requireNonNull(surface, "surface");
        return false;
    }

    /**
     * Whether a detected screenshot warns the user on this surface
     * (ADR-011 ruling 2): detection, not prevention, in conversations.
     *
     * @param surface the surface being shown
     * @return true for conversation surfaces
     */
    public static boolean screenshotDetectAndWarn(final AppSurface surface) {
        return surface == AppSurface.CONVERSATIONS;
    }
}
