/// Lumos - protocol/gen/swift / Generated Lumos protocol and policy constants.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-CONST-001, TRS-CONST-002, TRS-CONST-005, TRS-REP-002
/// ADRs: ADR-002, ADR-004
/// Threading: any
/// GENERATED - DO NOT EDIT. Generator: tools/constantsgen
/// Source register: protocol/constants/lumos_constants.json
/// Baseline: TRS v1.1 Sec. 23 / EDP v1.0 Sec. 4 (ADR-004)
/// constants_hash: sha256:83f32e68c912a8df9a6392194ac6691a45c6e8ae62fc6d60640424df45cbdc91

public enum LumosConstants {

    /// The one fixed 128-bit Lumos GATT service UUID (ADR-002).
    public static let serviceUUID = "E853EB6A-775C-4A49-A6BF-320E6A5CC609"

    /// Binds generated code to the register that produced it (TRS-CONST-005).
    public static let constantsHash = "sha256:83f32e68c912a8df9a6392194ac6691a45c6e8ae62fc6d60640424df45cbdc91"

    /// Register schema version.
    public static let schemaVersion = 1

    /// Specification sections this register was derived from.
    public static let baseline = "TRS v1.1 Sec. 23 / EDP v1.0 Sec. 4 (ADR-004)"

    // MARK: - Identity discovery

    /// identity_discovery.ADV_AUTH_TAG_LEN.default
    public static let advAuthTagLenDefault = 4

    /// identity_discovery.ADV_AUTH_TAG_LEN.overridable
    public static let advAuthTagLenOverridable = false

    /// identity_discovery.EID_ROTATION_SEC.default
    public static let eidRotationSecDefault = 600

    /// identity_discovery.EID_ROTATION_SEC.max
    public static let eidRotationSecMax = 900

    /// identity_discovery.EID_ROTATION_SEC.min
    public static let eidRotationSecMin = 300

    /// identity_discovery.EID_ROTATION_SEC.overridable
    public static let eidRotationSecOverridable = true

    /// identity_discovery.INTEREST_RATE_LIMIT_PER_HOUR.default
    public static let interestRateLimitPerHourDefault = 20

    /// identity_discovery.INTEREST_RATE_LIMIT_PER_HOUR.max
    public static let interestRateLimitPerHourMax = 60

    /// identity_discovery.INTEREST_RATE_LIMIT_PER_HOUR.min
    public static let interestRateLimitPerHourMin = 5

    /// identity_discovery.INTEREST_RATE_LIMIT_PER_HOUR.overridable
    public static let interestRateLimitPerHourOverridable = true

    /// identity_discovery.MAX_CONCURRENT_GATT_LINKS.default
    public static let maxConcurrentGATTLinksDefault = 4

    /// identity_discovery.MAX_CONCURRENT_GATT_LINKS.max
    public static let maxConcurrentGATTLinksMax = 7

    /// identity_discovery.MAX_CONCURRENT_GATT_LINKS.min
    public static let maxConcurrentGATTLinksMin = 2

    /// identity_discovery.MAX_CONCURRENT_GATT_LINKS.overridable
    public static let maxConcurrentGATTLinksOverridable = true

    /// identity_discovery.NEARBY_SYNTHETIC_LOAD_TARGET.default
    public static let nearbySyntheticLoadTargetDefault = 100

    /// identity_discovery.NEARBY_SYNTHETIC_LOAD_TARGET.overridable
    public static let nearbySyntheticLoadTargetOverridable = false

    /// identity_discovery.NEARBY_SYNTHETIC_LOAD_TARGET.test_only
    public static let nearbySyntheticLoadTargetTestOnly = true

    /// identity_discovery.PRESENCE_AGEOUT_SEC.default
    public static let presenceAgeoutSecDefault = 90

    /// identity_discovery.PRESENCE_AGEOUT_SEC.max
    public static let presenceAgeoutSecMax = 300

    /// identity_discovery.PRESENCE_AGEOUT_SEC.min
    public static let presenceAgeoutSecMin = 30

