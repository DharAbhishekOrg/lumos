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
    APP_LOCK(false),

    /**
     * Peers the user hid from the Nearby feed (SRS-NBY-012). Looked up by a
     * blind index over the authenticated peer identifier - the same shape as
     * the block list, for the same reason: the feed must answer "is this
     * peer hidden?" without the identifier being readable in the file, and
     * the key is identity, never the rotating advertisement (SRS-NBY-013's
     * logic applied to hiding).
     *
     * <p>Added at schema version 4. Hiding is silent: nothing here is ever
     * sent, and the hidden peer cannot observe any of it.
     */
    HIDDEN_PEER(true),

    /**
     * The per-peer interest machine state (TRS Sec. 4.2), looked up by a
     * blind index over the authenticated peer identifier: who the user is
     * interested in - and who declined whom - is at least as private as who
     * they blocked. The payload is the machine state and its timestamps,
     * never a message body.
     *
     * <p>Added at schema version 5 (WP-04 S3).
     */
    INTEREST_RECORD(true),

    /**
     * An established match (SRS-MAT-005, SRS-MAT-007): the stable match
     * identifier, keyed like every peer list by the identity's blind index.
     * TRS-LOC-001 names "match" as its own schema item, and the row outlives
     * interest churn - unmatching changes its state, never silently deletes
     * the fact for the safety flows that may need it (SRS-MAT-010).
     *
     * <p>Added at schema version 5 (WP-04 S3).
     */
    MATCH_RECORD(true),

    /**
     * The single-row rolling-hour interest budget (SRS-MAT-011): sent-at
     * timestamps and nothing else, persisted so a process restart cannot
     * reset the flood limit (SRS-SAF-006).
     *
     * <p>Added at schema version 5 (WP-04 S3).
     */
    INTEREST_BUDGET(false),

    /**
     * One conversation with a matched peer (TRS-LOC-001 "conversation"),
     * looked up by a blind index over the match identifier. It holds the
     * peer's pinned identity and nothing that is a message.
     *
     * <p>Added at schema version 6 (WP-05 S7b).
     */
    CONVERSATION(true),

    /**
     * One message or applied event in a conversation (TRS-LOC-001
     * "message"), looked up by a blind index over the match identifier so a
     * conversation's messages are found without the identifier being
     * readable. Edits, deletes and reactions are applied to the target's
     * record; the body is sealed like every payload.
     *
     * <p>Added at schema version 6 (WP-05 S7b).
     */
    CHAT_MESSAGE(true),

    /**
     * A send waiting for its transport (TRS-LOC-001 "outbox", TRS-PAT-004):
     * the already-sealed envelope and its idempotency key, written in the
     * same transaction as the message it carries (TRS Sec. 8.3) so it
     * survives process death and a retry resends the same bytes.
     *
     * <p>Added at schema version 6 (WP-05 S7b).
     */
    OUTBOX_ITEM(false),

    /**
     * The Double Ratchet state of one conversation, looked up by a blind
     * index over the match identifier. Written in the same transaction as
     * every message it seals or opens, so a restart can never reuse a
     * message key that was already deleted (SRS-RMT-017).
     *
     * <p>Added at schema version 6 (WP-05 S7b).
     */
    RATCHET_SESSION(true),

    /**
     * A message identifier already displayed, kept until it expires
     * (TRS-LOC-005, SRS-RMT-004). The row id IS the message id - a random
     * UUID the sender minted, so it derives from nothing - and the primary
     * key is the index the high-volume lookup needs.
     *
     * <p>Added at schema version 6 (WP-05 S7b).
     */
    MESSAGE_DEDUP(false),

    /**
     * A private signed or one-time prekey scalar, looked up by a blind index
     * over its id. A one-time prekey is deleted in the same transaction that
     * opens the first message it agreed (TRS-CRY-005).
     *
     * <p>Added at schema version 6 (WP-05 S7b).
     */
    PREKEY_SECRET(true),

    /**
     * This device's relay registration: its routing id and the bearer
     * credential the registry issued (TRS-BCK-007), in one row, and the
     * prekey id counters in a second row that outlives a re-registration so
     * no prekey id is ever reused (TRS-CRY-005). Sealed like every row, so the
     * credential never sits in the database in the clear.
     *
     * <p>Added at schema version 7 (WP-05 S7c).
     */
    RELAY_ACCOUNT(false),

    /**
     * One conversation's relay route, looked up by a blind index over the
     * match identifier: the peer's routing id, the capability that lets this
     * device send to it, and the capability this device issued so the peer
     * can send here. Kept apart from CONVERSATION so a session restart can
     * never drop it, and sealed like every row: the relay must never be able
     * to read who talks to whom from this device's disk either.
     *
     * <p>Added at schema version 7 (WP-05 S7c).
     */
    RELAY_ROUTE(true);

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
     * <p>The peer-keyed kinds: BLOCK_ENTRY, HIDDEN_PEER, INTEREST_RECORD,
     * MATCH_RECORD, CONVERSATION, CHAT_MESSAGE and RATCHET_SESSION; and
     * PREKEY_SECRET, keyed by prekey id. Each must answer "what do we hold for this peer?"
     * without the peer identifier being readable in the file - the threat
     * model treats the block list as a safety asset, not a preference
     * (data map Sec. 2.4), and who the user avoided, wanted or matched is
     * the same secret in different tenses.
     *
     * @return true when writes must supply a lookup tag
     */
    public boolean isLookedUpByTag() {
        return lookupByTag;
    }
}
