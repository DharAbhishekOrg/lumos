// Lumos - protocol/wire / Transport fragmentation: split and reassemble opaque blobs.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-PROTO-010, TRS-TRN-002, TRS-PROTO-007
// ADRs: ADR-004
// Threading: serial-per-link
// Notes: TRS-PROTO-010 makes this layer INDEPENDENT of semantic message framing,
// and the independence is structural here: Split and Reassembler carry []byte
// and never parse a frame. The 4-byte header layout is fixed by the constants
// register (transport.FRAG_HEADER_LEN notes) under the TRS Sec. 19 AI delivery
// rule: byte 0 flags (FIRST, LAST, rest reserved-zero), byte 1 assembly_id,
// bytes 2-3 fragment_index, big-endian. The FIRST fragment's content starts with
// a u32 total length, so the receiver validates bounds BEFORE allocating
// (TRS Sec. 9.4) - the same discipline the frame codec applies per TRS-PROTO-007.
//
// The reassembler is deliberately single-assembly: a serialized GATT link
// (TRS-TRN-003) carries one message at a time per direction, so "open assembly"
// is singular and a FIRST always begins a new one. Duplicate suppression covers
// the two shapes a reconnect epoch actually produces - a re-sent fragment of the
// OPEN assembly (index already consumed) and a straggler of the COMPLETED one -
// and whole-message retransmits are deduplicated above this layer by message_id,
// exactly where TRS Sec. 9.4 puts "duplicate semantic frames are suppressed
// before domain processing". Reassembly timeout is lazy: expiry is applied from
// the injected clock when the next fragment arrives, which keeps the layer free
// of timers and makes every timing behaviour a pure function the golden vectors
// can script.
package wire

import (
	"encoding/binary"

	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumoserrors"
)

// fragHeaderLen is the fixed fragment header length.
const fragHeaderLen = lumosconstants.FragHeaderLenDefault

// fragTotalPrefixLen is the u32 total-length prefix in a FIRST fragment.
const fragTotalPrefixLen = lumosconstants.FragTotalPrefixLenDefault

// FragmentPayloadCap derives the per-fragment content budget from a negotiated
// ATT MTU: mtu minus BLE_CHUNK_PAYLOAD.subtract, floored at
// BLE_CHUNK_PAYLOAD.floor (register rule negotiated_mtu_minus_7_floor_20).
func FragmentPayloadCap(negotiatedMTU int) int {
	budget := negotiatedMTU - lumosconstants.BLEChunkPayloadSubtract
	if budget < lumosconstants.BLEChunkPayloadFloor {
		return lumosconstants.BLEChunkPayloadFloor
	}
	return budget
}

// Split fragments an opaque blob for one link. chunkPayload is the content
// budget per fragment EXCLUDING the 4-byte header (FragmentPayloadCap of the
// negotiated MTU). The blob is never parsed.
func Split(blob []byte, assemblyID uint8, chunkPayload int) ([][]byte, error) {
	if chunkPayload < lumosconstants.BLEChunkPayloadFloor {
		return nil, wireErr(lumoserrors.ErrFragHeaderMalformed,
			"chunk payload %d below the register floor %d", chunkPayload, lumosconstants.BLEChunkPayloadFloor)
	}
	if len(blob) == 0 {
		return nil, wireErr(lumoserrors.ErrFragHeaderMalformed, "nothing to fragment")
	}
	if int64(len(blob)) > int64(lumosconstants.FragReassemblyMaxBytesDefault) {
		return nil, wireErr(lumoserrors.ErrFragTotalTooLarge,
			"blob %d exceeds FRAG_REASSEMBLY_MAX_BYTES %d", len(blob), lumosconstants.FragReassemblyMaxBytesDefault)
	}

	// The content stream is the u32 total followed by the blob; every fragment
	// carries up to chunkPayload bytes of it.
	content := make([]byte, 0, fragTotalPrefixLen+len(blob))
	content = binary.BigEndian.AppendUint32(content, uint32(len(blob)))
	content = append(content, blob...)

	count := (len(content) + chunkPayload - 1) / chunkPayload
	if count > lumosconstants.FragMaxFragmentsDefault {
		return nil, wireErr(lumoserrors.ErrFragTotalTooLarge,
			"%d fragments exceed FRAG_MAX_FRAGMENTS %d: the transfer does not fit this link",
			count, lumosconstants.FragMaxFragmentsDefault)
	}

	fragments := make([][]byte, 0, count)
	for index := 0; index < count; index++ {
		var flags byte
		if index == 0 {
			flags |= lumosconstants.FragFlagFirstDefault
		}
		if index == count-1 {
			flags |= lumosconstants.FragFlagLastDefault
		}
		at := index * chunkPayload
		end := min(at+chunkPayload, len(content))
		fragment := make([]byte, fragHeaderLen, fragHeaderLen+(end-at))
		fragment[0] = flags
		fragment[1] = assemblyID
		binary.BigEndian.PutUint16(fragment[2:4], uint16(index))
		fragments = append(fragments, append(fragment, content[at:end]...))
	}
	return fragments, nil
}

