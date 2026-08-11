// Lumos - tools/constantsgen / CLI: stamp, generate and verify the constants register.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CONST-001, TRS-CONST-003, TRS-CONST-005, TRS-REP-002, TRS-CICD-005
// ADRs: ADR-002, ADR-004
// Threading: main-safe
// Notes: -check is the CI gate. It recomputes the hash and regenerates every
// artefact in memory, then byte-compares against what is committed. Drift between
// the register and generated code therefore fails the build rather than being
// discovered later as a cross-platform interop bug.
package main

import (
	"bytes"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"sort"
)

// RegisterPath is the master register, relative to the repository root.
const RegisterPath = "protocol/constants/lumos_constants.json"

func main() {
	var (
		root  = flag.String("root", ".", "repository root")
		check = flag.Bool("check", false, "verify the committed hash and generated files without writing (CI gate)")
		stamp = flag.Bool("stamp", false, "recompute constants_hash and write it into the register")
	)
	flag.Parse()

	if err := run(*root, *check, *stamp); err != nil {
		fmt.Fprintln(os.Stderr, "constantsgen:", err)
		os.Exit(1)
	}
}

func run(root string, check, stamp bool) error {
	registerPath := filepath.Join(root, filepath.FromSlash(RegisterPath))
	reg, err := LoadRegister(registerPath)
	if err != nil {
		return err
	}

	hash, err := reg.ConstantsHash()
	if err != nil {
		return err
	}

	if stamp {
		updated, err := reg.WithHash(hash)
		if err != nil {
			return err
		}
		if err := writeIfChanged(registerPath, updated); err != nil {
			return err
		}
		fmt.Println("constants_hash:", hash)
		reg, err = LoadRegister(registerPath)
		if err != nil {
			return err
		}
	}

	declared, err := reg.DeclaredHash()
	if err != nil {
		return err
	}
	if declared != hash {
		return fmt.Errorf("constants_hash is stale: register declares %q but its content hashes to %q; run 'go run ./tools/constantsgen -stamp'", declared, hash)
	}

	canonical, err := reg.CanonicalForm()
	if err != nil {
		return err
	}
	model, err := BuildModel(reg, hash)
	if err != nil {
		return err
	}
	files, err := Emit(reg, model, canonical)
	if err != nil {
		return err
	}

	if check {
		return verify(root, files)
	}
	for _, f := range files {
		out := filepath.Join(root, filepath.FromSlash(f.Path))
		if err := os.MkdirAll(filepath.Dir(out), 0o755); err != nil {
			return fmt.Errorf("create %s: %w", filepath.Dir(out), err)
		}
		if err := writeIfChanged(out, f.Content); err != nil {
			return err
		}
	}
	fmt.Printf("generated %d files from %d constants (hash %s)\n", len(files), len(model.Constants), hash)
	return nil
}

// verify byte-compares every generated artefact against what is on disk.
func verify(root string, files []File) error {
	var stale []string
	for _, f := range files {
		out := filepath.Join(root, filepath.FromSlash(f.Path))
		got, err := os.ReadFile(out)
		if err != nil {
			stale = append(stale, f.Path+" (missing)")
			continue
		}
		if !bytes.Equal(got, f.Content) {
			stale = append(stale, f.Path+" (differs)")
		}
	}
	if len(stale) > 0 {
		sort.Strings(stale)
		return fmt.Errorf("generated files are out of date; run 'go run ./tools/constantsgen':\n  %s",
			joinLines(stale))
	}
	fmt.Printf("constants check passed: %d artefacts match the register\n", len(files))
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

// writeIfChanged avoids rewriting identical bytes so repeated generation does not
// churn file modification times.
func writeIfChanged(path string, content []byte) error {
	if existing, err := os.ReadFile(path); err == nil && bytes.Equal(existing, content) {
		return nil
	}
	if err := os.WriteFile(path, content, 0o644); err != nil {
		return fmt.Errorf("write %s: %w", path, err)
	}
	return nil
}
