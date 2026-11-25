// Lumos - tools/cicheck / The workflows and the required-check register must agree.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CICD-001, TRS-CICD-007, TRS-CODE-007
// ADRs: -
// Threading: any
// Notes: A GitHub ruleset requires a check by NAME, and the name is a job's
// `name:` field in a YAML file the ruleset cannot see. Nothing in either place
// can notice when they stop agreeing, and the failure is asymmetric: a required
// name no job reports blocks every pull request, which is loud, while a job no
// ruleset requires can be red on a mergeable pull request, which is silent.
// This repository met the first one. It is the second that this tool exists for.
//
// docs/ci/required-checks.md is the register both sides are compared against.
// The ruleset half is owner-held and cannot be read from CI without admin
// credentials, so this tool holds the WORKFLOW half still: names cannot move
// without the register moving with them, and the register is short enough to
// diff against the ruleset by eye (that document's Sec. 4 gives the command).
//
// Parsed with regexes rather than a YAML library, exactly as tools/schemacheck
// parses two source files: these are our own files in a known layout, and a
// YAML dependency would be a new dependency for one comparison.
package cicheck

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
)

// RegisterPath is the document both sides are compared against.
const RegisterPath = "docs/ci/required-checks.md"

// WorkflowPaths are the workflow files, keyed by the short name the register
// uses for each.
var WorkflowPaths = map[string]string{
	"conformance": ".github/workflows/conformance.yml",
	"pipeline":    ".github/workflows/pipeline.yml",
	"testflight":  ".github/workflows/testflight.yml",
}

// Job is one job as the workflow declares it.
type Job struct {
	// Workflow is the register's short name for the file it came from.
	Workflow string
	// ID is the YAML key - the job id, which is NOT what a ruleset requires.
	ID string
	// CheckName is the `name:` field, which IS what a ruleset requires.
	CheckName string
	// Condition is the job-level `if:`, flattened to one line, or "".
	Condition string
}

// Entry is one row of the register.
type Entry struct {
	// CheckName is the name the row declares.
	CheckName string
	// Workflow is the short workflow name.
	Workflow string
	// JobID is the job id the row declares.
	JobID string
	// Required is whether the ruleset must list this check.
	Required bool
}

var (
	jobsHeading = regexp.MustCompile(`^jobs:\s*$`)
	jobKey      = regexp.MustCompile(`^  ([A-Za-z0-9_-]+):\s*$`)
	jobName     = regexp.MustCompile(`^    name:\s*(.+?)\s*$`)
	jobIf       = regexp.MustCompile(`^    if:\s*(.*?)\s*$`)
	pathFilter  = regexp.MustCompile(`^\s+paths(-ignore)?:`)
	registerRow = regexp.MustCompile(
		"^\\|\\s*`([^`]+)`\\s*\\|\\s*([A-Za-z0-9_-]+)\\s*\\|\\s*`([^`]+)`\\s*\\|\\s*(yes|no)\\s*\\|")
	blockScalar = regexp.MustCompile(`^[>|][-+]?$`)
)

// LoadWorkflow parses one workflow file into its jobs, and reports whether the
// file declares a workflow-level path filter.
//
// The path-filter answer is the reason this returns two things. A workflow
// filtered out by `on: paths` posts no check runs at all, so a required check
// on it is expected for ever and every pull request that misses those paths is
// blocked. Only the region before `jobs:` is inspected: `paths` inside a step's
// `with:` is an action's input and means nothing here.
func LoadWorkflow(repoRoot, workflow string) ([]Job, bool, error) {
	rel, ok := WorkflowPaths[workflow]
	if !ok {
		return nil, false, fmt.Errorf("unknown workflow %q", workflow)
	}
	raw, err := os.ReadFile(filepath.Join(repoRoot, filepath.FromSlash(rel)))
	if err != nil {
		return nil, false, fmt.Errorf("read %s: %w", rel, err)
	}

	p := &parser{workflow: workflow, current: -1}
	text := strings.ReplaceAll(string(raw), "\r\n", "\n")
	for _, line := range strings.Split(text, "\n") {
		p.feed(line)
	}
	return p.done(), p.filtered, nil
}

// parser walks a workflow file one line at a time.
//
// A type rather than one function with a large switch, because TRS-CODE-004
// caps complexity at 15 and a YAML-shaped reader reaches that quickly: the
// preamble, the job keys, the scalar fields and the block-scalar continuation
// are four small state machines sharing one cursor.
type parser struct {
	workflow  string
	jobs      []Job
	inJobs    bool
	filtered  bool
	current   int
	pendingIf bool
	condition strings.Builder
}

