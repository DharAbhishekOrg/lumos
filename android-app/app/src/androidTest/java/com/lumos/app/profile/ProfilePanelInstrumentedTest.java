/*
 * Lumos - android-app/app / The Profile tab on a device: fields, saving, who-can-see and previews.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-PRO-001, SRS-PRO-002, SRS-PRO-003, SRS-PRO-008, SRS-PRO-010, SRS-NFR-A11Y-001
 * ADRs: ADR-003
 * Threading: test-only
 * Notes: Runs the real HomeActivity with a scripted profile source and a
 * Nearby host that lets the person in. Saving goes through the same panel
 * methods the dialogs call (the suite has no dialog-driving library), and the
 * stored result is read back from the source, so what is asserted is what
 * was saved, not what was typed.
 */

package com.lumos.app.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.lumos.app.R;
import com.lumos.app.home.HomeActivity;
import com.lumos.app.nearby.FeedState;
import com.lumos.app.nearby.NearbyActivity;
import com.lumos.app.nearby.NearbyHost;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/** The Profile tab over a scripted source. */
@RunWith(AndroidJUnit4.class)
public final class ProfilePanelInstrumentedTest {

    /** Lets the person in; nothing nearby. */
    private static final class OpenHost implements NearbyHost {
        @Override
        public boolean entryAllowed() {
            return true;
        }

        @Override
        public FeedState currentState() {
            return FeedState.notVisible();
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

    /** An in-memory profile store. */
    private static final class Memory implements ProfileSource {
        final AtomicReference<Editing> stored = new AtomicReference<>(
                new Editing(LocalProfile.empty(), ProfileFieldPolicy.privateByDefault()));

        @Override
        public Optional<Editing> load() {
            return Optional.of(stored.get());
        }

        @Override
        public boolean save(final Editing editing) {
            stored.set(editing);
            return true;
        }
    }

    private final Memory memory = new Memory();

    @After
    public void clear() {
        HomeActivity.setProfileSource(null);
        NearbyActivity.setHost(null);
    }

    private static void waitFor(final BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 50 && !condition.getAsBoolean(); i++) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Thread.sleep(100);
        }
        assertTrue("condition not met in time", condition.getAsBoolean());
    }

    private static boolean check(final ActivityScenario<HomeActivity> sc,
            final java.util.function.Predicate<HomeActivity> test) {
        final boolean[] ok = new boolean[1];
        sc.onActivity(a -> ok[0] = test.test(a));
        return ok[0];
    }

    private ActivityScenario<HomeActivity> openProfileTab() throws InterruptedException {
        NearbyActivity.setHost(new OpenHost());
        HomeActivity.setProfileSource(memory);
        final ActivityScenario<HomeActivity> sc = ActivityScenario.launch(HomeActivity.class);
        sc.onActivity(a -> ((BottomNavigationView) a.findViewById(R.id.home_tabs))
                .setSelectedItemId(R.id.tab_profile));
        waitFor(() -> check(sc, a -> ((ViewGroup) a.findViewById(R.id.profile_fields)).getChildCount()
                == ProfilePanel.EDITED.size()));
        return sc;
    }

    /** A fresh panel over the same views and source, loaded, to call what the dialogs call. */
    private ProfilePanel panel(final ActivityScenario<HomeActivity> sc) throws InterruptedException {
        final AtomicReference<ProfilePanel> made = new AtomicReference<>();
        sc.onActivity(a -> {
            final ProfilePanel panel = new ProfilePanel(a.findViewById(R.id.profile_page), memory, Runnable::run);
            panel.load();
            made.set(panel);
        });
        waitFor(() -> made.get().current() != null);
        return made.get();
    }

