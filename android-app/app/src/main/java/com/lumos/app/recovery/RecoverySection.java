/*
 * Lumos - android-app/app / What a RecoveryRecord carries, and what it cannot.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-007, SRS-DAT-004
 * ADRs: ADR-003
 * Threading: any
 * Notes: SRS-ONB-007 names five things a recovery export contains: profile
 * content, preferences, block list, match roster metadata and locally stored
 * conversation history. Two of those five belong to features that do not exist
 * yet - matches are WP-04 and conversations are WP-05 - and this enum says so
 * in the type rather than in a comment nobody reads.
 *
 * Listing them as PENDING instead of leaving them out is the honest shape. A
 * bundle that silently contained three of five sections would look complete to
 * every test written today; a bundle whose manifest names all five and marks
 * two unavailable cannot be mistaken for a full backup by the user, by a later
 * work package, or by the restore path.
 *
 * Nothing here is key material and nothing here can be. ADR-003 makes the
 * record content-only - identity keys are hardware-backed, non-exportable and
 * never escrowed - and RecoveryContentTest asserts the identity's bytes are
 * absent from an assembled bundle.
 */

package com.lumos.app.recovery;

import com.lumos.app.store.RecordKind;

/** One section of a recovery bundle. */
public enum RecoverySection {

    /** The user's own profile content (SRS-PRO-001). */
    PROFILE("profile", RecordKind.LOCAL_PROFILE),

    /** Per-field visibility policy (SRS-PRO-003). */
    PROFILE_FIELD_POLICY("profile_field_policy", RecordKind.PROFILE_FIELD_POLICY),

    /** Profile media metadata; the encrypted bytes are not in the bundle. */
    PROFILE_MEDIA("profile_media", RecordKind.PROFILE_MEDIA),

    /** The block list - a safety asset, and the one users most need back. */
    BLOCK_LIST("block_list", RecordKind.BLOCK_ENTRY),

    /** Preferences, which in WP-02 means the Visible Nearby state. */
    PREFERENCES("preferences", RecordKind.DISCOVERY_VISIBILITY),

    /** Match roster metadata. The match domain is WP-04. */
    MATCH_ROSTER("match_roster", null),

    /** Locally stored conversation history. The message domain is WP-05. */
    CONVERSATION_HISTORY("conversation_history", null);

    private final String label;
    private final RecordKind kind;

    RecoverySection(final String label, final RecordKind kind) {
        this.label = label;
        this.kind = kind;
    }

    /**
     * The section's name in the bundle format.
     *
     * <p>A stable string rather than the enum name, so renaming the constant
     * cannot silently invalidate every export a user already holds.
     *
     * @return the wire label
     */
    public String label() {
        return label;
    }

    /**
     * The record kind this section reads and writes, if it exists yet.
     *
     * @return the kind, or null for a section whose feature is a later work package
     */
    public RecordKind kind() {
        return kind;
    }

    /**
     * Whether this section can be filled by the code that exists today.
     *
     * @return true when a store table backs it
     */
    public boolean isAvailable() {
        return kind != null;
    }
}
