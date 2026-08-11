// Lumos - tools/tracecheck / CLI: validate and stamp the traceability matrix.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-AI-003, TRS-CICD-007
// ADRs: -
// Threading: main-safe
// Notes: -stamp is the mechanism that replaces slice S2's placeholder README
// exclusion. That exclusion existed only because restamping entries by hand was
// tedious enough to make the freshness gate feel like noise; with -stamp -auto
// computing exactly which IDs a diff requires and rewriting them in one command,
// the burden is gone and the exclusion is not needed. The freshness rule now
// applies to EVERY file under a mapped module, documentation included, which is
// the stricter reading AGENTS.md Sec. 1 calls for.
//
// A commit cannot contain its own hash, so last_commit records the HEAD the
// entry's evidence was produced against - the commit the author stamped from -
// and freshness proves the field CHANGED between base and head. That is exactly
// what EDP Sec. 6.2 asks for ("must also modify those entries' last_commit"),
// and it needs no self-referential SHA.
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

func main() {
	var (
		root      = flag.String("root", ".", "repository root")
		base      = flag.String("base", "", "base git ref for the freshness check (e.g. origin/main)")
		head      = flag.String("head", "HEAD", "head git ref for the freshness check")
		execPath  = flag.String("executed", "", "path to an executed-test set for the honesty check")
		stamp     = flag.Bool("stamp", false, "rewrite last_commit/updated for the given IDs")
		auto      = flag.Bool("auto", false, "with -stamp: compute the IDs the working change requires")
		rc        = flag.Bool("release-candidate", false, "additionally apply the release-candidate P0 gate")
		collect   = flag.String("collect", "", "collect executed tests into this path instead of validating")
		goJSON    = flag.String("go-json", "", "with -collect: go test -json output")
		junitDirs = flag.String("junit", "", "with -collect: comma-separated lang=module=dir triples")
		runID     = flag.String("run-id", "", "with -collect: the pipeline run identifier")
	)
	flag.Parse()

	if err := run(*root, opts{
		base: *base, head: *head, executed: *execPath,
		stamp: *stamp, auto: *auto, rc: *rc,
		collect: *collect, goJSON: *goJSON, junit: *junitDirs, runID: *runID,
		ids: flag.Args(),
	}); err != nil {
		fmt.Fprintln(os.Stderr, "tracecheck:", err)
		os.Exit(1)
	}
}

type opts struct {
	base, head, executed          string
	stamp, auto, rc               bool
	collect, goJSON, junit, runID string
	ids                           []string
}

func run(root string, o opts) error {
	if o.collect != "" {
		return doCollect(root, o)
	}
	if o.stamp {
		return doStamp(root, o)
	}
	return doValidate(root, o)
}

// ---- collect ----

func doCollect(root string, o opts) error {
	if o.runID == "" {
		return fmt.Errorf("-collect requires -run-id so the set can be tied to a pipeline run")
	}
	var all []string
	if o.goJSON != "" {
		got, err := CollectGoTests(o.goJSON)
		if err != nil {
			return err
		}
		fmt.Printf("go: %d passing tests\n", len(got))
		all = append(all, got...)
	}
	for _, spec := range splitNonEmpty(o.junit, ",") {
		parts := strings.SplitN(spec, "=", 3)
		if len(parts) != 3 {
			return fmt.Errorf("-junit entry %q must be lang=module=dir", spec)
		}
		lang, module, dir := parts[0], parts[1], parts[2]
		if _, err := os.Stat(dir); err != nil {
			return fmt.Errorf("-junit directory %s is unreadable: %w", dir, err)
		}
		got, err := CollectJUnitTests(dir, lang, module)
		if err != nil {
			return err
		}
		fmt.Printf("%s: %d passing tests\n", lang, len(got))
		if len(got) == 0 {
			// A declared source that harvests nothing leaves the gate blind to
			// that language while still reporting success. This has now bitten
			// twice - Java display names, then a missing Swift xUnit report -
			// and survived both times only because entries link more than one
			// language. Fail loudly rather than shrug.
			return fmt.Errorf("%s results at %s yielded no passing tests; the honesty gate would be silently blind to %s", lang, dir, lang)
		}
		all = append(all, got...)
	}
	if len(all) == 0 {
		return fmt.Errorf("collected no executed tests; refusing to write an empty set that would make honesty checks vacuous")
	}
	seen := map[string]bool{}
	for _, t := range all {
		seen[t] = true
	}
	tests := sortedKeys(seen)
	if err := WriteExecuted(o.collect, o.runID, tests); err != nil {
		return err
	}
	fmt.Printf("wrote %s: %d executed tests for run %s\n", o.collect, len(tests), o.runID)
	return nil
}

// ---- stamp ----

