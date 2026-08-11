// Lumos - tools/vectorgen / Tests for the TRS Sec. 18.4 completeness gate.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-TST-001, TRS-PROTO-015
// ADRs: -
// Threading: any
// Notes: The gate is tested by REMOVING a file from a synthetic emission and
// requiring the failure. A completeness check that has only ever run against a
// complete suite is indistinguishable from one that always returns nil - the
// exact failure mode TRS Sec. 18.4's "a skipped file is a failed gate" guards.
package main

import (
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
)

func TestSuiteFilesMatchTRS184(t *testing.T) {
	// Transcribed independently from the TRS Sec. 18.4 table.
	want := []string{
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
	if len(SuiteFiles) != len(want) {
		t.Fatalf("SuiteFiles has %d entries, TRS Sec. 18.4 prescribes %d", len(SuiteFiles), len(want))
	}
	if !sort.StringsAreSorted(SuiteFiles) {
		t.Error("SuiteFiles must stay sorted for stable output")
	}
	for i := range want {
		if SuiteFiles[i] != want[i] {
			t.Errorf("SuiteFiles[%d] = %s, want %s", i, SuiteFiles[i], want[i])
		}
	}
}

func TestEmittedSuiteIsComplete(t *testing.T) {
	if err := assertSuiteComplete(buildOnce(t)); err != nil {
		t.Fatalf("emitted suite is not complete: %v", err)
	}
}

// TestGateFailsOnMissingFile is the negative half: drop each prescribed file in
// turn and require the gate to name it.
func TestGateFailsOnMissingFile(t *testing.T) {
	full := buildOnce(t)
	for _, drop := range SuiteFiles {
		t.Run(strings.TrimPrefix(drop, "test-vectors/"), func(t *testing.T) {
			var reduced []VectorFile
			for _, f := range full {
				if f.Path != drop {
					reduced = append(reduced, f)
				}
			}
			err := assertSuiteComplete(reduced)
			if err == nil {
				t.Fatalf("gate accepted a suite missing %s - a skipped file must be a FAILED GATE", drop)
			}
			if !strings.Contains(err.Error(), drop) {
				t.Errorf("gate failure does not name the missing file: %v", err)
			}
		})
	}
}

func TestGateFailsOnUnexpectedFile(t *testing.T) {
	full := append(buildOnce(t), VectorFile{Path: "test-vectors/rogue/extra.json", Content: []byte("{}")})
	err := assertSuiteComplete(full)
	if err == nil {
		t.Fatal("gate accepted an unprescribed vector file")
	}
	if !strings.Contains(err.Error(), "rogue") {
		t.Errorf("gate failure does not name the unexpected file: %v", err)
	}
}

// TestEveryPrescribedFileIsCommitted checks the working tree, not just the
// in-memory emission: a file that generation produces but nobody committed
// would still fail the S5 runners.
func TestEveryPrescribedFileIsCommitted(t *testing.T) {
	root := repoRoot(t)
	for _, p := range SuiteFiles {
		if _, err := os.Stat(filepath.Join(root, filepath.FromSlash(p))); err != nil {
			t.Errorf("prescribed vector file is not committed: %s (%v)", p, err)
		}
	}
}

// TestManifestListsTheWholeSuite keeps the index and the tree in lock-step.
func TestManifestListsTheWholeSuite(t *testing.T) {
	files := buildOnce(t)
	var manifestContent []byte
	for _, f := range files {
		if f.Path == "test-vectors/manifest.json" {
			manifestContent = f.Content
		}
	}
	if manifestContent == nil {
		t.Fatal("no manifest emitted")
	}
	body := string(manifestContent)
	for _, p := range SuiteFiles {
		if !strings.Contains(body, p) {
			t.Errorf("manifest does not list %s", p)
		}
	}
}
