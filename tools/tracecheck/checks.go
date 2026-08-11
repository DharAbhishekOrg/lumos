// Lumos - tools/tracecheck / Completeness, freshness and status-honesty checks.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-AI-003, TRS-CICD-007
// ADRs: -
// Threading: any
// Notes: The three rules of EDP Sec. 6.2 / TRS Sec. 19.3. Freshness compares the
// matrix against the PR base rather than demanding a self-referential SHA - a
// commit cannot contain its own hash, and the rule as written requires the entry
// to be MODIFIED, which is exactly what a base-to-head diff can prove. Honesty
// refuses to take 'tested' on trust: a linked test must appear in the executed
// set produced by the same pipeline run.
package main

import (
	"fmt"
	"sort"
	"strings"
)

// CheckCompleteness enforces the seeding rule: one entry per catalogued ID, and
// no matrix ID absent from the catalogues (both directions).
func CheckCompleteness(m *Matrix, cat *Catalogue) []Finding {
	var out []Finding

	seen := map[string]int{}
	for _, e := range m.Entries {
		seen[e.ReqID]++
	}
	for id, n := range seen {
		if n > 1 {
			out = append(out, Finding{"duplicate-entry", id, fmt.Sprintf("appears %d times", n)})
		}
	}
	for _, id := range cat.IDs() {
		if seen[id] == 0 {
			out = append(out, Finding{"missing-entry", id, "catalogued requirement has no matrix entry"})
		}
	}
	for _, e := range m.Entries {
		if _, ok := cat.Priority[e.ReqID]; !ok {
			out = append(out, Finding{"orphan-entry", e.ReqID, "matrix entry is not in the SRS or TRS catalogue"})
		}
		if !ValidStatuses[e.Status] {
			out = append(out, Finding{"invalid-status", e.ReqID, fmt.Sprintf("status %q is not one of the five defined states", e.Status)})
		}
	}
	SortFindings(out)
	return out
}

// CheckFreshness enforces the co-editing rule: a change under a mapped module
// must also change those requirement entries' last_commit.
//
// changedPaths is the base..head file list; baseMatrix is the matrix as it was
// on the base. A nil baseMatrix means the matrix itself is new, which only
// happens on the seeding commit.
func CheckFreshness(head, base *Matrix, mm *ModuleMap, cat *Catalogue, changedPaths []string) []Finding {
	if base == nil {
		return nil
	}
	baseByID := base.ByID()
	headByID := head.ByID()

	// Which requirement IDs does this change touch?
	required := map[string]string{} // id -> the module prefix that demanded it
	for _, p := range changedPaths {
		for _, prefix := range mm.ModulesFor(p) {
			for _, id := range mm.RequirementsFor(prefix, cat) {
				if _, exists := required[id]; !exists {
					required[id] = prefix
				}
			}
		}
	}

	var stale []Finding
	for id, prefix := range required {
		h, ok := headByID[id]
		if !ok {
			continue // completeness reports this separately
		}
		b, ok := baseByID[id]
		if !ok {
			continue // newly catalogued; nothing to compare against
		}
		if sameCommit(h.LastCommit, b.LastCommit) {
			stale = append(stale, Finding{
				Rule:   "stale-entry",
				ReqID:  id,
				Detail: fmt.Sprintf("%s changed but last_commit was not updated (run: go run ./tools/tracecheck -stamp -auto)", prefix),
			})
		}
	}
	SortFindings(stale)
	return stale
}

func sameCommit(a, b *string) bool {
	if a == nil || b == nil {
		return a == nil && b == nil
	}
	return *a == *b
}

// ExecutedTests is the set of test identifiers a pipeline run actually executed.
type ExecutedTests struct {
	// RunID identifies the pipeline run that produced the set.
	RunID string `json:"run_id"`
	// Tests are normalised identifiers in the matrix's own form.
	Tests []string `json:"tests"`

	set map[string]bool
}

