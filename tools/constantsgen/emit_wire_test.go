// Lumos - tools/constantsgen / Tests for the generated error codes and class limits.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-PROTO-007, TRS-PROTO-013, TRS-CONST-001
// ADRs: ADR-004
// Threading: any
// Notes: expectedLimits transcribes the payload bounds by resolving TRS Sec. 9.2
// class -> TRS Sec. 23 constant BY HAND, so a unit-multiplier bug (kb vs mb) in
// the emitter cannot silently agree with itself.
package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func loadWire(t *testing.T) (*Register, *Model, *wireInputs) {
	t.Helper()
	r, m, _ := buildModel(t)
	in, err := LoadWireInputs(filepath.Join("..", ".."))
	if err != nil {
		t.Fatalf("LoadWireInputs: %v", err)
	}
	if err := ValidateWireCoverage(r, m, in); err != nil {
		t.Fatalf("ValidateWireCoverage: %v", err)
	}
	return r, m, in
}

// expectedLimits: class -> bytes, transcribed by hand from TRS Sec. 9.2 payload
// rules and TRS Sec. 23 defaults.
var expectedLimits = map[string]int64{
	"HELLO":           1228,    // QR_BOOTSTRAP_MAX_BYTES
	"PROFILE_PREVIEW": 65536,   // PREVIEW_MAX_KB 64 * 1024
	"PROFILE_FULL":    2097152, // FULL_IMAGE_MAX_MB 2 * 1024^2
	"TEXT_MESSAGE":    32768,   // RELAY_TEXT_ENVELOPE_MAX_BYTES
	"OBJECT_CHUNK":    65536,   // LAN_CHUNK_PAYLOAD_BYTES
	"MESH_RELAY":      32768,   // RELAY_TEXT_ENVELOPE_MAX_BYTES
	"STORE_FORWARD":   32768,   // RELAY_TEXT_ENVELOPE_MAX_BYTES
	"HEARTBEAT":       1228,    // QR_BOOTSTRAP_MAX_BYTES
	"ERROR":           1228,    // QR_BOOTSTRAP_MAX_BYTES
	"VOUCHER_TOKEN":   1228,    // QR_BOOTSTRAP_MAX_BYTES
}

func TestClassLimitsResolveUnitsCorrectly(t *testing.T) {
	r, _, in := loadWire(t)
	byName := map[string]payloadClassRule{}
	for _, rule := range in.payloadClasses {
		byName[rule.Name] = rule
	}
	for name, want := range expectedLimits {
		rule, ok := byName[name]
		if !ok {
			t.Errorf("class %s missing from payload schema", name)
			continue
		}
		got, err := resolveMaxPayload(r, rule)
		if err != nil {
			t.Errorf("%s: %v", name, err)
			continue
		}
		if got != want {
			t.Errorf("%s max payload = %d bytes, hand transcription says %d", name, got, want)
		}
	}
}

func TestEveryClassHasAPositiveLimit(t *testing.T) {
	r, m, in := loadWire(t)
	byName := map[string]payloadClassRule{}
	for _, rule := range in.payloadClasses {
		byName[rule.Name] = rule
	}
	for _, mem := range m.MessageClass {
		got, err := resolveMaxPayload(r, byName[mem.Name])
		if err != nil {
			t.Errorf("%s: %v", mem.Name, err)
			continue
		}
		if got <= 0 {
			t.Errorf("%s resolved to a non-positive payload bound %d", mem.Name, got)
		}
	}
}

func TestUnknownUnitIsRejected(t *testing.T) {
	if _, err := unitMultiplier("gb"); err == nil {
		t.Error("unknown unit 'gb' must fail generation, not guess a multiplier")
	}
}

func TestGeneratedWireFilesMatchCommitted(t *testing.T) {
	r, m, in := loadWire(t)
	files, err := EmitWireFiles(r, m, in)
	if err != nil {
		t.Fatalf("EmitWireFiles: %v", err)
	}
	root := repoRootPath(t)
	for _, f := range files {
		got, err := os.ReadFile(filepath.Join(root, filepath.FromSlash(f.Path)))
		if err != nil {
			t.Errorf("%s: %v (run: go run ./tools/constantsgen)", f.Path, err)
			continue
		}
		if string(got) != string(f.Content) {
			t.Errorf("%s is out of date (run: go run ./tools/constantsgen)", f.Path)
		}
	}
}

func TestGeneratedErrorCodesCoverRegistry(t *testing.T) {
	r, m, in := loadWire(t)
	files, err := EmitWireFiles(r, m, in)
	if err != nil {
		t.Fatalf("EmitWireFiles: %v", err)
	}
	var body string
	for _, f := range files {
		if f.Path == ErrorCodesGoOutPath {
			body = string(f.Content)
		}
	}
	if body == "" {
		t.Fatal("no error-code artefact emitted")
	}
	for _, c := range in.errorCodes {
		if !strings.Contains(body, `"`+c.Code+`"`) {
			t.Errorf("generated error codes missing %s", c.Code)
		}
	}
	if !strings.Contains(body, "func Known(") {
		t.Error("generated error codes missing Known()")
	}
}
