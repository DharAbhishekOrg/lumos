// Lumos - tools/vectorgen / The cross-language transport-fragmentation family.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-PROTO-010, TRS-PROTO-015, TRS-TST-001
// ADRs: ADR-004
// Threading: any
// Notes: WP-03 S2 adds the TRS-PROTO-010 fragmentation layer in three
// languages - Go (this generator's reference), Java (:protocol-java, which the
// Android BLE adapter will drive) and Swift (LumosProtocol) - and a stateful
// layer needs more than KATs: reassembly timeout and duplicate suppression are
// behaviours of a SEQUENCE, not of one input. The family therefore has two
// shapes. "split" cases pin the fragment bytes (consumers byte-compare their
// own Split and reassemble the committed fragments back to the blob).
// "scripts" drive a consumer's reassembler through offer-by-offer timelines -
// an injected millisecond clock, never a real one - and pin every step's
// outcome, including the exact stable error codes.
//
// Every script step carries the INTENDED outcome, and buildFragmentation runs
// the reference reassembler over the script and fails generation if reality
// disagrees. The committed expectations are therefore author intent held to the
// reference, not whatever the reference happened to do.
//
// This file is NOT prescribed by TRS Sec. 18.4; see ExtensionFiles in suite.go.
package main

import (
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"

	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumoserrors"
	"github.com/dharabhishekorg/lumos/protocol/wire"
)

// fragSplitCase pins Split's exact output for one blob and chunk payload.
type fragSplitCase struct {
	Name         string   `json:"name"`
	Description  string   `json:"description"`
	ChunkPayload int      `json:"chunk_payload"`
	AssemblyID   int      `json:"assembly_id"`
	BlobHex      string   `json:"blob_hex"`
	FragmentsHex []string `json:"fragments_hex"`
}

// fragStep is one offer in a scripted timeline.
type fragStep struct {
	AtMillis    int64  `json:"at_millis"`
	FragmentHex string `json:"fragment_hex"`
	// Expect is "accepted", "duplicate", "complete" or "error".
	Expect string `json:"expect"`
	// Error is the stable code when Expect is "error".
	Error string `json:"error,omitempty"`
	// PayloadHex is the reassembled blob when Expect is "complete".
	PayloadHex string `json:"payload_hex,omitempty"`
	// Restarted mirrors OfferResult.Restarted.
	Restarted bool `json:"restarted,omitempty"`
	// ExpiredPrevious mirrors OfferResult.ExpiredPrevious.
	ExpiredPrevious bool `json:"expired_previous,omitempty"`
}

// fragScript is one reassembler timeline.
type fragScript struct {
	Name          string     `json:"name"`
	Description   string     `json:"description"`
	TimeoutMillis int64      `json:"timeout_millis"`
	Steps         []fragStep `json:"steps"`
}

// fragFile is the emitted framing/fragmentation.json.
type fragFile struct {
	fileMeta
	Split   []fragSplitCase `json:"split"`
	Scripts []fragScript    `json:"scripts"`
}

// implementsIDs echoes fileMeta for the vectorDoc registration.
func (f *fragFile) implementsIDs() []string { return f.Implements }

// fragBlob derives a deterministic blob under the family's label namespace.
func fragBlob(label string, n int) []byte {
	return testonlyBytes("fragmentation/"+label, n)
}

// mustFragSplit runs the reference Split and fails generation on error.
func mustFragSplit(blob []byte, id uint8, chunk int) ([][]byte, error) {
	fragments, err := wire.Split(blob, id, chunk)
	if err != nil {
		return nil, fmt.Errorf("fragmentation reference split: %w", err)
	}
	return fragments, nil
}

// runFragScript executes a script against the reference reassembler and
// verifies every step's intended outcome, so a wrong expectation cannot be
// committed.
func runFragScript(s fragScript) error {
	r := wire.NewReassembler(s.TimeoutMillis)
	for i, step := range s.Steps {
		fragment, err := hex.DecodeString(step.FragmentHex)
		if err != nil {
			return fmt.Errorf("script %s step %d: %w", s.Name, i, err)
		}
		res, err := r.Offer(fragment, step.AtMillis)
		if step.Expect == "error" {
			var we *wire.Error
			switch {
			case err == nil:
				return fmt.Errorf("script %s step %d: expected %s, offer succeeded", s.Name, i, step.Error)
			case !errors.As(err, &we) || we.Code != step.Error:
				return fmt.Errorf("script %s step %d: expected %s, got %w", s.Name, i, step.Error, err)
			}
			continue
		}
		if err != nil {
			return fmt.Errorf("script %s step %d: unexpected error %w", s.Name, i, err)
		}
		if got := outcomeName(res.Outcome); got != step.Expect {
			return fmt.Errorf("script %s step %d: expected %s, got %s", s.Name, i, step.Expect, got)
		}
		if res.Restarted != step.Restarted || res.ExpiredPrevious != step.ExpiredPrevious {
			return fmt.Errorf("script %s step %d: flag mismatch (restarted %v/%v, expired %v/%v)",
				s.Name, i, res.Restarted, step.Restarted, res.ExpiredPrevious, step.ExpiredPrevious)
		}
		if step.Expect == "complete" && hex.EncodeToString(res.Payload) != step.PayloadHex {
			return fmt.Errorf("script %s step %d: reassembled payload differs", s.Name, i)
		}
	}
	return nil
}

