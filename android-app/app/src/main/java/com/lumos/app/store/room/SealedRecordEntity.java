/*
 * Lumos - android-app/app / Room entities for the sealed-record tables.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-AND-004, SRS-DAT-002, TRS-LOC-002
 * ADRs: ADR-003
 * Threading: any
 * Notes: Nine tables of identical shape, so the columns are declared once on a
 * plain base class and each table is a two-line subclass carrying only its
 * @Entity name. Room inherits columns from a non-entity superclass, which is
 * what keeps this from being nine copies of the same six fields.
 *
 * Room cannot parameterise a table name, which is exactly why it sits BEHIND
 * the SqlDatabase seam rather than replacing it: the generic store addresses
 * all nine kinds through one code path, and Room owns the database, the schema
 * and the migrations underneath. That division is the owner's ruling of
 * 2026-08-14 expressed in types.
 *
 * The field names ARE the canonical column names, so Room's exported schema
 * JSON is directly comparable with LocalSchema - which RoomSchemaParityTest
 * does at build time, on any host, without a device.
 */

package com.lumos.app.store.room;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** The columns every sealed-record table carries. */
public class SealedRecordEntity {

    /** Opaque stable row identifier (TRS-LOC-010). */
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "id")
    public String id = "";

    /** The AEAD-sealed record payload. */
    @NonNull
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB)
    public byte[] payload = new byte[0];

    /** Blind-index tag, or null for kinds with no natural-key lookup. */
    @Nullable
    @ColumnInfo(name = "lookup_tag", typeAffinity = ColumnInfo.BLOB)
    public byte[] lookupTag;

    /** Creation time, UTC milliseconds since the epoch. */
    @ColumnInfo(name = "created_at")
    public long createdAt;

    /** Last-update time, UTC milliseconds since the epoch. */
    @ColumnInfo(name = "updated_at")
    public long updatedAt;

    /** The schema version that sealed this row's payload. */
    @ColumnInfo(name = "payload_version")
    public int payloadVersion;

    /** The device identity table. */
    @Entity(tableName = "identity_key_record")
    public static class IdentityKeyRecordRow extends SealedRecordEntity { }

    /** The content-only recovery table (ADR-003). */
    @Entity(tableName = "recovery_record")
    public static class RecoveryRecordRow extends SealedRecordEntity { }

    /** The user's own profile. */
    @Entity(tableName = "local_profile")
    public static class LocalProfileRow extends SealedRecordEntity { }

    /** Per-field visibility policy (SRS-PRO-003). */
    @Entity(tableName = "profile_field_policy")
    public static class ProfileFieldPolicyRow extends SealedRecordEntity { }

    /** Profile media metadata. */
    @Entity(tableName = "profile_media")
    public static class ProfileMediaRow extends SealedRecordEntity { }

    /** Blocked peers, looked up by blind index. */
    @Entity(tableName = "block_entry", indices = {@androidx.room.Index("lookup_tag")})
    public static class BlockEntryRow extends SealedRecordEntity { }

    /** Local diagnostic events. */
    @Entity(tableName = "diagnostic_event")
    public static class DiagnosticEventRow extends SealedRecordEntity { }

    /** Onboarding consent evidence (SRS-ONB-001, SRS-ONB-002). Schema version 2. */
    @Entity(tableName = "consent_record")
    public static class ConsentRecordRow extends SealedRecordEntity { }

    /** Visible Nearby state (SRS-ONB-008). Schema version 2. */
    @Entity(tableName = "discovery_visibility")
    public static class DiscoveryVisibilityRow extends SealedRecordEntity { }

    /** App Lock choice (ADR-010). Schema version 3. */
    @Entity(tableName = "app_lock")
    public static class AppLockRow extends SealedRecordEntity { }

    /** Hidden Nearby peers (SRS-NBY-012). Schema version 4. */
    @Entity(tableName = "hidden_peer")
    public static class HiddenPeerRow extends SealedRecordEntity { }

    /** Per-peer interest machine state (TRS Sec. 4.2). Schema version 5. */
    @Entity(tableName = "interest_record")
    public static class InterestRecordRow extends SealedRecordEntity { }

    /** Established matches (SRS-MAT-005/007). Schema version 5. */
    @Entity(tableName = "match_record")
    public static class MatchRecordRow extends SealedRecordEntity { }

    /** The rolling-hour interest budget (SRS-MAT-011). Schema version 5. */
    @Entity(tableName = "interest_budget")
    public static class InterestBudgetRow extends SealedRecordEntity { }

    /** A conversation with a matched peer. Schema version 6. */
    @Entity(tableName = "conversation")
    public static class ConversationRow extends SealedRecordEntity { }

    /** One message or applied event. Schema version 6. */
    @Entity(tableName = "chat_message")
    public static class ChatMessageRow extends SealedRecordEntity { }

    /** A send waiting for its transport (TRS-PAT-004). Schema version 6. */
    @Entity(tableName = "outbox_item")
    public static class OutboxItemRow extends SealedRecordEntity { }

    /** One conversation's Double Ratchet state. Schema version 6. */
    @Entity(tableName = "ratchet_session")
    public static class RatchetSessionRow extends SealedRecordEntity { }

    /** A displayed message id, kept until it expires. Schema version 6. */
    @Entity(tableName = "message_dedup")
    public static class MessageDedupRow extends SealedRecordEntity { }

    /** A private prekey scalar. Schema version 6. */
    @Entity(tableName = "prekey_secret")
    public static class PrekeySecretRow extends SealedRecordEntity { }

    /** This device's relay registration. Schema version 7. */
    @Entity(tableName = "relay_account")
    public static class RelayAccountRow extends SealedRecordEntity { }

    /** One conversation's relay route. Schema version 7. */
    @Entity(tableName = "relay_route")
    public static class RelayRouteRow extends SealedRecordEntity { }

    /** The person's chat settings. Schema version 8. */
    @Entity(tableName = "chat_settings")
    public static class ChatSettingsRow extends SealedRecordEntity { }
}
