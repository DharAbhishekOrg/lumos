// Lumos - protocol/gen/go/lumoserrors / Generated stable protocol error codes.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CONST-001, TRS-CONST-002, TRS-CONST-005, TRS-REP-002
// ADRs: ADR-002, ADR-004
// Threading: any
// GENERATED - DO NOT EDIT. Generator: tools/constantsgen
// Source register: protocol/constants/lumos_constants.json
// Baseline: TRS v1.1 Sec. 23 / EDP v1.0 Sec. 4 (ADR-004)
// constants_hash: sha256:568eb8489beb092351d802a9f7ab88eb6eecc62376b39f75eb2e22361d48d37b
// Source schema: protocol/schemas/error_codes.schema.json

// Package lumoserrors holds the stable machine-readable error codes of
// TRS-PROTO-013. Codes are append-only; renumbering is a breaking change.
package lumoserrors

const (
	// ERR_FRAME_BAD_MAGIC (1, framing): Frame magic did not match framing.FRAME_MAGIC.
	ErrFrameBadMagic = "ERR_FRAME_BAD_MAGIC"
	// ERR_FRAME_UNSUPPORTED_VERSION (2, framing): Protocol major version differs from this build's.
	ErrFrameUnsupportedVersion = "ERR_FRAME_UNSUPPORTED_VERSION"
	// ERR_FRAME_UNKNOWN_TYPE (3, framing): Unknown message class on a frame marked critical.
	ErrFrameUnknownType = "ERR_FRAME_UNKNOWN_TYPE"
	// ERR_FRAME_BAD_HEADER_LENGTH (4, framing): header_length disagreed with the fixed length for this protocol version.
	ErrFrameBadHeaderLength = "ERR_FRAME_BAD_HEADER_LENGTH"
	// ERR_FRAME_TRUNCATED (5, framing): Fewer bytes available than header_length or payload_length require.
	ErrFrameTruncated = "ERR_FRAME_TRUNCATED"
	// ERR_FRAME_RESERVED_FLAG (6, framing): A reserved flag bit was set on a frame marked critical.
	ErrFrameReservedFlag = "ERR_FRAME_RESERVED_FLAG"
	// ERR_FRAME_RESERVED_FIELD (7, framing): The reserved header byte was non-zero on a frame marked critical.
	ErrFrameReservedField = "ERR_FRAME_RESERVED_FIELD"
	// ERR_FRAME_PAYLOAD_TOO_LARGE (8, framing): payload_length exceeded the maximum for its message class.
	ErrFramePayloadTooLarge = "ERR_FRAME_PAYLOAD_TOO_LARGE"
	// ERR_PAYLOAD_DECOMPRESSION_BOMB (20, payload): Declared or actual decompressed size exceeded the class maximum.
	ErrPayloadDecompressionBomb = "ERR_PAYLOAD_DECOMPRESSION_BOMB"
	// ERR_PAYLOAD_MALFORMED (21, payload): Payload did not decode against its class schema.
	ErrPayloadMalformed = "ERR_PAYLOAD_MALFORMED"
	// ERR_TEXT_UNKNOWN_EVENT_KIND (22, payload): Unknown inner event_kind on a critical TEXT_MESSAGE (AMD-005).
	ErrTextUnknownEventKind = "ERR_TEXT_UNKNOWN_EVENT_KIND"
	// ERR_TEXT_MISSING_TARGET (23, payload): An edit, delete or reaction arrived without target_message_id.
	ErrTextMissingTarget = "ERR_TEXT_MISSING_TARGET"
	// ERR_CRYPTO_AEAD_AUTH_FAILED (40, crypto): AEAD authentication failed; header-as-AAD mismatch or tampering.
	ErrCryptoAeadAuthFailed = "ERR_CRYPTO_AEAD_AUTH_FAILED"
	// ERR_CRYPTO_MAX_SKIP_EXCEEDED (41, crypto): Ratchet skip exceeded sessions_remote_text.MAX_SKIP_PER_CHAIN.
	ErrCryptoMaxSkipExceeded = "ERR_CRYPTO_MAX_SKIP_EXCEEDED"
	// ERR_CRYPTO_SKIPPED_KEY_CAP (42, crypto): Stored skipped keys would exceed SKIPPED_KEYS_PER_SESSION_CAP.
	ErrCryptoSkippedKeyCap = "ERR_CRYPTO_SKIPPED_KEY_CAP"
	// ERR_CRYPTO_PREKEY_SIGNATURE_INVALID (44, crypto): X3DH prekey bundle signature failed verification (identity-DH binding or signed prekey).
	ErrCryptoPrekeySignatureInvalid = "ERR_CRYPTO_PREKEY_SIGNATURE_INVALID"
	// ERR_CRYPTO_REPLAY (43, crypto): message_id already seen within the dedup window.
	ErrCryptoReplay = "ERR_CRYPTO_REPLAY"
	// ERR_MESH_HOP_VIOLATION (60, mesh): A relay attempted to raise hop_limit or TTL (TRS-PROTO-012).
	ErrMeshHopViolation = "ERR_MESH_HOP_VIOLATION"
	// ERR_MESH_TTL_EXPIRED (61, mesh): Frame TTL expired before delivery.
	ErrMeshTTLExpired = "ERR_MESH_TTL_EXPIRED"
	// ERR_TRANSPORT_LOCAL_ONLY_VIOLATION (62, mesh): A frame flagged local-only was offered to the internet relay (SRS-TRN-009).
	ErrTransportLocalOnlyViolation = "ERR_TRANSPORT_LOCAL_ONLY_VIOLATION"
	// ERR_MEDIA_DIGEST_MISMATCH (100, media): Reassembled object did not match the SHA-256 digest declared in OBJECT_META.
	ErrMediaDigestMismatch = "ERR_MEDIA_DIGEST_MISMATCH"
	// ERR_MEDIA_OBJECT_KEY_UNWRAP_FAILED (101, media): The object key could not be unwrapped from the active match session.
	ErrMediaObjectKeyUnwrapFailed = "ERR_MEDIA_OBJECT_KEY_UNWRAP_FAILED"
	// ERR_MEDIA_CHUNK_OUT_OF_RANGE (102, media): A chunk index fell outside the plan declared in OBJECT_META.
	ErrMediaChunkOutOfRange = "ERR_MEDIA_CHUNK_OUT_OF_RANGE"
	// ERR_ADV_AUTH_TAG_MISMATCH (110, adv): Advertisement authTag did not match the expected HMAC prefix for the epoch; the advertisement is discarded before connection.
	ErrAdvAuthTagMismatch = "ERR_ADV_AUTH_TAG_MISMATCH"
	// ERR_CONFIG_SIGNATURE_INVALID (80, config): Signed configuration or voucher failed signature verification.
	ErrConfigSignatureInvalid = "ERR_CONFIG_SIGNATURE_INVALID"
	// ERR_CONFIG_SCHEMA_INVALID (81, config): Configuration failed schema validation, including an out-of-bounds override (TRS-CONST-002).
	ErrConfigSchemaInvalid = "ERR_CONFIG_SCHEMA_INVALID"
	// ERR_CONFIG_ROLLBACK (82, config): Configuration version was older than the active one.
	ErrConfigRollback = "ERR_CONFIG_ROLLBACK"
	// ERR_CONFIG_EXPIRED (83, config): Configuration or voucher validity window has passed.
	ErrConfigExpired = "ERR_CONFIG_EXPIRED"
	// ERR_CONFIG_WRONG_ENVIRONMENT (84, config): Package environment did not match this build's.
	ErrConfigWrongEnvironment = "ERR_CONFIG_WRONG_ENVIRONMENT"
	// ERR_CONFIG_CONSTANTS_HASH_MISMATCH (85, config): Package constants_hash did not match this build's register (TRS-CONST-005).
	ErrConfigConstantsHashMismatch = "ERR_CONFIG_CONSTANTS_HASH_MISMATCH"
	// ERR_VOUCHER_NONCE_REPLAY (86, config): Voucher nonce was already redeemed within VOUCHER_NONCE_CACHE.
	ErrVoucherNonceReplay = "ERR_VOUCHER_NONCE_REPLAY"
	// ERR_VOUCHER_REVOKED (87, config): Voucher appears in the revocation set.
	ErrVoucherRevoked = "ERR_VOUCHER_REVOKED"
)

