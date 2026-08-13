// Lumos - tools/tracecheck / Collects the tests a pipeline run actually executed.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-AI-003, TRS-CICD-007
// ADRs: -
// Threading: any
// Notes: TRS Sec. 19.3 requires that 'tested' rest on a test which executed IN
// THE SAME PIPELINE RUN. That is only checkable if the run reports what it ran,
// so this reads the three runners' native machine-readable output - go test
// -json, JUnit XML from Gradle, xUnit XML from swift test - and normalises them
// into the identifier form the matrix uses. Only PASSING tests are collected: a
// test that ran and failed is not evidence that a requirement is satisfied.
package main

import (
	"bufio"
	"encoding/json"
	"encoding/xml"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// CollectGoTests parses `go test -json` output into matrix identifiers of the
// form go:<short-package>:<Test>. Subtests are folded into their parent, since
// the matrix links parent test names.
func CollectGoTests(path string) ([]string, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("open go test json: %w", err)
	}
	// Read-only: nothing was buffered, so a Close error carries no lost data.
	defer func() { _ = f.Close() }()

	out := map[string]bool{}
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 0, 1024*1024), 8*1024*1024)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || !strings.HasPrefix(line, "{") {
			continue
		}
		var ev struct {
			Action  string `json:"Action"`
			Package string `json:"Package"`
			Test    string `json:"Test"`
		}
		if err := json.Unmarshal([]byte(line), &ev); err != nil {
			continue // non-event output interleaved by the toolchain
		}
		if ev.Action != "pass" || ev.Test == "" {
			continue
		}
		// Fold subtests: "TestX/case" -> "TestX".
		name := ev.Test
		if i := strings.Index(name, "/"); i >= 0 {
			name = name[:i]
		}
		out["go:"+shortGoPackage(ev.Package)+":"+name] = true
	}
	if err := sc.Err(); err != nil {
		return nil, fmt.Errorf("scan go test json: %w", err)
	}
	return sortedKeys(out), nil
}

// shortGoPackage turns a full module path into the repo-relative package path
// the matrix cites, e.g. ".../lumos/protocol/wire" -> "protocol/wire".
func shortGoPackage(pkg string) string {
	const modulePrefix = "github.com/dharabhishekemail-ops/lumos/"
	return strings.TrimPrefix(pkg, modulePrefix)
}

type junitSuite struct {
	XMLName  xml.Name `xml:"testsuite"`
	Name     string   `xml:"name,attr"`
	TestCase []struct {
		Name      string    `xml:"name,attr"`
		ClassName string    `xml:"classname,attr"`
		Failure   *struct{} `xml:"failure"`
		Error     *struct{} `xml:"error"`
		Skipped   *struct{} `xml:"skipped"`
	} `xml:"testcase"`
}

// CollectJUnitTests walks a directory of JUnit/xUnit XML reports and returns
// matrix identifiers of the form <lang>:<module>:<Class>.<method>.
//
// Both Gradle's JUnit XML and `swift test --xunit-output` use the same schema,
// so one parser serves the Java and Swift runners.
func CollectJUnitTests(dir, lang, module string) ([]string, error) {
	out := map[string]bool{}
	err := filepath.WalkDir(dir, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.IsDir() || !strings.HasSuffix(strings.ToLower(d.Name()), ".xml") {
			return nil
		}
		raw, err := os.ReadFile(p)
		if err != nil {
			return err
		}
		// A report may hold one <testsuite> or a <testsuites> wrapper.
		var single junitSuite
		if err := xml.Unmarshal(raw, &single); err == nil && len(single.TestCase) > 0 {
			addJUnit(out, single, lang, module)
			return nil
		}
		var multi struct {
			Suites []junitSuite `xml:"testsuite"`
		}
		if err := xml.Unmarshal(raw, &multi); err != nil {
			// Not a report we understand. A results directory legitimately holds
			// other XML (Gradle writes binary-format companions), so skipping is
			// correct; the collector's fail-on-empty rule catches the case where
			// skipping means nothing at all was harvested.
			return nil //nolint:nilerr // an unparseable sibling file is not this walk's failure
		}
		for _, s := range multi.Suites {
			addJUnit(out, s, lang, module)
		}
		return nil
	})
	if err != nil {
		return nil, fmt.Errorf("walk %s: %w", dir, err)
	}
	return sortedKeys(out), nil
}

