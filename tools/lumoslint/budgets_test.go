// Lumos - tools/lumoslint / Tests for the TRS-CODE-004 budgets and their exception.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CODE-004
// ADRs: -
// Threading: any
// Notes: Both budgets are proven to FIRE on a fixture that breaks them and to
// stay silent on one that does not, in every language the repository owns -
// because a size gate that silently measures nothing is the easiest kind of
// green to ship, and this one was turned on while the whole tree was already
// under budget, which is exactly when a broken measurement would go unnoticed.
//
// The exception clause gets the same treatment from both sides: a well-formed
// exception must lift the budget, and the two malformed shapes - an ADR nobody
// wrote and a citation with no reason - must not.
package main

import (
	"fmt"
	"path/filepath"
	"strings"
	"testing"
)

// adrsForTest is the ADR set the real repository has, loaded once per test.
func adrsForTest(t *testing.T) map[string]bool {
	t.Helper()
	known, err := KnownADRs(repoRoot(t))
	if err != nil {
		t.Fatalf("load ADRs: %v", err)
	}
	return known
}

// longBody returns n lines of filler that contain no braces.
func longBody(n int) string {
	return strings.Repeat("        // filler\n", n)
}

// TestTheRepositoryIsWithinBudget is the live gate. It duplicates the sweep in
// TestRepositoryPassesEveryLint deliberately: this one names the rule, so a
// failure reads as a budget failure rather than as "some lint fired".
func TestTheRepositoryIsWithinBudget(t *testing.T) {
	root := repoRoot(t)
	known := adrsForTest(t)
	files, err := SourceFiles(root)
	if err != nil {
		t.Fatalf("walk: %v", err)
	}
	for _, p := range files {
		for _, f := range CheckBudgets(p, known) {
			t.Errorf("%s", f)
		}
	}
}

// TestTheClassBudgetFiresInEveryLanguage is the check that matters most: no
// analyser in this stack enforces a class budget for Java, Go or TypeScript, so
// if this measurement is wrong nothing else catches it.
func TestTheClassBudgetFiresInEveryLanguage(t *testing.T) {
	known := adrsForTest(t)
	cases := map[string]string{
		"Big.java":  "/*\n * Author: x\n * Implements: x\n * ADRs: -\n * Threading: any\n */\npublic final class Big {\n%s}\n",
		"Big.swift": "/// Author: x\n/// Implements: x\n/// ADRs: -\n/// Threading: any\npublic struct Big {\n%s}\n",
		"big.go":    "// Author: x\n// Implements: x\n// ADRs: -\n// Threading: any\npackage p\n\ntype Big struct {\n%s}\n",
		"big.ts":    "// Author: x\n// Implements: x\n// ADRs: -\n// Threading: any\nexport class Big {\n%s}\n",
	}
	for name, shape := range cases {
		dir := t.TempDir()

		over := write(t, dir, name, fmt.Sprintf(shape, longBody(MaxTypeLines+10)))
		if got := ruleSet(CheckBudgets(over, known))["budget-class-length"]; got != 1 {
			t.Errorf("%s: an over-budget type was not reported (got %d)", name, got)
		}

		under := write(t, dir, "ok-"+name, fmt.Sprintf(shape, longBody(10)))
		if got := ruleSet(CheckBudgets(under, known))["budget-class-length"]; got != 0 {
			t.Errorf("%s: a small type was reported (got %d)", name, got)
		}
	}
}

// TestTheFileBudgetFires covers the other half. Checkstyle and SwiftLint already
// enforce it for Java and Swift; nothing did for Go or TypeScript.
func TestTheFileBudgetFires(t *testing.T) {
	known := adrsForTest(t)
	dir := t.TempDir()
	header := "// Author: x\n// Implements: x\n// ADRs: -\n// Threading: any\npackage p\n"

	over := write(t, dir, "huge.go", header+strings.Repeat("// filler\n", MaxFileLines+5))
	if got := ruleSet(CheckBudgets(over, known))["budget-file-length"]; got != 1 {
		t.Errorf("an over-budget file was not reported (got %d)", got)
	}
	under := write(t, dir, "small.go", header+strings.Repeat("// filler\n", 20))
	if got := ruleSet(CheckBudgets(under, known))["budget-file-length"]; got != 0 {
		t.Errorf("a small file was reported (got %d)", got)
	}
}

// TestAWellFormedExceptionLiftsTheBudget proves the escape hatch opens.
func TestAWellFormedExceptionLiftsTheBudget(t *testing.T) {
	known := adrsForTest(t)
	dir := t.TempDir()

	body := "// Author: x\n// Implements: x\n// ADRs: -\n// Threading: any\n" +
		"// Budget-exception: class ADR-005 - the safety-number alphabet is one frozen table\n" +
		"package p\n\ntype Big struct {\n" + longBody(MaxTypeLines+10) + "}\n"
	p := write(t, dir, "excepted.go", body)
	if got := ruleSet(CheckBudgets(p, known))["budget-class-length"]; got != 0 {
		t.Errorf("a cited exception did not lift the class budget (got %d)", got)
	}

	// And it lifts only the budget it names.
	fileBody := "// Author: x\n// Implements: x\n// ADRs: -\n// Threading: any\n" +
		"// Budget-exception: class ADR-005 - only the class budget\n" +
		"package p\n" + strings.Repeat("// filler\n", MaxFileLines+5)
	q := write(t, dir, "still-too-long.go", fileBody)
	if got := ruleSet(CheckBudgets(q, known))["budget-file-length"]; got != 1 {
		t.Errorf("a class exception wrongly lifted the file budget (got %d)", got)
	}
}

