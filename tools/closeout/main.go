// Lumos - tools/closeout / CLI for the work-package closeout report.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-AI-003, TRS-AI-005
// ADRs: -
// Threading: main-safe
// Notes: There is deliberately NO -check mode and no CI gate comparing this
// report against the live matrix. A closeout is a frozen record of one moment:
// WP-02 will move statuses, and a gate demanding the WP-01 closeout keep pace
// would force the record to be rewritten until it no longer describes the
// moment it exists to describe. What IS enforced, by test, is that closeout.md
// and closeout-results.json agree with each other - the pair is self-consistent
// forever, and generated_for_commit says which tree they describe.
package main

import (
	"flag"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

func main() {
	var (
		root     = flag.String("root", ".", "repository root")
		out      = flag.String("out", "evidence/wp01/closeout-results.json", "output path")
		executed = flag.String("executed", "", "executed-test set from the pipeline run")
		table    = flag.Bool("table", false, "print the markdown status table and exit")
	)
	flag.Parse()

	if err := run(*root, *out, *executed, *table); err != nil {
		fmt.Fprintln(os.Stderr, "closeout:", err)
		os.Exit(1)
	}
}

func run(root, out, executedPath string, table bool) error {
	m, err := LoadMatrix(root)
	if err != nil {
		return err
	}
	executed, err := LoadExecuted(executedPath)
	if err != nil {
		return err
	}
	report := Build(m, executed, headCommit(root))

	if table {
		fmt.Print(MarkdownTable(report))
		return nil
	}

	if err := Write(filepath.Join(root, filepath.FromSlash(out)), report); err != nil {
		return err
	}
	fmt.Printf("wrote %s: %d in scope (%v), %d spillover, %d executed tests from run %q\n",
		out, report.Totals.InScopeCount, report.Totals.InScopeByStatus,
		report.Totals.SpilloverCount, report.Pipeline.ExecutedTests, report.Pipeline.RunID)
	return nil
}

func headCommit(root string) string {
	cmd := exec.Command("git", "rev-parse", "--short", "HEAD")
	cmd.Dir = root
	out, err := cmd.Output()
	if err != nil {
		return "unknown"
	}
	return strings.TrimSpace(string(out))
}
