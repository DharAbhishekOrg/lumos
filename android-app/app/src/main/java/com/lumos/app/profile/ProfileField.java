/*
 * Lumos - android-app/app / The profile's fields, and which are sensitive.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-PRO-001, SRS-PRO-002, SRS-PRO-003, SRS-NFR-PRI-001
 * ADRs: -
 * Threading: any
 * Notes: The field set of SRS-PRO-001, enumerated so that visibility policy,
 * preview derivation and completeness validation all range over the SAME list.
 * That is the point of the enum: a field added here is a field the policy
 * matrix, the preview and the validator must all account for, and the tests
 * range over values() so a new field cannot be quietly forgotten by one of them.
 *
 * `specialCategory` marks the fields the privacy data map classifies as GDPR
 * Article 9 - orientation and gender identity, plus the two the data map treats
 * as special category by ASSUMPTION because the user decides what goes in them:
 * the free-text bio and profile media. Classifying those as ordinary would put
 * the weakest controls on the two places users say the most (data map Sec. 2.2,
 * owner-accepted 2026-08-14 pending G-09).
 */

package com.lumos.app.profile;

/** Every field of the local profile (SRS-PRO-001). */
public enum ProfileField {

    /** Display name. Always visible - a profile with no alias cannot be shown. */
    ALIAS(false, true),

    /** Age or age band. Required: the adult-only gate depends on it. */
    AGE_BAND(false, true),

    /** Pronouns. */
    PRONOUNS(false, false),

    /** Gender identity. Article 9. */
    GENDER_IDENTITY(true, false),

    /** Sexual orientation. Article 9. */
    ORIENTATION(true, false),

    /** What the user is looking for. */
    RELATIONSHIP_INTENT(false, false),

    /** Free-text interests. */
    INTERESTS(false, false),

    /** Free-text bio. Special category by assumption: the user decides. */
    BIO(true, false),

    /** Profile media. Special category by assumption, for the same reason. */
    MEDIA(true, false);

    private final boolean specialCategory;
    private final boolean requiredForCompleteness;

    ProfileField(final boolean specialCategory, final boolean requiredForCompleteness) {
        this.specialCategory = specialCategory;
        this.requiredForCompleteness = requiredForCompleteness;
    }

    /**
     * Whether this field is GDPR Article 9 special-category data.
     *
     * @return true for orientation, gender identity, bio and media
     */
    public boolean isSpecialCategory() {
        return specialCategory;
    }

    /**
     * Whether a profile without this field is policy-incomplete (SRS-PRO-010).
     *
     * <p>Deliberately a short list. Requiring orientation or gender identity
     * would force a user to disclose Article 9 data to use the product at all,
     * which is the opposite of what SRS-PRO-002's prefer-not-to-say option is
     * for.
     *
     * @return true when the field must be present
     */
    public boolean isRequiredForCompleteness() {
        return requiredForCompleteness;
    }
}
