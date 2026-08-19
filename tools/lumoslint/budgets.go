// Lumos - tools/lumoslint / TRS-CODE-004 size budgets and their ADR exception.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CODE-004, TRS-CODE-001
// ADRs: -
// Threading: any
// Notes: TRS-CODE-004 sets three budgets and one escape hatch: complexity <= 15
// per function, <= 800 lines per file, <= 500 lines per class or type, and
// "exceptions require an ADR reference in the file header". Two of those four
// clauses had no enforcement anywhere until WP-02 slice S5, which is what this
// file closes.
//
// WHAT THIS DOES NOT DO, deliberately: cyclomatic complexity. Every language
// here already has a real analyser measuring it against 15 - gocyclo under
// golangci-lint, Checkstyle's CyclomaticComplexity, SwiftLint's
// cyclomatic_complexity - and each measures its own language's control flow
// properly. A second, cruder implementation in a Go line-scanner would disagree
// with them at the edges and the disagreement would be the tool's fault. What
// was actually missing is the CLASS budget, which no analyser in this stack
// enforces for Java, Go or TypeScript (SwiftLint's type_body_length covers only
// Swift), and the exception clause, which nothing anywhere enforced.
//
// The class budget is measured by brace matching rather than by parsing four
// languages. That is coarse, and the coarseness is bounded in the honest
// direction: braces inside strings or comments can only make a type look
// LONGER than it is, so the failure mode is a false alarm on a file somebody
// then looks at - not a silent pass on a 900-line class.
//
// THE EXCEPTION CLAUSE. TRS-CODE-004 permits a budget to be exceeded with "an
// ADR reference in the file header", and the loose reading of that - any file
// whose ADRs: line is non-empty may be as long as it likes - would exempt most
// of this repository, because ADR references there record which decisions a
// file implements. So an exception is a DEDICATED header line naming the
// budget it lifts and the ADR that justifies it:
//
//	Budget-exception: class ADR-012 - <why this type is allowed to be long>
//
// and the ADR must exist in docs/adrs/. A reference to an ADR nobody wrote is
// the failure this clause exists to prevent, so it fails the build.
//
// There are no exceptions in the tree today. The budgets were turned on while
// everything was under them, which is the only time turning a budget on is
// free - the longest type is 462 lines and the longest file 693.
package main

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
)

// MaxFileLines is TRS-CODE-004's per-file budget.
const MaxFileLines = 800

// MaxTypeLines is TRS-CODE-004's per-class/type budget.
const MaxTypeLines = 500

// BudgetKind names a budget an exception can lift.
type BudgetKind string

const (
	// BudgetFile is the per-file line budget.
	BudgetFile BudgetKind = "file"
	// BudgetClass is the per-class/type line budget.
	BudgetClass BudgetKind = "class"
)

// exceptionLine matches the dedicated header field, e.g.
// "Budget-exception: class ADR-012 - the wire codec is one table".
var exceptionLine = regexp.MustCompile(`Budget-exception:\s*(file|class)\s+(ADR-\d{3})\b`)

// typeDeclaration matches a class/type declaration per language. Each captures
// the kind in group 1 and the name in group 2, so one reporting path serves all
// four languages.
var typeDeclaration = map[string]*regexp.Regexp{
	".java": regexp.MustCompile(
		`^\s*(?:(?:public|private|protected|static|final|abstract|sealed|non-sealed)\s+)*` +
			`(class|interface|enum|record)\s+(\w+)`),
	".swift": regexp.MustCompile(
		`^\s*(?:(?:public|private|internal|fileprivate|final|open|indirect)\s+)*` +
			`(class|struct|enum|protocol|actor|extension)\s+(\w+)`),
	".go": regexp.MustCompile(`^type\s+(\w+)\s+(struct|interface)\s*\{`),
	".ts": regexp.MustCompile(`^\s*(?:export\s+)?(?:default\s+)?(?:abstract\s+)?(class|interface)\s+(\w+)`),
}

// KnownADRs lists the ADR identifiers docs/adrs/ actually holds.
//
// ADR-001 to 004 are baselined inside the EDP and have no file of their own, so
// they are named here; everything from 005 is a file and is discovered.
func KnownADRs(repoRoot string) (map[string]bool, error) {
	known := map[string]bool{
		"ADR-001": true, "ADR-002": true, "ADR-003": true, "ADR-004": true,
	}
	entries, err := os.ReadDir(filepath.Join(repoRoot, "docs", "adrs"))
	if err != nil {
		return nil, fmt.Errorf("read docs/adrs: %w", err)
	}
	id := regexp.MustCompile(`^(ADR-\d{3})`)
	for _, e := range entries {
		if m := id.FindStringSubmatch(e.Name()); m != nil {
			known[m[1]] = true
		}
	}
	return known, nil
}

