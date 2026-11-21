// Lumos - protocol/gen/go/lumosconstants / Generated Lumos protocol and policy constants.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CONST-001, TRS-CONST-002, TRS-CONST-005, TRS-REP-002
// ADRs: ADR-002, ADR-004
// Threading: any
// GENERATED - DO NOT EDIT. Generator: tools/constantsgen
// Source register: protocol/constants/lumos_constants.json
// Baseline: TRS v1.1 Sec. 23 / EDP v1.0 Sec. 4 (ADR-004)
// constants_hash: sha256:755935dc90f6ab1ae325b5d27b1d655f7b56898714a824e9e785d107e37fa1d4

package lumosconstants

// ServiceUUID is the one fixed 128-bit Lumos GATT service UUID (ADR-002).
const ServiceUUID = "E853EB6A-775C-4A49-A6BF-320E6A5CC609"

// ConstantsHash binds generated code to the register that produced it (TRS-CONST-005).
const ConstantsHash = "sha256:755935dc90f6ab1ae325b5d27b1d655f7b56898714a824e9e785d107e37fa1d4"

// SchemaVersion is the register schema version.
const SchemaVersion = 1

// Baseline names the specification sections this register was derived from.
const Baseline = "TRS v1.1 Sec. 23 / EDP v1.0 Sec. 4 (ADR-004)"

// Framing

// FrameFlagsReservedMaskDefault is framing.FRAME_FLAGS_RESERVED_MASK.default.
const FrameFlagsReservedMaskDefault = 65520

// FrameFlagsReservedMaskOverridable is framing.FRAME_FLAGS_RESERVED_MASK.overridable.
const FrameFlagsReservedMaskOverridable = false

// FrameFlagCompressedDefault is framing.FRAME_FLAG_COMPRESSED.default.
const FrameFlagCompressedDefault = 2

// FrameFlagCompressedOverridable is framing.FRAME_FLAG_COMPRESSED.overridable.
const FrameFlagCompressedOverridable = false

// FrameFlagCriticalDefault is framing.FRAME_FLAG_CRITICAL.default.
const FrameFlagCriticalDefault = 1

// FrameFlagCriticalOverridable is framing.FRAME_FLAG_CRITICAL.overridable.
const FrameFlagCriticalOverridable = false

// FrameFlagLocalOnlyDefault is framing.FRAME_FLAG_LOCAL_ONLY.default.
const FrameFlagLocalOnlyDefault = 8

// FrameFlagLocalOnlyOverridable is framing.FRAME_FLAG_LOCAL_ONLY.overridable.
const FrameFlagLocalOnlyOverridable = false

// FrameFlagRelayEligibleDefault is framing.FRAME_FLAG_RELAY_ELIGIBLE.default.
const FrameFlagRelayEligibleDefault = 4

// FrameFlagRelayEligibleOverridable is framing.FRAME_FLAG_RELAY_ELIGIBLE.overridable.
const FrameFlagRelayEligibleOverridable = false

// FrameHeaderLenBytesDefault is framing.FRAME_HEADER_LEN_BYTES.default.
const FrameHeaderLenBytesDefault = 58

// FrameHeaderLenBytesOverridable is framing.FRAME_HEADER_LEN_BYTES.overridable.
const FrameHeaderLenBytesOverridable = false

// FrameMagicDefault is framing.FRAME_MAGIC.default.
const FrameMagicDefault = 19533

// FrameMagicOverridable is framing.FRAME_MAGIC.overridable.
const FrameMagicOverridable = false

// ProtocolVersionEncoded is framing.PROTOCOL_VERSION.encoded.
const ProtocolVersionEncoded = 16

// ProtocolVersionMajor is framing.PROTOCOL_VERSION.major.
const ProtocolVersionMajor = 1

// ProtocolVersionMinor is framing.PROTOCOL_VERSION.minor.
const ProtocolVersionMinor = 0

// ProtocolVersionOverridable is framing.PROTOCOL_VERSION.overridable.
const ProtocolVersionOverridable = false

// Crypto protocol

// AdvAuthTagInfoOverridable is crypto_protocol.ADV_AUTH_TAG_INFO.overridable.
const AdvAuthTagInfoOverridable = false

// AdvAuthTagInfoValue is crypto_protocol.ADV_AUTH_TAG_INFO.value.
const AdvAuthTagInfoValue = "lumos/adv/auth-tag/v1"

// AdvEIDKdfInfoOverridable is crypto_protocol.ADV_EID_KDF_INFO.overridable.
const AdvEIDKdfInfoOverridable = false

// AdvEIDKdfInfoValue is crypto_protocol.ADV_EID_KDF_INFO.value.
const AdvEIDKdfInfoValue = "lumos/adv/eid/v1"

// AdvKeyKdfInfoOverridable is crypto_protocol.ADV_KEY_KDF_INFO.overridable.
const AdvKeyKdfInfoOverridable = false

// AdvKeyKdfInfoValue is crypto_protocol.ADV_KEY_KDF_INFO.value.
const AdvKeyKdfInfoValue = "lumos/adv/k-adv/v1"

// ChainKeySeedsMessage is crypto_protocol.CHAIN_KEY_SEEDS.message.
const ChainKeySeedsMessage = 1

// ChainKeySeedsNext is crypto_protocol.CHAIN_KEY_SEEDS.next.
const ChainKeySeedsNext = 2

// ChainKeySeedsOverridable is crypto_protocol.CHAIN_KEY_SEEDS.overridable.
const ChainKeySeedsOverridable = false

// ConfigSigningInfoOverridable is crypto_protocol.CONFIG_SIGNING_INFO.overridable.
const ConfigSigningInfoOverridable = false

// ConfigSigningInfoValue is crypto_protocol.CONFIG_SIGNING_INFO.value.
const ConfigSigningInfoValue = "lumos/config/signed-package/v1"

// DeviceAuthSigningInfoOverridable is crypto_protocol.DEVICE_AUTH_SIGNING_INFO.overridable.
const DeviceAuthSigningInfoOverridable = false

// DeviceAuthSigningInfoValue is crypto_protocol.DEVICE_AUTH_SIGNING_INFO.value.
const DeviceAuthSigningInfoValue = "lumos/device-auth/challenge/v1"

// HelloAckV1LayoutAuthSigLen is crypto_protocol.HELLO_ACK_V1_LAYOUT.auth_sig_len.
const HelloAckV1LayoutAuthSigLen = 64

// HelloAckV1LayoutOverridable is crypto_protocol.HELLO_ACK_V1_LAYOUT.overridable.
const HelloAckV1LayoutOverridable = false

// HelloAckV1LayoutTotalLen is crypto_protocol.HELLO_ACK_V1_LAYOUT.total_len.
const HelloAckV1LayoutTotalLen = 228

// HelloAuthInfoOverridable is crypto_protocol.HELLO_AUTH_INFO.overridable.
const HelloAuthInfoOverridable = false

// HelloAuthInfoValue is crypto_protocol.HELLO_AUTH_INFO.value.
const HelloAuthInfoValue = "lumos/hello/auth/v1"

// HelloV1LayoutBindingSigLen is crypto_protocol.HELLO_V1_LAYOUT.binding_sig_len.
const HelloV1LayoutBindingSigLen = 64

// HelloV1LayoutCapabilityBitsLen is crypto_protocol.HELLO_V1_LAYOUT.capability_bits_len.
const HelloV1LayoutCapabilityBitsLen = 2

// HelloV1LayoutEdPublicLen is crypto_protocol.HELLO_V1_LAYOUT.ed_public_len.
const HelloV1LayoutEdPublicLen = 32

// HelloV1LayoutEIDLen is crypto_protocol.HELLO_V1_LAYOUT.eid_len.
const HelloV1LayoutEIDLen = 12

// HelloV1LayoutHelloVersionLen is crypto_protocol.HELLO_V1_LAYOUT.hello_version_len.
const HelloV1LayoutHelloVersionLen = 1

// HelloV1LayoutNonceLen is crypto_protocol.HELLO_V1_LAYOUT.nonce_len.
const HelloV1LayoutNonceLen = 16

// HelloV1LayoutOverridable is crypto_protocol.HELLO_V1_LAYOUT.overridable.
const HelloV1LayoutOverridable = false

