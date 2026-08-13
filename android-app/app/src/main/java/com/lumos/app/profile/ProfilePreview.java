/*
 * Lumos - android-app/app / The preview, DERIVED and never stored.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-PRO-007, SRS-PRO-008, SRS-PRO-003, SRS-NFR-PRI-002
 * ADRs: ADR-002
 * Threading: any
 * Notes: The WP-02 kickoff names this trap by hand: "Profile preview must be
 * derived, never a second hand-maintained copy." So there is no preview record,
 * no preview table and no preview cache - `derive` is a pure function of
 * (profile, policy, audience), and the ONLY way to obtain a preview is to call
 * it. A stored preview would be a second source of truth for the same data,
 * and the two would diverge exactly when it mattered: after the user restricted
 * a field (WP-01 closeout Sec. 5.2 is the same defect one layer down).
 *
 * ProfilePreviewTest asserts the equivalence directly - for every audience, the
 * preview's fields equal the policy's visible set, and nothing a preview
 * exposes was hidden by the policy. It ranges over ProfileField.values(), so a
 * field added later is covered without anyone remembering to add a case.
 *
 * What a preview must NEVER carry is the media bytes. SRS-PRO-007 and
 * SRS-NFR-PRI-002 forbid a photo in a BLE advertisement; this type holds an
 * object identifier at most, and only when media is visible to the audience.
 * The identifier is meaningless without an authenticated GATT session, which is
 * ADR-002's asymmetric-advertising rule expressed in the data model.
 */

package com.lumos.app.profile;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** What one audience may see of a profile. Always derived, never stored. */
public final class ProfilePreview {

    private final ProfileFieldPolicy.Audience audience;
    private final Map<ProfileField, FieldValue> visible;
    private final String primaryMediaObjectId;

    private ProfilePreview(final ProfileFieldPolicy.Audience audience,
            final Map<ProfileField, FieldValue> visible, final String primaryMediaObjectId) {
        this.audience = audience;
        this.visible = Collections.unmodifiableMap(new EnumMap<>(visible));
        this.primaryMediaObjectId = primaryMediaObjectId;
    }

    /**
     * Derives what an audience may see.
     *
     * <p>The one and only construction path. Two rules decide the result and
     * both are visible here rather than spread across call sites: a field
     * appears only if the policy admits this audience, and an UNSET field never
     * appears at all - a preview should not advertise the shape of what the
     * user has not filled in.
     *
     * <p>An explicit prefer-not-to-say DOES appear, because it is an answer
     * (SRS-PRO-002) and hiding it would make a deliberate decline
     * indistinguishable from an empty field.
     *
     * @param profile the profile
     * @param policy the visibility policy
     * @param audience who is looking
     * @return the derived preview
     */
    public static ProfilePreview derive(final LocalProfile profile, final ProfileFieldPolicy policy,
            final ProfileFieldPolicy.Audience audience) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(policy, "policy");

        final Set<ProfileField> permitted = policy.visibleFields(audience);
        final Map<ProfileField, FieldValue> visible = new EnumMap<>(ProfileField.class);
        for (final ProfileField field : permitted) {
            if (field == ProfileField.MEDIA) {
                continue; // carried as an object identifier below, never as a value
            }
            final FieldValue value = profile.value(field);
            if (value.isAnswered()) {
                visible.put(field, value);
            }
        }

        String mediaId = null;
        if (permitted.contains(ProfileField.MEDIA) && profile.primaryMedia() != null) {
            mediaId = profile.primaryMedia().objectId();
        }
        return new ProfilePreview(audience, visible, mediaId);
    }

    /**
     * The audience this preview was derived for.
     *
     * @return the audience
     */
    public ProfileFieldPolicy.Audience audience() {
        return audience;
    }

    /**
     * The fields this preview exposes, with their values.
     *
     * @return an unmodifiable map
     */
    public Map<ProfileField, FieldValue> visibleValues() {
        return visible;
    }

    /**
     * The primary media object identifier, or null.
     *
     * <p>An identifier, never bytes: SRS-PRO-007 forbids a photo in an
     * advertisement, and retrieving the object needs an authenticated session.
     *
     * @return the object identifier, or null when media is not visible or absent
     */
    public String primaryMediaObjectId() {
        return primaryMediaObjectId;
    }

    /**
     * Whether this preview carries anything at all.
     *
     * @return true when no field and no media is exposed
     */
    public boolean isEmpty() {
        return visible.isEmpty() && primaryMediaObjectId == null;
    }
}
