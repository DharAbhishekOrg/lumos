/*
 * Lumos - protocol/gen/java / Generated Lumos protocol and policy constants.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-CONST-001, TRS-CONST-002, TRS-CONST-005, TRS-REP-002
 * ADRs: ADR-002, ADR-004
 * Threading: any
 * GENERATED - DO NOT EDIT. Generator: tools/constantsgen
 * Source register: protocol/constants/lumos_constants.json
 * Baseline: TRS v1.1 Sec. 23 / EDP v1.0 Sec. 4 (ADR-004)
 * constants_hash: sha256:7315f92990eb8e431d0d0c392a73822035bcc96c1c469fc4fa734d75cfa278d5
 */
package com.lumos.protocol;

import java.util.List;

/** Generated Lumos constants. Consume these; never re-declare a value. */
public final class LumosConstants {

    private LumosConstants() {
        throw new AssertionError("no instances");
    }

    /** The one fixed 128-bit Lumos GATT service UUID (ADR-002). */
    public static final String SERVICE_UUID = "E853EB6A-775C-4A49-A6BF-320E6A5CC609";

    /** Binds generated code to the register that produced it (TRS-CONST-005). */
    public static final String CONSTANTS_HASH = "sha256:7315f92990eb8e431d0d0c392a73822035bcc96c1c469fc4fa734d75cfa278d5";

    /** Register schema version. */
    public static final int SCHEMA_VERSION = 1;

    /** Specification sections this register was derived from. */
    public static final String BASELINE = "TRS v1.1 Sec. 23 / EDP v1.0 Sec. 4 (ADR-004)";

    // Framing

    /** framing.FRAME_FLAGS_RESERVED_MASK.default */
    public static final int FRAME_FLAGS_RESERVED_MASK_DEFAULT = 65520;

    /** framing.FRAME_FLAGS_RESERVED_MASK.overridable */
    public static final boolean FRAME_FLAGS_RESERVED_MASK_OVERRIDABLE = false;

    /** framing.FRAME_FLAG_COMPRESSED.default */
    public static final int FRAME_FLAG_COMPRESSED_DEFAULT = 2;

    /** framing.FRAME_FLAG_COMPRESSED.overridable */
    public static final boolean FRAME_FLAG_COMPRESSED_OVERRIDABLE = false;

    /** framing.FRAME_FLAG_CRITICAL.default */
    public static final int FRAME_FLAG_CRITICAL_DEFAULT = 1;

    /** framing.FRAME_FLAG_CRITICAL.overridable */
    public static final boolean FRAME_FLAG_CRITICAL_OVERRIDABLE = false;

    /** framing.FRAME_FLAG_LOCAL_ONLY.default */
    public static final int FRAME_FLAG_LOCAL_ONLY_DEFAULT = 8;

    /** framing.FRAME_FLAG_LOCAL_ONLY.overridable */
    public static final boolean FRAME_FLAG_LOCAL_ONLY_OVERRIDABLE = false;

    /** framing.FRAME_FLAG_RELAY_ELIGIBLE.default */
    public static final int FRAME_FLAG_RELAY_ELIGIBLE_DEFAULT = 4;

    /** framing.FRAME_FLAG_RELAY_ELIGIBLE.overridable */
    public static final boolean FRAME_FLAG_RELAY_ELIGIBLE_OVERRIDABLE = false;

    /** framing.FRAME_HEADER_LEN_BYTES.default */
    public static final int FRAME_HEADER_LEN_BYTES_DEFAULT = 58;

    /** framing.FRAME_HEADER_LEN_BYTES.overridable */
    public static final boolean FRAME_HEADER_LEN_BYTES_OVERRIDABLE = false;

    /** framing.FRAME_MAGIC.default */
    public static final int FRAME_MAGIC_DEFAULT = 19533;

    /** framing.FRAME_MAGIC.overridable */
    public static final boolean FRAME_MAGIC_OVERRIDABLE = false;

    /** framing.PROTOCOL_VERSION.encoded */
    public static final int PROTOCOL_VERSION_ENCODED = 16;

    /** framing.PROTOCOL_VERSION.major */
    public static final int PROTOCOL_VERSION_MAJOR = 1;

    /** framing.PROTOCOL_VERSION.minor */
    public static final int PROTOCOL_VERSION_MINOR = 0;

    /** framing.PROTOCOL_VERSION.overridable */
    public static final boolean PROTOCOL_VERSION_OVERRIDABLE = false;

    // Identity discovery

    /** identity_discovery.ADV_AUTH_TAG_LEN.default */
    public static final int ADV_AUTH_TAG_LEN_DEFAULT = 4;

    /** identity_discovery.ADV_AUTH_TAG_LEN.overridable */
    public static final boolean ADV_AUTH_TAG_LEN_OVERRIDABLE = false;

    /** identity_discovery.ADV_V1_LAYOUT.auth_tag_len */
    public static final int ADV_V1_LAYOUT_AUTH_TAG_LEN = 4;

    /** identity_discovery.ADV_V1_LAYOUT.eid_len */
    public static final int ADV_V1_LAYOUT_EID_LEN = 12;

    /** identity_discovery.ADV_V1_LAYOUT.mode_flags_len */
    public static final int ADV_V1_LAYOUT_MODE_FLAGS_LEN = 1;

    /** identity_discovery.ADV_V1_LAYOUT.overridable */
    public static final boolean ADV_V1_LAYOUT_OVERRIDABLE = false;

    /** identity_discovery.ADV_V1_LAYOUT.total_len */
    public static final int ADV_V1_LAYOUT_TOTAL_LEN = 18;

    /** identity_discovery.ADV_V1_LAYOUT.version_len */
    public static final int ADV_V1_LAYOUT_VERSION_LEN = 1;