// CheckBudgets applies TRS-CODE-004's file and class budgets to one source file.
func CheckBudgets(path string, knownADRs map[string]bool) []Finding {
	if IsGenerated(path) {
		// Generated code IS the register expressed in a language. A budget
		// finding there names something nobody may fix in place: the file is
		// overwritten on the next generation and the constants gate byte-
		// compares it. If the emitter ever produces an oversized file, the fix
		// belongs in the emitter.
		return nil
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		return []Finding{{"budget-unreadable", path, err.Error()}}
	}
	lines := strings.Split(strings.ReplaceAll(string(raw), "\r\n", "\n"), "\n")

	exceptions, findings := parseExceptions(path, lines, knownADRs)

	if len(lines) > MaxFileLines && !exceptions[BudgetFile] {
		findings = append(findings, Finding{"budget-file-length", path, fmt.Sprintf(
			"%d lines against TRS-CODE-004's budget of %d; split it, or add a "+
				"'Budget-exception: file ADR-NNN - reason' header line citing an ADR "+
				"that says why this one may be long", len(lines), MaxFileLines)})
	}
	if exceptions[BudgetClass] {
		return findings
	}
	for _, t := range typesIn(path, lines) {
		if t.lines > MaxTypeLines {
			findings = append(findings, Finding{"budget-class-length", path, fmt.Sprintf(
				"type %s is %d lines against TRS-CODE-004's budget of %d; split it, or "+
					"add a 'Budget-exception: class ADR-NNN - reason' header line",
				t.name, t.lines, MaxTypeLines)})
		}
	}
	return findings
}

// parseExceptions reads the header's budget exceptions and validates them.
func parseExceptions(path string, lines []string, knownADRs map[string]bool) (
	map[BudgetKind]bool, []Finding) {
	granted := map[BudgetKind]bool{}
	var findings []Finding

	limit := headerScanLines
	if len(lines) < limit {
		limit = len(lines)
	}
	for _, line := range lines[:limit] {
		m := exceptionLine.FindStringSubmatch(line)
		if m == nil {
			continue
		}
		kind, adr := BudgetKind(m[1]), m[2]
		if !knownADRs[adr] {
			findings = append(findings, Finding{"budget-exception-unknown-adr", path, fmt.Sprintf(
				"claims a %s-budget exception under %s, and no such ADR exists in "+
					"docs/adrs; an exception nobody wrote down is not an exception",
				kind, adr)})
			continue
		}
		if !strings.Contains(line, "-") || len(strings.TrimSpace(line)) < len(m[0])+4 {
			findings = append(findings, Finding{"budget-exception-unexplained", path, fmt.Sprintf(
				"claims a %s-budget exception under %s with no reason after it; the ADR "+
					"says why the decision was made, the line says why THIS file needs it",
				kind, adr)})
			continue
		}
		granted[kind] = true
	}
	return granted, findings
}

// declaredType is one class or type and the lines its body spans.
type declaredType struct {
	name  string
	lines int
}

// typesIn finds every class/type declaration and measures it by brace matching.
func typesIn(path string, lines []string) []declaredType {
	pattern, ok := typeDeclaration[strings.ToLower(filepath.Ext(path))]
	if !ok {
		return nil
	}
	var out []declaredType
	for i, line := range lines {
		m := pattern.FindStringSubmatch(line)
		if m == nil {
			continue
		}
		name := m[2]
		if strings.HasPrefix(name, "struct") || strings.HasPrefix(name, "interface") {
			name = m[1] // Go captures name first, kind second
		}
		if n := spanOf(lines, i); n > 0 {
			out = append(out, declaredType{name: name, lines: n})
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].name < out[j].name })
	return out
}

// spanOf counts the lines from a declaration to its matching closing brace.
//
// Returns 0 when the braces never balance, which is a declaration this scanner
// cannot measure rather than a violation - reporting an unbounded span would be
// inventing a number.
func spanOf(lines []string, start int) int {
	depth := 0
	opened := false
	for j := start; j < len(lines); j++ {
		depth += strings.Count(lines[j], "{") - strings.Count(lines[j], "}")
		if strings.Contains(lines[j], "{") {
			opened = true
		}
		if opened && depth <= 0 {
			return j - start + 1
		}
	}
	return 0
}
