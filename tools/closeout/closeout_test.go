// Lumos - tools/closeout / Tests for the closeout report generator.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-AI-003, TRS-AI-005
// ADRs: -
// Threading: any
// Notes: Two rules matter here and both are asserted against fixtures that must
// fail. TestScopeIsTheKickoffSetNotWhatMoved proves the scope is transcribed
// from the kickoff rather than read back from the work - a scope derived from
// what was completed can never report anything incomplete. And
// TestReportNeverUpgradesAStatus proves the generator is a reader: a closeout
// document that can change a status is a way to declare victory in prose.
package main

import (
	"encoding/json"
	"fmt"
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

// TestScopeIsTheKickoffSetNotWhatMoved pins the membership rule against IDs the
// kickoff names and IDs it deliberately does not.
func TestScopeIsTheKickoffSetNotWhatMoved(t *testing.T) {
	// Transcribed by hand from the WP-01 kickoff's "Exit evidence" clause.
	inScope := []string{
		"TRS-REP-001", "TRS-REP-008",
		"TRS-CONST-001", "TRS-CONST-005",
		"TRS-PROTO-001", "TRS-PROTO-009", "TRS-PROTO-016",
		"TRS-CICD-001", "TRS-CICD-005",
		"TRS-TST-001",
	}
	for _, id := range inScope {
		if !InScope("WP-01", id) {
			t.Errorf("%s is named by the kickoff but reads as out of scope", id)
		}
	}

	// These moved during WP-01 but the kickoff does not claim them. Counting
	// them as in-scope would inflate the completion figure.
	outOfScope := []string{
		"TRS-PROTO-017", "TRS-PROTO-018", // deferred to WP-03 by owner decision
		"TRS-CICD-004", "TRS-CICD-007", "TRS-CICD-008",
		"TRS-CODE-001", "TRS-CODE-003", "TRS-CODE-005",
		"TRS-CRY-001", "TRS-ARCH-006", "TRS-TST-002",
		"SRS-IDN-005", "SRS-NFR-CMP-004", "TRS-AI-003",
	}
	for _, id := range outOfScope {
		if InScope("WP-01", id) {
			t.Errorf("%s is not in the kickoff's exit-evidence set but reads as in scope", id)
		}
	}
}

// TestWPTwoScopeIsTheBriefNotWhatMoved does for WP-02 what the test above does
// for WP-01, and the transcription is deliberately independent of the one in
// closeout.go: reading the scope back out of the file it is meant to verify
// would prove only that the file agrees with itself.
//
// The count matters as much as the membership. EDP Sec. 7's WP-02 brief names
// forty-five requirement IDs; if a pattern here quietly matched forty-two, the
// closeout would report a higher completion rate against a smaller scope.
func TestWPTwoScopeIsTheBriefNotWhatMoved(t *testing.T) {
	// Transcribed by hand from the brief's "Key requirements" row.
	inScope := []string{
		"SRS-ONB-001", "SRS-ONB-008",
		"SRS-IDN-001", "SRS-IDN-002", "SRS-IDN-003", "SRS-IDN-007",
		"SRS-PRO-001", "SRS-PRO-009", "SRS-PRO-013",
		"SRS-SAF-009", "SRS-SAF-010",
		"SRS-DAT-002", "SRS-DAT-004", "SRS-DAT-012",
		"TRS-LOC-001", "TRS-LOC-010",
		"TRS-AND-004", "TRS-IOS-007",
		"SRS-NFR-SEC-002", "SRS-NFR-SEC-003", "SRS-NFR-SEC-013",
	}
	for _, id := range inScope {
		if !InScope("WP-02", id) {
			t.Errorf("%s is named by the WP-02 brief but reads as out of scope", id)
		}
	}

	// Moved during WP-02, or adjacent to the ranges above, and NOT claimed by
	// the brief. Counting any of them would inflate the completion figure.
	outOfScope := []string{
		"SRS-ONB-009", "SRS-IDN-004", "SRS-IDN-008", "SRS-PRO-014",
		"SRS-SAF-001", "SRS-SAF-011", "SRS-DAT-001", "SRS-DAT-003",
		"TRS-LOC-011", "TRS-AND-005", "TRS-IOS-006",
		"SRS-NFR-SEC-001", "SRS-NFR-SEC-004", "SRS-NFR-SEC-012",
		"SRS-NFR-LEG-007", "SRS-NFR-PRI-012",
		"TRS-CODE-004", "TRS-CICD-001", "TRS-REP-008", "TRS-CRY-001",
	}
	for _, id := range outOfScope {
		if InScope("WP-02", id) {
			t.Errorf("%s is not in the WP-02 brief but reads as in scope", id)
		}
	}

	// And the brief's arithmetic: eight, four, thirteen, two, three, ten, one,
	// one, three.
	want := 8 + 4 + 13 + 2 + 3 + 10 + 1 + 1 + 3
	got := 0
	for _, prefix := range []string{"SRS-ONB", "SRS-IDN", "SRS-PRO", "SRS-SAF",
		"SRS-DAT", "TRS-LOC", "TRS-AND", "TRS-IOS", "SRS-NFR-SEC"} {
		for n := 1; n <= 40; n++ {
			if InScope("WP-02", fmt.Sprintf("%s-%03d", prefix, n)) {
				got++
			}
		}
	}
	if got != want {
		t.Errorf("the WP-02 scope patterns match %d requirement IDs, the brief names %d", got, want)
	}
}

// TestEveryKnownWorkPackageHasPatterns keeps the -wp flag honest: a package the
// CLI accepts but has no patterns for would report an empty scope and a perfect
// completion rate.
func TestEveryKnownWorkPackageHasPatterns(t *testing.T) {
	for _, wp := range KnownWorkPackages() {
		if len(scopePatterns[wp]) == 0 {
			t.Errorf("%s is offered by the CLI and has no scope patterns", wp)
		}
	}
	if len(KnownWorkPackages()) < 2 {
		t.Error("WP-01 and WP-02 should both be closeable")
	}
}

// TestReportNeverUpgradesAStatus is the honesty assertion: whatever the matrix
// says is what the report says, including 'not_started' on a row that has a
// test linked to it.
func TestReportNeverUpgradesAStatus(t *testing.T) {
	m := &Matrix{
		Baseline: "fixture",
		Entries: []Entry{
			{ReqID: "TRS-REP-001", Status: "not_started"},
			{ReqID: "TRS-REP-002", Status: "implemented", Code: []string{"a.go"},
				Tests: []string{"go:pkg:TestOne"}},
			{ReqID: "TRS-TST-001", Status: "tested", Code: []string{"b.go"},
				Tests: []string{"go:pkg:TestTwo"}},
		},
	}
	// TestOne executed; TestTwo did not.
	executed := &ExecutedTests{RunID: "42", Tests: []string{"go:pkg:TestOne"}}
	r := Build("WP-01", m, executed, "abc1234")

	want := map[string]string{
		"TRS-REP-001": "not_started",
		"TRS-REP-002": "implemented",
		"TRS-TST-001": "tested",
	}
	for _, row := range r.InScope {
		if want[row.ReqID] != row.Status {
			t.Errorf("%s reported as %q, matrix says %q", row.ReqID, row.Status, want[row.ReqID])
		}
	}

	// The execution count must reflect the run, not the status. TRS-TST-001 is
	// 'tested' in this fixture yet none of its tests ran, and the report has to
	// say zero - that discrepancy is exactly what a reviewer needs to see.
	for _, row := range r.InScope {
		if row.ReqID == "TRS-TST-001" {
			if row.ExecutedIn == nil || *row.ExecutedIn != 0 {
				t.Errorf("TRS-TST-001 executed count = %v, want 0", row.ExecutedIn)
			}
		}
		if row.ReqID == "TRS-REP-002" {
			if row.ExecutedIn == nil || *row.ExecutedIn != 1 {
				t.Errorf("TRS-REP-002 executed count = %v, want 1", row.ExecutedIn)
			}
		}
	}
}

func TestSpilloverCapturesMovedOutOfScopeRows(t *testing.T) {
	m := &Matrix{Entries: []Entry{
		{ReqID: "TRS-REP-001", Status: "tested", Code: []string{"a"}},
		{ReqID: "TRS-CODE-001", Status: "tested", Code: []string{"b"}},
		{ReqID: "TRS-AND-001", Status: "not_started"},
	}}
	r := Build("WP-01", m, &ExecutedTests{}, "x")
	if len(r.InScope) != 1 || r.InScope[0].ReqID != "TRS-REP-001" {
		t.Errorf("in-scope rows = %v", r.InScope)
	}
	if len(r.Spillover) != 1 || r.Spillover[0].ReqID != "TRS-CODE-001" {
		t.Errorf("spillover rows = %v", r.Spillover)
	}
	// An untouched out-of-scope row is neither: it is simply not this work
	// package's business, and listing it would bury the rows that are.
	if r.Totals.SpilloverCount != 1 {
		t.Errorf("spillover count = %d, want 1", r.Totals.SpilloverCount)
	}
}

func TestByLanguageCountsEveryReportingRunner(t *testing.T) {
	executed := &ExecutedTests{RunID: "7", Tests: []string{
		"go:protocol/wire:TestA", "go:protocol/wire:TestB",
		"java:protocol-conformance:FramingConformanceTest.roundTrips",
		"swift:LumosProtocolConformance:CryptoConformanceTests.testX",
		"swift:LumosAppShell:LumosAppShellTests.testY",
		"ts:admin-console:AdminShell.testZ",
	}}
	r := Build("WP-01", &Matrix{}, executed, "x")
	want := map[string]int{"go": 2, "java": 1, "swift": 2, "ts": 1}
	for lang, n := range want {
		if r.Pipeline.ByLanguage[lang] != n {
			t.Errorf("%s: %d executed, want %d", lang, r.Pipeline.ByLanguage[lang], n)
		}
	}
	if r.Pipeline.ExecutedTests != 6 {
		t.Errorf("total executed = %d, want 6", r.Pipeline.ExecutedTests)
	}
	if strings.Join(r.Pipeline.SourcesPresent, ",") != "go,java,swift,ts" {
		t.Errorf("languages = %v", r.Pipeline.SourcesPresent)
	}
}

// TestMissingExecutedSetReportsZeroRatherThanGuessing: a closeout generated
// without pipeline evidence must say so, not silently omit the field.
func TestMissingExecutedSetReportsZeroRatherThanGuessing(t *testing.T) {
	e, err := LoadExecuted("")
	if err != nil {
		t.Fatalf("LoadExecuted: %v", err)
	}
	r := Build("WP-01", &Matrix{Entries: []Entry{
		{ReqID: "TRS-REP-001", Status: "tested", Tests: []string{"go:p:T"}},
	}}, e, "x")
	if r.Pipeline.ExecutedTests != 0 || r.Pipeline.RunID != "" {
		t.Errorf("expected an empty pipeline result, got %+v", r.Pipeline)
	}
	if r.InScope[0].ExecutedIn != nil {
		t.Error("with no executed set the per-row count must be absent, not zero: " +
			"zero would read as 'the test did not run' rather than 'nothing was measured'")
	}
}

// TestLiveMatrixIsCoveredEndToEnd runs the generator over the real repository.
func TestLiveMatrixIsCoveredEndToEnd(t *testing.T) {
	root := repoRoot(t)
	m, err := LoadMatrix(root)
	if err != nil {
		t.Fatalf("LoadMatrix: %v", err)
	}
	r := Build("WP-01", m, &ExecutedTests{}, "test")

	if r.Totals.MatrixEntries != 648 {
		t.Errorf("matrix has %d entries, the baseline defines 648", r.Totals.MatrixEntries)
	}
	if r.Totals.InScopeCount == 0 {
		t.Fatal("no in-scope requirements; the scope patterns match nothing")
	}
	sum := 0
	for _, n := range r.Totals.InScopeByStatus {
		sum += n
	}
	if sum != r.Totals.InScopeCount {
		t.Errorf("status counts sum to %d but %d rows are in scope", sum, r.Totals.InScopeCount)
	}
	for _, row := range r.InScope {
		if row.Status == "tested" && row.TestCount == 0 {
			t.Errorf("%s is tested with no linked test", row.ReqID)
		}
	}
}

// TestOwnerGatesAreCarriedThroughUnchanged: EDP Sec. 8 gates are owner-executed
// and an agent may never advance one. The closeout must reproduce them as they
// stand, including their status.
func TestOwnerGatesAreCarriedThroughUnchanged(t *testing.T) {
	root := repoRoot(t)
	m, err := LoadMatrix(root)
	if err != nil {
		t.Fatalf("LoadMatrix: %v", err)
	}
	r := Build("WP-01", m, &ExecutedTests{}, "test")
	if len(r.OwnerGates) != len(m.OwnerGates) {
		t.Fatalf("report carries %d gates, matrix has %d", len(r.OwnerGates), len(m.OwnerGates))
	}
	for i, g := range r.OwnerGates {
		if g.Status != m.OwnerGates[i].Status || g.Gate != m.OwnerGates[i].Gate {
			t.Errorf("gate %s altered: %q vs %q", g.Gate, g.Status, m.OwnerGates[i].Status)
		}
	}
}

func TestMarkdownTableRendersEveryInScopeRow(t *testing.T) {
	m, err := LoadMatrix(repoRoot(t))
	if err != nil {
		t.Fatalf("LoadMatrix: %v", err)
	}
	r := Build("WP-01", m, &ExecutedTests{}, "test")
	table := MarkdownTable(r)
	for _, row := range r.InScope {
		if !strings.Contains(table, "| "+row.ReqID+" | `"+row.Status+"` |") {
			t.Errorf("%s (%s) is missing from the rendered table", row.ReqID, row.Status)
		}
	}
}

// TestCloseoutDocumentAgreesWithItsResults compares the prose document against
// the COMMITTED snapshot, not against the live matrix.
//
// This distinction is the whole design. A closeout records one moment; WP-02
// will move statuses, and a check that held closeout.md to the current matrix
// would fail the moment the next work package starts, forcing the record to be
// edited until it no longer describes the moment it exists to describe. Held to
// its own snapshot, the pair stays true forever, and generated_for_commit says
// which tree it describes.
func TestCloseoutDocumentAgreesWithItsResults(t *testing.T) {
	root := repoRoot(t)

	raw, err := os.ReadFile(filepath.Join(root, "evidence", "wp01", "closeout-results.json"))
	if err != nil {
		t.Skipf("closeout-results.json not present yet: %v", err)
	}
	var snapshot Report
	if err := json.Unmarshal(raw, &snapshot); err != nil {
		t.Fatalf("parse closeout-results.json: %v", err)
	}
	if snapshot.GeneratedFor == "" {
		t.Error("the snapshot does not record which commit it describes")
	}
	if len(snapshot.InScope) == 0 {
		t.Fatal("the snapshot has no in-scope rows")
	}

	doc, err := os.ReadFile(filepath.Join(root, "evidence", "wp01", "closeout.md"))
	if err != nil {
		t.Skipf("closeout.md not present yet: %v", err)
	}
	for _, row := range snapshot.InScope {
		want := "| " + row.ReqID + " | `" + row.Status + "` |"
		if !strings.Contains(string(doc), want) {
			t.Errorf("closeout.md does not carry %s as %q, which is what the snapshot records",
				row.ReqID, row.Status)
		}
	}
	if snapshot.Pipeline.RunID != "" && !strings.Contains(string(doc), snapshot.Pipeline.RunID) {
		t.Errorf("closeout.md never cites run %s, the run its results came from",
			snapshot.Pipeline.RunID)
	}
}

// TestEveryOwnerGateStaysUnproven is the claim an agent must never make. EDP
// Sec. 8 gates are owner-executed; a closeout is exactly the document where a
// gate would quietly acquire a pass.
func TestEveryOwnerGateStaysUnproven(t *testing.T) {
	m, err := LoadMatrix(repoRoot(t))
	if err != nil {
		t.Fatalf("LoadMatrix: %v", err)
	}
	if len(m.OwnerGates) == 0 {
		t.Fatal("the owner-gate register is empty")
	}
	for _, g := range m.OwnerGates {
		if g.Status != "unproven_owner" {
			t.Errorf("gate %s is %q; no agent-run pipeline may advance an owner gate", g.Gate, g.Status)
		}
		if g.OwnerSignoff != nil && *g.OwnerSignoff != "" {
			t.Errorf("gate %s carries a sign-off %q that no owner supplied", g.Gate, *g.OwnerSignoff)
		}
	}
}
