// Lumos - protocol/gen/go/lumosconstants / Generated per-class maximum payload sizes.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CONST-001, TRS-CONST-002, TRS-CONST-005, TRS-REP-002
// ADRs: ADR-002, ADR-004
// Threading: any
// GENERATED - DO NOT EDIT. Generator: tools/constantsgen
// Source register: protocol/constants/lumos_constants.json
// Baseline: TRS v1.1 Sec. 23 / EDP v1.0 Sec. 4 (ADR-004)
// constants_hash: sha256:b7cebb50a545ddd203ddb2fdc86ccac1e0a4ecd9c3ed93355803516d7c1a7311
// Source schema: protocol/schemas/message_payloads.schema.json

package lumosconstants

// messageClassMaxPayload resolves each class's max_payload_ref through the
// constants register (TRS-PROTO-007). Values are bytes.
var messageClassMaxPayload = map[MessageClass]int64{
	MessageClassHello:            1228,    // transport.QR_BOOTSTRAP_MAX_BYTES
	MessageClassHelloAck:         1228,    // transport.QR_BOOTSTRAP_MAX_BYTES
	MessageClassInterestRequest:  1228,    // transport.QR_BOOTSTRAP_MAX_BYTES
	MessageClassInterestResponse: 1228,    // transport.QR_BOOTSTRAP_MAX_BYTES
	MessageClassProfilePreview:   65536,   // media.PREVIEW_MAX_KB
	MessageClassProfileFull:      2097152, // media.FULL_IMAGE_MAX_MB
	MessageClassRelayRoute:       1228,    // transport.QR_BOOTSTRAP_MAX_BYTES
	MessageClassTextMessage:      32768,   // sessions_remote_text.RELAY_TEXT_ENVELOPE_MAX_BYTES
	MessageClassDeliveryReceipt:  1228,    // transport.QR_BOOTSTRAP_MAX_BYTES
	MessageClassReadReceipt:      1228,    // transport.QR_BOOTSTRAP_MAX_BYTES
	MessageClassTyping:           1228,    // transport.QR_BOOTSTRAP_MAX_BYTES
	MessageClassObjectMeta:       1228,    // transport.QR_BOOTSTRAP_MAX_BYTES
	MessageClassObjectChunk:      65536,   // transport.LAN_CHUNK_PAYLOAD_BYTES
	MessageClassObjectAck:        1228,    // transport.QR_BOOTSTRAP_MAX_BYTES
	MessageClassObjectCancel:     1228,    // transport.QR_BOOTSTRAP_MAX_BYTES
	MessageClassVoiceMeta:        1228,    // transport.QR_BOOTSTRAP_MAX_BYTES
	MessageClassMeshRelay:        32768,   // sessions_remote_text.RELAY_TEXT_ENVELOPE_MAX_BYTES
	MessageClassStoreForward:     32768,   // sessions_remote_text.RELAY_TEXT_ENVELOPE_MAX_BYTES
	MessageClassVenuePackageHint: 1228,    // transport.QR_BOOTSTRAP_MAX_BYTES
	MessageClassVenueRoomEvent:   1228,    // transport.QR_BOOTSTRAP_MAX_BYTES
	MessageClassVoucherToken:     1228,    // transport.QR_BOOTSTRAP_MAX_BYTES
	MessageClassHeartbeat:        1228,    // transport.QR_BOOTSTRAP_MAX_BYTES
	MessageClassError:            1228,    // transport.QR_BOOTSTRAP_MAX_BYTES
}

// MaxPayloadBytes returns the maximum ciphertext payload for a message class.
// The second result is false for a class this protocol version does not define;
// callers apply TRS-PROTO-006 handling rather than assuming a bound.
func MaxPayloadBytes(mc MessageClass) (int64, bool) {
	max, ok := messageClassMaxPayload[mc]
	return max, ok
}
