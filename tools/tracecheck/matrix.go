// Lumos - tools/tracecheck / Trace-matrix model, catalogues and owner-gate registry.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-AI-003, TRS-CICD-007
// ADRs: -
// Threading: any
// Notes: The matrix is an input to the Definition of Done, not paperwork
// produced for it (TRS Sec. 19.3). Everything here is deliberately mechanical:
// a matrix reconstructed after the fact must be indistinguishable from a
// fabricated one to a human, so the checks are the only thing standing between
// an honest record and a plausible-looking one.
package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// Paths, relative to the repository root.
const (
	MatrixPath    = "evidence/trace/trace-matrix.json"
	ModuleMapPath = "evidence/trace/module-map.json"
	SRSPath       = "docs/requirements/srs_requirements_v1_1.json"
	TRSPath       = "docs/requirements/trs_requirements_v1_1.json"
)

// Valid statuses per TRS Sec. 19.3.
const (
	StatusNotStarted  = "not_started"
	StatusImplemented = "implemented"
	StatusTested      = "tested"
	StatusUnproven    = "unproven"
	StatusDeferred    = "deferred"
)

// ValidStatuses is the closed set. Anything else is a defect, not a new state.
var ValidStatuses = map[string]bool{
	StatusNotStarted:  true,
	StatusImplemented: true,
	StatusTested:      true,
	StatusUnproven:    true,
	StatusDeferred:    true,
}

// Entry is one requirement's row (EDP Sec. 6.1), plus the owner sign-off field
// TRS Sec. 19.3 requires for an owner-executed gate.
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

// GateRow is one EDP Sec. 8 owner-gate row carried by the matrix itself. This
// register was seeded with the handover pack; slice S6 began validating it.
type GateRow struct {
	Gate         string   `json:"gate"`
	Status       string   `json:"status"`
	Evidence     []string `json:"evidence"`
	OwnerSignoff *string  `json:"owner_signoff"`
}

// GateStatusUnprovenOwner is the only status an agent may leave a gate in.
const GateStatusUnprovenOwner = "unproven_owner"

// GateStatusOwnerExecuted marks a gate the owner has attached evidence for.
const GateStatusOwnerExecuted = "owner_executed"

// Matrix is the live traceability matrix.
//
// Field order mirrors the committed file so a rewrite produces a minimal diff,
// and TestMatrixStructCoversEveryTopLevelField proves the struct models every
// key present - a stamp that silently dropped an unmodelled field would corrupt
// the record it exists to protect.
type Matrix struct {
	SchemaVersion int       `json:"schema_version"`
	Baseline      string    `json:"baseline"`
	Entries       []Entry   `json:"entries"`
	OwnerGates    []GateRow `json:"owner_gates"`
	Convention    string    `json:"last_commit_convention,omitempty"`

	byID map[string]*Entry
}

// ByID indexes the entries.
func (m *Matrix) ByID() map[string]*Entry {
	if m.byID == nil {
		m.byID = make(map[string]*Entry, len(m.Entries))
		for i := range m.Entries {
			m.byID[m.Entries[i].ReqID] = &m.Entries[i]
		}
	}
	return m.byID
}

// LoadMatrix reads the matrix from repoRoot.
func LoadMatrix(repoRoot string) (*Matrix, error) {
	var m Matrix
	if err := readJSON(filepath.Join(repoRoot, filepath.FromSlash(MatrixPath)), &m); err != nil {
		return nil, err
	}
	return &m, nil
}

// ModuleMap maps module path prefixes to the requirement domains they carry.
type ModuleMap struct {
	Comment  string              `json:"comment"`
	Mappings map[string][]string `json:"mappings"`
	// Exclude is intentionally absent from the struct: slice S6 removed the
	// S2 placeholder, and re-adding one must be a deliberate schema change
	// rather than a field that quietly reappears.
}