// Numeric maps each stable code to its registry number.
var Numeric = map[string]int{
	ErrFrameBadMagic:                1,
	ErrFrameUnsupportedVersion:      2,
	ErrFrameUnknownType:             3,
	ErrFrameBadHeaderLength:         4,
	ErrFrameTruncated:               5,
	ErrFrameReservedFlag:            6,
	ErrFrameReservedField:           7,
	ErrFramePayloadTooLarge:         8,
	ErrPayloadDecompressionBomb:     20,
	ErrPayloadMalformed:             21,
	ErrTextUnknownEventKind:         22,
	ErrTextMissingTarget:            23,
	ErrCryptoAeadAuthFailed:         40,
	ErrCryptoMaxSkipExceeded:        41,
	ErrCryptoSkippedKeyCap:          42,
	ErrCryptoPrekeySignatureInvalid: 44,
	ErrCryptoReplay:                 43,
	ErrMeshHopViolation:             60,
	ErrMeshTTLExpired:               61,
	ErrTransportLocalOnlyViolation:  62,
	ErrMediaDigestMismatch:          100,
	ErrMediaObjectKeyUnwrapFailed:   101,
	ErrMediaChunkOutOfRange:         102,
	ErrAdvAuthTagMismatch:           110,
	ErrConfigSignatureInvalid:       80,
	ErrConfigSchemaInvalid:          81,
	ErrConfigRollback:               82,
	ErrConfigExpired:                83,
	ErrConfigWrongEnvironment:       84,
	ErrConfigConstantsHashMismatch:  85,
	ErrVoucherNonceReplay:           86,
	ErrVoucherRevoked:               87,
}

// Known reports whether code is in the stable registry.
func Known(code string) bool {
	_, ok := Numeric[code]
	return ok
}
