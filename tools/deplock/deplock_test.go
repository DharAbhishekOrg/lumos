// Lumos - tools/deplock / Tests for dependency lock and checksum verification.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-REP-003, TRS-CICD-001
// ADRs: -
// Threading: any
// Notes: These run against the live repository, not fixtures, because the thing
// under test IS the repository's lock state. `go mod verify`, `npm ci` and the
// Gradle wrapper-validation action check that the bytes match their checksums;
// what they cannot check is that the toolchain manifest - the record a human
// reviews and an auditor cites - still describes those same bytes. That is what
// fails silently, so that is what these tests assert.
package deplock

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func repoRoot(t *testing.T) string {
	t.Helper()
	p, err := filepath.Abs(filepath.Join("..", ".."))
	if err != nil {
		t.Fatalf("resolve repo root: %v", err)
	}
	return p
}

// --- Go ---------------------------------------------------------------------

func TestEveryGoModuleIsChecksummed(t *testing.T) {
	root := repoRoot(t)
	modules, err := GoModules(root)
	if err != nil {
		t.Fatalf("GoModules: %v", err)
	}
	if len(modules) == 0 {
		t.Fatal("no requirements parsed from go.mod; the parser has drifted")
	}
	sums, err := GoSumEntries(root)
	if err != nil {
		t.Fatalf("GoSumEntries: %v", err)
	}
	for _, m := range modules {
		if !sums[m.Path] {
			t.Errorf("%s is required by go.mod but has no go.sum entry", m.Path)
		}
	}
}

// TestGoManifestMirrorsGoMod is the cross-check. AGENTS.md Sec. 3 requires a
// name, version, licence and reason for every dependency; the manifest is where
// those live, and it is worth nothing if it lags the lockfile.
func TestGoManifestMirrorsGoMod(t *testing.T) {
	root := repoRoot(t)
	modules, err := GoModules(root)
	if err != nil {
		t.Fatalf("GoModules: %v", err)
	}
	manifest, err := LoadManifest(root)
	if err != nil {
		t.Fatalf("LoadManifest: %v", err)
	}

	declared := map[string]string{}
	for _, m := range manifest.GoDependencies.Modules {
		declared[m.Path] = m.Version
		if m.License == "" || m.Why == "" {
			t.Errorf("manifest entry %s has no licence or no stated reason (AGENTS.md Sec. 3)", m.Path)
		}
	}
	for _, m := range modules {
		version, ok := declared[m.Path]
		if !ok {
			t.Errorf("go.mod requires %s but the toolchain manifest never declares it", m.Path)
			continue
		}
		if version != m.Version {
			t.Errorf("%s: go.mod pins %s, manifest says %s", m.Path, m.Version, version)
		}
	}
	for path := range declared {
		found := false
		for _, m := range modules {
			if m.Path == path {
				found = true
			}
		}
		if !found {
			t.Errorf("the manifest declares %s but go.mod no longer requires it", path)
		}
	}
}

// TestOnlyTheApprovedCryptoDependencyIsPresent enforces the owner's standing
// instruction: no crypto primitives beyond the Go standard library and the one
// approved module, and no Go module at all without a recorded owner approval.
func TestOnlyTheApprovedCryptoDependencyIsPresent(t *testing.T) {
	modules, err := GoModules(repoRoot(t))
	if err != nil {
		t.Fatalf("GoModules: %v", err)
	}
	approved := map[string]bool{
		"golang.org/x/crypto": true, // owner-approved 2026-08-11 for ChaCha20-Poly1305
		"golang.org/x/sys":    true, // indirect dependency of the above
		// Owner-approved 2026-11-22 for WP-05 S4b (TRS-BCK-008), when the owner
		// accepted "connect the server to a real database ... the pgx v5
		// driver (MIT licence)". The rest are pgx's own dependencies.
		"github.com/jackc/pgx/v5":        true,
		"github.com/jackc/pgpassfile":    true,
		"github.com/jackc/pgservicefile": true,
		"github.com/jackc/puddle/v2":     true,
		"golang.org/x/sync":              true,
		"golang.org/x/text":              true,
		// Owner-approved 2026-11-22 for WP-05 S5b (TRS-BCK-008), answering
		// "go-redis v9.23.0 (BSD-2-Clause) ... plus what it pulls in ...
		// Approve this set?" with "Approve all". The rest are its dependencies.
		"github.com/redis/go-redis/v9": true,
		"github.com/cespare/xxhash/v2": true,
		"go.uber.org/atomic":           true,
	}
	for _, m := range modules {
		if !approved[m.Path] {
			t.Errorf("unapproved module %s@%s in go.mod; every dependency needs explicit owner approval",
				m.Path, m.Version)
		}
	}
}