    /** identity_discovery.ADV_V1_SCAN_RESPONSE_LAYOUT.capability_bits_len */
    public static final int ADV_V1_SCAN_RESPONSE_LAYOUT_CAPABILITY_BITS_LEN = 2;

    /** identity_discovery.ADV_V1_SCAN_RESPONSE_LAYOUT.overridable */
    public static final boolean ADV_V1_SCAN_RESPONSE_LAYOUT_OVERRIDABLE = false;

    /** identity_discovery.ADV_V1_SCAN_RESPONSE_LAYOUT.total_len */
    public static final int ADV_V1_SCAN_RESPONSE_LAYOUT_TOTAL_LEN = 6;

    /** identity_discovery.ADV_V1_SCAN_RESPONSE_LAYOUT.venue_hint_len */
    public static final int ADV_V1_SCAN_RESPONSE_LAYOUT_VENUE_HINT_LEN = 4;

    /** identity_discovery.EID_ROTATION_SEC.default */
    public static final int EID_ROTATION_SEC_DEFAULT = 600;

    /** identity_discovery.EID_ROTATION_SEC.max */
    public static final int EID_ROTATION_SEC_MAX = 900;

    /** identity_discovery.EID_ROTATION_SEC.min */
    public static final int EID_ROTATION_SEC_MIN = 300;

    /** identity_discovery.EID_ROTATION_SEC.overridable */
    public static final boolean EID_ROTATION_SEC_OVERRIDABLE = true;

    /** identity_discovery.INTEREST_RATE_LIMIT_PER_HOUR.default */
    public static final int INTEREST_RATE_LIMIT_PER_HOUR_DEFAULT = 20;

    /** identity_discovery.INTEREST_RATE_LIMIT_PER_HOUR.max */
    public static final int INTEREST_RATE_LIMIT_PER_HOUR_MAX = 60;

    /** identity_discovery.INTEREST_RATE_LIMIT_PER_HOUR.min */
    public static final int INTEREST_RATE_LIMIT_PER_HOUR_MIN = 5;

    /** identity_discovery.INTEREST_RATE_LIMIT_PER_HOUR.overridable */
    public static final boolean INTEREST_RATE_LIMIT_PER_HOUR_OVERRIDABLE = true;

    /** identity_discovery.MAX_CONCURRENT_GATT_LINKS.default */
    public static final int MAX_CONCURRENT_GATT_LINKS_DEFAULT = 4;

    /** identity_discovery.MAX_CONCURRENT_GATT_LINKS.max */
    public static final int MAX_CONCURRENT_GATT_LINKS_MAX = 7;

    /** identity_discovery.MAX_CONCURRENT_GATT_LINKS.min */
    public static final int MAX_CONCURRENT_GATT_LINKS_MIN = 2;

    /** identity_discovery.MAX_CONCURRENT_GATT_LINKS.overridable */
    public static final boolean MAX_CONCURRENT_GATT_LINKS_OVERRIDABLE = true;

    /** identity_discovery.NEARBY_SYNTHETIC_LOAD_TARGET.default */
    public static final int NEARBY_SYNTHETIC_LOAD_TARGET_DEFAULT = 100;

    /** identity_discovery.NEARBY_SYNTHETIC_LOAD_TARGET.overridable */
    public static final boolean NEARBY_SYNTHETIC_LOAD_TARGET_OVERRIDABLE = false;

    /** identity_discovery.NEARBY_SYNTHETIC_LOAD_TARGET.test_only */
    public static final boolean NEARBY_SYNTHETIC_LOAD_TARGET_TEST_ONLY = true;

    /** identity_discovery.PRESENCE_AGEOUT_SEC.default */
    public static final int PRESENCE_AGEOUT_SEC_DEFAULT = 90;

    /** identity_discovery.PRESENCE_AGEOUT_SEC.max */
    public static final int PRESENCE_AGEOUT_SEC_MAX = 300;

    /** identity_discovery.PRESENCE_AGEOUT_SEC.min */
    public static final int PRESENCE_AGEOUT_SEC_MIN = 30;

    /** identity_discovery.PRESENCE_AGEOUT_SEC.overridable */
    public static final boolean PRESENCE_AGEOUT_SEC_OVERRIDABLE = true;

    /** identity_discovery.RESCAN_LADDER.calibrated */
    public static final boolean RESCAN_LADDER_CALIBRATED = true;

    /** identity_discovery.RESCAN_LADDER.max_steps */
    public static final int RESCAN_LADDER_MAX_STEPS = 3;

    /** identity_discovery.RESCAN_LADDER.max_steps_max */
    public static final int RESCAN_LADDER_MAX_STEPS_MAX = 5;

    /** identity_discovery.RESCAN_LADDER.max_steps_min */
    public static final int RESCAN_LADDER_MAX_STEPS_MIN = 1;

    /** identity_discovery.RESCAN_LADDER.overridable */
    public static final boolean RESCAN_LADDER_OVERRIDABLE = true;

    /** identity_discovery.RESCAN_LADDER.step_pct */
    public static final int RESCAN_LADDER_STEP_PCT = 50;

    /** identity_discovery.RESCAN_LADDER.step_pct_max */
    public static final int RESCAN_LADDER_STEP_PCT_MAX = 100;

    /** identity_discovery.RESCAN_LADDER.step_pct_min */
    public static final int RESCAN_LADDER_STEP_PCT_MIN = 25;

    /** identity_discovery.RESCAN_LADDER.trigger_empty_cycles */
    public static final int RESCAN_LADDER_TRIGGER_EMPTY_CYCLES = 2;

    /** identity_discovery.RESCAN_LADDER.trigger_empty_cycles_max */
    public static final int RESCAN_LADDER_TRIGGER_EMPTY_CYCLES_MAX = 4;

