// Lumos - tools/closeout / Work-package closeout report generator.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-AI-003, TRS-AI-005, TRS-CICD-007
// ADRs: -
// Threading: any
// Notes: The closeout numbers are DERIVED from the trace matrix and from an
// executed-test set produced by a real pipeline run. None of them is typed by
// hand. That is the whole point: a closeout report is the document most likely
// to be written from memory at the end of a long work package, and a status
// summary that disagrees with the matrix it summarises is worse than no summary
// - it is the version people quote.
//
// The tool also refuses to upgrade anything. It reads status; it never writes
// status. A requirement reaches 'tested' by having a test execute, not by a
// report saying so.
package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
)

// MatrixPath is the traceability matrix, relative to the repository root.
const MatrixPath = "evidence/trace/trace-matrix.json"

// Entry mirrors the trace matrix row. Field order matches the committed file.
type Entry struct {
	ReqID        string   `json:"req_id"`
	Status       string   `json:"status"`
	Code         []string `json:"code"`
	Tests        []string `json:"tests"`
	Evidence     []string `json:"evidence"`
	ADR          []string `json:"adr"`
	LastCommit   *string  `json:"last_commit"`
	Updated      *string  `json:"updated"`
	OwnerSignoff *string  `json:"owner_signoff,omitempty"`
}

// GateRow is one EDP Sec. 8 owner-gate row.
type GateRow struct {
	Gate         string   `json:"gate"`
	Status       string   `json:"status"`
	Evidence     []string `json:"evidence"`
	OwnerSignoff *string  `json:"owner_signoff"`
}

// Matrix is the traceability matrix.
type Matrix struct {
	SchemaVersion int       `json:"schema_version"`
	Baseline      string    `json:"baseline"`
	Entries       []Entry   `json:"entries"`
	OwnerGates    []GateRow `json:"owner_gates"`
	Convention    string    `json:"last_commit_convention,omitempty"`
}

// ExecutedTests is the set of tests a pipeline run actually executed.
type ExecutedTests struct {
	RunID string   `json:"run_id"`
	Tests []string `json:"tests"`
}

// scopePatterns is each work package's declared requirement set, transcribed
// from its EDP Sec. 7 brief and NOT derived from whatever happens to have
// moved. That direction is the whole value of a closeout: a scope read back
// from the work done can never report the work as incomplete, and seventeen of
// WP-02's forty-five are `not_started` - a derived list would simply not have
// mentioned them.
//
// WP-01 comes from its kickoff's "Exit evidence" clause: "TRS-REP-*,
// TRS-PROTO-001..016, TRS-CONST-*, TRS-CICD-001/005, TRS-TST-001".
//
// WP-02 comes from its brief's "Key requirements" row: "SRS-ONB-001..008,
// SRS-IDN-001..003/007, SRS-PRO-001..013, SRS-SAF-009/010, SRS-DAT-002/004/012,
// TRS-LOC-001..010, TRS-AND-004, TRS-IOS-007, SRS-NFR-SEC-002/003/013".
//
// WP-03 comes from its brief's "Key requirements" row: "SRS-NBY-001..017,
// SRS-IDN-004, SRS-PRO-007, TRS-AND-005/006, TRS-IOS-003/009, TRS-TRN-002/003,
// TRS-PROTO-009/017/018, SRS-NFR-BAT-001/002, SRS-NFR-PERF-005/009,
// AT-003/004". The two acceptance tests are owner-executed radio campaigns,
// not matrix rows, so they appear in the closeout's owner-gate section rather
// than here; the TRS-ENV family the slices also moved is out of the brief's
// row on purpose and is reported as spillover.
var scopePatterns = map[string][]*regexp.Regexp{
	"WP-01": {
		regexp.MustCompile(`^TRS-REP-\d+$`),
		regexp.MustCompile(`^TRS-CONST-\d+$`),
		regexp.MustCompile(`^TRS-PROTO-0(0[1-9]|1[0-6])$`),
		regexp.MustCompile(`^TRS-CICD-00[15]$`),
		regexp.MustCompile(`^TRS-TST-001$`),
	},
	"WP-02": {
		regexp.MustCompile(`^SRS-ONB-00[1-8]$`),
		regexp.MustCompile(`^SRS-IDN-00[1237]$`),
		regexp.MustCompile(`^SRS-PRO-0(0[1-9]|1[0-3])$`),
		regexp.MustCompile(`^SRS-SAF-0(09|10)$`),
		regexp.MustCompile(`^SRS-DAT-0(02|04|12)$`),
		regexp.MustCompile(`^TRS-LOC-0(0[1-9]|10)$`),
		regexp.MustCompile(`^TRS-AND-004$`),
		regexp.MustCompile(`^TRS-IOS-007$`),
		regexp.MustCompile(`^SRS-NFR-SEC-0(02|03|13)$`),
	},
	"WP-03": {
		regexp.MustCompile(`^SRS-NBY-0(0[1-9]|1[0-7])$`),
		regexp.MustCompile(`^SRS-IDN-004$`),
		regexp.MustCompile(`^SRS-PRO-007$`),
		regexp.MustCompile(`^TRS-AND-00[56]$`),
		regexp.MustCompile(`^TRS-IOS-00[39]$`),
		regexp.MustCompile(`^TRS-TRN-00[23]$`),
		regexp.MustCompile(`^TRS-PROTO-0(09|17|18)$`),
		regexp.MustCompile(`^SRS-NFR-BAT-00[12]$`),
		regexp.MustCompile(`^SRS-NFR-PERF-00[59]$`),
	},
}

