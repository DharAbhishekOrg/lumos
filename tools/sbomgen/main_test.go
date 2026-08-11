// Lumos - tools/sbomgen / Tests for the SBOM stub.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CICD-004, TRS-REP-003
// ADRs: -
// Threading: any
// Notes: The test that matters is the one asserting the document DECLARES its
// own incompleteness. An SBOM that looks complete but is not is worse than no
// SBOM, because a downstream consumer treats it as an inventory.
package main

import (
	"encoding/json"
	"os"
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

func generate(t *testing.T) SBOM {
	t.Helper()
	root := repoRoot(t)
	out := filepath.Join(t.TempDir(), "sbom.json")
	if err := run(root, out); err != nil {
		t.Fatalf("run: %v", err)
	}
	raw, err := os.ReadFile(out)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	var doc SBOM
	if err := json.Unmarshal(raw, &doc); err != nil {
		t.Fatalf("parse: %v", err)
	}
	return doc
}

func TestSBOMIsValidCycloneDX(t *testing.T) {
	doc := generate(t)
	if doc.BOMFormat != "CycloneDX" || doc.SpecVersion != "1.5" {
		t.Errorf("format %s/%s", doc.BOMFormat, doc.SpecVersion)
	}
	if len(doc.Components) == 0 {
		t.Fatal("no components; an empty SBOM would pass every downstream check vacuously")
	}
}

// TestSBOMDeclaresItsOwnLimits is the honesty assertion.
func TestSBOMDeclaresItsOwnLimits(t *testing.T) {
	doc := generate(t)
	if len(doc.Limitations) == 0 {
		t.Fatal("the stub must declare what it does not cover")
	}
	joined := strings.ToLower(strings.Join(doc.Limitations, " "))
	for _, expected := range []string{"transitive", "provenance"} {
		if !strings.Contains(joined, expected) {
			t.Errorf("limitations do not mention %q", expected)
		}
	}
	note, _ := doc.Metadata["x-note"].(string)
	if !strings.Contains(strings.ToUpper(note), "STUB") {
		t.Error("the document must name itself a stub in its metadata")
	}
}

func TestSBOMCoversEveryLockedEcosystem(t *testing.T) {
	doc := generate(t)
	seen := map[string]bool{}
	for _, c := range doc.Components {
		seen[c.Ecosystem] = true
		if c.Name == "" || c.Version == "" {
			t.Errorf("component %+v is missing a name or version", c)
		}
		if c.SourceFile == "" {
			t.Errorf("component %s does not say where it is locked", c.Name)
		}
	}
	for _, eco := range []string{"go", "gradle", "npm"} {
		if !seen[eco] {
			t.Errorf("no %s components; that ecosystem's lockfile is not being read", eco)
		}
	}
}

// TestEveryComponentCarriesALicenceOrSaysItDoesNot: an SBOM whose licence
// field is simply absent reads as "no restrictions" to a tool that consumes it.
func TestEveryComponentCarriesALicenceOrSaysItDoesNot(t *testing.T) {
	doc := generate(t)
	for _, c := range doc.Components {
		if len(c.Licenses) == 0 && c.LicenseNote == "" {
			t.Errorf("%s has neither a licence nor a note explaining its absence", c.Name)
		}
		if len(c.Licenses) > 0 && c.LicenseNote != "" {
			t.Errorf("%s carries both a licence and an absence note", c.Name)
		}
	}
}

func TestGoModulesIncludeTheApprovedCryptoDependency(t *testing.T) {
	doc := generate(t)
	for _, c := range doc.Components {
		if c.Name == "golang.org/x/crypto" {
			if !strings.HasPrefix(c.PURL, "pkg:golang/") {
				t.Errorf("bad purl %q", c.PURL)
			}
			return
		}
	}
	t.Error("golang.org/x/crypto is locked in go.mod but absent from the SBOM")
}
