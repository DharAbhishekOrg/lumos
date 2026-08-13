// Lumos - tools/tracecheck / Tests for the executed-test collector.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-AI-003, TRS-CICD-007
// ADRs: -
// Threading: any
// Notes: Every defect this collector has had was a naming defect - Gradle
// writing a @DisplayName sentence into the name attribute, a missing Swift
// xUnit report, node:test emitting the placeholder classname="test", and
// SwiftPM emitting the placeholder name="TestResults" on the other field. None
// of them failed loudly; each produced identifiers that matched no matrix row,
// so the honesty gate went quiet instead of red.
//
// The fixtures here are CAPTURED from a real CI artefact, not composed. That is
// not pedantry: the invented Swift fixture written in slice S7 is precisely why
// the fourth defect shipped.
package main

import (
	"os"
	"path/filepath"
	"testing"
)

func writeReport(t *testing.T, body string) string {
	t.Helper()
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "results.xml"), []byte(body), 0o644); err != nil {
		t.Fatalf("write report: %v", err)
	}
	return dir
}

func collect(t *testing.T, body, lang, module string) []string {
	t.Helper()
	got, err := CollectJUnitTests(writeReport(t, body), lang, module)
	if err != nil {
		t.Fatalf("CollectJUnitTests: %v", err)
	}
	return got
}

func assertIDs(t *testing.T, got, want []string) {
	t.Helper()
	if len(got) != len(want) {
		t.Fatalf("collected %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("identifier %d = %q, want %q", i, got[i], want[i])
		}
	}
}

// The three fixtures below are CAPTURED, not composed. Each is a verbatim
// excerpt of an artefact downloaded from CI run 31540270055. That distinction is
// the point of this file: slice S7's Swift fixture was written from what I
// assumed SwiftPM emitted - a <testsuite> named for the class - and the real
// output names every suite "TestResults" and puts the class in classname. The
// invented fixture passed while every Swift identifier in the matrix silently
// matched nothing. A fixture that agrees with its author instead of with the
// tool is the same self-agreement defect the golden vectors exist to prevent.

// Gradle: one file per class, rooted at <testsuite>, fully qualified class name
// in BOTH the suite name and every classname attribute.
func TestCollectsGradleJUnitReports(t *testing.T) {
	const report = `<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.lumos.protocol.conformance.CryptoConformanceTest" tests="7" skipped="0" failures="0" errors="0" timestamp="2026-08-11T21:58:11.429Z" hostname="runnervmzvulz" time="0.599">
  <properties/>
  <testcase name="aeadKat()" classname="com.lumos.protocol.conformance.CryptoConformanceTest" time="0.221"/>
  <testcase name="advertisement()" classname="com.lumos.protocol.conformance.CryptoConformanceTest" time="0.023"/>
</testsuite>`
	assertIDs(t, collect(t, report, "java", "protocol-conformance"), []string{
		"java:protocol-conformance:CryptoConformanceTest.advertisement",
		"java:protocol-conformance:CryptoConformanceTest.aeadKat",
	})
}

// SwiftPM's --xunit-output: ONE <testsuite name="TestResults"> wrapping every
// case, with the real suite in classname. Preferring the suite name here is
// what broke every Swift identifier in slice S7.
func TestCollectsSwiftXUnitReports(t *testing.T) {
	const report = `<?xml version="1.0" encoding="UTF-8"?>

<testsuites>
<testsuite name="TestResults" errors="0" tests="10" failures="0" time="1.5345801280000002">
<testcase classname="LumosProtocolConformance.CryptoConformanceTests" name="testConfigPackagesAndVouchers" time="0.261370167">
</testcase>
<testcase classname="LumosProtocolConformance.CryptoConformanceTests" name="testAEADKnownAnswersAndTampers" time="0.262349459">
</testcase>
</testsuite>
</testsuites>`
	assertIDs(t, collect(t, report, "swift", "LumosProtocolConformance"), []string{
		"swift:LumosProtocolConformance:CryptoConformanceTests.testAEADKnownAnswersAndTampers",
		"swift:LumosProtocolConformance:CryptoConformanceTests.testConfigPackagesAndVouchers",
	})
}

// node:test writes the describe() block as the suite and the literal string
// "test" as every classname - the mirror image of SwiftPM. Preferring classname
// here would collapse the whole module into identifiers beginning "test.".
func TestCollectsNodeTestJUnitReports(t *testing.T) {
	const report = `<?xml version="1.0" encoding="utf-8"?>
<testsuites>
	<testsuite name="AdminShell" time="0.002169" disabled="0" errors="0" tests="2" failures="0" skipped="0">
		<testcase name="fourBuildEnvironmentsAreSeparated" time="0.000740" classname="test" file="dist/shell.test.js"/>
		<testcase name="buildProvenanceIsRendered" time="0.000099" classname="test" file="dist/shell.test.js"/>
	</testsuite>
</testsuites>`
	assertIDs(t, collect(t, report, "ts", "admin-console"), []string{
		"ts:admin-console:AdminShell.buildProvenanceIsRendered",
		"ts:admin-console:AdminShell.fourBuildEnvironmentsAreSeparated",
	})
}

