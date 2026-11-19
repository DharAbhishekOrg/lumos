/*
 * Lumos - android-app/app / ADR-011's three rulings, walked over every surface.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-NFR-SEC-013, SRS-SAF-004
 * ADRs: ADR-011
 * Threading: any
 * Notes: The tests iterate AppSurface.values(), so a surface added next year
 * is covered by construction: it gets recents redaction automatically, it
 * cannot block capture, and it warns on screenshots only if someone edits the
 * policy - and then this test - on purpose. The capture-never-blocked case
 * carries the SRS-SAF-004 reasoning in its message because it is the ruling
 * most tempting to "fix": a global FLAG_SECURE looks like safety and would
 * take away the harassed user's evidence.
 */

package com.lumos.app.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.onboarding.AppSurface;
import org.junit.jupiter.api.Test;

/** The per-surface screen-capture policy (ADR-011). */
final class ScreenPrivacyPolicyTest {

    @Test
    void theRecentsSnapshotIsRedactedOnEverySurface() {
        for (final AppSurface surface : AppSurface.values()) {
            assertTrue(ScreenPrivacyPolicy.recentsRedacted(surface),
                    surface + ": the app-switcher thumbnail is at-rest data and is "
                            + "always redacted (ADR-011)");
        }
    }

    @Test
    void captureIsNeverBlockedBecauseEvidenceCaptureIsASafetyFeature() {
        for (final AppSurface surface : AppSurface.values()) {
            assertFalse(ScreenPrivacyPolicy.captureBlocked(surface),
                    surface + ": blocking screenshots would take away the user's own "
                            + "evidence capture (SRS-SAF-004); detection, never prevention");
        }
    }

    @Test
    void screenshotWarningsApplyToConversationsAndNowhereElse() {
        for (final AppSurface surface : AppSurface.values()) {
            assertEquals(surface == AppSurface.CONVERSATIONS,
                    ScreenPrivacyPolicy.screenshotDetectAndWarn(surface),
                    surface + ": detect-and-warn is a peer-trust feature, scoped to "
                            + "conversations (ADR-011)");
        }
    }
}
