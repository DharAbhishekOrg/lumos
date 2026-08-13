/*
 * Lumos - android-app/app / Per-field visibility, per audience.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-PRO-003, SRS-PRO-008, SRS-NFR-PRI-006
 * ADRs: -
 * Threading: any
 * Notes: SRS-PRO-003 requires the user to control each sensitive field's
 * visibility BEFORE it is shared. Two design choices carry that:
 *
 * 1. The default is the most private audience, not the least. A field with no
 *    explicit policy is visible to MATCH only - never to a nearby stranger.
 *    Defaulting the other way would mean a field added in a later work package
 *    is disclosed to everyone until someone remembers to restrict it, and that
 *    mistake is unrecoverable once broadcast (SRS Sec. 2.4: the stricter
 *    reading wins).
 *
 * 2. Audiences are ORDERED by how much they have been trusted, so a policy is
 *    one threshold per field rather than a set of independent booleans. A set
 *    would admit "visible to a stranger but not to a match", which is not a
 *    privacy preference anyone holds and which the preview derivation would
 *    then have to handle.
 *
 * Everything that renders a profile to anyone goes through visibleFields, so
 * there is exactly one place where "who can see this" is decided.
 */

package com.lumos.app.profile;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Which fields each audience may see. */
public final class ProfileFieldPolicy {

    /**
     * Who is looking, ordered from least to most trusted.
     *
     * <p>The ordinal IS the trust level; visibleFields relies on it.
     */
    public enum Audience {
        /** Someone the app has merely detected nearby (WP-03). */
        NEARBY,
        /** Someone who has sent an interest request (WP-04). */
        REQUESTER,
        /** A participant in an activated venue room (WP-08). */
        VENUE,
        /** A mutual match (WP-04). */
        MATCH
    }

    private final Map<ProfileField, Audience> minimumAudience;

    private ProfileFieldPolicy(final Map<ProfileField, Audience> minimumAudience) {
        this.minimumAudience = Collections.unmodifiableMap(new EnumMap<>(minimumAudience));
    }

    /**
     * The safe default: every field visible to matches only.
     *
     * @return the most private policy
     */
    public static ProfileFieldPolicy privateByDefault() {
        final Map<ProfileField, Audience> policy = new EnumMap<>(ProfileField.class);
        for (final ProfileField field : ProfileField.values()) {
            policy.put(field, Audience.MATCH);
        }
        return new ProfileFieldPolicy(policy);
    }

    /**
     * This policy with one field's threshold changed.
     *
     * @param field the field
     * @param leastTrustedAudience the least-trusted audience that may see it
     * @return a new policy
     */
    public ProfileFieldPolicy with(final ProfileField field, final Audience leastTrustedAudience) {
        final Map<ProfileField, Audience> next = new EnumMap<>(minimumAudience);
        next.put(field, leastTrustedAudience);
        return new ProfileFieldPolicy(next);
    }

    /**
     * The least-trusted audience allowed to see a field.
     *
     * @param field the field
     * @return its threshold, defaulting to {@link Audience#MATCH}
     */
    public Audience threshold(final ProfileField field) {
        return minimumAudience.getOrDefault(field, Audience.MATCH);
    }

    /**
     * Whether an audience may see a field.
     *
     * @param field the field
     * @param audience who is looking
     * @return true when the audience is at or above the field's threshold
     */
    public boolean isVisibleTo(final ProfileField field, final Audience audience) {
        return audience.ordinal() >= threshold(field).ordinal();
    }

    /**
     * Every field an audience may see, in declaration order.
     *
     * <p>The single decision point. Preview derivation, the privacy preview of
     * SRS-PRO-008 and every future sharing path call this rather than
     * re-implementing the comparison - which is how SRS-PRO-003 stays true of
     * paths written after it.
     *
     * @param audience who is looking
     * @return the visible fields
     */
    public Set<ProfileField> visibleFields(final Audience audience) {
        final Set<ProfileField> visible = new LinkedHashSet<>();
        for (final ProfileField field : ProfileField.values()) {
            if (isVisibleTo(field, audience)) {
                visible.add(field);
            }
        }
        return Collections.unmodifiableSet(visible);
    }
}
