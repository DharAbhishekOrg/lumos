// Lumos - protocol/wire / Fragmentation behaviour: split, reassembly, timeout, duplicates.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-PROTO-010, TRS-PROTO-007, TRS-TST-001
// ADRs: ADR-004
// Threading: any
// Notes: The golden-vector family pins the cross-language byte behaviour; these
// tests pin the semantics the vectors are generated FROM, including the two
// derivations the register asserts in prose - the reassembly ceiling being
// exactly the largest legal frame, and the chunk-payload rule - so a drift in
// either fails here before it reaches a committed vector.
package wire

import (
	"bytes"
	"errors"
	"testing"

	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumoserrors"
)

const testTimeoutMillis = int64(lumosconstants.ReassemblyTimeoutSecBLE) * 1000

func testBlob(n int) []byte {
	blob := make([]byte, n)
	for i := range blob {
		blob[i] = byte(i * 31)
	}
	return blob
}

func mustSplit(t *testing.T, blob []byte, id uint8, chunk int) [][]byte {
	t.Helper()
	fragments, err := Split(blob, id, chunk)
	if err != nil {
		t.Fatalf("split: %v", err)
	}
	return fragments
}

func wireCode(t *testing.T, err error) string {
	t.Helper()
	var we *Error
	if !errors.As(err, &we) {
		t.Fatalf("error %v is not a wire error", err)
	}
	return we.Code
}

func TestReassemblyCeilingIsTheLargestLegalFrame(t *testing.T) {
	// The register states FRAG_REASSEMBLY_MAX_BYTES = FRAME_HEADER_LEN_BYTES +
	// the largest per-class payload bound. Holding the equality here means a
	// raised class bound cannot leave the ceiling silently behind.
	var largest int64
	for mc := range lumosconstants.MessageClassNames {
		if max, ok := lumosconstants.MaxPayloadBytes(mc); ok && max > largest {
			largest = max
		}
	}
	want := int64(lumosconstants.FrameHeaderLenBytesDefault) + largest
	if int64(lumosconstants.FragReassemblyMaxBytesDefault) != want {
		t.Fatalf("FRAG_REASSEMBLY_MAX_BYTES is %d; the largest legal frame is %d",
			lumosconstants.FragReassemblyMaxBytesDefault, want)
	}
}

func TestFragmentPayloadCapFollowsTheRegisterRule(t *testing.T) {
	if got := FragmentPayloadCap(251); got != 244 {
		t.Fatalf("cap at the 251 target: got %d, want 244", got)
	}
	if got := FragmentPayloadCap(23); got != lumosconstants.BLEChunkPayloadFloor {
		t.Fatalf("cap at MTU 23 must floor at %d, got %d", lumosconstants.BLEChunkPayloadFloor, got)
	}
}

func TestSplitAndReassembleRoundTrip(t *testing.T) {
	for _, chunk := range []int{lumosconstants.BLEChunkPayloadFloor, 37, 244} {
		blob := testBlob(1000)
		fragments := mustSplit(t, blob, 7, chunk)
		r := NewReassembler(testTimeoutMillis)
		var payload []byte
		for i, f := range fragments {
			res, err := r.Offer(f, int64(i))
			if err != nil {
				t.Fatalf("chunk %d fragment %d: %v", chunk, i, err)
			}
			if i < len(fragments)-1 && res.Outcome != OfferAccepted {
				t.Fatalf("chunk %d fragment %d: outcome %v", chunk, i, res.Outcome)
			}
			if i == len(fragments)-1 {
				if res.Outcome != OfferComplete {
					t.Fatalf("chunk %d last fragment: outcome %v", chunk, res.Outcome)
				}
				payload = res.Payload
			}
		}
		if !bytes.Equal(payload, blob) {
			t.Fatalf("chunk %d: reassembled blob differs", chunk)
		}
	}
}

func TestSingleFragmentBlobIsFirstAndLast(t *testing.T) {
	blob := testBlob(10)
	fragments := mustSplit(t, blob, 1, 244)
	if len(fragments) != 1 {
		t.Fatalf("want one fragment, got %d", len(fragments))
	}
	wantFlags := byte(lumosconstants.FragFlagFirstDefault | lumosconstants.FragFlagLastDefault)
	if fragments[0][0] != wantFlags {
		t.Fatalf("flags %#x, want FIRST|LAST", fragments[0][0])
	}
	r := NewReassembler(testTimeoutMillis)
	res, err := r.Offer(fragments[0], 0)
	if err != nil || res.Outcome != OfferComplete || !bytes.Equal(res.Payload, blob) {
		t.Fatalf("single-fragment reassembly: res=%+v err=%v", res, err)
	}
}