// HelloV1LayoutProtocolVersionLen is crypto_protocol.HELLO_V1_LAYOUT.protocol_version_len.
const HelloV1LayoutProtocolVersionLen = 1

// HelloV1LayoutTotalLen is crypto_protocol.HELLO_V1_LAYOUT.total_len.
const HelloV1LayoutTotalLen = 164

// HelloV1LayoutVenueHintLen is crypto_protocol.HELLO_V1_LAYOUT.venue_hint_len.
const HelloV1LayoutVenueHintLen = 4

// HelloV1LayoutXPublicLen is crypto_protocol.HELLO_V1_LAYOUT.x_public_len.
const HelloV1LayoutXPublicLen = 32

// InterestRequestV1LayoutOverridable is crypto_protocol.INTEREST_REQUEST_V1_LAYOUT.overridable.
const InterestRequestV1LayoutOverridable = false

// InterestRequestV1LayoutPreviewLenLen is crypto_protocol.INTEREST_REQUEST_V1_LAYOUT.preview_len_len.
const InterestRequestV1LayoutPreviewLenLen = 2

// InterestRequestV1LayoutVersion is crypto_protocol.INTEREST_REQUEST_V1_LAYOUT.version.
const InterestRequestV1LayoutVersion = 1

// InterestRequestV1LayoutVersionLen is crypto_protocol.INTEREST_REQUEST_V1_LAYOUT.version_len.
const InterestRequestV1LayoutVersionLen = 1

// InterestResponseV1LayoutOverridable is crypto_protocol.INTEREST_RESPONSE_V1_LAYOUT.overridable.
const InterestResponseV1LayoutOverridable = false

// InterestResponseV1LayoutVerdictAccept is crypto_protocol.INTEREST_RESPONSE_V1_LAYOUT.verdict_accept.
const InterestResponseV1LayoutVerdictAccept = 1

// InterestResponseV1LayoutVerdictLen is crypto_protocol.INTEREST_RESPONSE_V1_LAYOUT.verdict_len.
const InterestResponseV1LayoutVerdictLen = 1

// InterestResponseV1LayoutVerdictUnmatch is crypto_protocol.INTEREST_RESPONSE_V1_LAYOUT.verdict_unmatch.
const InterestResponseV1LayoutVerdictUnmatch = 2

// InterestResponseV1LayoutVersion is crypto_protocol.INTEREST_RESPONSE_V1_LAYOUT.version.
const InterestResponseV1LayoutVersion = 1

// InterestResponseV1LayoutVersionLen is crypto_protocol.INTEREST_RESPONSE_V1_LAYOUT.version_len.
const InterestResponseV1LayoutVersionLen = 1

// MatchIDKdfInfoOverridable is crypto_protocol.MATCH_ID_KDF_INFO.overridable.
const MatchIDKdfInfoOverridable = false

// MatchIDKdfInfoValue is crypto_protocol.MATCH_ID_KDF_INFO.value.
const MatchIDKdfInfoValue = "lumos/match-id/v1"

// MatchIDLenDefault is crypto_protocol.MATCH_ID_LEN.default.
const MatchIDLenDefault = 16

// MatchIDLenOverridable is crypto_protocol.MATCH_ID_LEN.overridable.
const MatchIDLenOverridable = false

// MatchTokenColorsOverridable is crypto_protocol.MATCH_TOKEN_COLORS.overridable.
const MatchTokenColorsOverridable = false

// MatchTokenKdfInfoOverridable is crypto_protocol.MATCH_TOKEN_KDF_INFO.overridable.
const MatchTokenKdfInfoOverridable = false

// MatchTokenKdfInfoValue is crypto_protocol.MATCH_TOKEN_KDF_INFO.value.
const MatchTokenKdfInfoValue = "lumos/match-token/v1"

// MediaObjectKeyInfoOverridable is crypto_protocol.MEDIA_OBJECT_KEY_INFO.overridable.
const MediaObjectKeyInfoOverridable = false

// MediaObjectKeyInfoValue is crypto_protocol.MEDIA_OBJECT_KEY_INFO.value.
const MediaObjectKeyInfoValue = "lumos/media/object-key/v1"

// MediaObjectKeyWrapInfoOverridable is crypto_protocol.MEDIA_OBJECT_KEY_WRAP_INFO.overridable.
const MediaObjectKeyWrapInfoOverridable = false

// MediaObjectKeyWrapInfoValue is crypto_protocol.MEDIA_OBJECT_KEY_WRAP_INFO.value.
const MediaObjectKeyWrapInfoValue = "lumos/media/object-key-wrap/v1"

// PeerIDKdfInfoOverridable is crypto_protocol.PEER_ID_KDF_INFO.overridable.
const PeerIDKdfInfoOverridable = false

// PeerIDKdfInfoValue is crypto_protocol.PEER_ID_KDF_INFO.value.
const PeerIDKdfInfoValue = "lumos/peer-id/v1"

// PeerIDLenDefault is crypto_protocol.PEER_ID_LEN.default.
const PeerIDLenDefault = 16

// PeerIDLenOverridable is crypto_protocol.PEER_ID_LEN.overridable.
const PeerIDLenOverridable = false

// RatchetHeaderLayoutDhLen is crypto_protocol.RATCHET_HEADER_LAYOUT.dh_len.
const RatchetHeaderLayoutDhLen = 32

// RatchetHeaderLayoutNLen is crypto_protocol.RATCHET_HEADER_LAYOUT.n_len.
const RatchetHeaderLayoutNLen = 4

// RatchetHeaderLayoutOverridable is crypto_protocol.RATCHET_HEADER_LAYOUT.overridable.
const RatchetHeaderLayoutOverridable = false

// RatchetHeaderLayoutPnLen is crypto_protocol.RATCHET_HEADER_LAYOUT.pn_len.
const RatchetHeaderLayoutPnLen = 4

// RatchetHeaderLayoutTotalLen is crypto_protocol.RATCHET_HEADER_LAYOUT.total_len.
const RatchetHeaderLayoutTotalLen = 40

// RatchetMessageKdfInfoOverridable is crypto_protocol.RATCHET_MESSAGE_KDF_INFO.overridable.
const RatchetMessageKdfInfoOverridable = false

// RatchetMessageKdfInfoValue is crypto_protocol.RATCHET_MESSAGE_KDF_INFO.value.
const RatchetMessageKdfInfoValue = "lumos/ratchet/msg/v1"

// RatchetRootKdfInfoOverridable is crypto_protocol.RATCHET_ROOT_KDF_INFO.overridable.
const RatchetRootKdfInfoOverridable = false

// RatchetRootKdfInfoValue is crypto_protocol.RATCHET_ROOT_KDF_INFO.value.
const RatchetRootKdfInfoValue = "lumos/ratchet/root/v1"

// RecoveryArgon2idIterations is crypto_protocol.RECOVERY_ARGON2ID.iterations.
const RecoveryArgon2idIterations = 3

// RecoveryArgon2idIterationsMax is crypto_protocol.RECOVERY_ARGON2ID.iterations_max.
const RecoveryArgon2idIterationsMax = 10

// RecoveryArgon2idIterationsMin is crypto_protocol.RECOVERY_ARGON2ID.iterations_min.
const RecoveryArgon2idIterationsMin = 2

// RecoveryArgon2idKeyBytes is crypto_protocol.RECOVERY_ARGON2ID.key_bytes.
const RecoveryArgon2idKeyBytes = 32

// RecoveryArgon2idMemoryKib is crypto_protocol.RECOVERY_ARGON2ID.memory_kib.
const RecoveryArgon2idMemoryKib = 65536

// RecoveryArgon2idMemoryKibMax is crypto_protocol.RECOVERY_ARGON2ID.memory_kib_max.
const RecoveryArgon2idMemoryKibMax = 262144

// RecoveryArgon2idMemoryKibMin is crypto_protocol.RECOVERY_ARGON2ID.memory_kib_min.
const RecoveryArgon2idMemoryKibMin = 32768

// RecoveryArgon2idOverridable is crypto_protocol.RECOVERY_ARGON2ID.overridable.
const RecoveryArgon2idOverridable = false

// RecoveryArgon2idParallelism is crypto_protocol.RECOVERY_ARGON2ID.parallelism.
const RecoveryArgon2idParallelism = 1