// --- Gradle -----------------------------------------------------------------

func TestGradleWrapperIsChecksumPinned(t *testing.T) {
	root := repoRoot(t)
	w, err := ReadGradleWrapper(root)
	if err != nil {
		t.Fatalf("ReadGradleWrapper: %v", err)
	}
	if len(w.SHA256) != 64 {
		t.Fatalf("distributionSha256Sum is %q; a wrapper without a pinned checksum "+
			"downloads whatever the URL serves today", w.SHA256)
	}
	if !strings.HasPrefix(w.URL, "https://") {
		t.Errorf("distributionUrl %q is not https", w.URL)
	}

	manifest, err := LoadManifest(root)
	if err != nil {
		t.Fatalf("LoadManifest: %v", err)
	}
	if manifest.Supporting.Gradle.DistributionSHA256 != w.SHA256 {
		t.Errorf("wrapper checksum %s does not match the manifest's %s",
			w.SHA256, manifest.Supporting.Gradle.DistributionSHA256)
	}
	if manifest.Supporting.Gradle.Version != w.Version {
		t.Errorf("wrapper is Gradle %s, manifest says %s", w.Version, manifest.Supporting.Gradle.Version)
	}
}

// TestJVMDependenciesAreCataloguedAndDeclared ties the Gradle version catalogue
// to the manifest in both directions.
func TestJVMDependenciesAreCataloguedAndDeclared(t *testing.T) {
	root := repoRoot(t)
	catalogue, err := VersionCatalogue(root)
	if err != nil {
		t.Fatalf("VersionCatalogue: %v", err)
	}
	if len(catalogue) == 0 {
		t.Fatal("the version catalogue is empty; Gradle dependency versions are not centrally pinned")
	}

	manifest, err := LoadManifest(root)
	if err != nil {
		t.Fatalf("LoadManifest: %v", err)
	}
	for _, m := range manifest.JVMTestDependencies.Modules {
		if m.License == "" || m.Why == "" {
			t.Errorf("manifest entry %s has no licence or no stated reason", m.Path)
		}
		found := false
		for _, version := range catalogue {
			if version == m.Version {
				found = true
			}
		}
		if !found {
			t.Errorf("manifest pins %s at %s, but no catalogue alias resolves to that version",
				m.Path, m.Version)
		}
	}

	// The Android Gradle Plugin is a build dependency the manifest must record;
	// it was bumped to satisfy Gradle 9 and that bump has to be visible.
	if _, ok := catalogue["androidGradlePlugin"]; !ok {
		t.Error("the catalogue does not pin androidGradlePlugin")
	}
}

// --- npm --------------------------------------------------------------------

func TestNPMLockfilePinsEveryPackageWithIntegrity(t *testing.T) {
	lock, err := ReadNPMLock(repoRoot(t))
	if err != nil {
		t.Fatalf("ReadNPMLock: %v", err)
	}
	if lock.LockfileVersion < 3 {
		t.Errorf("lockfileVersion %d; v3 is required for the integrity fields npm ci verifies",
			lock.LockfileVersion)
	}
	if len(lock.Packages) < 2 {
		t.Fatalf("only %d packages in the lockfile; it has not been resolved", len(lock.Packages))
	}
	for name, pkg := range lock.Packages {
		if name == "" || pkg.Link {
			continue // the root project and workspace links carry no integrity hash
		}
		if pkg.Version == "" {
			t.Errorf("%s has no pinned version", name)
		}
		if pkg.Integrity == "" {
			t.Errorf("%s has no integrity hash; npm ci cannot verify what it downloads", name)
		} else if !strings.HasPrefix(pkg.Integrity, "sha512-") && !strings.HasPrefix(pkg.Integrity, "sha256-") {
			t.Errorf("%s integrity %q is weaker than SHA-256", name, pkg.Integrity)
		}
		if pkg.Resolved != "" && !strings.HasPrefix(pkg.Resolved, "https://") {
			t.Errorf("%s resolves over %q, not https", name, pkg.Resolved)
		}
	}
}

