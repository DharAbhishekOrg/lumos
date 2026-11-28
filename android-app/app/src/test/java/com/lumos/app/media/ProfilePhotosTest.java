/*
 * Lumos - android-app/app / Profile photos: only clean, encrypted pictures join a profile, and nothing dangles.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-PRO-001, SRS-PRO-004, SRS-PRO-005, SRS-PRO-006, SRS-PRO-009, TRS-TST-001
 * ADRs: ADR-003
 * Threading: test-only
 * Notes: Over the real profile store and the real encrypted object store. The
 * re-encoder here passes bytes through untouched, so the planted metadata of
 * DirtyImages reaches the sanitiser and the test proves the sanitiser, not
 * the platform re-draw, is what keeps it out of storage. Lives in the media
 * package to reach the fixtures.
 */

package com.lumos.app.media;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.Fixtures;
import com.lumos.app.data.DataKeyHierarchy;
import com.lumos.app.keystore.SoftwareKekStore;
import com.lumos.app.profile.FieldValue;
import com.lumos.app.profile.LocalProfile;
import com.lumos.app.profile.ProfileField;
import com.lumos.app.profile.ProfileFieldPolicy;
import com.lumos.app.profile.ProfileMedia;
import com.lumos.app.profile.ProfilePhotos;
import com.lumos.app.profile.ProfileRepository;
import com.lumos.app.profile.ProfileSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ProfilePhotos over the real stores. */
final class ProfilePhotosTest {

    private static final ProfilePhotos.Reencoder AS_IS = picked -> picked;

    private static MediaObjectStore vaultIn(final Path dir) throws Exception {
        return new MediaObjectStore(dir, DataKeyHierarchy.create(SoftwareKekStore.create())
                .deriveKey(DataKeyHierarchy.DataDomain.MEDIA_STORE));
    }

    private static ProfileSource.Editing named() {
        return new ProfileSource.Editing(LocalProfile.empty()
                .with(ProfileField.ALIAS, FieldValue.stated("Asha"))
                .with(ProfileField.AGE_BAND, FieldValue.stated("25–34")),
                ProfileFieldPolicy.privateByDefault());
    }

    private static List<String> ids(final ProfilePhotos.Change change) {
        return change.editing().orElseThrow().profile().media().stream().map(ProfileMedia::objectId).toList();
    }

    @Test
    void anAddedPhotoIsStrippedEncryptedAndReferenced(@TempDir final Path dir) throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final MediaObjectStore store = vaultIn(dir);
            final ProfileSource source = new ProfileSource.Stored(new ProfileRepository(stack.store()));
            final ProfilePhotos photos = new ProfilePhotos(AS_IS, ProfilePhotos.vaultOf(store), source);

            final ProfilePhotos.Change added = photos.add(named(), DirtyImages.jpegWithEverything(640, 480));