// RecoveryArgon2idSaltBytes is crypto_protocol.RECOVERY_ARGON2ID.salt_bytes.
const RecoveryArgon2idSaltBytes = 16

// SafetyNumberEmojiAlphabetOverridable is crypto_protocol.SAFETY_NUMBER_EMOJI_ALPHABET.overridable.
const SafetyNumberEmojiAlphabetOverridable = false

// SafetyNumberIterationsDefault is crypto_protocol.SAFETY_NUMBER_ITERATIONS.default.
const SafetyNumberIterationsDefault = 5200

// SafetyNumberIterationsOverridable is crypto_protocol.SAFETY_NUMBER_ITERATIONS.overridable.
const SafetyNumberIterationsOverridable = false

// SafetyNumberKdfInfoOverridable is crypto_protocol.SAFETY_NUMBER_KDF_INFO.overridable.
const SafetyNumberKdfInfoOverridable = false

// SafetyNumberKdfInfoValue is crypto_protocol.SAFETY_NUMBER_KDF_INFO.value.
const SafetyNumberKdfInfoValue = "lumos/safety-number/v1"

// VoucherSigningInfoOverridable is crypto_protocol.VOUCHER_SIGNING_INFO.overridable.
const VoucherSigningInfoOverridable = false

// VoucherSigningInfoValue is crypto_protocol.VOUCHER_SIGNING_INFO.value.
const VoucherSigningInfoValue = "lumos/voucher/token/v1"

// X3dhFPrefixFillByte is crypto_protocol.X3DH_F_PREFIX.fill_byte.
const X3dhFPrefixFillByte = 255

// X3dhFPrefixLen is crypto_protocol.X3DH_F_PREFIX.len.
const X3dhFPrefixLen = 32

// X3dhFPrefixOverridable is crypto_protocol.X3DH_F_PREFIX.overridable.
const X3dhFPrefixOverridable = false

// X3dhIdentityBindingInfoOverridable is crypto_protocol.X3DH_IDENTITY_BINDING_INFO.overridable.
const X3dhIdentityBindingInfoOverridable = false

// X3dhIdentityBindingInfoValue is crypto_protocol.X3DH_IDENTITY_BINDING_INFO.value.
const X3dhIdentityBindingInfoValue = "lumos/x3dh/identity-dh-binding/v1"

// X3dhKdfInfoOverridable is crypto_protocol.X3DH_KDF_INFO.overridable.
const X3dhKdfInfoOverridable = false

// X3dhKdfInfoValue is crypto_protocol.X3DH_KDF_INFO.value.
const X3dhKdfInfoValue = "lumos/x3dh/v1"

// X3dhOpkIDNoneDefault is crypto_protocol.X3DH_OPK_ID_NONE.default.
const X3dhOpkIDNoneDefault = 4294967295

// X3dhOpkIDNoneOverridable is crypto_protocol.X3DH_OPK_ID_NONE.overridable.
const X3dhOpkIDNoneOverridable = false

// X3dhPrekeySigningInfoOverridable is crypto_protocol.X3DH_PREKEY_SIGNING_INFO.overridable.
const X3dhPrekeySigningInfoOverridable = false

// X3dhPrekeySigningInfoValue is crypto_protocol.X3DH_PREKEY_SIGNING_INFO.value.
const X3dhPrekeySigningInfoValue = "lumos/x3dh/signed-prekey/v1"

// X3dhTranscriptInfoOverridable is crypto_protocol.X3DH_TRANSCRIPT_INFO.overridable.
const X3dhTranscriptInfoOverridable = false

// X3dhTranscriptInfoValue is crypto_protocol.X3DH_TRANSCRIPT_INFO.value.
const X3dhTranscriptInfoValue = "lumos/x3dh/transcript/v1"

// Identity discovery

// AdvAuthTagLenDefault is identity_discovery.ADV_AUTH_TAG_LEN.default.
const AdvAuthTagLenDefault = 4

// AdvAuthTagLenOverridable is identity_discovery.ADV_AUTH_TAG_LEN.overridable.
const AdvAuthTagLenOverridable = false

// AdvV1LayoutAuthTagLen is identity_discovery.ADV_V1_LAYOUT.auth_tag_len.
const AdvV1LayoutAuthTagLen = 4

// AdvV1LayoutEIDLen is identity_discovery.ADV_V1_LAYOUT.eid_len.
const AdvV1LayoutEIDLen = 12

// AdvV1LayoutModeFlagsLen is identity_discovery.ADV_V1_LAYOUT.mode_flags_len.
const AdvV1LayoutModeFlagsLen = 1

// AdvV1LayoutOverridable is identity_discovery.ADV_V1_LAYOUT.overridable.
const AdvV1LayoutOverridable = false

// AdvV1LayoutTotalLen is identity_discovery.ADV_V1_LAYOUT.total_len.
const AdvV1LayoutTotalLen = 18

// AdvV1LayoutVersionLen is identity_discovery.ADV_V1_LAYOUT.version_len.
const AdvV1LayoutVersionLen = 1

// AdvV1ScanResponseLayoutCapabilityBitsLen is identity_discovery.ADV_V1_SCAN_RESPONSE_LAYOUT.capability_bits_len.
const AdvV1ScanResponseLayoutCapabilityBitsLen = 2

// AdvV1ScanResponseLayoutOverridable is identity_discovery.ADV_V1_SCAN_RESPONSE_LAYOUT.overridable.
const AdvV1ScanResponseLayoutOverridable = false

// AdvV1ScanResponseLayoutTotalLen is identity_discovery.ADV_V1_SCAN_RESPONSE_LAYOUT.total_len.
const AdvV1ScanResponseLayoutTotalLen = 6

// AdvV1ScanResponseLayoutVenueHintLen is identity_discovery.ADV_V1_SCAN_RESPONSE_LAYOUT.venue_hint_len.
const AdvV1ScanResponseLayoutVenueHintLen = 4

// EIDRotationSecDefault is identity_discovery.EID_ROTATION_SEC.default.
const EIDRotationSecDefault = 600

// EIDRotationSecMax is identity_discovery.EID_ROTATION_SEC.max.
const EIDRotationSecMax = 900

// EIDRotationSecMin is identity_discovery.EID_ROTATION_SEC.min.
const EIDRotationSecMin = 300

// EIDRotationSecOverridable is identity_discovery.EID_ROTATION_SEC.overridable.
const EIDRotationSecOverridable = true

// InterestCooldownSecDefault is identity_discovery.INTEREST_COOLDOWN_SEC.default.
const InterestCooldownSecDefault = 900

// InterestCooldownSecMax is identity_discovery.INTEREST_COOLDOWN_SEC.max.
const InterestCooldownSecMax = 3600

// InterestCooldownSecMin is identity_discovery.INTEREST_COOLDOWN_SEC.min.
const InterestCooldownSecMin = 60

// InterestCooldownSecOverridable is identity_discovery.INTEREST_COOLDOWN_SEC.overridable.
const InterestCooldownSecOverridable = true

// InterestRateLimitPerHourDefault is identity_discovery.INTEREST_RATE_LIMIT_PER_HOUR.default.
const InterestRateLimitPerHourDefault = 20

// InterestRateLimitPerHourMax is identity_discovery.INTEREST_RATE_LIMIT_PER_HOUR.max.
const InterestRateLimitPerHourMax = 60

// InterestRateLimitPerHourMin is identity_discovery.INTEREST_RATE_LIMIT_PER_HOUR.min.
const InterestRateLimitPerHourMin = 5

// InterestRateLimitPerHourOverridable is identity_discovery.INTEREST_RATE_LIMIT_PER_HOUR.overridable.
const InterestRateLimitPerHourOverridable = true

// MaxConcurrentGATTLinksDefault is identity_discovery.MAX_CONCURRENT_GATT_LINKS.default.
const MaxConcurrentGATTLinksDefault = 4

// MaxConcurrentGATTLinksMax is identity_discovery.MAX_CONCURRENT_GATT_LINKS.max.
const MaxConcurrentGATTLinksMax = 7

// MaxConcurrentGATTLinksMin is identity_discovery.MAX_CONCURRENT_GATT_LINKS.min.
const MaxConcurrentGATTLinksMin = 2

// MaxConcurrentGATTLinksOverridable is identity_discovery.MAX_CONCURRENT_GATT_LINKS.overridable.
const MaxConcurrentGATTLinksOverridable = true

