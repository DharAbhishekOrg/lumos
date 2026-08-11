// Lumos - tools/deplock / Dependency lock and checksum verification (TRS-REP-003).
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-REP-003, TRS-CICD-001, TRS-CICD-004
// ADRs: -
// Threading: any
// Notes: TRS-REP-003 says versions and checksums "shall be locked". Four
// ecosystems lock differently - go.sum, a Gradle wrapper SHA-256 plus a version
// catalogue, an npm lockfile with subresource integrity, and SwiftPM's
// Package.resolved - so a single command cannot prove it. This package inspects
// each lockfile in turn and, critically, cross-checks them against the toolchain
// manifest, which is the human-readable record a reviewer actually reads. A
// manifest that disagrees with the lockfile is the failure mode that matters:
// it is the one nobody notices.
package deplock

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// Manifest is the subset of the toolchain manifest this package verifies.
type Manifest struct {
	Supporting struct {
		Gradle struct {
			Version            string `json:"version"`
			DistributionSHA256 string `json:"distribution_sha256"`
		} `json:"gradle"`
	} `json:"supporting"`
	GoDependencies struct {
		Modules []Declared `json:"modules"`
	} `json:"go_dependencies"`
	JVMTestDependencies struct {
		Modules []Declared `json:"modules"`
	} `json:"jvm_test_dependencies"`
	BuildDependencies struct {
		Modules []Declared `json:"modules"`
	} `json:"build_dependencies"`
	NPMDependencies struct {
		Modules []Declared `json:"modules"`
	} `json:"npm_dependencies"`
	NotYetPinned struct {
		Pending []string `json:"pending"`
	} `json:"not_yet_pinned"`
}

// Declared is a dependency declared in the manifest per AGENTS.md Sec. 3:
// name, version, licence and the reason it exists.
type Declared struct {
	Path           string `json:"path"`
	Version        string `json:"version"`
	License        string `json:"license"`
	Why            string `json:"why"`
	Indirect       bool   `json:"indirect"`
	CatalogueAlias string `json:"catalogue_alias"`
}

// PackageJSON is the subset of admin-console/package.json this package reads.
type PackageJSON struct {
	Dependencies    map[string]string `json:"dependencies"`
	DevDependencies map[string]string `json:"devDependencies"`
}

// ReadPackageJSON parses admin-console/package.json.
func ReadPackageJSON(repoRoot string) (*PackageJSON, error) {
	raw, err := os.ReadFile(filepath.Join(repoRoot, "admin-console", "package.json"))
	if err != nil {
		return nil, fmt.Errorf("read package.json: %w", err)
	}
	var p PackageJSON
	if err := json.Unmarshal(raw, &p); err != nil {
		return nil, fmt.Errorf("parse package.json: %w", err)
	}
	return &p, nil
}

// LoadManifest reads tools/toolchain/toolchain-manifest.json.
func LoadManifest(repoRoot string) (*Manifest, error) {
	raw, err := os.ReadFile(filepath.Join(repoRoot, "tools", "toolchain", "toolchain-manifest.json"))
	if err != nil {
		return nil, fmt.Errorf("read toolchain manifest: %w", err)
	}
	var m Manifest
	if err := json.Unmarshal(raw, &m); err != nil {
		return nil, fmt.Errorf("parse toolchain manifest: %w", err)
	}
	return &m, nil
}

// GoModule is one requirement parsed out of go.mod.
type GoModule struct {
	Path     string
	Version  string
	Indirect bool
}

var goRequire = regexp.MustCompile(`^(?:require\s+)?([a-z0-9.\-]+\.[a-z]{2,}/[^\s]+)\s+(v[^\s]+)`)

