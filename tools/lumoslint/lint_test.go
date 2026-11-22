// Lumos - tools/lumoslint / Tests for the header, constants and architecture lints.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CODE-001, TRS-CODE-003, TRS-CODE-005
// ADRs: ADR-004
// Threading: any
// Notes: Each rule is proven to FIRE on a bad fixture and to stay silent on a
// good one. The two false-positive shapes that made this scanner worth deferring
// in slice S2 - hand-transcribed values in tests, and percentage arithmetic - are
// pinned as explicit non-findings, because a lint that cries wolf is unenforced
// in practice however green it looks.
package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func write(t *testing.T, dir, name, body string) string {
	t.Helper()
	p := filepath.Join(dir, name)
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		t.Fatalf("mkdir: %v", err)
	}
	if err := os.WriteFile(p, []byte(body), 0o644); err != nil {
		t.Fatalf("write: %v", err)
	}
	return p
}

// readLinesForTest reads a file into lines for the budget tests.
func readLinesForTest(t *testing.T, path string) []string {
	t.Helper()
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}
	return strings.Split(strings.ReplaceAll(string(raw), "\r\n", "\n"), "\n")
}

func ruleSet(f []Finding) map[string]int {
	out := map[string]int{}
	for _, x := range f {
		out[x.Rule]++
	}
	return out
}

func repoRoot(t *testing.T) string {
	t.Helper()
	p, err := filepath.Abs(filepath.Join("..", ".."))
	if err != nil {
		t.Fatalf("resolve repo root: %v", err)
	}
	return p
}

const goodHeader = `// Lumos - x/y / A purpose.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-AAA-001
// ADRs: -
// Threading: any
// Notes: -
package x
`

// ---- the live tree ----

// TestRepositoryPassesEveryLint is the live assertion: the whole tree lints
// clean, which is what the CI gate runs.
func TestRepositoryPassesEveryLint(t *testing.T) {
	root := repoRoot(t)
	values, err := RegisterValues(root)
	if err != nil {
		t.Fatalf("RegisterValues: %v", err)
	}
	files, err := SourceFiles(root)
	if err != nil {
		t.Fatalf("SourceFiles: %v", err)
	}
	if len(files) < 50 {
		t.Fatalf("only %d source files found; the walk is not reaching the tree", len(files))
	}
	for _, p := range files {
		for _, f := range CheckHeader(p) {
			t.Errorf("header: %s", f)
		}
		for _, f := range CheckConstants(p, values) {
			t.Errorf("constants: %s", f)
		}
		for _, f := range CheckArchitecture(root, p) {
			t.Errorf("architecture: %s", f)
		}
	}
}

func TestRegisterValuesAreDistinctiveOnly(t *testing.T) {
	values, err := RegisterValues(repoRoot(t))
	if err != nil {
		t.Fatalf("RegisterValues: %v", err)
	}
	if len(values) == 0 {
		t.Fatal("no register values loaded; the scanner would police nothing")
	}
	for v := range values {
		if v < minDistinctiveValue {
			t.Errorf("value %d is below the distinctiveness floor and would generate noise", v)
		}
		if allowedCommon[v] {
			t.Errorf("value %d is on the common-value allow list and should have been excluded", v)
		}
	}
}

// ---- header ----

func TestHeaderRuleFiresAndStaysSilent(t *testing.T) {
	dir := t.TempDir()

	if f := CheckHeader(write(t, dir, "good.go", goodHeader)); len(f) != 0 {
		t.Errorf("a complete header must pass, got %v", f)
	}

	cases := map[string]string{
		"header-missing-module": "// Author: dhar.abhishek.email@gmail.com\n// Implements: X\n// ADRs: -\n// Threading: any\n// Notes: -\npackage x\n",
		"header-missing-author": "// Lumos - a / b\n// Implements: X\n// ADRs: -\n// Threading: any\n// Notes: -\npackage x\n",
	}
	for rule, body := range cases {
		t.Run(rule, func(t *testing.T) {
			if ruleSet(CheckHeader(write(t, dir, rule+".go", body)))[rule] == 0 {
				t.Errorf("expected %s to fire", rule)
			}
		})
	}

	t.Run("missing fields", func(t *testing.T) {
		body := "// Lumos - a / b\n// Author: dhar.abhishek.email@gmail.com\npackage x\n"
		got := ruleSet(CheckHeader(write(t, dir, "fields.go", body)))
		if got["header-missing-field"] < 3 {
			t.Errorf("Implements/ADRs/Threading/Notes should each fire, got %v", got)
		}
	})

	t.Run("wrong author is not accepted", func(t *testing.T) {
		body := strings.Replace(goodHeader, AuthorLine, "Author: someone.else@example.com", 1)
		if ruleSet(CheckHeader(write(t, dir, "author.go", body)))["header-missing-author"] != 1 {
			t.Error("TRS-CODE-001 mandates the exact author line")
		}
	})
}