// OfferOutcome says what one offered fragment did to the assembly.
type OfferOutcome int

const (
	// OfferAccepted means the fragment advanced an incomplete assembly.
	OfferAccepted OfferOutcome = iota
	// OfferDuplicate means the fragment was already consumed and is suppressed.
	OfferDuplicate
	// OfferComplete means the assembly finished; Payload holds the blob.
	OfferComplete
)

// OfferResult reports a successful Offer.
type OfferResult struct {
	Outcome OfferOutcome
	// Payload is the reassembled blob when Outcome is OfferComplete.
	Payload []byte
	// Restarted is true when a FIRST replaced a live, incomplete assembly.
	Restarted bool
	// ExpiredPrevious is true when the open assembly aged out during this
	// offer before the fragment was considered.
	ExpiredPrevious bool
}

// Reassembler rebuilds one link direction's blobs. It is not safe for
// concurrent use: the serialized GATT queue (TRS-TRN-003) is its caller.
type Reassembler struct {
	timeoutMillis int64

	open struct {
		active bool
		id     uint8
		next   int
		total  int
		buf    []byte
		lastAt int64
	}
	completed struct {
		valid bool
		id    uint8
	}
	expired struct {
		valid bool
		id    uint8
	}
}

// NewReassembler builds a reassembler whose assemblies expire after
// timeoutMillis of inactivity (REASSEMBLY_TIMEOUT_SEC for the carrying link,
// in milliseconds).
func NewReassembler(timeoutMillis int64) *Reassembler {
	return &Reassembler{timeoutMillis: timeoutMillis}
}

// Offer feeds one received fragment at nowMillis from the caller's clock.
func (r *Reassembler) Offer(fragment []byte, nowMillis int64) (OfferResult, error) {
	if len(fragment) < fragHeaderLen+1 {
		return OfferResult{}, wireErr(lumoserrors.ErrFragHeaderMalformed,
			"fragment shorter than header plus one content byte")
	}
	flags := fragment[0]
	if flags&lumosconstants.FragFlagsReservedMaskDefault != 0 {
		return OfferResult{}, wireErr(lumoserrors.ErrFragHeaderMalformed, "reserved flag bit set")
	}
	id := fragment[1]
	index := int(binary.BigEndian.Uint16(fragment[2:4]))
	content := fragment[fragHeaderLen:]

	expiredNow := r.expireIfIdle(nowMillis)

	if flags&lumosconstants.FragFlagFirstDefault != 0 {
		return r.offerFirst(id, index, flags, content, nowMillis, expiredNow)
	}
	return r.offerContinuation(id, index, flags, content, nowMillis, expiredNow)
}

// expireIfIdle lazily applies the reassembly timeout and reports whether the
// open assembly aged out on this offer.
func (r *Reassembler) expireIfIdle(nowMillis int64) bool {
	if !r.open.active || nowMillis-r.open.lastAt <= r.timeoutMillis {
		return false
	}
	r.expired.valid, r.expired.id = true, r.open.id
	r.open.active = false
	r.open.buf = nil
	return true
}

