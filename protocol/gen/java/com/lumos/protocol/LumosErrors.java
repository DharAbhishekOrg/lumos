/*
 * Lumos - protocol/gen/java / Generated stable protocol error codes.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-CONST-001, TRS-CONST-002, TRS-CONST-005, TRS-REP-002
 * ADRs: ADR-002, ADR-004
 * Threading: any
 * GENERATED - DO NOT EDIT. Generator: tools/constantsgen
 * Source register: protocol/constants/lumos_constants.json
 * Baseline: TRS v1.1 Sec. 23 / EDP v1.0 Sec. 4 (ADR-004)
 * constants_hash: sha256:8bb65dc5e630eb6df4261d8eb75296f6cdc5191f09de248c33dfbe2803e6e373
 */
package com.lumos.protocol;

import java.util.Map;

/** Stable machine-readable error codes of TRS-PROTO-013. Append-only. */
public final class LumosErrors {

    private LumosErrors() {
        throw new AssertionError("no instances");
    }

    /** ERR_FRAME_BAD_MAGIC (1, framing): Frame magic did not match framing.FRAME_MAGIC. */
    public static final String ERR_FRAME_BAD_MAGIC = "ERR_FRAME_BAD_MAGIC";

    /** ERR_FRAME_UNSUPPORTED_VERSION (2, framing): Protocol major version differs from this build's. */
    public static final String ERR_FRAME_UNSUPPORTED_VERSION = "ERR_FRAME_UNSUPPORTED_VERSION";

    /** ERR_FRAME_UNKNOWN_TYPE (3, framing): Unknown message class on a frame marked critical. */
    public static final String ERR_FRAME_UNKNOWN_TYPE = "ERR_FRAME_UNKNOWN_TYPE";

    /** ERR_FRAME_BAD_HEADER_LENGTH (4, framing): header_length disagreed with the fixed length for this protocol version. */
    public static final String ERR_FRAME_BAD_HEADER_LENGTH = "ERR_FRAME_BAD_HEADER_LENGTH";

    /** ERR_FRAME_TRUNCATED (5, framing): Fewer bytes available than header_length or payload_length require. */
    public static final String ERR_FRAME_TRUNCATED = "ERR_FRAME_TRUNCATED";

    /** ERR_FRAME_RESERVED_FLAG (6, framing): A reserved flag bit was set on a frame marked critical. */
    public static final String ERR_FRAME_RESERVED_FLAG = "ERR_FRAME_RESERVED_FLAG";

    /** ERR_FRAME_RESERVED_FIELD (7, framing): The reserved header byte was non-zero on a frame marked critical. */
    public static final String ERR_FRAME_RESERVED_FIELD = "ERR_FRAME_RESERVED_FIELD";

    /** ERR_FRAME_PAYLOAD_TOO_LARGE (8, framing): payload_length exceeded the maximum for its message class. */
    public static final String ERR_FRAME_PAYLOAD_TOO_LARGE = "ERR_FRAME_PAYLOAD_TOO_LARGE";

    /** ERR_PAYLOAD_DECOMPRESSION_BOMB (20, payload): Declared or actual decompressed size exceeded the class maximum. */
    public static final String ERR_PAYLOAD_DECOMPRESSION_BOMB = "ERR_PAYLOAD_DECOMPRESSION_BOMB";

    /** ERR_PAYLOAD_MALFORMED (21, payload): Payload did not decode against its class schema. */
    public static final String ERR_PAYLOAD_MALFORMED = "ERR_PAYLOAD_MALFORMED";

    /** ERR_TEXT_UNKNOWN_EVENT_KIND (22, payload): Unknown inner event_kind on a critical TEXT_MESSAGE (AMD-005). */
    public static final String ERR_TEXT_UNKNOWN_EVENT_KIND = "ERR_TEXT_UNKNOWN_EVENT_KIND";

    /** ERR_TEXT_MISSING_TARGET (23, payload): An edit, delete or reaction arrived without target_message_id. */
    public static final String ERR_TEXT_MISSING_TARGET = "ERR_TEXT_MISSING_TARGET";

    /** ERR_CRYPTO_AEAD_AUTH_FAILED (40, crypto): AEAD authentication failed; header-as-AAD mismatch or tampering. */
    public static final String ERR_CRYPTO_AEAD_AUTH_FAILED = "ERR_CRYPTO_AEAD_AUTH_FAILED";

    /** ERR_CRYPTO_MAX_SKIP_EXCEEDED (41, crypto): Ratchet skip exceeded sessions_remote_text.MAX_SKIP_PER_CHAIN. */
    public static final String ERR_CRYPTO_MAX_SKIP_EXCEEDED = "ERR_CRYPTO_MAX_SKIP_EXCEEDED";