// TestNPMManifestMirrorsPackageJSON is the npm half of the cross-check.
func TestNPMManifestMirrorsPackageJSON(t *testing.T) {
	root := repoRoot(t)
	pkg, err := ReadPackageJSON(root)
	if err != nil {
		t.Fatalf("ReadPackageJSON: %v", err)
	}
	manifest, err := LoadManifest(root)
	if err != nil {
		t.Fatalf("LoadManifest: %v", err)
	}

	declared := map[string]string{}
	for _, m := range manifest.NPMDependencies.Modules {
		declared[m.Path] = m.Version
		if m.License == "" || m.Why == "" {
			t.Errorf("manifest entry %s has no licence or no stated reason", m.Path)
		}
	}
	if len(pkg.Dependencies) != 0 {
		t.Errorf("the admin shell has runtime dependencies %v; WP-01 ships none", pkg.Dependencies)
	}
	for name, version := range pkg.DevDependencies {
		got, ok := declared[name]
		if !ok {
			t.Errorf("package.json declares %s but the toolchain manifest never does", name)
			continue
		}
		if got != version {
			t.Errorf("%s: package.json pins %s, manifest says %s", name, version, got)
		}
		if strings.ContainsAny(version, "^~*") {
			t.Errorf("%s is pinned as %q; a range is not a pin (TRS-REP-003)", name, version)
		}
	}
	for name := range declared {
		if _, ok := pkg.DevDependencies[name]; !ok {
			t.Errorf("the manifest declares npm package %s but package.json no longer does", name)
		}
	}
}

// TestBuildPluginsAreDeclaredAndCatalogued covers AGP, which determines what
// the Android build produces and so is a dependency like any other.
func TestBuildPluginsAreDeclaredAndCatalogued(t *testing.T) {
	root := repoRoot(t)
	manifest, err := LoadManifest(root)
	if err != nil {
		t.Fatalf("LoadManifest: %v", err)
	}
	catalogue, err := VersionCatalogue(root)
	if err != nil {
		t.Fatalf("VersionCatalogue: %v", err)
	}
	if len(manifest.BuildDependencies.Modules) == 0 {
		t.Fatal("no build dependencies declared; AGP determines the Android output and must be recorded")
	}
	for _, m := range manifest.BuildDependencies.Modules {
		if m.License == "" || m.Why == "" {
			t.Errorf("build dependency %s has no licence or no stated reason", m.Path)
		}
		if m.CatalogueAlias == "" {
			t.Errorf("build dependency %s names no catalogue alias", m.Path)
			continue
		}
		got, ok := catalogue[m.CatalogueAlias]
		if !ok {
			t.Errorf("catalogue has no alias %q for %s", m.CatalogueAlias, m.Path)
			continue
		}
		if got != m.Version {
			t.Errorf("%s: catalogue pins %s, manifest says %s", m.Path, got, m.Version)
		}
	}
}

// --- the manifest's own bookkeeping ------------------------------------------

// TestManifestNoLongerCallsPinnedItemsPending closes the loop on the
// not_yet_pinned list slice S0 wrote. Leaving an item there once it is pinned
// makes the manifest read as less complete than the repository is, which is the
// mirror image of the honesty problem and just as misleading.
func TestManifestNoLongerCallsPinnedItemsPending(t *testing.T) {
	manifest, err := LoadManifest(repoRoot(t))
	if err != nil {
		t.Fatalf("LoadManifest: %v", err)
	}
	nowPinned := map[string]string{
		"agp":          "gradle/libs.versions.toml pins androidGradlePlugin",
		"npm_lockfile": "admin-console/package-lock.json is committed and integrity-checked",
		"sbom":         "tools/sbomgen emits evidence/sbom/lumos-sbom.json",
	}
	for _, pending := range manifest.NotYetPinned.Pending {
		if why, ok := nowPinned[pending]; ok {
			t.Errorf("manifest still lists %q as not yet pinned, but %s", pending, why)
		}
	}
}

// --- Shipped dependencies ---------------------------------------------------
//
// These are the ones a licence review turns on, and the ones the owner attached
// conditions to. A condition given in conversation and recorded only in prose is
// a condition that lapses the first time somebody edits the prose; these are the
// same conditions written as failures.