    /// identity_discovery.PRESENCE_AGEOUT_SEC.overridable
    public static let presenceAgeoutSecOverridable = true

    /// identity_discovery.RESCAN_LADDER.bounds_unspecified
    public static let rescanLadderBoundsUnspecified = true

    /// identity_discovery.RESCAN_LADDER.calibrated
    public static let rescanLadderCalibrated = true

    /// identity_discovery.RESCAN_LADDER.max_steps
    public static let rescanLadderMaxSteps = 3

    /// identity_discovery.RESCAN_LADDER.overridable
    public static let rescanLadderOverridable = true

    /// identity_discovery.RESCAN_LADDER.step_pct
    public static let rescanLadderStepPCT = 50

    /// identity_discovery.RESCAN_LADDER.trigger_empty_cycles
    public static let rescanLadderTriggerEmptyCycles = 2

    /// identity_discovery.RSSI_BANDS_DBM.bounds_unspecified
    public static let rssiBandsDBMBoundsUnspecified = true

    /// identity_discovery.RSSI_BANDS_DBM.calibrated
    public static let rssiBandsDBMCalibrated = true

    /// identity_discovery.RSSI_BANDS_DBM.nearby_gte
    public static let rssiBandsDBMNearbyGte = -75

    /// identity_discovery.RSSI_BANDS_DBM.overridable
    public static let rssiBandsDBMOverridable = true

    /// identity_discovery.RSSI_BANDS_DBM.very_close_gte
    public static let rssiBandsDBMVeryCloseGte = -55

    /// identity_discovery.SCAN_WINDOW_FG_MS.calibrated
    public static let scanWindowFgMSCalibrated = true

    /// identity_discovery.SCAN_WINDOW_FG_MS.duty_max_pct
    public static let scanWindowFgMSDutyMaxPCT = 60

    /// identity_discovery.SCAN_WINDOW_FG_MS.duty_min_pct
    public static let scanWindowFgMSDutyMinPCT = 20

    /// identity_discovery.SCAN_WINDOW_FG_MS.off
    public static let scanWindowFgMSOff = 6000

    /// identity_discovery.SCAN_WINDOW_FG_MS.on
    public static let scanWindowFgMSOn = 4000

    /// identity_discovery.SCAN_WINDOW_FG_MS.overridable
    public static let scanWindowFgMSOverridable = true

    /// identity_discovery.SCAN_WINDOW_VENUE_MS.calibrated
    public static let scanWindowVenueMSCalibrated = true

    /// identity_discovery.SCAN_WINDOW_VENUE_MS.duty_max_pct
    public static let scanWindowVenueMSDutyMaxPCT = 70

    /// identity_discovery.SCAN_WINDOW_VENUE_MS.off
    public static let scanWindowVenueMSOff = 4000

    /// identity_discovery.SCAN_WINDOW_VENUE_MS.on
    public static let scanWindowVenueMSOn = 6000

    /// identity_discovery.SCAN_WINDOW_VENUE_MS.overridable
    public static let scanWindowVenueMSOverridable = true

    // MARK: - Sessions remote text

    /// sessions_remote_text.CLOCK_SKEW_TOLERANCE_SEC.default
    public static let clockSkewToleranceSecDefault = 300

    /// sessions_remote_text.CLOCK_SKEW_TOLERANCE_SEC.overridable
    public static let clockSkewToleranceSecOverridable = false

    /// sessions_remote_text.DEDUP_CACHE.bounds_unspecified
    public static let dedupCacheBoundsUnspecified = true

    /// sessions_remote_text.DEDUP_CACHE.days
    public static let dedupCacheDays = 7

    /// sessions_remote_text.DEDUP_CACHE.ids
    public static let dedupCacheIDS = 10000

    /// sessions_remote_text.DEDUP_CACHE.overridable
    public static let dedupCacheOverridable = true

    /// sessions_remote_text.MAX_SKIP_PER_CHAIN.default
    public static let maxSkipPerChainDefault = 512

