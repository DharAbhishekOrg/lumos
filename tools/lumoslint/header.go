// Lumos - tools/lumoslint / Source-file header lint (TRS Sec. 24.1).
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CODE-001, TRS-CODE-002
// ADRs: -
// Threading: any
// Notes: TRS Sec. 24.1 accepts three comment forms - block, /// and package
// comment - but requires every field in all of them. Generated files carry
// "GENERATED - DO NOT EDIT" plus the generator name INSTEAD OF Notes, so the
// check branches on that rather than demanding a Notes line no generator would
// write. The point is a header that is true, not a header that is present.
package main

import (
	"bufio"
	"fmt"
	"os"
	"strings"
)

// RequiredHeaderFields are the TRS Sec. 24.1 fields every source file declares.
var RequiredHeaderFields = []string{"Author:", "Implements:", "ADRs:", "Threading:"}

// AuthorLine is the exact author TRS-CODE-001 mandates.
const AuthorLine = "Author: dhar.abhishek.email@gmail.com"

// GeneratedMarker replaces the Notes field in generated files.
const GeneratedMarker = "GENERATED - DO NOT EDIT"

// headerScanLines is how far into a file the header must appear. A header
// pushed below this is not serving its purpose as the first thing a reader sees.
const headerScanLines = 30

// CheckHeader validates one source file's header.
func CheckHeader(path string) []Finding {
	f, err := os.Open(path)
	if err != nil {
		return []Finding{{"header-unreadable", path, err.Error()}}
	}
	defer f.Close()

	var head []string
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	for i := 0; i < headerScanLines && sc.Scan(); i++ {
		head = append(head, sc.Text())
	}
	block := strings.Join(head, "\n")

	var out []Finding
	if !strings.Contains(block, "Lumos - ") {
		out = append(out, Finding{"header-missing-module", path,
			"no 'Lumos - <module> / <purpose>' line in the first " + fmt.Sprint(headerScanLines) + " lines"})
	}
	if !strings.Contains(block, AuthorLine) {
		out = append(out, Finding{"header-missing-author", path, "missing " + AuthorLine})
	}
	for _, field := range RequiredHeaderFields {
		if field == "Author:" {
			continue // covered above, with its exact value
		}
		if !strings.Contains(block, field) {
			out = append(out, Finding{"header-missing-field", path, "missing " + field})
		}
	}

	generated := declaresGenerated(head)
	if generated {
		if !strings.Contains(block, "Generator:") {
			out = append(out, Finding{"header-generated-without-generator", path,
				"generated file must name its generator (TRS Sec. 24.1)"})
		}
	} else if !strings.Contains(block, "Notes:") {
		out = append(out, Finding{"header-missing-field", path, "missing Notes:"})
	}
	return out
}

// declaresGenerated reports whether a header line DECLARES the file generated.
//
// The marker must begin the comment's text, which is how every generator writes
// it. Merely mentioning the marker - as this file's own header does when
// explaining the rule - is prose, not a declaration, and an earlier version of
// this check could not tell the difference and flagged itself.
func declaresGenerated(lines []string) bool {
	for _, l := range lines {
		t := strings.TrimSpace(l)
		for _, prefix := range []string{"///", "//", "/*", "*", "#"} {
			if !strings.HasPrefix(t, prefix) {
				continue
			}
			if strings.HasPrefix(strings.TrimSpace(strings.TrimPrefix(t, prefix)), GeneratedMarker) {
				return true
			}
			break
		}
	}
	return false
}

// IsGenerated reports whether a file declares itself generated.
func IsGenerated(path string) bool {
	raw, err := os.ReadFile(path)
	if err != nil {
		return false
	}
	head := string(raw)
	if len(head) > 4096 {
		head = head[:4096]
	}
	return declaresGenerated(strings.Split(head, "\n"))
}