// NearbySyntheticLoadTargetDefault is identity_discovery.NEARBY_SYNTHETIC_LOAD_TARGET.default.
const NearbySyntheticLoadTargetDefault = 100

// NearbySyntheticLoadTargetOverridable is identity_discovery.NEARBY_SYNTHETIC_LOAD_TARGET.overridable.
const NearbySyntheticLoadTargetOverridable = false

// NearbySyntheticLoadTargetTestOnly is identity_discovery.NEARBY_SYNTHETIC_LOAD_TARGET.test_only.
const NearbySyntheticLoadTargetTestOnly = true

// PresenceAgeoutSecDefault is identity_discovery.PRESENCE_AGEOUT_SEC.default.
const PresenceAgeoutSecDefault = 90

// PresenceAgeoutSecMax is identity_discovery.PRESENCE_AGEOUT_SEC.max.
const PresenceAgeoutSecMax = 300

// PresenceAgeoutSecMin is identity_discovery.PRESENCE_AGEOUT_SEC.min.
const PresenceAgeoutSecMin = 30

// PresenceAgeoutSecOverridable is identity_discovery.PRESENCE_AGEOUT_SEC.overridable.
const PresenceAgeoutSecOverridable = true

// RescanLadderCalibrated is identity_discovery.RESCAN_LADDER.calibrated.
const RescanLadderCalibrated = true

// RescanLadderMaxSteps is identity_discovery.RESCAN_LADDER.max_steps.
const RescanLadderMaxSteps = 3

// RescanLadderMaxStepsMax is identity_discovery.RESCAN_LADDER.max_steps_max.
const RescanLadderMaxStepsMax = 5

// RescanLadderMaxStepsMin is identity_discovery.RESCAN_LADDER.max_steps_min.
const RescanLadderMaxStepsMin = 1

// RescanLadderOverridable is identity_discovery.RESCAN_LADDER.overridable.
const RescanLadderOverridable = true

// RescanLadderStepPCT is identity_discovery.RESCAN_LADDER.step_pct.
const RescanLadderStepPCT = 50

// RescanLadderStepPCTMax is identity_discovery.RESCAN_LADDER.step_pct_max.
const RescanLadderStepPCTMax = 100

// RescanLadderStepPCTMin is identity_discovery.RESCAN_LADDER.step_pct_min.
const RescanLadderStepPCTMin = 25

// RescanLadderTriggerEmptyCycles is identity_discovery.RESCAN_LADDER.trigger_empty_cycles.
const RescanLadderTriggerEmptyCycles = 2

// RescanLadderTriggerEmptyCyclesMax is identity_discovery.RESCAN_LADDER.trigger_empty_cycles_max.
const RescanLadderTriggerEmptyCyclesMax = 4

// RescanLadderTriggerEmptyCyclesMin is identity_discovery.RESCAN_LADDER.trigger_empty_cycles_min.
const RescanLadderTriggerEmptyCyclesMin = 1

// RSSIBandsDBMCalibrated is identity_discovery.RSSI_BANDS_DBM.calibrated.
const RSSIBandsDBMCalibrated = true

// RSSIBandsDBMNearbyGte is identity_discovery.RSSI_BANDS_DBM.nearby_gte.
const RSSIBandsDBMNearbyGte = -75

// RSSIBandsDBMNearbyGteMax is identity_discovery.RSSI_BANDS_DBM.nearby_gte_max.
const RSSIBandsDBMNearbyGteMax = -65

// RSSIBandsDBMNearbyGteMin is identity_discovery.RSSI_BANDS_DBM.nearby_gte_min.
const RSSIBandsDBMNearbyGteMin = -85

// RSSIBandsDBMOverridable is identity_discovery.RSSI_BANDS_DBM.overridable.
const RSSIBandsDBMOverridable = true

// RSSIBandsDBMVeryCloseGte is identity_discovery.RSSI_BANDS_DBM.very_close_gte.
const RSSIBandsDBMVeryCloseGte = -55

// RSSIBandsDBMVeryCloseGteMax is identity_discovery.RSSI_BANDS_DBM.very_close_gte_max.
const RSSIBandsDBMVeryCloseGteMax = -45

// RSSIBandsDBMVeryCloseGteMin is identity_discovery.RSSI_BANDS_DBM.very_close_gte_min.
const RSSIBandsDBMVeryCloseGteMin = -60

// ScanWindowFgMSCalibrated is identity_discovery.SCAN_WINDOW_FG_MS.calibrated.
const ScanWindowFgMSCalibrated = true

// ScanWindowFgMSDutyMaxPCT is identity_discovery.SCAN_WINDOW_FG_MS.duty_max_pct.
const ScanWindowFgMSDutyMaxPCT = 60

// ScanWindowFgMSDutyMinPCT is identity_discovery.SCAN_WINDOW_FG_MS.duty_min_pct.
const ScanWindowFgMSDutyMinPCT = 20

// ScanWindowFgMSOff is identity_discovery.SCAN_WINDOW_FG_MS.off.
const ScanWindowFgMSOff = 6000

// ScanWindowFgMSOn is identity_discovery.SCAN_WINDOW_FG_MS.on.
const ScanWindowFgMSOn = 4000

// ScanWindowFgMSOverridable is identity_discovery.SCAN_WINDOW_FG_MS.overridable.
const ScanWindowFgMSOverridable = true

// ScanWindowVenueMSCalibrated is identity_discovery.SCAN_WINDOW_VENUE_MS.calibrated.
const ScanWindowVenueMSCalibrated = true

// ScanWindowVenueMSDutyMaxPCT is identity_discovery.SCAN_WINDOW_VENUE_MS.duty_max_pct.
const ScanWindowVenueMSDutyMaxPCT = 70

// ScanWindowVenueMSOff is identity_discovery.SCAN_WINDOW_VENUE_MS.off.
const ScanWindowVenueMSOff = 4000

// ScanWindowVenueMSOn is identity_discovery.SCAN_WINDOW_VENUE_MS.on.
const ScanWindowVenueMSOn = 6000

// ScanWindowVenueMSOverridable is identity_discovery.SCAN_WINDOW_VENUE_MS.overridable.
const ScanWindowVenueMSOverridable = true

// Sessions remote text

// ClockSkewToleranceSecDefault is sessions_remote_text.CLOCK_SKEW_TOLERANCE_SEC.default.
const ClockSkewToleranceSecDefault = 300

// ClockSkewToleranceSecOverridable is sessions_remote_text.CLOCK_SKEW_TOLERANCE_SEC.overridable.
const ClockSkewToleranceSecOverridable = false

// DedupCacheDays is sessions_remote_text.DEDUP_CACHE.days.
const DedupCacheDays = 7

// DedupCacheDaysMax is sessions_remote_text.DEDUP_CACHE.days_max.
const DedupCacheDaysMax = 30

// DedupCacheDaysMin is sessions_remote_text.DEDUP_CACHE.days_min.
const DedupCacheDaysMin = 3

// DedupCacheIDS is sessions_remote_text.DEDUP_CACHE.ids.
const DedupCacheIDS = 10000

// DedupCacheIDSMax is sessions_remote_text.DEDUP_CACHE.ids_max.
const DedupCacheIDSMax = 50000

// DedupCacheIDSMin is sessions_remote_text.DEDUP_CACHE.ids_min.
const DedupCacheIDSMin = 5000

// DedupCacheOverridable is sessions_remote_text.DEDUP_CACHE.overridable.
const DedupCacheOverridable = true

// DeviceAuthChallengeTTLSecDefault is sessions_remote_text.DEVICE_AUTH_CHALLENGE_TTL_SEC.default.
const DeviceAuthChallengeTTLSecDefault = 120

// DeviceAuthChallengeTTLSecOverridable is sessions_remote_text.DEVICE_AUTH_CHALLENGE_TTL_SEC.overridable.
const DeviceAuthChallengeTTLSecOverridable = false

// MaxSkipPerChainDefault is sessions_remote_text.MAX_SKIP_PER_CHAIN.default.
const MaxSkipPerChainDefault = 512

// MaxSkipPerChainOverridable is sessions_remote_text.MAX_SKIP_PER_CHAIN.overridable.
const MaxSkipPerChainOverridable = false

// MessageIDLenBytesDefault is sessions_remote_text.MESSAGE_ID_LEN_BYTES.default.
const MessageIDLenBytesDefault = 16

