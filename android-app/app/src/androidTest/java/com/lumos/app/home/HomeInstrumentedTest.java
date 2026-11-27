/*
 * Lumos - android-app/app / The home shell, on a device, against a scripted host and chat stack.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-UX-008, SRS-UX-007, SRS-ONB-001, SRS-RMT-005, SRS-RMT-014, SRS-NFR-A11Y-001,
 *             SRS-NFR-SEC-013
 * ADRs: ADR-011
 * Threading: test-only
 * Notes: The real activity, layouts, tab bar and adapters. Proves the gate
 * (no host, nothing shown), that the shell opens on Nearby, that each tab
 * shows its own page under its own title, that a chat row is one spoken
 * sentence with its unread count and the Chats tab carries the unread badge,
 * that a new match row speaks as one, that empty lists are sentences, and
 * that tapping a row opens that conversation, that the Chats page's
 * read-receipt switch shows the stored choice, saves a tap and puts itself
 * back when a save fails (SRS-RMT-014), and that leaving the screen
 * blanks its app-switcher thumbnail (ADR-011).
 */

package com.lumos.app.home;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Build;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.lumos.app.R;
import com.lumos.app.chat.ChatRecords;
import com.lumos.app.chat.ConversationList;
import com.lumos.app.conversation.ConversationActivity;
import com.lumos.app.nearby.FeedState;
import com.lumos.app.nearby.NearbyActivity;
import com.lumos.app.nearby.NearbyHost;
import com.lumos.protocol.chat.DeliveryMachine;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Drives HomeActivity with a scripted host and source. */
@RunWith(AndroidJUnit4.class)
public final class HomeInstrumentedTest {

    private static final byte[] CHAT = new byte[16];
    private static final byte[] MATCH = new byte[16];

    static {
        CHAT[0] = 1;
        MATCH[0] = 2;
    }

    /** A host that lets the shell open and shows an empty Nearby feed. */
    private static final class OpenHost implements NearbyHost {
        @Override
        public boolean entryAllowed() {
            return true;
        }

        @Override
        public FeedState currentState() {
            return FeedState.scanningEmpty();
        }

        @Override
        public boolean advertisingActive() {
            return false;
        }

        @Override
        public void hideCard(final String peerKey) {
            // Not used here.
        }

        @Override
        public void stopNearby() {
            // Not used here.
        }
    }

    private static final class Scripted implements HomeSource {
        final List<ConversationList.Summary> summaries = new CopyOnWriteArrayList<>();

        @Override
        public List<ConversationList.Summary> conversations() {
            return summaries;
        }
    }

    /** A chat stack with a read-receipt choice, recording every save. */
    private static final class WithSettings implements HomeSource {
        final AtomicReference<Boolean> choice = new AtomicReference<>(Boolean.TRUE);
        final List<Boolean> saves = new CopyOnWriteArrayList<>();
        volatile boolean failSaves;

        @Override
        public List<ConversationList.Summary> conversations() {
            return List.of();
        }

        @Override
        public Optional<Boolean> readReceipts() {
            return Optional.of(choice.get());
        }

        @Override
        public boolean setReadReceipts(final boolean enabled) {
            saves.add(enabled);
            if (failSaves) {
                return false;
            }
            choice.set(enabled);
            return true;
        }
    }

    private static ConversationList.Summary chat(final String text, final int unread) {
        final long now = System.currentTimeMillis();
        final ChatRecords.Message m = new ChatRecords.Message(ChatRecords.newMessageId(), CHAT, false,
                text.getBytes(StandardCharsets.UTF_8), now, 1, DeliveryMachine.State.DELIVERED, false, false,
                new byte[0], new byte[0], new byte[0]);
        return new ConversationList.Summary(CHAT, Optional.of(m), unread);
    }

