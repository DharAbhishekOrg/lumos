// Lumos - tools/repolint / Tests for the TRS Sec. 4.1 repository-layout check.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-REP-001
// ADRs: -
// Threading: any
// Notes: Each negative case is asserted against a synthetic fixture rather than
// the live tree, so the check is proven to fail for the right reason. A layout
// checker that has only ever been run against a passing repository is untested.
package repolint

import (
	"os"
	"path/filepath"
	"sort"
	"testing"
)

// repoRoot resolves the repository root relative to this package (tools/repolint).
func repoRoot(t *testing.T) string {
	t.Helper()
	abs, err := filepath.Abs(filepath.Join("..", ".."))
	if err != nil {
		t.Fatalf("resolve repository root: %v", err)
	}
	return abs
}

// TestRequiredRootsMatchTRS41 guards the list itself against silent edits.
func TestRequiredRootsMatchTRS41(t *testing.T) {
	want := []string{
		"admin-console", "android-app", "backend", "config-schema", "docs",
		"evidence", "ios-app", "protocol", "test-vectors", "tools",
	}
	if len(RequiredRoots) != len(want) {
		t.Fatalf("RequiredRoots has %d entries, TRS Sec. 4.1 defines %d", len(RequiredRoots), len(want))
	}
	if !sort.StringsAreSorted(RequiredRoots) {
		t.Error("RequiredRoots must stay sorted for stable violation output")
	}
	seen := map[string]bool{}
	for i, got := range RequiredRoots {
		if got != want[i] {
			t.Errorf("RequiredRoots[%d] = %q, want %q", i, got, want[i])
		}
		if seen[got] {
			t.Errorf("duplicate root %q", got)
		}
		seen[got] = true
	}
}

// TestRepositoryLayoutConforms is the live assertion for TRS-REP-001.
func TestRepositoryLayoutConforms(t *testing.T) {
	violations, err := Check(repoRoot(t))
	if err != nil {
		t.Fatalf("Check: %v", err)
	}
	for _, v := range violations {
		t.Errorf("layout violation: %s", v)
	}
}

// newConformingFixture builds a minimal tree that Check must accept.
func newConformingFixture(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	for _, root := range RequiredRoots {
		if err := os.MkdirAll(filepath.Join(dir, root), 0o755); err != nil {
			t.Fatalf("mkdir %s: %v", root, err)
		}
	}
	for _, rel := range relocatedArtifacts {
		p := filepath.Join(dir, filepath.FromSlash(rel))
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			t.Fatalf("mkdir for %s: %v", rel, err)
		}
		if err := os.WriteFile(p, []byte("fixture"), 0o644); err != nil {
			t.Fatalf("write %s: %v", rel, err)
		}
	}
	if err := os.WriteFile(filepath.Join(dir, "AGENTS.md"), []byte("fixture"), 0o644); err != nil {
		t.Fatalf("write AGENTS.md: %v", err)
	}
	return dir
}

func rulesOf(violations []Violation) map[string]string {
	out := make(map[string]string, len(violations))
	for _, v := range violations {
		out[v.Rule] = v.Path
	}
	return out
}

func TestFixtureBaselineIsClean(t *testing.T) {
	violations, err := Check(newConformingFixture(t))
	if err != nil {
		t.Fatalf("Check: %v", err)
	}
	if len(violations) != 0 {
		t.Fatalf("conforming fixture reported %d violations: %v", len(violations), violations)
	}
}

func TestCheckDetectsMissingRoot(t *testing.T) {
	dir := newConformingFixture(t)
	if err := os.RemoveAll(filepath.Join(dir, "config-schema")); err != nil {
		t.Fatalf("remove root: %v", err)
	}
	violations, err := Check(dir)
	if err != nil {
		t.Fatalf("Check: %v", err)
	}
	got := rulesOf(violations)
	if got["missing-required-root"] != "config-schema" {
		t.Errorf("expected missing-required-root for config-schema, got %v", violations)
	}
}

