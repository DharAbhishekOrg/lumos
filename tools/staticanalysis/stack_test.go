// Lumos - tools/staticanalysis / Tests that the TRS-CODE-006 stack is wired and armed.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CODE-006, TRS-CODE-004, TRS-CICD-001
// ADRs: -
// Threading: any
// Notes: What these tests can and cannot prove is worth being blunt about,
// because the difference is the whole reason WP-01 refused to claim this
// requirement.
//
// They CANNOT prove the tree is warning-free. Only running the analysers proves
// that, and the analysers run in CI, where the six jobs going green in one
// pipeline run IS that evidence.
//
// They CAN prove the things a green run does not: that every tool the
// requirement names is configured and invoked, that each one is set to FAIL on
// a warning rather than print it, and that the budgets in each tool's config are
// the numbers TRS-CODE-004 specifies rather than whatever default the tool
// shipped with. A stack that is silently missing a tool, or that runs one with
// its failure threshold turned off, produces exactly the same green as a
// correct one - which is the failure shape this repository has met three times.
package staticanalysis

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

func read(t *testing.T, root, rel string) string {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join(root, filepath.FromSlash(rel)))
	if err != nil {
		t.Fatalf("read %s: %v", rel, err)
	}
	return string(raw)
}

// TestEveryAnalyserIsConfiguredAndInvoked walks the whole stack: config present,
// failure threshold armed, CI step present. A tool that satisfies two of the
// three is the partial gate WP-01 declined to call a gate.
func TestEveryAnalyserIsConfiguredAndInvoked(t *testing.T) {
	root := repoRoot(t)

	for _, a := range Stack {
		t.Run(a.Name, func(t *testing.T) {
			for _, cfg := range a.ConfigPaths {
				if _, err := os.Stat(filepath.Join(root, filepath.FromSlash(cfg))); err != nil {
					t.Errorf("%s is configured by %s, which is missing: %v", a.Name, cfg, err)
				}
			}

			armed := read(t, root, a.ZeroWarningIn)
			if !strings.Contains(armed, a.ZeroWarningSetting) {
				t.Errorf("%s: %s no longer contains %q, so a warning would be reported rather than fail the build",
					a.Name, a.ZeroWarningIn, a.ZeroWarningSetting)
			}

			workflow := read(t, root, a.Workflow)
			if !strings.Contains(workflow, a.Invocation) {
				t.Errorf("%s is configured but %s never runs %q; a configured analyser that no job invokes proves nothing",
					a.Name, a.Workflow, a.Invocation)
			}
		})
	}
}

// TestEveryPinnedAnalyserIsDeclaredInTheManifest applies AGENTS.md Sec. 3 to the
// analysers themselves. Three of them carry copyleft licences - Checkstyle and
// SpotBugs are LGPL-2.1, golangci-lint is GPL-3.0 - and the rule is that such a
// dependency is declared with its name, version, licence and reason before it is
// used, not discovered later by a reviewer reading a workflow file.
//
// The check runs in both directions that matter: the version in the file that
// pins it must be the version this register names, and the manifest must name it
// too. A pin that drifts from either is the state where the declared licence no
// longer describes the binary CI actually downloads.
func TestEveryPinnedAnalyserIsDeclaredInTheManifest(t *testing.T) {
	root := repoRoot(t)
	manifest := read(t, root, "tools/toolchain/toolchain-manifest.json")

	declared := 0
	for _, a := range Stack {
		if a.Version == "" {
			continue // versioned with AGP or with the Swift toolchain; see the manifest note
		}
		declared++
		t.Run(a.Name, func(t *testing.T) {
			pin := read(t, root, a.PinnedIn)
			if !strings.Contains(pin, a.Version) {
				t.Errorf("%s: %s does not pin %s", a.Name, a.PinnedIn, a.Version)
			}
			if !strings.Contains(manifest, a.Version) {
				t.Errorf("%s %s is pinned in %s but never declared in the toolchain manifest "+
					"(AGENTS.md Sec. 3: name, version, licence and reason)", a.Name, a.Version, a.PinnedIn)
			}
		})
	}
	if declared == 0 {
		t.Fatal("no analyser carries a pinned version; the register has lost its version fields")
	}

	// The copyleft licences must be stated, not merely implied by the tool name.
	for _, licence := range []string{"LGPL-2.1-or-later", "GPL-3.0-only"} {
		if !strings.Contains(manifest, licence) {
			t.Errorf("the manifest does not state %s; copyleft licences are declared explicitly", licence)
		}
	}
}

