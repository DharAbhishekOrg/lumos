// Lumos - tools/tokensgen / Tests: resolution, the contrast gate and the drift gate.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-UX-001, SRS-NFR-A11Y-002
// ADRs: -
// Threading: test-only
// Notes: Each failure mode the generator exists to stop is shown being stopped:
// an unreadable pair, a malformed or dangling colour, and a platform file
// edited by hand.
package main

import (
	"bytes"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func repoRoot(t *testing.T) string {
	t.Helper()
	_, file, _, _ := runtime.Caller(0)
	return filepath.Join(filepath.Dir(file), "..", "..")
}

func committed(t *testing.T) []byte {
	t.Helper()
	data, err := os.ReadFile(filepath.Join(repoRoot(t), filepath.FromSlash(SourcePath)))
	if err != nil {
		t.Fatal(err)
	}
	return data
}

func TestTheCommittedTokensKeepEveryPromisedPairReadableInBothThemes(t *testing.T) {
	tok, err := Parse(committed(t))
	if err != nil {
		t.Fatal(err)
	}
	if f := CheckContrast(tok); len(f) != 0 {
		t.Fatalf("promised pairs fail AA: %v", f)
	}
	if len(PromisedPairs()) < 41 {
		t.Fatalf("only %d promised pairs: the list has lost its coverage", len(PromisedPairs()))
	}
}

func TestAnUnreadableTokenIsRefusedNamingThemeAndPair(t *testing.T) {
	// Daylight's subtle ink lightened until captions would be unreadable.
	data := bytes.Replace(committed(t), []byte(`"light": "#5E637F"`), []byte(`"light": "#A0A3B5"`), 1)
	tok, err := Parse(data)
	if err != nil {
		t.Fatal(err)
	}
	f := CheckContrast(tok)
	if len(f) == 0 || !strings.Contains(strings.Join(f, "\n"), "Daylight: ink-subtle on surface") {
		t.Fatalf("a caption colour at about 2.5:1 must be refused, got %v", f)
	}
	for _, line := range f {
		if strings.HasPrefix(line, "Midnight") {
			t.Fatalf("only Daylight changed, yet Midnight was blamed: %s", line)
		}
	}
}

func TestColoursResolveAndAnythingAmbiguousIsAnError(t *testing.T) {
	tok, err := Parse(committed(t))
	if err != nil {
		t.Fatal(err)
	}
	by := map[string]Color{}
	for _, c := range tok.Colors {
		by[c.Name] = c
	}
	if by["tick"].Midnight != by["ink-subtle"].Midnight || by["tick"].Daylight != by["ink-subtle"].Daylight {
		t.Fatal("tick aliases ink-subtle in both themes")
	}
	if a := by["scrim"].Midnight.A; a != 163 {
		t.Fatalf("rgba(…, 0.64) must keep its alpha as 163/255, got %d", a)
	}
	base := string(committed(t))
	for name, bad := range map[string]string{
		"a named colour":     `"dark": "red"`,
		"a CSS variable":     `"dark": "var(--x)"`,
		"a dangling alias":   `"dark": "{nowhere}"`,
		"a short hex":        `"dark": "#FFF"`,
		"a channel over 255": `"dark": "rgba(300, 0, 0, 0.5)"`,
	} {
		data := strings.Replace(base, `"dark": "#0A0D1A"`, bad, 1)
		if data == base {
			t.Fatal("fixture did not apply")
		}
		if _, err := Parse([]byte(data)); err == nil {
			t.Errorf("%s must be refused, not guessed at", name)
		}
	}
}

func TestTheCommittedPlatformFilesAreCurrent(t *testing.T) {
	if err := run(repoRoot(t), true); err != nil {
		t.Fatalf("run go run ./tools/tokensgen and commit the result: %v", err)
	}
}

func TestAPlatformFileEditedByHandFailsTheGate(t *testing.T) {
	root := t.TempDir()
	src := filepath.Join(root, filepath.FromSlash(SourcePath))
	if err := os.MkdirAll(filepath.Dir(src), 0o750); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(src, committed(t), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := run(root, false); err != nil {
		t.Fatal(err)
	}
	if err := run(root, true); err != nil {
		t.Fatalf("freshly generated files must pass: %v", err)
	}
	night := filepath.Join(root, filepath.FromSlash(AndroidNightPath))
	data, err := os.ReadFile(night) // #nosec G304 -- a temp copy this test wrote
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(night, bytes.Replace(data, []byte("#FF0A0D1A"), []byte("#FF000000"), 1), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := run(root, true); err == nil || !strings.Contains(err.Error(), AndroidNightPath) {
		t.Fatalf("a hand-edited colour must fail the gate and name the file, got %v", err)
	}
}

func TestOutputIsByteDeterministic(t *testing.T) {
	tok, err := Parse(committed(t))
	if err != nil {
		t.Fatal(err)
	}
	a, b := Emit(tok), Emit(tok)
	for p := range a {
		if !bytes.Equal(a[p], b[p]) {
			t.Fatalf("%s differs between two runs", p)
		}
	}
}
