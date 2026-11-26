/*
 * Lumos - android-app/app / The home shell: Nearby, Matches and Chats under one tab bar.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-UX-008, SRS-UX-012, SRS-RMT-001, SRS-ONB-001, TRS-UX-011, TRS-AND-003, TRS-AND-010
 * ADRs: ADR-011, ADR-012
 * Threading: main
 * Notes: Thin, like the other screens. Entry is gated exactly as Nearby's is:
 * no host, or a host whose onboarding machine has not opened the product
 * surfaces, means finish() before anything is inflated (SRS-ONB-001). The
 * Nearby tab is NearbyPanel over the same host; Matches and Chats are read on
 * the relay executor through HomeSource, built by HomeLists and drawn here,
 * refreshing while visible so a new message moves its chat to the top. The
 * Chats tab carries a badge with the total unread. A row opens its
 * conversation. The selected tab survives rotation. Kept out of the app
 * switcher's thumbnails like every screen (ADR-011). Discover and Profile
 * join the bar when they exist (CR-008's five tabs).
 */

package com.lumos.app.home;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.lumos.app.LumosApplication;
import com.lumos.app.R;
import com.lumos.app.conversation.ConversationActivity;
import com.lumos.app.nearby.NearbyActivity;
import com.lumos.app.nearby.NearbyHost;
import com.lumos.app.nearby.NearbyPanel;
import com.lumos.app.onboarding.AppSurface;
import com.lumos.app.safety.RecentsRedaction;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** The home shell. */
public final class HomeActivity extends AppCompatActivity {

    /** How often the visible lists re-read the store. */
    static final Duration REFRESH = Duration.ofSeconds(4);

    private static final String STATE_TAB = "tab";
    private static volatile HomeSource sourceOverride;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::refreshLoop;
    private HomeSource source;
    private Executor worker;
    private NearbyPanel nearby;
    private ActivityResultLauncher<String[]> permissions;
    private Consumer<Boolean> pendingPermission;
    private HomeAdapters.Matches matches;
    private HomeAdapters.Chats chats;
    private BottomNavigationView tabs;
    private boolean visible;

    /**
     * Replaces the chat stack with a scripted one, for the instrumented test.
     *
     * @param source the source, or null for the real one
     */
    public static void setSource(final HomeSource source) {
        sourceOverride = source;
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        RecentsRedaction.created(this, AppSurface.CONVERSATIONS);
        final NearbyHost host = NearbyActivity.host();
        if (host == null || !host.entryAllowed() || !(getApplication() instanceof LumosApplication app)) {
            // SRS-ONB-001: no product surface before consent; nothing was inflated.
            finish();
            return;
        }
        source = sourceOverride != null ? sourceOverride : new RuntimeHomeSource(app);
        worker = app.relayExecutor();
        setContentView(R.layout.activity_home);
        applyInsets();
        nearby = new NearbyPanel(findViewById(R.id.nearby_panel), host);
        // SRS-ONB-004: the Bluetooth permission is asked when the person turns
        // Visible Nearby on, and only then.
        permissions = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
            final Consumer<Boolean> answer = pendingPermission;
            pendingPermission = null;
            if (answer != null) {
                answer.accept(!result.isEmpty() && !result.containsValue(Boolean.FALSE));
            }
        });
        nearby.setPermissionAsker((names, granted) -> {
            pendingPermission = granted;
            permissions.launch(names);
        });
        matches = new HomeAdapters.Matches(this::open);
        chats = new HomeAdapters.Chats(this::open);
        bindList(R.id.matches_list, matches);
        bindList(R.id.chats_list, chats);
        ViewCompat.setAccessibilityHeading(findViewById(R.id.home_title), true);
        tabs = findViewById(R.id.home_tabs);
        tabs.setOnItemSelectedListener(item -> {
            show(item.getItemId());
            return true;
        });
        final int tab = savedInstanceState == null ? R.id.tab_nearby
                : savedInstanceState.getInt(STATE_TAB, R.id.tab_nearby);
        tabs.setSelectedItemId(tab);
        show(tab);
    }

    private void bindList(final int id, final RecyclerView.Adapter<?> adapter) {
        final RecyclerView list = findViewById(id);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
    }

    private void applyInsets() {
        final View root = findViewById(R.id.home_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            final Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    /** Shows one tab's page and names it in the title. */
    private void show(final int tab) {
        findViewById(R.id.nearby_panel).setVisibility(tab == R.id.tab_nearby ? View.VISIBLE : View.GONE);
        findViewById(R.id.matches_page).setVisibility(tab == R.id.tab_matches ? View.VISIBLE : View.GONE);
        findViewById(R.id.chats_page).setVisibility(tab == R.id.tab_chats ? View.VISIBLE : View.GONE);
        final int title;
        if (tab == R.id.tab_matches) {
            title = R.string.home_tab_matches;
        } else if (tab == R.id.tab_chats) {
            title = R.string.home_tab_chats;
        } else {
            title = R.string.home_tab_nearby;
            nearby.render();
        }
        final TextView heading = findViewById(R.id.home_title);
        heading.setText(title);
    }

    @Override
    protected void onSaveInstanceState(final Bundle out) {
        super.onSaveInstanceState(out);
        if (tabs != null) {
            out.putInt(STATE_TAB, tabs.getSelectedItemId());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        RecentsRedaction.leaving(this, AppSurface.CONVERSATIONS, false);
        if (source == null) {
            return;
        }
        visible = true;
        nearby.setForeground(true);
        reload();
        main.postDelayed(refresh, REFRESH.toMillis());
    }

    @Override
    protected void onPause() {
        RecentsRedaction.leaving(this, AppSurface.CONVERSATIONS, true);
        super.onPause();
        visible = false;
        main.removeCallbacks(refresh);
        if (nearby != null) {
            nearby.setForeground(false);
        }
    }

    private void refreshLoop() {
        if (visible) {
            nearby.render();
            reload();
            main.postDelayed(refresh, REFRESH.toMillis());
        }
    }

    /** Reads on the worker, draws on the main thread. */
    private void reload() {
        final Locale locale = getResources().getConfiguration().getLocales().get(0);
        worker.execute(() -> {
            final HomeLists.Lists lists = HomeLists.build(source.conversations(), System.currentTimeMillis(),
                    ZoneId.systemDefault(), locale);
            main.post(() -> draw(lists));
        });
    }

    private void draw(final HomeLists.Lists lists) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        matches.submitList(new ArrayList<>(lists.matches()));
        chats.submitList(new ArrayList<>(lists.chats()));
        findViewById(R.id.matches_empty).setVisibility(lists.matches().isEmpty() ? View.VISIBLE : View.GONE);
        findViewById(R.id.chats_empty).setVisibility(lists.chats().isEmpty() ? View.VISIBLE : View.GONE);
        int unread = 0;
        for (final HomeLists.ChatRow c : lists.chats()) {
            unread += c.unread();
        }
        if (unread > 0) {
            final BadgeDrawable badge = tabs.getOrCreateBadge(R.id.tab_chats);
            badge.setMaxNumber(HomeLists.MAX_UNREAD_SHOWN);
            badge.setNumber(unread);
        } else {
            tabs.removeBadge(R.id.tab_chats);
        }
    }

    private void open(final byte[] matchId) {
        startActivity(ConversationActivity.intent(this, matchId));
    }
}
