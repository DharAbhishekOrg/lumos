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

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;
import com.lumos.app.R;
import com.lumos.app.ble.BlePermissions;
import com.lumos.app.onboarding.DiscoveryVisibility;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

/** Renders a NearbyHost's state into panel_nearby. */
public final class NearbyPanel {

    /** Asks the person for runtime permissions, for the activity that owns the panel. */
    public interface PermissionAsker {
        /**
         * Asks for the permissions and reports whether all were granted.
         *
         * @param permissions the Android permission names
         * @param granted receives the answer on the main thread
         */
        void ask(String[] permissions, Consumer<Boolean> granted);
    }

    private static final int SECONDS_PER_MINUTE = 60;
    private static final int MINUTES_PER_HOUR = 60;

    private final View root;
    private final NearbyHost host;
    private final NearbyCardAdapter adapter;
    private PermissionAsker asker;

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
        root.findViewById(R.id.vn_hour).setOnClickListener(view ->
                turnOn(DiscoveryVisibility.Selection.defaultSession()));
        root.findViewById(R.id.vn_custom).setOnClickListener(view -> chooseTime());
        root.findViewById(R.id.vn_off).setOnClickListener(view -> {
            final VisibleNearbyController vn = host.visibleNearby();
            if (vn != null) {
                vn.turnOff();
            }
            render();
        });
        host.setOnVisibilityChanged(this::render);
    }

    /**
     * Sets who asks for the Bluetooth permission when the person turns
     * Visible Nearby on (SRS-ONB-004: asked then, never at launch).
     *
     * @param permissionAsker the owning activity's asker, or null
     */
    public void setPermissionAsker(final PermissionAsker permissionAsker) {
        this.asker = permissionAsker;
    }

    /**
     * The owning activity came on screen or left it; the radio follows.
     *
     * @param active true while on screen
     */
    public void setForeground(final boolean active) {
        final VisibleNearbyController vn = host.visibleNearby();
        if (vn != null) {
            vn.setForeground(active);
        }
        if (active) {
            render();
        }
    }

    private void turnOn(final DiscoveryVisibility.Selection selection) {
        final VisibleNearbyController vn = host.visibleNearby();
        if (vn == null) {
            return;
        }
        final Context context = root.getContext();
        // Being visible now also means looking and connecting (scan, connect,
        // advertise on 31+; fine location below), so ask for all of them at once.
        final List<String> missing = new ArrayList<>();
        for (final String permission : BlePermissions.requiredRuntimePermissions(Build.VERSION.SDK_INT)) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        if (missing.isEmpty() || asker == null) {
            vn.turnOn(selection);
            render();
            return;
        }
        asker.ask(missing.toArray(new String[0]), granted -> {
            if (granted) {
                vn.turnOn(selection);
            } else {
                vn.permissionRefused();
            }
            render();
        });
    }

    private void chooseTime() {
        final Context context = root.getContext();
        final Slider slider = new Slider(context);
        slider.setValueFrom(DiscoveryVisibility.Selection.minimumSeconds() / (float) SECONDS_PER_MINUTE);
        slider.setValueTo(DiscoveryVisibility.Selection.maximumSeconds() / (float) SECONDS_PER_MINUTE);
        slider.setStepSize(DiscoveryVisibility.Selection.minimumSeconds() / (float) SECONDS_PER_MINUTE);
        slider.setValue(DiscoveryVisibility.Selection.defaultSession().durationSeconds() / (float) SECONDS_PER_MINUTE);
        slider.setLabelFormatter(value -> label(context, (int) value));
        final TextView note = new TextView(context);
        note.setText(R.string.visible_nearby_custom_note);
        final LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        final int pad = (int) (24 * context.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(slider);
        box.addView(note);
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.visible_nearby_duration_custom)
                .setView(box)
                .setPositiveButton(R.string.visibility_turn_on, (dialog, which) ->
                        turnOn(DiscoveryVisibility.Selection.customDuration((int) slider.getValue() * SECONDS_PER_MINUTE)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static String label(final Context context, final int minutes) {
        return context.getString(R.string.visible_nearby_custom_value, minutes / MINUTES_PER_HOUR,
                minutes % MINUTES_PER_HOUR);
    }

    private void renderVisibleNearby() {
        final View section = root.findViewById(R.id.visible_nearby_section);
        final VisibleNearbyController vn = host.visibleNearby();
        if (vn == null) {
            section.setVisibility(View.GONE);
            return;
        }
        vn.refresh();
        section.setVisibility(View.VISIBLE);
        final Context context = root.getContext();
        final TextView status = root.findViewById(R.id.vn_status);
        if (vn.visible()) {
            final String until = DateFormat.getTimeFormat(context).format(new Date(vn.expiresAtMillis()));
            status.setText(context.getString(R.string.visible_nearby_indicator_until, until));
        } else if (vn.endedOnItsOwn()) {
            status.setText(R.string.visible_nearby_expired);
        } else {
            status.setText(R.string.visibility_off_body);
        }
        root.findViewById(R.id.vn_hour).setVisibility(vn.visible() ? View.GONE : View.VISIBLE);
        root.findViewById(R.id.vn_custom).setVisibility(vn.visible() ? View.GONE : View.VISIBLE);
        root.findViewById(R.id.vn_off).setVisibility(vn.visible() ? View.VISIBLE : View.GONE);
        final TextView problem = root.findViewById(R.id.vn_problem);
        if (vn.problem() == null) {
            problem.setVisibility(View.GONE);
        } else {
            problem.setVisibility(View.VISIBLE);
            problem.setText(problemText(vn.problem()));
        }
    }

    private static int problemText(final VisibleNearbyController.RadioProblem problem) {
        switch (problem) {
            case BLUETOOTH_OFF:
                return R.string.visible_nearby_problem_bluetooth_off;
            case NOT_ALLOWED:
                return R.string.visible_nearby_problem_not_allowed;
            case UNSUPPORTED:
                return R.string.visible_nearby_problem_unsupported;
            case REFUSED:
            default:
                return R.string.visible_nearby_problem_refused;
        }
    }

    /** Renders the host's current state. */
    public void render() {
        renderVisibleNearby();
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
            case NOT_YET:
                return R.string.nearby_state_not_yet;
            case NOT_VISIBLE:
                return R.string.nearby_state_not_visible;
            case SCANNING_EMPTY:
            default:
                return R.string.nearby_state_empty;
        }
    }
}
