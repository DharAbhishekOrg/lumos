// Lumos - tools/toolchain / Tests that the pinned toolchain manifest is honest.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-REP-003, TRS-REP-006
// ADRs: -
// Threading: any
// Notes: The version-agreement tests execute the real binaries. A manifest that
// merely parses proves nothing; a pin that disagrees with the installed
// toolchain is worse than no pin, because it looks like evidence.
package toolchain

import (
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

func manifestPath(t *testing.T) string {
	t.Helper()
	abs, err := filepath.Abs(filepath.Join("..", "..", filepath.FromSlash(ManifestPath)))
	if err != nil {
		t.Fatalf("resolve manifest path: %v", err)
	}
	return abs
}

func load(t *testing.T) *Manifest {
	t.Helper()
	m, err := Load(manifestPath(t))
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	return m
}

func TestManifestParsesAndDeclaresProvenance(t *testing.T) {
	m := load(t)
	if m.SchemaVersion != 1 {
		t.Errorf("schema_version = %d, want 1", m.SchemaVersion)
	}
	if m.WorkPackage != "WP-01" || m.Slice != "S0" {
		t.Errorf("provenance = %s/%s, want WP-01/S0", m.WorkPackage, m.Slice)
	}
	if len(m.Implements) == 0 {
		t.Error("manifest must declare the requirement IDs it implements")
	}
}

// TestPinnedToolchainsAreConcrete enforces that every toolchain either carries a
// concrete version or explicitly declares where it runs instead. Silence is the
// failure mode this guards against.
func TestPinnedToolchainsAreConcrete(t *testing.T) {
	m := load(t)
	for _, name := range []string{"go", "jdk", "node", "swift"} {
		tc, ok := m.Toolchains[name]
		if !ok {
			t.Errorf("toolchain %q missing from manifest", name)
			continue
		}
		if tc.Version != nil {
			if strings.TrimSpace(*tc.Version) == "" {
				t.Errorf("toolchain %q has an empty version pin", name)
			}
			if strings.TrimSpace(tc.Verified) == "" {
				t.Errorf("toolchain %q is pinned but records no verification output", name)
			}
			continue
		}
		if tc.Status == "" || tc.ExecutionSurface == "" {
			t.Errorf("toolchain %q has no version and must declare status + execution_surface, got status=%q surface=%q",
				name, tc.Status, tc.ExecutionSurface)
		}
	}
}

// TestSwiftIsHonestlyUnavailable pins the WP-01 known trap: the Swift runner
// cannot execute on this host and must not be reported as if it could.
func TestSwiftIsHonestlyUnavailable(t *testing.T) {
	m := load(t)
	swift := m.Toolchains["swift"]
	if swift.Version != nil {
		t.Fatal("swift claims a local version; it is not installable on the Windows authoring host")
	}
	if swift.ExecutionSurface != "github-actions:macos-latest" {
		t.Errorf("swift execution_surface = %q, want github-actions:macos-latest", swift.ExecutionSurface)
	}
}

// TestPythonIsScopedToAuthoringHost keeps the spec-reading helper out of the
// project's dependency surface.
func TestPythonIsScopedToAuthoringHost(t *testing.T) {
	m := load(t)
	py, ok := m.Supporting["python"]
	if !ok {
		t.Fatal("supporting.python missing")
	}
	if py.Scope != "authoring_host_only" {
		t.Errorf("python scope = %q, want authoring_host_only", py.Scope)
	}
}

// runVersion executes bin with args and returns trimmed combined output.
func runVersion(t *testing.T, bin string, args ...string) (string, bool) {
	t.Helper()
	if _, err := exec.LookPath(bin); err != nil {
		return "", false
	}
	out, err := exec.Command(bin, args...).CombinedOutput()
	if err != nil {
		t.Fatalf("%s %v: %v", bin, args, err)
	}
	return strings.TrimSpace(string(out)), true
}

func TestGoVersionMatchesPin(t *testing.T) {
	m := load(t)
	pin := m.Toolchains["go"].Version
	if pin == nil {
		t.Fatal("go must be pinned")
	}
	out, ok := runVersion(t, "go", "version")
	if !ok {
		t.Skip("go not on PATH")
	}
	if !strings.Contains(out, "go"+*pin+" ") {
		t.Errorf("installed %q does not match pinned go %s", out, *pin)
	}
}

func TestNodeVersionMatchesPin(t *testing.T) {
	m := load(t)
	pin := m.Toolchains["node"].Version
	if pin == nil {
		t.Fatal("node must be pinned")
	}
	out, ok := runVersion(t, "node", "--version")
	if !ok {
		t.Skip("node not on PATH")
	}
	if out != "v"+*pin {
		t.Errorf("installed node %q does not match pinned %s", out, *pin)
	}
}