// GoModules parses the requirements out of go.mod.
func GoModules(repoRoot string) ([]GoModule, error) {
	raw, err := os.ReadFile(filepath.Join(repoRoot, "go.mod"))
	if err != nil {
		return nil, fmt.Errorf("read go.mod: %w", err)
	}
	var out []GoModule
	for _, line := range strings.Split(string(raw), "\n") {
		trimmed := strings.TrimSpace(line)
		m := goRequire.FindStringSubmatch(trimmed)
		if m == nil {
			continue
		}
		out = append(out, GoModule{
			Path:     m[1],
			Version:  m[2],
			Indirect: strings.Contains(trimmed, "// indirect"),
		})
	}
	return out, nil
}

// GoSumEntries returns every module path recorded in go.sum.
func GoSumEntries(repoRoot string) (map[string]bool, error) {
	raw, err := os.ReadFile(filepath.Join(repoRoot, "go.sum"))
	if err != nil {
		return nil, fmt.Errorf("read go.sum: %w", err)
	}
	out := map[string]bool{}
	for _, line := range strings.Split(string(raw), "\n") {
		fields := strings.Fields(line)
		if len(fields) >= 3 {
			out[fields[0]] = true
		}
	}
	return out, nil
}

// GradleWrapper is the pinned Gradle distribution.
type GradleWrapper struct {
	Version string
	SHA256  string
	URL     string
}

// ReadGradleWrapper parses gradle/wrapper/gradle-wrapper.properties.
func ReadGradleWrapper(repoRoot string) (*GradleWrapper, error) {
	raw, err := os.ReadFile(filepath.Join(repoRoot, "gradle", "wrapper", "gradle-wrapper.properties"))
	if err != nil {
		return nil, fmt.Errorf("read gradle-wrapper.properties: %w", err)
	}
	w := &GradleWrapper{}
	for _, line := range strings.Split(string(raw), "\n") {
		key, value, ok := strings.Cut(strings.TrimSpace(line), "=")
		if !ok {
			continue
		}
		// Properties files escape the colon in a URL.
		value = strings.ReplaceAll(value, `\:`, ":")
		switch key {
		case "distributionSha256Sum":
			w.SHA256 = value
		case "distributionUrl":
			w.URL = value
			if m := regexp.MustCompile(`gradle-([0-9][0-9.]*)-`).FindStringSubmatch(value); m != nil {
				w.Version = m[1]
			}
		}
	}
	return w, nil
}

// NPMLock is the subset of package-lock.json this package verifies.
type NPMLock struct {
	Name            string `json:"name"`
	LockfileVersion int    `json:"lockfileVersion"`
	Packages        map[string]struct {
		Version   string `json:"version"`
		Resolved  string `json:"resolved"`
		Integrity string `json:"integrity"`
		Link      bool   `json:"link"`
	} `json:"packages"`
}

// ReadNPMLock parses admin-console/package-lock.json.
func ReadNPMLock(repoRoot string) (*NPMLock, error) {
	raw, err := os.ReadFile(filepath.Join(repoRoot, "admin-console", "package-lock.json"))
	if err != nil {
		return nil, fmt.Errorf("read package-lock.json: %w", err)
	}
	var l NPMLock
	if err := json.Unmarshal(raw, &l); err != nil {
		return nil, fmt.Errorf("parse package-lock.json: %w", err)
	}
	return &l, nil
}

// VersionCatalogue returns the version each alias resolves to in
// gradle/libs.versions.toml.
func VersionCatalogue(repoRoot string) (map[string]string, error) {
	raw, err := os.ReadFile(filepath.Join(repoRoot, "gradle", "libs.versions.toml"))
	if err != nil {
		return nil, fmt.Errorf("read libs.versions.toml: %w", err)
	}
	out := map[string]string{}
	section := ""
	for _, line := range strings.Split(string(raw), "\n") {
		t := strings.TrimSpace(line)
		if strings.HasPrefix(t, "[") {
			section = t
			continue
		}
		if section != "[versions]" || t == "" || strings.HasPrefix(t, "#") {
			continue
		}
		key, value, ok := strings.Cut(t, "=")
		if !ok {
			continue
		}
		out[strings.TrimSpace(key)] = strings.Trim(strings.TrimSpace(value), `"`)
	}
	return out, nil
}
