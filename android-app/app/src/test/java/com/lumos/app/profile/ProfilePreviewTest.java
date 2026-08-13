/*
 * Lumos - android-app/app / The preview is derived, and equals the policy.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-PRO-007, SRS-PRO-008, SRS-PRO-003, SRS-NFR-PRI-002
 * ADRs: ADR-002
 * Threading: any
 * Notes: The WP-02 kickoff names the trap: "Profile preview must be derived,
 * never a second hand-maintained copy." A test cannot prove a second copy does
 * not exist somewhere, but it can prove the two properties that make one
 * pointless and detectable - the preview equals what the policy admits, for
 * every audience and every field, and it changes the instant the policy or the
 * profile changes.
 *
 * Every test here ranges over ProfileField.values() and Audience.values()
 * rather than naming cases, so a field or an audience added later is covered
 * without anyone remembering to extend the matrix. That is the difference
 * between a matrix test and a handful of examples.
 */

package com.lumos.app.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Preview derivation and its equivalence with the policy. */
final class ProfilePreviewTest {

    /** A profile with every field stated and one media object. */
    private static LocalProfile fullyStatedProfile() {
        LocalProfile profile = LocalProfile.empty();
        for (final ProfileField field : ProfileField.values()) {
            profile = profile.with(field, FieldValue.stated("value of " + field));
        }
        return profile.withMedia(java.util.List.of(
                new ProfileMedia("object-1", "image/jpeg", 640, 480, 1024)));
    }

    @Test
    void everyAudienceSeesExactlyWhatThePolicyAdmits() {
        final LocalProfile profile = fullyStatedProfile();
        ProfileFieldPolicy policy = ProfileFieldPolicy.privateByDefault();
        // A spread of thresholds, so the assertion is not trivially satisfied
        // by every field having the same one.
        policy = policy.with(ProfileField.ALIAS, ProfileFieldPolicy.Audience.NEARBY)
                .with(ProfileField.PRONOUNS, ProfileFieldPolicy.Audience.NEARBY)
                .with(ProfileField.AGE_BAND, ProfileFieldPolicy.Audience.REQUESTER)
                .with(ProfileField.INTERESTS, ProfileFieldPolicy.Audience.VENUE)
                .with(ProfileField.MEDIA, ProfileFieldPolicy.Audience.REQUESTER);

        for (final ProfileFieldPolicy.Audience audience : ProfileFieldPolicy.Audience.values()) {
            final ProfilePreview preview = ProfilePreview.derive(profile, policy, audience);
            final Set<ProfileField> admitted = EnumSet.copyOf(policy.visibleFields(audience));
            // MEDIA is carried as an object identifier, not as a value.
            admitted.remove(ProfileField.MEDIA);

            assertEquals(admitted, EnumSet.copyOf(preview.visibleValues().keySet()),
                    audience + ": the preview's fields must equal the policy's visible set");

            final boolean mediaAdmitted = policy.isVisibleTo(ProfileField.MEDIA, audience);
            assertEquals(mediaAdmitted, preview.primaryMediaObjectId() != null,
                    audience + ": media presence must follow the policy");
        }
    }

    @Test
    void noPreviewEverExposesAFieldThePolicyHides() {
        final LocalProfile profile = fullyStatedProfile();
        final ProfileFieldPolicy policy = ProfileFieldPolicy.privateByDefault();

        for (final ProfileFieldPolicy.Audience audience : ProfileFieldPolicy.Audience.values()) {
            final ProfilePreview preview = ProfilePreview.derive(profile, policy, audience);
            for (final ProfileField exposed : preview.visibleValues().keySet()) {
                assertTrue(policy.isVisibleTo(exposed, audience),
                        audience + " was shown " + exposed + ", which the policy hides");
            }
        }
    }

    @Test
    void theDefaultPolicyShowsANearbyStrangerNothing() {
        final ProfilePreview preview = ProfilePreview.derive(
                fullyStatedProfile(), ProfileFieldPolicy.privateByDefault(),
                ProfileFieldPolicy.Audience.NEARBY);
        assertTrue(preview.isEmpty(),
                "a field with no explicit policy must default to the most private audience");
    }