    /// sessions_remote_text.MAX_SKIP_PER_CHAIN.overridable
    public static let maxSkipPerChainOverridable = false

    /// sessions_remote_text.MESSAGE_ID_LEN_BYTES.default
    public static let messageIDLenBytesDefault = 16

    /// sessions_remote_text.MESSAGE_ID_LEN_BYTES.overridable
    public static let messageIDLenBytesOverridable = false

    /// sessions_remote_text.ONE_TIME_PREKEYS_UPLOADED.default
    public static let oneTimePrekeysUploadedDefault = 100

    /// sessions_remote_text.ONE_TIME_PREKEYS_UPLOADED.max
    public static let oneTimePrekeysUploadedMax = 200

    /// sessions_remote_text.ONE_TIME_PREKEYS_UPLOADED.min
    public static let oneTimePrekeysUploadedMin = 50

    /// sessions_remote_text.ONE_TIME_PREKEYS_UPLOADED.overridable
    public static let oneTimePrekeysUploadedOverridable = true

    /// sessions_remote_text.PREKEY_REPLENISH_THRESHOLD.default
    public static let prekeyReplenishThresholdDefault = 20

    /// sessions_remote_text.PREKEY_REPLENISH_THRESHOLD.max
    public static let prekeyReplenishThresholdMax = 50

    /// sessions_remote_text.PREKEY_REPLENISH_THRESHOLD.min
    public static let prekeyReplenishThresholdMin = 10

    /// sessions_remote_text.PREKEY_REPLENISH_THRESHOLD.overridable
    public static let prekeyReplenishThresholdOverridable = true

    /// sessions_remote_text.RELAY_ENVELOPE_TTL_DAYS.default
    public static let relayEnvelopeTTLDaysDefault = 30

    /// sessions_remote_text.RELAY_ENVELOPE_TTL_DAYS.max
    public static let relayEnvelopeTTLDaysMax = 45

    /// sessions_remote_text.RELAY_ENVELOPE_TTL_DAYS.min
    public static let relayEnvelopeTTLDaysMin = 1

    /// sessions_remote_text.RELAY_ENVELOPE_TTL_DAYS.overridable
    public static let relayEnvelopeTTLDaysOverridable = true

    /// sessions_remote_text.RELAY_MAILBOX_QUOTA.bounds_unspecified
    public static let relayMailboxQuotaBoundsUnspecified = true

    /// sessions_remote_text.RELAY_MAILBOX_QUOTA.bytes
    public static let relayMailboxQuotaBytes = 8388608

    /// sessions_remote_text.RELAY_MAILBOX_QUOTA.envelopes
    public static let relayMailboxQuotaEnvelopes = 1000

    /// sessions_remote_text.RELAY_MAILBOX_QUOTA.overridable
    public static let relayMailboxQuotaOverridable = true

    /// sessions_remote_text.RELAY_TEXT_ENVELOPE_MAX_BYTES.default
    public static let relayTextEnvelopeMaxBytesDefault = 32768

    /// sessions_remote_text.RELAY_TEXT_ENVELOPE_MAX_BYTES.max
    public static let relayTextEnvelopeMaxBytesMax = 65536

    /// sessions_remote_text.RELAY_TEXT_ENVELOPE_MAX_BYTES.min
    public static let relayTextEnvelopeMaxBytesMin = 8192

    /// sessions_remote_text.RELAY_TEXT_ENVELOPE_MAX_BYTES.overridable
    public static let relayTextEnvelopeMaxBytesOverridable = true

    /// sessions_remote_text.SAFETY_NUMBER_FORMAT.digits
    public static let safetyNumberFormatDigits = 60

    /// sessions_remote_text.SAFETY_NUMBER_FORMAT.emoji_count
    public static let safetyNumberFormatEmojiCount = 6

    /// sessions_remote_text.SAFETY_NUMBER_FORMAT.overridable
    public static let safetyNumberFormatOverridable = false

    /// sessions_remote_text.SESSION_ID_LEN_BYTES.default
    public static let sessionIDLenBytesDefault = 16

