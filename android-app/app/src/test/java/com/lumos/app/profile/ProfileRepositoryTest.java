/*
 * Lumos - android-app/app / Profile CRUD, inclusivity and lifecycle.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-PRO-001, SRS-PRO-002, SRS-PRO-009, SRS-PRO-010, SRS-PRO-011, SRS-DAT-002
 * ADRs: -
 * Threading: any
 * Notes: The SRS-PRO-001 CRUD test, run over the real sealed store on a real
 * SQLite engine, so it also demonstrates that profile content is subject to the
 * S2 encryption rather than merely adjacent to it.
 *
 * The inclusivity cases are named for what they are. SRS-PRO-002 asks for
 * inclusive values, self-description and prefer-not-to-say without assuming
 * binary gender or heterosexual matching - so the fixtures use non-binary and
 * queer identities and free-text self-description, not "male"/"female". A test
 * that only ever stored "male" would pass while the model quietly assumed two
 * options.
 */

package com.lumos.app.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.data.DataKeyHierarchy;
import com.lumos.app.keystore.SoftwareKekStore;
import com.lumos.app.store.JdbcSqlDatabase;
import com.lumos.app.store.LocalStore;
import com.lumos.app.store.RecordKind;
import com.lumos.app.store.StorageCapacity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Profile persistence, inclusivity, completeness and lifecycle. */
final class ProfileRepositoryTest {

    private static final StorageCapacity ROOMY = () -> Long.MAX_VALUE;

    @Test
    void aProfileRoundTripsThroughTheSealedStore() throws Exception {
        try (JdbcSqlDatabase database = JdbcSqlDatabase.inMemory()) {
            final LocalStore store = openStore(database);
            final ProfileRepository repository = new ProfileRepository(store);
            final LocalProfile written = LocalProfile.empty()
                    .with(ProfileField.ALIAS, FieldValue.stated("Sam"))
                    .with(ProfileField.AGE_BAND, FieldValue.stated("30-34"))
                    .with(ProfileField.PRONOUNS, FieldValue.stated("they/them"))
                    .with(ProfileField.GENDER_IDENTITY, FieldValue.stated("non-binary"))
                    .with(ProfileField.ORIENTATION, FieldValue.stated("queer"))
                    .with(ProfileField.BIO, FieldValue.stated("Reads maps for fun.\nMakes bread."))
                    .withMedia(List.of(new ProfileMedia("obj-1", "image/jpeg", 640, 480, 2048)));
            repository.save(written);

            final LocalProfile read = repository.load().orElseThrow();
            for (final ProfileField field : ProfileField.values()) {
                assertEquals(written.value(field), read.value(field), field + " must round-trip");
            }
            assertEquals(written.media(), read.media());
        }
    }

    @Test
    void theThreeAnswerStatesStayDistinct() throws Exception {
        try (JdbcSqlDatabase database = JdbcSqlDatabase.inMemory()) {
            final LocalStore store = openStore(database);
            final ProfileRepository repository = new ProfileRepository(store);
            repository.save(LocalProfile.empty()
                    .with(ProfileField.ORIENTATION, FieldValue.preferNotToSay())
                    .with(ProfileField.GENDER_IDENTITY, FieldValue.stated("agender")));

            final LocalProfile read = repository.load().orElseThrow();
            assertEquals(FieldValue.Kind.PREFER_NOT_TO_SAY,
                    read.value(ProfileField.ORIENTATION).kind(),
                    "an explicit decline must survive storage as a decline, not as blank");
            assertEquals(FieldValue.Kind.STATED, read.value(ProfileField.GENDER_IDENTITY).kind());
            assertEquals(FieldValue.Kind.UNSET, read.value(ProfileField.INTERESTS).kind());
        }
    }