// TestGeneratedMarkerNeedsAGenerator covers the generated branch, and the prose
// case that made an earlier version of this check flag itself.
func TestGeneratedMarkerNeedsAGenerator(t *testing.T) {
	dir := t.TempDir()

	generated := "// Lumos - a / b\n// Author: dhar.abhishek.email@gmail.com\n// Implements: X\n// ADRs: -\n// Threading: any\n// GENERATED - DO NOT EDIT. Generator: tools/thing\npackage x\n"
	if f := CheckHeader(write(t, dir, "gen.go", generated)); len(f) != 0 {
		t.Errorf("a generated header naming its generator must pass, got %v", f)
	}

	noGenerator := strings.Replace(generated, ". Generator: tools/thing", "", 1)
	if ruleSet(CheckHeader(write(t, dir, "nogen.go", noGenerator)))["header-generated-without-generator"] != 1 {
		t.Error("a generated file must name its generator")
	}

	// Prose that merely quotes the marker is not a declaration.
	prose := "// Lumos - a / b\n// Author: dhar.abhishek.email@gmail.com\n// Implements: X\n// ADRs: -\n// Threading: any\n// Notes: files carry \"GENERATED - DO NOT EDIT\" when generated.\npackage x\n"
	if f := CheckHeader(write(t, dir, "prose.go", prose)); len(f) != 0 {
		t.Errorf("quoting the marker in prose must not mark a file generated, got %v", f)
	}
}

// ---- constants ----

func TestConstantsRuleFiresOnProductionCode(t *testing.T) {
	dir := t.TempDir()
	values := map[int64][]string{1228: {"transport.QR_BOOTSTRAP_MAX_BYTES.default"}}

	bad := goodHeader + "\nconst limit = 1228\n"
	if ruleSet(CheckConstants(write(t, dir, "bad.go", bad), values))["hand-written-constant"] != 1 {
		t.Error("a duplicated register value in production code must fire")
	}

	good := goodHeader + "\nconst limit = lumosconstants.QRBootstrapMaxBytesDefault\n"
	if f := CheckConstants(write(t, dir, "good.go", good), values); len(f) != 0 {
		t.Errorf("consuming the generated symbol must pass, got %v", f)
	}

	t.Run("hex form is caught too", func(t *testing.T) {
		hexBad := goodHeader + "\nconst limit = 0x4CC\n" // 1228
		if ruleSet(CheckConstants(write(t, dir, "hex.go", hexBad), values))["hand-written-constant"] != 1 {
			t.Error("a hex literal duplicating a register value must fire")
		}
	})
}

// TestConstantsRuleExemptsTheKnownFalsePositives pins the two shapes that would
// otherwise make this scanner untrustworthy.
func TestConstantsRuleExemptsTheKnownFalsePositives(t *testing.T) {
	dir := t.TempDir()
	values := map[int64][]string{1228: {"transport.QR_BOOTSTRAP_MAX_BYTES.default"}}
	body := goodHeader + "\nconst limit = 1228\n"

	t.Run("test files transcribe values by design", func(t *testing.T) {
		if f := CheckConstants(write(t, dir, "thing_test.go", body), values); len(f) != 0 {
			t.Errorf("test code must be exempt: hand transcription is the verification, got %v", f)
		}
	})

	t.Run("generated files are the register", func(t *testing.T) {
		gen := "// Lumos - a / b\n// Author: dhar.abhishek.email@gmail.com\n// Implements: X\n// ADRs: -\n// Threading: any\n// GENERATED - DO NOT EDIT. Generator: g\nconst limit = 1228\n"
		if f := CheckConstants(write(t, dir, "gen.go", gen), values); len(f) != 0 {
			t.Errorf("generated code must be exempt, got %v", f)
		}
	})

	t.Run("an annotated site is suppressed with its reason visible", func(t *testing.T) {
		same := goodHeader + "\nconst limit = 1228 // " + AllowLiteralMarker + " - reason\n"
		if f := CheckConstants(write(t, dir, "same.go", same), values); len(f) != 0 {
			t.Errorf("same-line annotation must suppress, got %v", f)
		}
		above := goodHeader + "\n// " + AllowLiteralMarker + " - reason\nconst limit = 1228\n"
		if f := CheckConstants(write(t, dir, "above.go", above), values); len(f) != 0 {
			t.Errorf("preceding-line annotation must suppress, got %v", f)
		}
	})

	t.Run("values in comments and strings are not code", func(t *testing.T) {
		body := goodHeader + "\n// the bound is 1228 bytes\nconst msg = \"limit 1228\"\n"
		if f := CheckConstants(write(t, dir, "prose.go", body), values); len(f) != 0 {
			t.Errorf("prose and string literals must not fire, got %v", f)
		}
	})
}

// ---- architecture ----