    /// sessions_remote_text.SESSION_ID_LEN_BYTES.overridable
    public static let sessionIDLenBytesOverridable = false

    /// sessions_remote_text.SIGNED_PREKEY_ROTATION_DAYS.default
    public static let signedPrekeyRotationDaysDefault = 7

    /// sessions_remote_text.SIGNED_PREKEY_ROTATION_DAYS.max
    public static let signedPrekeyRotationDaysMax = 30

    /// sessions_remote_text.SIGNED_PREKEY_ROTATION_DAYS.min
    public static let signedPrekeyRotationDaysMin = 3

    /// sessions_remote_text.SIGNED_PREKEY_ROTATION_DAYS.overridable
    public static let signedPrekeyRotationDaysOverridable = true

    /// sessions_remote_text.SKIPPED_KEYS_PER_SESSION_CAP.default
    public static let skippedKeysPerSessionCapDefault = 2000

    /// sessions_remote_text.SKIPPED_KEYS_PER_SESSION_CAP.overridable
    public static let skippedKeysPerSessionCapOverridable = false

    /// sessions_remote_text.SKIPPED_KEY_TTL_DAYS.default
    public static let skippedKeyTTLDaysDefault = 30

    /// sessions_remote_text.SKIPPED_KEY_TTL_DAYS.max
    public static let skippedKeyTTLDaysMax = 45

    /// sessions_remote_text.SKIPPED_KEY_TTL_DAYS.min
    public static let skippedKeyTTLDaysMin = 7

    /// sessions_remote_text.SKIPPED_KEY_TTL_DAYS.overridable
    public static let skippedKeyTTLDaysOverridable = true

    // MARK: - Mesh store forward

    /// mesh_store_forward.MESH_FRAME_TTL_SEC.default
    public static let meshFrameTTLSecDefault = 300

    /// mesh_store_forward.MESH_FRAME_TTL_SEC.max
    public static let meshFrameTTLSecMax = 900

    /// mesh_store_forward.MESH_FRAME_TTL_SEC.min
    public static let meshFrameTTLSecMin = 60

    /// mesh_store_forward.MESH_FRAME_TTL_SEC.overridable
    public static let meshFrameTTLSecOverridable = true

    /// mesh_store_forward.MESH_HOP_ABSOLUTE_CEILING.default
    public static let meshHopAbsoluteCeilingDefault = 5

    /// mesh_store_forward.MESH_HOP_ABSOLUTE_CEILING.overridable
    public static let meshHopAbsoluteCeilingOverridable = false

    /// mesh_store_forward.MESH_HOP_DEFAULT.default
    public static let meshHopDefaultDefault = 3

    /// mesh_store_forward.MESH_HOP_DEFAULT.max
    public static let meshHopDefaultMax = 5

    /// mesh_store_forward.MESH_HOP_DEFAULT.min
    public static let meshHopDefaultMin = 1

    /// mesh_store_forward.MESH_HOP_DEFAULT.overridable
    public static let meshHopDefaultOverridable = true

    /// mesh_store_forward.STORE_FORWARD_QUOTA.bounds_unspecified
    public static let storeForwardQuotaBoundsUnspecified = true

    /// mesh_store_forward.STORE_FORWARD_QUOTA.bytes
    public static let storeForwardQuotaBytes = 2097152

    /// mesh_store_forward.STORE_FORWARD_QUOTA.envelopes
    public static let storeForwardQuotaEnvelopes = 50

    /// mesh_store_forward.STORE_FORWARD_QUOTA.overridable
    public static let storeForwardQuotaOverridable = true

    /// mesh_store_forward.STORE_FORWARD_TTL_SEC.default
    public static let storeForwardTTLSecDefault = 1800

    /// mesh_store_forward.STORE_FORWARD_TTL_SEC.max
    public static let storeForwardTTLSecMax = 7200

    /// mesh_store_forward.STORE_FORWARD_TTL_SEC.min
    public static let storeForwardTTLSecMin = 300

    /// mesh_store_forward.STORE_FORWARD_TTL_SEC.overridable
    public static let storeForwardTTLSecOverridable = true

