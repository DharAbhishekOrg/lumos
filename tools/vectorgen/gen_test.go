// Lumos - tools/vectorgen / Tests for determinism, provenance and suite integrity.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-TST-001, TRS-CONST-005, TRS-PROTO-013, TRS-PROTO-014
// ADRs: ADR-004
// Threading: any
// Notes: The determinism test runs the full build twice and byte-compares -
// the same check generation performs before writing, asserted here so 'go test'
// alone proves it. The negative-code test cross-checks frame_invalid.json
// against the error registry, so a typo'd expected_error cannot ship.
package main

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/dharabhishekemail-ops/lumos/protocol/schema"
)

func repoRoot(t *testing.T) string {
	t.Helper()
	p, err := filepath.Abs(filepath.Join("..", ".."))
	if err != nil {
		t.Fatalf("resolve repo root: %v", err)
	}
	return p
}

func buildOnce(t *testing.T) []VectorFile {
	t.Helper()
	files, err := Build(repoRoot(t), "TEST")
	if err != nil {
		t.Fatalf("Build: %v", err)
	}
	return files
}

func TestDoubleGenerationIsByteIdentical(t *testing.T) {
	first := buildOnce(t)
	second := buildOnce(t)
	if len(first) != len(second) {
		t.Fatalf("file counts differ: %d vs %d", len(first), len(second))
	}
	for i := range first {
		if first[i].Path != second[i].Path {
			t.Errorf("file order unstable: %s vs %s", first[i].Path, second[i].Path)
		}
		if !bytes.Equal(first[i].Content, second[i].Content) {
			t.Errorf("%s is not byte-identical across two builds", first[i].Path)
		}
	}
}

// TestCommittedVectorsMatchGenerator is the CI staleness gate in test form.
// generator_commit is inherited from the committed manifest so the comparison
// is about content, not provenance stamps.
func TestCommittedVectorsMatchGenerator(t *testing.T) {
	root := repoRoot(t)
	commit, err := committedCommit(root)
	if err != nil {
		t.Fatalf("committed manifest: %v", err)
	}
	files, err := Build(root, commit)
	if err != nil {
		t.Fatalf("Build: %v", err)
	}
	for _, f := range files {
		committed, err := os.ReadFile(filepath.Join(root, filepath.FromSlash(f.Path)))
		if err != nil {
			t.Errorf("%s missing: %v (run: go run ./tools/vectorgen)", f.Path, err)
			continue
		}
		if !bytes.Equal(committed, f.Content) {
			t.Errorf("%s is stale (run: go run ./tools/vectorgen)", f.Path)
		}
	}
}

// TestEveryVectorFileEmbedsTheRegisterHash is the TRS-CONST-005 binding: a
// vector that does not carry the current register hash is drift, mechanically.
func TestEveryVectorFileEmbedsTheRegisterHash(t *testing.T) {
	root := repoRoot(t)
	reg, err := schema.LoadRegister(root)
	if err != nil {
		t.Fatalf("LoadRegister: %v", err)
	}
	want, err := reg.String("constants_hash")
	if err != nil {
		t.Fatalf("constants_hash: %v", err)
	}
	for _, f := range buildOnce(t) {
		var meta struct {
			ConstantsHash string `json:"constants_hash"`
			KeyProvenance string `json:"key_provenance"`
		}
		if err := json.Unmarshal(f.Content, &meta); err != nil {
			t.Errorf("%s: %v", f.Path, err)
			continue
		}
		if meta.ConstantsHash != want {
			t.Errorf("%s embeds hash %q, register says %q", f.Path, meta.ConstantsHash, want)
		}
		if !strings.HasPrefix(meta.KeyProvenance, "TESTONLY-") {
			t.Errorf("%s key_provenance %q is not TESTONLY- namespaced", f.Path, meta.KeyProvenance)
		}
	}
}