    /** identity_discovery.RESCAN_LADDER.trigger_empty_cycles_min */
    public static final int RESCAN_LADDER_TRIGGER_EMPTY_CYCLES_MIN = 1;

    /** identity_discovery.RSSI_BANDS_DBM.calibrated */
    public static final boolean RSSI_BANDS_DBM_CALIBRATED = true;

    /** identity_discovery.RSSI_BANDS_DBM.nearby_gte */
    public static final int RSSI_BANDS_DBM_NEARBY_GTE = -75;

    /** identity_discovery.RSSI_BANDS_DBM.nearby_gte_max */
    public static final int RSSI_BANDS_DBM_NEARBY_GTE_MAX = -65;

    /** identity_discovery.RSSI_BANDS_DBM.nearby_gte_min */
    public static final int RSSI_BANDS_DBM_NEARBY_GTE_MIN = -85;

    /** identity_discovery.RSSI_BANDS_DBM.overridable */
    public static final boolean RSSI_BANDS_DBM_OVERRIDABLE = true;

    /** identity_discovery.RSSI_BANDS_DBM.very_close_gte */
    public static final int RSSI_BANDS_DBM_VERY_CLOSE_GTE = -55;

    /** identity_discovery.RSSI_BANDS_DBM.very_close_gte_max */
    public static final int RSSI_BANDS_DBM_VERY_CLOSE_GTE_MAX = -45;

    /** identity_discovery.RSSI_BANDS_DBM.very_close_gte_min */
    public static final int RSSI_BANDS_DBM_VERY_CLOSE_GTE_MIN = -60;

    /** identity_discovery.SCAN_WINDOW_FG_MS.calibrated */
    public static final boolean SCAN_WINDOW_FG_MS_CALIBRATED = true;

    /** identity_discovery.SCAN_WINDOW_FG_MS.duty_max_pct */
    public static final int SCAN_WINDOW_FG_MS_DUTY_MAX_PCT = 60;

    /** identity_discovery.SCAN_WINDOW_FG_MS.duty_min_pct */
    public static final int SCAN_WINDOW_FG_MS_DUTY_MIN_PCT = 20;

    /** identity_discovery.SCAN_WINDOW_FG_MS.off */
    public static final int SCAN_WINDOW_FG_MS_OFF = 6000;

    /** identity_discovery.SCAN_WINDOW_FG_MS.on */
    public static final int SCAN_WINDOW_FG_MS_ON = 4000;

    /** identity_discovery.SCAN_WINDOW_FG_MS.overridable */
    public static final boolean SCAN_WINDOW_FG_MS_OVERRIDABLE = true;

    /** identity_discovery.SCAN_WINDOW_VENUE_MS.calibrated */
    public static final boolean SCAN_WINDOW_VENUE_MS_CALIBRATED = true;

    /** identity_discovery.SCAN_WINDOW_VENUE_MS.duty_max_pct */
    public static final int SCAN_WINDOW_VENUE_MS_DUTY_MAX_PCT = 70;

    /** identity_discovery.SCAN_WINDOW_VENUE_MS.off */
    public static final int SCAN_WINDOW_VENUE_MS_OFF = 4000;

    /** identity_discovery.SCAN_WINDOW_VENUE_MS.on */
    public static final int SCAN_WINDOW_VENUE_MS_ON = 6000;

    /** identity_discovery.SCAN_WINDOW_VENUE_MS.overridable */
    public static final boolean SCAN_WINDOW_VENUE_MS_OVERRIDABLE = true;

    // Sessions remote text

    /** sessions_remote_text.CLOCK_SKEW_TOLERANCE_SEC.default */
    public static final int CLOCK_SKEW_TOLERANCE_SEC_DEFAULT = 300;

    /** sessions_remote_text.CLOCK_SKEW_TOLERANCE_SEC.overridable */
    public static final boolean CLOCK_SKEW_TOLERANCE_SEC_OVERRIDABLE = false;

    /** sessions_remote_text.DEDUP_CACHE.days */
    public static final int DEDUP_CACHE_DAYS = 7;

    /** sessions_remote_text.DEDUP_CACHE.days_max */
    public static final int DEDUP_CACHE_DAYS_MAX = 30;

    /** sessions_remote_text.DEDUP_CACHE.days_min */
    public static final int DEDUP_CACHE_DAYS_MIN = 3;

    /** sessions_remote_text.DEDUP_CACHE.ids */
    public static final int DEDUP_CACHE_IDS = 10000;

    /** sessions_remote_text.DEDUP_CACHE.ids_max */
    public static final int DEDUP_CACHE_IDS_MAX = 50000;

    /** sessions_remote_text.DEDUP_CACHE.ids_min */
    public static final int DEDUP_CACHE_IDS_MIN = 5000;

    /** sessions_remote_text.DEDUP_CACHE.overridable */
    public static final boolean DEDUP_CACHE_OVERRIDABLE = true;

    /** sessions_remote_text.MAX_SKIP_PER_CHAIN.default */
    public static final int MAX_SKIP_PER_CHAIN_DEFAULT = 512;

    /** sessions_remote_text.MAX_SKIP_PER_CHAIN.overridable */
    public static final boolean MAX_SKIP_PER_CHAIN_OVERRIDABLE = false;

    /** sessions_remote_text.MESSAGE_ID_LEN_BYTES.default */
    public static final int MESSAGE_ID_LEN_BYTES_DEFAULT = 16;

    /** sessions_remote_text.MESSAGE_ID_LEN_BYTES.overridable */
    public static final boolean MESSAGE_ID_LEN_BYTES_OVERRIDABLE = false;

    /** sessions_remote_text.ONE_TIME_PREKEYS_UPLOADED.default */
    public static final int ONE_TIME_PREKEYS_UPLOADED_DEFAULT = 100;

