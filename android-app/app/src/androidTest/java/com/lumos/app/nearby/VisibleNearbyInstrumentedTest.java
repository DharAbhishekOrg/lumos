/*
 * Lumos - android-app/app / Visible Nearby's buttons, tapped on a real screen.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-NBY-001, SRS-NBY-002, SRS-NBY-003, SRS-NBY-017
 * ADRs: ADR-002
 * Threading: any
 * Notes: The Nearby panel over a real VisibleNearbyController and a real
 * sealed store (Room, software KEK), with the radio scripted so the test
 * judges the screen and the controller's orders to it: "For an hour" starts
 * it and shows the end time and the indicator; "Turn off" and the one-action
 * stop both end it at once. The CI emulator is API 30, where advertising
 * needs no runtime permission, so no prompt interrupts the taps.
 */

package com.lumos.app.nearby;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.lumos.app.R;
import com.lumos.app.ble.BleAdvertiser;
import com.lumos.app.data.DataKeyHierarchy;
import com.lumos.app.keystore.SoftwareKekStore;
import com.lumos.app.onboarding.DiscoveryVisibility;
import com.lumos.app.store.LocalStore;
import com.lumos.app.store.room.LumosRoomDatabase;
import com.lumos.app.store.room.RoomSqlDatabase;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Visible Nearby on the Nearby screen. */
@RunWith(AndroidJUnit4.class)
public final class VisibleNearbyInstrumentedTest {

    /** A radio that records what it was told. */
    private static final class ScriptedAdvertiser implements BleAdvertiser {
        volatile boolean running;

        @Override
        public void startHint(final byte[] serviceData, final byte[] scanResponse, final Listener listener) {
            running = true;
        }

        @Override
        public void startDegraded(final Listener listener) {
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }
    }

    /** A host with a real Visible Nearby controller. */
    private static final class Host implements NearbyHost {
        private final VisibleNearbyController controller;

        Host(final VisibleNearbyController controller) {
            this.controller = controller;
        }

        @Override
        public boolean entryAllowed() {
            return true;
        }

        @Override
        public FeedState currentState() {
            return FeedState.notYet();
        }

        @Override
        public boolean advertisingActive() {
            return controller.advertising();
        }

        @Override
        public void hideCard(final String peerKey) {
            // No cards in this test.
        }

        @Override
        public void stopNearby() {
            controller.turnOff();
        }

        @Override
        public VisibleNearbyController visibleNearby() {
            return controller;
        }
    }

    private final Context context = ApplicationProvider.getApplicationContext();
    private final ScriptedAdvertiser radio = new ScriptedAdvertiser();
    private LumosRoomDatabase room;

    @Before
    public void open() throws Exception {
        context.deleteDatabase(LumosRoomDatabase.DATABASE_FILE);
        room = LumosRoomDatabase.open(context);
        final LocalStore store = LocalStore.open(RoomSqlDatabase.wrapping(room),
                DataKeyHierarchy.create(SoftwareKekStore.create()).deriveKey(DataKeyHierarchy.DataDomain.DATABASE),
                () -> Long.MAX_VALUE, System::currentTimeMillis);
        NearbyActivity.setHost(new Host(new VisibleNearbyController(new DiscoveryVisibility(store), radio,
                System::currentTimeMillis, failure -> { })));
    }

    @After
    public void close() {
        NearbyActivity.setHost(null);
        room.close();
        context.deleteDatabase(LumosRoomDatabase.DATABASE_FILE);
    }

    private static String text(final ActivityScenario<NearbyActivity> scenario, final int id) {
        final AtomicReference<String> out = new AtomicReference<>();
        scenario.onActivity(a -> out.set(((TextView) a.findViewById(id)).getText().toString()));
        return out.get();
    }

    private static boolean shown(final ActivityScenario<NearbyActivity> scenario, final int id) {
        final AtomicReference<Boolean> out = new AtomicReference<>();
        scenario.onActivity(a -> out.set(a.findViewById(id).getVisibility() == View.VISIBLE));
        return out.get();
    }

    @Test
    public void forAnHourTurnsItOnWithItsEndAndBothStopsTurnItOffAtOnce() {
        try (ActivityScenario<NearbyActivity> scenario = ActivityScenario.launch(NearbyActivity.class)) {
            assertTrue("the section shows", shown(scenario, R.id.visible_nearby_section));
            assertEquals(context.getString(R.string.visibility_off_body), text(scenario, R.id.vn_status));
            assertFalse("off until chosen", radio.running);
            assertFalse(shown(scenario, R.id.visibility_indicator));

            scenario.onActivity(a -> a.findViewById(R.id.vn_hour).performClick());
            assertTrue("the tap starts the radio", radio.running);
            final String until = context.getString(R.string.visible_nearby_indicator_until, "");
            assertTrue("the end time shows", text(scenario, R.id.vn_status).startsWith(until.trim()));
            assertTrue("the indicator shows while advertising", shown(scenario, R.id.visibility_indicator));
            assertTrue(shown(scenario, R.id.vn_off));
            assertFalse("no second way on while on", shown(scenario, R.id.vn_hour));

            scenario.onActivity(a -> a.findViewById(R.id.vn_off).performClick());
            assertFalse("Turn off stops it at once", radio.running);
            assertEquals(context.getString(R.string.visibility_off_body), text(scenario, R.id.vn_status));

            scenario.onActivity(a -> a.findViewById(R.id.vn_hour).performClick());
            assertTrue(radio.running);
            scenario.onActivity(a -> a.findViewById(R.id.stop_nearby).performClick());
            assertFalse("the one-action stop ends it too (SRS-NBY-017)", radio.running);
            assertFalse(shown(scenario, R.id.visibility_indicator));
        }
    }
}