    /** ERR_CRYPTO_SKIPPED_KEY_CAP (42, crypto): Stored skipped keys would exceed SKIPPED_KEYS_PER_SESSION_CAP. */
    public static final String ERR_CRYPTO_SKIPPED_KEY_CAP = "ERR_CRYPTO_SKIPPED_KEY_CAP";

    /** ERR_CRYPTO_PREKEY_SIGNATURE_INVALID (44, crypto): X3DH prekey bundle signature failed verification (identity-DH binding or signed prekey). */
    public static final String ERR_CRYPTO_PREKEY_SIGNATURE_INVALID = "ERR_CRYPTO_PREKEY_SIGNATURE_INVALID";

    /** ERR_CRYPTO_RECOVERY_OPEN_FAILED (45, crypto): A RecoveryRecord could not be opened. ONE code for every cause - wrong passphrase, tampered header, corrupted ciphertext, unknown format - because distinguishing them is an oracle over the passphrase (TRS-CRY-013). */
    public static final String ERR_CRYPTO_RECOVERY_OPEN_FAILED = "ERR_CRYPTO_RECOVERY_OPEN_FAILED";

    /** ERR_CRYPTO_RECOVERY_PARAMS_REJECTED (46, crypto): A RecoveryRecord declares Argon2id work factors below the register minima. Reported separately from OPEN_FAILED on purpose: this is a property of the FILE, readable without any passphrase attempt, so naming it discloses nothing and warning the user their old backup is too weak to trust is worth more than uniformity. */
    public static final String ERR_CRYPTO_RECOVERY_PARAMS_REJECTED = "ERR_CRYPTO_RECOVERY_PARAMS_REJECTED";

    /** ERR_CRYPTO_REPLAY (43, crypto): message_id already seen within the dedup window. */
    public static final String ERR_CRYPTO_REPLAY = "ERR_CRYPTO_REPLAY";

    /** ERR_MESH_HOP_VIOLATION (60, mesh): A relay attempted to raise hop_limit or TTL (TRS-PROTO-012). */
    public static final String ERR_MESH_HOP_VIOLATION = "ERR_MESH_HOP_VIOLATION";

    /** ERR_MESH_TTL_EXPIRED (61, mesh): Frame TTL expired before delivery. */
    public static final String ERR_MESH_TTL_EXPIRED = "ERR_MESH_TTL_EXPIRED";

    /** ERR_TRANSPORT_LOCAL_ONLY_VIOLATION (62, mesh): A frame flagged local-only was offered to the internet relay (SRS-TRN-009). */
    public static final String ERR_TRANSPORT_LOCAL_ONLY_VIOLATION = "ERR_TRANSPORT_LOCAL_ONLY_VIOLATION";

    /** ERR_MEDIA_DIGEST_MISMATCH (100, media): Reassembled object did not match the SHA-256 digest declared in OBJECT_META. */
    public static final String ERR_MEDIA_DIGEST_MISMATCH = "ERR_MEDIA_DIGEST_MISMATCH";

    /** ERR_MEDIA_OBJECT_KEY_UNWRAP_FAILED (101, media): The object key could not be unwrapped from the active match session. */
    public static final String ERR_MEDIA_OBJECT_KEY_UNWRAP_FAILED = "ERR_MEDIA_OBJECT_KEY_UNWRAP_FAILED";

    /** ERR_MEDIA_CHUNK_OUT_OF_RANGE (102, media): A chunk index fell outside the plan declared in OBJECT_META. */
    public static final String ERR_MEDIA_CHUNK_OUT_OF_RANGE = "ERR_MEDIA_CHUNK_OUT_OF_RANGE";

    /** ERR_ADV_AUTH_TAG_MISMATCH (110, adv): Advertisement authTag did not match the expected HMAC prefix for the epoch; the advertisement is discarded before connection. */
    public static final String ERR_ADV_AUTH_TAG_MISMATCH = "ERR_ADV_AUTH_TAG_MISMATCH";

    /** ERR_FRAG_HEADER_MALFORMED (120, transport): Fragment shorter than its header, a reserved flag bit set, a FIRST without a usable total-length prefix or a zero total, a non-zero index on a FIRST, or a fragment carrying no content byte (TRS-PROTO-010 layer rules). */
    public static final String ERR_FRAG_HEADER_MALFORMED = "ERR_FRAG_HEADER_MALFORMED";