// TestPlaceholdersSitOnOppositeFields is the regression guard. Neither "always
// prefer classname" nor "always prefer the suite name" can satisfy both runners,
// so a future simplification to either one must fail here.
func TestPlaceholdersSitOnOppositeFields(t *testing.T) {
	cases := []struct {
		name             string
		suite, className string
		want             string
	}{
		{"swiftpm places the placeholder on the suite", "TestResults", "Module.CryptoConformanceTests", "Module.CryptoConformanceTests"},
		{"node places it on the classname", "AdminShell", "test", "AdminShell"},
		{"gradle agrees with itself", "com.lumos.X", "com.lumos.X", "com.lumos.X"},
		{"neither field is useful", "TestResults", "test", "test"},
		{"only a suite name exists", "OnlySuite", "", "OnlySuite"},
	}
	for _, c := range cases {
		if got := suiteName(c.suite, c.className); got != c.want {
			t.Errorf("%s: suiteName(%q, %q) = %q, want %q", c.name, c.suite, c.className, got, c.want)
		}
	}
}

// TestIdentifierShapeDriftIsCaught: the collector renaming everything a runner
// produces must be LOUD. This is the check that would have caught the S7 Swift
// break in the run that introduced it.
// TestADeadIdentifierAmongLiveOnesIsCaught covers the gap the pair-level rule
// leaves: a handful of stale identifiers hiding behind matching ones in the
// same module. This is the WP-02 S2 case - four StorageEncryptionTest
// identifiers matched nothing because of the "(Path)" suffix, while every row
// carrying them stayed green on other tests in java:app-dev.
func TestADeadIdentifierAmongLiveOnesIsCaught(t *testing.T) {
	m := &Matrix{Entries: []Entry{{
		ReqID:  "SRS-DAT-002",
		Status: StatusTested,
		Code:   []string{"android-app/app/src/main/java/com/lumos/app/store/LocalStore.java"},
		Tests: []string{
			"java:app-dev:LocalStoreTest.recordsRoundTripPerKind",
			"java:app-dev:StorageEncryptionTest.noSensitiveValueAppearsInTheDatabaseFile",
		},
	}}}

	// The module produced tests; one linked identifier is not among them.
	partial := &ExecutedTests{RunID: "31746507818", Tests: []string{
		"java:app-dev:LocalStoreTest.recordsRoundTripPerKind",
		"java:app-dev:StorageEncryptionTest.noSensitiveValueAppearsInTheDatabaseFile(Path)",
	}}

	// The pair-level rule is deaf to this: one linked identifier DID match.
	if f := checkIdentifierShapes(m, partial); len(f) != 0 {
		t.Errorf("the pair-level rule should stay quiet here; that is why the stricter one exists: %v", f)
	}

	findings := checkEveryLinkedIdentifierRan(m, partial)
	if len(findings) != 1 || findings[0].Rule != "linked-test-did-not-run" {
		t.Fatalf("the dead identifier was not flagged: %v", findings)
	}
	if findings[0].ReqID != "SRS-DAT-002" {
		t.Errorf("finding names %q, want the requirement carrying the stale link", findings[0].ReqID)
	}

	// Every identifier matching: silent.
	healthy := &ExecutedTests{RunID: "1", Tests: []string{
		"java:app-dev:LocalStoreTest.recordsRoundTripPerKind",
		"java:app-dev:StorageEncryptionTest.noSensitiveValueAppearsInTheDatabaseFile",
	}}
	if f := checkEveryLinkedIdentifierRan(m, healthy); len(f) != 0 {
		t.Errorf("healthy identifiers were flagged: %v", f)
	}

	// A pair that produced NOTHING is a path-filtered job, not drift. Flagging
	// it would make the Swift filter unusable.
	skipped := &ExecutedTests{RunID: "1", Tests: []string{"go:protocol/wire:TestRoundTrip"}}
	if f := checkEveryLinkedIdentifierRan(m, skipped); len(f) != 0 {
		t.Errorf("a skipped runner must not be reported as drift: %v", f)
	}
}

func TestIdentifierShapeDriftIsCaught(t *testing.T) {
	m := &Matrix{Entries: []Entry{{
		ReqID:  "TRS-PROTO-001",
		Status: StatusTested,
		Code:   []string{"protocol/wire/frame.go"},
		Tests: []string{
			"go:protocol/wire:TestRoundTrip",
			"swift:LumosProtocolConformance:FramingConformanceTests.testFrameRoundtrip",
		},
	}}}

	// The Swift runner reported tests, but under drifted identifiers.
	drifted := &ExecutedTests{RunID: "1", Tests: []string{
		"go:protocol/wire:TestRoundTrip",
		"swift:LumosProtocolConformance:TestResults.testFrameRoundtrip",
	}}
	findings := checkIdentifierShapes(m, drifted)
	if len(findings) != 1 || findings[0].Rule != "identifier-shape-drift" {
		t.Fatalf("drifted Swift identifiers were not flagged: %v", findings)
	}
	if findings[0].ReqID != "swift:LumosProtocolConformance" {
		t.Errorf("finding names %q, want the lang:module pair", findings[0].ReqID)
	}

	// Matching identifiers: silent.
	matching := &ExecutedTests{RunID: "1", Tests: []string{
		"go:protocol/wire:TestRoundTrip",
		"swift:LumosProtocolConformance:FramingConformanceTests.testFrameRoundtrip",
	}}
	if f := checkIdentifierShapes(m, matching); len(f) != 0 {
		t.Errorf("healthy identifiers were flagged: %v", f)
	}

	// A runner that produced NOTHING is a skipped job, not drift. The Swift
	// path filter skips macOS legitimately, and flagging that would make the
	// filter unusable; the collector's fail-on-empty rule covers the real case.
	skipped := &ExecutedTests{RunID: "1", Tests: []string{"go:protocol/wire:TestRoundTrip"}}
	if f := checkIdentifierShapes(m, skipped); len(f) != 0 {
		t.Errorf("a skipped runner was reported as drift: %v", f)
	}
}