    // MARK: - Transport

    /// transport.BLE_CHUNK_PAYLOAD.floor
    public static let bleChunkPayloadFloor = 20

    /// transport.BLE_CHUNK_PAYLOAD.overridable
    public static let bleChunkPayloadOverridable = false

    /// transport.BLE_CHUNK_PAYLOAD.rule
    public static let bleChunkPayloadRule = "negotiated_mtu_minus_7_floor_20"

    /// transport.BLE_CHUNK_PAYLOAD.subtract
    public static let bleChunkPayloadSubtract = 7

    /// transport.BLE_TARGET_ATT_MTU.default
    public static let bleTargetAttMTUDefault = 251

    /// transport.BLE_TARGET_ATT_MTU.max
    public static let bleTargetAttMTUMax = 517

    /// transport.BLE_TARGET_ATT_MTU.min
    public static let bleTargetAttMTUMin = 23

    /// transport.BLE_TARGET_ATT_MTU.overridable
    public static let bleTargetAttMTUOverridable = false

    /// transport.CIRCUIT_BREAKER.half_open_after_sec
    public static let circuitBreakerHalfOpenAfterSec = 30

    /// transport.CIRCUIT_BREAKER.open_after_fails
    public static let circuitBreakerOpenAfterFails = 3

    /// transport.CIRCUIT_BREAKER.overridable
    public static let circuitBreakerOverridable = false

    /// transport.LAN_CHUNK_PAYLOAD_BYTES.default
    public static let lanChunkPayloadBytesDefault = 65536

    /// transport.LAN_CHUNK_PAYLOAD_BYTES.overridable
    public static let lanChunkPayloadBytesOverridable = false

    /// transport.QR_BOOTSTRAP_MAX_BYTES.default
    public static let qrBootstrapMaxBytesDefault = 1228

    /// transport.QR_BOOTSTRAP_MAX_BYTES.overridable
    public static let qrBootstrapMaxBytesOverridable = false

    /// transport.REASSEMBLY_TIMEOUT_SEC.ble
    public static let reassemblyTimeoutSecBLE = 120

    /// transport.REASSEMBLY_TIMEOUT_SEC.bounds_unspecified
    public static let reassemblyTimeoutSecBoundsUnspecified = true

    /// transport.REASSEMBLY_TIMEOUT_SEC.lan
    public static let reassemblyTimeoutSecLAN = 30

    /// transport.REASSEMBLY_TIMEOUT_SEC.overridable
    public static let reassemblyTimeoutSecOverridable = true

    /// transport.RETRY_BACKOFF.base_ms
    public static let retryBackoffBaseMS = 1000

    /// transport.RETRY_BACKOFF.cap_ms
    public static let retryBackoffCapMS = 60000

    /// transport.RETRY_BACKOFF.factor
    public static let retryBackoffFactor = 2

    /// transport.RETRY_BACKOFF.jitter
    public static let retryBackoffJitter = true

    /// transport.RETRY_BACKOFF.overridable
    public static let retryBackoffOverridable = false

    /// transport.RETRY_BUDGET_PER_PATH.default
    public static let retryBudgetPerPathDefault = 5

    /// transport.RETRY_BUDGET_PER_PATH.max
    public static let retryBudgetPerPathMax = 8

    /// transport.RETRY_BUDGET_PER_PATH.min
    public static let retryBudgetPerPathMin = 3

    /// transport.RETRY_BUDGET_PER_PATH.overridable
    public static let retryBudgetPerPathOverridable = true

    // MARK: - Media

    /// media.FILE_MAX_MB.default
    public static let fileMaxMBDefault = 25

    /// media.FILE_MAX_MB.max
    public static let fileMaxMBMax = 100

    /// media.FILE_MAX_MB.min
    public static let fileMaxMBMin = 5

    /// media.FILE_MAX_MB.overridable
    public static let fileMaxMBOverridable = true

    /// media.FULL_IMAGE_MAX_MB.default
    public static let fullImageMaxMBDefault = 2

