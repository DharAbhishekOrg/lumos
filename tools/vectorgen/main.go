// Lumos - tools/vectorgen / CLI: generate and verify the golden-vector suite.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-TST-001, TRS-CONST-005, TRS-PROTO-015
// ADRs: ADR-004
// Threading: main-safe
// Notes: Generation always runs the build TWICE and byte-compares before any
// file is written - the TRS Sec. 18.4 determinism self-check is a precondition
// of writing, not an optional mode. -check regenerates against the committed
// manifest's generator_commit and byte-compares every committed file, so CI
// fails on stale vectors without regenerating them.
package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"
)

func main() {
	var (
		root   = flag.String("root", ".", "repository root")
		check  = flag.Bool("check", false, "verify committed vectors match the generator (CI gate)")
		commit = flag.String("commit", "", "generator commit recorded in the manifest (defaults to the committed manifest's value)")
	)
	flag.Parse()
	if err := run(*root, *check, *commit); err != nil {
		fmt.Fprintln(os.Stderr, "vectorgen:", err)
		os.Exit(1)
	}
}

// committedCommit reads generator_commit from the committed manifest, so -check
// runs and plain regenerations do not churn the recorded provenance.
func committedCommit(root string) (string, error) {
	raw, err := os.ReadFile(filepath.Join(root, "test-vectors", "manifest.json"))
	if err != nil {
		return "", err
	}
	var m struct {
		GeneratorCommit string `json:"generator_commit"`
	}
	if err := json.Unmarshal(raw, &m); err != nil {
		return "", err
	}
	if m.GeneratorCommit == "" {
		return "", fmt.Errorf("committed manifest has no generator_commit")
	}
	return m.GeneratorCommit, nil
}

func run(root string, check bool, commit string) error {
	if commit == "" {
		fromDisk, err := committedCommit(root)
		if err != nil {
			return fmt.Errorf("no -commit given and no committed manifest to inherit from: %w", err)
		}
		commit = fromDisk
	}

	// Determinism self-check: two independent builds must agree byte-for-byte.
	first, err := Build(root, commit)
	if err != nil {
		return err
	}
	second, err := Build(root, commit)
	if err != nil {
		return err
	}
	if len(first) != len(second) {
		return fmt.Errorf("determinism self-check failed: %d vs %d files", len(first), len(second))
	}
	for i := range first {
		if first[i].Path != second[i].Path || !bytes.Equal(first[i].Content, second[i].Content) {
			return fmt.Errorf("determinism self-check failed: %s is not byte-identical across two builds", first[i].Path)
		}
	}

	if check {
		var stale []string
		for _, f := range first {
			committed, err := os.ReadFile(filepath.Join(root, filepath.FromSlash(f.Path)))
			if err != nil {
				stale = append(stale, f.Path+" (missing)")
				continue
			}
			if !bytes.Equal(committed, f.Content) {
				stale = append(stale, f.Path+" (differs)")
			}
		}
		if len(stale) > 0 {
			return fmt.Errorf("committed vectors are stale; run 'go run ./tools/vectorgen':\n  %s", joinLines(stale))
		}
		fmt.Printf("vector check passed: %d files match the generator (determinism self-check green)\n", len(first))
		return nil
	}

	for _, f := range first {
		out := filepath.Join(root, filepath.FromSlash(f.Path))
		if err := os.MkdirAll(filepath.Dir(out), 0o755); err != nil {
			return err
		}
		if existing, err := os.ReadFile(out); err == nil && bytes.Equal(existing, f.Content) {
			continue
		}
		if err := os.WriteFile(out, f.Content, 0o644); err != nil {
			return err
		}
	}
	fmt.Printf("generated %d vector files (determinism self-check green, commit %s)\n", len(first), commit)
	return nil
}

func joinLines(xs []string) string {
	var b bytes.Buffer
	for i, x := range xs {
		if i > 0 {
			b.WriteString("\n  ")
		}
		b.WriteString(x)
	}
	return b.String()
}
