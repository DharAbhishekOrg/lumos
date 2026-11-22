// Lumos - protocol/chat / Delivery and read receipts: sealed in the chat's ratchet, bound to their class.
// Author: dhar.abhishek.email@gmail.com
// Implements: SRS-RMT-005, SRS-RMT-014, SRS-RMT-004, SRS-RMT-009, TRS-PROTO-005, TRS-PROTO-007
// ADRs: ADR-001, ADR-008
// Threading: serial-executor
// Notes: CR-005 (owner-accepted 2026-11-23) keeps the TRS Sec. 9.2 classes:
// DELIVERY_RECEIPT (0x11) and READ_RECEIPT (0x12). A receipt is sealed in the
// same Double Ratchet session as the text it acknowledges and rides the same
// envelope (PREKEY until the peer replies, then NORMAL), so it is end-to-end
// encrypted: the relay sees that an envelope is a receipt and which kind, as
// the owner accepted, and never which messages it acknowledges.
//
// Plaintext, fixed under the TRS Sec. 19 AI delivery rule:
//
//	receipt_id[16] | kind u8 | count u8 | message_id[16] x count
//
// kind is the class code and must equal the class the relay delivered the
// envelope under, and receipt_id must equal the relay's message id - so a
// relay can neither turn "delivered" into "read", nor a receipt into a text,
// nor relabel one, without the open failing (SRS-RMT-009). count runs from 1
// to RECEIPT_IDS_PER_ENVELOPE.max; an encoder sends at most .default. Only
// the recipient's receipt ever moves a message past one tick (owner ruling
// 2026-11-21): this is that receipt.
package chat

import (
	"github.com/dharabhishekorg/lumos/protocol/crypto/ratchet"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
)

// Receipt acknowledges one or more of the peer's messages.
type Receipt struct {
	ID    MessageID
	Kind  lumosconstants.MessageClass
	Acked []MessageID
}

func receiptKind(k lumosconstants.MessageClass) bool {
	return k == lumosconstants.MessageClassDeliveryReceipt || k == lumosconstants.MessageClassReadReceipt
}

// Encode renders receipt_id | kind | count | ids. An encoder sends at most
// RECEIPT_IDS_PER_ENVELOPE.default ids.
func (r Receipt) Encode() ([]byte, error) {
	if !receiptKind(r.Kind) {
		return nil, malformed("not a receipt class")
	}
	if len(r.Acked) == 0 || len(r.Acked) > lumosconstants.ReceiptIDSPerEnvelopeDefault {
		return nil, malformed("receipt id count out of range")
	}
	out := make([]byte, 0, MessageIDSize+2+MessageIDSize*len(r.Acked))
	out = append(out, r.ID[:]...)
	out = append(out, byte(r.Kind), byte(len(r.Acked))) // #nosec G115 -- len is bounded by RECEIPT_IDS_PER_ENVELOPE above
	for _, id := range r.Acked {
		out = append(out, id[:]...)
	}
	return out, nil
}

// DecodeReceipt parses a receipt plaintext. It accepts up to
// RECEIPT_IDS_PER_ENVELOPE.max ids and refuses trailing bytes.
func DecodeReceipt(b []byte) (Receipt, error) {
	if len(b) < MessageIDSize+2 {
		return Receipt{}, malformed("receipt shorter than its header")
	}
	var r Receipt
	copy(r.ID[:], b[:MessageIDSize])
	r.Kind = lumosconstants.MessageClass(b[MessageIDSize])
	if !receiptKind(r.Kind) {
		return Receipt{}, malformed("not a receipt class")
	}
	n := int(b[MessageIDSize+1])
	if n == 0 || n > lumosconstants.ReceiptIDSPerEnvelopeMax {
		return Receipt{}, malformed("receipt id count out of range")
	}
	body := b[MessageIDSize+2:]
	if len(body) != n*MessageIDSize {
		return Receipt{}, malformed("receipt length does not match its count")
	}
	r.Acked = make([]MessageID, n)
	for i := range r.Acked {
		copy(r.Acked[i][:], body[i*MessageIDSize:])
	}
	return r, nil
}

// SealReceipt encrypts one receipt into an envelope, exactly as Seal does a
// text plaintext.
func SealReceipt(st *ratchet.State, r Receipt, prekey *PrekeyFields, now int64) ([]byte, error) {
	pt, err := r.Encode()
	if err != nil {
		return nil, err
	}
	return sealBytes(st, pt, prekey, now)
}

// OpenReceipt decrypts one envelope the relay delivered under outerID and
// outerClass. The authenticated id and kind must match both.
func OpenReceipt(st *ratchet.State, e Envelope, outerID MessageID, outerClass lumosconstants.MessageClass,
	now int64, kp ratchet.KeyProvider) (Receipt, error) {
	pt, err := st.Decrypt(e.Header, e.Ciphertext, now, kp)
	if err != nil {
		return Receipt{}, err
	}
	r, err := DecodeReceipt(pt)
	if err != nil {
		return Receipt{}, err
	}
	if r.ID != outerID {
		return Receipt{}, malformed("authenticated receipt id differs from the delivered id")
	}
	if r.Kind != outerClass {
		return Receipt{}, malformed("authenticated receipt kind differs from the delivered class")
	}
	return r, nil
}
