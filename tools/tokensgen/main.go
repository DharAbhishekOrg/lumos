// Lumos - tools/tokensgen / CLI: generate and verify the platform design tokens.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-UX-001, TRS-CICD-005
// ADRs: -
// Threading: main-safe
// Notes: -check is the CI gate, as for tools/constantsgen: it regenerates every
// file in memory and byte-compares with what is committed, so Android and iOS
// can never drift from the one token source, and a token edited by hand in a
// platform file fails the build.
package main

import (
	"bytes"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"sort"
)

func main() {
	root := flag.String("root", ".", "repository root")
	check := flag.Bool("check", false, "verify the generated files without writing (CI gate)")
	flag.Parse()
	if err := run(*root, *check); err != nil {
		fmt.Fprintln(os.Stderr, "tokensgen:", err)
		os.Exit(1)
	}
}

func run(root string, check bool) error {
	t, err := Load(filepath.Join(root, filepath.FromSlash(SourcePath)))
	if err != nil {
		return err
	}
	if failures := CheckContrast(t); len(failures) > 0 {
		for _, f := range failures {
			fmt.Fprintln(os.Stderr, "  "+f)
		}
		return fmt.Errorf("%d token pairs fail WCAG 2.2 AA", len(failures))
	}
	files := Emit(t)
	paths := make([]string, 0, len(files))
	for p := range files {
		paths = append(paths, p)
	}
	sort.Strings(paths)
	var stale []string
	for _, p := range paths {
		full := filepath.Join(root, filepath.FromSlash(p))
		if check {
			have, err := os.ReadFile(full) // #nosec G304 -- a generated path of this repository
			if err != nil || !bytes.Equal(have, files[p]) {
				stale = append(stale, p)
			}
			continue
		}
		if err := os.MkdirAll(filepath.Dir(full), 0o750); err != nil {
			return err
		}
		if err := os.WriteFile(full, files[p], 0o600); err != nil {
			return err
		}
	}
	if len(stale) > 0 {
		return fmt.Errorf("generated tokens are stale; run go run ./tools/tokensgen: %v", stale)
	}
	if check {
		fmt.Printf("token gate passed: %d colours, %d files current, every promised pair passes AA\n", len(t.Colors), len(paths))
	} else {
		fmt.Printf("wrote %d files from %s\n", len(paths), SourcePath)
	}
	return nil
}