func TestSplitRejects(t *testing.T) {
	if _, err := Split(nil, 0, 244); err == nil {
		t.Fatal("empty blob must not split")
	}
	if _, err := Split(testBlob(10), 0, lumosconstants.BLEChunkPayloadFloor-1); err == nil {
		t.Fatal("chunk payload below the floor must be rejected")
	}
	// A blob needing more than FRAG_MAX_FRAGMENTS at this chunk payload: the
	// sender refuses rather than stalling the receiver.
	tooMany := testBlob(lumosconstants.FragMaxFragmentsDefault*lumosconstants.BLEChunkPayloadFloor + 1)
	_, err := Split(tooMany, 0, lumosconstants.BLEChunkPayloadFloor)
	if err == nil || wireCode(t, err) != lumoserrors.ErrFragTotalTooLarge {
		t.Fatalf("oversized split: %v", err)
	}
}

func TestDuplicateFragmentsAreSuppressed(t *testing.T) {
	fragments := mustSplit(t, testBlob(100), 3, lumosconstants.BLEChunkPayloadFloor)
	if len(fragments) < 4 {
		t.Fatalf("test wants >=4 fragments, got %d", len(fragments))
	}
	r := NewReassembler(testTimeoutMillis)
	offer := func(i int) OfferResult {
		res, err := r.Offer(fragments[i], int64(i))
		if err != nil {
			t.Fatalf("fragment %d: %v", i, err)
		}
		return res
	}
	offer(0)
	offer(1)
	if res := offer(1); res.Outcome != OfferDuplicate {
		t.Fatalf("re-sent fragment must be suppressed, got %v", res.Outcome)
	}
	if res := offer(0); res.Outcome != OfferAccepted || !res.Restarted {
		// A re-sent FIRST legitimately restarts: whole-message dedupe is the
		// semantic layer's job (message_id), not this one's.
		t.Fatalf("re-sent FIRST must restart the assembly, got %+v", res)
	}
}

func TestStragglerAfterCompletionIsSuppressed(t *testing.T) {
	fragments := mustSplit(t, testBlob(60), 9, lumosconstants.BLEChunkPayloadFloor)
	r := NewReassembler(testTimeoutMillis)
	for i, f := range fragments {
		if _, err := r.Offer(f, int64(i)); err != nil {
			t.Fatalf("fragment %d: %v", i, err)
		}
	}
	res, err := r.Offer(fragments[1], 99)
	if err != nil || res.Outcome != OfferDuplicate {
		t.Fatalf("straggler after completion: res=%+v err=%v", res, err)
	}
}

func TestSequenceGapDropsTheAssembly(t *testing.T) {
	fragments := mustSplit(t, testBlob(100), 5, lumosconstants.BLEChunkPayloadFloor)
	r := NewReassembler(testTimeoutMillis)
	if _, err := r.Offer(fragments[0], 0); err != nil {
		t.Fatal(err)
	}
	_, err := r.Offer(fragments[2], 1)
	if err == nil || wireCode(t, err) != lumoserrors.ErrFragSequenceGap {
		t.Fatalf("skipped index: %v", err)
	}
	// The assembly is gone, so even the fragment that WAS next is now an
	// unknown continuation.
	_, err = r.Offer(fragments[1], 2)
	if err == nil || wireCode(t, err) != lumoserrors.ErrFragSequenceGap {
		t.Fatalf("continuation after drop: %v", err)
	}
}

func TestUnknownContinuationIsAGap(t *testing.T) {
	fragments := mustSplit(t, testBlob(100), 5, lumosconstants.BLEChunkPayloadFloor)
	r := NewReassembler(testTimeoutMillis)
	_, err := r.Offer(fragments[1], 0)
	if err == nil || wireCode(t, err) != lumoserrors.ErrFragSequenceGap {
		t.Fatalf("continuation with no history: %v", err)
	}
}

func TestReassemblyTimeoutExpiresTheAssembly(t *testing.T) {
	fragments := mustSplit(t, testBlob(100), 11, lumosconstants.BLEChunkPayloadFloor)
	r := NewReassembler(testTimeoutMillis)
	if _, err := r.Offer(fragments[0], 0); err != nil {
		t.Fatal(err)
	}
	_, err := r.Offer(fragments[1], testTimeoutMillis+1)
	if err == nil || wireCode(t, err) != lumoserrors.ErrFragReassemblyTimeout {
		t.Fatalf("continuation past the timeout: %v", err)
	}
	// A fresh FIRST after expiry proceeds normally and reports nothing stale.
	res, err := r.Offer(fragments[0], testTimeoutMillis+2)
	if err != nil || res.Outcome != OfferAccepted || res.Restarted {
		t.Fatalf("FIRST after expiry: res=%+v err=%v", res, err)
	}
}