    @Test
    void selfDescriptionIsFreeTextNotAFixedOption() throws Exception {
        try (JdbcSqlDatabase database = JdbcSqlDatabase.inMemory()) {
            final LocalStore store = openStore(database);
            final ProfileRepository repository = new ProfileRepository(store);
            // Deliberately not an enumerable option: SRS-PRO-002 requires
            // self-description, which a fixed vocabulary cannot express.
            final String selfDescribed = "genderqueer / femme-leaning, ask me";
            repository.save(LocalProfile.empty()
                    .with(ProfileField.GENDER_IDENTITY, FieldValue.stated(selfDescribed)));
            assertEquals(selfDescribed,
                    repository.load().orElseThrow().value(ProfileField.GENDER_IDENTITY).text());
        }
    }

    @Test
    void profileContentIsSealedOnDisk(@TempDir final Path directory) throws Exception {
        final Path file = directory.resolve("profile.db");
        try (JdbcSqlDatabase database = JdbcSqlDatabase.atPath(file.toString())) {
            final LocalStore store = openStore(database);
            final ProfileRepository repository = new ProfileRepository(store);
            repository.save(LocalProfile.empty()
                    .with(ProfileField.ORIENTATION, FieldValue.stated("SECRET-ORIENTATION"))
                    .with(ProfileField.BIO, FieldValue.stated("SECRET-BIO")));
        }
        final byte[] onDisk = Files.readAllBytes(file);
        for (final String secret : List.of("SECRET-ORIENTATION", "SECRET-BIO")) {
            assertFalse(new String(onDisk, StandardCharsets.ISO_8859_1).contains(secret),
                    "profile content must inherit the S2 sealing: " + secret + " found on disk");
        }
    }

    @Test
    void policyRoundTripsAndDefaultsPrivateWhenAbsent() throws Exception {
        try (JdbcSqlDatabase database = JdbcSqlDatabase.inMemory()) {
            final LocalStore store = openStore(database);
            final ProfileRepository repository = new ProfileRepository(store);

            for (final ProfileField field : ProfileField.values()) {
                assertEquals(ProfileFieldPolicy.Audience.MATCH,
                        repository.loadPolicy().threshold(field),
                        "an absent policy must read as the most private, never as unrestricted");
            }

            repository.savePolicy(ProfileFieldPolicy.privateByDefault()
                    .with(ProfileField.ALIAS, ProfileFieldPolicy.Audience.NEARBY));
            assertEquals(ProfileFieldPolicy.Audience.NEARBY,
                    repository.loadPolicy().threshold(ProfileField.ALIAS));
            assertEquals(ProfileFieldPolicy.Audience.MATCH,
                    repository.loadPolicy().threshold(ProfileField.BIO));
        }
    }

    @Test
    void theStoredPreviewPathAgreesWithDirectDerivation() throws Exception {
        try (JdbcSqlDatabase database = JdbcSqlDatabase.inMemory()) {
            final LocalStore store = openStore(database);
            final ProfileRepository repository = new ProfileRepository(store);
            final LocalProfile profile = LocalProfile.empty()
                    .with(ProfileField.ALIAS, FieldValue.stated("Sam"));
            final ProfileFieldPolicy policy = ProfileFieldPolicy.privateByDefault()
                    .with(ProfileField.ALIAS, ProfileFieldPolicy.Audience.NEARBY);
            repository.save(profile);
            repository.savePolicy(policy);

            assertEquals(
                    ProfilePreview.derive(profile, policy, ProfileFieldPolicy.Audience.NEARBY)
                            .visibleValues(),
                    repository.previewFor(ProfileFieldPolicy.Audience.NEARBY).orElseThrow()
                            .visibleValues(),
                    "the SRS-PRO-008 privacy preview must be the SAME function the sharing path uses");
        }
    }

    // ---- completeness and lifecycle ----

    @Test
    void anEmptyProfileIsNotShowable() {
        final LocalProfile empty = LocalProfile.empty();
        assertFalse(empty.isShowable(), "SRS-PRO-010: an empty profile may not go Visible Nearby");
        assertEquals(List.of(ProfileField.ALIAS, ProfileField.AGE_BAND),
                empty.missingRequiredFields());
    }