// TestStackCoversEveryLanguageTheRepositoryOwns is the completeness half. The
// four languages are the ones TRS Sec. 5.3 names, and a stack that silently
// stopped covering one would still be green in every job that remained.
func TestStackCoversEveryLanguageTheRepositoryOwns(t *testing.T) {
	covered := map[string]int{}
	for _, a := range Stack {
		covered[a.Language]++
	}
	for _, lang := range []string{"go", "java", "swift", "ts"} {
		if covered[lang] == 0 {
			t.Errorf("no TRS-CODE-006 analyser covers %s", lang)
		}
	}
}

// TestGolangciEnforcesTheComplexityBudgetAndReportsEverything checks the two
// settings that decide whether the Go gate means anything. golangci-lint
// defaults to printing at most 50 issues per linter and 3 of any one kind; on a
// tree with more findings than that, the run exits non-zero having HIDDEN the
// rest, so a later fix of the visible ones would turn the gate green while the
// hidden ones remained.
func TestGolangciEnforcesTheComplexityBudgetAndReportsEverything(t *testing.T) {
	cfg := read(t, repoRoot(t), ".golangci.yml")

	for _, unlimited := range []string{"max-issues-per-linter: 0", "max-same-issues: 0"} {
		if !strings.Contains(cfg, unlimited) {
			t.Errorf("missing %q: a capped report hides findings past the cap", unlimited)
		}
	}
	// TRS-CODE-004 budgets complexity at 15 per function.
	if !strings.Contains(cfg, "min-complexity: 15") {
		t.Error("gocyclo must carry the TRS-CODE-004 budget of 15")
	}
	if !strings.Contains(cfg, "- gocyclo") {
		t.Error("gocyclo must be enabled; it is what applies the TRS-CODE-004 budget to Go")
	}
}

// TestGosecExclusionsAreScopedToBuildTooling guards the one thing that would
// quietly gut the Go security analysis: an exclusion that lost its path scope.
// `- path: ^tools/` is what keeps backend/ and protocol/ - the code that will
// handle untrusted bytes - fully analysed. An exclusion rule carrying gosec
// with no path is a blanket disable wearing the shape of a scoped one.
func TestGosecExclusionsAreScopedToBuildTooling(t *testing.T) {
	cfg := read(t, repoRoot(t), ".golangci.yml")

	for _, block := range strings.Split(cfg, "- path:")[1:] {
		// Each block runs to the next rule; only inspect its own lines.
		if !strings.Contains(block, "gosec") {
			continue
		}
		scope := strings.TrimSpace(strings.SplitN(block, "\n", 2)[0])
		if scope != "^tools/" && !strings.HasPrefix(scope, "^protocol/schema/") && scope != "_test\\.go" {
			t.Errorf("gosec is excluded for scope %q; the exclusions are meant to cover build tooling only", scope)
		}
	}
}

// TestCheckstyleCarriesTheSpecifiedBudgets asserts the numbers rather than the
// module names: Checkstyle ships defaults for both, and a config that lost its
// <property> lines would still run and still pass.
func TestCheckstyleCarriesTheSpecifiedBudgets(t *testing.T) {
	cfg := read(t, repoRoot(t), "tools/staticanalysis/checkstyle/checkstyle.xml")

	// TRS-CODE-004: <= 800 lines per file, <= 15 cyclomatic complexity.
	for _, want := range []string{
		`<module name="FileLength">`,
		`<property name="max" value="800"/>`,
		`<module name="CyclomaticComplexity">`,
		`<property name="max" value="15"/>`,
	} {
		if !strings.Contains(cfg, want) {
			t.Errorf("checkstyle.xml is missing %s", want)
		}
	}
}