// MessageIDLenBytesOverridable is sessions_remote_text.MESSAGE_ID_LEN_BYTES.overridable.
const MessageIDLenBytesOverridable = false

// OneTimePrekeysUploadedDefault is sessions_remote_text.ONE_TIME_PREKEYS_UPLOADED.default.
const OneTimePrekeysUploadedDefault = 100

// OneTimePrekeysUploadedMax is sessions_remote_text.ONE_TIME_PREKEYS_UPLOADED.max.
const OneTimePrekeysUploadedMax = 200

// OneTimePrekeysUploadedMin is sessions_remote_text.ONE_TIME_PREKEYS_UPLOADED.min.
const OneTimePrekeysUploadedMin = 50

// OneTimePrekeysUploadedOverridable is sessions_remote_text.ONE_TIME_PREKEYS_UPLOADED.overridable.
const OneTimePrekeysUploadedOverridable = true

// PrekeyReplenishThresholdDefault is sessions_remote_text.PREKEY_REPLENISH_THRESHOLD.default.
const PrekeyReplenishThresholdDefault = 20

// PrekeyReplenishThresholdMax is sessions_remote_text.PREKEY_REPLENISH_THRESHOLD.max.
const PrekeyReplenishThresholdMax = 50

// PrekeyReplenishThresholdMin is sessions_remote_text.PREKEY_REPLENISH_THRESHOLD.min.
const PrekeyReplenishThresholdMin = 10

// PrekeyReplenishThresholdOverridable is sessions_remote_text.PREKEY_REPLENISH_THRESHOLD.overridable.
const PrekeyReplenishThresholdOverridable = true

// RelayEnvelopeTTLDaysDefault is sessions_remote_text.RELAY_ENVELOPE_TTL_DAYS.default.
const RelayEnvelopeTTLDaysDefault = 30

// RelayEnvelopeTTLDaysMax is sessions_remote_text.RELAY_ENVELOPE_TTL_DAYS.max.
const RelayEnvelopeTTLDaysMax = 45

// RelayEnvelopeTTLDaysMin is sessions_remote_text.RELAY_ENVELOPE_TTL_DAYS.min.
const RelayEnvelopeTTLDaysMin = 1

// RelayEnvelopeTTLDaysOverridable is sessions_remote_text.RELAY_ENVELOPE_TTL_DAYS.overridable.
const RelayEnvelopeTTLDaysOverridable = true

// RelayMailboxQuotaBytes is sessions_remote_text.RELAY_MAILBOX_QUOTA.bytes.
const RelayMailboxQuotaBytes = 8388608

// RelayMailboxQuotaBytesMax is sessions_remote_text.RELAY_MAILBOX_QUOTA.bytes_max.
const RelayMailboxQuotaBytesMax = 33554432

// RelayMailboxQuotaBytesMin is sessions_remote_text.RELAY_MAILBOX_QUOTA.bytes_min.
const RelayMailboxQuotaBytesMin = 2097152

// RelayMailboxQuotaEnvelopes is sessions_remote_text.RELAY_MAILBOX_QUOTA.envelopes.
const RelayMailboxQuotaEnvelopes = 1000

// RelayMailboxQuotaEnvelopesMax is sessions_remote_text.RELAY_MAILBOX_QUOTA.envelopes_max.
const RelayMailboxQuotaEnvelopesMax = 5000

// RelayMailboxQuotaEnvelopesMin is sessions_remote_text.RELAY_MAILBOX_QUOTA.envelopes_min.
const RelayMailboxQuotaEnvelopesMin = 200

// RelayMailboxQuotaOverridable is sessions_remote_text.RELAY_MAILBOX_QUOTA.overridable.
const RelayMailboxQuotaOverridable = true

// RelayTextEnvelopeMaxBytesDefault is sessions_remote_text.RELAY_TEXT_ENVELOPE_MAX_BYTES.default.
const RelayTextEnvelopeMaxBytesDefault = 32768

// RelayTextEnvelopeMaxBytesMax is sessions_remote_text.RELAY_TEXT_ENVELOPE_MAX_BYTES.max.
const RelayTextEnvelopeMaxBytesMax = 65536

// RelayTextEnvelopeMaxBytesMin is sessions_remote_text.RELAY_TEXT_ENVELOPE_MAX_BYTES.min.
const RelayTextEnvelopeMaxBytesMin = 8192

// RelayTextEnvelopeMaxBytesOverridable is sessions_remote_text.RELAY_TEXT_ENVELOPE_MAX_BYTES.overridable.
const RelayTextEnvelopeMaxBytesOverridable = true

// SafetyNumberFormatDigits is sessions_remote_text.SAFETY_NUMBER_FORMAT.digits.
const SafetyNumberFormatDigits = 60

// SafetyNumberFormatEmojiAlphabetSize is sessions_remote_text.SAFETY_NUMBER_FORMAT.emoji_alphabet_size.
const SafetyNumberFormatEmojiAlphabetSize = 64

// SafetyNumberFormatEmojiCount is sessions_remote_text.SAFETY_NUMBER_FORMAT.emoji_count.
const SafetyNumberFormatEmojiCount = 6

// SafetyNumberFormatOverridable is sessions_remote_text.SAFETY_NUMBER_FORMAT.overridable.
const SafetyNumberFormatOverridable = false

// SessionIDLenBytesDefault is sessions_remote_text.SESSION_ID_LEN_BYTES.default.
const SessionIDLenBytesDefault = 16

// SessionIDLenBytesOverridable is sessions_remote_text.SESSION_ID_LEN_BYTES.overridable.
const SessionIDLenBytesOverridable = false

// SignedPrekeyRotationDaysDefault is sessions_remote_text.SIGNED_PREKEY_ROTATION_DAYS.default.
const SignedPrekeyRotationDaysDefault = 7

// SignedPrekeyRotationDaysMax is sessions_remote_text.SIGNED_PREKEY_ROTATION_DAYS.max.
const SignedPrekeyRotationDaysMax = 30

// SignedPrekeyRotationDaysMin is sessions_remote_text.SIGNED_PREKEY_ROTATION_DAYS.min.
const SignedPrekeyRotationDaysMin = 3

// SignedPrekeyRotationDaysOverridable is sessions_remote_text.SIGNED_PREKEY_ROTATION_DAYS.overridable.
const SignedPrekeyRotationDaysOverridable = true

// SkippedKeysPerSessionCapDefault is sessions_remote_text.SKIPPED_KEYS_PER_SESSION_CAP.default.
const SkippedKeysPerSessionCapDefault = 2000

// SkippedKeysPerSessionCapOverridable is sessions_remote_text.SKIPPED_KEYS_PER_SESSION_CAP.overridable.
const SkippedKeysPerSessionCapOverridable = false

// SkippedKeyTTLDaysDefault is sessions_remote_text.SKIPPED_KEY_TTL_DAYS.default.
const SkippedKeyTTLDaysDefault = 30

// SkippedKeyTTLDaysMax is sessions_remote_text.SKIPPED_KEY_TTL_DAYS.max.
const SkippedKeyTTLDaysMax = 45

// SkippedKeyTTLDaysMin is sessions_remote_text.SKIPPED_KEY_TTL_DAYS.min.
const SkippedKeyTTLDaysMin = 7

// SkippedKeyTTLDaysOverridable is sessions_remote_text.SKIPPED_KEY_TTL_DAYS.overridable.
const SkippedKeyTTLDaysOverridable = true

// Mesh store forward

// MeshFrameTTLSecDefault is mesh_store_forward.MESH_FRAME_TTL_SEC.default.
const MeshFrameTTLSecDefault = 300

// MeshFrameTTLSecMax is mesh_store_forward.MESH_FRAME_TTL_SEC.max.
const MeshFrameTTLSecMax = 900

// MeshFrameTTLSecMin is mesh_store_forward.MESH_FRAME_TTL_SEC.min.
const MeshFrameTTLSecMin = 60

// MeshFrameTTLSecOverridable is mesh_store_forward.MESH_FRAME_TTL_SEC.overridable.
const MeshFrameTTLSecOverridable = true

// MeshHopAbsoluteCeilingDefault is mesh_store_forward.MESH_HOP_ABSOLUTE_CEILING.default.
const MeshHopAbsoluteCeilingDefault = 5

