/*
 * Lumos - android-app/app / The Profile tab's store source round-trips the profile and its policy.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-PRO-001, SRS-PRO-003, SRS-PRO-004
 * ADRs: ADR-003
 * Threading: test-only
 * Notes: Over the real store: nothing saved reads as an empty profile with
 * the most private policy; a save returns both exactly; prefer-not-to-say
 * survives as itself, not as an empty answer.
 */

package com.lumos.app.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.Fixtures;
import org.junit.jupiter.api.Test;

/** ProfileSource.Stored over the real store. */
final class ProfileSourceTest {

    @Test
    void nothingSavedIsAnEmptyProfileThatOnlyMatchesCouldSee() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final ProfileSource.Editing read = new ProfileSource.Stored(new ProfileRepository(stack.store()))
                    .load().orElseThrow();
            assertEquals(LocalProfile.empty().missingRequiredFields(), read.profile().missingRequiredFields());
            for (final ProfileField field : ProfileField.values()) {
                assertEquals(ProfileFieldPolicy.Audience.MATCH, read.policy().threshold(field), field.name());
            }
        }
    }

    @Test
    void aSaveReadsBackExactly() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final ProfileSource source = new ProfileSource.Stored(new ProfileRepository(stack.store()));
            final LocalProfile profile = LocalProfile.empty()
                    .with(ProfileField.ALIAS, FieldValue.stated("Asha"))
                    .with(ProfileField.AGE_BAND, FieldValue.stated("25–34"))
                    .with(ProfileField.ORIENTATION, FieldValue.preferNotToSay());
            final ProfileFieldPolicy policy = ProfileFieldPolicy.privateByDefault()
                    .with(ProfileField.ALIAS, ProfileFieldPolicy.Audience.REQUESTER);
            assertTrue(source.save(new ProfileSource.Editing(profile, policy)));

            final ProfileSource.Editing read = new ProfileSource.Stored(new ProfileRepository(stack.store()))
                    .load().orElseThrow();
            assertEquals(FieldValue.stated("Asha"), read.profile().value(ProfileField.ALIAS));
            assertEquals(FieldValue.preferNotToSay(), read.profile().value(ProfileField.ORIENTATION),
                    "prefer-not-to-say is kept as itself");
            assertTrue(read.profile().isShowable(), "alias and age band make it complete");
            assertEquals(ProfileFieldPolicy.Audience.REQUESTER, read.policy().threshold(ProfileField.ALIAS));
            assertEquals(ProfileFieldPolicy.Audience.MATCH, read.policy().threshold(ProfileField.BIO));
        }
    }
}