// TestNegativeVectorsUseRegisteredCodesOnly cross-checks frame_invalid.json
// against the stable registry.
func TestNegativeVectorsUseRegisteredCodesOnly(t *testing.T) {
	root := repoRoot(t)
	registry, err := schema.LoadErrorCodes(root)
	if err != nil {
		t.Fatalf("LoadErrorCodes: %v", err)
	}
	known := registry.Set()

	invalids, err := buildInvalids("sha256:test")
	if err != nil {
		t.Fatalf("buildInvalids: %v", err)
	}
	if len(invalids.Cases) < 10 {
		t.Fatalf("only %d negative cases; the S4a set has 12", len(invalids.Cases))
	}
	seen := map[string]bool{}
	for _, c := range invalids.Cases {
		if _, ok := known[c.ExpectedError]; !ok {
			t.Errorf("case %s expects %q, which is not in the stable registry", c.Name, c.ExpectedError)
		}
		seen[c.ExpectedError] = true
	}
	// Every framing-category code must be exercised by at least one negative.
	for _, c := range registry.Codes {
		if c.Category == "framing" && !seen[c.Code] {
			t.Errorf("framing error code %s has no negative vector exercising it", c.Code)
		}
	}
}

// TestCryptoVectorsPresentAndHashed asserts the four S4b crypto files are
// emitted, each carrying the register hash and TESTONLY provenance.
func TestCryptoVectorsPresentAndHashed(t *testing.T) {
	files := buildOnce(t)
	emitted := map[string]bool{}
	for _, f := range files {
		emitted[f.Path] = true
	}
	for _, want := range []string{
		"test-vectors/crypto/x3dh_kat.json",
		"test-vectors/crypto/ratchet_sequences.json",
		"test-vectors/crypto/aead_kat.json",
		"test-vectors/crypto/safety_number.json",
	} {
		if !emitted[want] {
			t.Errorf("S4b crypto vector %s was not emitted", want)
		}
	}
}

// TestRatchetNegativeUsesRegisteredCode cross-checks the MAX_SKIP breach code.
func TestRatchetNegativeUsesRegisteredCode(t *testing.T) {
	registry, err := schema.LoadErrorCodes(repoRoot(t))
	if err != nil {
		t.Fatalf("LoadErrorCodes: %v", err)
	}
	known := registry.Set()
	rf, err := buildRatchetSequences("sha256:test")
	if err != nil {
		t.Fatalf("buildRatchetSequences: %v", err)
	}
	if len(rf.Negatives) == 0 {
		t.Fatal("ratchet file has no negative case")
	}
	for _, neg := range rf.Negatives {
		if _, ok := known[neg.ExpectedError]; !ok {
			t.Errorf("ratchet negative %s expects %q, not in the registry", neg.Name, neg.ExpectedError)
		}
	}
}

func TestRoundtripCoversEveryRegisterClass(t *testing.T) {
	root := repoRoot(t)
	reg, err := schema.LoadRegister(root)
	if err != nil {
		t.Fatalf("LoadRegister: %v", err)
	}
	names, err := reg.MessageClassNames()
	if err != nil {
		t.Fatalf("MessageClassNames: %v", err)
	}
	rt, err := buildRoundtrips(reg, "sha256:test")
	if err != nil {
		t.Fatalf("buildRoundtrips: %v", err)
	}
	positive := map[string]bool{}
	for _, c := range rt.Cases {
		if !c.DecodeOnly {
			positive[c.Name] = true
		}
	}
	for _, name := range names {
		if !positive[name] {
			t.Errorf("class %s has no positive roundtrip case (TRS-PROTO-014)", name)
		}
	}
}

// TestManifestListsExactlyTheEmittedFiles keeps the manifest and the tree in
// lock-step; S4c extends this into the full Sec. 18.4 completeness gate.
func TestManifestListsExactlyTheEmittedFiles(t *testing.T) {
	files := buildOnce(t)
	var man struct {
		GeneratorCommit string `json:"generator_commit"`
		Files           []struct {
			Path string `json:"path"`
		} `json:"files"`
	}
	var manifestContent []byte
	listed := map[string]bool{}
	for _, f := range files {
		if f.Path == "test-vectors/manifest.json" {
			manifestContent = f.Content
			continue
		}
		listed[f.Path] = false
	}
	if manifestContent == nil {
		t.Fatal("no manifest emitted")
	}
	if err := json.Unmarshal(manifestContent, &man); err != nil {
		t.Fatalf("parse manifest: %v", err)
	}
	if man.GeneratorCommit != "TEST" {
		t.Errorf("generator_commit = %q, want the value passed to Build", man.GeneratorCommit)
	}
	for _, f := range man.Files {
		if _, ok := listed[f.Path]; !ok {
			t.Errorf("manifest lists %s, which was not emitted", f.Path)
			continue
		}
		listed[f.Path] = true
	}
	for path, ok := range listed {
		if !ok {
			t.Errorf("emitted file %s is not listed in the manifest", path)
		}
	}
}
