// Lumos - tools/tracecheck / Tests for the executed-test collector.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-AI-003, TRS-CICD-007
// ADRs: -
// Threading: any
// Notes: Every defect this collector has had was a naming defect - Gradle
// writing a @DisplayName sentence into the name attribute, Swift wrapping a
// method as "-[Suite method]", node:test emitting the placeholder
// classname="test" for every case. None of them failed loudly; each produced
// identifiers that matched no matrix row, so the honesty gate went quiet
// instead of red. The fixtures below are the real output shape of each of the
// three runners, and they assert the EXACT identifier produced.
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

// Gradle writes one file per test class, rooted at <testsuite>, with the fully
// qualified class name in both the suite name and every classname attribute.
func TestCollectsGradleJUnitReports(t *testing.T) {
	const report = `<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.lumos.protocol.FramingConformanceTest" tests="2" failures="0">
  <testcase name="roundTripsEveryPositiveVector()" classname="com.lumos.protocol.FramingConformanceTest"/>
  <testcase name="rejectsEveryInvalidVector()" classname="com.lumos.protocol.FramingConformanceTest"/>
</testsuite>`
	assertIDs(t, collect(t, report, "java", "protocol-conformance"), []string{
		"java:protocol-conformance:FramingConformanceTest.rejectsEveryInvalidVector",
		"java:protocol-conformance:FramingConformanceTest.roundTripsEveryPositiveVector",
	})
}

// SwiftPM's --xunit-output wraps suites in <testsuites> and may write the
// Objective-C selector form "-[Suite method]" as the case name.
func TestCollectsSwiftXUnitReports(t *testing.T) {
	const report = `<?xml version="1.0" encoding="UTF-8"?>
<testsuites>
  <testsuite name="LumosProtocolConformance.CryptoConformanceTests" tests="2" failures="0">
    <testcase classname="LumosProtocolConformance.CryptoConformanceTests" name="testSafetyNumberReproducesDigitsAndEmoji"/>
    <testcase classname="LumosProtocolConformance.CryptoConformanceTests" name="-[CryptoConformanceTests testAeadTamperCases]"/>
  </testsuite>
</testsuites>`
	assertIDs(t, collect(t, report, "swift", "LumosProtocolConformance"), []string{
		"swift:LumosProtocolConformance:CryptoConformanceTests.testAeadTamperCases",
		"swift:LumosProtocolConformance:CryptoConformanceTests.testSafetyNumberReproducesDigitsAndEmoji",
	})
}

// node:test writes the describe() block as the suite and the literal string
// "test" as every case's classname. Preferring classname here would collapse
// the whole module into identifiers beginning "test.".
func TestCollectsNodeTestJUnitReports(t *testing.T) {
	const report = `<?xml version="1.0" encoding="utf-8"?>
<testsuites>
  <testsuite name="AdminShell" tests="2" failures="0">
    <testcase name="fourBuildEnvironmentsAreSeparated" classname="test" file="dist/shell.test.js"/>
    <testcase name="buildProvenanceIsRendered" classname="test" file="dist/shell.test.js"/>
  </testsuite>
</testsuites>`
	assertIDs(t, collect(t, report, "ts", "admin-console"), []string{
		"ts:admin-console:AdminShell.buildProvenanceIsRendered",
		"ts:admin-console:AdminShell.fourBuildEnvironmentsAreSeparated",
	})
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