    /** sessions_remote_text.ONE_TIME_PREKEYS_UPLOADED.max */
    public static final int ONE_TIME_PREKEYS_UPLOADED_MAX = 200;

    /** sessions_remote_text.ONE_TIME_PREKEYS_UPLOADED.min */
    public static final int ONE_TIME_PREKEYS_UPLOADED_MIN = 50;

    /** sessions_remote_text.ONE_TIME_PREKEYS_UPLOADED.overridable */
    public static final boolean ONE_TIME_PREKEYS_UPLOADED_OVERRIDABLE = true;

    /** sessions_remote_text.PREKEY_REPLENISH_THRESHOLD.default */
    public static final int PREKEY_REPLENISH_THRESHOLD_DEFAULT = 20;

    /** sessions_remote_text.PREKEY_REPLENISH_THRESHOLD.max */
    public static final int PREKEY_REPLENISH_THRESHOLD_MAX = 50;

    /** sessions_remote_text.PREKEY_REPLENISH_THRESHOLD.min */
    public static final int PREKEY_REPLENISH_THRESHOLD_MIN = 10;

    /** sessions_remote_text.PREKEY_REPLENISH_THRESHOLD.overridable */
    public static final boolean PREKEY_REPLENISH_THRESHOLD_OVERRIDABLE = true;

    /** sessions_remote_text.RELAY_ENVELOPE_TTL_DAYS.default */
    public static final int RELAY_ENVELOPE_TTL_DAYS_DEFAULT = 30;

    /** sessions_remote_text.RELAY_ENVELOPE_TTL_DAYS.max */
    public static final int RELAY_ENVELOPE_TTL_DAYS_MAX = 45;

    /** sessions_remote_text.RELAY_ENVELOPE_TTL_DAYS.min */
    public static final int RELAY_ENVELOPE_TTL_DAYS_MIN = 1;

    /** sessions_remote_text.RELAY_ENVELOPE_TTL_DAYS.overridable */
    public static final boolean RELAY_ENVELOPE_TTL_DAYS_OVERRIDABLE = true;

    /** sessions_remote_text.RELAY_MAILBOX_QUOTA.bytes */
    public static final int RELAY_MAILBOX_QUOTA_BYTES = 8388608;

    /** sessions_remote_text.RELAY_MAILBOX_QUOTA.bytes_max */
    public static final int RELAY_MAILBOX_QUOTA_BYTES_MAX = 33554432;

    /** sessions_remote_text.RELAY_MAILBOX_QUOTA.bytes_min */
    public static final int RELAY_MAILBOX_QUOTA_BYTES_MIN = 2097152;

    /** sessions_remote_text.RELAY_MAILBOX_QUOTA.envelopes */
    public static final int RELAY_MAILBOX_QUOTA_ENVELOPES = 1000;

    /** sessions_remote_text.RELAY_MAILBOX_QUOTA.envelopes_max */
    public static final int RELAY_MAILBOX_QUOTA_ENVELOPES_MAX = 5000;

    /** sessions_remote_text.RELAY_MAILBOX_QUOTA.envelopes_min */
    public static final int RELAY_MAILBOX_QUOTA_ENVELOPES_MIN = 200;

    /** sessions_remote_text.RELAY_MAILBOX_QUOTA.overridable */
    public static final boolean RELAY_MAILBOX_QUOTA_OVERRIDABLE = true;

    /** sessions_remote_text.RELAY_TEXT_ENVELOPE_MAX_BYTES.default */
    public static final int RELAY_TEXT_ENVELOPE_MAX_BYTES_DEFAULT = 32768;

    /** sessions_remote_text.RELAY_TEXT_ENVELOPE_MAX_BYTES.max */
    public static final int RELAY_TEXT_ENVELOPE_MAX_BYTES_MAX = 65536;

    /** sessions_remote_text.RELAY_TEXT_ENVELOPE_MAX_BYTES.min */
    public static final int RELAY_TEXT_ENVELOPE_MAX_BYTES_MIN = 8192;

    /** sessions_remote_text.RELAY_TEXT_ENVELOPE_MAX_BYTES.overridable */
    public static final boolean RELAY_TEXT_ENVELOPE_MAX_BYTES_OVERRIDABLE = true;

    /** sessions_remote_text.SAFETY_NUMBER_FORMAT.digits */
    public static final int SAFETY_NUMBER_FORMAT_DIGITS = 60;

    /** sessions_remote_text.SAFETY_NUMBER_FORMAT.emoji_alphabet_size */
    public static final int SAFETY_NUMBER_FORMAT_EMOJI_ALPHABET_SIZE = 64;

    /** sessions_remote_text.SAFETY_NUMBER_FORMAT.emoji_count */
    public static final int SAFETY_NUMBER_FORMAT_EMOJI_COUNT = 6;

    /** sessions_remote_text.SAFETY_NUMBER_FORMAT.overridable */
    public static final boolean SAFETY_NUMBER_FORMAT_OVERRIDABLE = false;

    /** sessions_remote_text.SESSION_ID_LEN_BYTES.default */
    public static final int SESSION_ID_LEN_BYTES_DEFAULT = 16;

    /** sessions_remote_text.SESSION_ID_LEN_BYTES.overridable */
    public static final boolean SESSION_ID_LEN_BYTES_OVERRIDABLE = false;

    /** sessions_remote_text.SIGNED_PREKEY_ROTATION_DAYS.default */
    public static final int SIGNED_PREKEY_ROTATION_DAYS_DEFAULT = 7;

    /** sessions_remote_text.SIGNED_PREKEY_ROTATION_DAYS.max */
    public static final int SIGNED_PREKEY_ROTATION_DAYS_MAX = 30;

    /** sessions_remote_text.SIGNED_PREKEY_ROTATION_DAYS.min */
    public static final int SIGNED_PREKEY_ROTATION_DAYS_MIN = 3;