// MeshHopAbsoluteCeilingOverridable is mesh_store_forward.MESH_HOP_ABSOLUTE_CEILING.overridable.
const MeshHopAbsoluteCeilingOverridable = false

// MeshHopDefaultDefault is mesh_store_forward.MESH_HOP_DEFAULT.default.
const MeshHopDefaultDefault = 3

// MeshHopDefaultMax is mesh_store_forward.MESH_HOP_DEFAULT.max.
const MeshHopDefaultMax = 5

// MeshHopDefaultMin is mesh_store_forward.MESH_HOP_DEFAULT.min.
const MeshHopDefaultMin = 1

// MeshHopDefaultOverridable is mesh_store_forward.MESH_HOP_DEFAULT.overridable.
const MeshHopDefaultOverridable = true

// StoreForwardQuotaBytes is mesh_store_forward.STORE_FORWARD_QUOTA.bytes.
const StoreForwardQuotaBytes = 2097152

// StoreForwardQuotaBytesMax is mesh_store_forward.STORE_FORWARD_QUOTA.bytes_max.
const StoreForwardQuotaBytesMax = 8388608

// StoreForwardQuotaBytesMin is mesh_store_forward.STORE_FORWARD_QUOTA.bytes_min.
const StoreForwardQuotaBytesMin = 524288

// StoreForwardQuotaEnvelopes is mesh_store_forward.STORE_FORWARD_QUOTA.envelopes.
const StoreForwardQuotaEnvelopes = 50

// StoreForwardQuotaEnvelopesMax is mesh_store_forward.STORE_FORWARD_QUOTA.envelopes_max.
const StoreForwardQuotaEnvelopesMax = 200

// StoreForwardQuotaEnvelopesMin is mesh_store_forward.STORE_FORWARD_QUOTA.envelopes_min.
const StoreForwardQuotaEnvelopesMin = 10

// StoreForwardQuotaOverridable is mesh_store_forward.STORE_FORWARD_QUOTA.overridable.
const StoreForwardQuotaOverridable = true

// StoreForwardTTLSecDefault is mesh_store_forward.STORE_FORWARD_TTL_SEC.default.
const StoreForwardTTLSecDefault = 1800

// StoreForwardTTLSecMax is mesh_store_forward.STORE_FORWARD_TTL_SEC.max.
const StoreForwardTTLSecMax = 7200

// StoreForwardTTLSecMin is mesh_store_forward.STORE_FORWARD_TTL_SEC.min.
const StoreForwardTTLSecMin = 300

// StoreForwardTTLSecOverridable is mesh_store_forward.STORE_FORWARD_TTL_SEC.overridable.
const StoreForwardTTLSecOverridable = true

// Transport

// BLEChunkPayloadFloor is transport.BLE_CHUNK_PAYLOAD.floor.
const BLEChunkPayloadFloor = 20

// BLEChunkPayloadOverridable is transport.BLE_CHUNK_PAYLOAD.overridable.
const BLEChunkPayloadOverridable = false

// BLEChunkPayloadRule is transport.BLE_CHUNK_PAYLOAD.rule.
const BLEChunkPayloadRule = "negotiated_mtu_minus_7_floor_20"

// BLEChunkPayloadSubtract is transport.BLE_CHUNK_PAYLOAD.subtract.
const BLEChunkPayloadSubtract = 7

// BLETargetAttMTUDefault is transport.BLE_TARGET_ATT_MTU.default.
const BLETargetAttMTUDefault = 251

// BLETargetAttMTUMax is transport.BLE_TARGET_ATT_MTU.max.
const BLETargetAttMTUMax = 517

// BLETargetAttMTUMin is transport.BLE_TARGET_ATT_MTU.min.
const BLETargetAttMTUMin = 23

// BLETargetAttMTUOverridable is transport.BLE_TARGET_ATT_MTU.overridable.
const BLETargetAttMTUOverridable = false

// CircuitBreakerHalfOpenAfterSec is transport.CIRCUIT_BREAKER.half_open_after_sec.
const CircuitBreakerHalfOpenAfterSec = 30

// CircuitBreakerOpenAfterFails is transport.CIRCUIT_BREAKER.open_after_fails.
const CircuitBreakerOpenAfterFails = 3

// CircuitBreakerOverridable is transport.CIRCUIT_BREAKER.overridable.
const CircuitBreakerOverridable = false

// FragFlagsReservedMaskDefault is transport.FRAG_FLAGS_RESERVED_MASK.default.
const FragFlagsReservedMaskDefault = 252

// FragFlagsReservedMaskOverridable is transport.FRAG_FLAGS_RESERVED_MASK.overridable.
const FragFlagsReservedMaskOverridable = false

// FragFlagFirstDefault is transport.FRAG_FLAG_FIRST.default.
const FragFlagFirstDefault = 1

// FragFlagFirstOverridable is transport.FRAG_FLAG_FIRST.overridable.
const FragFlagFirstOverridable = false

// FragFlagLastDefault is transport.FRAG_FLAG_LAST.default.
const FragFlagLastDefault = 2

// FragFlagLastOverridable is transport.FRAG_FLAG_LAST.overridable.
const FragFlagLastOverridable = false

// FragHeaderLenDefault is transport.FRAG_HEADER_LEN.default.
const FragHeaderLenDefault = 4

// FragHeaderLenOverridable is transport.FRAG_HEADER_LEN.overridable.
const FragHeaderLenOverridable = false

// FragMaxFragmentsDefault is transport.FRAG_MAX_FRAGMENTS.default.
const FragMaxFragmentsDefault = 65536

// FragMaxFragmentsOverridable is transport.FRAG_MAX_FRAGMENTS.overridable.
const FragMaxFragmentsOverridable = false

// FragReassemblyMaxBytesDefault is transport.FRAG_REASSEMBLY_MAX_BYTES.default.
const FragReassemblyMaxBytesDefault = 2097210

// FragReassemblyMaxBytesOverridable is transport.FRAG_REASSEMBLY_MAX_BYTES.overridable.
const FragReassemblyMaxBytesOverridable = false

// FragTotalPrefixLenDefault is transport.FRAG_TOTAL_PREFIX_LEN.default.
const FragTotalPrefixLenDefault = 4

// FragTotalPrefixLenOverridable is transport.FRAG_TOTAL_PREFIX_LEN.overridable.
const FragTotalPrefixLenOverridable = false

// LANChunkPayloadBytesDefault is transport.LAN_CHUNK_PAYLOAD_BYTES.default.
const LANChunkPayloadBytesDefault = 65536

// LANChunkPayloadBytesOverridable is transport.LAN_CHUNK_PAYLOAD_BYTES.overridable.
const LANChunkPayloadBytesOverridable = false

// QRBootstrapMaxBytesDefault is transport.QR_BOOTSTRAP_MAX_BYTES.default.
const QRBootstrapMaxBytesDefault = 1228

// QRBootstrapMaxBytesOverridable is transport.QR_BOOTSTRAP_MAX_BYTES.overridable.
const QRBootstrapMaxBytesOverridable = false

// ReassemblyTimeoutSecBLE is transport.REASSEMBLY_TIMEOUT_SEC.ble.
const ReassemblyTimeoutSecBLE = 120

// ReassemblyTimeoutSecBLEMax is transport.REASSEMBLY_TIMEOUT_SEC.ble_max.
const ReassemblyTimeoutSecBLEMax = 300

// ReassemblyTimeoutSecBLEMin is transport.REASSEMBLY_TIMEOUT_SEC.ble_min.
const ReassemblyTimeoutSecBLEMin = 60

// ReassemblyTimeoutSecLAN is transport.REASSEMBLY_TIMEOUT_SEC.lan.
const ReassemblyTimeoutSecLAN = 30

// ReassemblyTimeoutSecLANMax is transport.REASSEMBLY_TIMEOUT_SEC.lan_max.
const ReassemblyTimeoutSecLANMax = 120

// ReassemblyTimeoutSecLANMin is transport.REASSEMBLY_TIMEOUT_SEC.lan_min.
const ReassemblyTimeoutSecLANMin = 15

// ReassemblyTimeoutSecOverridable is transport.REASSEMBLY_TIMEOUT_SEC.overridable.
const ReassemblyTimeoutSecOverridable = true