// TestEveryRunnerLanguageIsATestReference stops a new runner being wired into
// CI while the honesty check silently ignores its identifiers - which is what
// happened to "ts" between slice S7 and the WP-01 closeout.
// gradleInjectedParameterFixture is a VERBATIM excerpt of
// TEST-com.lumos.app.store.StorageEncryptionTest.xml, downloaded from CI run
// 31746507818. It is here because it is the exact artefact that exposed the
// defect: JUnit writes an INJECTED PARAMETER TYPE into the name attribute for a
// test taking @TempDir, so "noSensitive...(Path)" reached the matrix and four
// linked identifiers matched nothing while their rows stayed green on other
// tests in the same module.
const gradleInjectedParameterFixture = `<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.lumos.app.store.StorageEncryptionTest" tests="2" skipped="0" failures="0" errors="0" time="0.111">
  <testcase name="aRowMovedToAnotherIdCannotBeOpened(Path)" classname="com.lumos.app.store.StorageEncryptionTest" time="0.023"/>
  <testcase name="noSensitiveValueAppearsInTheDatabaseFile(Path)" classname="com.lumos.app.store.StorageEncryptionTest" time="0.04"/>
</testsuite>`

// TestInjectedParameterIsNotPartOfTheIdentifier pins the WP-02 S2 fix.
func TestInjectedParameterIsNotPartOfTheIdentifier(t *testing.T) {
	assertIDs(t, collect(t, gradleInjectedParameterFixture, "java", "app-dev"), []string{
		"java:app-dev:StorageEncryptionTest.aRowMovedToAnotherIdCannotBeOpened",
		"java:app-dev:StorageEncryptionTest.noSensitiveValueAppearsInTheDatabaseFile",
	})
}

// TestDisplayNameSentencesKeepTheirParentheses is the other half of the same
// fix. The name attribute also carries @DisplayName sentences, which may end in
// a parenthetical; truncating one of those is how this collector mangled Java
// identifiers in slice S6. The trim applies only when what precedes the
// parenthesis is a single word.
func TestDisplayNameSentencesKeepTheirParentheses(t *testing.T) {
	const fixture = `<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.lumos.protocol.conformance.CryptoConformanceTest" tests="1" skipped="0" failures="0" errors="0" time="0.1">
  <testcase name="the alphabet survives font substitution (mostly)" classname="com.lumos.protocol.conformance.CryptoConformanceTest" time="0.01"/>
</testsuite>`
	assertIDs(t, collect(t, fixture, "java", "protocol-conformance"), []string{
		"java:protocol-conformance:CryptoConformanceTest.the alphabet survives font substitution (mostly)",
	})
}

func TestEveryRunnerLanguageIsATestReference(t *testing.T) {
	for _, lang := range TestLanguages {
		id := lang + ":module:Suite.method"
		if !isTestReference(id) {
			t.Errorf("%q is a declared runner language but %q is not treated as a test reference", lang, id)
		}
	}
	for _, notATest := range []string{"ci:conformance#123", "vector:test-vectors/framing/frame_roundtrip.json", ""} {
		if isTestReference(notATest) {
			t.Errorf("%q is evidence of a different kind and must not count as an executed test", notATest)
		}
	}
}

// Only passing tests are evidence. A failed, errored or skipped case that
// counted as executed would let a red run satisfy the honesty gate.
func TestFailedAndSkippedCasesAreNotEvidence(t *testing.T) {
	const report = `<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="Suite" tests="4" failures="1">
  <testcase name="passes" classname="Suite"/>
  <testcase name="fails" classname="Suite"><failure message="boom"/></testcase>
  <testcase name="errors" classname="Suite"><error message="boom"/></testcase>
  <testcase name="skipped" classname="Suite"><skipped/></testcase>
</testsuite>`
	assertIDs(t, collect(t, report, "java", "m"), []string{"java:m:Suite.passes"})
}

// A report the parser does not understand must yield nothing rather than
// crash the run - and the caller's fail-on-empty rule then makes that visible.
func TestUnparseableReportYieldsNothing(t *testing.T) {
	assertIDs(t, collect(t, "<not-a-report/>", "java", "m"), nil)
}