    /** sessions_remote_text.SIGNED_PREKEY_ROTATION_DAYS.overridable */
    public static final boolean SIGNED_PREKEY_ROTATION_DAYS_OVERRIDABLE = true;

    /** sessions_remote_text.SKIPPED_KEYS_PER_SESSION_CAP.default */
    public static final int SKIPPED_KEYS_PER_SESSION_CAP_DEFAULT = 2000;

    /** sessions_remote_text.SKIPPED_KEYS_PER_SESSION_CAP.overridable */
    public static final boolean SKIPPED_KEYS_PER_SESSION_CAP_OVERRIDABLE = false;

    /** sessions_remote_text.SKIPPED_KEY_TTL_DAYS.default */
    public static final int SKIPPED_KEY_TTL_DAYS_DEFAULT = 30;

    /** sessions_remote_text.SKIPPED_KEY_TTL_DAYS.max */
    public static final int SKIPPED_KEY_TTL_DAYS_MAX = 45;

    /** sessions_remote_text.SKIPPED_KEY_TTL_DAYS.min */
    public static final int SKIPPED_KEY_TTL_DAYS_MIN = 7;

    /** sessions_remote_text.SKIPPED_KEY_TTL_DAYS.overridable */
    public static final boolean SKIPPED_KEY_TTL_DAYS_OVERRIDABLE = true;

    // Mesh store forward

    /** mesh_store_forward.MESH_FRAME_TTL_SEC.default */
    public static final int MESH_FRAME_TTL_SEC_DEFAULT = 300;

    /** mesh_store_forward.MESH_FRAME_TTL_SEC.max */
    public static final int MESH_FRAME_TTL_SEC_MAX = 900;

    /** mesh_store_forward.MESH_FRAME_TTL_SEC.min */
    public static final int MESH_FRAME_TTL_SEC_MIN = 60;

    /** mesh_store_forward.MESH_FRAME_TTL_SEC.overridable */
    public static final boolean MESH_FRAME_TTL_SEC_OVERRIDABLE = true;

    /** mesh_store_forward.MESH_HOP_ABSOLUTE_CEILING.default */
    public static final int MESH_HOP_ABSOLUTE_CEILING_DEFAULT = 5;

    /** mesh_store_forward.MESH_HOP_ABSOLUTE_CEILING.overridable */
    public static final boolean MESH_HOP_ABSOLUTE_CEILING_OVERRIDABLE = false;

    /** mesh_store_forward.MESH_HOP_DEFAULT.default */
    public static final int MESH_HOP_DEFAULT_DEFAULT = 3;

    /** mesh_store_forward.MESH_HOP_DEFAULT.max */
    public static final int MESH_HOP_DEFAULT_MAX = 5;

    /** mesh_store_forward.MESH_HOP_DEFAULT.min */
    public static final int MESH_HOP_DEFAULT_MIN = 1;

    /** mesh_store_forward.MESH_HOP_DEFAULT.overridable */
    public static final boolean MESH_HOP_DEFAULT_OVERRIDABLE = true;

    /** mesh_store_forward.STORE_FORWARD_QUOTA.bytes */
    public static final int STORE_FORWARD_QUOTA_BYTES = 2097152;

    /** mesh_store_forward.STORE_FORWARD_QUOTA.bytes_max */
    public static final int STORE_FORWARD_QUOTA_BYTES_MAX = 8388608;

    /** mesh_store_forward.STORE_FORWARD_QUOTA.bytes_min */
    public static final int STORE_FORWARD_QUOTA_BYTES_MIN = 524288;

    /** mesh_store_forward.STORE_FORWARD_QUOTA.envelopes */
    public static final int STORE_FORWARD_QUOTA_ENVELOPES = 50;

    /** mesh_store_forward.STORE_FORWARD_QUOTA.envelopes_max */
    public static final int STORE_FORWARD_QUOTA_ENVELOPES_MAX = 200;

    /** mesh_store_forward.STORE_FORWARD_QUOTA.envelopes_min */
    public static final int STORE_FORWARD_QUOTA_ENVELOPES_MIN = 10;

    /** mesh_store_forward.STORE_FORWARD_QUOTA.overridable */
    public static final boolean STORE_FORWARD_QUOTA_OVERRIDABLE = true;

    /** mesh_store_forward.STORE_FORWARD_TTL_SEC.default */
    public static final int STORE_FORWARD_TTL_SEC_DEFAULT = 1800;

    /** mesh_store_forward.STORE_FORWARD_TTL_SEC.max */
    public static final int STORE_FORWARD_TTL_SEC_MAX = 7200;

    /** mesh_store_forward.STORE_FORWARD_TTL_SEC.min */
    public static final int STORE_FORWARD_TTL_SEC_MIN = 300;

    /** mesh_store_forward.STORE_FORWARD_TTL_SEC.overridable */
    public static final boolean STORE_FORWARD_TTL_SEC_OVERRIDABLE = true;

    // Transport

    /** transport.BLE_CHUNK_PAYLOAD.floor */
    public static final int BLE_CHUNK_PAYLOAD_FLOOR = 20;

    /** transport.BLE_CHUNK_PAYLOAD.overridable */
    public static final boolean BLE_CHUNK_PAYLOAD_OVERRIDABLE = false;

    /** transport.BLE_CHUNK_PAYLOAD.rule */
    public static final String BLE_CHUNK_PAYLOAD_RULE = "negotiated_mtu_minus_7_floor_20";

    /** transport.BLE_CHUNK_PAYLOAD.subtract */
    public static final int BLE_CHUNK_PAYLOAD_SUBTRACT = 7;

    /** transport.BLE_TARGET_ATT_MTU.default */
    public static final int BLE_TARGET_ATT_MTU_DEFAULT = 251;