    /// media.FULL_IMAGE_MAX_MB.max
    public static let fullImageMaxMBMax = 10

    /// media.FULL_IMAGE_MAX_MB.min
    public static let fullImageMaxMBMin = 1

    /// media.FULL_IMAGE_MAX_MB.overridable
    public static let fullImageMaxMBOverridable = true

    /// media.MEDIA_CACHE_QUOTA_MB.bounds_unspecified
    public static let mediaCacheQuotaMBBoundsUnspecified = true

    /// media.MEDIA_CACHE_QUOTA_MB.default
    public static let mediaCacheQuotaMBDefault = 512

    /// media.MEDIA_CACHE_QUOTA_MB.overridable
    public static let mediaCacheQuotaMBOverridable = true

    /// media.MEDIA_CACHE_QUOTA_MB.ttl_days
    public static let mediaCacheQuotaMBTTLDays = 30

    /// media.PREVIEW_MAX_KB.default
    public static let previewMaxKBDefault = 64

    /// media.PREVIEW_MAX_KB.max
    public static let previewMaxKBMax = 128

    /// media.PREVIEW_MAX_KB.min
    public static let previewMaxKBMin = 32

    /// media.PREVIEW_MAX_KB.overridable
    public static let previewMaxKBOverridable = true

    /// media.THUMBNAIL_MAX_KB.default
    public static let thumbnailMaxKBDefault = 12

    /// media.THUMBNAIL_MAX_KB.max
    public static let thumbnailMaxKBMax = 16

    /// media.THUMBNAIL_MAX_KB.min
    public static let thumbnailMaxKBMin = 8

    /// media.THUMBNAIL_MAX_KB.overridable
    public static let thumbnailMaxKBOverridable = true

    /// media.VOICE_NOTE.bitrate_kbps
    public static let voiceNoteBitrateKbps: [Int] = [24, 32]

    /// media.VOICE_NOTE.codec
    public static let voiceNoteCodec = "AAC-LC mono M4A"

    /// media.VOICE_NOTE.max_kb
    public static let voiceNoteMaxKB = 600

    /// media.VOICE_NOTE.max_sec
    public static let voiceNoteMaxSec = 120

    /// media.VOICE_NOTE.overridable
    public static let voiceNoteOverridable = true

    /// media.VOICE_NOTE.sample_rate_hz
    public static let voiceNoteSampleRateHZ = 24000

    /// media.VOICE_NOTE.sec_max
    public static let voiceNoteSecMax = 180

    /// media.VOICE_NOTE.sec_min
    public static let voiceNoteSecMin = 30

    // MARK: - Config venue ops

    /// config_venue_ops.CONFIG_PACKAGE_MAX_KB.default
    public static let configPackageMaxKBDefault = 512

    /// config_venue_ops.CONFIG_PACKAGE_MAX_KB.overridable
    public static let configPackageMaxKBOverridable = false

    /// config_venue_ops.CONFIG_REFRESH_INTERVAL_HOURS.default
    public static let configRefreshIntervalHoursDefault = 6

    /// config_venue_ops.CONFIG_REFRESH_INTERVAL_HOURS.jitter
    public static let configRefreshIntervalHoursJitter = true

    /// config_venue_ops.CONFIG_REFRESH_INTERVAL_HOURS.max
    public static let configRefreshIntervalHoursMax = 24

    /// config_venue_ops.CONFIG_REFRESH_INTERVAL_HOURS.min
    public static let configRefreshIntervalHoursMin = 1

    /// config_venue_ops.CONFIG_REFRESH_INTERVAL_HOURS.overridable
    public static let configRefreshIntervalHoursOverridable = true

    /// config_venue_ops.SPONSOR_ASSET_MAX_KB.bounds_unspecified
    public static let sponsorAssetMaxKBBoundsUnspecified = true

    /// config_venue_ops.SPONSOR_ASSET_MAX_KB.cache_count
    public static let sponsorAssetMaxKBCacheCount = 8

