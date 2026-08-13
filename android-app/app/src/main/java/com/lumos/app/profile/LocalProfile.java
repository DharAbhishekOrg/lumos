/*
 * Lumos - android-app/app / The local profile and its media.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-PRO-001, SRS-PRO-002, SRS-PRO-009, SRS-PRO-010, SRS-PRO-011
 * ADRs: -
 * Threading: any
 * Notes: An immutable value with copy-on-write mutators, so a profile handed to
 * the preview derivation cannot be changed underneath it (TRS-ARCH-004:
 * immutable view state, one-way flow). Every field is a FieldValue, so
 * "unset", "prefer not to say" and a stated answer stay distinguishable all
 * the way to the wire.
 *
 * Lifecycle (SRS-PRO-011) is modelled as a state rather than a pair of
 * booleans: ACTIVE, PAUSED and HIDDEN are mutually exclusive and a boolean pair
 * would admit a fourth, meaningless combination. Permanent deletion is not a
 * state here - it is the absence of the record, which the store handles, and
 * modelling it as a state would leave a tombstone containing the data the user
 * asked to erase.
 */

package com.lumos.app.profile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** The user's own profile, immutable. */
public final class LocalProfile {

    /** Profile visibility lifecycle (SRS-PRO-011). */
    public enum Lifecycle {
        /** Normal: eligible to be shown, subject to completeness and policy. */
        ACTIVE,
        /** Paused by the user: never shown, retained intact. */
        PAUSED,
        /** Temporarily hidden: never shown, retained intact. */
        HIDDEN
    }

    private final Map<ProfileField, FieldValue> values;
    private final List<ProfileMedia> media;
    private final Lifecycle lifecycle;

    private LocalProfile(final Map<ProfileField, FieldValue> values,
            final List<ProfileMedia> media, final Lifecycle lifecycle) {
        this.values = Collections.unmodifiableMap(new EnumMap<>(values));
        this.media = Collections.unmodifiableList(new ArrayList<>(media));
        this.lifecycle = lifecycle;
    }

    /**
     * An empty profile: every field unset, no media, active.
     *
     * @return the empty profile
     */
    public static LocalProfile empty() {
        final Map<ProfileField, FieldValue> values = new EnumMap<>(ProfileField.class);
        for (final ProfileField field : ProfileField.values()) {
            values.put(field, FieldValue.unset());
        }
        return new LocalProfile(values, List.of(), Lifecycle.ACTIVE);
    }

    /**
     * This profile with one field replaced.
     *
     * @param field the field
     * @param value its new value
     * @return a new profile
     */
    public LocalProfile with(final ProfileField field, final FieldValue value) {
        final Map<ProfileField, FieldValue> next = new EnumMap<>(values);
        next.put(field, value);
        return new LocalProfile(next, media, lifecycle);
    }

    /**
     * This profile with its media list replaced.
     *
     * <p>Order is meaningful: the first entry is the primary photo
     * (SRS-PRO-009), so reordering is how a user chooses one.
     *
     * @param replacement the new media list, in display order
     * @return a new profile
     */
    public LocalProfile withMedia(final List<ProfileMedia> replacement) {
        return new LocalProfile(values, replacement, lifecycle);
    }

    /**
     * This profile in a different lifecycle state (SRS-PRO-011).
     *
     * @param next the state
     * @return a new profile
     */
    public LocalProfile withLifecycle(final Lifecycle next) {
        return new LocalProfile(values, media, next);
    }

    /**
     * One field's value.
     *
     * @param field the field
     * @return its value, never null
     */
    public FieldValue value(final ProfileField field) {
        return values.getOrDefault(field, FieldValue.unset());
    }

    /**
     * The media list in display order; the first entry is primary.
     *
     * @return an unmodifiable list
     */
    public List<ProfileMedia> media() {
        return media;
    }

    /**
     * The primary photo, or null when there is no media.
     *
     * @return the first media entry, or null
     */
    public ProfileMedia primaryMedia() {
        return media.isEmpty() ? null : media.get(0);
    }

    /**
     * The lifecycle state.
     *
     * @return the state
     */
    public Lifecycle lifecycle() {
        return lifecycle;
    }

    /**
     * Whether this profile may be shown to anyone at all.
     *
     * <p>Visibility is the conjunction of lifecycle and completeness: a paused
     * profile is never shown however complete, and an incomplete profile is
     * never shown however active (SRS-PRO-010, SRS-PRO-011). WP-03's Visible
     * Nearby control consults this rather than re-deriving it.
     *
     * @return true when the profile is eligible to be shown
     */
    public boolean isShowable() {
        return lifecycle == Lifecycle.ACTIVE && missingRequiredFields().isEmpty();
    }

    /**
     * The required fields this profile has not answered (SRS-PRO-010).
     *
     * <p>An explicit prefer-not-to-say counts as answered, so declining a
     * sensitive field never makes a profile incomplete.
     *
     * @return the unanswered required fields, in declaration order
     */
    public List<ProfileField> missingRequiredFields() {
        final List<ProfileField> missing = new ArrayList<>();
        for (final ProfileField field : ProfileField.values()) {
            if (field.isRequiredForCompleteness() && !value(field).isAnswered()) {
                missing.add(field);
            }
        }
        return missing;
    }
}
