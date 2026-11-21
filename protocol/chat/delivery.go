// Lumos - protocol/chat / The outgoing-message delivery machine and its ticks.
// Author: dhar.abhishek.email@gmail.com
// Implements: SRS-RMT-005, SRS-RMT-014, SRS-TRN-008, TRS-PAT-002
// ADRs: ADR-002
// Threading: any
// Notes: SRS-RMT-005's seven states as one lookup table the platforms copy
// and the vectors replay. The EDP guardrail is structural: the only events
// whose rows reach DELIVERED or READ are the recipient's receipts, so no
// transport success - a relay accepting an upload, a BLE write completing -
// can ever show two ticks. Receipts only move forward (a late delivery
// receipt after a read one leaves the message READ), and a recipient's
// receipt beats a local FAILED, because the recipient saw it. Unlisted pairs
// absorb, as in the match machine, and the exhaustiveness test walks the
// whole product. The owner's 2026-11-21 ruling fixed the presentation as
// WhatsApp-style ticks; Tick() is that mapping, here so every platform
// draws the same glyph for the same state.

package chat

// State is where one outgoing message stands.
type State int

// Event is one typed input to the machine.
type Event int

// Tick is the glyph a state renders as.
type Tick string

// States.
const (
	// Queued: stored in the outbox, no attempt running.
	Queued State = iota
	// Sending: a transport attempt is in flight.
	Sending
	// Sent: a transport accepted it (relay upload or local write).
	Sent
	// Delivered: the recipient's device acknowledged it.
	Delivered
	// Read: the recipient opened it (and allows read receipts).
	Read
	// Failed: the retry budget ran out; the user may retry.
	Failed
	// LocalOnlyWaiting: nearby-only content waiting for a nearby path.
	LocalOnlyWaiting
)

// Events.
const (
	// SendStarted: the orchestrator began an attempt.
	SendStarted Event = iota
	// TransportAccepted: the attempt's transport took the bytes.
	TransportAccepted
	// TransportRetryable: the attempt failed and may be retried.
	TransportRetryable
	// AttemptsExhausted: the retry budget is spent.
	AttemptsExhausted
	// DeliveryReceipt: the recipient's device acknowledged the message.
	DeliveryReceipt
	// ReadReceipt: the recipient read the message.
	ReadReceipt
	// UserRetry: the user tapped retry on a failed message.
	UserRetry
	// LocalPathUnavailable: nearby-only content has no nearby path.
	LocalPathUnavailable
	// LocalPathAvailable: a nearby path appeared for waiting content.
	LocalPathAvailable
)

// The ticks: WhatsApp-style, per the owner's 2026-11-21 ruling.
const (
	TickClock      Tick = "clock"       // queued or sending
	TickSingle     Tick = "single"      // sent: one grey tick
	TickDouble     Tick = "double"      // delivered: two grey ticks
	TickDoubleRead Tick = "double_read" // read: two blue ticks
	TickFailed     Tick = "failed"      // failed: red mark, tap to retry
	TickNearby     Tick = "nearby"      // waiting for a nearby path
)

// Receipts move any not-yet-delivered message forward; they are the ground
// truth the guardrail names, so they apply from every pre-receipt state.
var receiptRows = map[Event]State{DeliveryReceipt: Delivered, ReadReceipt: Read}

// table holds every pair that changes something besides receipts.
var table = map[State]map[Event]State{
	Queued: {
		SendStarted:          Sending,
		LocalPathUnavailable: LocalOnlyWaiting,
	},
	Sending: {
		TransportAccepted:  Sent,
		TransportRetryable: Queued,
		AttemptsExhausted:  Failed,
	},
	Failed: {
		UserRetry: Queued,
	},
	LocalOnlyWaiting: {
		LocalPathAvailable: Queued,
	},
}

// rank orders states for the receipts-only-move-forward rule.
var rank = map[State]int{
	Queued: 0, Sending: 0, Failed: 0, LocalOnlyWaiting: 0,
	Sent: 1, Delivered: 2, Read: 3,
}

// Step applies one event; unlisted pairs absorb (same state).
func Step(s State, e Event) State {
	if target, ok := receiptRows[e]; ok {
		if rank[target] > rank[s] {
			return target
		}
		return s
	}
	if row, ok := table[s]; ok {
		if next, ok := row[e]; ok {
			return next
		}
	}
	return s
}

// TickOf is the glyph a state renders as.
func TickOf(s State) Tick {
	switch s {
	case Sent:
		return TickSingle
	case Delivered:
		return TickDouble
	case Read:
		return TickDoubleRead
	case Failed:
		return TickFailed
	case LocalOnlyWaiting:
		return TickNearby
	default:
		return TickClock
	}
}

// States lists every state, for exhaustive walks.
func States() []State {
	return []State{Queued, Sending, Sent, Delivered, Read, Failed, LocalOnlyWaiting}
}

// Events lists every event, for exhaustive walks.
func Events() []Event {
	return []Event{SendStarted, TransportAccepted, TransportRetryable, AttemptsExhausted,
		DeliveryReceipt, ReadReceipt, UserRetry, LocalPathUnavailable, LocalPathAvailable}
}

// ReceiptsToSend is the SRS-RMT-014 policy for an incoming message: a
// delivery receipt always goes back; a read receipt only when the user
// allows read receipts. Turning read receipts off never turns delivery
// receipts off.
func ReceiptsToSend(readReceiptsEnabled, opened bool) (delivery, read bool) {
	return true, readReceiptsEnabled && opened
}