    /** ERR_FRAG_TOTAL_TOO_LARGE (121, transport): Declared reassembled length exceeds FRAG_REASSEMBLY_MAX_BYTES, or a split would need more than FRAG_MAX_FRAGMENTS. Rejected before any reassembly allocation (TRS Sec. 9.4). */
    public static final String ERR_FRAG_TOTAL_TOO_LARGE = "ERR_FRAG_TOTAL_TOO_LARGE";

    /** ERR_FRAG_SEQUENCE_GAP (122, transport): A continuation fragment skipped an index, or continued an assembly the receiver does not have open or remembered. */
    public static final String ERR_FRAG_SEQUENCE_GAP = "ERR_FRAG_SEQUENCE_GAP";

    /** ERR_FRAG_LENGTH_MISMATCH (123, transport): Fragment content overran the declared total, or a LAST fragment completed with fewer bytes than declared. */
    public static final String ERR_FRAG_LENGTH_MISMATCH = "ERR_FRAG_LENGTH_MISMATCH";

    /** ERR_FRAG_REASSEMBLY_TIMEOUT (124, transport): A continuation arrived for an assembly that already expired under REASSEMBLY_TIMEOUT_SEC (TRS-PROTO-010 reassembly timeout). */
    public static final String ERR_FRAG_REASSEMBLY_TIMEOUT = "ERR_FRAG_REASSEMBLY_TIMEOUT";

    /** ERR_HELLO_MALFORMED (130, session): A HELLO or HELLO_ACK payload was not a well-formed hello_v1: wrong length, or an unsupported hello_version. */
    public static final String ERR_HELLO_MALFORMED = "ERR_HELLO_MALFORMED";

    /** ERR_HELLO_VERSION_UNSUPPORTED (131, session): The in-band protocol_version's major nibble differs from this build's (TRS-PROTO-017 re-confirmation; same rule as the frame header, ADR-007). */
    public static final String ERR_HELLO_VERSION_UNSUPPORTED = "ERR_HELLO_VERSION_UNSUPPORTED";

    /** ERR_HELLO_BINDING_INVALID (132, session): The ADR-008 binding signature inside a HELLO or HELLO_ACK did not verify: the claimed identity-DH key does not belong to the claimed Ed25519 identity. */
    public static final String ERR_HELLO_BINDING_INVALID = "ERR_HELLO_BINDING_INVALID";

    /** ERR_HELLO_SIGNATURE_INVALID (133, session): A HELLO_ACK auth signature did not verify over this side's HELLO transcript - a spoofed identity or a tampered exchange. */
    public static final String ERR_HELLO_SIGNATURE_INVALID = "ERR_HELLO_SIGNATURE_INVALID";

    /** ERR_HELLO_NONCE_MISMATCH (134, session): A HELLO_ACK answered a nonce this side did not issue, or did not issue recently - the replay shape (TRS-ENV-002). */
    public static final String ERR_HELLO_NONCE_MISMATCH = "ERR_HELLO_NONCE_MISMATCH";

    /** ERR_CONFIG_SIGNATURE_INVALID (80, config): Signed configuration or voucher failed signature verification. */
    public static final String ERR_CONFIG_SIGNATURE_INVALID = "ERR_CONFIG_SIGNATURE_INVALID";

    /** ERR_CONFIG_SCHEMA_INVALID (81, config): Configuration failed schema validation, including an out-of-bounds override (TRS-CONST-002). */
    public static final String ERR_CONFIG_SCHEMA_INVALID = "ERR_CONFIG_SCHEMA_INVALID";

    /** ERR_CONFIG_ROLLBACK (82, config): Configuration version was older than the active one. */
    public static final String ERR_CONFIG_ROLLBACK = "ERR_CONFIG_ROLLBACK";

    /** ERR_CONFIG_EXPIRED (83, config): Configuration or voucher validity window has passed. */
    public static final String ERR_CONFIG_EXPIRED = "ERR_CONFIG_EXPIRED";

    /** ERR_CONFIG_WRONG_ENVIRONMENT (84, config): Package environment did not match this build's. */
    public static final String ERR_CONFIG_WRONG_ENVIRONMENT = "ERR_CONFIG_WRONG_ENVIRONMENT";

    /** ERR_CONFIG_CONSTANTS_HASH_MISMATCH (85, config): Package constants_hash did not match this build's register (TRS-CONST-005). */
    public static final String ERR_CONFIG_CONSTANTS_HASH_MISMATCH = "ERR_CONFIG_CONSTANTS_HASH_MISMATCH";

