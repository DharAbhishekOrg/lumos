// Lumos - tools/cicheck / The gate that keeps the ruleset and the workflows honest.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CICD-001, TRS-CICD-007
// ADRs: -
// Threading: any
// Notes: The comparison is run against the real files, and the extractor is
// also run against fabricated drift - a comparison that cannot fail proves
// nothing, which is the quiet-green failure this repository has met more than
// once.
//
// The one thing these tests cannot check is the ruleset itself: reading it
// needs admin credentials that CI does not have and should not have. What they
// hold still is the workflow side, so the register stays an accurate
// description of what the workflows report, and diffing the register against
// the ruleset stays a two-minute job somebody can actually do.
package cicheck

import (
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

func load(t *testing.T) ([]Job, []Entry) {
	t.Helper()
	root := repoRoot(t)
	jobs, err := LoadAllWorkflows(root)
	if err != nil {
		t.Fatalf("load workflows: %v", err)
	}
	register, err := LoadRegister(root)
	if err != nil {
		t.Fatalf("load register: %v", err)
	}
	return jobs, register
}

// TestTheRegisterDescribesTheWorkflows is the gate itself.
func TestTheRegisterDescribesTheWorkflows(t *testing.T) {
	jobs, register := load(t)
	for _, d := range Differences(jobs, register) {
		t.Errorf("check-name drift: %s", d)
	}
}

// TestNoRequiredCheckCanSkipForTheWrongReason pins the rule the traceability
// gate broke: a required job may skip only when the path filter says the diff
// cannot change what it asserts.
func TestNoRequiredCheckCanSkipForTheWrongReason(t *testing.T) {
	jobs, register := load(t)
	for _, v := range RequiredJobSkipViolations(jobs, register) {
		t.Errorf("%s", v)
	}
}

// TestNoWorkflowFiltersItselfOut is the other half of "always reports".
func TestNoWorkflowFiltersItselfOut(t *testing.T) {
	for _, v := range mustPathFiltered(t) {
		t.Errorf("%s", v)
	}
}

func mustPathFiltered(t *testing.T) []string {
	t.Helper()
	out, err := PathFilteredWorkflows(repoRoot(t))
	if err != nil {
		t.Fatalf("scan workflows: %v", err)
	}
	return out
}

// TestExtractionActuallyFoundSomething guards the parser. A regex that silently
// matched nothing would make every comparison above vacuously true.
func TestExtractionActuallyFoundSomething(t *testing.T) {
	jobs, register := load(t)
	if len(jobs) < 10 {
		t.Fatalf("only %d jobs parsed from two workflows; the parser is not reading them", len(jobs))
	}
	if len(register) != len(jobs) {
		t.Fatalf("register has %d rows for %d jobs", len(register), len(jobs))
	}
	for _, j := range jobs {
		if j.CheckName == "" {
			t.Errorf("job %s parsed with no check name", key(j))
		}
	}
	// The three jobs whose conditions this tool exists to reason about must
	// have been parsed WITH their conditions, or the skip rule is vacuous.
	conditions := map[string]string{}
	for _, j := range jobs {
		conditions[key(j)] = j.Condition
	}
	for _, id := range []string{"conformance/swift", "conformance/android-instrumented"} {
		if !strings.Contains(conditions[id], "needs.changes.outputs.") {
			t.Errorf("%s: expected a path-filter condition, parsed %q", id, conditions[id])
		}
	}
	if conditions["conformance/trace"] != "always()" {
		t.Errorf("conformance/trace: expected always(), parsed %q", conditions["conformance/trace"])
	}
}

// TestEveryRequiredCheckIsRegistered states the required set in this test as
// well as in the document, transcribed by hand. Reading the list back out of
// the file it is meant to verify would prove only that the file agrees with
// itself; this is the same reason constantsgen's goldenValues exists.
func TestEveryRequiredCheckIsRegistered(t *testing.T) {
	_, register := load(t)
	want := []string{
		"admin console tests",
		"android instrumented tests",
		"android unit tests",
		"conformance gate",
		"go conformance",
		"java conformance",
		"pipeline gate",
		"swift conformance",
		"traceability gate",
	}
	got := RequiredNames(register)
	if strings.Join(got, "|") != strings.Join(want, "|") {
		t.Errorf("required checks:\n got: %v\nwant: %v", got, want)
	}
}

// TestRequiredCheckNamesAreTypeable is judgement made mechanical. A required
// name is retyped by a human into a ruleset field that does not trim or
// validate, so the characters that invite a mismatch are worth refusing:
// parentheses and plus signs were in three of the names that drifted.
func TestRequiredCheckNamesAreTypeable(t *testing.T) {
	_, register := load(t)
	for _, name := range RequiredNames(register) {
		if name != strings.TrimSpace(name) {
			t.Errorf("required check %q has leading or trailing space; the ruleset UI keeps it", name)
		}
		if strings.ContainsAny(name, "()[]{}+/:,") {
			t.Errorf("required check %q contains punctuation that invites a typo; "+
				"required names say what is gated, and the detail belongs in the "+
				"workflow's comments", name)
		}
		if strings.ToLower(name) != name {
			t.Errorf("required check %q is not all lower case; mixed case is one more "+
				"thing to get wrong when retyping it", name)
		}
	}
}

// TestDifferencesDetectsEveryKindOfDrift proves the comparison can fail.
func TestDifferencesDetectsEveryKindOfDrift(t *testing.T) {
	base := func() ([]Job, []Entry) {
		return []Job{{Workflow: "pipeline", ID: "lint", CheckName: "lint"}},
			[]Entry{{CheckName: "lint", Workflow: "pipeline", JobID: "lint", Required: true}}
	}
	if jobs, register := base(); len(Differences(jobs, register)) != 0 {
		t.Fatalf("the baseline must agree with itself: %v", Differences(jobs, register))
	}

	cases := map[string]func([]Job, []Entry) ([]Job, []Entry){
		"a renamed job": func(j []Job, r []Entry) ([]Job, []Entry) {
			j[0].CheckName = "lint (everything)"
			return j, r
		},
		"a job in no table": func(j []Job, r []Entry) ([]Job, []Entry) {
			return append(j, Job{Workflow: "pipeline", ID: "new", CheckName: "new"}), r
		},
		"a registered job that does not exist": func(j []Job, r []Entry) ([]Job, []Entry) {
			return j, append(r, Entry{CheckName: "ghost", Workflow: "pipeline", JobID: "ghost"})
		},
		"two jobs reporting one name": func(j []Job, r []Entry) ([]Job, []Entry) {
			j = append(j, Job{Workflow: "conformance", ID: "lint2", CheckName: "lint"})
			r = append(r, Entry{CheckName: "lint", Workflow: "conformance", JobID: "lint2"})
			return j, r
		},
	}
	for name, mutate := range cases {
		jobs, register := base()
		jobs, register = mutate(jobs, register)
		if len(Differences(jobs, register)) == 0 {
			t.Errorf("%s was not detected", name)
		}
	}
}

// TestTheSkipRuleDetectsEveryKindOfViolation proves that half can fail too.
func TestTheSkipRuleDetectsEveryKindOfViolation(t *testing.T) {
	register := []Entry{{CheckName: "gate", Workflow: "conformance", JobID: "gate", Required: true}}
	allowed := []string{"", "always()", "needs.changes.outputs.swift == 'true'"}
	for _, condition := range allowed {
		jobs := []Job{{Workflow: "conformance", ID: "gate", CheckName: "gate", Condition: condition}}
		if v := RequiredJobSkipViolations(jobs, register); len(v) != 0 {
			t.Errorf("condition %q should be allowed, got %v", condition, v)
		}
	}
	refused := []string{
		"always() && needs.go.result == 'success'",
		"needs.go.result != 'failure'",
		"github.event_name == 'push'",
	}
	for _, condition := range refused {
		jobs := []Job{{Workflow: "conformance", ID: "gate", CheckName: "gate", Condition: condition}}
		if v := RequiredJobSkipViolations(jobs, register); len(v) == 0 {
			t.Errorf("condition %q should be refused", condition)
		}
	}

	// And a job that is NOT required may be conditioned however it likes.
	jobs := []Job{{Workflow: "conformance", ID: "gate", CheckName: "gate",
		Condition: "needs.go.result == 'success'"}}
	notRequired := []Entry{{CheckName: "gate", Workflow: "conformance", JobID: "gate"}}
	if v := RequiredJobSkipViolations(jobs, notRequired); len(v) != 0 {
		t.Errorf("a job that is not required must not be constrained: %v", v)
	}
}
