/*
 * Lumos - android-app/app / The Profile tab's photos: add, main photo, remove, and who sees them.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-PRO-001, SRS-PRO-003, SRS-PRO-008, SRS-PRO-009, SRS-NFR-A11Y-001
 * ADRs: ADR-003
 * Threading: test-only
 * Notes: Over an in-memory profile and an in-memory vault, with the real
 * re-encoder and sanitiser. The strip's package methods are what its picker
 * and dialogs call. Photos are private to matches by default, so "see what
 * others see" names the main photo for a match and not for someone nearby.
 */

package com.lumos.app.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.lumos.app.R;
import com.lumos.app.home.HomeActivity;
import com.lumos.app.media.BitmapReencoder;
import com.lumos.app.nearby.FeedState;
import com.lumos.app.nearby.NearbyActivity;
import com.lumos.app.nearby.NearbyHost;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/** The photo strip over scripted stores. */
@RunWith(AndroidJUnit4.class)
public final class ProfilePhotoStripInstrumentedTest {

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
        final AtomicReference<Editing> stored = new AtomicReference<>(new Editing(LocalProfile.empty()
                .with(ProfileField.ALIAS, FieldValue.stated("Asha"))
                .with(ProfileField.AGE_BAND, FieldValue.stated("25–34")),
                ProfileFieldPolicy.privateByDefault()));

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

    /** An in-memory vault. */
    private static final class Vault implements ProfilePhotos.Vault {
        final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        private int next;

        @Override
        public synchronized String put(final byte[] sanitised) {
            final String id = String.format(java.util.Locale.ROOT, "%032x", ++next);
            objects.put(id, sanitised.clone());
            return id;
        }

        @Override
        public byte[] get(final String objectId) throws IOException {
            final byte[] bytes = objects.get(objectId);
            if (bytes == null) {
                throw new IOException("absent");
            }
            return bytes.clone();
        }

        @Override
        public void delete(final String objectId) {
            objects.remove(objectId);
        }

        @Override
        public List<String> list() {
            return new ArrayList<>(objects.keySet());
        }
    }

    private final Memory memory = new Memory();
    private final Vault vault = new Vault();

    @After
    public void clear() {
        HomeActivity.setProfileSource(null);
        HomeActivity.setProfilePhotos(null);
        NearbyActivity.setHost(null);
    }

    private static void waitFor(final BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 80 && !condition.getAsBoolean(); i++) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Thread.sleep(100);
        }
        assertTrue("condition not met in time", condition.getAsBoolean());
    }

    private static int thumbs(final ActivityScenario<HomeActivity> sc) {
        final int[] count = new int[1];
        sc.onActivity(a -> count[0] = ((ViewGroup) a.findViewById(R.id.profile_photos)).getChildCount());
        return count[0];
    }

    private static byte[] jpeg(final int color) {
        final Bitmap bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out));
        bitmap.recycle();
        return out.toByteArray();
    }

    private ActivityScenario<HomeActivity> openProfileTab() throws InterruptedException {
        NearbyActivity.setHost(new OpenHost());
        HomeActivity.setProfileSource(memory);
        HomeActivity.setProfilePhotos(new ProfilePhotos(new BitmapReencoder(), vault, memory));
        final ActivityScenario<HomeActivity> sc = ActivityScenario.launch(HomeActivity.class);
        sc.onActivity(a -> ((BottomNavigationView) a.findViewById(R.id.home_tabs))
                .setSelectedItemId(R.id.tab_profile));
        waitFor(() -> {
            final boolean[] ready = new boolean[1];
            sc.onActivity(a -> ready[0] = a.photoStrip() != null
                    && ((ViewGroup) a.findViewById(R.id.profile_fields)).getChildCount() > 0);
            return ready[0];
        });
        return sc;
    }

    private static ProfilePhotoStrip strip(final ActivityScenario<HomeActivity> sc) {
        final AtomicReference<ProfilePhotoStrip> strip = new AtomicReference<>();
        sc.onActivity(a -> strip.set(a.photoStrip()));
        return strip.get();
    }

    private List<String> stored() {
        final List<String> ids = new ArrayList<>();
        for (final ProfileMedia media : memory.stored.get().profile().media()) {
            ids.add(media.objectId());
        }
        return ids;
    }

    @Test
    public void photosShowWithTheirPlaceAndTheFirstIsTheMainOne() throws Exception {
        try (ActivityScenario<HomeActivity> sc = openProfileTab()) {
            sc.onActivity(a -> assertEquals(View.VISIBLE,
                    a.findViewById(R.id.profile_photos_section).getVisibility()));
            final ProfilePhotoStrip strip = strip(sc);

            strip.addPicked(jpeg(Color.RED));
            waitFor(() -> thumbs(sc) == 1);
            strip.addPicked(jpeg(Color.GREEN));
            waitFor(() -> thumbs(sc) == 2);

            sc.onActivity(a -> {
                final ViewGroup photos = a.findViewById(R.id.profile_photos);
                assertEquals(a.getString(R.string.profile_photo_main_description, 1, 2),
                        photos.getChildAt(0).getContentDescription().toString());
                assertEquals(a.getString(R.string.profile_photo_description, 2, 2),
                        photos.getChildAt(1).getContentDescription().toString());
            });
            assertEquals(2, vault.objects.size());
        }
    }

    @Test
    public void choosingTheMainPhotoAndRemovingOneChangeTheStoredProfile() throws Exception {
        try (ActivityScenario<HomeActivity> sc = openProfileTab()) {
            final ProfilePhotoStrip strip = strip(sc);
            strip.addPicked(jpeg(Color.RED));
            waitFor(() -> thumbs(sc) == 1);
            strip.addPicked(jpeg(Color.GREEN));
            waitFor(() -> thumbs(sc) == 2);
            final List<String> added = stored();

            sc.onActivity(a -> strip.makePrimary(added.get(1)));
            waitFor(() -> stored().equals(List.of(added.get(1), added.get(0))));

            sc.onActivity(a -> strip.remove(added.get(0)));
            waitFor(() -> thumbs(sc) == 1);
            assertEquals(List.of(added.get(1)), stored());
            assertFalse("the removed photo's bytes are gone", vault.objects.containsKey(added.get(0)));
        }
    }

    @Test
    public void photosArePrivateToMatchesByDefault() throws Exception {
        try (ActivityScenario<HomeActivity> sc = openProfileTab()) {
            strip(sc).addPicked(jpeg(Color.BLUE));
            waitFor(() -> thumbs(sc) == 1);
            sc.onActivity(a -> {
                final ProfilePanel shown = new ProfilePanel(a.findViewById(R.id.profile_page), memory,
                        Runnable::run);
                shown.show(memory.stored.get());
                final String photo = a.getString(R.string.profile_preview_photo);
                assertTrue(shown.previewText(ProfileFieldPolicy.Audience.MATCH).contains(photo));
                assertFalse(shown.previewText(ProfileFieldPolicy.Audience.NEARBY).contains(photo));
                assertFalse(shown.previewText(ProfileFieldPolicy.Audience.REQUESTER).contains(photo));
                assertTrue(((android.widget.TextView) a.findViewById(R.id.profile_photos_audience)).getText()
                        .toString().contains(a.getString(R.string.profile_audience_match)));
            });
        }
    }
}
