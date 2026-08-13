// Lumos - tools/lumoslint / No-hand-written-constants scanner (TRS-CODE-005).
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CODE-005, TRS-CONST-001
// ADRs: ADR-004
// Threading: any
// Notes: ADR-004 says a literal duplicated constant is a defect. Deferred from
// slice S2 with a stated reason - there was no hand-written source to police, and
// a poorly-tuned scanner produces false positives that train reviewers to ignore
// it. That reason has expired now that the codecs exist.
//
// The scanner deliberately looks for a NARROW thing: a register value that is
// distinctive enough that its appearance as a bare literal is almost certainly a
// duplicated constant. Small numbers (0, 1, 2, 8, 16, 32...) appear everywhere
// for legitimate reasons - array indices, byte offsets, shifts - so flagging
// them would drown the real signal. A scanner nobody trusts enforces nothing.
package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
)

// AllowLiteralMarker suppresses one finding, with a written reason beside it.
const AllowLiteralMarker = "lumoslint:allow-literal"

// minDistinctiveValue is the floor below which a register value is too common in
// ordinary code to flag. 100 keeps genuinely distinctive values - 1228, 32768,
// 8388608, 5200 - while excluding the small integers every codec uses.
// lumoslint:allow-literal - this scanner's own threshold, not a protocol value.
const minDistinctiveValue = 100

// allowedCommon are values above the floor that are still ordinary in code and
// would otherwise generate noise: powers of two used for buffer sizes, common
// byte counts, and round time values.
var allowedCommon = map[int64]bool{
	128: true, 200: true, 255: true, 256: true, 512: true, 1000: true,
	1024: true, 2048: true, 4096: true, 8192: true, 16384: true,
	32768: true, 65536: true,
}

// RegisterValues loads the distinctive constant values worth policing, mapped to
// the register paths they came from.
func RegisterValues(repoRoot string) (map[int64][]string, error) {
	raw, err := os.ReadFile(filepath.Join(repoRoot, "protocol", "constants", "lumos_constants.json"))
	if err != nil {
		return nil, fmt.Errorf("read constants register: %w", err)
	}
	var doc map[string]any
	if err := json.Unmarshal(raw, &doc); err != nil {
		return nil, fmt.Errorf("parse constants register: %w", err)
	}

	out := map[int64][]string{}
	for group, gv := range doc {
		entries, ok := gv.(map[string]any)
		if !ok || strings.HasPrefix(group, "$") {
			continue
		}
		for name, ev := range entries {
			fields, ok := ev.(map[string]any)
			if !ok {
				continue
			}
			for field, v := range fields {
				n, ok := v.(float64)
				if !ok {
					continue
				}
				iv := int64(n)
				if float64(iv) != n {
					continue
				}
				if iv < minDistinctiveValue || allowedCommon[iv] {
					continue
				}
				path := group + "." + name + "." + field
				out[iv] = append(out[iv], path)
			}
		}
	}
	return out, nil
}

// numberPattern matches decimal and hex integer literals, with optional
// language suffixes (Go/Java/Swift), not preceded by a word character.
var numberPattern = regexp.MustCompile(`\b(0[xX][0-9a-fA-F]+|[0-9][0-9_]*)\b`)

// IsTestFile reports whether a path is test code.
//
// Test code is EXEMPT from this rule, and deliberately so. The register tests
// transcribe values by hand precisely so they do not agree with the artefact
// under test - goldenValues in constantsgen exists to catch a transcription slip
// that reading the JSON back could never catch. Flagging those literals would
// invert the rule's purpose and bury the real signal, which is the outcome that
// made this scanner worth deferring in slice S2 rather than shipping noisy.
//
// TRS-CODE-005 is about code that CONSUMES constants: application and protocol
// code must reference the generated symbol. That is what this check polices.
func IsTestFile(path string) bool {
	base := strings.ToLower(filepath.Base(path))
	switch {
	case strings.HasSuffix(base, "_test.go"):
		return true
	case strings.HasSuffix(base, "test.java"), strings.HasSuffix(base, "tests.java"):
		return true
	case strings.HasSuffix(base, "test.swift"), strings.HasSuffix(base, "tests.swift"):
		return true
	case strings.HasSuffix(base, ".test.ts"), strings.HasSuffix(base, ".spec.ts"):
		return true
	}
	dir := filepath.ToSlash(filepath.Dir(path))
	return strings.Contains(dir, "/Tests/") || strings.Contains(dir, "/src/test/")
}

// CheckConstants scans one source file for bare literals that duplicate a
// distinctive register value.
func CheckConstants(path string, values map[int64][]string) []Finding {
	if IsGenerated(path) {
		return nil // generated code IS the register, expressed in three languages
	}
	if IsTestFile(path) {
		return nil // see IsTestFile: hand-transcribed values are the verification
	}
	f, err := os.Open(path)
	if err != nil {
		return []Finding{{"constants-unreadable", path, err.Error()}}
	}
	// Read-only: nothing was buffered, so a Close error carries no lost data.
	defer func() { _ = f.Close() }()

	var out []Finding
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 0, 64*1024), 4*1024*1024)
	lineNo := 0
	previous := ""
	for sc.Scan() {
		lineNo++
		line := sc.Text()
		code := stripCommentAndStrings(line)
		// The escape hatch is honoured on the line itself or the line above, so a
		// long expression can carry its justification without wrapping. Either
		// way it leaves a visible trace at the site for a reviewer.
		suppressed := strings.Contains(line, AllowLiteralMarker) ||
			strings.Contains(previous, AllowLiteralMarker)
		previous = line
		if code == "" || suppressed {
			continue
		}
		for _, m := range numberPattern.FindAllString(code, -1) {
			v, err := parseIntLiteral(m)
			if err != nil {
				continue
			}
			paths, isRegisterValue := values[v]
			if !isRegisterValue {
				continue
			}
			out = append(out, Finding{
				Rule: "hand-written-constant",
				Path: fmt.Sprintf("%s:%d", path, lineNo),
				Detail: fmt.Sprintf("literal %s duplicates register value %s; consume the generated constant (ADR-004, TRS-CODE-005)",
					m, strings.Join(paths, " / ")),
			})
		}
	}
	return out
}

func parseIntLiteral(s string) (int64, error) {
	s = strings.ReplaceAll(s, "_", "")
	if strings.HasPrefix(s, "0x") || strings.HasPrefix(s, "0X") {
		return strconv.ParseInt(s[2:], 16, 64)
	}
	return strconv.ParseInt(s, 10, 64)
}

// stripCommentAndStrings removes string literals and trailing line comments, so
// a value mentioned in prose or in a test fixture's expected text is not flagged
// as code duplicating a constant.
func stripCommentAndStrings(line string) string {
	trimmed := strings.TrimSpace(line)
	for _, p := range []string{"//", "#", "*", "///"} {
		if strings.HasPrefix(trimmed, p) {
			return ""
		}
	}
	var b strings.Builder
	inStr := false
	var quote byte
	for i := 0; i < len(line); i++ {
		c := line[i]
		if inStr {
			if c == '\\' {
				i++
				continue
			}
			if c == quote {
				inStr = false
			}
			continue
		}
		switch c {
		case '"', '\'', '`':
			inStr = true
			quote = c
			continue
		case '/':
			if i+1 < len(line) && line[i+1] == '/' {
				return b.String()
			}
		}
		b.WriteByte(c)
	}
	return b.String()
}