// placeholderSuiteNames are values a runner writes when it has nothing real to
// put in the field. Each entry is here because a runner ACTUALLY emits it, as
// captured from a live CI artefact - not because it looked plausible:
//
//	"TestResults" - SwiftPM's --xunit-output writes one <testsuite
//	                name="TestResults"> wrapper around every case and puts the
//	                real suite in classname.
//	"test"        - node:test's junit reporter does the mirror image: the real
//	                suite is the <testsuite> name from describe(), and every
//	                classname is the literal string "test".
//
// The two runners put their placeholder on OPPOSITE fields, which is why
// neither "always prefer classname" nor "always prefer the suite name" works.
// Slice S7 chose suite-name-first to accommodate node and silently broke every
// Swift identifier; the closeout run is where that surfaced.
var placeholderSuiteNames = map[string]bool{
	"TestResults": true,
	"test":        true,
}

// suiteName picks whichever of the two fields carries real information.
func suiteName(fromSuite, fromClassName string) string {
	for _, candidate := range []string{fromClassName, fromSuite} {
		if candidate != "" && !placeholderSuiteNames[candidate] {
			return candidate
		}
	}
	// Both are empty or both are placeholders: prefer whatever is non-empty so
	// the caller's own emptiness checks decide, rather than inventing a name.
	if fromClassName != "" {
		return fromClassName
	}
	return fromSuite
}

func addJUnit(out map[string]bool, s junitSuite, lang, module string) {
	for _, tc := range s.TestCase {
		if tc.Failure != nil || tc.Error != nil || tc.Skipped != nil {
			continue // only passing tests are evidence
		}
		class := suiteName(s.Name, tc.ClassName)
		// Keep the simple class name: the matrix cites Class.method, not FQN.
		if i := strings.LastIndex(class, "."); i >= 0 {
			class = class[i+1:]
		}
		method := tc.Name
		// Swift's xUnit output may write "-[Suite testMethod]"; unwrap that form
		// ONLY. Splitting on spaces unconditionally would mangle any other
		// name - JUnit writes a @DisplayName sentence there, and taking its last
		// word silently produced identifiers like "alphabet" that match nothing.
		if strings.HasPrefix(method, "-[") && strings.HasSuffix(method, "]") {
			inner := method[2 : len(method)-1]
			if i := strings.LastIndex(inner, " "); i >= 0 {
				inner = inner[i+1:]
			}
			method = inner
		}
		// Gradle writes "methodName()" for a JUnit 5 test without @DisplayName.
		method = strings.TrimSuffix(method, "()")
		if class == "" || method == "" {
			continue
		}
		out[fmt.Sprintf("%s:%s:%s.%s", lang, module, class, method)] = true
	}
}

func sortedKeys(m map[string]bool) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}

// WriteExecuted writes the normalised executed-test set.
func WriteExecuted(path, runID string, tests []string) error {
	e := ExecutedTests{RunID: runID, Tests: tests}
	raw, err := json.MarshalIndent(e, "", " ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	return os.WriteFile(path, append(raw, '\n'), 0o644)
}

// LoadExecuted reads an executed-test set.
func LoadExecuted(path string) (*ExecutedTests, error) {
	var e ExecutedTests
	if err := readJSON(path, &e); err != nil {
		return nil, err
	}
	if e.RunID == "" {
		return nil, fmt.Errorf("executed-test set has no run_id; it cannot be tied to a pipeline run")
	}
	return &e, nil
}