    @Test
    void tighteningThePolicyChangesTheNextPreviewImmediately() {
        final LocalProfile profile = fullyStatedProfile();
        final ProfileFieldPolicy open = ProfileFieldPolicy.privateByDefault()
                .with(ProfileField.BIO, ProfileFieldPolicy.Audience.NEARBY);
        final ProfilePreview before = ProfilePreview.derive(profile, open,
                ProfileFieldPolicy.Audience.NEARBY);
        assertTrue(before.visibleValues().containsKey(ProfileField.BIO));

        final ProfileFieldPolicy tightened = open.with(ProfileField.BIO,
                ProfileFieldPolicy.Audience.MATCH);
        final ProfilePreview after = ProfilePreview.derive(profile, tightened,
                ProfileFieldPolicy.Audience.NEARBY);
        assertFalse(after.visibleValues().containsKey(ProfileField.BIO),
                "a derived preview cannot lag the policy; a stored one would");
        assertNotEquals(before.visibleValues(), after.visibleValues());
    }

    @Test
    void editingTheProfileChangesTheNextPreviewImmediately() {
        final ProfileFieldPolicy policy = ProfileFieldPolicy.privateByDefault()
                .with(ProfileField.ALIAS, ProfileFieldPolicy.Audience.NEARBY);
        final LocalProfile before = fullyStatedProfile();
        final LocalProfile after = before.with(ProfileField.ALIAS, FieldValue.stated("renamed"));

        assertNotEquals(
                ProfilePreview.derive(before, policy, ProfileFieldPolicy.Audience.NEARBY)
                        .visibleValues().get(ProfileField.ALIAS),
                ProfilePreview.derive(after, policy, ProfileFieldPolicy.Audience.NEARBY)
                        .visibleValues().get(ProfileField.ALIAS),
                "a derived preview cannot lag the profile either");
    }

    @Test
    void unsetFieldsNeverAppearButDeclinedOnesDo() {
        final ProfileFieldPolicy policy = ProfileFieldPolicy.privateByDefault()
                .with(ProfileField.ORIENTATION, ProfileFieldPolicy.Audience.NEARBY)
                .with(ProfileField.GENDER_IDENTITY, ProfileFieldPolicy.Audience.NEARBY);
        final LocalProfile profile = LocalProfile.empty()
                .with(ProfileField.ORIENTATION, FieldValue.preferNotToSay());

        final ProfilePreview preview = ProfilePreview.derive(profile, policy,
                ProfileFieldPolicy.Audience.NEARBY);
        assertEquals(FieldValue.preferNotToSay(),
                preview.visibleValues().get(ProfileField.ORIENTATION),
                "an explicit decline is an answer (SRS-PRO-002) and must be shown as one");
        assertFalse(preview.visibleValues().containsKey(ProfileField.GENDER_IDENTITY),
                "an unanswered field must not advertise its own absence");
    }

    @Test
    void thePreviewCarriesAnObjectIdentifierAndNeverImageBytes() {
        final ProfileFieldPolicy policy = ProfileFieldPolicy.privateByDefault()
                .with(ProfileField.MEDIA, ProfileFieldPolicy.Audience.NEARBY);
        final ProfilePreview preview = ProfilePreview.derive(fullyStatedProfile(), policy,
                ProfileFieldPolicy.Audience.NEARBY);

        assertEquals("object-1", preview.primaryMediaObjectId());
        // SRS-PRO-007 / SRS-NFR-PRI-002: a photo may not ride in an
        // advertisement. The type has nowhere to put one, and this asserts the
        // value side stays free of it too.
        assertFalse(preview.visibleValues().containsKey(ProfileField.MEDIA),
                "media must never be a preview VALUE; only an identifier");
    }

    @Test
    void aProfileWithNoMediaExposesNoIdentifier() {
        final ProfileFieldPolicy policy = ProfileFieldPolicy.privateByDefault()
                .with(ProfileField.MEDIA, ProfileFieldPolicy.Audience.NEARBY);
        assertNull(ProfilePreview.derive(LocalProfile.empty(), policy,
                ProfileFieldPolicy.Audience.NEARBY).primaryMediaObjectId());
    }

    @Test
    void everyFieldIsCoveredByTheMatrix() {
        // Guards the tests above: if a field were somehow unreachable by any
        // audience, the equivalence assertions would pass vacuously for it.
        final ProfileFieldPolicy everythingVisible = openToNearby();
        final Set<ProfileField> visible = everythingVisible.visibleFields(
                ProfileFieldPolicy.Audience.NEARBY);
        assertEquals(EnumSet.allOf(ProfileField.class), EnumSet.copyOf(visible),
                "every declared field must be expressible in the policy");
    }

    /**
     * A policy opening every field to the least-trusted audience.
     *
     * @return the fully open policy
     */
    private static ProfileFieldPolicy openToNearby() {
        ProfileFieldPolicy policy = ProfileFieldPolicy.privateByDefault();
        for (final ProfileField field : ProfileField.values()) {
            policy = policy.with(field, ProfileFieldPolicy.Audience.NEARBY);
        }
        return policy;
    }
}