func TestCheckDetectsRequiredRootThatIsAFile(t *testing.T) {
	dir := newConformingFixture(t)
	p := filepath.Join(dir, "backend")
	if err := os.RemoveAll(p); err != nil {
		t.Fatalf("remove root: %v", err)
	}
	if err := os.WriteFile(p, []byte("not a directory"), 0o644); err != nil {
		t.Fatalf("write file: %v", err)
	}
	violations, err := Check(dir)
	if err != nil {
		t.Fatalf("Check: %v", err)
	}
	if rulesOf(violations)["required-root-not-a-directory"] != "backend" {
		t.Errorf("expected required-root-not-a-directory for backend, got %v", violations)
	}
}

func TestCheckDetectsUnexpectedRootDirectory(t *testing.T) {
	dir := newConformingFixture(t)
	if err := os.MkdirAll(filepath.Join(dir, "requirements"), 0o755); err != nil {
		t.Fatalf("mkdir: %v", err)
	}
	violations, err := Check(dir)
	if err != nil {
		t.Fatalf("Check: %v", err)
	}
	got := rulesOf(violations)
	if got["unexpected-root-directory"] != "requirements" {
		t.Errorf("expected unexpected-root-directory for requirements, got %v", violations)
	}
	if got["stale-pre-move-path"] != "requirements" {
		t.Errorf("expected stale-pre-move-path for requirements, got %v", violations)
	}
}

func TestCheckDetectsUnexpectedRootFile(t *testing.T) {
	dir := newConformingFixture(t)
	if err := os.WriteFile(filepath.Join(dir, "scratch-notes.txt"), []byte("x"), 0o644); err != nil {
		t.Fatalf("write: %v", err)
	}
	violations, err := Check(dir)
	if err != nil {
		t.Fatalf("Check: %v", err)
	}
	if rulesOf(violations)["unexpected-root-file"] != "scratch-notes.txt" {
		t.Errorf("expected unexpected-root-file, got %v", violations)
	}
}

func TestCheckIgnoresDotEntries(t *testing.T) {
	dir := newConformingFixture(t)
	if err := os.MkdirAll(filepath.Join(dir, ".github", "workflows"), 0o755); err != nil {
		t.Fatalf("mkdir: %v", err)
	}
	if err := os.WriteFile(filepath.Join(dir, ".gitignore"), []byte("x"), 0o644); err != nil {
		t.Fatalf("write: %v", err)
	}
	violations, err := Check(dir)
	if err != nil {
		t.Fatalf("Check: %v", err)
	}
	if len(violations) != 0 {
		t.Errorf("dot entries must not be flagged, got %v", violations)
	}
}

func TestCheckDetectsMissingRelocatedArtifact(t *testing.T) {
	dir := newConformingFixture(t)
	target := filepath.Join(dir, filepath.FromSlash("docs/requirements/trs_requirements_v1_1.json"))
	if err := os.Remove(target); err != nil {
		t.Fatalf("remove: %v", err)
	}
	violations, err := Check(dir)
	if err != nil {
		t.Fatalf("Check: %v", err)
	}
	if rulesOf(violations)["missing-relocated-artifact"] != "docs/requirements/trs_requirements_v1_1.json" {
		t.Errorf("expected missing-relocated-artifact, got %v", violations)
	}
}

func TestCheckDetectsStaleArchiveAtRoot(t *testing.T) {
	dir := newConformingFixture(t)
	if err := os.WriteFile(filepath.Join(dir, "Lumos_Codex_Handover_Pack.zip"), []byte("x"), 0o644); err != nil {
		t.Fatalf("write: %v", err)
	}
	violations, err := Check(dir)
	if err != nil {
		t.Fatalf("Check: %v", err)
	}
	if rulesOf(violations)["stale-pre-move-path"] != "Lumos_Codex_Handover_Pack.zip" {
		t.Errorf("expected stale-pre-move-path for the archive, got %v", violations)
	}
}
