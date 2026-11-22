// Lumos - protocol/gen/go/lumosconstants / Generated canonical message-class enums.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CONST-001, TRS-CONST-002, TRS-CONST-005, TRS-REP-002
// ADRs: ADR-002, ADR-004
// Threading: any
// GENERATED - DO NOT EDIT. Generator: tools/constantsgen
// Source register: protocol/constants/lumos_constants.json
// Baseline: TRS v1.1 Sec. 23 / EDP v1.0 Sec. 4 (ADR-004)
// constants_hash: sha256:b7cebb50a545ddd203ddb2fdc86ccac1e0a4ecd9c3ed93355803516d7c1a7311

package lumosconstants

import "fmt"

// MessageClass is a canonical Lumos message class (TRS Sec. 9.2).
type MessageClass uint8

const (
	// MessageClassHello: Version/capability/session bootstrap
	MessageClassHello MessageClass = 0x01
	// MessageClassHelloAck: Capability and session acknowledgement
	MessageClassHelloAck MessageClass = 0x02
	// MessageClassInterestRequest: Consent-stage request payload
	MessageClassInterestRequest MessageClass = 0x03
	// MessageClassInterestResponse: Accept/decline/ignore-safe response
	MessageClassInterestResponse MessageClass = 0x04
	// MessageClassProfilePreview: Approved preview fields
	MessageClassProfilePreview MessageClass = 0x05
	// MessageClassProfileFull: Approved full profile after match
	MessageClassProfileFull MessageClass = 0x06
	// MessageClassRelayRoute: Post-match, nearby-only: this device's relay routing id and the send capability it minted for the peer (CR-005)
	MessageClassRelayRoute MessageClass = 0x07
	// MessageClassTextMessage: Ratcheted private text event carrying the inner event envelope (AMD-005)
	MessageClassTextMessage MessageClass = 0x10
	// MessageClassDeliveryReceipt: Delivered state
	MessageClassDeliveryReceipt MessageClass = 0x11
	// MessageClassReadReceipt: Optional read state
	MessageClassReadReceipt MessageClass = 0x12
	// MessageClassTyping: Optional ephemeral state
	MessageClassTyping MessageClass = 0x13
	// MessageClassObjectMeta: Media/file metadata and digest
	MessageClassObjectMeta MessageClass = 0x20
	// MessageClassObjectChunk: Encrypted object fragment
	MessageClassObjectChunk MessageClass = 0x21
	// MessageClassObjectAck: Selective acknowledgement bitmap/ranges
	MessageClassObjectAck MessageClass = 0x22
	// MessageClassObjectCancel: Cancellation/cleanup
	MessageClassObjectCancel MessageClass = 0x23
	// MessageClassVoiceMeta: Voice-note codec/duration metadata
	MessageClassVoiceMeta MessageClass = 0x24
	// MessageClassMeshRelay: Opaque relay wrapper
	MessageClassMeshRelay MessageClass = 0x30
	// MessageClassStoreForward: Short-TTL opaque mailbox frame
	MessageClassStoreForward MessageClass = 0x31
	// MessageClassVenuePackageHint: Signed venue package reference
	MessageClassVenuePackageHint MessageClass = 0x40
	// MessageClassVenueRoomEvent: Moderated graphical room event
	MessageClassVenueRoomEvent MessageClass = 0x41
	// MessageClassVoucherToken: Signed voucher/bootstrap token
	MessageClassVoucherToken MessageClass = 0x42
	// MessageClassHeartbeat: Liveness and route health
	MessageClassHeartbeat MessageClass = 0x50
	// MessageClassError: Stable machine-readable failure
	MessageClassError MessageClass = 0x7F
)

// MessageClassNames maps each known MessageClass to its canonical name.
var MessageClassNames = map[MessageClass]string{
	MessageClassHello:            "HELLO",
	MessageClassHelloAck:         "HELLO_ACK",
	MessageClassInterestRequest:  "INTEREST_REQUEST",
	MessageClassInterestResponse: "INTEREST_RESPONSE",
	MessageClassProfilePreview:   "PROFILE_PREVIEW",
	MessageClassProfileFull:      "PROFILE_FULL",
	MessageClassRelayRoute:       "RELAY_ROUTE",
	MessageClassTextMessage:      "TEXT_MESSAGE",
	MessageClassDeliveryReceipt:  "DELIVERY_RECEIPT",
	MessageClassReadReceipt:      "READ_RECEIPT",
	MessageClassTyping:           "TYPING",
	MessageClassObjectMeta:       "OBJECT_META",
	MessageClassObjectChunk:      "OBJECT_CHUNK",
	MessageClassObjectAck:        "OBJECT_ACK",
	MessageClassObjectCancel:     "OBJECT_CANCEL",
	MessageClassVoiceMeta:        "VOICE_META",
	MessageClassMeshRelay:        "MESH_RELAY",
	MessageClassStoreForward:     "STORE_FORWARD",
	MessageClassVenuePackageHint: "VENUE_PACKAGE_HINT",
	MessageClassVenueRoomEvent:   "VENUE_ROOM_EVENT",
	MessageClassVoucherToken:     "VOUCHER_TOKEN",
	MessageClassHeartbeat:        "HEARTBEAT",
	MessageClassError:            "ERROR",
}

// String returns the canonical name, or an explicit unknown form. It never
// panics: unknown values are a protocol condition handled per TRS-PROTO-006.
func (v MessageClass) String() string {
	if n, ok := MessageClassNames[v]; ok {
		return n
	}
	return fmt.Sprintf("UNKNOWN_MESSAGECLASS(0x%02X)", uint8(v))
}

// Known reports whether v is a MessageClass defined by this protocol version.
func (v MessageClass) Known() bool {
	_, ok := MessageClassNames[v]
	return ok
}

// TextEventKind is a inner event kind of a TEXT_MESSAGE envelope (AMD-005).
type TextEventKind uint8

const (
	// TextEventKindText: Original text event
	TextEventKindText TextEventKind = 0x00
	// TextEventKindEdit: Authenticated edit over the original message
	TextEventKindEdit TextEventKind = 0x01
	// TextEventKindDeleteForEveryone: Authenticated delete over the original message
	TextEventKindDeleteForEveryone TextEventKind = 0x02
	// TextEventKindReaction: Authenticated reaction over the original message
	TextEventKindReaction TextEventKind = 0x03
)

// TextEventKindNames maps each known TextEventKind to its canonical name.
var TextEventKindNames = map[TextEventKind]string{
	TextEventKindText:              "TEXT",
	TextEventKindEdit:              "EDIT",
	TextEventKindDeleteForEveryone: "DELETE_FOR_EVERYONE",
	TextEventKindReaction:          "REACTION",
}

// String returns the canonical name, or an explicit unknown form. It never
// panics: unknown values are a protocol condition handled per TRS-PROTO-006.
func (v TextEventKind) String() string {
	if n, ok := TextEventKindNames[v]; ok {
		return n
	}
	return fmt.Sprintf("UNKNOWN_TEXTEVENTKIND(0x%02X)", uint8(v))
}

// Known reports whether v is a TextEventKind defined by this protocol version.
func (v TextEventKind) Known() bool {
	_, ok := TextEventKindNames[v]
	return ok
}