// offerFirst starts a new assembly, completing it at once when LAST is also set.
func (r *Reassembler) offerFirst(id uint8, index int, flags byte, content []byte,
	nowMillis int64, expiredNow bool) (OfferResult, error) {
	if index != 0 {
		return OfferResult{}, wireErr(lumoserrors.ErrFragHeaderMalformed,
			"FIRST fragment with index %d", index)
	}
	if len(content) < fragTotalPrefixLen+1 {
		return OfferResult{}, wireErr(lumoserrors.ErrFragHeaderMalformed,
			"FIRST fragment without a total prefix and a content byte")
	}
	total := int64(binary.BigEndian.Uint32(content[:fragTotalPrefixLen]))
	if total == 0 {
		return OfferResult{}, wireErr(lumoserrors.ErrFragHeaderMalformed, "zero total length")
	}
	if total > int64(lumosconstants.FragReassemblyMaxBytesDefault) {
		// Checked before any reassembly allocation (TRS Sec. 9.4).
		return OfferResult{}, wireErr(lumoserrors.ErrFragTotalTooLarge,
			"declared total %d exceeds FRAG_REASSEMBLY_MAX_BYTES %d",
			total, lumosconstants.FragReassemblyMaxBytesDefault)
	}
	data := content[fragTotalPrefixLen:]
	if int64(len(data)) > total {
		return OfferResult{}, wireErr(lumoserrors.ErrFragLengthMismatch,
			"FIRST carries %d bytes of a %d-byte blob", len(data), total)
	}

	restarted := r.open.active
	r.open.active = true
	r.open.id = id
	r.open.next = 1
	r.open.total = int(total)
	r.open.buf = append(make([]byte, 0, total), data...)
	r.open.lastAt = nowMillis

	if flags&lumosconstants.FragFlagLastDefault != 0 {
		return r.complete(restarted, expiredNow)
	}
	return OfferResult{Outcome: OfferAccepted, Restarted: restarted, ExpiredPrevious: expiredNow}, nil
}

// offerContinuation extends, suppresses or rejects a non-FIRST fragment.
func (r *Reassembler) offerContinuation(id uint8, index int, flags byte, content []byte,
	nowMillis int64, expiredNow bool) (OfferResult, error) {
	switch {
	case r.open.active && id == r.open.id:
		// Falls through to the sequential logic below.
	case r.completed.valid && id == r.completed.id:
		// A straggler of the assembly that already completed: a reconnect
		// epoch re-sent it. Suppressed, never an error (TRS-PROTO-010).
		return OfferResult{Outcome: OfferDuplicate, ExpiredPrevious: expiredNow}, nil
	case r.expired.valid && id == r.expired.id:
		return OfferResult{}, wireErr(lumoserrors.ErrFragReassemblyTimeout,
			"continuation of an assembly that aged out")
	default:
		return OfferResult{}, wireErr(lumoserrors.ErrFragSequenceGap,
			"continuation of an assembly this receiver does not have")
	}

	if index < r.open.next {
		// Already consumed: a link-level retransmit. Suppressed without
		// refreshing the idle clock - a duplicate is not progress.
		return OfferResult{Outcome: OfferDuplicate, ExpiredPrevious: expiredNow}, nil
	}
	if index > r.open.next {
		r.drop()
		return OfferResult{}, wireErr(lumoserrors.ErrFragSequenceGap,
			"fragment index %d where %d was expected", index, r.open.next)
	}
	if len(r.open.buf)+len(content) > r.open.total {
		r.drop()
		return OfferResult{}, wireErr(lumoserrors.ErrFragLengthMismatch,
			"content overruns the declared total %d", r.open.total)
	}
	r.open.buf = append(r.open.buf, content...)
	r.open.next = index + 1
	r.open.lastAt = nowMillis

	if flags&lumosconstants.FragFlagLastDefault != 0 {
		return r.complete(false, expiredNow)
	}
	return OfferResult{Outcome: OfferAccepted, ExpiredPrevious: expiredNow}, nil
}

// complete closes the open assembly, demanding exactly the declared length.
func (r *Reassembler) complete(restarted, expiredNow bool) (OfferResult, error) {
	if len(r.open.buf) != r.open.total {
		got, want := len(r.open.buf), r.open.total
		r.drop()
		return OfferResult{}, wireErr(lumoserrors.ErrFragLengthMismatch,
			"LAST completed with %d of %d declared bytes", got, want)
	}
	payload := r.open.buf
	r.completed.valid, r.completed.id = true, r.open.id
	r.drop()
	return OfferResult{
		Outcome: OfferComplete, Payload: payload,
		Restarted: restarted, ExpiredPrevious: expiredNow,
	}, nil
}

// drop discards the open assembly without recording a tombstone.
func (r *Reassembler) drop() {
	r.open.active = false
	r.open.buf = nil
}