// KnownWorkPackages lists the packages this tool can close, sorted.
func KnownWorkPackages() []string {
	out := make([]string, 0, len(scopePatterns))
	for wp := range scopePatterns {
		out = append(out, wp)
	}
	sort.Strings(out)
	return out
}

// InScope reports whether a requirement belongs to a work package's declared set.
func InScope(workPackage, reqID string) bool {
	for _, p := range scopePatterns[workPackage] {
		if p.MatchString(reqID) {
			return true
		}
	}
	return false
}

// Row is one requirement's closeout line.
type Row struct {
	ReqID      string   `json:"req_id"`
	Status     string   `json:"status"`
	Tests      []string `json:"tests"`
	CodePaths  int      `json:"code_paths"`
	TestCount  int      `json:"test_count"`
	ExecutedIn *int     `json:"tests_executed_this_run,omitempty"`
}

// Report is the machine-readable closeout document.
type Report struct {
	WorkPackage  string         `json:"work_package"`
	Baseline     string         `json:"baseline"`
	GeneratedFor string         `json:"generated_for_commit"`
	Pipeline     PipelineResult `json:"pipeline"`
	Totals       Totals         `json:"totals"`
	InScope      []Row          `json:"in_scope"`
	Spillover    []Row          `json:"out_of_scope_but_moved"`
	OwnerGates   []GateRow      `json:"owner_gates"`
	Unproven     []string       `json:"unproven_or_deferred_req_ids"`
}

// PipelineResult records what a real CI run executed.
type PipelineResult struct {
	RunID          string         `json:"run_id"`
	ExecutedTests  int            `json:"executed_tests_total"`
	ByLanguage     map[string]int `json:"executed_tests_by_language"`
	SourcesPresent []string       `json:"languages_reporting"`
}

// Totals counts statuses across the declared scope and the whole matrix.
type Totals struct {
	MatrixEntries   int            `json:"matrix_entries"`
	MatrixByStatus  map[string]int `json:"matrix_by_status"`
	InScopeCount    int            `json:"in_scope_count"`
	InScopeByStatus map[string]int `json:"in_scope_by_status"`
	SpilloverCount  int            `json:"out_of_scope_but_moved_count"`
}

// LoadMatrix reads the traceability matrix.
func LoadMatrix(repoRoot string) (*Matrix, error) {
	raw, err := os.ReadFile(filepath.Join(repoRoot, filepath.FromSlash(MatrixPath)))
	if err != nil {
		return nil, fmt.Errorf("read matrix: %w", err)
	}
	var m Matrix
	if err := json.Unmarshal(raw, &m); err != nil {
		return nil, fmt.Errorf("parse matrix: %w", err)
	}
	return &m, nil
}