// LoadRawMatrix reads the matrix as a generic object, so a test can prove the
// typed struct models every field the committed file carries.
func LoadRawMatrix(repoRoot string) (map[string]any, error) {
	var raw map[string]any
	if err := readJSON(filepath.Join(repoRoot, filepath.FromSlash(MatrixPath)), &raw); err != nil {
		return nil, err
	}
	return raw, nil
}

// MarshalMatrix renders the matrix in the committed file's formatting.
func MarshalMatrix(m *Matrix) ([]byte, error) {
	raw, err := json.MarshalIndent(m, "", " ")
	if err != nil {
		return nil, err
	}
	return append(raw, '\n'), nil
}

// LoadRawModuleMap reads the module map as a generic object, so a test can
// assert on fields the typed struct deliberately does not model.
func LoadRawModuleMap(repoRoot string) (map[string]any, error) {
	var raw map[string]any
	if err := readJSON(filepath.Join(repoRoot, filepath.FromSlash(ModuleMapPath)), &raw); err != nil {
		return nil, err
	}
	return raw, nil
}

// LoadModuleMap reads the module map from repoRoot.
func LoadModuleMap(repoRoot string) (*ModuleMap, error) {
	var mm ModuleMap
	if err := readJSON(filepath.Join(repoRoot, filepath.FromSlash(ModuleMapPath)), &mm); err != nil {
		return nil, err
	}
	if len(mm.Mappings) == 0 {
		return nil, fmt.Errorf("module map has no mappings")
	}
	return &mm, nil
}

// Requirement is one catalogued requirement.
type Requirement struct {
	ID       string `json:"id"`
	Priority string `json:"priority"`
}

// Catalogue is the union of the SRS and TRS requirement catalogues.
type Catalogue struct {
	Priority map[string]string
}

// IDs returns every catalogued requirement ID, sorted.
func (c *Catalogue) IDs() []string {
	out := make([]string, 0, len(c.Priority))
	for id := range c.Priority {
		out = append(out, id)
	}
	sort.Strings(out)
	return out
}

// LoadCatalogue reads and merges both requirement catalogues.
func LoadCatalogue(repoRoot string) (*Catalogue, error) {
	c := &Catalogue{Priority: map[string]string{}}
	for _, p := range []string{SRSPath, TRSPath} {
		var doc struct {
			Requirements []Requirement `json:"requirements"`
		}
		if err := readJSON(filepath.Join(repoRoot, filepath.FromSlash(p)), &doc); err != nil {
			return nil, err
		}
		for _, r := range doc.Requirements {
			if prev, dup := c.Priority[r.ID]; dup {
				return nil, fmt.Errorf("requirement %s catalogued twice (%s and %s)", r.ID, prev, r.Priority)
			}
			c.Priority[r.ID] = r.Priority
		}
	}
	if len(c.Priority) == 0 {
		return nil, fmt.Errorf("requirement catalogues are empty")
	}
	return c, nil
}

// OwnerGate is one EDP Sec. 8 gate an AI agent cannot produce evidence for.
type OwnerGate struct {
	ID           string
	Title        string
	EarliestWP   string
	Requirements []string
}

