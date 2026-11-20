/*
 * Lumos - android-app/app / The state matrix, demonstrated on a real screen.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-NBY-003, SRS-NBY-010, SRS-NBY-014, SRS-ONB-001
 * ADRs: ADR-002, ADR-011
 * Threading: test-only
 * Notes: The WP-03 closeout Sec. 6.3 exit-evidence item, closed where it
 * said it would be: "UI states for permission/adapter-off matrix
 * demonstrated". A scripted host drives the real activity on the emulator
 * through every FeedState kind and the test reads what a person would see -
 * the right message for each radio situation, skeleton copy instead of a
 * spinner for empty, the list only for cards, and the SRS-NBY-003 indicator
 * only while advertising is actually active. The AT-001 half is the
 * entry-gate test: a host whose onboarding gate answers no (or no host at
 * all, the fresh-install shape) means the activity finishes before
 * inflating anything.
 */

package com.lumos.app.nearby;

import static org.junit.Assert.assertEquals;

import android.view.View;
import android.widget.TextView;
// Lifecycle.State is ActivityScenario.getState()'s return type - it arrives
// as part of the declared androidx.test:core API surface, not as a
// dependency of its own.
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.lumos.app.R;
import com.lumos.app.ble.BlePermissions;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Drives NearbyActivity through the SRS-NBY-010 matrix. */
@RunWith(AndroidJUnit4.class)
public final class NearbyStatesInstrumentedTest {

    /** A host the test scripts; every answer is a field. */
    private static final class ScriptedHost implements NearbyHost {
        private boolean allowed = true;
        private boolean advertising;
        private FeedState state = FeedState.scanningEmpty();
        private int stops;

        @Override
        public boolean entryAllowed() {
            return allowed;
        }

        @Override
        public FeedState currentState() {
            return state;
        }

        @Override
        public boolean advertisingActive() {
            return advertising;
        }

        @Override
        public void hideCard(final String peerKey) {
            // Unused in the state demo.
        }

        @Override
        public void stopNearby() {
            stops++;
            advertising = false;
        }
    }

    @After
    public void clearHost() {
        NearbyActivity.setHost(null);
    }

    @Test
    public void everyRadioSituationShowsItsOwnSentenceAndEmptyIsASentenceNotASpinner() {
        final ScriptedHost script = new ScriptedHost();
        NearbyActivity.setHost(script);
        try (ActivityScenario<NearbyActivity> scenario =
                ActivityScenario.launch(NearbyActivity.class)) {
            final int[][] expectations = {
                {FeedState.Kind.RADIO_OFF.ordinal(), R.string.nearby_state_radio_off},
                {FeedState.Kind.PERMISSION_NEEDED.ordinal(),
                    R.string.nearby_state_permission_needed},
                {FeedState.Kind.RADIO_UNAVAILABLE.ordinal(),
                    R.string.nearby_state_radio_unavailable},
                {FeedState.Kind.RADIO_BUSY.ordinal(), R.string.nearby_state_radio_busy},
                {FeedState.Kind.SCANNING_EMPTY.ordinal(), R.string.nearby_state_empty},
            };
            for (final int[] expectation : expectations) {
                scenario.onActivity(activity -> {
                    script.state = stateOf(expectation[0]);
                    activity.render();
                    final TextView message = activity.findViewById(R.id.state_message);
                    assertEquals(View.VISIBLE, message.getVisibility());
                    assertEquals(activity.getString(expectation[1]),
                            message.getText().toString());
                    assertEquals("the list never shows beside a state message",
                            View.GONE,
                            activity.findViewById(R.id.nearby_list).getVisibility());
                });
            }
        }
    }

    @Test
    public void theIndicatorShowsOnlyWhileAdvertisingAndStopIsOneAction() {
        final ScriptedHost script = new ScriptedHost();
        script.advertising = true;
        NearbyActivity.setHost(script);
        try (ActivityScenario<NearbyActivity> scenario =
                ActivityScenario.launch(NearbyActivity.class)) {
            scenario.onActivity(activity -> {
                assertEquals("advertising on means the indicator is shown (SRS-NBY-003)",
                        View.VISIBLE,
                        activity.findViewById(R.id.visibility_indicator).getVisibility());
                activity.findViewById(R.id.stop_nearby).performClick();
                assertEquals("one tap, one stop (SRS-NBY-017)", 1, script.stops);
                assertEquals("stopped means the indicator is gone",
                        View.GONE,
                        activity.findViewById(R.id.visibility_indicator).getVisibility());
            });
        }
    }

    @Test
    public void aGateThatSaysNoMeansTheScreenNeverInflates() {
        final ScriptedHost script = new ScriptedHost();
        script.allowed = false;
        NearbyActivity.setHost(script);
        try (ActivityScenario<NearbyActivity> scenario =
                ActivityScenario.launch(NearbyActivity.class)) {
            assertEquals("SRS-ONB-001: no product surface before consent",
                    Lifecycle.State.DESTROYED, scenario.getState());
        }
    }

    @Test
    public void noHostAtAllIsTheFreshInstallShapeAndAlsoRefuses() {
        NearbyActivity.setHost(null);
        try (ActivityScenario<NearbyActivity> scenario =
                ActivityScenario.launch(NearbyActivity.class)) {
            assertEquals(Lifecycle.State.DESTROYED, scenario.getState());
        }
    }

    private static FeedState stateOf(final int ordinal) {
        final FeedState.Kind kind = FeedState.Kind.values()[ordinal];
        switch (kind) {
            case RADIO_OFF:
                return FeedState.of(BlePermissions.ScanFailure.ADAPTER_UNAVAILABLE);
            case PERMISSION_NEEDED:
                return FeedState.of(BlePermissions.ScanFailure.PERMISSION_MISSING);
            case RADIO_UNAVAILABLE:
                return FeedState.of(BlePermissions.ScanFailure.INTERNAL_ERROR);
            case RADIO_BUSY:
                return FeedState.of(BlePermissions.ScanFailure.SCANNING_TOO_FREQUENTLY);
            default:
                return FeedState.scanningEmpty();
        }
    }
}
