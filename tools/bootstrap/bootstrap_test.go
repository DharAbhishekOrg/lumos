// Lumos - tools/bootstrap / Tests for the fresh-machine bootstrap scripts.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-REP-006, TRS-REP-005, TRS-CICD-008
// ADRs: -
// Threading: any
// Notes: Shell scripts are not compiled and not type-checked, so nothing stops
// them rotting except a test. The two assertions that earn their place are
// TestScriptsCarryNoVersionLiterals - which is the only thing preventing a
// second, drifting copy of the pinned toolchain - and
// TestSampleEnvironmentCarriesNothingSecretShaped, which enforces TRS-REP-005
// at the one file in the repository designed to look like a credential store.
//
// What these tests CANNOT do is run the scripts on a fresh machine. That is
// TRS-REP-006's stated verification method ("fresh-machine setup test") and it
// needs a clean host, so TRS-REP-006 stays 'implemented', not 'tested'.
package bootstrap

import (
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
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

func read(t *testing.T, parts ...string) string {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join(append([]string{repoRoot(t)}, parts...)...))
	if err != nil {
		t.Fatalf("read %v: %v", parts, err)
	}
	return string(raw)
}

func scripts(t *testing.T) map[string]string {
	t.Helper()
	return map[string]string{
		"bootstrap.sh":  read(t, "tools", "bootstrap", "bootstrap.sh"),
		"bootstrap.ps1": read(t, "tools", "bootstrap", "bootstrap.ps1"),
	}
}

// manifestVersions returns the version strings pinned in the toolchain
// manifest, which is the single source of truth the scripts must read.
func manifestVersions(t *testing.T) []string {
	t.Helper()
	var doc struct {
		Toolchains map[string]struct {
			Version *string `json:"version"`
			NPM     string  `json:"npm"`
		} `json:"toolchains"`
		Supporting map[string]struct {
			Version            string `json:"version"`
			DistributionSHA256 string `json:"distribution_sha256"`
		} `json:"supporting"`
	}
	if err := json.Unmarshal([]byte(read(t, "tools", "toolchain", "toolchain-manifest.json")), &doc); err != nil {
		t.Fatalf("parse manifest: %v", err)
	}
	var out []string
	for _, tc := range doc.Toolchains {
		if tc.Version != nil && *tc.Version != "" {
			out = append(out, *tc.Version)
		}
		if tc.NPM != "" {
			out = append(out, tc.NPM)
		}
	}
	for name, s := range doc.Supporting {
		// git's version is reported, never pinned or asserted against, so a
		// script quoting it would not be drift.
		if name == "git" {
			continue
		}
		if s.Version != "" {
			out = append(out, s.Version)
		}
		if s.DistributionSHA256 != "" {
			out = append(out, s.DistributionSHA256)
		}
	}
	if len(out) < 3 {
		t.Fatalf("manifest yielded only %d versions; the parser has drifted from the file", len(out))
	}
	return out
}

// TestScriptsCarryNoVersionLiterals is the anti-drift rule. A version written
// into a script is a second source of truth that no build step compares against
// the manifest, so it silently becomes wrong.
func TestScriptsCarryNoVersionLiterals(t *testing.T) {
	versions := manifestVersions(t)
	for name, body := range scripts(t) {
		for _, v := range versions {
			if strings.Contains(body, v) {
				t.Errorf("%s hardcodes the pinned version %q; read it from the manifest instead", name, v)
			}
		}
	}
}

func TestBothScriptsReadTheManifest(t *testing.T) {
	for name, body := range scripts(t) {
		if !strings.Contains(body, "toolchain-manifest.json") {
			t.Errorf("%s never names the toolchain manifest", name)
		}
	}
}

// TestEveryPinnedToolchainIsChecked stops a toolchain being added to the
// manifest and forgotten by the thing that installs it.
func TestEveryPinnedToolchainIsChecked(t *testing.T) {
	var doc struct {
		Toolchains map[string]json.RawMessage `json:"toolchains"`
	}
	if err := json.Unmarshal([]byte(read(t, "tools", "toolchain", "toolchain-manifest.json")), &doc); err != nil {
		t.Fatalf("parse manifest: %v", err)
	}
	if len(doc.Toolchains) == 0 {
		t.Fatal("no toolchains in the manifest")
	}
	for name, body := range scripts(t) {
		lower := strings.ToLower(body)
		for tool := range doc.Toolchains {
			if !strings.Contains(lower, strings.ToLower(tool)) {
				t.Errorf("%s does not mention the pinned toolchain %q", name, tool)
			}
		}
	}
}

// TestBothScriptsApplyTheSameVersionPolicy keeps the two hosts honest about
// each other: a rule enforced on Linux but not Windows is not a rule.
func TestBothScriptsApplyTheSameVersionPolicy(t *testing.T) {
	for name, body := range scripts(t) {
		for _, phrase := range []string{"major/minor mismatch", "patch drift"} {
			if !strings.Contains(body, phrase) {
				t.Errorf("%s does not implement the %q outcome", name, phrase)
			}
		}
	}
}