// OwnerGates is the EDP Sec. 8 register, transcribed by hand. Only requirement
// IDs are listed: the acceptance-test IDs the register also names (AT-003 and
// friends) are not rows in this matrix, which holds SRS and TRS IDs only.
//
// An agent may never mark any of these 'tested'. TRS-AI-009/010/014 are explicit
// that simulated, emulated or asserted substitutes are release-blocking
// violations, so the check below refuses the status outright rather than
// trusting a reviewer to notice.
var OwnerGates = []OwnerGate{
	{
		ID: "G-01", Title: "Physical BLE captures and interop", EarliestWP: "WP-03",
		// The register cites AT-003/004/008/011/020 only; no SRS/TRS row maps
		// directly, so this gate constrains no matrix entry today. It is listed
		// so the registry is complete and the omission is visible.
		Requirements: nil,
	},
	{
		ID: "G-02", Title: "RF-controlled performance benchmarks", EarliestWP: "WP-03/07",
		Requirements: []string{"SRS-NFR-PERF-005", "SRS-NFR-PERF-007", "SRS-NFR-BAT-004"},
	},
	{
		ID: "G-03", Title: "Push at scale and separate-network remote chat", EarliestWP: "WP-05",
		Requirements: nil,
	},
	{
		ID: "G-04", Title: "Cloud production posture", EarliestWP: "WP-05",
		Requirements: nil,
	},
	{
		ID: "G-05", Title: "KMS/HSM signing, key rotation and rollback rehearsal", EarliestWP: "WP-08",
		Requirements: []string{"SRS-ADM-005", "SRS-ADM-006", "TRS-CRY-010"},
	},
	{
		ID: "G-06", Title: "Store console setup and billing sign-off", EarliestWP: "WP-09",
		Requirements: nil,
	},
	{
		ID: "G-07", Title: "External penetration test and cryptographic review", EarliestWP: "WP-11",
		Requirements: []string{"SRS-NFR-SEC-014"},
	},
	{
		ID: "G-08", Title: "24-hour soak, venue stress, closed-beta crash-free rate", EarliestWP: "WP-11",
		Requirements: []string{"SRS-NFR-REL-002", "SRS-NFR-REL-010", "SRS-NFR-REL-011"},
	},
	{
		ID: "G-09", Title: "Legal, trademark, jurisdictional and policy clearances", EarliestWP: "any",
		Requirements: []string{"SRS-NFR-COM-003", "SRS-NFR-COM-009", "SRS-NFR-COM-010"},
	},
}

// OwnerGateByRequirement indexes gates by the requirement IDs they constrain.
func OwnerGateByRequirement() map[string]OwnerGate {
	out := map[string]OwnerGate{}
	for _, g := range OwnerGates {
		for _, r := range g.Requirements {
			out[r] = g
		}
	}
	return out
}

// Finding is one validation failure. Rule keeps CI output machine-readable.
type Finding struct {
	Rule   string
	ReqID  string
	Detail string
}

func (f Finding) String() string {
	if f.ReqID == "" {
		return fmt.Sprintf("[%s] %s", f.Rule, f.Detail)
	}
	return fmt.Sprintf("[%s] %s: %s", f.Rule, f.ReqID, f.Detail)
}

// SortFindings orders findings for stable output.
func SortFindings(f []Finding) {
	sort.Slice(f, func(i, j int) bool {
		if f[i].Rule != f[j].Rule {
			return f[i].Rule < f[j].Rule
		}
		return f[i].ReqID < f[j].ReqID
	})
}

// ModulesFor returns the mapped prefixes a changed path falls under.
func (mm *ModuleMap) ModulesFor(path string) []string {
	var out []string
	clean := strings.ReplaceAll(path, "\\", "/")
	for prefix := range mm.Mappings {
		if strings.HasPrefix(clean, prefix) {
			out = append(out, prefix)
		}
	}
	sort.Strings(out)
	return out
}

// RequirementsFor expands a mapped prefix's domains into concrete requirement
// IDs present in the catalogue. A domain entry may be a full ID or a prefix.
func (mm *ModuleMap) RequirementsFor(prefix string, cat *Catalogue) []string {
	var out []string
	for _, domain := range mm.Mappings[prefix] {
		for _, id := range cat.IDs() {
			if id == domain || strings.HasPrefix(id, domain+"-") {
				out = append(out, id)
			}
		}
	}
	sort.Strings(out)
	return dedupe(out)
}

func dedupe(in []string) []string {
	seen := map[string]bool{}
	out := in[:0]
	for _, s := range in {
		if !seen[s] {
			seen[s] = true
			out = append(out, s)
		}
	}
	return out
}

func readJSON(path string, into any) error {
	raw, err := os.ReadFile(path)
	if err != nil {
		return fmt.Errorf("read %s: %w", filepath.Base(path), err)
	}
	if err := json.Unmarshal(raw, into); err != nil {
		return fmt.Errorf("parse %s: %w", filepath.Base(path), err)
	}
	return nil
}