func TestFirstArrivingAfterIdleExpiryReportsIt(t *testing.T) {
	a := mustSplit(t, testBlob(100), 1, lumosconstants.BLEChunkPayloadFloor)
	b := mustSplit(t, testBlob(50), 2, lumosconstants.BLEChunkPayloadFloor)
	r := NewReassembler(testTimeoutMillis)
	if _, err := r.Offer(a[0], 0); err != nil {
		t.Fatal(err)
	}
	res, err := r.Offer(b[0], testTimeoutMillis+1)
	if err != nil || !res.ExpiredPrevious || res.Restarted {
		t.Fatalf("FIRST over an aged-out assembly: res=%+v err=%v", res, err)
	}
}

func TestExactlyAtTheTimeoutIsNotExpired(t *testing.T) {
	fragments := mustSplit(t, testBlob(100), 1, lumosconstants.BLEChunkPayloadFloor)
	r := NewReassembler(testTimeoutMillis)
	if _, err := r.Offer(fragments[0], 0); err != nil {
		t.Fatal(err)
	}
	res, err := r.Offer(fragments[1], testTimeoutMillis)
	if err != nil || res.Outcome != OfferAccepted {
		t.Fatalf("elapsed == timeout must still be alive: res=%+v err=%v", res, err)
	}
}

func TestMalformedFragmentsAreRejected(t *testing.T) {
	r := NewReassembler(testTimeoutMillis)
	cases := []struct {
		name     string
		fragment []byte
	}{
		{"short", []byte{0x01, 0x00, 0x00, 0x00}},
		{"reserved_bit", []byte{0x04, 0x00, 0x00, 0x00, 0xAA}},
		{"first_with_nonzero_index", []byte{0x01, 0x00, 0x00, 0x01, 0, 0, 0, 5, 0xAA}},
		{"first_without_total", []byte{0x01, 0x00, 0x00, 0x00, 0, 0, 0}},
		{"zero_total", append([]byte{0x03, 0x00, 0x00, 0x00}, 0, 0, 0, 0, 0xAA)},
	}
	for _, c := range cases {
		_, err := r.Offer(c.fragment, 0)
		if err == nil || wireCode(t, err) != lumoserrors.ErrFragHeaderMalformed {
			t.Fatalf("%s: %v", c.name, err)
		}
	}
}

func TestDeclaredTotalBoundsBeforeAllocation(t *testing.T) {
	over := uint32(lumosconstants.FragReassemblyMaxBytesDefault + 1)
	fragment := []byte{
		lumosconstants.FragFlagFirstDefault, 0x00, 0x00, 0x00,
		byte(over >> 24), byte(over >> 16), byte(over >> 8), byte(over), 0xAA,
	}
	r := NewReassembler(testTimeoutMillis)
	_, err := r.Offer(fragment, 0)
	if err == nil || wireCode(t, err) != lumoserrors.ErrFragTotalTooLarge {
		t.Fatalf("oversized declared total: %v", err)
	}
}

func TestLengthMismatchesAreRejected(t *testing.T) {
	// Overrun: FIRST declares 4 bytes, continuation pushes past it.
	first := []byte{lumosconstants.FragFlagFirstDefault, 0x01, 0x00, 0x00, 0, 0, 0, 4, 0xAA, 0xBB, 0xCC}
	overrun := []byte{0x00, 0x01, 0x00, 0x01, 0xDD, 0xEE}
	r := NewReassembler(testTimeoutMillis)
	if _, err := r.Offer(first, 0); err != nil {
		t.Fatal(err)
	}
	_, err := r.Offer(overrun, 1)
	if err == nil || wireCode(t, err) != lumoserrors.ErrFragLengthMismatch {
		t.Fatalf("overrun: %v", err)
	}

	// Underrun: LAST arrives with the assembly short of the declared total
	// (6 declared, 3 + 1 delivered).
	short := []byte{lumosconstants.FragFlagFirstDefault, 0x01, 0x00, 0x00, 0, 0, 0, 6, 0xAA, 0xBB, 0xCC}
	r = NewReassembler(testTimeoutMillis)
	if _, err := r.Offer(short, 0); err != nil {
		t.Fatal(err)
	}
	last := []byte{lumosconstants.FragFlagLastDefault, 0x01, 0x00, 0x01, 0xDD}
	_, err = r.Offer(last, 1)
	if err == nil || wireCode(t, err) != lumoserrors.ErrFragLengthMismatch {
		t.Fatalf("underrun: %v", err)
	}
}

func TestLayerIsContentOpaque(t *testing.T) {
	// TRS-PROTO-010 independence, exercised rather than asserted: bytes that
	// are NOT a canonical frame fragment and reassemble unchanged.
	blob := []byte("not a frame at all")
	fragments := mustSplit(t, blob, 42, lumosconstants.BLEChunkPayloadFloor)
	r := NewReassembler(testTimeoutMillis)
	var out []byte
	for i, f := range fragments {
		res, err := r.Offer(f, int64(i))
		if err != nil {
			t.Fatal(err)
		}
		if res.Outcome == OfferComplete {
			out = res.Payload
		}
	}
	if !bytes.Equal(out, blob) {
		t.Fatal("non-frame bytes must survive the layer untouched")
	}
}
