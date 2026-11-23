/*
 * Lumos - android-app/app / The Nearby feed bound to its views, for any screen that shows it.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-NBY-003, SRS-NBY-008, SRS-NBY-010, SRS-NBY-014, SRS-NBY-017
 * ADRs: ADR-002
 * Threading: main
 * Notes: Extracted from NearbyActivity (CS2c) so the home shell's Nearby tab
 * and the standalone screen render the host's state the same way: one
 * message or one list, never a spinner (SRS-NBY-014), the indicator only
 * while advertising is really on (SRS-NBY-003), and the one-action stop
 * (SRS-NBY-017). It holds no state of its own beyond the adapter.
 */

package com.lumos.app.nearby;

import android.view.View;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.lumos.app.R;

/** Renders a NearbyHost's state into panel_nearby. */
public final class NearbyPanel {

    private final View root;
    private final NearbyHost host;
    private final NearbyCardAdapter adapter;

    /**
     * Binds the panel.
     *
     * @param root the inflated panel_nearby
     * @param host the running app
     */
    public NearbyPanel(final View root, final NearbyHost host) {
        this.root = root;
        this.host = host;
        this.adapter = new NearbyCardAdapter(host);
        final RecyclerView list = root.findViewById(R.id.nearby_list);
        list.setLayoutManager(new LinearLayoutManager(root.getContext()));
        list.setAdapter(adapter);
        root.findViewById(R.id.stop_nearby).setOnClickListener(view -> {
            host.stopNearby();
            render();
        });
    }

    /** Renders the host's current state. */
    public void render() {
        final FeedState state = host.currentState();
        final TextView message = root.findViewById(R.id.state_message);
        final RecyclerView list = root.findViewById(R.id.nearby_list);
        root.findViewById(R.id.visibility_indicator)
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