            assertEquals(ProfilePhotos.Outcome.DONE, added.outcome());
            final ProfileMedia media = added.editing().orElseThrow().profile().primaryMedia();
            assertEquals("image/jpeg", media.contentType());
            assertEquals(640, media.widthPixels());
            assertEquals(480, media.heightPixels());
            final byte[] kept = store.get(media.objectId());
            assertEquals(kept.length, media.byteCount());
            final String plain = new String(kept, StandardCharsets.ISO_8859_1);
            for (final String secret : DirtyImages.plantedSecrets()) {
                assertFalse(plain.contains(secret), "stored photo still carries: " + secret);
            }
            final String disk = new String(Files.readAllBytes(dir.resolve(media.objectId())),
                    StandardCharsets.ISO_8859_1);
            assertFalse(disk.contains("JFIF"), "the file on disk is ciphertext (SRS-PRO-004)");
        }
    }

    @Test
    void thePrimaryCanBeChosenAndTheOrderChangedOffline(@TempDir final Path dir) throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final ProfileSource source = new ProfileSource.Stored(new ProfileRepository(stack.store()));
            final ProfilePhotos photos = new ProfilePhotos(AS_IS, ProfilePhotos.vaultOf(vaultIn(dir)), source);
            ProfilePhotos.Change last = photos.add(named(), DirtyImages.jpegWithEverything(10, 10));
            last = photos.add(last.editing().orElseThrow(), DirtyImages.pngWithEverything(20, 20));
            last = photos.add(last.editing().orElseThrow(), DirtyImages.jpegWithEverything(30, 30));
            final List<String> added = ids(last);

            last = photos.makePrimary(last.editing().orElseThrow(), added.get(2));
            assertEquals(List.of(added.get(2), added.get(0), added.get(1)), ids(last));

            last = photos.move(last.editing().orElseThrow(), added.get(2), 99);
            assertEquals(List.of(added.get(0), added.get(1), added.get(2)), ids(last), "clamped to the end");

            final ProfilePhotos.Change unknown = photos.makePrimary(last.editing().orElseThrow(),
                    "00000000000000000000000000000000");
            assertEquals(ProfilePhotos.Outcome.UNKNOWN, unknown.outcome());
            assertEquals(added, ids(unknown), "an unknown photo changes nothing");
        }
    }

    @Test
    void removingStoresTheProfileThenDeletesTheBytes(@TempDir final Path dir) throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final MediaObjectStore store = vaultIn(dir);
            final ProfileSource source = new ProfileSource.Stored(new ProfileRepository(stack.store()));
            final ProfilePhotos photos = new ProfilePhotos(AS_IS, ProfilePhotos.vaultOf(store), source);
            final ProfilePhotos.Change added = photos.add(named(), DirtyImages.jpegWithEverything(10, 10));
            final String id = ids(added).get(0);

            final ProfilePhotos.Change removed = photos.remove(added.editing().orElseThrow(), id);

            assertEquals(ProfilePhotos.Outcome.DONE, removed.outcome());
            assertTrue(ids(removed).isEmpty());
            assertTrue(store.list().isEmpty(), "the encrypted bytes are gone too");
        }
    }

    @Test
    void aFullProfileTakesNoMore(@TempDir final Path dir) throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final MediaObjectStore store = vaultIn(dir);
            final ProfileSource source = new ProfileSource.Stored(new ProfileRepository(stack.store()));
            final ProfilePhotos photos = new ProfilePhotos(AS_IS, ProfilePhotos.vaultOf(store), source);
            ProfileSource.Editing editing = named();
            for (int i = 0; i < ProfilePhotos.MAX_PHOTOS; i++) {
                editing = photos.add(editing, DirtyImages.jpegWithEverything(8, 8)).editing().orElseThrow();
            }

            final ProfilePhotos.Change extra = photos.add(editing, DirtyImages.jpegWithEverything(8, 8));

            assertEquals(ProfilePhotos.Outcome.FULL, extra.outcome());
            assertEquals(ProfilePhotos.MAX_PHOTOS, store.list().size(), "nothing extra was written");
        }
    }

    @Test
    void anUnsafePictureIsNeverStored(@TempDir final Path dir) throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final MediaObjectStore store = vaultIn(dir);
            final ProfileSource source = new ProfileSource.Stored(new ProfileRepository(stack.store()));
            final ProfilePhotos photos = new ProfilePhotos(AS_IS, ProfilePhotos.vaultOf(store), source);

            final ProfilePhotos.Change notAnImage = photos.add(named(),
                    "<svg onload=alert(1)>".getBytes(StandardCharsets.UTF_8));
            final ProfilePhotos.Change refused = new ProfilePhotos(picked -> {
                throw new MediaSanitizer.RejectedException(MediaSanitizer.Rejection.BAD_DIMENSIONS, "bomb");
            }, ProfilePhotos.vaultOf(store), source).add(named(), DirtyImages.jpegWithEverything(8, 8));

            assertEquals(ProfilePhotos.Outcome.REJECTED, notAnImage.outcome());
            assertEquals(ProfilePhotos.Outcome.REJECTED, refused.outcome());
            assertTrue(store.list().isEmpty());
        }
    }

    @Test
    void aFailedSaveLeavesNoOrphanAndTheSweepClearsLeftovers(@TempDir final Path dir) throws Exception {
        final MediaObjectStore store = vaultIn(dir);
        final ProfileSource refusing = new ProfileSource() {
            @Override
            public Optional<Editing> load() {
                return Optional.of(named());
            }

            @Override
            public boolean save(final Editing editing) {
                return false;
            }
        };
        final ProfilePhotos photos = new ProfilePhotos(AS_IS, ProfilePhotos.vaultOf(store), refusing);

        assertEquals(ProfilePhotos.Outcome.NOT_SAVED,
                photos.add(named(), DirtyImages.jpegWithEverything(8, 8)).outcome());
        assertTrue(store.list().isEmpty(), "the new object was deleted again");

        final String leftover = store.put(new byte[] {1, 2, 3});
        assertEquals(1, photos.sweep(), "named() references no photo, so the leftover goes");
        assertFalse(store.list().contains(leftover));

        final ProfileSource unreadable = new ProfileSource() {
            @Override
            public Optional<Editing> load() {
                return Optional.empty();
            }

            @Override
            public boolean save(final Editing editing) {
                return false;
            }
        };
        final String kept = store.put(new byte[] {4, 5, 6});
        assertEquals(0, new ProfilePhotos(AS_IS, ProfilePhotos.vaultOf(store), unreadable).sweep(),
                "an unreadable profile could reference anything, so nothing is deleted");
        assertArrayEquals(new byte[] {4, 5, 6}, store.get(kept));
    }
}