    /// config_venue_ops.SPONSOR_ASSET_MAX_KB.default
    public static let sponsorAssetMaxKBDefault = 300

    /// config_venue_ops.SPONSOR_ASSET_MAX_KB.overridable
    public static let sponsorAssetMaxKBOverridable = true

    /// config_venue_ops.SUPPORT_RING_BUFFER_MB.bounds_unspecified
    public static let supportRingBufferMBBoundsUnspecified = true

    /// config_venue_ops.SUPPORT_RING_BUFFER_MB.default
    public static let supportRingBufferMBDefault = 4

    /// config_venue_ops.SUPPORT_RING_BUFFER_MB.overridable
    public static let supportRingBufferMBOverridable = true

    /// config_venue_ops.VENUE_ROOM_POST_RATE_PER_MIN.bounds_unspecified
    public static let venueRoomPostRatePerMinBoundsUnspecified = true

    /// config_venue_ops.VENUE_ROOM_POST_RATE_PER_MIN.default
    public static let venueRoomPostRatePerMinDefault = 10

    /// config_venue_ops.VENUE_ROOM_POST_RATE_PER_MIN.overridable
    public static let venueRoomPostRatePerMinOverridable = true

    /// config_venue_ops.VOUCHER_NONCE_CACHE.bounds_unspecified
    public static let voucherNonceCacheBoundsUnspecified = true

    /// config_venue_ops.VOUCHER_NONCE_CACHE.count
    public static let voucherNonceCacheCount = 5000

    /// config_venue_ops.VOUCHER_NONCE_CACHE.days
    public static let voucherNonceCacheDays = 90

    /// config_venue_ops.VOUCHER_NONCE_CACHE.overridable
    public static let voucherNonceCacheOverridable = true

    // MARK: - Resource budgets

    /// resource_budgets.ACTIVE_VENUE_BATTERY_MAX_PCT_30MIN.budget
    public static let activeVenueBatteryMaxPCT30minBudget = true

    /// resource_budgets.ACTIVE_VENUE_BATTERY_MAX_PCT_30MIN.calibrated
    public static let activeVenueBatteryMaxPCT30minCalibrated = true

    /// resource_budgets.ACTIVE_VENUE_BATTERY_MAX_PCT_30MIN.default
    public static let activeVenueBatteryMaxPCT30minDefault = 6

    /// resource_budgets.ACTIVE_VENUE_BATTERY_MAX_PCT_30MIN.overridable
    public static let activeVenueBatteryMaxPCT30minOverridable = false

    /// resource_budgets.ANDROID_DOWNLOAD_MAX_MB.budget
    public static let androidDownloadMaxMBBudget = true

    /// resource_budgets.ANDROID_DOWNLOAD_MAX_MB.default
    public static let androidDownloadMaxMBDefault = 40

    /// resource_budgets.ANDROID_DOWNLOAD_MAX_MB.overridable
    public static let androidDownloadMaxMBOverridable = false

    /// resource_budgets.COLD_LAUNCH_P95_MS.default
    public static let coldLaunchP95MSDefault = 2000

    /// resource_budgets.COLD_LAUNCH_P95_MS.overridable
    public static let coldLaunchP95MSOverridable = false

    /// resource_budgets.IDLE_BACKGROUND.battery_pct_max
    public static let idleBackgroundBatteryPCTMax = 0

    /// resource_budgets.IDLE_BACKGROUND.overridable
    public static let idleBackgroundOverridable = false

    /// resource_budgets.IDLE_BACKGROUND.wake_locks_permitted
    public static let idleBackgroundWakeLocksPermitted = false

    /// resource_budgets.IOS_DOWNLOAD_MAX_MB.budget
    public static let iosDownloadMaxMBBudget = true

    /// resource_budgets.IOS_DOWNLOAD_MAX_MB.default
    public static let iosDownloadMaxMBDefault = 60

    /// resource_budgets.IOS_DOWNLOAD_MAX_MB.overridable
    public static let iosDownloadMaxMBOverridable = false

    /// resource_budgets.STEADY_STATE_RAM_MAX_MB.budget
    public static let steadyStateRAMMaxMBBudget = true