// Has reports whether a linked test identifier executed.
func (e *ExecutedTests) Has(id string) bool {
	if e.set == nil {
		e.set = make(map[string]bool, len(e.Tests))
		for _, t := range e.Tests {
			e.set[t] = true
		}
	}
	return e.set[id]
}

// CheckHonesty enforces the status-honesty rules of TRS Sec. 19.3.
//
// executed may be nil, which means no test-execution evidence was supplied. In
// that mode the run-linked half is skipped and reported by the caller; the
// structural half - owner gates, sign-off fields, links that a status implies -
// still applies, because none of it depends on a pipeline run.
func CheckHonesty(m *Matrix, cat *Catalogue, executed *ExecutedTests) []Finding {
	var out []Finding
	gates := OwnerGateByRequirement()

	for i := range m.Entries {
		e := &m.Entries[i]

		// An owner-executed gate may never be claimed as tested by an agent.
		if g, isGate := gates[e.ReqID]; isGate {
			if e.Status == StatusTested {
				out = append(out, Finding{
					Rule:  "owner-gate-claimed-tested",
					ReqID: e.ReqID,
					Detail: fmt.Sprintf("%s (%s) is owner-executed; simulated or asserted substitutes are release-blocking (TRS-AI-009/010/014)",
						g.ID, g.Title),
				})
			}
			if e.Status == StatusUnproven && (e.OwnerSignoff == nil || strings.TrimSpace(*e.OwnerSignoff) == "") {
				out = append(out, Finding{
					Rule:   "unproven-without-signoff",
					ReqID:  e.ReqID,
					Detail: fmt.Sprintf("%s is unproven and must carry an owner_signoff field until the owner attaches evidence", g.ID),
				})
			}
		}

		// unproven is reserved for owner-executed gates; using it elsewhere
		// would let ordinary unfinished work masquerade as blocked-on-owner.
		if e.Status == StatusUnproven {
			if _, isGate := gates[e.ReqID]; !isGate {
				out = append(out, Finding{
					Rule:   "unproven-outside-owner-gate",
					ReqID:  e.ReqID,
					Detail: "unproven is reserved for EDP Sec. 8 owner-executed gates",
				})
			}
		}

		// A status that claims work must point at the work.
		switch e.Status {
		case StatusTested:
			if len(e.Tests) == 0 {
				out = append(out, Finding{"tested-without-tests", e.ReqID, "status tested with no linked test"})
			}
			if len(e.Code) == 0 {
				out = append(out, Finding{"tested-without-code", e.ReqID, "status tested with no linked code path"})
			}
		case StatusImplemented:
			if len(e.Code) == 0 {
				out = append(out, Finding{"implemented-without-code", e.ReqID, "status implemented with no linked code path"})
			}
		case StatusNotStarted:
			if len(e.Code) > 0 || len(e.Tests) > 0 {
				out = append(out, Finding{
					Rule:   "not-started-with-links",
					ReqID:  e.ReqID,
					Detail: "status not_started but code or tests are linked; the status understates the work",
				})
			}
		}

		// Anything past not_started must record when it was last touched.
		if e.Status != StatusNotStarted {
			if e.LastCommit == nil || strings.TrimSpace(*e.LastCommit) == "" {
				out = append(out, Finding{"missing-last-commit", e.ReqID, "non-default status with no last_commit"})
			}
			if e.Updated == nil || strings.TrimSpace(*e.Updated) == "" {
				out = append(out, Finding{"missing-updated", e.ReqID, "non-default status with no updated date"})
			}
		}

		// The run-linked half: 'tested' requires a test that actually executed.
		if e.Status == StatusTested && executed != nil && len(e.Tests) > 0 {
			if !anyExecuted(e.Tests, executed) {
				out = append(out, Finding{
					Rule:  "tested-without-executed-test",
					ReqID: e.ReqID,
					Detail: fmt.Sprintf("none of the %d linked tests appear in the executed set for run %s",
						len(e.Tests), executed.RunID),
				})
			}
		}
	}

	out = append(out, checkGateRegister(m)...)
	out = append(out, checkIdentifierShapes(m, executed)...)

	SortFindings(out)
	return out
}

