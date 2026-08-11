// Lumos - tools/repolint / Repository-layout conformance check for TRS Sec. 4.1.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-REP-001
// ADRs: -
// Threading: any
// Notes: Pure filesystem inspection with no build-system coupling, so the check
// runs identically on a developer host and in a clean CI container. TRS-REP-001
// is a containment requirement ("shall contain separate ... roots"), but the
// WP-01 kickoff additionally mandates that handover-pack files be moved into
// their proper locations; DriftingRoots therefore enforces the stronger reading
// per AGENTS.md Sec. 1 (stricter interpretation wins).
package repolint

import (
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// RequiredRoots is the normative repository layout of TRS Sec. 4.1. The mapping
// to TRS-REP-001's prose names is: "android" -> android-app, "ios" -> ios-app,
// "admin" -> admin-console, "schemas" -> config-schema.
var RequiredRoots = []string{
	"admin-console",
	"android-app",
	"backend",
	"config-schema",
	"docs",
	"evidence",
	"ios-app",
	"protocol",
	"test-vectors",
	"tools",
}

// allowedRootFiles are the only non-dot files permitted at the repository root.
// Dot-prefixed entries (.git, .github, .gitignore, editor and agent tooling
// directories) are tooling rather than delivery roots and are never flagged.
// Build-system entry points must sit at the repository root by construction:
// Gradle looks for settings.gradle.kts beside the wrapper, and SwiftPM requires
// Package.swift at its package root. They are tooling, not delivery roots, and
// TRS Sec. 4.1's containment requirement is unaffected by their presence.
var allowedRootFiles = map[string]bool{
	"AGENTS.md":           true,
	"CLAUDE.md":           true,
	"README.md":           true,
	"go.mod":              true,
	"go.sum":              true,
	"settings.gradle.kts": true,
	"gradlew":             true,
	"gradlew.bat":         true,
	"Package.swift":       true,
	"Package.resolved":    true,
}

// allowedRootDirs are non-delivery directories the build systems place at the
// root. "gradle" holds only the pinned wrapper.
var allowedRootDirs = map[string]bool{
	"gradle": true,
}

// relocatedArtifacts maps each handover-pack file to the location it must
// occupy after the WP-01 slice S1 move. Sizes are not asserted here; the move
// itself was size-verified against the (password-protected) archive before it
// was performed, and that verification is recorded in docs/README.md.
var relocatedArtifacts = []string{
	"docs/Lumos_EDP_v1_0.pdf",
	"docs/Lumos_SRS_v1_1.pdf",
	"docs/Lumos_TRS_v1_1.pdf",
	"docs/acceptance/acceptance_tests_v1_1.csv",
	"docs/handover/Lumos_Codex_Handover_Pack.zip",
	"docs/prompts/WP-01-kickoff.md",
	"docs/requirements/srs_requirements_v1_1.json",
	"docs/requirements/trs_requirements_v1_1.json",
	"evidence/trace/module-map.json",
	"evidence/trace/trace-matrix.json",
	"protocol/constants/lumos_constants.seed.json",
	"tools/toolchain/toolchain-manifest.json",
}

// vacatedPaths must no longer exist once the slice S1 move is complete. A
// lingering copy would mean two sources of truth for a baselined document.
var vacatedPaths = []string{
	"Lumos_Codex_Handover_Pack.zip",
	"acceptance",
	"prompts",
	"requirements",
}

// Violation is a single layout defect. Rule identifies the check that failed so
// CI output stays machine-readable.
type Violation struct {
	Rule   string
	Path   string
	Detail string
}

func (v Violation) String() string {
	return fmt.Sprintf("[%s] %s: %s", v.Rule, v.Path, v.Detail)
}

// Check inspects repoRoot and returns every layout violation found, sorted for
// stable output. An empty slice means the layout conforms to TRS Sec. 4.1.
func Check(repoRoot string) ([]Violation, error) {
	var violations []Violation

	for _, root := range RequiredRoots {
		p := filepath.Join(repoRoot, root)
		info, err := os.Stat(p)
		switch {
		case os.IsNotExist(err):
			violations = append(violations, Violation{
				Rule:   "missing-required-root",
				Path:   root,
				Detail: "required by TRS Sec. 4.1 but absent",
			})
		case err != nil:
			return nil, fmt.Errorf("stat %s: %w", p, err)
		case !info.IsDir():
			violations = append(violations, Violation{
				Rule:   "required-root-not-a-directory",
				Path:   root,
				Detail: "exists but is not a directory",
			})
		}
	}

	drift, err := driftingEntries(repoRoot)
	if err != nil {
		return nil, err
	}
	violations = append(violations, drift...)

	for _, rel := range relocatedArtifacts {
		if _, err := os.Stat(filepath.Join(repoRoot, filepath.FromSlash(rel))); os.IsNotExist(err) {
			violations = append(violations, Violation{
				Rule:   "missing-relocated-artifact",
				Path:   rel,
				Detail: "handover-pack artefact absent from its TRS Sec. 4.1 location",
			})
		} else if err != nil {
			return nil, fmt.Errorf("stat %s: %w", rel, err)
		}
	}

	for _, old := range vacatedPaths {
		if _, err := os.Stat(filepath.Join(repoRoot, filepath.FromSlash(old))); err == nil {
			violations = append(violations, Violation{
				Rule:   "stale-pre-move-path",
				Path:   old,
				Detail: "must not exist after the WP-01 slice S1 relocation",
			})
		} else if !os.IsNotExist(err) {
			return nil, fmt.Errorf("stat %s: %w", old, err)
		}
	}

	sort.Slice(violations, func(i, j int) bool {
		if violations[i].Rule != violations[j].Rule {
			return violations[i].Rule < violations[j].Rule
		}
		return violations[i].Path < violations[j].Path
	})
	return violations, nil
}

// driftingEntries reports top-level entries that are neither a required root
// nor an allow-listed root file. Dot-prefixed entries are skipped as tooling.
func driftingEntries(repoRoot string) ([]Violation, error) {
	entries, err := os.ReadDir(repoRoot)
	if err != nil {
		return nil, fmt.Errorf("read repository root: %w", err)
	}

	required := make(map[string]bool, len(RequiredRoots))
	for _, r := range RequiredRoots {
		required[r] = true
	}

	var violations []Violation
	for _, e := range entries {
		name := e.Name()
		if strings.HasPrefix(name, ".") {
			continue
		}
		if e.IsDir() {
			if !required[name] && !allowedRootDirs[name] {
				violations = append(violations, Violation{
					Rule:   "unexpected-root-directory",
					Path:   name,
					Detail: "not one of the TRS Sec. 4.1 roots; move it under an existing root",
				})
			}
			continue
		}
		if !allowedRootFiles[name] {
			violations = append(violations, Violation{
				Rule:   "unexpected-root-file",
				Path:   name,
				Detail: "not an allow-listed repository-root file",
			})
		}
	}
	return violations, nil
}