func outcomeName(o wire.OfferOutcome) string {
	switch o {
	case wire.OfferAccepted:
		return "accepted"
	case wire.OfferDuplicate:
		return "duplicate"
	case wire.OfferComplete:
		return "complete"
	}
	return "unknown"
}

// fragStepsFor turns split fragments into in-order offer steps ending in
// completion, pacing offers stepMillis apart.
func fragStepsFor(fragments [][]byte, blob []byte, startMillis, stepMillis int64) []fragStep {
	steps := make([]fragStep, 0, len(fragments))
	for i, f := range fragments {
		step := fragStep{
			AtMillis:    startMillis + int64(i)*stepMillis,
			FragmentHex: hex.EncodeToString(f),
			Expect:      "accepted",
		}
		if i == len(fragments)-1 {
			step.Expect = "complete"
			step.PayloadHex = hex.EncodeToString(blob)
		}
		steps = append(steps, step)
	}
	return steps
}

// rawFragment hand-assembles a fragment for the malformed and boundary cases.
func rawFragment(flags byte, id uint8, index uint16, content ...byte) string {
	out := make([]byte, 4, 4+len(content))
	out[0] = flags
	out[1] = id
	binary.BigEndian.PutUint16(out[2:4], index)
	return hex.EncodeToString(append(out, content...))
}

