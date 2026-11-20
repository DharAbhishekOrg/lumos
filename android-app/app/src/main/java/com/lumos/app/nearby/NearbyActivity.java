/*
 * Lumos - android-app/app / The Nearby screen: the controller's state, rendered.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-NBY-003, SRS-NBY-008, SRS-NBY-010, SRS-NBY-014, SRS-NBY-017,
 *             SRS-ONB-001
 * ADRs: ADR-002, ADR-011
 * Threading: main
 * Notes: The first Activity in the app, and deliberately the dumbest class
 * in the package: it inflates one layout, asks the host for the current
 * FeedState and shows exactly that - one message or one list, never a
 * spinner (SRS-NBY-014). Entry is gated before any content inflates: no
 * host, or a host whose onboarding machine has not made this surface
 * reachable, means finish() (SRS-ONB-001 / AT-001). ADR-011's table is
 * consulted the moment the window exists - recents redaction is
 * unconditional for every surface, this one included. The host seam is set
 * by the composition root (or the instrumented demo); a static setter is the
 * honest spelling of "there is no dependency framework here", and the
 * composition-root slice that replaces it is on the plan.
 */

package com.lumos.app.nearby;

import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.lumos.app.R;
import com.lumos.app.onboarding.AppSurface;
import com.lumos.app.safety.ScreenPrivacyPolicy;

/** Renders the Nearby feed. */
public final class NearbyActivity extends AppCompatActivity {

    private static NearbyHost host;

    private NearbyCardAdapter adapter;

    /**
     * Installs the host the next created activity will render from. Called
     * by the composition root at process start, and by the instrumented
     * state demo.
     *
     * @param nearbyHost the running app, or null to clear
     */
    public static void setHost(final NearbyHost nearbyHost) {
        host = nearbyHost;
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (ScreenPrivacyPolicy.recentsRedacted(AppSurface.NEARBY_DISCOVERY)
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // ADR-011 ruling 1: the app-switcher thumbnail is at-rest data.
            // Below API 33 the same ruling is held by the onPause-scoped
            // FLAG_SECURE in redactWhileLeaving() - the scoping the ADR names,
            // which redacts the snapshot the OS takes as the app leaves while
            // never blocking a screenshot the user takes in the app
            // (ruling 3, SRS-SAF-004).
            setRecentsScreenshotEnabled(false);
        }
        if (host == null || !host.entryAllowed()) {
            // SRS-ONB-001: no product surface before consent. Nothing has
            // been inflated yet, so nothing was shown.
            finish();
            return;
        }
        setContentView(R.layout.activity_nearby);
        adapter = new NearbyCardAdapter(host);
        final RecyclerView list = findViewById(R.id.nearby_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        findViewById(R.id.stop_nearby).setOnClickListener(view -> {
            host.stopNearby();
            render();
        });
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        redactWhileLeaving(false);
        if (host != null) {
            render();
        }
    }

    @Override
    protected void onPause() {
        redactWhileLeaving(true);
        super.onPause();
    }

    private void redactWhileLeaving(final boolean leaving) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                || !ScreenPrivacyPolicy.recentsRedacted(AppSurface.NEARBY_DISCOVERY)) {
            return;
        }
        if (leaving) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    /** Renders the host's current state; also called by the state demo. */
    public void render() {
        final FeedState state = host.currentState();
        final TextView message = findViewById(R.id.state_message);
        final RecyclerView list = findViewById(R.id.nearby_list);
        findViewById(R.id.visibility_indicator)
                .setVisibility(host.advertisingActive() ? View.VISIBLE : View.GONE);
        if (state.kind() == FeedState.Kind.CARDS) {
            message.setVisibility(View.GONE);
            list.setVisibility(View.VISIBLE);
            adapter.submit(state.cards());
            return;
        }
        list.setVisibility(View.GONE);
        message.setVisibility(View.VISIBLE);
        message.setText(messageFor(state.kind()));
    }

    private static int messageFor(final FeedState.Kind kind) {
        switch (kind) {
            case RADIO_OFF:
                return R.string.nearby_state_radio_off;
            case PERMISSION_NEEDED:
                return R.string.nearby_state_permission_needed;
            case RADIO_UNAVAILABLE:
                return R.string.nearby_state_radio_unavailable;
            case RADIO_BUSY:
                return R.string.nearby_state_radio_busy;
            case SCANNING_EMPTY:
            default:
                return R.string.nearby_state_empty;
        }
    }
}