// checkIdentifierShapes catches a runner whose identifier FORMAT has drifted
// away from the matrix, as opposed to a runner that failed.
//
// This is the failure this project keeps meeting. A collector change renames
// every identifier a language produces; the language still reports its tests as
// passing, the collector still writes a healthy-looking set, and every linked ID
// for that language silently matches nothing. Rows survive because they also
// link a language that still matches, so the gate stays green while a whole
// runner's evidence has quietly stopped counting. It has now happened three
// times: Java @DisplayName sentences, a missing Swift xUnit report, and - in
// slice S7 - a suite-name preference that broke every Swift identifier at once.
//
// The rule: for every lang:module pair the matrix links, if that pair produced
// ANY executed identifier this run, at least one of the matrix's linked
// identifiers for it must be among them. A pair that produced nothing at all is
// not flagged here - that is either a legitimately skipped job (the Swift path
// filter) or the collector's own fail-on-empty rule, which fires first.
func checkIdentifierShapes(m *Matrix, executed *ExecutedTests) []Finding {
	if executed == nil || len(executed.Tests) == 0 {
		return nil
	}

	executedByPair := map[string]int{}
	for _, id := range executed.Tests {
		if pair, ok := langModule(id); ok {
			executedByPair[pair]++
		}
	}

	linkedByPair := map[string]int{}
	matchedByPair := map[string]int{}
	for i := range m.Entries {
		for _, t := range m.Entries[i].Tests {
			pair, ok := langModule(t)
			if !ok {
				continue // e.g. a "ci:workflow#run" reference, not a test identifier
			}
			linkedByPair[pair]++
			if executed.Has(t) {
				matchedByPair[pair]++
			}
		}
	}

	var out []Finding
	for pair, linked := range linkedByPair {
		produced := executedByPair[pair]
		if produced == 0 || matchedByPair[pair] > 0 {
			continue
		}
		out = append(out, Finding{
			Rule:  "identifier-shape-drift",
			ReqID: pair,
			Detail: fmt.Sprintf(
				"run %s produced %d executed identifiers for %s but none of the %d the matrix links; "+
					"the runner's identifier format has drifted, so its evidence is silently not counting",
				executed.RunID, produced, pair, linked),
		})
	}
	return out
}

// langModule extracts the "lang:module" prefix of a matrix test identifier.
func langModule(id string) (string, bool) {
	lang, rest, ok := strings.Cut(id, ":")
	if !ok {
		return "", false
	}
	module, _, ok := strings.Cut(rest, ":")
	if !ok {
		return "", false
	}
	return lang + ":" + module, true
}