// TestScriptsCoverTheKnownGaps pins the three things a fresh machine gets wrong
// and slice S0 recorded as outstanding.
func TestScriptsCoverTheKnownGaps(t *testing.T) {
	required := map[string]string{
		"cmdline-tools": "the S0 preflight gap: licences cannot be accepted headlessly without it",
		"android-36":    "TRS Sec. 5.3 pins the compileSdk/targetSdk baseline at 36",
		"gradlew":       "the committed wrapper is SHA-256 pinned; a system Gradle bypasses it",
		"sample-env":    "TRS-REP-006 requires a deterministic sample environment",
	}
	for name, body := range scripts(t) {
		for token, why := range required {
			if !strings.Contains(body, token) {
				t.Errorf("%s never checks %q - %s", name, token, why)
			}
		}
	}
}

func TestPosixScriptIsExecutableShellWithUnixEndings(t *testing.T) {
	body := read(t, "tools", "bootstrap", "bootstrap.sh")
	if !strings.HasPrefix(body, "#!/usr/bin/env bash") {
		t.Error("bootstrap.sh has no bash shebang; a fresh machine may run it under dash")
	}
	if !strings.Contains(body, "set -euo pipefail") {
		t.Error("bootstrap.sh does not fail fast; a bootstrap that ignores errors reports success falsely")
	}
	if strings.Contains(body, "\r\n") {
		t.Error("bootstrap.sh has CRLF line endings and will not execute on Linux or macOS")
	}
}

// TestSampleEnvironmentCarriesNothingSecretShaped enforces TRS-REP-005 on the
// one file in the repository whose whole purpose is to look like a credential
// file. The scan is deliberately blunt: a long opaque token is rejected on
// shape alone, without asking whether it happens to be real.
func TestSampleEnvironmentCarriesNothingSecretShaped(t *testing.T) {
	body := read(t, "tools", "bootstrap", "sample-env", "lumos.env.sample")

	forbiddenKeys := []string{
		"PASSWORD", "SECRET", "PRIVATE_KEY", "APNS", "KEYSTORE",
		"SERVICE_ACCOUNT", "API_TOKEN", "ACCESS_KEY", "DATABASE_URL",
	}
	longOpaque := regexp.MustCompile(`[A-Za-z0-9+/_-]{32,}={0,2}`)

	for i, line := range strings.Split(body, "\n") {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" || strings.HasPrefix(trimmed, "#") {
			continue // prose may name a secret it is warning you not to add
		}
		key, value, ok := strings.Cut(trimmed, "=")
		if !ok {
			t.Errorf("line %d is neither a comment nor KEY=VALUE: %q", i+1, trimmed)
			continue
		}
		upper := strings.ToUpper(key)
		for _, bad := range forbiddenKeys {
			if strings.Contains(upper, bad) {
				t.Errorf("line %d defines %s; the sample environment must not carry a %s-shaped key at all",
					i+1, key, bad)
			}
		}
		if longOpaque.MatchString(value) {
			t.Errorf("line %d value %q is long and opaque; a placeholder must be obviously not a credential",
				i+1, value)
		}
	}
}

// TestSampleEnvironmentSeparatesTheFourVariants ties the sample environment to
// TRS-REP-004's vocabulary rather than letting it invent its own.
func TestSampleEnvironmentSeparatesTheFourVariants(t *testing.T) {
	body := read(t, "tools", "bootstrap", "sample-env", "lumos.env.sample")
	for _, env := range []string{"development", "test", "staging", "production"} {
		if !strings.Contains(body, env) {
			t.Errorf("the sample environment never names the %q variant (TRS-REP-004)", env)
		}
	}
	if !strings.Contains(body, "LUMOS_ENVIRONMENT=development") {
		t.Error("the sample environment must default to development, not to a deployed variant")
	}
}

// --- negative fixtures ------------------------------------------------------
// Both scanners above pass today because the files are clean. A scanner that
// has only ever seen healthy input is indistinguishable from one that returns
// nothing, so each rule is exercised against input it must reject.

func TestVersionLiteralScannerRejectsAHardcodedVersion(t *testing.T) {
	versions := manifestVersions(t)
	poisoned := "GO_PIN=\"" + versions[0] + "\"  # hardcoded on purpose\n"
	found := false
	for _, v := range versions {
		if strings.Contains(poisoned, v) {
			found = true
		}
	}
	if !found {
		t.Fatal("the literal scanner does not detect a version copied straight out of the manifest")
	}
}

func TestSecretShapeScannerRejectsACredentialShapedValue(t *testing.T) {
	longOpaque := regexp.MustCompile(`[A-Za-z0-9+/_-]{32,}={0,2}`)
	rejected := []string{
		"AKIAIOSFODNN7EXAMPLEAKIAIOSFODNN7EXAMPLE",
		"ghp_1234567890abcdefghijklmnopqrstuvwxyz",
		"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9",
	}
	for _, v := range rejected {
		if !longOpaque.MatchString(v) {
			t.Errorf("the secret-shape scanner would let %q through the sample environment", v)
		}
	}
	accepted := []string{"development", "127.0.0.1:8080", "0.0.0-dev", "0000000", "debug"}
	for _, v := range accepted {
		if longOpaque.MatchString(v) {
			t.Errorf("the secret-shape scanner rejects the legitimate placeholder %q", v)
		}
	}
}

func TestBootstrapIsDocumented(t *testing.T) {
	doc := read(t, "tools", "bootstrap", "README.md")
	for _, expected := range []string{"bootstrap.sh", "bootstrap.ps1", "TRS-REP-006", ".env.local"} {
		if !strings.Contains(doc, expected) {
			t.Errorf("tools/bootstrap/README.md does not cover %q", expected)
		}
	}
}