    /** transport.BLE_TARGET_ATT_MTU.max */
    public static final int BLE_TARGET_ATT_MTU_MAX = 517;

    /** transport.BLE_TARGET_ATT_MTU.min */
    public static final int BLE_TARGET_ATT_MTU_MIN = 23;

    /** transport.BLE_TARGET_ATT_MTU.overridable */
    public static final boolean BLE_TARGET_ATT_MTU_OVERRIDABLE = false;

    /** transport.CIRCUIT_BREAKER.half_open_after_sec */
    public static final int CIRCUIT_BREAKER_HALF_OPEN_AFTER_SEC = 30;

    /** transport.CIRCUIT_BREAKER.open_after_fails */
    public static final int CIRCUIT_BREAKER_OPEN_AFTER_FAILS = 3;

    /** transport.CIRCUIT_BREAKER.overridable */
    public static final boolean CIRCUIT_BREAKER_OVERRIDABLE = false;

    /** transport.LAN_CHUNK_PAYLOAD_BYTES.default */
    public static final int LAN_CHUNK_PAYLOAD_BYTES_DEFAULT = 65536;

    /** transport.LAN_CHUNK_PAYLOAD_BYTES.overridable */
    public static final boolean LAN_CHUNK_PAYLOAD_BYTES_OVERRIDABLE = false;

    /** transport.QR_BOOTSTRAP_MAX_BYTES.default */
    public static final int QR_BOOTSTRAP_MAX_BYTES_DEFAULT = 1228;

    /** transport.QR_BOOTSTRAP_MAX_BYTES.overridable */
    public static final boolean QR_BOOTSTRAP_MAX_BYTES_OVERRIDABLE = false;

    /** transport.REASSEMBLY_TIMEOUT_SEC.ble */
    public static final int REASSEMBLY_TIMEOUT_SEC_BLE = 120;

    /** transport.REASSEMBLY_TIMEOUT_SEC.ble_max */
    public static final int REASSEMBLY_TIMEOUT_SEC_BLE_MAX = 300;

    /** transport.REASSEMBLY_TIMEOUT_SEC.ble_min */
    public static final int REASSEMBLY_TIMEOUT_SEC_BLE_MIN = 60;

    /** transport.REASSEMBLY_TIMEOUT_SEC.lan */
    public static final int REASSEMBLY_TIMEOUT_SEC_LAN = 30;

    /** transport.REASSEMBLY_TIMEOUT_SEC.lan_max */
    public static final int REASSEMBLY_TIMEOUT_SEC_LAN_MAX = 120;

    /** transport.REASSEMBLY_TIMEOUT_SEC.lan_min */
    public static final int REASSEMBLY_TIMEOUT_SEC_LAN_MIN = 15;

    /** transport.REASSEMBLY_TIMEOUT_SEC.overridable */
    public static final boolean REASSEMBLY_TIMEOUT_SEC_OVERRIDABLE = true;

    /** transport.RETRY_BACKOFF.base_ms */
    public static final int RETRY_BACKOFF_BASE_MS = 1000;

    /** transport.RETRY_BACKOFF.cap_ms */
    public static final int RETRY_BACKOFF_CAP_MS = 60000;

    /** transport.RETRY_BACKOFF.factor */
    public static final int RETRY_BACKOFF_FACTOR = 2;

    /** transport.RETRY_BACKOFF.jitter */
    public static final boolean RETRY_BACKOFF_JITTER = true;

    /** transport.RETRY_BACKOFF.overridable */
    public static final boolean RETRY_BACKOFF_OVERRIDABLE = false;

    /** transport.RETRY_BUDGET_PER_PATH.default */
    public static final int RETRY_BUDGET_PER_PATH_DEFAULT = 5;

    /** transport.RETRY_BUDGET_PER_PATH.max */
    public static final int RETRY_BUDGET_PER_PATH_MAX = 8;

    /** transport.RETRY_BUDGET_PER_PATH.min */
    public static final int RETRY_BUDGET_PER_PATH_MIN = 3;

    /** transport.RETRY_BUDGET_PER_PATH.overridable */
    public static final boolean RETRY_BUDGET_PER_PATH_OVERRIDABLE = true;

    // Media

    /** media.FILE_MAX_MB.default */
    public static final int FILE_MAX_MB_DEFAULT = 25;

    /** media.FILE_MAX_MB.max */
    public static final int FILE_MAX_MB_MAX = 100;

    /** media.FILE_MAX_MB.min */
    public static final int FILE_MAX_MB_MIN = 5;

    /** media.FILE_MAX_MB.overridable */
    public static final boolean FILE_MAX_MB_OVERRIDABLE = true;

    /** media.FULL_IMAGE_MAX_MB.default */
    public static final int FULL_IMAGE_MAX_MB_DEFAULT = 2;

    /** media.FULL_IMAGE_MAX_MB.max */
    public static final int FULL_IMAGE_MAX_MB_MAX = 10;

    /** media.FULL_IMAGE_MAX_MB.min */
    public static final int FULL_IMAGE_MAX_MB_MIN = 1;

    /** media.FULL_IMAGE_MAX_MB.overridable */
    public static final boolean FULL_IMAGE_MAX_MB_OVERRIDABLE = true;

    /** media.MEDIA_CACHE_QUOTA_MB.default */
    public static final int MEDIA_CACHE_QUOTA_MB_DEFAULT = 512;

    /** media.MEDIA_CACHE_QUOTA_MB.max */
    public static final int MEDIA_CACHE_QUOTA_MB_MAX = 2048;

    /** media.MEDIA_CACHE_QUOTA_MB.min */
    public static final int MEDIA_CACHE_QUOTA_MB_MIN = 128;