// checkGateRegister validates the matrix's own EDP Sec. 8 owner-gate register:
// every gate present exactly once, a status from the closed vocabulary, and a
// sign-off wherever the gate claims owner execution. An agent may only ever
// leave a gate at unproven_owner (TRS-AI-009/010/014).
func checkGateRegister(m *Matrix) []Finding {
	var out []Finding
	if len(m.OwnerGates) == 0 {
		out = append(out, Finding{"missing-gate-register", "", "the matrix carries no owner_gates register"})
		return out
	}

	seen := map[string]bool{}
	for _, g := range m.OwnerGates {
		if seen[g.Gate] {
			out = append(out, Finding{"duplicate-gate", g.Gate, "appears more than once"})
		}
		seen[g.Gate] = true

		switch g.Status {
		case GateStatusUnprovenOwner:
			if g.OwnerSignoff != nil && strings.TrimSpace(*g.OwnerSignoff) != "" {
				out = append(out, Finding{
					Rule:   "unproven-gate-with-signoff",
					ReqID:  g.Gate,
					Detail: "a gate carrying owner sign-off should be owner_executed, not unproven_owner",
				})
			}
		case GateStatusOwnerExecuted:
			if g.OwnerSignoff == nil || strings.TrimSpace(*g.OwnerSignoff) == "" {
				out = append(out, Finding{
					Rule:   "owner-executed-without-signoff",
					ReqID:  g.Gate,
					Detail: "owner_executed requires an owner_signoff field naming who attached the evidence",
				})
			}
			if len(g.Evidence) == 0 {
				out = append(out, Finding{
					Rule:   "owner-executed-without-evidence",
					ReqID:  g.Gate,
					Detail: "owner_executed with no evidence file; a gate is not passed by assertion",
				})
			}
		default:
			out = append(out, Finding{
				Rule:   "invalid-gate-status",
				ReqID:  g.Gate,
				Detail: fmt.Sprintf("status %q is neither %s nor %s", g.Status, GateStatusUnprovenOwner, GateStatusOwnerExecuted),
			})
		}
	}

	for _, g := range OwnerGates {
		if !seen[g.ID] {
			out = append(out, Finding{"missing-gate", g.ID, g.Title + " is not in the matrix gate register"})
		}
	}
	return out
}

// anyExecuted reports whether at least one linked test ran. Identifiers that are
// not test references - CI run markers and vector-file citations - are ignored
// rather than treated as executable, since they are evidence of a different kind.
func anyExecuted(links []string, executed *ExecutedTests) bool {
	for _, l := range links {
		if !isTestReference(l) {
			continue
		}
		if executed.Has(l) {
			return true
		}
	}
	return false
}

// TestLanguages are the runner prefixes a matrix test identifier may carry.
// "ts" joined the set in slice S7 when the admin console gained a collected
// junit report; it was missing from isTestReference until the WP-01 closeout,
// which meant a row backed only by TypeScript tests skipped the executed-test
// check entirely and could have reached 'tested' on evidence nobody verified
// had run.
var TestLanguages = []string{"go", "java", "swift", "ts"}

// isTestReference reports whether a link names an executable test.
func isTestReference(l string) bool {
	for _, lang := range TestLanguages {
		if strings.HasPrefix(l, lang+":") {
			return true
		}
	}
	return false
}

// CheckReleaseCandidate is the stricter gate of TRS Sec. 19.3: a release
// candidate may not contain a P0 entry that is neither tested nor an explicitly
// signed-off owner gate.
func CheckReleaseCandidate(m *Matrix, cat *Catalogue) []Finding {
	var out []Finding
	gates := OwnerGateByRequirement()
	for i := range m.Entries {
		e := &m.Entries[i]
		if cat.Priority[e.ReqID] != "P0" {
			continue
		}
		if e.Status == StatusTested {
			continue
		}
		if _, isGate := gates[e.ReqID]; isGate && e.Status == StatusUnproven &&
			e.OwnerSignoff != nil && strings.TrimSpace(*e.OwnerSignoff) != "" {
			continue
		}
		out = append(out, Finding{
			Rule:   "p0-not-release-ready",
			ReqID:  e.ReqID,
			Detail: fmt.Sprintf("P0 is %q; a release candidate needs tested, or unproven with owner sign-off for an EDP Sec. 8 gate", e.Status),
		})
	}
	SortFindings(out)
	return out
}

// Summary counts statuses for the run log.
func Summary(m *Matrix) string {
	counts := map[string]int{}
	for _, e := range m.Entries {
		counts[e.Status]++
	}
	keys := make([]string, 0, len(counts))
	for k := range counts {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	var parts []string
	for _, k := range keys {
		parts = append(parts, fmt.Sprintf("%s=%d", k, counts[k]))
	}
	return fmt.Sprintf("%d entries: %s", len(m.Entries), strings.Join(parts, " "))
}
