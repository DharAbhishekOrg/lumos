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
		if reg, err = restamp(registerPath, reg, hash); err != nil {
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

	model, files, err := emitAll(root, reg, hash)
	if err != nil {
		return err
	}

	if check {
		return verify(root, files)
	}
	if err := writeAll(root, files); err != nil {
		return err
	}
	fmt.Printf("generated %d files from %d constants (hash %s)\n", len(files), len(model.Constants), hash)
	return nil
}

// restamp writes the freshly computed hash back into the register and reloads
// it, so everything downstream reads the stamped file rather than the in-memory
// value it was derived from.
func restamp(registerPath string, reg *Register, hash string) (*Register, error) {
	updated, err := reg.WithHash(hash)
	if err != nil {
		return nil, err
	}
	if err := writeIfChanged(registerPath, updated); err != nil {
		return nil, err
	}
	fmt.Println("constants_hash:", hash)
	return LoadRegister(registerPath)
}

// emitAll builds the model and every generated artefact: the tri-language
// constant files, the wire codecs and their JVM counterparts. The model is
// returned alongside the files because the caller reports the constant count.
func emitAll(root string, reg *Register, hash string) (*Model, []File, error) {
	canonical, err := reg.CanonicalForm()
	if err != nil {
		return nil, nil, err
	}
	model, err := BuildModel(reg, hash)
	if err != nil {
		return nil, nil, err
	}
	files, err := Emit(reg, model, canonical)
	if err != nil {
		return nil, nil, err
	}
	wireIn, err := LoadWireInputs(root)
	if err != nil {
		return nil, nil, err
	}
	if err := ValidateWireCoverage(reg, model, wireIn); err != nil {
		return nil, nil, err
	}
	wireFiles, err := EmitWireFiles(reg, model, wireIn)
	if err != nil {
		return nil, nil, err
	}
	files = append(files, wireFiles...)
	jvmFiles, err := EmitJVMWireFiles(reg, model, wireIn)
	if err != nil {
		return nil, nil, err
	}
	return model, append(files, jvmFiles...), nil
}

// writeAll writes every generated artefact, creating parent directories.
func writeAll(root string, files []File) error {
	for _, f := range files {
		out := filepath.Join(root, filepath.FromSlash(f.Path))
		if err := os.MkdirAll(filepath.Dir(out), 0o755); err != nil {
			return fmt.Errorf("create %s: %w", filepath.Dir(out), err)
		}
		if err := writeIfChanged(out, f.Content); err != nil {
			return err
		}
	}
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