    /** media.MEDIA_CACHE_QUOTA_MB.overridable */
    public static final boolean MEDIA_CACHE_QUOTA_MB_OVERRIDABLE = true;

    /** media.MEDIA_CACHE_QUOTA_MB.ttl_days */
    public static final int MEDIA_CACHE_QUOTA_MB_TTL_DAYS = 30;

    /** media.MEDIA_CACHE_QUOTA_MB.ttl_days_max */
    public static final int MEDIA_CACHE_QUOTA_MB_TTL_DAYS_MAX = 90;

    /** media.MEDIA_CACHE_QUOTA_MB.ttl_days_min */
    public static final int MEDIA_CACHE_QUOTA_MB_TTL_DAYS_MIN = 7;

    /** media.PREVIEW_MAX_KB.default */
    public static final int PREVIEW_MAX_KB_DEFAULT = 64;

    /** media.PREVIEW_MAX_KB.max */
    public static final int PREVIEW_MAX_KB_MAX = 128;

    /** media.PREVIEW_MAX_KB.min */
    public static final int PREVIEW_MAX_KB_MIN = 32;

    /** media.PREVIEW_MAX_KB.overridable */
    public static final boolean PREVIEW_MAX_KB_OVERRIDABLE = true;

    /** media.THUMBNAIL_MAX_KB.default */
    public static final int THUMBNAIL_MAX_KB_DEFAULT = 12;

    /** media.THUMBNAIL_MAX_KB.max */
    public static final int THUMBNAIL_MAX_KB_MAX = 16;

    /** media.THUMBNAIL_MAX_KB.min */
    public static final int THUMBNAIL_MAX_KB_MIN = 8;

    /** media.THUMBNAIL_MAX_KB.overridable */
    public static final boolean THUMBNAIL_MAX_KB_OVERRIDABLE = true;

    /** media.VOICE_NOTE.bitrate_kbps */
    public static final List<Integer> VOICE_NOTE_BITRATE_KBPS = List.of(24, 32);

    /** media.VOICE_NOTE.codec */
    public static final String VOICE_NOTE_CODEC = "AAC-LC mono M4A";

    /** media.VOICE_NOTE.max_kb */
    public static final int VOICE_NOTE_MAX_KB = 600;

    /** media.VOICE_NOTE.max_sec */
    public static final int VOICE_NOTE_MAX_SEC = 120;

    /** media.VOICE_NOTE.overridable */
    public static final boolean VOICE_NOTE_OVERRIDABLE = true;

    /** media.VOICE_NOTE.sample_rate_hz */
    public static final int VOICE_NOTE_SAMPLE_RATE_HZ = 24000;

    /** media.VOICE_NOTE.sec_max */
    public static final int VOICE_NOTE_SEC_MAX = 180;

    /** media.VOICE_NOTE.sec_min */
    public static final int VOICE_NOTE_SEC_MIN = 30;

    // Config venue ops

    /** config_venue_ops.CONFIG_PACKAGE_MAX_KB.default */
    public static final int CONFIG_PACKAGE_MAX_KB_DEFAULT = 512;

    /** config_venue_ops.CONFIG_PACKAGE_MAX_KB.overridable */
    public static final boolean CONFIG_PACKAGE_MAX_KB_OVERRIDABLE = false;

    /** config_venue_ops.CONFIG_REFRESH_INTERVAL_HOURS.default */
    public static final int CONFIG_REFRESH_INTERVAL_HOURS_DEFAULT = 6;

    /** config_venue_ops.CONFIG_REFRESH_INTERVAL_HOURS.jitter */
    public static final boolean CONFIG_REFRESH_INTERVAL_HOURS_JITTER = true;

    /** config_venue_ops.CONFIG_REFRESH_INTERVAL_HOURS.max */
    public static final int CONFIG_REFRESH_INTERVAL_HOURS_MAX = 24;

    /** config_venue_ops.CONFIG_REFRESH_INTERVAL_HOURS.min */
    public static final int CONFIG_REFRESH_INTERVAL_HOURS_MIN = 1;

    /** config_venue_ops.CONFIG_REFRESH_INTERVAL_HOURS.overridable */
    public static final boolean CONFIG_REFRESH_INTERVAL_HOURS_OVERRIDABLE = true;

    /** config_venue_ops.SPONSOR_ASSET_MAX_KB.cache_count */
    public static final int SPONSOR_ASSET_MAX_KB_CACHE_COUNT = 8;

    /** config_venue_ops.SPONSOR_ASSET_MAX_KB.cache_count_max */
    public static final int SPONSOR_ASSET_MAX_KB_CACHE_COUNT_MAX = 16;

    /** config_venue_ops.SPONSOR_ASSET_MAX_KB.cache_count_min */
    public static final int SPONSOR_ASSET_MAX_KB_CACHE_COUNT_MIN = 4;

    /** config_venue_ops.SPONSOR_ASSET_MAX_KB.default */
    public static final int SPONSOR_ASSET_MAX_KB_DEFAULT = 300;

    /** config_venue_ops.SPONSOR_ASSET_MAX_KB.max */
    public static final int SPONSOR_ASSET_MAX_KB_MAX = 512;

    /** config_venue_ops.SPONSOR_ASSET_MAX_KB.min */
    public static final int SPONSOR_ASSET_MAX_KB_MIN = 100;

    /** config_venue_ops.SPONSOR_ASSET_MAX_KB.overridable */
    public static final boolean SPONSOR_ASSET_MAX_KB_OVERRIDABLE = true;

    /** config_venue_ops.SUPPORT_RING_BUFFER_MB.default */
    public static final int SUPPORT_RING_BUFFER_MB_DEFAULT = 4;

    /** config_venue_ops.SUPPORT_RING_BUFFER_MB.max */
    public static final int SUPPORT_RING_BUFFER_MB_MAX = 16;

