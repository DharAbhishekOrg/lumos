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
package main

import (
	"fmt"
	"sort"
	"strings"
)

// SuiteFiles is the TRS Sec. 18.4 golden-vector tree, transcribed by hand from
// the specification table. manifest.json is excluded: it is the index of this
// set, not a member of it.
var SuiteFiles = []string{
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
