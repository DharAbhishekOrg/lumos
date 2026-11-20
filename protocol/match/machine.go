// Lumos - protocol/match / Interest/consent transitions as one lookup table.
// Author: dhar.abhishek.email@gmail.com
// Implements: SRS-MAT-003, SRS-MAT-004, SRS-MAT-005, SRS-MAT-014, TRS-PAT-002,
//             TRS-ARCH-009
// ADRs: ADR-002
// Threading: any
// Notes: The platform mirrors copy this table, the vectors replay it. Design
// rules, each held by a test: (1) SRS-MAT-004 silence - no row whose event
// is a decline or ignore carries a sending effect, so the asker's view
// after either is identical to no answer at all; (2) SRS-MAT-014
// idempotency - an event a state has already accounted for hits a missing
// row and is absorbed unchanged, which also covers reordering and replayed
// accepts after an unmatch; (3) a remote accept only ever answers a request
// WE still have outstanding; (4) SRS-MAT-005 - EffectMatchEstablished is
// the sole door to profile reveal and chat, and only mutual acceptance rows
// carry it. Blocking wins from every state. Absorption is not an error:
// Step returns the same state with EffectNone, and the only errors are a
// malformed call, never a protocol surprise.

package match

// State is where one peer relationship stands, seen from this device.
type State int

// Event is one typed input, local action or remote frame (TRS-ARCH-009).
type Event int

// Effect is the single instruction Step hands back to the caller.
type Effect int

// States.
const (
	None State = iota
	// RequestedOut: we asked, nothing heard - which is also exactly what
	// the asker sees after being declined or ignored, on purpose.
	RequestedOut
	RequestedIn
	// BothRequested: both asked before either answered - the race.
	BothRequested
	Matched
	// DeclinedIn / IgnoredIn: our silent answers, remembered so duplicates
	// of the old request do not resurface a card. A decline lets a
	// genuinely new request arrive later; an ignore absorbs them all.
	DeclinedIn
	IgnoredIn
	Unmatched
	Blocked
)

// Events.
const (
	LocalRequest Event = iota
	LocalAccept
	LocalDecline
	LocalIgnore
	LocalBlock
	LocalUnmatch
	RemoteRequest
	RemoteAccept
	RemoteUnmatch
)

// Effects. The two establishing effects are the only door to profile
// reveal and chat (SRS-MAT-005); which of the two fires says whether an
// acceptance still has to be transmitted.
const (
	EffectNone Effect = iota
	// EffectSendRequest: put our interest request on the wire.
	EffectSendRequest
	// EffectAcceptAndEstablish: OUR acceptance made the match - transmit
	// it, derive the match ID, reveal, open chat.
	EffectAcceptAndEstablish
	// EffectMatchEstablished: THEIR acceptance arrived - nothing to send;
	// derive the match ID, reveal, open chat.
	EffectMatchEstablished
	// EffectSendUnmatch: tell the peer the match is over (SRS-MAT-009's
	// visible half; blocks stay silent).
	EffectSendUnmatch
)

// Establishes reports whether an effect opens the SRS-MAT-005 door.
func (e Effect) Establishes() bool {
	return e == EffectAcceptAndEstablish || e == EffectMatchEstablished
}

type transition struct {
	next   State
	effect Effect
}

// table holds every pair that CHANGES anything. A missing pair absorbs:
// same state, EffectNone. The exhaustiveness test walks the full product
// and asserts each pair is either listed here or deliberately absorbing.
var table = map[State]map[Event]transition{
	None: {
		LocalRequest:  {RequestedOut, EffectSendRequest},
		RemoteRequest: {RequestedIn, EffectNone},
		LocalBlock:    {Blocked, EffectNone},
	},
	RequestedOut: {
		RemoteRequest: {BothRequested, EffectNone},
		RemoteAccept:  {Matched, EffectMatchEstablished},
		LocalBlock:    {Blocked, EffectNone},
	},
	RequestedIn: {
		LocalAccept:  {Matched, EffectAcceptAndEstablish},
		LocalDecline: {DeclinedIn, EffectNone},
		LocalIgnore:  {IgnoredIn, EffectNone},
		LocalRequest: {BothRequested, EffectSendRequest},
		LocalBlock:   {Blocked, EffectNone},
	},
	BothRequested: {
		LocalAccept:  {Matched, EffectAcceptAndEstablish},
		RemoteAccept: {Matched, EffectMatchEstablished},
		// Declining THEIR request leaves OUR request outstanding.
		LocalDecline: {RequestedOut, EffectNone},
		LocalIgnore:  {RequestedOut, EffectNone},
		LocalBlock:   {Blocked, EffectNone},
	},
	Matched: {
		LocalUnmatch:  {Unmatched, EffectSendUnmatch},
		RemoteUnmatch: {Unmatched, EffectNone},
		LocalBlock:    {Blocked, EffectNone},
	},
	DeclinedIn: {
		// A genuinely new request may try again; the old silence held.
		RemoteRequest: {RequestedIn, EffectNone},
		LocalRequest:  {RequestedOut, EffectSendRequest},
		LocalBlock:    {Blocked, EffectNone},
	},
	IgnoredIn: {
		// Ignore means stop surfacing: further requests absorb.
		LocalRequest: {RequestedOut, EffectSendRequest},
		LocalBlock:   {Blocked, EffectNone},
	},
	Unmatched: {
		// A new round may start after an unmatch; replayed accepts from
		// the dead match are absorbed by the missing-pair rule.
		LocalRequest:  {RequestedOut, EffectSendRequest},
		RemoteRequest: {RequestedIn, EffectNone},
		LocalBlock:    {Blocked, EffectNone},
	},
	Blocked: {
		// Nothing leaves Blocked from in here; unblocking is a safety-
		// centre flow with its own confirmation, outside this machine.
	},
}

// Step applies one event. Unknown pairs absorb: same state, EffectNone.
func Step(state State, event Event) (State, Effect) {
	if row, ok := table[state]; ok {
		if t, ok := row[event]; ok {
			return t.next, t.effect
		}
	}
	return state, EffectNone
}

// States lists every state, for exhaustive walks.
func States() []State {
	return []State{None, RequestedOut, RequestedIn, BothRequested, Matched,
		DeclinedIn, IgnoredIn, Unmatched, Blocked}
}

// Events lists every event, for exhaustive walks.
func Events() []Event {
	return []Event{LocalRequest, LocalAccept, LocalDecline, LocalIgnore,
		LocalBlock, LocalUnmatch, RemoteRequest, RemoteAccept, RemoteUnmatch}
}