    private static void waitFor(final BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 50 && !condition.getAsBoolean(); i++) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Thread.sleep(100);
        }
        assertTrue("condition not met in time", condition.getAsBoolean());
    }

    private static boolean onActivity(final ActivityScenario<HomeActivity> sc,
            final java.util.function.Predicate<HomeActivity> check) {
        final boolean[] ok = new boolean[1];
        sc.onActivity(a -> ok[0] = check.test(a));
        return ok[0];
    }

    @After
    public void clear() {
        NearbyActivity.setHost(null);
        HomeActivity.setSource(null);
    }

    @Test
    public void withoutTheOnboardingGateNothingOpens() {
        NearbyActivity.setHost(null);
        HomeActivity.setSource(new Scripted());
        try (ActivityScenario<HomeActivity> sc = ActivityScenario.launch(HomeActivity.class)) {
            assertEquals(Lifecycle.State.DESTROYED, sc.getState());
        }
    }

    @Test
    public void eachTabShowsItsOwnPageAndRowsSpeakAsOneSentence() throws Exception {
        final Scripted s = new Scripted();
        s.summaries.add(chat("Jazz at nine?", 3));
        s.summaries.add(new ConversationList.Summary(MATCH, Optional.empty(), 0));
        NearbyActivity.setHost(new OpenHost());
        HomeActivity.setSource(s);
        try (ActivityScenario<HomeActivity> sc = ActivityScenario.launch(HomeActivity.class)) {
            sc.onActivity(a -> {
                assertEquals(a.getString(R.string.home_tab_nearby),
                        ((TextView) a.findViewById(R.id.home_title)).getText().toString());
                assertEquals(View.VISIBLE, a.findViewById(R.id.nearby_panel).getVisibility());
                assertEquals(View.GONE, a.findViewById(R.id.chats_page).getVisibility());
            });
            waitFor(() -> onActivity(sc, a -> ((BottomNavigationView) a.findViewById(R.id.home_tabs))
                    .getBadge(R.id.tab_chats) != null));
            sc.onActivity(a -> {
                final BottomNavigationView tabs = a.findViewById(R.id.home_tabs);
                assertEquals("the Chats tab carries the unread total", 3, tabs.getBadge(R.id.tab_chats).getNumber());
                tabs.setSelectedItemId(R.id.tab_chats);
                assertEquals(a.getString(R.string.home_tab_chats),
                        ((TextView) a.findViewById(R.id.home_title)).getText().toString());
                assertEquals(View.GONE, a.findViewById(R.id.nearby_panel).getVisibility());
                assertEquals(View.VISIBLE, a.findViewById(R.id.chats_page).getVisibility());
            });
            waitFor(() -> onActivity(sc, a -> ((RecyclerView) a.findViewById(R.id.chats_list)).getChildCount() == 1));
            sc.onActivity(a -> {
                final View row = ((RecyclerView) a.findViewById(R.id.chats_list)).getChildAt(0);
                final String spoken = row.getContentDescription().toString();
                final String name = a.getString(R.string.conversation_fallback_name);
                assertTrue(spoken, spoken.startsWith(name + ". Jazz at nine?. "));
                assertTrue(spoken, spoken.endsWith(a.getResources()
                        .getQuantityString(R.plurals.chats_unread_spoken, 3, 3)));
                assertEquals("3", ((TextView) row.findViewById(R.id.chat_unread)).getText().toString());
                assertEquals(View.GONE, a.findViewById(R.id.chats_empty).getVisibility());

                ((BottomNavigationView) a.findViewById(R.id.home_tabs)).setSelectedItemId(R.id.tab_matches);
                assertEquals(View.VISIBLE, a.findViewById(R.id.matches_page).getVisibility());
            });
            waitFor(() -> onActivity(sc, a -> ((RecyclerView) a.findViewById(R.id.matches_list)).getChildCount() == 1));
            sc.onActivity(a -> {
                final View row = ((RecyclerView) a.findViewById(R.id.matches_list)).getChildAt(0);
                assertEquals(a.getString(R.string.matches_spoken, a.getString(R.string.conversation_fallback_name)),
                        row.getContentDescription().toString());
            });
        }
    }

    @Test
    public void emptyListsAreSentencesAndARowOpensItsConversation() throws Exception {
        final Scripted s = new Scripted();
        NearbyActivity.setHost(new OpenHost());
        HomeActivity.setSource(s);
        try (ActivityScenario<HomeActivity> sc = ActivityScenario.launch(HomeActivity.class)) {
            sc.onActivity(a -> ((BottomNavigationView) a.findViewById(R.id.home_tabs)).setSelectedItemId(R.id.tab_chats));
            waitFor(() -> onActivity(sc, a -> a.findViewById(R.id.chats_empty).getVisibility() == View.VISIBLE));
            sc.onActivity(a -> assertNull(((BottomNavigationView) a.findViewById(R.id.home_tabs))
                    .getBadge(R.id.tab_chats)));

            final List<Intent> started = new CopyOnWriteArrayList<>();
            final Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
                @Override
                public Instrumentation.ActivityResult onStartActivity(final Intent intent) {
                    started.add(intent);
                    return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
                }
            };
            InstrumentationRegistry.getInstrumentation().addMonitor(monitor);
            try {
                s.summaries.add(chat("Hello", 0));
                waitFor(() -> onActivity(sc, a -> ((RecyclerView) a.findViewById(R.id.chats_list)).getChildCount() == 1));
                sc.onActivity(a -> ((RecyclerView) a.findViewById(R.id.chats_list)).getChildAt(0).performClick());
                waitFor(() -> !started.isEmpty());
                final Intent intent = started.get(0);
                assertNotNull(intent.getComponent());
                assertEquals(ConversationActivity.class.getName(), intent.getComponent().getClassName());
                assertArrayEquals("the row opened its own conversation", CHAT,
                        intent.getByteArrayExtra(ConversationActivity.EXTRA_MATCH_ID));
            } finally {
                InstrumentationRegistry.getInstrumentation().removeMonitor(monitor);
            }
        }
    }

    @Test
    public void theReadReceiptSwitchShowsTheStoredChoiceAndSavesATap() throws Exception {
        final WithSettings s = new WithSettings();
        NearbyActivity.setHost(new OpenHost());
        HomeActivity.setSource(s);
        try (ActivityScenario<HomeActivity> sc = ActivityScenario.launch(HomeActivity.class)) {
            sc.onActivity(a -> ((BottomNavigationView) a.findViewById(R.id.home_tabs)).setSelectedItemId(R.id.tab_chats));
            waitFor(() -> onActivity(sc, a -> a.findViewById(R.id.chats_read_receipts).getVisibility() == View.VISIBLE));
            sc.onActivity(a -> {
                final MaterialSwitch toggle = a.findViewById(R.id.chats_read_receipts);
                assertTrue("shows the stored choice", toggle.isChecked());
                assertEquals(a.getString(R.string.read_receipts_switch), toggle.getText().toString());
                assertEquals(View.VISIBLE, a.findViewById(R.id.chats_read_receipts_detail).getVisibility());
                assertTrue("a touch target of at least 48dp (SRS-NFR-A11Y-001)",
                        toggle.getHeight() >= 48 * a.getResources().getDisplayMetrics().density - 1);
                toggle.performClick();
            });
            waitFor(() -> s.saves.contains(Boolean.FALSE));
            waitFor(() -> !s.choice.get());
            sc.onActivity(a -> assertTrue("the switch stays off once stored",
                    !((MaterialSwitch) a.findViewById(R.id.chats_read_receipts)).isChecked()));

            s.failSaves = true;
            sc.onActivity(a -> ((MaterialSwitch) a.findViewById(R.id.chats_read_receipts)).performClick());
            waitFor(() -> s.saves.size() == 2);
            waitFor(() -> onActivity(sc, a -> !((MaterialSwitch) a.findViewById(R.id.chats_read_receipts))
                    .isChecked()));
            assertTrue("a save that failed left the stored choice alone", !s.choice.get());
        }
    }

    @Test
    public void withNoChatStackTheSwitchIsNotShown() throws Exception {
        NearbyActivity.setHost(new OpenHost());
        HomeActivity.setSource(new Scripted());
        try (ActivityScenario<HomeActivity> sc = ActivityScenario.launch(HomeActivity.class)) {
            sc.onActivity(a -> ((BottomNavigationView) a.findViewById(R.id.home_tabs)).setSelectedItemId(R.id.tab_chats));
            waitFor(() -> onActivity(sc, a -> a.findViewById(R.id.chats_empty).getVisibility() == View.VISIBLE));
            sc.onActivity(a -> {
                assertEquals(View.GONE, a.findViewById(R.id.chats_read_receipts).getVisibility());
                assertEquals(View.GONE, a.findViewById(R.id.chats_read_receipts_detail).getVisibility());
            });
        }
    }

    @Test
    public void leavingTheScreenBlanksItsAppSwitcherThumbnail() {
        // ADR-011 ruling 1 below API 33 (the CI emulator is API 30): FLAG_SECURE only while leaving.
        Assume.assumeTrue("API 33+ uses setRecentsScreenshotEnabled, which has no getter",
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU);
        NearbyActivity.setHost(new OpenHost());
        HomeActivity.setSource(new Scripted());
        try (ActivityScenario<HomeActivity> sc = ActivityScenario.launch(HomeActivity.class)) {
            sc.onActivity(a -> assertEquals("screenshots in the app are never blocked", 0,
                    a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE));
            sc.moveToState(Lifecycle.State.STARTED);
            sc.onActivity(a -> assertTrue("the thumbnail taken on the way out is blank",
                    (a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0));
            sc.moveToState(Lifecycle.State.RESUMED);
            sc.onActivity(a -> assertEquals(0, a.getWindow().getAttributes().flags
                    & WindowManager.LayoutParams.FLAG_SECURE));
        }
    }
}