    /// resource_budgets.STEADY_STATE_RAM_MAX_MB.default
    public static let steadyStateRAMMaxMBDefault = 220

    /// resource_budgets.STEADY_STATE_RAM_MAX_MB.overridable
    public static let steadyStateRAMMaxMBOverridable = false
}

/// Canonical Lumos message class (TRS Sec. 9.2).
public enum MessageClass: UInt8, CaseIterable, Sendable {
    /// Version/capability/session bootstrap
    case hello = 0x01
    /// Capability and session acknowledgement
    case helloAck = 0x02
    /// Consent-stage request payload
    case interestRequest = 0x03
    /// Accept/decline/ignore-safe response
    case interestResponse = 0x04
    /// Approved preview fields
    case profilePreview = 0x05
    /// Approved full profile after match
    case profileFull = 0x06
    /// Ratcheted private text event carrying the inner event envelope (AMD-005)
    case textMessage = 0x10
    /// Delivered state
    case deliveryReceipt = 0x11
    /// Optional read state
    case readReceipt = 0x12
    /// Optional ephemeral state
    case typing = 0x13
    /// Media/file metadata and digest
    case objectMeta = 0x20
    /// Encrypted object fragment
    case objectChunk = 0x21
    /// Selective acknowledgement bitmap/ranges
    case objectAck = 0x22
    /// Cancellation/cleanup
    case objectCancel = 0x23
    /// Voice-note codec/duration metadata
    case voiceMeta = 0x24
    /// Opaque relay wrapper
    case meshRelay = 0x30
    /// Short-TTL opaque mailbox frame
    case storeForward = 0x31
    /// Signed venue package reference
    case venuePackageHint = 0x40
    /// Moderated graphical room event
    case venueRoomEvent = 0x41
    /// Signed voucher/bootstrap token
    case voucherToken = 0x42
    /// Liveness and route health
    case heartbeat = 0x50
    /// Stable machine-readable failure
    case error = 0x7F

    /// The canonical name as written in the register.
    public var canonicalName: String {
        switch self {
        case .hello: return "HELLO"
        case .helloAck: return "HELLO_ACK"
        case .interestRequest: return "INTEREST_REQUEST"
        case .interestResponse: return "INTEREST_RESPONSE"
        case .profilePreview: return "PROFILE_PREVIEW"
        case .profileFull: return "PROFILE_FULL"
        case .textMessage: return "TEXT_MESSAGE"
        case .deliveryReceipt: return "DELIVERY_RECEIPT"
        case .readReceipt: return "READ_RECEIPT"
        case .typing: return "TYPING"
        case .objectMeta: return "OBJECT_META"
        case .objectChunk: return "OBJECT_CHUNK"
        case .objectAck: return "OBJECT_ACK"
        case .objectCancel: return "OBJECT_CANCEL"
        case .voiceMeta: return "VOICE_META"
        case .meshRelay: return "MESH_RELAY"
        case .storeForward: return "STORE_FORWARD"
        case .venuePackageHint: return "VENUE_PACKAGE_HINT"
        case .venueRoomEvent: return "VENUE_ROOM_EVENT"
        case .voucherToken: return "VOUCHER_TOKEN"
        case .heartbeat: return "HEARTBEAT"
        case .error: return "ERROR"
        }
    }
}

/// Inner event kind of a TEXT_MESSAGE envelope (AMD-005).
public enum TextEventKind: UInt8, CaseIterable, Sendable {
    /// Original text event
    case text = 0x00
    /// Authenticated edit over the original message
    case edit = 0x01
    /// Authenticated delete over the original message
    case deleteForEveryone = 0x02
    /// Authenticated reaction over the original message
    case reaction = 0x03

    /// The canonical name as written in the register.
    public var canonicalName: String {
        switch self {
        case .text: return "TEXT"
        case .edit: return "EDIT"
        case .deleteForEveryone: return "DELETE_FOR_EVERYONE"
        case .reaction: return "REACTION"
        }
    }
}
