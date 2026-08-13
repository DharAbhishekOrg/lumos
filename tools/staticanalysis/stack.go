// Lumos - tools/staticanalysis / The TRS-CODE-006 analyser stack, described once.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CODE-006, TRS-CICD-001
// ADRs: -
// Threading: any
// Notes: TRS-CODE-006 names a stack of analysers and one property of all of
// them: zero warnings. Two things can go wrong with a requirement shaped like
// that, and neither is visible from a green pipeline.
//
// The first is a MISSING tool. WP-01 left this requirement not_started for
// exactly that reason - part of the stack was wired and the closeout refused to
// call a partial gate the gate. Stack below is the full list, transcribed from
// the requirement text clause by clause, so completeness is something a test
// counts rather than something a reader has to notice.
//
// The second is a tool that still runs but has stopped FAILING: a
// maxWarnings that drifts off zero, an ignoreFailures that flips, a --strict
// that gets dropped from a command line during an unrelated edit. Each entry
// therefore records the exact text that makes its tool fail on a warning, and
// the test asserts that text is present where the tool is configured. That is
// the same defect shape as the collector drifts of WP-01 Sec. 5.1: the job goes
// on reporting success while the evidence quietly stops counting.
//
// This file only DESCRIBES the stack. The proof that it is zero-warning is the
// five jobs themselves going green in the same pipeline run; no test here can
// substitute for that, and none pretends to.
package staticanalysis

// Analyser is one member of the TRS-CODE-006 stack.
type Analyser struct {
	// Name is the tool as TRS-CODE-006 names it.
	Name string
	// Language is the half of the codebase it covers.
	Language string
	// ConfigPaths are the repository-relative files that configure it. Empty
	// for a tool configured entirely on its command line.
	ConfigPaths []string
	// ZeroWarningSetting is the literal text that makes this tool fail on a
	// warning rather than report one.
	ZeroWarningSetting string
	// ZeroWarningIn is the repository-relative file that must contain
	// ZeroWarningSetting.
	ZeroWarningIn string
	// Workflow is the CI workflow that runs it.
	Workflow string
	// Invocation is text that must appear in that workflow, proving the tool is
	// actually run rather than merely configured.
	Invocation string
	// Version is the pinned version, or "" for a tool versioned with something
	// else (Android Lint ships inside AGP; Swift 6 mode is the compiler's).
	Version string
	// PinnedIn is the repository-relative file carrying that version.
	PinnedIn string
}

// Stack is the TRS-CODE-006 analysers, in the order the requirement lists them:
// Checkstyle + SpotBugs + Android Lint for Java, SwiftLint and Swift 6 strict
// concurrency for iOS, golangci-lint for Go, ESLint + tsc strict for the admin
// console. Adding a tool is a change to this list first; the tests then demand
// its configuration and its CI step.
var Stack = []Analyser{
	{
		Name:               "Checkstyle",
		Language:           "java",
		ConfigPaths:        []string{"tools/staticanalysis/checkstyle/checkstyle.xml"},
		ZeroWarningSetting: "maxWarnings = 0",
		ZeroWarningIn:      "android-app/protocol-conformance/build.gradle.kts",
		Workflow:           ".github/workflows/pipeline.yml",
		Invocation:         ":protocol-conformance:staticAnalysis",
		Version:            "12.3.1",
		PinnedIn:           "gradle/libs.versions.toml",
	},
	{
		Name:               "SpotBugs",
		Language:           "java",
		ConfigPaths:        []string{"tools/staticanalysis/spotbugs/exclude.xml"},
		ZeroWarningSetting: "ignoreFailures.set(false)",
		ZeroWarningIn:      "android-app/protocol-conformance/build.gradle.kts",
		Workflow:           ".github/workflows/pipeline.yml",
		Invocation:         ":protocol-conformance:staticAnalysis",
		Version:            "4.10.3",
		PinnedIn:           "gradle/libs.versions.toml",
	},
	{
		Name:     "Android Lint",
		Language: "java",
		// Configured inline in the module's `lint {}` block rather than in a
		// file of its own, which is where AGP expects it.
		ZeroWarningSetting: "warningsAsErrors = true",
		ZeroWarningIn:      "android-app/app/build.gradle.kts",
		Workflow:           ".github/workflows/pipeline.yml",
		Invocation:         ":app:lintDevDebug",
	},
	{
		Name:               "SwiftLint",
		Language:           "swift",
		ConfigPaths:        []string{".swiftlint.yml"},
		ZeroWarningSetting: "--strict",
		ZeroWarningIn:      ".github/workflows/conformance.yml",
		// SwiftLint rides the existing macOS job in conformance.yml rather than
		// spending a second macOS runner. Every Swift source it reads lives in
		// that job's path filter, and .swiftlint.yml was added to the filter
		// alongside them.
		Workflow:   ".github/workflows/conformance.yml",
		Invocation: "swiftlint lint",
		Version:    "0.65.0",
		PinnedIn:   ".github/workflows/conformance.yml",
	},
	{
		Name:     "Swift 6 strict concurrency",
		Language: "swift",
		// Not a separate tool: it is the compiler, put into Swift 6 language
		// mode by every target in Package.swift. The zero-warning half is
		// -warnings-as-errors on the CI build, without which a concurrency
		// diagnostic is printed and ignored.
		ConfigPaths:        []string{"Package.swift"},
		ZeroWarningSetting: "-warnings-as-errors",
		ZeroWarningIn:      ".github/workflows/conformance.yml",
		Workflow:           ".github/workflows/conformance.yml",
		Invocation:         "swift build",
	},
	{
		Name:               "golangci-lint",
		Language:           "go",
		ConfigPaths:        []string{".golangci.yml"},
		ZeroWarningSetting: "max-issues-per-linter: 0",
		ZeroWarningIn:      ".golangci.yml",
		Workflow:           ".github/workflows/pipeline.yml",
		Invocation:         "golangci-lint run",
		Version:            "2.12.2",
		PinnedIn:           ".github/workflows/pipeline.yml",
	},
	{
		Name:     "ESLint + tsc strict",
		Language: "ts",
		ConfigPaths: []string{
			"admin-console/eslint.config.js",
			"admin-console/tsconfig.json",
		},
		ZeroWarningSetting: "--max-warnings=0",
		ZeroWarningIn:      "admin-console/package.json",
		Workflow:           ".github/workflows/pipeline.yml",
		Invocation:         "npm run lint",
		Version:            "9.39.5",
		PinnedIn:           "admin-console/package.json",
	},
}

// ByName finds an analyser by the name TRS-CODE-006 gives it.
func ByName(name string) (Analyser, bool) {
	for _, a := range Stack {
		if a.Name == name {
			return a, true
		}
	}
	return Analyser{}, false
}