// LoadExecuted reads an executed-test set produced by a pipeline run. An absent
// path is not an error: it yields an empty set, and Build then reports zero
// executed tests rather than inventing a number.
func LoadExecuted(path string) (*ExecutedTests, error) {
	if path == "" {
		return &ExecutedTests{}, nil
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("read executed-test set: %w", err)
	}
	var e ExecutedTests
	if err := json.Unmarshal(raw, &e); err != nil {
		return nil, fmt.Errorf("parse executed-test set: %w", err)
	}
	return &e, nil
}

// Build assembles the report. It reads status and never writes it.
func Build(workPackage string, m *Matrix, executed *ExecutedTests, commit string) *Report {
	executedSet := make(map[string]bool, len(executed.Tests))
	byLanguage := map[string]int{}
	for _, id := range executed.Tests {
		executedSet[id] = true
		if lang, _, ok := strings.Cut(id, ":"); ok {
			byLanguage[lang]++
		}
	}
	languages := make([]string, 0, len(byLanguage))
	for lang := range byLanguage {
		languages = append(languages, lang)
	}
	sort.Strings(languages)

	r := &Report{
		WorkPackage:  workPackage,
		Baseline:     m.Baseline,
		GeneratedFor: commit,
		Pipeline: PipelineResult{
			RunID:          executed.RunID,
			ExecutedTests:  len(executed.Tests),
			ByLanguage:     byLanguage,
			SourcesPresent: languages,
		},
		Totals: Totals{
			MatrixEntries:   len(m.Entries),
			MatrixByStatus:  map[string]int{},
			InScopeByStatus: map[string]int{},
		},
		OwnerGates: m.OwnerGates,
	}

	for _, e := range m.Entries {
		r.Totals.MatrixByStatus[e.Status]++

		row := Row{
			ReqID:     e.ReqID,
			Status:    e.Status,
			Tests:     e.Tests,
			CodePaths: len(e.Code),
			TestCount: len(e.Tests),
		}
		if len(e.Tests) > 0 && len(executedSet) > 0 {
			n := 0
			for _, t := range e.Tests {
				if executedSet[t] {
					n++
				}
			}
			row.ExecutedIn = &n
		}

		switch {
		case InScope(workPackage, e.ReqID):
			r.Totals.InScopeByStatus[e.Status]++
			r.InScope = append(r.InScope, row)
		case e.Status != "not_started":
			r.Spillover = append(r.Spillover, row)
		}

		if e.Status == "unproven" || e.Status == "deferred" {
			r.Unproven = append(r.Unproven, e.ReqID)
		}
	}

	r.Totals.InScopeCount = len(r.InScope)
	r.Totals.SpilloverCount = len(r.Spillover)
	sort.Slice(r.InScope, func(i, j int) bool { return r.InScope[i].ReqID < r.InScope[j].ReqID })
	sort.Slice(r.Spillover, func(i, j int) bool { return r.Spillover[i].ReqID < r.Spillover[j].ReqID })
	return r
}

// Write emits the report as indented JSON.
func Write(path string, r *Report) error {
	raw, err := json.MarshalIndent(r, "", " ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	return os.WriteFile(path, append(raw, '\n'), 0o644)
}

// MarkdownTable renders the in-scope rows as the table closeout.md carries, so
// the prose document and the JSON cannot drift apart.
func MarkdownTable(r *Report) string {
	var b strings.Builder
	b.WriteString("| Requirement | Status | Linked tests | Executed this run |\n")
	b.WriteString("| --- | --- | ---: | ---: |\n")
	for _, row := range r.InScope {
		executed := "-"
		if row.ExecutedIn != nil {
			executed = fmt.Sprintf("%d", *row.ExecutedIn)
		}
		fmt.Fprintf(&b, "| %s | `%s` | %d | %s |\n", row.ReqID, row.Status, row.TestCount, executed)
	}
	return b.String()
}