// buildFragmentation emits the fragmentation family.
func buildFragmentation(constantsHash string) (*fragFile, error) {
	const timeoutMillis = int64(lumosconstants.ReassemblyTimeoutSecBLE) * 1000
	const first = byte(lumosconstants.FragFlagFirstDefault)
	const last = byte(lumosconstants.FragFlagLastDefault)

	file := &fragFile{
		fileMeta: fileMeta{
			SchemaVersion: 1, Generator: GeneratorName, ConstantsHash: constantsHash,
			Implements:    []string{"TRS-PROTO-010", "TRS-PROTO-015", "TRS-TST-001"},
			KeyProvenance: KeyProvenance,
			Notes: "TRS-PROTO-010 transport fragmentation. The layer is content-opaque: blobs " +
				"here are arbitrary derived bytes (one is an encoded canonical frame; the layer " +
				"never knows). split cases pin exact fragment bytes - consumers byte-compare " +
				"their own Split AND reassemble the committed fragments. scripts drive a " +
				"reassembler through timelines with an injected millisecond clock and pin every " +
				"offer's outcome: accepted/duplicate/complete flags or the exact stable error " +
				"code. timeout_millis is REASSEMBLY_TIMEOUT_SEC.ble * 1000; elapsed time " +
				"STRICTLY GREATER than the timeout expires an idle assembly.",
		},
	}

	// ---- split cases ------------------------------------------------------
	hello, err := buildFrame("HELLO", lumosconstants.MessageClassHello, 1)
	if err != nil {
		return nil, err
	}
	frameBlob, err := hex.DecodeString(hello.FrameHex)
	if err != nil {
		return nil, err
	}
	splitInputs := []struct {
		name, description string
		blob              []byte
		id                uint8
		chunk             int
	}{
		{"frame_at_default_mtu",
			"An encoded canonical HELLO frame split at the 251-MTU budget (244). The layer does not know it is a frame.",
			frameBlob, 1, 244},
		{"blob_at_floor_chunk",
			"A 299-byte opaque blob at the degraded-link floor budget (20).",
			fragBlob("floor", 299), 2, lumosconstants.BLEChunkPayloadFloor},
		{"blob_at_odd_chunk",
			"A 1000-byte opaque blob at an odd 37-byte budget, exercising a non-aligned final fragment.",
			fragBlob("odd", 1000), 3, 37},
		{"single_fragment_blob",
			"A 10-byte blob that fits one fragment: FIRST|LAST together.",
			fragBlob("single", 10), 4, 244},
	}
	for _, in := range splitInputs {
		fragments, err := mustFragSplit(in.blob, in.id, in.chunk)
		if err != nil {
			return nil, err
		}
		c := fragSplitCase{
			Name: in.name, Description: in.description,
			ChunkPayload: in.chunk, AssemblyID: int(in.id),
			BlobHex: hex.EncodeToString(in.blob),
		}
		for _, f := range fragments {
			c.FragmentsHex = append(c.FragmentsHex, hex.EncodeToString(f))
		}
		file.Split = append(file.Split, c)
	}

	// ---- scripted timelines ----------------------------------------------
	blobA := fragBlob("script-a", 101)
	fragsA, err := mustFragSplit(blobA, 10, lumosconstants.BLEChunkPayloadFloor)
	if err != nil {
		return nil, err
	}
	blobB := fragBlob("script-b", 50)
	fragsB, err := mustFragSplit(blobB, 11, lumosconstants.BLEChunkPayloadFloor)
	if err != nil {
		return nil, err
	}
	hx := func(f []byte) string { return hex.EncodeToString(f) }

	scripts := []fragScript{
		{
			Name:          "in_order_reassembly",
			Description:   "Every fragment in order completes with the original blob.",
			TimeoutMillis: timeoutMillis,
			Steps:         fragStepsFor(fragsA, blobA, 0, 10),
		},
		{
			Name:          "duplicate_fragment_suppressed",
			Description:   "A re-sent continuation is suppressed without disturbing the assembly (TRS-PROTO-010 duplicate suppression).",
			TimeoutMillis: timeoutMillis,
			Steps: []fragStep{
				{AtMillis: 0, FragmentHex: hx(fragsA[0]), Expect: "accepted"},
				{AtMillis: 10, FragmentHex: hx(fragsA[1]), Expect: "accepted"},
				{AtMillis: 20, FragmentHex: hx(fragsA[1]), Expect: "duplicate"},
				{AtMillis: 30, FragmentHex: hx(fragsA[2]), Expect: "accepted"},
				{AtMillis: 40, FragmentHex: hx(fragsA[3]), Expect: "accepted"},
				{AtMillis: 50, FragmentHex: hx(fragsA[4]), Expect: "accepted"},
				{AtMillis: 60, FragmentHex: hx(fragsA[5]), Expect: "complete", PayloadHex: hex.EncodeToString(blobA)},
			},
		},
		{
			Name:          "straggler_after_completion_suppressed",
			Description:   "A reconnect epoch re-delivers a fragment of the assembly that already completed: suppressed, never an error.",
			TimeoutMillis: timeoutMillis,
			Steps: append(fragStepsFor(fragsB, blobB, 0, 10),
				fragStep{AtMillis: 110, FragmentHex: hx(fragsB[1]), Expect: "duplicate"}),
		},
		{
			Name:          "first_replaces_open_assembly",
			Description:   "A FIRST while another assembly is open abandons the old one and restarts; whole-message dedupe is the semantic layer's job.",
			TimeoutMillis: timeoutMillis,
			Steps: append([]fragStep{
				{AtMillis: 0, FragmentHex: hx(fragsA[0]), Expect: "accepted"},
				{AtMillis: 10, FragmentHex: hx(fragsA[1]), Expect: "accepted"},
				{AtMillis: 20, FragmentHex: hx(fragsB[0]), Expect: "accepted", Restarted: true},
			}, fragStepsFor(fragsB[1:], blobB, 30, 10)...),
		},
		{
			Name:          "reassembly_timeout_expires_assembly",
			Description:   "A continuation arriving past the idle timeout fails with the timeout code; a fresh FIRST then proceeds normally.",
			TimeoutMillis: timeoutMillis,
			Steps: []fragStep{
				{AtMillis: 0, FragmentHex: hx(fragsA[0]), Expect: "accepted"},
				{AtMillis: timeoutMillis + 1, FragmentHex: hx(fragsA[1]), Expect: "error", Error: lumoserrors.ErrFragReassemblyTimeout},
				{AtMillis: timeoutMillis + 2, FragmentHex: hx(fragsA[0]), Expect: "accepted"},
			},
		},
		{
			Name:          "first_over_idle_assembly_reports_expiry",
			Description:   "A FIRST arriving after the open assembly aged out reports expired_previous and is not a restart.",
			TimeoutMillis: timeoutMillis,
			Steps: []fragStep{
				{AtMillis: 0, FragmentHex: hx(fragsA[0]), Expect: "accepted"},
				{AtMillis: timeoutMillis + 1, FragmentHex: hx(fragsB[0]), Expect: "accepted", ExpiredPrevious: true},
			},
		},
		{
			Name:          "exactly_at_timeout_still_alive",
			Description:   "Elapsed time equal to the timeout does NOT expire the assembly; only strictly greater does.",
			TimeoutMillis: timeoutMillis,
			Steps: append([]fragStep{
				{AtMillis: 0, FragmentHex: hx(fragsA[0]), Expect: "accepted"},
				{AtMillis: timeoutMillis, FragmentHex: hx(fragsA[1]), Expect: "accepted"},
			}, fragStepsFor(fragsA[2:], blobA, timeoutMillis+10, 10)...),
		},
		{
			Name:          "sequence_gap_drops_assembly",
			Description:   "A skipped index fails with the gap code and drops the assembly, so even the fragment that WAS next becomes unknown.",
			TimeoutMillis: timeoutMillis,
			Steps: []fragStep{
				{AtMillis: 0, FragmentHex: hx(fragsA[0]), Expect: "accepted"},
				{AtMillis: 10, FragmentHex: hx(fragsA[2]), Expect: "error", Error: lumoserrors.ErrFragSequenceGap},
				{AtMillis: 20, FragmentHex: hx(fragsA[1]), Expect: "error", Error: lumoserrors.ErrFragSequenceGap},
			},
		},
		{
			Name:          "unknown_continuation_rejected",
			Description:   "A continuation with no open or remembered assembly fails with the gap code.",
			TimeoutMillis: timeoutMillis,
			Steps: []fragStep{
				{AtMillis: 0, FragmentHex: hx(fragsA[1]), Expect: "error", Error: lumoserrors.ErrFragSequenceGap},
			},
		},
		{
			Name:          "declared_total_bounds_before_allocation",
			Description:   "A FIRST declaring more than FRAG_REASSEMBLY_MAX_BYTES is rejected before any allocation (TRS Sec. 9.4).",
			TimeoutMillis: timeoutMillis,
			Steps: []fragStep{
				{AtMillis: 0, FragmentHex: rawFragment(first, 20, 0,
					0x00, 0x20, 0x00, 0x3B, 0xAA), // 2097211 = max + 1
					Expect: "error", Error: lumoserrors.ErrFragTotalTooLarge},
			},
		},
		{
			Name:          "malformed_fragments_rejected",
			Description:   "Reserved flag bits, a FIRST with a non-zero index, a FIRST without a total, and a zero total all fail with the malformed code.",
			TimeoutMillis: timeoutMillis,
			Steps: []fragStep{
				{AtMillis: 0, FragmentHex: rawFragment(0x04, 0, 0, 0xAA), Expect: "error", Error: lumoserrors.ErrFragHeaderMalformed},
				{AtMillis: 1, FragmentHex: rawFragment(first, 0, 1, 0x00, 0x00, 0x00, 0x05, 0xAA), Expect: "error", Error: lumoserrors.ErrFragHeaderMalformed},
				{AtMillis: 2, FragmentHex: rawFragment(first, 0, 0, 0x00, 0x00, 0x00), Expect: "error", Error: lumoserrors.ErrFragHeaderMalformed},
				{AtMillis: 3, FragmentHex: rawFragment(first|last, 0, 0, 0x00, 0x00, 0x00, 0x00, 0xAA), Expect: "error", Error: lumoserrors.ErrFragHeaderMalformed},
			},
		},
		{
			Name:          "length_mismatches_rejected",
			Description:   "Content overrunning the declared total, and a LAST completing short of it, both fail with the mismatch code.",
			TimeoutMillis: timeoutMillis,
			Steps: []fragStep{
				{AtMillis: 0, FragmentHex: rawFragment(first, 30, 0, 0x00, 0x00, 0x00, 0x04, 0xAA, 0xBB, 0xCC), Expect: "accepted"},
				{AtMillis: 10, FragmentHex: rawFragment(0x00, 30, 1, 0xDD, 0xEE), Expect: "error", Error: lumoserrors.ErrFragLengthMismatch},
				{AtMillis: 20, FragmentHex: rawFragment(first, 31, 0, 0x00, 0x00, 0x00, 0x06, 0xAA, 0xBB, 0xCC), Expect: "accepted"},
				{AtMillis: 30, FragmentHex: rawFragment(last, 31, 1, 0xDD), Expect: "error", Error: lumoserrors.ErrFragLengthMismatch},
			},
		},
	}

	for _, s := range scripts {
		if err := runFragScript(s); err != nil {
			return nil, err
		}
	}
	file.Scripts = scripts
	return file, nil
}