// RetryBackoffBaseMS is transport.RETRY_BACKOFF.base_ms.
const RetryBackoffBaseMS = 1000

// RetryBackoffCapMS is transport.RETRY_BACKOFF.cap_ms.
const RetryBackoffCapMS = 60000

// RetryBackoffFactor is transport.RETRY_BACKOFF.factor.
const RetryBackoffFactor = 2

// RetryBackoffJitter is transport.RETRY_BACKOFF.jitter.
const RetryBackoffJitter = true

// RetryBackoffOverridable is transport.RETRY_BACKOFF.overridable.
const RetryBackoffOverridable = false

// RetryBudgetPerPathDefault is transport.RETRY_BUDGET_PER_PATH.default.
const RetryBudgetPerPathDefault = 5

// RetryBudgetPerPathMax is transport.RETRY_BUDGET_PER_PATH.max.
const RetryBudgetPerPathMax = 8

// RetryBudgetPerPathMin is transport.RETRY_BUDGET_PER_PATH.min.
const RetryBudgetPerPathMin = 3

// RetryBudgetPerPathOverridable is transport.RETRY_BUDGET_PER_PATH.overridable.
const RetryBudgetPerPathOverridable = true

// Media

// FileMaxMBDefault is media.FILE_MAX_MB.default.
const FileMaxMBDefault = 25

// FileMaxMBMax is media.FILE_MAX_MB.max.
const FileMaxMBMax = 100

// FileMaxMBMin is media.FILE_MAX_MB.min.
const FileMaxMBMin = 5

// FileMaxMBOverridable is media.FILE_MAX_MB.overridable.
const FileMaxMBOverridable = true

// FullImageMaxMBDefault is media.FULL_IMAGE_MAX_MB.default.
const FullImageMaxMBDefault = 2

// FullImageMaxMBMax is media.FULL_IMAGE_MAX_MB.max.
const FullImageMaxMBMax = 10

// FullImageMaxMBMin is media.FULL_IMAGE_MAX_MB.min.
const FullImageMaxMBMin = 1

// FullImageMaxMBOverridable is media.FULL_IMAGE_MAX_MB.overridable.
const FullImageMaxMBOverridable = true

// MediaCacheQuotaMBDefault is media.MEDIA_CACHE_QUOTA_MB.default.
const MediaCacheQuotaMBDefault = 512

// MediaCacheQuotaMBMax is media.MEDIA_CACHE_QUOTA_MB.max.
const MediaCacheQuotaMBMax = 2048

// MediaCacheQuotaMBMin is media.MEDIA_CACHE_QUOTA_MB.min.
const MediaCacheQuotaMBMin = 128

// MediaCacheQuotaMBOverridable is media.MEDIA_CACHE_QUOTA_MB.overridable.
const MediaCacheQuotaMBOverridable = true

// MediaCacheQuotaMBTTLDays is media.MEDIA_CACHE_QUOTA_MB.ttl_days.
const MediaCacheQuotaMBTTLDays = 30

// MediaCacheQuotaMBTTLDaysMax is media.MEDIA_CACHE_QUOTA_MB.ttl_days_max.
const MediaCacheQuotaMBTTLDaysMax = 90

// MediaCacheQuotaMBTTLDaysMin is media.MEDIA_CACHE_QUOTA_MB.ttl_days_min.
const MediaCacheQuotaMBTTLDaysMin = 7

// PreviewMaxKBDefault is media.PREVIEW_MAX_KB.default.
const PreviewMaxKBDefault = 64

// PreviewMaxKBMax is media.PREVIEW_MAX_KB.max.
const PreviewMaxKBMax = 128

// PreviewMaxKBMin is media.PREVIEW_MAX_KB.min.
const PreviewMaxKBMin = 32

// PreviewMaxKBOverridable is media.PREVIEW_MAX_KB.overridable.
const PreviewMaxKBOverridable = true

// ThumbnailMaxKBDefault is media.THUMBNAIL_MAX_KB.default.
const ThumbnailMaxKBDefault = 12

// ThumbnailMaxKBMax is media.THUMBNAIL_MAX_KB.max.
const ThumbnailMaxKBMax = 16

// ThumbnailMaxKBMin is media.THUMBNAIL_MAX_KB.min.
const ThumbnailMaxKBMin = 8

// ThumbnailMaxKBOverridable is media.THUMBNAIL_MAX_KB.overridable.
const ThumbnailMaxKBOverridable = true

// VoiceNoteCodec is media.VOICE_NOTE.codec.
const VoiceNoteCodec = "AAC-LC mono M4A"

// VoiceNoteMaxKB is media.VOICE_NOTE.max_kb.
const VoiceNoteMaxKB = 600

// VoiceNoteMaxSec is media.VOICE_NOTE.max_sec.
const VoiceNoteMaxSec = 120

// VoiceNoteOverridable is media.VOICE_NOTE.overridable.
const VoiceNoteOverridable = true

// VoiceNoteSampleRateHZ is media.VOICE_NOTE.sample_rate_hz.
const VoiceNoteSampleRateHZ = 24000

// VoiceNoteSecMax is media.VOICE_NOTE.sec_max.
const VoiceNoteSecMax = 180

// VoiceNoteSecMin is media.VOICE_NOTE.sec_min.
const VoiceNoteSecMin = 30

// Config venue ops

// ConfigPackageMaxKBDefault is config_venue_ops.CONFIG_PACKAGE_MAX_KB.default.
const ConfigPackageMaxKBDefault = 512

// ConfigPackageMaxKBOverridable is config_venue_ops.CONFIG_PACKAGE_MAX_KB.overridable.
const ConfigPackageMaxKBOverridable = false

// ConfigRefreshIntervalHoursDefault is config_venue_ops.CONFIG_REFRESH_INTERVAL_HOURS.default.
const ConfigRefreshIntervalHoursDefault = 6

// ConfigRefreshIntervalHoursJitter is config_venue_ops.CONFIG_REFRESH_INTERVAL_HOURS.jitter.
const ConfigRefreshIntervalHoursJitter = true

// ConfigRefreshIntervalHoursMax is config_venue_ops.CONFIG_REFRESH_INTERVAL_HOURS.max.
const ConfigRefreshIntervalHoursMax = 24

// ConfigRefreshIntervalHoursMin is config_venue_ops.CONFIG_REFRESH_INTERVAL_HOURS.min.
const ConfigRefreshIntervalHoursMin = 1

// ConfigRefreshIntervalHoursOverridable is config_venue_ops.CONFIG_REFRESH_INTERVAL_HOURS.overridable.
const ConfigRefreshIntervalHoursOverridable = true

// SponsorAssetMaxKBCacheCount is config_venue_ops.SPONSOR_ASSET_MAX_KB.cache_count.
const SponsorAssetMaxKBCacheCount = 8

// SponsorAssetMaxKBCacheCountMax is config_venue_ops.SPONSOR_ASSET_MAX_KB.cache_count_max.
const SponsorAssetMaxKBCacheCountMax = 16

// SponsorAssetMaxKBCacheCountMin is config_venue_ops.SPONSOR_ASSET_MAX_KB.cache_count_min.
const SponsorAssetMaxKBCacheCountMin = 4

// SponsorAssetMaxKBDefault is config_venue_ops.SPONSOR_ASSET_MAX_KB.default.
const SponsorAssetMaxKBDefault = 300

// SponsorAssetMaxKBMax is config_venue_ops.SPONSOR_ASSET_MAX_KB.max.
const SponsorAssetMaxKBMax = 512

// SponsorAssetMaxKBMin is config_venue_ops.SPONSOR_ASSET_MAX_KB.min.
const SponsorAssetMaxKBMin = 100

// SponsorAssetMaxKBOverridable is config_venue_ops.SPONSOR_ASSET_MAX_KB.overridable.
const SponsorAssetMaxKBOverridable = true

// SupportRingBufferMBDefault is config_venue_ops.SUPPORT_RING_BUFFER_MB.default.
const SupportRingBufferMBDefault = 4

// SupportRingBufferMBMax is config_venue_ops.SUPPORT_RING_BUFFER_MB.max.
const SupportRingBufferMBMax = 16

// SupportRingBufferMBMin is config_venue_ops.SUPPORT_RING_BUFFER_MB.min.
const SupportRingBufferMBMin = 1