    @Test
    public void anEmptyProfileShowsEveryFieldPrivateAndSaysItCannotBeSeenYet() throws Exception {
        try (ActivityScenario<HomeActivity> sc = openProfileTab()) {
            sc.onActivity(a -> {
                assertEquals(View.VISIBLE, a.findViewById(R.id.profile_page).getVisibility());
                final TextView status = a.findViewById(R.id.profile_status);
                assertEquals(View.VISIBLE, status.getVisibility());
                assertEquals(a.getString(R.string.profile_incomplete), status.getText().toString());
                final ViewGroup fields = a.findViewById(R.id.profile_fields);
                final String onlyMatches = a.getString(R.string.profile_audience_label,
                        a.getString(R.string.profile_audience_match));
                for (int i = 0; i < fields.getChildCount(); i++) {
                    final View row = fields.getChildAt(i);
                    final TextView audience = row.findViewById(R.id.profile_field_audience);
                    assertEquals("private by default", onlyMatches, audience.getText().toString());
                    assertTrue("the audience line says what tapping does",
                            audience.getContentDescription().toString().startsWith(onlyMatches));
                    final float density = a.getResources().getDisplayMetrics().density;
                    assertTrue("48dp targets", row.findViewById(R.id.profile_field_edit).getHeight() >= 48 * density - 1
                            && audience.getHeight() >= 48 * density - 1);
                }
            });
        }
    }

    @Test
    public void nameAndAgeCompleteTheProfileAndDeclineIsKeptAsItself() throws Exception {
        try (ActivityScenario<HomeActivity> sc = openProfileTab()) {
            final ProfilePanel panel = panel(sc);
            sc.onActivity(a -> {
                panel.setValue(ProfileField.ALIAS, FieldValue.stated("  Asha "));
                panel.setValue(ProfileField.AGE_BAND, FieldValue.stated("25–34"));
                panel.setValue(ProfileField.ORIENTATION, FieldValue.preferNotToSay());
            });
            final LocalProfile saved = memory.stored.get().profile();
            assertEquals(FieldValue.stated("Asha"), saved.value(ProfileField.ALIAS));
            assertEquals(FieldValue.preferNotToSay(), saved.value(ProfileField.ORIENTATION));
            assertTrue(saved.isShowable());
            waitFor(() -> check(sc, a -> a.findViewById(R.id.profile_status).getVisibility() == View.GONE));
            sc.onActivity(a -> {
                final TextView orientation = ((ViewGroup) a.findViewById(R.id.profile_fields))
                        .getChildAt(ProfilePanel.EDITED.indexOf(ProfileField.ORIENTATION))
                        .findViewById(R.id.profile_field_value);
                assertEquals(a.getString(R.string.profile_value_declined), orientation.getText().toString());
            });
        }
    }

    @Test
    public void eachAudienceSeesExactlyWhatThePersonChose() throws Exception {
        try (ActivityScenario<HomeActivity> sc = openProfileTab()) {
            final ProfilePanel panel = panel(sc);
            sc.onActivity(a -> {
                panel.setValue(ProfileField.ALIAS, FieldValue.stated("Asha"));
                panel.setValue(ProfileField.AGE_BAND, FieldValue.stated("25–34"));
                panel.setValue(ProfileField.ORIENTATION, FieldValue.stated("Queer"));
                panel.setValue(ProfileField.PRONOUNS, FieldValue.preferNotToSay());
                panel.setAudience(ProfileField.ALIAS, ProfileFieldPolicy.Audience.NEARBY);
                panel.setAudience(ProfileField.AGE_BAND, ProfileFieldPolicy.Audience.REQUESTER);

                final String nearby = panel.previewText(ProfileFieldPolicy.Audience.NEARBY);
                assertTrue(nearby.contains("Asha"));
                assertFalse("age is for people they show interest in", nearby.contains("25–34"));
                assertFalse("sexuality stays with matches", nearby.contains("Queer"));

                final String requester = panel.previewText(ProfileFieldPolicy.Audience.REQUESTER);
                assertTrue(requester.contains("Asha") && requester.contains("25–34"));
                assertFalse(requester.contains("Queer"));

                final String match = panel.previewText(ProfileFieldPolicy.Audience.MATCH);
                assertTrue(match.contains("Queer"));
                assertFalse("a decline is never shown as a value",
                        match.contains(a.getString(R.string.profile_value_declined)));
            });
            assertEquals(ProfileFieldPolicy.Audience.NEARBY,
                    memory.stored.get().policy().threshold(ProfileField.ALIAS));
        }
    }
}