// feed advances the parser by one line.
func (p *parser) feed(line string) {
	if !p.inJobs {
		p.preamble(line)
		return
	}
	if p.absorbCondition(line) {
		return
	}
	if m := jobKey.FindStringSubmatch(line); m != nil {
		p.jobs = append(p.jobs, Job{Workflow: p.workflow, ID: m[1]})
		p.current = len(p.jobs) - 1
		return
	}
	if p.current >= 0 {
		p.field(line)
	}
}

// preamble reads everything above `jobs:`, which is where a workflow-level
// path filter would be.
func (p *parser) preamble(line string) {
	if jobsHeading.MatchString(line) {
		p.inJobs = true
		return
	}
	if pathFilter.MatchString(line) {
		p.filtered = true
	}
}

// absorbCondition gathers the continuation lines of a block-scalar `if:`,
// which runs until the next line indented four spaces or fewer. It reports
// whether the line belonged to that condition.
func (p *parser) absorbCondition(line string) bool {
	if !p.pendingIf {
		return false
	}
	trimmed := strings.TrimSpace(line)
	if trimmed != "" && indentOf(line) > 4 {
		p.condition.WriteString(" ")
		p.condition.WriteString(trimmed)
		return true
	}
	p.closeCondition()
	return false
}

// field reads the two job-level scalars this tool reasons about.
func (p *parser) field(line string) {
	if m := jobName.FindStringSubmatch(line); m != nil && p.jobs[p.current].CheckName == "" {
		p.jobs[p.current].CheckName = strings.Trim(m[1], `"'`)
		return
	}
	m := jobIf.FindStringSubmatch(line)
	if m == nil || p.jobs[p.current].Condition != "" {
		return
	}
	if blockScalar.MatchString(m[1]) {
		p.pendingIf = true
		p.condition.Reset()
		return
	}
	p.jobs[p.current].Condition = m[1]
}

// closeCondition commits a gathered block-scalar condition to its job.
func (p *parser) closeCondition() {
	if !p.pendingIf || p.current < 0 {
		return
	}
	p.jobs[p.current].Condition = strings.TrimSpace(p.condition.String())
	p.pendingIf = false
	p.condition.Reset()
}

// done finishes the file and returns the jobs.
//
// A job with no `name:` reports its id as the check name, which is GitHub's
// own rule and not a fallback of ours.
func (p *parser) done() []Job {
	p.closeCondition()
	for i := range p.jobs {
		if p.jobs[i].CheckName == "" {
			p.jobs[i].CheckName = p.jobs[i].ID
		}
	}
	return p.jobs
}

// indentOf counts a line's leading spaces.
func indentOf(line string) int {
	return len(line) - len(strings.TrimLeft(line, " "))
}

// LoadAllWorkflows parses every workflow, sorted for stable comparison.
func LoadAllWorkflows(repoRoot string) ([]Job, error) {
	var all []Job
	names := make([]string, 0, len(WorkflowPaths))
	for name := range WorkflowPaths {
		names = append(names, name)
	}
	sort.Strings(names)
	for _, name := range names {
		jobs, _, err := LoadWorkflow(repoRoot, name)
		if err != nil {
			return nil, err
		}
		all = append(all, jobs...)
	}
	sort.Slice(all, func(i, j int) bool { return key(all[i]) < key(all[j]) })
	return all, nil
}

// LoadRegister parses the required-check register's tables.
func LoadRegister(repoRoot string) ([]Entry, error) {
	raw, err := os.ReadFile(filepath.Join(repoRoot, filepath.FromSlash(RegisterPath)))
	if err != nil {
		return nil, fmt.Errorf("read %s: %w", RegisterPath, err)
	}
	var out []Entry
	for _, line := range strings.Split(strings.ReplaceAll(string(raw), "\r\n", "\n"), "\n") {
		m := registerRow.FindStringSubmatch(line)
		if m == nil {
			continue
		}
		out = append(out, Entry{
			CheckName: m[1], Workflow: m[2], JobID: m[3], Required: m[4] == "yes",
		})
	}
	sort.Slice(out, func(i, j int) bool {
		return out[i].Workflow+"/"+out[i].JobID < out[j].Workflow+"/"+out[j].JobID
	})
	return out, nil
}

// key identifies a job across workflows.
func key(j Job) string { return j.Workflow + "/" + j.ID }

