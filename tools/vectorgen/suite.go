// Lumos - tools/vectorgen / The TRS Sec. 18.4 suite completeness gate.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-TST-001, TRS-PROTO-015
// ADRs: -
// Threading: any
// Notes: TRS Sec. 18.4 is explicit that "a skipped file is a failed gate". The
// prescribed tree is transcribed here BY HAND from the specification table, not
// derived from whatever the generator happens to emit - a completeness check
// built from the artefact it checks would pass for any suite, including an empty
// one. Generation fails if a file is missing or if an unlisted file appears.
// Additions the specification does not prescribe live in ExtensionFiles, which
// keeps "what the TRS asked for" and "what we chose to add" separable by eye.
package main

import (
	"fmt"
	"sort"
	"strings"
)

// TRS184Files is the TRS Sec. 18.4 golden-vector tree, transcribed by hand from
// the specification table. manifest.json is excluded: it is the index of this
// set, not a member of it.
//
// Nothing may be added here that is not in that table. Files this project needs
// beyond the specification go in ExtensionFiles, so this list stays a faithful
// copy that a reviewer can diff against the PDF.
var TRS184Files = []string{
	"test-vectors/adv/ble_advertisement.json",
	"test-vectors/config/package_invalid.json",
	"test-vectors/config/package_valid.json",
	"test-vectors/crypto/aead_kat.json",
	"test-vectors/crypto/ratchet_sequences.json",
	"test-vectors/crypto/safety_number.json",
	"test-vectors/crypto/x3dh_kat.json",
	"test-vectors/errors/error_codes.json",
	"test-vectors/framing/frame_invalid.json",
	"test-vectors/framing/frame_roundtrip.json",
	"test-vectors/media/object_pipeline.json",
	"test-vectors/voucher/voucher_tokens.json",
}

// ExtensionFiles are vector files this project emits BEYOND TRS Sec. 18.4.
//
// The specification's table was written before the ADR-003 RecoveryRecord
// existed, so it prescribes no recovery family. WP-02 S4b adds one on the
// owner's instruction, because the record is a cross-language format with three
// implementations and that is exactly the kind of thing golden vectors are for.
// Keeping it in its own list means the completeness gate still covers it while
// the Sec. 18.4 transcription above cannot be quietly grown to hide an addition.
var ExtensionFiles = []string{
	"test-vectors/crypto/identity_binding.json",
	"test-vectors/crypto/recovery_record.json",
	// WP-03 S2: the TRS-PROTO-010 fragmentation layer is stateful and
	// cross-language (Go reference, Java for the Android BLE adapter, Swift),
	// so it gets the same treatment the Sec. 18.4 families get - including
	// scripted timelines, which a stateful layer needs and KATs cannot give.
	"test-vectors/framing/fragmentation.json",
	// WP-03 S4: the HELLO/HELLO_ACK canonical trust point (TRS-PROTO-017).
	// Three implementations grant presence from this exchange, and
	// TRS-ENV-002's zero-false-positive rule is only as strong as their
	// agreement on exactly which bytes verify and which are rejected.
	"test-vectors/session/hello_exchange.json",
}

// SuiteFiles is everything generation must emit: the specification's tree plus
// the deliberate extensions, sorted so the manifest's order is stable.
var SuiteFiles = sortedUnion(TRS184Files, ExtensionFiles)

// sortedUnion concatenates and sorts, so the emitted order never depends on
// which group a path came from.
func sortedUnion(groups ...[]string) []string {
	var out []string
	for _, g := range groups {
		out = append(out, g...)
	}
	sort.Strings(out)
	return out
}

// assertSuiteComplete enforces that emitted is exactly SuiteFiles.
func assertSuiteComplete(emitted []VectorFile) error {
	got := map[string]bool{}
	for _, f := range emitted {
		if f.Path == "test-vectors/manifest.json" {
			continue
		}
		got[f.Path] = true
	}

	var missing, unexpected []string
	want := map[string]bool{}
	for _, p := range SuiteFiles {
		want[p] = true
		if !got[p] {
			missing = append(missing, p)
		}
	}
	for p := range got {
		if !want[p] {
			unexpected = append(unexpected, p)
		}
	}
	sort.Strings(missing)
	sort.Strings(unexpected)

	if len(missing) > 0 {
		return fmt.Errorf("TRS Sec. 18.4 suite is incomplete - a missing vector file is a FAILED GATE:\n  %s",
			strings.Join(missing, "\n  "))
	}
	if len(unexpected) > 0 {
		return fmt.Errorf("vector files emitted that TRS Sec. 18.4 does not prescribe (extend SuiteFiles deliberately):\n  %s",
			strings.Join(unexpected, "\n  "))
	}
	return nil
}