    @Test
    void decliningSensitiveFieldsNeverMakesAProfileIncomplete() {
        LocalProfile profile = LocalProfile.empty()
                .with(ProfileField.ALIAS, FieldValue.stated("Sam"))
                .with(ProfileField.AGE_BAND, FieldValue.stated("30-34"));
        for (final ProfileField field : ProfileField.values()) {
            if (field.isSpecialCategory()) {
                profile = profile.with(field, FieldValue.preferNotToSay());
            }
        }
        assertTrue(profile.isShowable(),
                "requiring Article 9 data to use the product is what prefer-not-to-say exists to prevent");
    }

    @Test
    void pausingAndHidingSuppressVisibilityWithoutLosingData() throws Exception {
        try (JdbcSqlDatabase database = JdbcSqlDatabase.inMemory()) {
            final LocalStore store = openStore(database);
            final ProfileRepository repository = new ProfileRepository(store);
            final LocalProfile complete = LocalProfile.empty()
                    .with(ProfileField.ALIAS, FieldValue.stated("Sam"))
                    .with(ProfileField.AGE_BAND, FieldValue.stated("30-34"));
            assertTrue(complete.isShowable());

            for (final LocalProfile.Lifecycle state : List.of(
                    LocalProfile.Lifecycle.PAUSED, LocalProfile.Lifecycle.HIDDEN)) {
                repository.save(complete.withLifecycle(state));
                final LocalProfile read = repository.load().orElseThrow();
                assertEquals(state, read.lifecycle());
                assertFalse(read.isShowable(), state + " must suppress visibility");
                assertEquals("Sam", read.value(ProfileField.ALIAS).text(),
                        state + " must retain the profile intact (SRS-PRO-011)");
            }
        }
    }

    @Test
    void permanentDeletionLeavesNoRow() throws Exception {
        try (JdbcSqlDatabase database = JdbcSqlDatabase.inMemory()) {
            final LocalStore store = openStore(database);
            final ProfileRepository repository = new ProfileRepository(store);
            repository.save(LocalProfile.empty().with(ProfileField.ALIAS, FieldValue.stated("Sam")));
            repository.savePolicy(ProfileFieldPolicy.privateByDefault());

            repository.deletePermanently();

            assertTrue(repository.load().isEmpty());
            assertEquals(0, store.count(RecordKind.LOCAL_PROFILE),
                    "SRS-PRO-011 deletion is deletion; a tombstone would retain what was erased");
            assertEquals(0, store.count(RecordKind.PROFILE_FIELD_POLICY));
        }
    }

    @Test
    void mediaOrderIsPreservedAndTheFirstEntryIsPrimary() throws Exception {
        try (JdbcSqlDatabase database = JdbcSqlDatabase.inMemory()) {
            final LocalStore store = openStore(database);
            final ProfileRepository repository = new ProfileRepository(store);
            final ProfileMedia first = new ProfileMedia("obj-1", "image/jpeg", 640, 480, 2048);
            final ProfileMedia second = new ProfileMedia("obj-2", "image/png", 320, 240, 1024);
            repository.save(LocalProfile.empty().withMedia(List.of(first, second)));
            assertEquals(first, repository.load().orElseThrow().primaryMedia());

            // SRS-PRO-009: choosing a primary photo IS reordering.
            repository.save(repository.load().orElseThrow().withMedia(List.of(second, first)));
            assertEquals(second, repository.load().orElseThrow().primaryMedia());
            assertEquals(List.of(second, first), repository.load().orElseThrow().media());
        }
    }

    /**
     * Opens one sealed store over a database.
     *
     * @param database the engine
     * @return the store
     * @throws Exception on key or store failure
     */
    private static LocalStore openStore(final JdbcSqlDatabase database) throws Exception {
        final byte[] key = DataKeyHierarchy.create(SoftwareKekStore.create())
                .deriveKey(DataKeyHierarchy.DataDomain.DATABASE);
        return LocalStore.open(database, key, ROOMY, () -> 1_700_000_000_000L);
    }
}
