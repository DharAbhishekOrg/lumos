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
	"os/exec"
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

// TestBuildEntryPointsAreAccepted pins the deliberate allowance for build-system
// files that must live at the repository root by construction. Without this the
// allowance could be widened silently into a general escape hatch.
func TestBuildEntryPointsAreAccepted(t *testing.T) {
	dir := newConformingFixture(t)
	for _, name := range []string{"settings.gradle.kts", "gradlew", "gradlew.bat", "Package.swift"} {
		if err := os.WriteFile(filepath.Join(dir, name), []byte("x"), 0o644); err != nil {
			t.Fatalf("write %s: %v", name, err)
		}
	}
	if err := os.MkdirAll(filepath.Join(dir, "gradle", "wrapper"), 0o755); err != nil {
		t.Fatalf("mkdir gradle/wrapper: %v", err)
	}
	violations, err := Check(dir)
	if err != nil {
		t.Fatalf("Check: %v", err)
	}
	if len(violations) != 0 {
		t.Errorf("build entry points must be accepted, got %v", violations)
	}

	// The allowance is a fixed list, not a general exemption for build-ish names.
	if err := os.WriteFile(filepath.Join(dir, "build.sh"), []byte("x"), 0o644); err != nil {
		t.Fatalf("write build.sh: %v", err)
	}
	violations, err = Check(dir)
	if err != nil {
		t.Fatalf("Check: %v", err)
	}
	if rulesOf(violations)["unexpected-root-file"] != "build.sh" {
		t.Errorf("an unlisted root file must still be flagged, got %v", violations)
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

// TestGitIgnoredBuildOutputIsNotLayoutDrift covers the exemption in both
// directions in one fixture: with build/ ignored the tree is clean, and the
// moment the ignore rule goes away the same directory is flagged. An exemption
// asserted only in the passing direction cannot be distinguished from the
// check having stopped looking.
func TestGitIgnoredBuildOutputIsNotLayoutDrift(t *testing.T) {
	dir := newConformingFixture(t)
	git := func(args ...string) {
		t.Helper()
		cmd := exec.Command("git", args...)
		cmd.Dir = dir
		if out, err := cmd.CombinedOutput(); err != nil {
			t.Skipf("git unavailable in this environment (%v): %s", err, out)
		}
	}
	git("init", "-q")

	if err := os.MkdirAll(filepath.Join(dir, "build"), 0o755); err != nil {
		t.Fatalf("mkdir build: %v", err)
	}
	if err := os.WriteFile(filepath.Join(dir, ".gitignore"), []byte("build/\n"), 0o644); err != nil {
		t.Fatalf("write .gitignore: %v", err)
	}
	violations, err := Check(dir)
	if err != nil {
		t.Fatalf("Check: %v", err)
	}
	if len(violations) != 0 {
		t.Errorf("git-ignored build output must not be layout drift, got %v", violations)
	}

	// Same directory, no longer ignored: the rule must fire.
	if err := os.WriteFile(filepath.Join(dir, ".gitignore"), []byte("# nothing ignored\n"), 0o644); err != nil {
		t.Fatalf("rewrite .gitignore: %v", err)
	}
	violations, err = Check(dir)
	if err != nil {
		t.Fatalf("Check: %v", err)
	}
	if rulesOf(violations)["unexpected-root-directory"] != "build" {
		t.Errorf("a tracked build/ directory must still be flagged, got %v", violations)
	}
}

// TestExemptionIsRefusedWithoutGit pins the fallback: no git means no
// exemptions, so the check degrades towards more scrutiny rather than less.
func TestExemptionIsRefusedWithoutGit(t *testing.T) {
	dir := newConformingFixture(t) // deliberately NOT a git repository
	if err := os.MkdirAll(filepath.Join(dir, "build"), 0o755); err != nil {
		t.Fatalf("mkdir build: %v", err)
	}
	if err := os.WriteFile(filepath.Join(dir, ".gitignore"), []byte("build/\n"), 0o644); err != nil {
		t.Fatalf("write .gitignore: %v", err)
	}
	violations, err := Check(dir)
	if err != nil {
		t.Fatalf("Check: %v", err)
	}
	if rulesOf(violations)["unexpected-root-directory"] != "build" {
		t.Errorf("without a git repository nothing may be exempted, got %v", violations)
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