func TestArchitectureRuleFiresOnForbiddenImports(t *testing.T) {
	dir := t.TempDir()

	bad := write(t, dir, "protocol/wire/x.go",
		goodHeader+"\nimport (\n\t\"github.com/dharabhishekorg/lumos/backend/store\"\n)\n")
	if ruleSet(CheckArchitecture(dir, bad))["forbidden-import"] != 1 {
		t.Error("protocol importing backend must fire (TRS Sec. 4.3)")
	}

	platform := write(t, dir, "protocol/adv/y.swift",
		goodHeader+"\nimport CoreBluetooth\n")
	if ruleSet(CheckArchitecture(dir, platform))["forbidden-import"] != 1 {
		t.Error("protocol importing a platform radio API must fire (TRS-ARCH-002)")
	}

	ok := write(t, dir, "protocol/wire/ok.go",
		goodHeader+"\nimport (\n\t\"github.com/dharabhishekorg/lumos/protocol/schema\"\n)\n")
	if f := CheckArchitecture(dir, ok); len(f) != 0 {
		t.Errorf("an in-layer import must pass, got %v", f)
	}

	unmapped := write(t, dir, "tools/x/z.go",
		goodHeader+"\nimport (\n\t\"github.com/dharabhishekorg/lumos/backend/store\"\n)\n")
	if f := CheckArchitecture(dir, unmapped); len(f) != 0 {
		t.Errorf("tools are not layer-constrained today, got %v", f)
	}
}

// TestPendingArchitectureRulesAreDeclared keeps the uncovered Sec. 4.3 clauses
// visible, so partial coverage is never mistaken for full coverage.
func TestPendingArchitectureRulesAreDeclared(t *testing.T) {
	if len(PendingRules) == 0 {
		t.Fatal("Sec. 4.3 has clauses with nothing to constrain yet; they must be declared, not omitted")
	}
	summary := PendingSummary()
	for _, r := range PendingRules {
		if !strings.Contains(summary, r) {
			t.Errorf("pending rule %q is missing from the summary", r)
		}
	}
}

func TestTheMinSdkRuleFiresInTheLibraryAndNowhereElse(t *testing.T) {
	root := t.TempDir()
	write := func(rel, body string) string {
		p := filepath.Join(root, filepath.FromSlash(rel))
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(p, []byte(body), 0o644); err != nil {
			t.Fatal(err)
		}
		return p
	}
	bad := "class A {\n  void f() {\n    out.writeBytes(b);\n    String h = HexFormat.of().formatHex(b);\n  }\n}\n"
	lib := write("android-app/protocol-java/src/main/java/com/x/A.java", bad)
	if got := CheckMinSdkApis(root, lib); len(got) != 2 {
		t.Fatalf("want two findings in the library, got %v", got)
	}
	app := write("android-app/app/src/main/java/com/x/A.java", bad)
	if got := CheckMinSdkApis(root, app); len(got) != 0 {
		t.Fatalf("the app module is Android Lint's, not this rule's: %v", got)
	}
	comment := write("android-app/protocol-java/src/main/java/com/x/B.java",
		"class B {\n  // HexFormat is API 34, so not here\n  /* writeBytes( either */\n}\n")
	if got := CheckMinSdkApis(root, comment); len(got) != 0 {
		t.Fatalf("a comment naming an API is not a use of it: %v", got)
	}
}

// TestTheJcaCurvesAreAllowedOnlyInTheJvmBackend pins WP-05 S7d's guard: the
// API-33 JCA crypto may appear in JcaCryptoBackend and nowhere else in the
// library, so the chat path cannot drift back onto it.
func TestTheJcaCurvesAreAllowedOnlyInTheJvmBackend(t *testing.T) {
	root := t.TempDir()
	write := func(rel, body string) string {
		p := filepath.Join(root, filepath.FromSlash(rel))
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(p, []byte(body), 0o644); err != nil {
			t.Fatal(err)
		}
		return p
	}
	jca := "class A {\n  void f() {\n    KeyAgreement.getInstance(\"XDH\");\n" +
		"    new XECPublicKeySpec(NamedParameterSpec.X25519, u);\n" +
		"    Signature.getInstance(\"Ed25519\");\n    Cipher.getInstance(\"ChaCha20-Poly1305\");\n  }\n}\n"
	elsewhere := write("android-app/protocol-java/src/main/java/com/lumos/protocol/x3dh/X3dh.java", jca)
	if got := CheckMinSdkApis(root, elsewhere); len(got) != 4 {
		t.Fatalf("want four findings outside the JVM backend, got %v", got)
	}
	backend := write("android-app/protocol-java/src/main/java/com/lumos/protocol/wire/JcaCryptoBackend.java", jca)
	if got := CheckMinSdkApis(root, backend); len(got) != 0 {
		t.Fatalf("the JVM backend is where these belong: %v", got)
	}
	still := write("android-app/protocol-java/src/main/java/com/lumos/protocol/wire/JcaCryptoBackend.java",
		"class B {\n  void f() {\n    out.writeBytes(b);\n  }\n}\n")
	if got := CheckMinSdkApis(root, still); len(got) != 1 {
		t.Fatalf("the backend still obeys the minSdk API rules: %v", got)
	}
}
