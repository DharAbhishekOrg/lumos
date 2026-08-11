// Lumos - tools/lumoslint / Architecture lint (TRS Sec. 4.3, TRS-CODE-003).
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CODE-003, TRS-ARCH-001, TRS-ARCH-002
// ADRs: -
// Threading: any
// Notes: TRS Sec. 4.3 makes a violating import a build failure, not a review
// comment. The rules below cover the layers that EXIST today; the domain and
// adapter rules of Sec. 4.3 have nothing to constrain yet and are listed as
// pending rather than silently omitted, so the gap is visible in the tool's own
// output instead of being mistaken for coverage.
package main

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
)

// ImportRule forbids one directed dependency between module prefixes.
type ImportRule struct {
	// From is the repository-relative prefix the rule applies to.
	From string
	// ForbiddenSubstrings are import-path fragments that must not appear.
	ForbiddenSubstrings []string
	// Why states the Sec. 4.3 clause being enforced.
	Why string
}

// ImportRules are the layering rules enforceable against today's tree.
var ImportRules = []ImportRule{
	{
		From:                "protocol/",
		ForbiddenSubstrings: []string{"lumos/backend", "lumos/admin-console", "lumos/tools"},
		Why:                 "protocol and security libraries have no application or tooling dependencies (TRS Sec. 4.3)",
	},
	{
		From:                "backend/",
		ForbiddenSubstrings: []string{"lumos/admin-console", "lumos/tools/"},
		Why:                 "the backend does not depend on the admin console or build tooling (TRS Sec. 4.3)",
	},
	{
		From: "protocol/",
		// Nothing under protocol/ may reach a platform radio, billing, push,
		// camera or key-store API: those live behind adapters (TRS-ARCH-002).
		ForbiddenSubstrings: []string{"android.bluetooth", "CoreBluetooth", "StoreKit", "android.security.keystore"},
		Why:                 "domain and protocol modules do not import platform Bluetooth, billing, push, camera or key-store APIs (TRS-ARCH-002)",
	},
}

// PendingRules records Sec. 4.3 clauses with nothing to constrain yet. They are
// named so their absence reads as scope, not as coverage.
var PendingRules = []string{
	"presentation -> domain interfaces only (no presentation layer until WP-02)",
	"domain -> repositories, crypto and transport contracts only (no domain modules yet)",
	"platform adapters -> platform APIs and contract implementations (no adapters until WP-03)",
	"admin configuration models -> shared schemas, not mobile executable code (admin shell only)",
}

var (
	goImport    = regexp.MustCompile(`^\s*(?:[\w.]+\s+)?"([^"]+)"\s*$`)
	javaImport  = regexp.MustCompile(`^\s*import\s+(?:static\s+)?([\w.]+)\s*;`)
	swiftImport = regexp.MustCompile(`^\s*import\s+([\w.]+)`)
	tsImport    = regexp.MustCompile(`(?:from\s+|require\()\s*['"]([^'"]+)['"]`)
)

// CheckArchitecture validates one file's imports against the layering rules.
func CheckArchitecture(repoRoot, path string) []Finding {
	rel, err := filepath.Rel(repoRoot, path)
	if err != nil {
		return nil
	}
	rel = filepath.ToSlash(rel)

	var applicable []ImportRule
	for _, r := range ImportRules {
		if strings.HasPrefix(rel, r.From) {
			applicable = append(applicable, r)
		}
	}
	if len(applicable) == 0 {
		return nil
	}

	raw, err := os.ReadFile(path)
	if err != nil {
		return []Finding{{"architecture-unreadable", rel, err.Error()}}
	}

	var out []Finding
	for i, line := range strings.Split(string(raw), "\n") {
		imp := extractImport(line)
		if imp == "" {
			continue
		}
		for _, r := range applicable {
			for _, forbidden := range r.ForbiddenSubstrings {
				if strings.Contains(imp, forbidden) {
					out = append(out, Finding{
						Rule:   "forbidden-import",
						Path:   fmt.Sprintf("%s:%d", rel, i+1),
						Detail: fmt.Sprintf("%s imports %q: %s", r.From, imp, r.Why),
					})
				}
			}
		}
	}
	return out
}

func extractImport(line string) string {
	for _, re := range []*regexp.Regexp{javaImport, swiftImport, tsImport} {
		if m := re.FindStringSubmatch(line); m != nil {
			return m[1]
		}
	}
	// A bare quoted string on its own line inside a Go import block.
	if m := goImport.FindStringSubmatch(line); m != nil && strings.Contains(m[1], "/") {
		return m[1]
	}
	return ""
}

// PendingSummary renders the unenforced clauses for the run log.
func PendingSummary() string {
	sorted := append([]string(nil), PendingRules...)
	sort.Strings(sorted)
	return "architecture rules pending (nothing to constrain yet):\n  " + strings.Join(sorted, "\n  ")
}