func doStamp(root string, o opts) error {
	m, err := LoadMatrix(root)
	if err != nil {
		return err
	}
	cat, err := LoadCatalogue(root)
	if err != nil {
		return err
	}

	ids := o.ids
	if o.auto {
		mm, err := LoadModuleMap(root)
		if err != nil {
			return err
		}
		changed, err := workingChanges(root)
		if err != nil {
			return err
		}
		ids = requiredIDs(mm, cat, changed)
		fmt.Printf("%d changed paths require %d requirement entries\n", len(changed), len(ids))
	}
	if len(ids) == 0 {
		fmt.Println("nothing to stamp")
		return nil
	}

	sha, err := gitOutput(root, "rev-parse", "--short", "HEAD")
	if err != nil {
		return fmt.Errorf("resolve HEAD: %w", err)
	}
	today := time.Now().UTC().Format("2006-01-02")

	byID := m.ByID()
	var missing []string
	for _, id := range ids {
		e, ok := byID[id]
		if !ok {
			missing = append(missing, id)
			continue
		}
		e.LastCommit = &sha
		e.Updated = &today
	}
	if len(missing) > 0 {
		return fmt.Errorf("not in the matrix: %s", strings.Join(missing, ", "))
	}
	if err := writeMatrix(root, m); err != nil {
		return err
	}
	fmt.Printf("stamped %d entries at %s (%s)\n", len(ids), sha, today)
	return nil
}

// requiredIDs expands changed paths into the requirement IDs freshness demands.
func requiredIDs(mm *ModuleMap, cat *Catalogue, changed []string) []string {
	seen := map[string]bool{}
	for _, p := range changed {
		for _, prefix := range mm.ModulesFor(p) {
			for _, id := range mm.RequirementsFor(prefix, cat) {
				seen[id] = true
			}
		}
	}
	return sortedKeys(seen)
}

// workingChanges lists paths changed in the working tree and index, so an
// author can stamp before committing.
func workingChanges(root string) ([]string, error) {
	out, err := gitOutput(root, "status", "--porcelain=v1")
	if err != nil {
		return nil, err
	}
	var paths []string
	for _, line := range strings.Split(out, "\n") {
		if len(line) < 4 {
			continue
		}
		p := strings.TrimSpace(line[3:])
		// Renames read "old -> new"; the new path is what matters.
		if i := strings.Index(p, " -> "); i >= 0 {
			p = p[i+4:]
		}
		paths = append(paths, strings.Trim(p, `"`))
	}
	return paths, nil
}

// ---- validate ----

func doValidate(root string, o opts) error {
	m, err := LoadMatrix(root)
	if err != nil {
		return err
	}
	cat, err := LoadCatalogue(root)
	if err != nil {
		return err
	}
	mm, err := LoadModuleMap(root)
	if err != nil {
		return err
	}

	fmt.Println("matrix:", Summary(m))

	var findings []Finding
	findings = append(findings, CheckCompleteness(m, cat)...)

	if o.base != "" {
		changed, err := gitDiffNames(root, o.base, o.head)
		if err != nil {
			return fmt.Errorf("freshness diff: %w", err)
		}
		baseMatrix, err := matrixAtRef(root, o.base)
		if err != nil {
			return fmt.Errorf("read matrix at %s: %w", o.base, err)
		}
		fmt.Printf("freshness: %d changed paths against %s\n", len(changed), o.base)
		findings = append(findings, CheckFreshness(m, baseMatrix, mm, cat, changed)...)
	} else {
		fmt.Println("freshness: skipped (no -base given; CI always supplies one)")
	}

	var executed *ExecutedTests
	if o.executed != "" {
		executed, err = LoadExecuted(o.executed)
		if err != nil {
			return err
		}
		fmt.Printf("honesty: %d executed tests from run %s\n", len(executed.Tests), executed.RunID)
	} else {
		fmt.Println("honesty: structural checks only (no -executed set supplied)")
	}
	findings = append(findings, CheckHonesty(m, cat, executed)...)

	if o.rc {
		findings = append(findings, CheckReleaseCandidate(m, cat)...)
	}

	if len(findings) > 0 {
		SortFindings(findings)
		fmt.Fprintf(os.Stderr, "\n%d traceability finding(s):\n", len(findings))
		for _, f := range findings {
			fmt.Fprintln(os.Stderr, "  "+f.String())
		}
		return fmt.Errorf("traceability gate failed")
	}
	fmt.Println("traceability gate passed")
	return nil
}

// ---- git helpers ----

func gitOutput(root string, args ...string) (string, error) {
	cmd := exec.Command("git", args...)
	cmd.Dir = root
	out, err := cmd.Output()
	if err != nil {
		return "", fmt.Errorf("git %s: %w", strings.Join(args, " "), err)
	}
	return strings.TrimSpace(string(out)), nil
}

func gitDiffNames(root, base, head string) ([]string, error) {
	out, err := gitOutput(root, "diff", "--name-only", base, head)
	if err != nil {
		return nil, err
	}
	return splitNonEmpty(out, "\n"), nil
}

// matrixAtRef reads the matrix as it exists at a git ref, without checking it out.
func matrixAtRef(root, ref string) (*Matrix, error) {
	out, err := gitOutput(root, "show", ref+":"+MatrixPath)
	if err != nil {
		// The matrix may not exist on the base at all (the seeding commit).
		return nil, nil //nolint:nilnil // absence is a meaningful, non-error state
	}
	var m Matrix
	if err := json.Unmarshal([]byte(out), &m); err != nil {
		return nil, fmt.Errorf("parse matrix at %s: %w", ref, err)
	}
	return &m, nil
}

func writeMatrix(root string, m *Matrix) error {
	raw, err := json.MarshalIndent(m, "", " ")
	if err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(root, filepath.FromSlash(MatrixPath)), append(raw, '\n'), 0o644)
}

func splitNonEmpty(s, sep string) []string {
	var out []string
	for _, p := range strings.Split(s, sep) {
		if t := strings.TrimSpace(p); t != "" {
			out = append(out, t)
		}
	}
	return out
}
