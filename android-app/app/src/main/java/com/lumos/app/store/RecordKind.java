/*
 * Lumos - android-app/app / The TRS Sec. 8.1 entity subset WP-02 persists.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-LOC-001, SRS-DAT-002
 * ADRs: ADR-003
 * Threading: any
 * Notes: Nine of the sixteen domains TRS-LOC-001 enumerates - the ones WP-02's
 * own features touch. The rest (presence, match, conversation, message, outbox,
 * chunk state, dedup, venue, voucher, entitlement, configuration) arrive with
 * the work packages that own them, which is why TRS-LOC-001 stays not_started
 * until the set is complete rather than being claimed at seven-sixteenths.
 *
 * The enum name is the domain concept; the table name is its snake_case form,
 * derived once here so a table name cannot be mistyped at a call site. Names
 * are deliberately generic ("profile_media", not "profile_photos"): the table
 * name is visible to anyone who reads the file header, so it says what KIND of
 * record lives there and nothing about its contents (threat model LT-16).
 */

package com.lumos.app.store;

import java.util.Locale;

/** The record kinds the WP-02 local store holds, one table each. */
public enum RecordKind {

    /** The device identity: public halves and the KEK-wrapped private envelope. */
    IDENTITY_KEY_RECORD(false),

    /** Content-only recovery material per ADR-003; never key material. */
    RECOVERY_RECORD(false),

    /** The user's own profile content. */
    LOCAL_PROFILE(false),

    /** Per-field visibility policy (SRS-PRO-003). */
    PROFILE_FIELD_POLICY(false),

    /** Profile media metadata; the bytes live in the object store. */
    PROFILE_MEDIA(false),

    /** Blocked peers. Looked up by a blind index over the peer identifier. */
    BLOCK_ENTRY(true),

    /** Local diagnostic events, redaction-checked before they are written. */
    DIAGNOSTIC_EVENT(false),

    /**
     * The onboarding consent evidence: the age-gate affirmation and the legal
     * document versions the user accepted (SRS-ONB-001, SRS-ONB-002).
     *
     * <p>Added at schema version 2. The declared date of birth is NOT here and
     * is not anywhere - the gate evaluates it and keeps only the answer.
     */
    CONSENT_RECORD(false),

    /**
     * Whether the user has turned Visible Nearby on, and when (SRS-ONB-008).
     *
     * <p>Added at schema version 2. Its absence reads as off, so a device that
     * has never been told otherwise is not discoverable - which is the whole
     * requirement, expressed as the default rather than as a code path.
     */
    DISCOVERY_VISIBILITY(false),

    /**
     * Whether the user has turned App Lock on (ADR-010).
     *
     * <p>Added at schema version 3. Its absence reads as not enabled - App
     * Lock is a protection the user adds, and the record only ever carries
     * the choice, never anything that could unlock it.
     */
    APP_LOCK(false);

    private final boolean lookupByTag;

    RecordKind(final boolean lookupByTag) {
        this.lookupByTag = lookupByTag;
    }

    /**
     * The table name for this kind.
     *
     * @return the lower-case snake_case table name
     */
    public String table() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether this kind is looked up by a blind index over a natural key.
     *
     * <p>Only BLOCK_ENTRY is today. A block list must answer "is this peer
     * blocked?" without the peer identifier being readable in the file - the
     * threat model treats the block list as a safety asset, not a preference
     * (data map Sec. 2.4).
     *
     * @return true when writes must supply a lookup tag
     */
    public boolean isLookedUpByTag() {
        return lookupByTag;
    }
}