// TestSwiftLintCarriesTheSpecifiedBudgets does the same for Swift. SwiftLint's
// own defaults are 400/1000 lines per file and a complexity of 10, none of
// which are the TRS numbers, so an accidentally emptied config would fail this
// repository for the wrong reasons in one direction and pass it in the other.
func TestSwiftLintCarriesTheSpecifiedBudgets(t *testing.T) {
	cfg := read(t, repoRoot(t), ".swiftlint.yml")

	for _, want := range []string{
		"file_length:",
		"type_body_length:",
		"cyclomatic_complexity:",
		"warning: 800",
		"warning: 500",
		"warning: 15",
	} {
		if !strings.Contains(cfg, want) {
			t.Errorf(".swiftlint.yml is missing %q", want)
		}
	}
	// The generated register must stay out of scope; see the file's own note.
	if !strings.Contains(cfg, "- protocol/gen/swift") {
		t.Error("the generated Swift register must be excluded; nobody may edit it in place")
	}
}

// TestSwiftTargetsAreAllInSwift6LanguageMode covers the half of TRS-CODE-006
// that is not a linter. "Swift 6 strict concurrency diagnostics" is a compiler
// mode, and a target added without it compiles under Swift 5 rules and reports
// nothing - which reads identically to a target with no concurrency problems.
func TestSwiftTargetsAreAllInSwift6LanguageMode(t *testing.T) {
	manifest := read(t, repoRoot(t), "Package.swift")

	targets := strings.Count(manifest, ".target(") + strings.Count(manifest, ".testTarget(")
	modes := strings.Count(manifest, ".swiftLanguageMode(.v6)")
	if targets == 0 {
		t.Fatal("Package.swift declares no targets; the parse above is wrong")
	}
	if modes != targets {
		t.Errorf("%d targets but %d declare .swiftLanguageMode(.v6); every target must be in Swift 6 mode",
			targets, modes)
	}
}

// TestAppModuleWiresSpotbugsOnceItHasProductionCode is a dated exemption with an
// expiry that fires by itself.
//
// SpotBugs analyses compiled bytecode. The Android module is still the empty
// WP-01 shell: it has no main source set, so a SpotBugs task there would report
// success having examined nothing. Rather than wire a task that proves nothing,
// the omission is recorded here with the exact condition that ends it - the
// first production source file. WP-02 adds one within days of this commit, and
// this test fails that day rather than whenever somebody next re-reads the
// build file.
func TestAppModuleWiresSpotbugsOnceItHasProductionCode(t *testing.T) {
	root := repoRoot(t)
	mainSrc := filepath.Join(root, filepath.FromSlash("android-app/app/src/main/java"))

	if _, err := os.Stat(mainSrc); os.IsNotExist(err) {
		return // still the empty shell; the exemption holds
	} else if err != nil {
		t.Fatalf("stat %s: %v", mainSrc, err)
	}

	build := read(t, root, "android-app/app/build.gradle.kts")
	if !strings.Contains(build, "spotbugs") {
		t.Error("android-app/app now has production sources, so SpotBugs must be wired into that module: " +
			"the exemption in its build file was conditional on the module being empty")
	}
}

// TestAndroidVariantTestsCoverAllFourEnvironments closes the WP-01 TRS-REP-004
// gap from the CI side. The variant assertions live in BuildVariantTest.java and
// run once per flavour, so the evidence is four separate task invocations; a
// pipeline that ran only testDevDebugUnitTest would be green having checked one
// environment of four.
func TestAndroidVariantTestsCoverAllFourEnvironments(t *testing.T) {
	root := repoRoot(t)
	workflow := read(t, root, ".github/workflows/conformance.yml")

	for _, variant := range []string{
		"testDevDebugUnitTest",
		"testQaDebugUnitTest",
		"testStagingDebugUnitTest",
		"testProdDebugUnitTest",
	} {
		if !strings.Contains(workflow, variant) {
			t.Errorf("conformance.yml does not run %s; TRS-REP-004 needs all four variants asserted", variant)
		}
	}

	// The four flavours must also still declare the environment names
	// TRS-REP-004 uses. The whole buildConfigField line is matched rather than
	// the bare word: "test" and "staging" appear in this build file for other
	// reasons, and a check they satisfied by accident would survive the field
	// being deleted.
	build := read(t, root, "android-app/app/build.gradle.kts")
	for _, env := range []string{"development", "test", "staging", "production"} {
		field := `buildConfigField("String", "LUMOS_ENVIRONMENT", "\"` + env + `\"")`
		if !strings.Contains(build, field) {
			t.Errorf("android-app/app/build.gradle.kts no longer sets LUMOS_ENVIRONMENT to %q", env)
		}
	}
}