    /** config_venue_ops.SUPPORT_RING_BUFFER_MB.min */
    public static final int SUPPORT_RING_BUFFER_MB_MIN = 1;

    /** config_venue_ops.SUPPORT_RING_BUFFER_MB.overridable */
    public static final boolean SUPPORT_RING_BUFFER_MB_OVERRIDABLE = true;

    /** config_venue_ops.VENUE_ROOM_POST_RATE_PER_MIN.default */
    public static final int VENUE_ROOM_POST_RATE_PER_MIN_DEFAULT = 10;

    /** config_venue_ops.VENUE_ROOM_POST_RATE_PER_MIN.max */
    public static final int VENUE_ROOM_POST_RATE_PER_MIN_MAX = 30;

    /** config_venue_ops.VENUE_ROOM_POST_RATE_PER_MIN.min */
    public static final int VENUE_ROOM_POST_RATE_PER_MIN_MIN = 3;

    /** config_venue_ops.VENUE_ROOM_POST_RATE_PER_MIN.overridable */
    public static final boolean VENUE_ROOM_POST_RATE_PER_MIN_OVERRIDABLE = true;

    /** config_venue_ops.VOUCHER_NONCE_CACHE.count */
    public static final int VOUCHER_NONCE_CACHE_COUNT = 5000;

    /** config_venue_ops.VOUCHER_NONCE_CACHE.count_max */
    public static final int VOUCHER_NONCE_CACHE_COUNT_MAX = 20000;

    /** config_venue_ops.VOUCHER_NONCE_CACHE.count_min */
    public static final int VOUCHER_NONCE_CACHE_COUNT_MIN = 1000;

    /** config_venue_ops.VOUCHER_NONCE_CACHE.days */
    public static final int VOUCHER_NONCE_CACHE_DAYS = 90;

    /** config_venue_ops.VOUCHER_NONCE_CACHE.days_max */
    public static final int VOUCHER_NONCE_CACHE_DAYS_MAX = 365;

    /** config_venue_ops.VOUCHER_NONCE_CACHE.days_min */
    public static final int VOUCHER_NONCE_CACHE_DAYS_MIN = 30;

    /** config_venue_ops.VOUCHER_NONCE_CACHE.overridable */
    public static final boolean VOUCHER_NONCE_CACHE_OVERRIDABLE = true;

    // Resource budgets

    /** resource_budgets.ACTIVE_VENUE_BATTERY_MAX_PCT_30MIN.budget */
    public static final boolean ACTIVE_VENUE_BATTERY_MAX_PCT_30MIN_BUDGET = true;

    /** resource_budgets.ACTIVE_VENUE_BATTERY_MAX_PCT_30MIN.calibrated */
    public static final boolean ACTIVE_VENUE_BATTERY_MAX_PCT_30MIN_CALIBRATED = true;

    /** resource_budgets.ACTIVE_VENUE_BATTERY_MAX_PCT_30MIN.default */
    public static final int ACTIVE_VENUE_BATTERY_MAX_PCT_30MIN_DEFAULT = 6;

    /** resource_budgets.ACTIVE_VENUE_BATTERY_MAX_PCT_30MIN.overridable */
    public static final boolean ACTIVE_VENUE_BATTERY_MAX_PCT_30MIN_OVERRIDABLE = false;

    /** resource_budgets.ANDROID_DOWNLOAD_MAX_MB.budget */
    public static final boolean ANDROID_DOWNLOAD_MAX_MB_BUDGET = true;

    /** resource_budgets.ANDROID_DOWNLOAD_MAX_MB.default */
    public static final int ANDROID_DOWNLOAD_MAX_MB_DEFAULT = 40;

    /** resource_budgets.ANDROID_DOWNLOAD_MAX_MB.overridable */
    public static final boolean ANDROID_DOWNLOAD_MAX_MB_OVERRIDABLE = false;

    /** resource_budgets.COLD_LAUNCH_P95_MS.default */
    public static final int COLD_LAUNCH_P95_MS_DEFAULT = 2000;

    /** resource_budgets.COLD_LAUNCH_P95_MS.overridable */
    public static final boolean COLD_LAUNCH_P95_MS_OVERRIDABLE = false;

    /** resource_budgets.IDLE_BACKGROUND.battery_pct_max */
    public static final int IDLE_BACKGROUND_BATTERY_PCT_MAX = 0;

    /** resource_budgets.IDLE_BACKGROUND.overridable */
    public static final boolean IDLE_BACKGROUND_OVERRIDABLE = false;

    /** resource_budgets.IDLE_BACKGROUND.wake_locks_permitted */
    public static final boolean IDLE_BACKGROUND_WAKE_LOCKS_PERMITTED = false;

    /** resource_budgets.IOS_DOWNLOAD_MAX_MB.budget */
    public static final boolean IOS_DOWNLOAD_MAX_MB_BUDGET = true;

    /** resource_budgets.IOS_DOWNLOAD_MAX_MB.default */
    public static final int IOS_DOWNLOAD_MAX_MB_DEFAULT = 60;

    /** resource_budgets.IOS_DOWNLOAD_MAX_MB.overridable */
    public static final boolean IOS_DOWNLOAD_MAX_MB_OVERRIDABLE = false;

    /** resource_budgets.STEADY_STATE_RAM_MAX_MB.budget */
    public static final boolean STEADY_STATE_RAM_MAX_MB_BUDGET = true;

    /** resource_budgets.STEADY_STATE_RAM_MAX_MB.default */
    public static final int STEADY_STATE_RAM_MAX_MB_DEFAULT = 220;

    /** resource_budgets.STEADY_STATE_RAM_MAX_MB.overridable */
    public static final boolean STEADY_STATE_RAM_MAX_MB_OVERRIDABLE = false;
}