// TestAnUnwritableExceptionIsRefused is the clause's whole point. "Exceptions
// require an ADR reference" means nothing if the reference can be invented.
func TestAnUnwritableExceptionIsRefused(t *testing.T) {
	known := adrsForTest(t)
	dir := t.TempDir()

	ghost := "// Author: x\n// Implements: x\n// ADRs: -\n// Threading: any\n" +
		"// Budget-exception: class ADR-999 - an ADR nobody wrote\n" +
		"package p\n\ntype Big struct {\n" + longBody(MaxTypeLines+10) + "}\n"
	p := write(t, dir, "ghost.go", ghost)
	rules := ruleSet(CheckBudgets(p, known))
	if rules["budget-exception-unknown-adr"] != 1 {
		t.Errorf("an exception citing a non-existent ADR was accepted: %v", rules)
	}
	if rules["budget-class-length"] != 1 {
		t.Errorf("a refused exception must not still lift the budget: %v", rules)
	}

	bare := "// Author: x\n// Implements: x\n// ADRs: -\n// Threading: any\n" +
		"// Budget-exception: class ADR-005\n" +
		"package p\n\ntype Big struct {\n" + longBody(MaxTypeLines+10) + "}\n"
	q := write(t, dir, "bare.go", bare)
	rules = ruleSet(CheckBudgets(q, known))
	if rules["budget-exception-unexplained"] != 1 {
		t.Errorf("an exception with no reason was accepted: %v", rules)
	}
}

// TestGeneratedFilesAreExempt: a budget finding in emitted code names something
// nobody can fix in place.
func TestGeneratedFilesAreExempt(t *testing.T) {
	known := adrsForTest(t)
	dir := t.TempDir()
	body := "// " + GeneratedMarker + "\npackage p\n\ntype Big struct {\n" +
		longBody(MaxTypeLines+10) + "}\n"
	p := write(t, dir, "generated.go", body)
	if got := CheckBudgets(p, known); len(got) != 0 {
		t.Errorf("generated code was held to the budget: %v", got)
	}
}

// TestTheMeasurementIsNotVacuous guards the brace matcher. A regex that matched
// nothing would make every assertion above pass for the wrong reason, and the
// budgets were switched on while the tree was already compliant - the one
// moment a measurement of zero is indistinguishable from a measurement of
// nothing.
func TestTheMeasurementIsNotVacuous(t *testing.T) {
	root := repoRoot(t)
	// A known long type, measured by hand at the time of writing: the Java
	// conformance suite's CryptoConformanceTest is the longest in the tree.
	path := filepath.Join(root, filepath.FromSlash(
		"android-app/protocol-conformance/src/test/java/com/lumos/protocol/conformance/CryptoConformanceTest.java"))
	lines := readLinesForTest(t, path)
	types := typesIn(path, lines)
	if len(types) == 0 {
		t.Fatalf("no types parsed from %s; the declaration regex matches nothing", path)
	}
	var found bool
	for _, ty := range types {
		if ty.name == "CryptoConformanceTest" {
			found = true
			if ty.lines < 300 || ty.lines > MaxTypeLines {
				t.Errorf("CryptoConformanceTest measured %d lines; expected a few hundred, "+
					"under the budget - if this has genuinely grown past %d it needs "+
					"splitting rather than a looser test", ty.lines, MaxTypeLines)
			}
		}
	}
	if !found {
		t.Error("the class declaration itself was not found")
	}

	// And every language's declaration pattern must match its own shape, or a
	// whole language silently goes unmeasured.
	for ext, shape := range map[string]string{
		".java":  "public final class A {\n}\n",
		".swift": "public struct A {\n}\n",
		".go":    "type A struct {\n}\n",
		".ts":    "export class A {\n}\n",
	} {
		if got := typesIn("x"+ext, strings.Split(shape, "\n")); len(got) != 1 {
			t.Errorf("%s: declaration pattern matched %d types, want 1", ext, len(got))
		}
	}
}

// TestKnownADRsFindsTheRealSet keeps the exception clause honest at the other
// end: if this returned an empty set every citation would be refused, and if it
// returned everything none would be.
func TestKnownADRsFindsTheRealSet(t *testing.T) {
	known := adrsForTest(t)
	for _, id := range []string{"ADR-001", "ADR-003", "ADR-005", "ADR-010", "ADR-011"} {
		if !known[id] {
			t.Errorf("%s exists and was not found", id)
		}
	}
	for _, id := range []string{"ADR-000", "ADR-099", "ADR-999"} {
		if known[id] {
			t.Errorf("%s does not exist and was accepted", id)
		}
	}
}
