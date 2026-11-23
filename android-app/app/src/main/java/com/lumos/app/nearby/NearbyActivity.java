/*
 * Lumos - android-app/app / The Nearby screen: the controller's state, rendered.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-NBY-003, SRS-NBY-008, SRS-NBY-010, SRS-NBY-014, SRS-NBY-017,
 *             SRS-ONB-001
 * ADRs: ADR-002, ADR-011
 * Threading: main
 * Notes: The first Activity in the app, and deliberately the dumbest class
 * in the package: it inflates the Nearby panel and lets NearbyPanel show the
 * host's current FeedState - one message or one list, never a spinner
 * (SRS-NBY-014). The home shell shows the same panel as its Nearby tab. Entry is gated before any content inflates: no
 * host, or a host whose onboarding machine has not made this surface
 * reachable, means finish() (SRS-ONB-001 / AT-001). ADR-011's table is
 * consulted the moment the window exists - recents redaction is
 * unconditional for every surface, this one included (RecentsRedaction). The host seam is set
 * by the composition root (or the instrumented demo); a static setter is the
 * honest spelling of "there is no dependency framework here", and the
 * composition-root slice that replaces it is on the plan.
 */

package com.lumos.app.nearby;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.lumos.app.R;
import com.lumos.app.onboarding.AppSurface;
import com.lumos.app.safety.RecentsRedaction;

/** Renders the Nearby feed. */
public final class NearbyActivity extends AppCompatActivity {

    private static NearbyHost host;

    private NearbyPanel panel;

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

    /** @return the installed host, or null; the home shell renders its Nearby tab from the same one */
    public static NearbyHost host() {
        return host;
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // ADR-011 ruling 1: the app-switcher thumbnail is at-rest data.
        RecentsRedaction.created(this, AppSurface.NEARBY_DISCOVERY);
        if (host == null || !host.entryAllowed()) {
            // SRS-ONB-001: no product surface before consent. Nothing has
            // been inflated yet, so nothing was shown.
            finish();
            return;
        }
        setContentView(R.layout.panel_nearby);
        panel = new NearbyPanel(findViewById(R.id.nearby_panel), host);
        panel.render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        RecentsRedaction.leaving(this, AppSurface.NEARBY_DISCOVERY, false);
        if (panel != null) {
            panel.render();
        }
    }

    @Override
    protected void onPause() {
        RecentsRedaction.leaving(this, AppSurface.NEARBY_DISCOVERY, true);
        super.onPause();
    }

    /** Renders the host's current state; also called by the state demo. */
    public void render() {
        panel.render();
    }
}
