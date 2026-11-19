// Lumos - tools/tracecheck / Tests for the three traceability rules.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-AI-003, TRS-CICD-007
// ADRs: -
// Threading: any
// Notes: Every rule has a NEGATIVE fixture. A traceability gate that has only
// ever run against a healthy matrix is indistinguishable from one that returns
// nil, and this gate is the thing standing between an honest record and a
// plausible-looking one - so it is the last place to accept an untested check.
package main

import (
	"encoding/json"
	"fmt"
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

func liveCatalogue(t *testing.T) *Catalogue {
	t.Helper()
	c, err := LoadCatalogue(repoRoot(t))
	if err != nil {
		t.Fatalf("LoadCatalogue: %v", err)
	}
	return c
}

func s(v string) *string { return &v }

// tinyCatalogue is a synthetic catalogue for rule-level fixtures.
func tinyCatalogue() *Catalogue {
	return &Catalogue{Priority: map[string]string{
		"TRS-AAA-001": "P0",
		"TRS-AAA-002": "P1",
		"TRS-BBB-001": "P0",
	}}
}

func tinyMap() *ModuleMap {
	return &ModuleMap{Mappings: map[string][]string{
		"protocol/": {"TRS-AAA"},
		"backend/":  {"TRS-BBB-001"},
	}}
}

// cleanGates is a fully populated, honest gate register. Entry-level fixtures
// include it so they exercise the rule under test rather than the register.
func cleanGates() []GateRow {
	var rows []GateRow
	for _, g := range OwnerGates {
		rows = append(rows, GateRow{Gate: g.ID, Status: GateStatusUnprovenOwner})
	}
	return rows
}

func rules(f []Finding) map[string]int {
	out := map[string]int{}
	for _, x := range f {
		out[x.Rule]++
	}
	return out
}

// ---- live matrix ----

func TestLiveMatrixPassesEveryStructuralRule(t *testing.T) {
	root := repoRoot(t)
	m, err := LoadMatrix(root)
	if err != nil {
		t.Fatalf("LoadMatrix: %v", err)
	}
	cat := liveCatalogue(t)

	for _, f := range CheckCompleteness(m, cat) {
		t.Errorf("completeness: %s", f)
	}
	for _, f := range CheckHonesty(m, cat, nil) {
		t.Errorf("honesty: %s", f)
	}
}

func TestLiveMatrixIsExactlyTheCatalogue(t *testing.T) {
	root := repoRoot(t)
	m, err := LoadMatrix(root)
	if err != nil {
		t.Fatalf("LoadMatrix: %v", err)
	}
	cat := liveCatalogue(t)
	if len(m.Entries) != len(cat.Priority) {
		t.Errorf("matrix has %d entries, catalogue has %d", len(m.Entries), len(cat.Priority))
	}
}

// TestModuleMapHasNoExclusion pins the S6 replacement of the S2 placeholder: an
// exclusion list must not reappear without a deliberate schema change.
func TestModuleMapHasNoExclusion(t *testing.T) {
	raw, err := LoadRawModuleMap(repoRoot(t))
	if err != nil {
		t.Fatalf("read module map: %v", err)
	}
	if _, present := raw["exclude"]; present {
		t.Error("module-map.json carries an exclude list; freshness must apply to every file under a mapped module (S6 replaced the S2 placeholder with -stamp -auto)")
	}
	if _, present := raw["exclude_rationale"]; present {
		t.Error("module-map.json still carries exclude_rationale")
	}
}

// TestMatrixStructCoversEveryTopLevelField is the guard that stops -stamp from
// corrupting the record it protects. The stamp rewrites the whole file through
// the Matrix struct, so any top-level key the struct does not model would be
// silently deleted. This slice caught exactly that: the seeded matrix carried an
// owner_gates register nobody had modelled, and the first -stamp run dropped it.
func TestMatrixStructCoversEveryTopLevelField(t *testing.T) {
	root := repoRoot(t)
	raw, err := LoadRawMatrix(root)
	if err != nil {
		t.Fatalf("read matrix: %v", err)
	}
	m, err := LoadMatrix(root)
	if err != nil {
		t.Fatalf("LoadMatrix: %v", err)
	}
	roundTripped, err := MarshalMatrix(m)
	if err != nil {
		t.Fatalf("MarshalMatrix: %v", err)
	}
	var after map[string]any
	if err := json.Unmarshal(roundTripped, &after); err != nil {
		t.Fatalf("re-parse: %v", err)
	}
	for key := range raw {
		if _, kept := after[key]; !kept {
			t.Errorf("top-level field %q is dropped by a stamp rewrite; model it on the Matrix struct", key)
		}
	}
}

// TestStampRoundTripPreservesEveryEntryField proves the rewrite is lossless at
// entry level too, not merely at the top level.
func TestStampRoundTripPreservesEveryEntryField(t *testing.T) {
	root := repoRoot(t)
	raw, err := LoadRawMatrix(root)
	if err != nil {
		t.Fatalf("read matrix: %v", err)
	}
	m, err := LoadMatrix(root)
	if err != nil {
		t.Fatalf("LoadMatrix: %v", err)
	}
	out, err := MarshalMatrix(m)
	if err != nil {
		t.Fatalf("MarshalMatrix: %v", err)
	}
	var after map[string]any
	if err := json.Unmarshal(out, &after); err != nil {
		t.Fatalf("re-parse: %v", err)
	}

	before, _ := raw["entries"].([]any)
	got, _ := after["entries"].([]any)
	if len(before) != len(got) {
		t.Fatalf("entry count changed: %d -> %d", len(before), len(got))
	}
	for i := range before {
		b, _ := before[i].(map[string]any)
		g, _ := got[i].(map[string]any)
		for k, bv := range b {
			gv, kept := g[k]
			if !kept {
				t.Errorf("entry %d lost field %q", i, k)
				continue
			}
			if fmt.Sprint(bv) != fmt.Sprint(gv) {
				t.Errorf("entry %d field %q changed: %v -> %v", i, k, bv, gv)
			}
		}
	}

	gatesBefore, _ := raw["owner_gates"].([]any)
	gatesAfter, _ := after["owner_gates"].([]any)
	if len(gatesBefore) != len(gatesAfter) {
		t.Errorf("owner_gates count changed: %d -> %d", len(gatesBefore), len(gatesAfter))
	}
}

// TestGateRegisterMatchesEDPSection8 pins the nine gates by hand.
func TestGateRegisterMatchesEDPSection8(t *testing.T) {
	want := []string{"G-01", "G-02", "G-03", "G-04", "G-05", "G-06", "G-07", "G-08", "G-09"}
	if len(OwnerGates) != len(want) {
		t.Fatalf("registry has %d gates, EDP Sec. 8 defines %d", len(OwnerGates), len(want))
	}
	for i, g := range OwnerGates {
		if g.ID != want[i] {
			t.Errorf("gate[%d] = %s, want %s", i, g.ID, want[i])
		}
	}

	m, err := LoadMatrix(repoRoot(t))
	if err != nil {
		t.Fatalf("LoadMatrix: %v", err)
	}
	if len(m.OwnerGates) != len(want) {
		t.Fatalf("matrix gate register has %d rows, want %d", len(m.OwnerGates), len(want))
	}
	for _, row := range m.OwnerGates {
		if row.Status != GateStatusUnprovenOwner {
			t.Errorf("gate %s is %q; every gate is still awaiting owner evidence", row.Gate, row.Status)
		}
	}
}

// TestGateRegisterRejectsDishonestRows exercises the negative side.
func TestGateRegisterRejectsDishonestRows(t *testing.T) {
	cat := tinyCatalogue()
	base := func(rows []GateRow) *Matrix {
		return &Matrix{OwnerGates: rows, Entries: []Entry{
			{ReqID: "TRS-AAA-001", Status: StatusNotStarted},
		}}
	}
	all := func() []GateRow {
		var rows []GateRow
		for _, g := range OwnerGates {
			rows = append(rows, GateRow{Gate: g.ID, Status: GateStatusUnprovenOwner})
		}
		return rows
	}

	t.Run("owner_executed without sign-off", func(t *testing.T) {
		rows := all()
		rows[0].Status = GateStatusOwnerExecuted
		rows[0].Evidence = []string{"evidence/rc1/G-01.json"}
		if rules(CheckHonesty(base(rows), cat, nil))["owner-executed-without-signoff"] != 1 {
			t.Error("a gate claiming owner execution without a sign-off must be reported")
		}
	})

	t.Run("owner_executed without evidence", func(t *testing.T) {
		rows := all()
		rows[0].Status = GateStatusOwnerExecuted
		rows[0].OwnerSignoff = s("owner: captured 2026-09-01")
		if rules(CheckHonesty(base(rows), cat, nil))["owner-executed-without-evidence"] != 1 {
			t.Error("a gate is not passed by assertion; missing evidence must be reported")
		}
	})

	t.Run("invented gate status", func(t *testing.T) {
		rows := all()
		rows[2].Status = "probably_fine"
		if rules(CheckHonesty(base(rows), cat, nil))["invalid-gate-status"] != 1 {
			t.Error("an invented gate status must be reported")
		}
	})

	t.Run("missing gate", func(t *testing.T) {
		rows := all()[:5]
		if rules(CheckHonesty(base(rows), cat, nil))["missing-gate"] != 4 {
			t.Error("every EDP Sec. 8 gate must be present in the register")
		}
	})
}

// ---- completeness ----

func TestCompletenessDetectsMissingOrphanAndDuplicate(t *testing.T) {
	cat := tinyCatalogue()

	t.Run("missing entry", func(t *testing.T) {
		m := &Matrix{Entries: []Entry{
			{ReqID: "TRS-AAA-001", Status: StatusNotStarted},
			{ReqID: "TRS-AAA-002", Status: StatusNotStarted},
		}}
		if rules(CheckCompleteness(m, cat))["missing-entry"] != 1 {
			t.Error("a catalogued ID with no entry must be reported")
		}
	})

	t.Run("orphan entry", func(t *testing.T) {
		m := &Matrix{Entries: []Entry{
			{ReqID: "TRS-AAA-001", Status: StatusNotStarted},
			{ReqID: "TRS-AAA-002", Status: StatusNotStarted},
			{ReqID: "TRS-BBB-001", Status: StatusNotStarted},
			{ReqID: "TRS-ZZZ-999", Status: StatusNotStarted},
		}}
		if rules(CheckCompleteness(m, cat))["orphan-entry"] != 1 {
			t.Error("an entry absent from the catalogues must be reported")
		}
	})

	t.Run("duplicate entry", func(t *testing.T) {
		m := &Matrix{Entries: []Entry{
			{ReqID: "TRS-AAA-001", Status: StatusNotStarted},
			{ReqID: "TRS-AAA-001", Status: StatusNotStarted},
			{ReqID: "TRS-AAA-002", Status: StatusNotStarted},
			{ReqID: "TRS-BBB-001", Status: StatusNotStarted},
		}}
		if rules(CheckCompleteness(m, cat))["duplicate-entry"] != 1 {
			t.Error("a duplicated requirement row must be reported")
		}
	})

	t.Run("invalid status", func(t *testing.T) {
		m := &Matrix{Entries: []Entry{
			{ReqID: "TRS-AAA-001", Status: "nearly_done"},
			{ReqID: "TRS-AAA-002", Status: StatusNotStarted},
			{ReqID: "TRS-BBB-001", Status: StatusNotStarted},
		}}
		if rules(CheckCompleteness(m, cat))["invalid-status"] != 1 {
			t.Error("an invented status must be reported")
		}
	})
}

// ---- freshness ----

func TestFreshnessDetectsStaleEntries(t *testing.T) {
	cat := tinyCatalogue()
	mm := tinyMap()

	base := &Matrix{Entries: []Entry{
		{ReqID: "TRS-AAA-001", Status: StatusImplemented, LastCommit: s("aaaa111")},
		{ReqID: "TRS-AAA-002", Status: StatusImplemented, LastCommit: s("aaaa111")},
		{ReqID: "TRS-BBB-001", Status: StatusImplemented, LastCommit: s("aaaa111")},
	}}

	t.Run("touched module without a stamp is stale", func(t *testing.T) {
		head := &Matrix{Entries: []Entry{
			{ReqID: "TRS-AAA-001", Status: StatusImplemented, LastCommit: s("aaaa111")},
			{ReqID: "TRS-AAA-002", Status: StatusImplemented, LastCommit: s("aaaa111")},
			{ReqID: "TRS-BBB-001", Status: StatusImplemented, LastCommit: s("aaaa111")},
		}}
		f := CheckFreshness(head, base, mm, cat, []string{"protocol/wire/frame.go"})
		if len(f) != 2 {
			t.Fatalf("both TRS-AAA entries should be stale, got %d: %v", len(f), f)
		}
		for _, x := range f {
			if x.Rule != "stale-entry" {
				t.Errorf("unexpected rule %s", x.Rule)
			}
			if !strings.Contains(x.Detail, "protocol/") {
				t.Errorf("the finding should name the module that demanded the stamp: %s", x.Detail)
			}
		}
	})

	t.Run("stamped entries pass", func(t *testing.T) {
		head := &Matrix{Entries: []Entry{
			{ReqID: "TRS-AAA-001", Status: StatusImplemented, LastCommit: s("bbbb222")},
			{ReqID: "TRS-AAA-002", Status: StatusImplemented, LastCommit: s("bbbb222")},
			{ReqID: "TRS-BBB-001", Status: StatusImplemented, LastCommit: s("aaaa111")},
		}}
		if f := CheckFreshness(head, base, mm, cat, []string{"protocol/wire/frame.go"}); len(f) != 0 {
			t.Errorf("stamped entries must pass, got %v", f)
		}
	})

	t.Run("unmapped path demands nothing", func(t *testing.T) {
		head := &Matrix{Entries: base.Entries}
		if f := CheckFreshness(head, base, mm, cat, []string{"docs/README.md"}); len(f) != 0 {
			t.Errorf("an unmapped path must not demand a stamp, got %v", f)
		}
	})

	// A documentation file UNDER a mapped module still demands a stamp: the S2
	// exclusion is gone, and this is the case that proves it.
	t.Run("documentation under a mapped module still demands a stamp", func(t *testing.T) {
		head := &Matrix{Entries: base.Entries}
		f := CheckFreshness(head, base, mm, cat, []string{"protocol/README.md"})
		if len(f) == 0 {
			t.Error("a README under a mapped module must demand a stamp now that the exclusion is removed")
		}
	})
}

// ---- honesty ----

func TestHonestyStructuralRules(t *testing.T) {
	cat := tinyCatalogue()

	t.Run("tested without a linked test", func(t *testing.T) {
		m := &Matrix{Entries: []Entry{{
			ReqID: "TRS-AAA-001", Status: StatusTested,
			Code: []string{"protocol/x.go"}, LastCommit: s("abc"), Updated: s("2026-08-12"),
		}}}
		if rules(CheckHonesty(m, cat, nil))["tested-without-tests"] != 1 {
			t.Error("tested with no linked test must be reported")
		}
	})

	t.Run("tested without linked code", func(t *testing.T) {
		m := &Matrix{Entries: []Entry{{
			ReqID: "TRS-AAA-001", Status: StatusTested,
			Tests: []string{"go:x:TestY"}, LastCommit: s("abc"), Updated: s("2026-08-12"),
		}}}
		if rules(CheckHonesty(m, cat, nil))["tested-without-code"] != 1 {
			t.Error("tested with no linked code must be reported")
		}
	})

	t.Run("not_started with links understates the work", func(t *testing.T) {
		m := &Matrix{Entries: []Entry{{
			ReqID: "TRS-AAA-001", Status: StatusNotStarted, Code: []string{"protocol/x.go"},
		}}}
		if rules(CheckHonesty(m, cat, nil))["not-started-with-links"] != 1 {
			t.Error("not_started with linked code must be reported")
		}
	})

	t.Run("non-default status without provenance", func(t *testing.T) {
		m := &Matrix{Entries: []Entry{{
			ReqID: "TRS-AAA-001", Status: StatusImplemented, Code: []string{"protocol/x.go"},
		}}}
		got := rules(CheckHonesty(m, cat, nil))
		if got["missing-last-commit"] != 1 || got["missing-updated"] != 1 {
			t.Errorf("implemented without last_commit/updated must be reported, got %v", got)
		}
	})
}

func TestHonestyOwnerGateRules(t *testing.T) {
	cat := &Catalogue{Priority: map[string]string{
		"TRS-CRY-010": "P0", "SRS-NFR-SEC-014": "P0", "TRS-AAA-001": "P0",
	}}

	t.Run("an owner gate may never be claimed tested", func(t *testing.T) {
		m := &Matrix{Entries: []Entry{{
			ReqID: "TRS-CRY-010", Status: StatusTested,
			Code: []string{"x"}, Tests: []string{"go:x:TestY"},
			LastCommit: s("abc"), Updated: s("2026-08-12"),
		}}}
		if rules(CheckHonesty(m, cat, nil))["owner-gate-claimed-tested"] != 1 {
			t.Error("claiming an EDP Sec. 8 gate as tested must be reported (TRS-AI-009/010/014)")
		}
	})

	t.Run("unproven owner gate needs a sign-off field", func(t *testing.T) {
		m := &Matrix{Entries: []Entry{{
			ReqID: "SRS-NFR-SEC-014", Status: StatusUnproven,
			Code: []string{"x"}, LastCommit: s("abc"), Updated: s("2026-08-12"),
		}}}
		if rules(CheckHonesty(m, cat, nil))["unproven-without-signoff"] != 1 {
			t.Error("unproven without owner_signoff must be reported")
		}
	})

	t.Run("signed-off unproven owner gate passes", func(t *testing.T) {
		m := &Matrix{OwnerGates: cleanGates(), Entries: []Entry{{
			ReqID: "SRS-NFR-SEC-014", Status: StatusUnproven,
			Code: []string{"x"}, LastCommit: s("abc"), Updated: s("2026-08-12"),
			OwnerSignoff: s("owner: pen test scheduled 2026-09"),
		}}}
		if f := CheckHonesty(m, cat, nil); len(f) != 0 {
			t.Errorf("a signed-off owner gate must pass, got %v", f)
		}
	})

	t.Run("unproven outside an owner gate is refused", func(t *testing.T) {
		m := &Matrix{Entries: []Entry{{
			ReqID: "TRS-AAA-001", Status: StatusUnproven,
			Code: []string{"x"}, LastCommit: s("abc"), Updated: s("2026-08-12"),
			OwnerSignoff: s("owner: later"),
		}}}
		if rules(CheckHonesty(m, cat, nil))["unproven-outside-owner-gate"] != 1 {
			t.Error("unproven is reserved for owner gates; ordinary unfinished work must not use it")
		}
	})
}

func TestHonestyRequiresATestThatActuallyRan(t *testing.T) {
	cat := tinyCatalogue()
	entry := Entry{
		ReqID: "TRS-AAA-001", Status: StatusTested,
		Code: []string{"protocol/x.go"}, Tests: []string{"go:protocol/wire:TestSomething"},
		LastCommit: s("abc"), Updated: s("2026-08-12"),
	}

	t.Run("linked test did not run", func(t *testing.T) {
		m := &Matrix{Entries: []Entry{entry}}
		ex := &ExecutedTests{RunID: "run-1", Tests: []string{"go:protocol/wire:TestSomethingElse"}}
		if rules(CheckHonesty(m, cat, ex))["tested-without-executed-test"] != 1 {
			t.Error("tested must fail when no linked test appears in the run's executed set")
		}
	})

	t.Run("linked test ran", func(t *testing.T) {
		m := &Matrix{OwnerGates: cleanGates(), Entries: []Entry{entry}}
		ex := &ExecutedTests{RunID: "run-1", Tests: []string{"go:protocol/wire:TestSomething"}}
		if f := CheckHonesty(m, cat, ex); len(f) != 0 {
			t.Errorf("tested must pass when a linked test ran, got %v", f)
		}
	})

	t.Run("non-test evidence links do not satisfy the rule", func(t *testing.T) {
		e := entry
		e.Tests = []string{"ci:conformance#123", "vectors:crypto/aead_kat.json"}
		m := &Matrix{Entries: []Entry{e}}
		ex := &ExecutedTests{RunID: "run-1", Tests: []string{"go:protocol/wire:TestSomething"}}
		if rules(CheckHonesty(m, cat, ex))["tested-without-executed-test"] != 1 {
			t.Error("a CI marker or vector citation is evidence of a different kind and must not stand in for an executed test")
		}
	})
}

// ---- release candidate ----

func TestReleaseCandidateGate(t *testing.T) {
	cat := &Catalogue{Priority: map[string]string{
		"TRS-AAA-001": "P0", "TRS-AAA-002": "P1", "TRS-CRY-010": "P0",
	}}

	m := &Matrix{Entries: []Entry{
		{ReqID: "TRS-AAA-001", Status: StatusImplemented},
		{ReqID: "TRS-AAA-002", Status: StatusNotStarted},
		{ReqID: "TRS-CRY-010", Status: StatusUnproven, OwnerSignoff: s("owner: KMS rehearsal booked")},
	}}
	f := CheckReleaseCandidate(m, cat)
	if len(f) != 1 || f[0].ReqID != "TRS-AAA-001" {
		t.Fatalf("only the unfinished P0 should block a release candidate, got %v", f)
	}

	// A P1 that is not tested does not block; an unsigned owner gate does.
	m2 := &Matrix{Entries: []Entry{
		{ReqID: "TRS-CRY-010", Status: StatusUnproven},
	}}
	if len(CheckReleaseCandidate(m2, cat)) != 1 {
		t.Error("an owner gate without sign-off must block a release candidate")
	}
}

// ---- executed-test collection ----

func TestGoPackageShortening(t *testing.T) {
	got := shortGoPackage("github.com/dharabhishekorg/lumos/protocol/wire")
	if got != "protocol/wire" {
		t.Errorf("shortGoPackage = %q, want protocol/wire", got)
	}
}
