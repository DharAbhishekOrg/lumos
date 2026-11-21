// Lumos - protocol/chat / The delivery machine's guardrails, walked exhaustively.
// Author: dhar.abhishek.email@gmail.com
// Implements: SRS-RMT-005, SRS-RMT-014, TRS-PAT-002, TRS-TST-001
// ADRs: ADR-002
// Threading: test-only
// Notes: Four walks over the full state x event product: every pair is a
// listed transition or a listed absorption (TRS-PAT-002); only a recipient
// receipt reaches DELIVERED or READ (the EDP guardrail - no transport
// success shows two ticks); nothing ever moves backward once the recipient
// has spoken; and the tick table covers every state with the owner's
// WhatsApp-style mapping.

package chat

import "testing"

// absorbing lists every pair that deliberately changes nothing.
var absorbing = map[State][]Event{
	Queued: {TransportAccepted, TransportRetryable, AttemptsExhausted, UserRetry,
		LocalPathAvailable},
	Sending: {SendStarted, UserRetry, LocalPathUnavailable, LocalPathAvailable},
	Sent: {SendStarted, TransportAccepted, TransportRetryable, AttemptsExhausted,
		UserRetry, LocalPathUnavailable, LocalPathAvailable},
	Delivered: {SendStarted, TransportAccepted, TransportRetryable, AttemptsExhausted,
		DeliveryReceipt, UserRetry, LocalPathUnavailable, LocalPathAvailable},
	Read: {SendStarted, TransportAccepted, TransportRetryable, AttemptsExhausted,
		DeliveryReceipt, ReadReceipt, UserRetry, LocalPathUnavailable, LocalPathAvailable},
	Failed: {SendStarted, TransportAccepted, TransportRetryable, AttemptsExhausted,
		LocalPathUnavailable, LocalPathAvailable},
	LocalOnlyWaiting: {SendStarted, TransportAccepted, TransportRetryable,
		AttemptsExhausted, UserRetry, LocalPathUnavailable},
}

func listedAbsorbing(s State, e Event) bool {
	for _, a := range absorbing[s] {
		if a == e {
			return true
		}
	}
	return false
}

func TestEveryPairIsATransitionOrADeliberateAbsorption(t *testing.T) {
	for _, s := range States() {
		for _, e := range Events() {
			moved := Step(s, e) != s
			listed := listedAbsorbing(s, e)
			if moved && listed {
				t.Errorf("state %d event %d is listed as absorbing but moved", s, e)
			}
			if !moved && !listed {
				t.Errorf("state %d event %d absorbs but is not listed: decide it (TRS-PAT-002)", s, e)
			}
		}
	}
}

func TestOnlyTheRecipientCanProduceDeliveredOrRead(t *testing.T) {
	for _, s := range States() {
		for _, e := range Events() {
			next := Step(s, e)
			reachedReceiptState := (next == Delivered || next == Read) && next != s
			if reachedReceiptState && e != DeliveryReceipt && e != ReadReceipt {
				t.Errorf("state %d event %d reached %d without a recipient receipt: "+
					"never derive delivered from a transport (EDP WP-05 guardrail)", s, e, next)
			}
		}
	}
}

func TestReceiptsNeverMoveAMessageBackward(t *testing.T) {
	for _, s := range States() {
		for _, e := range []Event{DeliveryReceipt, ReadReceipt} {
			if rank[Step(s, e)] < rank[s] {
				t.Errorf("receipt %d moved state %d backward", e, s)
			}
		}
	}
	if Step(Read, DeliveryReceipt) != Read {
		t.Fatal("a late delivery receipt after a read one must leave the message read")
	}
	if Step(Failed, DeliveryReceipt) != Delivered {
		t.Fatal("the recipient's receipt is ground truth even after a local failure")
	}
}

func TestEveryStateHasTheOwnersTick(t *testing.T) {
	want := map[State]Tick{
		Queued: TickClock, Sending: TickClock, Sent: TickSingle, Delivered: TickDouble,
		Read: TickDoubleRead, Failed: TickFailed, LocalOnlyWaiting: TickNearby,
	}
	for _, s := range States() {
		if TickOf(s) != want[s] {
			t.Errorf("state %d draws %q, want %q", s, TickOf(s), want[s])
		}
	}
}

func TestReadReceiptsOffNeverSilencesDeliveryReceipts(t *testing.T) {
	for _, enabled := range []bool{true, false} {
		for _, opened := range []bool{true, false} {
			delivery, read := ReceiptsToSend(enabled, opened)
			if !delivery {
				t.Fatalf("enabled=%v opened=%v: delivery receipts always go back (SRS-RMT-014)",
					enabled, opened)
			}
			if read != (enabled && opened) {
				t.Fatalf("enabled=%v opened=%v: read receipt policy wrong", enabled, opened)
			}
		}
	}
}