    /** ERR_VOUCHER_NONCE_REPLAY (86, config): Voucher nonce was already redeemed within VOUCHER_NONCE_CACHE. */
    public static final String ERR_VOUCHER_NONCE_REPLAY = "ERR_VOUCHER_NONCE_REPLAY";

    /** ERR_VOUCHER_REVOKED (87, config): Voucher appears in the revocation set. */
    public static final String ERR_VOUCHER_REVOKED = "ERR_VOUCHER_REVOKED";

    /** Maps each stable code to its registry number. */
    public static final Map<String, Integer> NUMERIC = Map.ofEntries(
        Map.entry(ERR_FRAME_BAD_MAGIC, 1),
        Map.entry(ERR_FRAME_UNSUPPORTED_VERSION, 2),
        Map.entry(ERR_FRAME_UNKNOWN_TYPE, 3),
        Map.entry(ERR_FRAME_BAD_HEADER_LENGTH, 4),
        Map.entry(ERR_FRAME_TRUNCATED, 5),
        Map.entry(ERR_FRAME_RESERVED_FLAG, 6),
        Map.entry(ERR_FRAME_RESERVED_FIELD, 7),
        Map.entry(ERR_FRAME_PAYLOAD_TOO_LARGE, 8),
        Map.entry(ERR_PAYLOAD_DECOMPRESSION_BOMB, 20),
        Map.entry(ERR_PAYLOAD_MALFORMED, 21),
        Map.entry(ERR_TEXT_UNKNOWN_EVENT_KIND, 22),
        Map.entry(ERR_TEXT_MISSING_TARGET, 23),
        Map.entry(ERR_CRYPTO_AEAD_AUTH_FAILED, 40),
        Map.entry(ERR_CRYPTO_MAX_SKIP_EXCEEDED, 41),
        Map.entry(ERR_CRYPTO_SKIPPED_KEY_CAP, 42),
        Map.entry(ERR_CRYPTO_PREKEY_SIGNATURE_INVALID, 44),
        Map.entry(ERR_CRYPTO_RECOVERY_OPEN_FAILED, 45),
        Map.entry(ERR_CRYPTO_RECOVERY_PARAMS_REJECTED, 46),
        Map.entry(ERR_CRYPTO_REPLAY, 43),
        Map.entry(ERR_MESH_HOP_VIOLATION, 60),
        Map.entry(ERR_MESH_TTL_EXPIRED, 61),
        Map.entry(ERR_TRANSPORT_LOCAL_ONLY_VIOLATION, 62),
        Map.entry(ERR_MEDIA_DIGEST_MISMATCH, 100),
        Map.entry(ERR_MEDIA_OBJECT_KEY_UNWRAP_FAILED, 101),
        Map.entry(ERR_MEDIA_CHUNK_OUT_OF_RANGE, 102),
        Map.entry(ERR_ADV_AUTH_TAG_MISMATCH, 110),
        Map.entry(ERR_FRAG_HEADER_MALFORMED, 120),
        Map.entry(ERR_FRAG_TOTAL_TOO_LARGE, 121),
        Map.entry(ERR_FRAG_SEQUENCE_GAP, 122),
        Map.entry(ERR_FRAG_LENGTH_MISMATCH, 123),
        Map.entry(ERR_FRAG_REASSEMBLY_TIMEOUT, 124),
        Map.entry(ERR_HELLO_MALFORMED, 130),
        Map.entry(ERR_HELLO_VERSION_UNSUPPORTED, 131),
        Map.entry(ERR_HELLO_BINDING_INVALID, 132),
        Map.entry(ERR_HELLO_SIGNATURE_INVALID, 133),
        Map.entry(ERR_HELLO_NONCE_MISMATCH, 134),
        Map.entry(ERR_CONFIG_SIGNATURE_INVALID, 80),
        Map.entry(ERR_CONFIG_SCHEMA_INVALID, 81),
        Map.entry(ERR_CONFIG_ROLLBACK, 82),
        Map.entry(ERR_CONFIG_EXPIRED, 83),
        Map.entry(ERR_CONFIG_WRONG_ENVIRONMENT, 84),
        Map.entry(ERR_CONFIG_CONSTANTS_HASH_MISMATCH, 85),
        Map.entry(ERR_VOUCHER_NONCE_REPLAY, 86),
        Map.entry(ERR_VOUCHER_REVOKED, 87)
    );

    /**
     * Reports whether a code is in the stable registry.
     *
     * @param code a candidate stable error code
     * @return true when the registry defines it
     */
    public static boolean known(final String code) {
        return NUMERIC.containsKey(code);
    }
}