// SupportRingBufferMBOverridable is config_venue_ops.SUPPORT_RING_BUFFER_MB.overridable.
const SupportRingBufferMBOverridable = true

// VenueRoomPostRatePerMinDefault is config_venue_ops.VENUE_ROOM_POST_RATE_PER_MIN.default.
const VenueRoomPostRatePerMinDefault = 10

// VenueRoomPostRatePerMinMax is config_venue_ops.VENUE_ROOM_POST_RATE_PER_MIN.max.
const VenueRoomPostRatePerMinMax = 30

// VenueRoomPostRatePerMinMin is config_venue_ops.VENUE_ROOM_POST_RATE_PER_MIN.min.
const VenueRoomPostRatePerMinMin = 3

// VenueRoomPostRatePerMinOverridable is config_venue_ops.VENUE_ROOM_POST_RATE_PER_MIN.overridable.
const VenueRoomPostRatePerMinOverridable = true

// VoucherNonceCacheCount is config_venue_ops.VOUCHER_NONCE_CACHE.count.
const VoucherNonceCacheCount = 5000

// VoucherNonceCacheCountMax is config_venue_ops.VOUCHER_NONCE_CACHE.count_max.
const VoucherNonceCacheCountMax = 20000

// VoucherNonceCacheCountMin is config_venue_ops.VOUCHER_NONCE_CACHE.count_min.
const VoucherNonceCacheCountMin = 1000

// VoucherNonceCacheDays is config_venue_ops.VOUCHER_NONCE_CACHE.days.
const VoucherNonceCacheDays = 90

// VoucherNonceCacheDaysMax is config_venue_ops.VOUCHER_NONCE_CACHE.days_max.
const VoucherNonceCacheDaysMax = 365

// VoucherNonceCacheDaysMin is config_venue_ops.VOUCHER_NONCE_CACHE.days_min.
const VoucherNonceCacheDaysMin = 30

// VoucherNonceCacheOverridable is config_venue_ops.VOUCHER_NONCE_CACHE.overridable.
const VoucherNonceCacheOverridable = true

// Resource budgets

// ActiveVenueBatteryMaxPCT30minBudget is resource_budgets.ACTIVE_VENUE_BATTERY_MAX_PCT_30MIN.budget.
const ActiveVenueBatteryMaxPCT30minBudget = true

// ActiveVenueBatteryMaxPCT30minCalibrated is resource_budgets.ACTIVE_VENUE_BATTERY_MAX_PCT_30MIN.calibrated.
const ActiveVenueBatteryMaxPCT30minCalibrated = true

// ActiveVenueBatteryMaxPCT30minDefault is resource_budgets.ACTIVE_VENUE_BATTERY_MAX_PCT_30MIN.default.
const ActiveVenueBatteryMaxPCT30minDefault = 6

// ActiveVenueBatteryMaxPCT30minOverridable is resource_budgets.ACTIVE_VENUE_BATTERY_MAX_PCT_30MIN.overridable.
const ActiveVenueBatteryMaxPCT30minOverridable = false

// AndroidDownloadMaxMBBudget is resource_budgets.ANDROID_DOWNLOAD_MAX_MB.budget.
const AndroidDownloadMaxMBBudget = true

// AndroidDownloadMaxMBDefault is resource_budgets.ANDROID_DOWNLOAD_MAX_MB.default.
const AndroidDownloadMaxMBDefault = 40

// AndroidDownloadMaxMBOverridable is resource_budgets.ANDROID_DOWNLOAD_MAX_MB.overridable.
const AndroidDownloadMaxMBOverridable = false

// ColdLaunchP95MSDefault is resource_budgets.COLD_LAUNCH_P95_MS.default.
const ColdLaunchP95MSDefault = 2000

// ColdLaunchP95MSOverridable is resource_budgets.COLD_LAUNCH_P95_MS.overridable.
const ColdLaunchP95MSOverridable = false

// IdleBackgroundBatteryPCTMax is resource_budgets.IDLE_BACKGROUND.battery_pct_max.
const IdleBackgroundBatteryPCTMax = 0

// IdleBackgroundOverridable is resource_budgets.IDLE_BACKGROUND.overridable.
const IdleBackgroundOverridable = false

// IdleBackgroundWakeLocksPermitted is resource_budgets.IDLE_BACKGROUND.wake_locks_permitted.
const IdleBackgroundWakeLocksPermitted = false

// IOSDownloadMaxMBBudget is resource_budgets.IOS_DOWNLOAD_MAX_MB.budget.
const IOSDownloadMaxMBBudget = true

// IOSDownloadMaxMBDefault is resource_budgets.IOS_DOWNLOAD_MAX_MB.default.
const IOSDownloadMaxMBDefault = 60

// IOSDownloadMaxMBOverridable is resource_budgets.IOS_DOWNLOAD_MAX_MB.overridable.
const IOSDownloadMaxMBOverridable = false

// SteadyStateRAMMaxMBBudget is resource_budgets.STEADY_STATE_RAM_MAX_MB.budget.
const SteadyStateRAMMaxMBBudget = true

// SteadyStateRAMMaxMBDefault is resource_budgets.STEADY_STATE_RAM_MAX_MB.default.
const SteadyStateRAMMaxMBDefault = 220

// SteadyStateRAMMaxMBOverridable is resource_budgets.STEADY_STATE_RAM_MAX_MB.overridable.
const SteadyStateRAMMaxMBOverridable = false

// Onboarding safety

// AdultAgeMinimumOverridable is onboarding_safety.ADULT_AGE_MINIMUM.overridable.
const AdultAgeMinimumOverridable = false

// AdultAgeMinimumYears is onboarding_safety.ADULT_AGE_MINIMUM.years.
const AdultAgeMinimumYears = 18

// VisibleNearbyDurationSecDefault is onboarding_safety.VISIBLE_NEARBY_DURATION_SEC.default.
const VisibleNearbyDurationSecDefault = 3600

// VisibleNearbyDurationSecMax is onboarding_safety.VISIBLE_NEARBY_DURATION_SEC.max.
const VisibleNearbyDurationSecMax = 28800

// VisibleNearbyDurationSecMin is onboarding_safety.VISIBLE_NEARBY_DURATION_SEC.min.
const VisibleNearbyDurationSecMin = 900

// VisibleNearbyDurationSecOverridable is onboarding_safety.VISIBLE_NEARBY_DURATION_SEC.overridable.
const VisibleNearbyDurationSecOverridable = true

// MatchTokenColorsHex is crypto_protocol.MATCH_TOKEN_COLORS.hex. It is a var because Go has no array constants; treat it as read-only.
var MatchTokenColorsHex = [...]string{"#FF6F61", "#FFB300", "#43A047", "#00897B", "#1E88E5", "#8E24AA", "#D81B60", "#546E7A"}

// MatchTokenColorsNames is crypto_protocol.MATCH_TOKEN_COLORS.names. It is a var because Go has no array constants; treat it as read-only.
var MatchTokenColorsNames = [...]string{"coral", "amber", "leaf", "teal", "sky", "violet", "rose", "slate"}

// SafetyNumberEmojiAlphabetGlyphs is crypto_protocol.SAFETY_NUMBER_EMOJI_ALPHABET.glyphs. It is a var because Go has no array constants; treat it as read-only.
var SafetyNumberEmojiAlphabetGlyphs = [...]string{"🐶", "🐱", "🐴", "🐮", "🐷", "🐸", "🐵", "🐔", "🐧", "🐢", "🐝", "🦋", "🐙", "🐬", "🐘", "🐊", "🍎", "🍌", "🍇", "🍓", "🍉", "🍍", "🌽", "🍄", "🍞", "🍕", "🎂", "🍩", "☕", "🍦", "🌵", "🌲", "🌻", "🌹", "🍁", "⭐", "🌈", "🔥", "💧", "⚡", "🔑", "🔒", "🎈", "🎁", "⚽", "🎸", "🎺", "🎯", "🚀", "⚓", "🚲", "🚂", "⏰", "💡", "🔔", "🎧", "📷", "🔭", "🎨", "🎪", "💙", "🎵", "🏆", "👑"}

// VoiceNoteBitrateKbps is media.VOICE_NOTE.bitrate_kbps. It is a var because Go has no array constants; treat it as read-only.
var VoiceNoteBitrateKbps = [...]int{24, 32}
