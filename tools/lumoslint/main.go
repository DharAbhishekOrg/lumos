// Lumos - tools/lumoslint / CLI: header, constants and architecture lint.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CODE-001, TRS-CODE-003, TRS-CODE-004, TRS-CODE-005, TRS-CICD-001
// ADRs: ADR-004
// Threading: main-safe
// Notes: One tool, four checks, because they share the same file walk and the
// same exclusion policy. Build output, vendored code and the committed Gradle
// wrapper are skipped: linting code nobody in this repository wrote produces
// findings nobody can act on.
package main

import (
	"flag"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// Finding is one lint failure.
type Finding struct {
	Rule   string
	Path   string
	Detail string
}

func (f Finding) String() string {
	return fmt.Sprintf("[%s] %s: %s", f.Rule, f.Path, f.Detail)
}

// lintedExtensions are the source languages this repository owns.
var lintedExtensions = map[string]bool{
	".go": true, ".java": true, ".swift": true, ".ts": true, ".kts": true,
}

// skippedDirs are trees whose contents this repository does not author.
// "dist" is tsc's output directory: it holds emitted .js and .d.ts files whose
// headers are whatever the compiler chose to carry over, and holding generated
// output to the authoring standard of TRS Sec. 24.1 flags the compiler, not a
// person who can fix it.
var skippedDirs = map[string]bool{
	".git": true, ".gradle": true, "build": true, ".build": true,
	"node_modules": true, "gradle": true, ".swiftpm": true, "DerivedData": true,
	"dist": true,
}

func main() {
	var (
		root    = flag.String("root", ".", "repository root")
		headers = flag.Bool("headers", true, "check TRS Sec. 24.1 file headers")
		consts  = flag.Bool("constants", true, "check for hand-written register constants")
		arch    = flag.Bool("architecture", true, "check TRS Sec. 4.3 layering")
		budgets = flag.Bool("budgets", true, "check TRS-CODE-004 file and class size budgets")
	)
	flag.Parse()

	if err := run(*root, *headers, *consts, *arch, *budgets); err != nil {
		fmt.Fprintln(os.Stderr, "lumoslint:", err)
		os.Exit(1)
	}
}

// checks is which of the four rules this invocation runs.
//
// A struct rather than four bool parameters threaded through two functions:
// TRS-CODE-004 caps complexity at 15 and adding the budget rule put `run` at
// 16, which is the gate this slice built catching the change that built it. The
// honest fix is fewer branches in one place, not a looser cap.
type checks struct {
	headers bool
	consts  bool
	arch    bool
	budgets bool
}

// context is everything the per-file rules need that is loaded once.
type context struct {
	root      string
	values    map[int64][]string
	knownADRs map[string]bool
}

func run(root string, headers, consts, arch, budgets bool) error {
	abs, err := filepath.Abs(root)
	if err != nil {
		return err
	}
	want := checks{headers: headers, consts: consts, arch: arch, budgets: budgets}

	ctx, err := loadContext(abs, want)
	if err != nil {
		return err
	}

	files, err := SourceFiles(abs)
	if err != nil {
		return err
	}
	fmt.Printf("linting %d source files\n", len(files))

	var findings []Finding
	for _, p := range files {
		findings = append(findings, checkFile(ctx, want, p)...)
	}

	if arch {
		fmt.Println(PendingSummary())
	}

	if len(findings) > 0 {
		sort.Slice(findings, func(i, j int) bool {
			if findings[i].Rule != findings[j].Rule {
				return findings[i].Rule < findings[j].Rule
			}
			return findings[i].Path < findings[j].Path
		})
		fmt.Fprintf(os.Stderr, "\n%d lint finding(s):\n", len(findings))
		for _, f := range findings {
			fmt.Fprintln(os.Stderr, "  "+f.String())
		}
		return fmt.Errorf("lint gate failed")
	}
	fmt.Println("lint gate passed")
	return nil
}

// loadContext reads whatever the requested rules need up front, and says what
// it loaded - a rule that silently policed nothing would read as a clean run.
func loadContext(root string, want checks) (context, error) {
	ctx := context{root: root}
	var err error
	if want.consts {
		if ctx.values, err = RegisterValues(root); err != nil {
			return ctx, err
		}
		fmt.Printf("constants: policing %d distinctive register values\n", len(ctx.values))
	}
	if want.budgets {
		if ctx.knownADRs, err = KnownADRs(root); err != nil {
			return ctx, err
		}
		fmt.Printf("budgets: %d file lines, %d class lines; %d ADRs may be cited for an exception\n",
			MaxFileLines, MaxTypeLines, len(ctx.knownADRs))
	}
	return ctx, nil
}

// checkFile applies every requested rule to one source file.
func checkFile(ctx context, want checks, path string) []Finding {
	var out []Finding
	if want.headers {
		out = append(out, CheckHeader(path)...)
	}
	if want.consts {
		out = append(out, CheckConstants(path, ctx.values)...)
	}
	if want.arch {
		out = append(out, CheckArchitecture(ctx.root, path)...)
		out = append(out, CheckMinSdkApis(ctx.root, path)...)
	}
	if want.budgets {
		out = append(out, CheckBudgets(path, ctx.knownADRs)...)
	}
	return out
}

// SourceFiles walks the repository for files this project authors.
func SourceFiles(root string) ([]string, error) {
	var out []string
	err := filepath.WalkDir(root, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.IsDir() {
			if skippedDirs[d.Name()] || strings.HasPrefix(d.Name(), ".") && d.Name() != "." {
				if p != root {
					return filepath.SkipDir
				}
			}
			return nil
		}
		if lintedExtensions[strings.ToLower(filepath.Ext(p))] {
			out = append(out, p)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	sort.Strings(out)
	return out, nil
}