func TestRuntimeDependenciesAreCataloguedAndDeclared(t *testing.T) {
	root := repoRoot(t)
	manifest, err := LoadManifest(root)
	if err != nil {
		t.Fatalf("LoadManifest: %v", err)
	}
	catalogue, err := VersionCatalogue(root)
	if err != nil {
		t.Fatalf("VersionCatalogue: %v", err)
	}
	if len(manifest.RuntimeDependencies.Modules) == 0 {
		t.Fatal("no runtime dependencies declared; the app ships libsodium through two of them")
	}
	for _, m := range manifest.RuntimeDependencies.Modules {
		if m.License == "" || m.Why == "" {
			t.Errorf("shipped dependency %s has no licence or no stated reason", m.Path)
		}
		if m.CatalogueAlias == "" {
			t.Errorf("shipped dependency %s names no catalogue alias", m.Path)
			continue
		}
		if catalogue[m.CatalogueAlias] != m.Version {
			t.Errorf("manifest pins %s at %s, catalogue alias %s says %q",
				m.Path, m.Version, m.CatalogueAlias, catalogue[m.CatalogueAlias])
		}
	}
}

// TestEveryShippedDependencyIsInTheNotices is the owner's licence condition of
// 2026-08-18 written as a test. "The licence text goes in the app's third-party
// notices" is checkable, so it is checked: a dependency added to the shipped set
// without its notice fails here rather than at whatever review nobody scheduled.
func TestEveryShippedDependencyIsInTheNotices(t *testing.T) {
	root := repoRoot(t)
	manifest, err := LoadManifest(root)
	if err != nil {
		t.Fatalf("LoadManifest: %v", err)
	}
	raw, err := os.ReadFile(filepath.Join(root, "docs", "legal", "third-party-notices.md"))
	if err != nil {
		t.Fatalf("the shipped notices file is missing: %v", err)
	}
	notices := string(raw)

	shipped := append([]Declared{}, manifest.RuntimeDependencies.Modules...)
	shipped = append(shipped, manifest.SwiftPMDependencies.Modules...)
	for _, m := range shipped {
		name := m.Path
		if i := strings.LastIndex(name, "/"); i >= 0 {
			name = strings.TrimSuffix(name[i+1:], ".git")
		}
		if i := strings.Index(name, ":"); i >= 0 {
			name = name[i+1:]
		}
		if !strings.Contains(strings.ToLower(notices), strings.ToLower(name)) {
			t.Errorf("shipped dependency %s is not named in docs/legal/third-party-notices.md", m.Path)
		}
		if !strings.Contains(notices, m.Version) {
			t.Errorf("the notices do not record %s at the pinned version %s", m.Path, m.Version)
		}
	}

	// The two licences whose full text the shipped set actually requires.
	for _, marker := range []string{"Mozilla Public License Version 2.0", "ISC License", "Apache License"} {
		if !strings.Contains(notices, marker) {
			t.Errorf("the notices do not carry the %q text", marker)
		}
	}
}

// --- SwiftPM ----------------------------------------------------------------

func TestSwiftPMManifestMirrorsPackageResolved(t *testing.T) {
	root := repoRoot(t)
	manifest, err := LoadManifest(root)
	if err != nil {
		t.Fatalf("LoadManifest: %v", err)
	}
	pins, err := ReadSwiftPMResolved(root)
	if err != nil {
		t.Fatalf("ReadSwiftPMResolved: %v", err)
	}
	if len(pins) == 0 {
		t.Fatal("Package.resolved pins nothing; the SwiftPM dependency is unlocked")
	}

	byLocation := map[string]SwiftPMPin{}
	for _, p := range pins {
		byLocation[p.Location] = p
	}
	for _, m := range manifest.SwiftPMDependencies.Modules {
		pin, ok := byLocation[m.Path]
		if !ok {
			t.Errorf("manifest declares %s but Package.resolved does not pin it", m.Path)
			continue
		}
		if pin.State.Version != m.Version {
			t.Errorf("%s: manifest says %s, Package.resolved says %s",
				m.Path, m.Version, pin.State.Version)
		}
		// The revision is the part that matters. A version tag can be moved;
		// the commit it resolved to cannot, which is why the manifest records
		// both and the swift job asserts the resolution reaches this one.
		if m.Revision == "" {
			t.Errorf("%s: the manifest records no revision", m.Path)
		} else if pin.State.Revision != m.Revision {
			t.Errorf("%s: manifest revision %s, Package.resolved %s",
				m.Path, m.Revision, pin.State.Revision)
		}
	}
	if len(manifest.SwiftPMDependencies.Modules) != len(pins) {
		t.Errorf("Package.resolved pins %d package(s), the manifest declares %d",
			len(pins), len(manifest.SwiftPMDependencies.Modules))
	}
}