// Differences reports every way the workflows and the register disagree.
// An empty result means a ruleset configured from the register is configured
// from the workflows.
func Differences(jobs []Job, register []Entry) []string {
	var out []string

	declared := map[string]Entry{}
	for _, e := range register {
		declared[e.Workflow+"/"+e.JobID] = e
	}
	actual := map[string]Job{}
	for _, j := range jobs {
		actual[key(j)] = j
	}

	for _, j := range jobs {
		e, ok := declared[key(j)]
		if !ok {
			out = append(out, fmt.Sprintf(
				"job %s reports check %q and is in no table in %s; a job nobody "+
					"registered is a job nobody requires", key(j), j.CheckName, RegisterPath))
			continue
		}
		if e.CheckName != j.CheckName {
			out = append(out, fmt.Sprintf(
				"job %s reports check %q but %s says %q; a ruleset configured from "+
					"the register would require a name nothing reports",
				key(j), j.CheckName, RegisterPath, e.CheckName))
		}
	}
	for _, e := range register {
		if _, ok := actual[e.Workflow+"/"+e.JobID]; !ok {
			out = append(out, fmt.Sprintf(
				"%s registers %s/%s (%q) and no such job exists",
				RegisterPath, e.Workflow, e.JobID, e.CheckName))
		}
	}

	// Two jobs reporting one name make a ruleset entry ambiguous about which
	// of them satisfied it.
	seen := map[string]string{}
	for _, j := range jobs {
		if first, clash := seen[j.CheckName]; clash {
			out = append(out, fmt.Sprintf(
				"check name %q is reported by both %s and %s; a ruleset entry "+
					"cannot say which one it means", j.CheckName, first, key(j)))
			continue
		}
		seen[j.CheckName] = key(j)
	}

	sort.Strings(out)
	return out
}

// RequiredJobSkipViolations reports required jobs whose `if:` can skip them for
// a reason other than the path filter.
//
// This is the rule the traceability gate broke. GitHub posts an `if`-skipped
// job as a check run with conclusion "skipped", and a ruleset treats skipped as
// satisfied - so a required job conditioned on `needs.<other>.result` is a
// required check that vanishes precisely when another job failed. A required
// job may therefore be skipped for exactly one reason: the path filter has
// decided the diff cannot change what it asserts.
func RequiredJobSkipViolations(jobs []Job, register []Entry) []string {
	required := map[string]bool{}
	for _, e := range register {
		if e.Required {
			required[e.Workflow+"/"+e.JobID] = true
		}
	}

	var out []string
	for _, j := range jobs {
		if !required[key(j)] || j.Condition == "" {
			continue
		}
		condition := strings.TrimSpace(j.Condition)
		if condition == "always()" {
			continue
		}
		if strings.Contains(condition, ".result") {
			out = append(out, fmt.Sprintf(
				"required job %s is conditioned on another job's result (%q); a "+
					"skipped required check counts as satisfied, so this one would "+
					"go quiet exactly when something upstream broke. Use "+
					"`if: always()` and assert the results inside the job.",
				key(j), condition))
			continue
		}
		if !strings.Contains(condition, "needs.changes.outputs.") {
			out = append(out, fmt.Sprintf(
				"required job %s has condition %q, which is neither `always()` nor "+
					"a path-filter output; a required job may only skip when the "+
					"diff cannot change what it asserts", key(j), condition))
		}
	}
	sort.Strings(out)
	return out
}

// PathFilteredWorkflows reports workflows that filter themselves out with
// `on: paths`, which stops their checks reporting at all.
func PathFilteredWorkflows(repoRoot string) ([]string, error) {
	var out []string
	names := make([]string, 0, len(WorkflowPaths))
	for name := range WorkflowPaths {
		names = append(names, name)
	}
	sort.Strings(names)
	for _, name := range names {
		_, filtered, err := LoadWorkflow(repoRoot, name)
		if err != nil {
			return nil, err
		}
		if filtered {
			out = append(out, fmt.Sprintf(
				"%s declares a workflow-level paths filter; a workflow filtered out "+
					"that way posts NO check runs, so every required check on it stays "+
					"expected for ever and blocks every pull request that misses those "+
					"paths. Filter in a job-level `if:` instead, where the skip still "+
					"reports.", WorkflowPaths[name]))
		}
	}
	return out, nil
}

// RequiredNames lists the check names a ruleset must require, sorted.
func RequiredNames(register []Entry) []string {
	var out []string
	for _, e := range register {
		if e.Required {
			out = append(out, e.CheckName)
		}
	}
	sort.Strings(out)
	return out
}
