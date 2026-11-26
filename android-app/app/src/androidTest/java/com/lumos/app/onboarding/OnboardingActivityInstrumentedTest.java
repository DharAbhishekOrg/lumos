/*
 * Lumos - android-app/app / The launcher, driven the way a person drives it, on a real Keystore and Room.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-001, SRS-ONB-002, SRS-ONB-006, SRS-ONB-008
 * ADRs: ADR-012
 * Threading: any
 * Notes: One flow per process, because the onboarding in progress lives in
 * the application: a fresh install opens on the age gate with Continue off;
 * choosing an adult date opens the documents; accepting them opens the
 * identity step; "Set up" makes the KEK, the key root file, the store, the
 * identity and the consent through the production DeviceInstallation and
 * opens the home tabs. A second launch goes straight home. The home launch is
 * intercepted by an ActivityMonitor, so only the launcher's own behaviour is
 * judged. The install's files and Keystore alias are removed before and after.
 */

package com.lumos.app.onboarding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.view.View;
import android.widget.DatePicker;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.lumos.app.AppBoot;
import com.lumos.app.LumosApplication;
import com.lumos.app.R;
import com.lumos.app.home.HomeActivity;
import com.lumos.app.nearby.NearbyActivity;
import com.lumos.app.store.LocalStore;
import com.lumos.app.store.room.LumosRoomDatabase;
import java.security.KeyStore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** The launcher, end to end. */
@RunWith(AndroidJUnit4.class)
public final class OnboardingActivityInstrumentedTest {

    private final Context context = ApplicationProvider.getApplicationContext();
    private Instrumentation.ActivityMonitor homeMonitor;

    private void wipeInstall() throws Exception {
        AppBoot.keyRootFile(context).delete();
        context.deleteDatabase(LumosRoomDatabase.DATABASE_FILE);
        final KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(AppBoot.KEK_ALIAS)) {
            keyStore.deleteEntry(AppBoot.KEK_ALIAS);
        }
    }

    @Before
    public void freshInstall() throws Exception {
        wipeInstall();
        final LumosApplication app = (LumosApplication) context;
        NearbyActivity.setHost(app.host());
        homeMonitor = new Instrumentation.ActivityMonitor(HomeActivity.class.getName(),
                new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true);
        InstrumentationRegistry.getInstrumentation().addMonitor(homeMonitor);
    }

    @After
    public void cleanUp() throws Exception {
        InstrumentationRegistry.getInstrumentation().removeMonitor(homeMonitor);
        NearbyActivity.setHost(null);
        wipeInstall();
    }

    private static void waitFor(final BooleanSupplier condition) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 15_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out");
            }
            Thread.sleep(100);
        }
    }

    private static String title(final ActivityScenario<OnboardingActivity> scenario) {
        final AtomicReference<String> text = new AtomicReference<>("");
        if (scenario.getState() != Lifecycle.State.DESTROYED) {
            scenario.onActivity(a -> text.set(((TextView) a.findViewById(R.id.onboarding_title)).getText()
                    .toString()));
        }
        return text.get();
    }

    private static boolean actionEnabled(final ActivityScenario<OnboardingActivity> scenario) {
        final AtomicBoolean enabled = new AtomicBoolean();
        scenario.onActivity(a -> {
            final View action = a.findViewById(R.id.onboarding_action);
            enabled.set(action.getVisibility() == View.VISIBLE && action.isEnabled());
        });
        return enabled.get();
    }

    @Test
    public void aPersonOnboardsThroughTheScreensAndTheNextLaunchGoesStraightHome() throws Exception {
        try (ActivityScenario<OnboardingActivity> scenario = ActivityScenario.launch(OnboardingActivity.class)) {
            final String ageTitle = context.getString(R.string.age_gate_title);
            waitFor(() -> ageTitle.equals(title(scenario)));
            assertFalse("Continue is off until a date is chosen", actionEnabled(scenario));

            scenario.onActivity(a -> ((DatePicker) a.findViewById(R.id.age_picker)).updateDate(1990, 0, 15));
            assertTrue("choosing a date turns Continue on", actionEnabled(scenario));
            scenario.onActivity(a -> a.findViewById(R.id.onboarding_action).performClick());

            assertEquals(context.getString(R.string.consent_title), title(scenario));
            scenario.onActivity(a -> {
                for (final int id : new int[] {R.id.doc_terms, R.id.doc_privacy, R.id.doc_community, R.id.doc_radio}) {
                    assertEquals("every document is offered", View.VISIBLE, a.findViewById(id).getVisibility());
                }
            });
            assertFalse("nothing is written before the last step", AppBoot.keyRootFile(context).exists());
            scenario.onActivity(a -> a.findViewById(R.id.onboarding_action).performClick());

            assertEquals(context.getString(R.string.identity_title), title(scenario));
            scenario.onActivity(a -> a.findViewById(R.id.onboarding_action).performClick());
            waitFor(() -> homeMonitor.getHits() == 1);
        }

        final LocalStore store = AppBoot.openOnboarded(context).orElseThrow();
        assertTrue("consent is stored with the identity", new ConsentRepository(store).load().isPresent());
        assertFalse("SRS-ONB-008: onboarding makes nobody discoverable",
                new DiscoveryVisibility(store).isVisibleNearby(System.currentTimeMillis()));
        assertTrue("entry is open after onboarding", ((LumosApplication) context).host().entryAllowed());

        try (ActivityScenario<OnboardingActivity> again = ActivityScenario.launch(OnboardingActivity.class)) {
            waitFor(() -> homeMonitor.getHits() == 2);
        }
    }
}
