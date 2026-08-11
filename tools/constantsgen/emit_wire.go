// Lumos - tools/constantsgen / Generates Go error codes and per-class payload limits.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-PROTO-007, TRS-PROTO-013, TRS-CONST-001, TRS-CODE-005
// ADRs: ADR-004
// Threading: any
// Notes: The wire codec needs the stable error codes and the class payload maxima
// as Go symbols. Hand-writing either would duplicate a registry that already
// exists in schema form, so both are generated: error codes from
// error_codes.schema.json, class limits by resolving each max_payload_ref through
// the constants register with its declared unit. A ref that does not resolve, or
// a class without a rule, is a generation failure - not a silent zero.
package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// Wire-generation inputs and outputs, relative to the repository root.
const (
	MessagePayloadsSchemaPath = "protocol/schemas/message_payloads.schema.json"
	ErrorCodesSchemaPath      = "protocol/schemas/error_codes.schema.json"
	ClassLimitsOutPath        = "protocol/gen/go/lumosconstants/class_limits.go"
	ErrorCodesGoOutPath       = "protocol/gen/go/lumoserrors/error_codes.go"
)

// wireInputs are the parsed schema files the wire emitters consume.
type wireInputs struct {
	payloadClasses []payloadClassRule
	errorCodes     []errorCodeEntry
}

type payloadClassRule struct {
	Name          string `json:"name"`
	MaxPayloadRef string `json:"max_payload_ref"`
	Unit          string `json:"unit"`
}

type errorCodeEntry struct {
	Code     string `json:"code"`
	Numeric  int64  `json:"numeric"`
	Category string `json:"category"`
	Meaning  string `json:"meaning"`
}

// LoadWireInputs reads and parses both schema files.
func LoadWireInputs(root string) (*wireInputs, error) {
	var payloads struct {
		Classes []payloadClassRule `json:"classes"`
	}
	if err := readJSON(filepath.Join(root, filepath.FromSlash(MessagePayloadsSchemaPath)), &payloads); err != nil {
		return nil, err
	}
	var registry struct {
		Codes []errorCodeEntry `json:"codes"`
	}
	if err := readJSON(filepath.Join(root, filepath.FromSlash(ErrorCodesSchemaPath)), &registry); err != nil {
		return nil, err
	}
	if len(payloads.Classes) == 0 || len(registry.Codes) == 0 {
		return nil, fmt.Errorf("wire inputs are empty: %d classes, %d error codes", len(payloads.Classes), len(registry.Codes))
	}
	return &wireInputs{payloadClasses: payloads.Classes, errorCodes: registry.Codes}, nil
}

func readJSON(path string, into any) error {
	raw, err := os.ReadFile(path)
	if err != nil {
		return fmt.Errorf("read %s: %w", path, err)
	}
	if err := json.Unmarshal(raw, into); err != nil {
		return fmt.Errorf("parse %s: %w", path, err)
	}
	return nil
}

// unitMultiplier maps a payload-rule unit to bytes. An unknown unit is an error,
// not a guess.
func unitMultiplier(unit string) (int64, error) {
	switch unit {
	case "", "bytes":
		return 1, nil
	case "kb":
		return 1024, nil
	case "mb":
		return 1024 * 1024, nil
	}
	return 0, fmt.Errorf("unknown payload unit %q", unit)
}

// resolveMaxPayload resolves one class rule to a byte count through the register.
func resolveMaxPayload(r *Register, rule payloadClassRule) (int64, error) {
	path := rule.MaxPayloadRef
	raw, err := registerLeaf(r, path)
	if err != nil {
		raw, err = registerLeaf(r, path+".default")
		if err != nil {
			return 0, fmt.Errorf("class %s: max_payload_ref %q does not resolve", rule.Name, rule.MaxPayloadRef)
		}
	}
	mult, err := unitMultiplier(rule.Unit)
	if err != nil {
		return 0, fmt.Errorf("class %s: %w", rule.Name, err)
	}
	return raw * mult, nil
}

func registerLeaf(r *Register, dotted string) (int64, error) {
	var cur any = r.Decode
	for _, seg := range strings.Split(dotted, ".") {
		m, ok := cur.(map[string]any)
		if !ok {
			return 0, fmt.Errorf("%q not traversable", seg)
		}
		cur, ok = m[seg]
		if !ok {
			return 0, fmt.Errorf("no member %q", seg)
		}
	}
	n, ok := cur.(json.Number)
	if !ok {
		return 0, fmt.Errorf("%q is not a number", dotted)
	}
	return n.Int64()
}

// EmitWireFiles produces the generated class-limits and error-code artefacts.
func EmitWireFiles(r *Register, m *Model, in *wireInputs) ([]File, error) {
	limits, err := gofmtSource(ClassLimitsOutPath, emitClassLimits(r, m, in))
	if err != nil {
		return nil, err
	}
	codes, err := gofmtSource(ErrorCodesGoOutPath, emitErrorCodesGo(m, in))
	if err != nil {
		return nil, err
	}
	return []File{
		{ClassLimitsOutPath, limits},
		{ErrorCodesGoOutPath, codes},
	}, nil
}

func emitClassLimits(r *Register, m *Model, in *wireInputs) string {
	// Emission order follows the register's class order for a stable, readable file.
	ruleByName := map[string]payloadClassRule{}
	for _, rule := range in.payloadClasses {
		ruleByName[rule.Name] = rule
	}

	var b strings.Builder
	b.WriteString(header("//", "protocol/gen/go/lumosconstants", "Generated per-class maximum payload sizes."))
	b.WriteString(provenance(m, "//"))
	b.WriteString("// Source schema: " + MessagePayloadsSchemaPath + "\n")
	b.WriteString("\npackage lumosconstants\n\n")
	b.WriteString("// messageClassMaxPayload resolves each class's max_payload_ref through the\n")
	b.WriteString("// constants register (TRS-PROTO-007). Values are bytes.\n")
	b.WriteString("var messageClassMaxPayload = map[MessageClass]int64{\n")
	for _, mem := range m.MessageClass {
		rule, ok := ruleByName[mem.Name]
		if !ok {
			// BuildWireModel validation guarantees coverage; keep the emitter total anyway.
			continue
		}
		bytes, err := resolveMaxPayload(r, rule)
		if err != nil {
			// Unreachable after validation; emit nothing rather than a wrong number.
			continue
		}
		b.WriteString(fmt.Sprintf("\tMessageClass%s: %d, // %s\n", upperCamel(mem.Name), bytes, rule.MaxPayloadRef))
	}
	b.WriteString("}\n\n")
	b.WriteString("// MaxPayloadBytes returns the maximum ciphertext payload for a message class.\n")
	b.WriteString("// The second result is false for a class this protocol version does not define;\n")
	b.WriteString("// callers apply TRS-PROTO-006 handling rather than assuming a bound.\n")
	b.WriteString("func MaxPayloadBytes(mc MessageClass) (int64, bool) {\n")
	b.WriteString("\tmax, ok := messageClassMaxPayload[mc]\n\treturn max, ok\n}\n")
	return b.String()
}

func emitErrorCodesGo(m *Model, in *wireInputs) string {
	var b strings.Builder
	b.WriteString(header("//", "protocol/gen/go/lumoserrors", "Generated stable protocol error codes."))
	b.WriteString(provenance(m, "//"))
	b.WriteString("// Source schema: " + ErrorCodesSchemaPath + "\n")
	b.WriteString("\n// Package lumoserrors holds the stable machine-readable error codes of\n")
	b.WriteString("// TRS-PROTO-013. Codes are append-only; renumbering is a breaking change.\n")
	b.WriteString("package lumoserrors\n\n")

	b.WriteString("const (\n")
	for _, c := range in.errorCodes {
		b.WriteString(fmt.Sprintf("\t// %s (%d, %s): %s\n", c.Code, c.Numeric, c.Category, c.Meaning))
		b.WriteString(fmt.Sprintf("\t%s = %q\n", upperCamel(c.Code), c.Code))
	}
	b.WriteString(")\n\n")

	b.WriteString("// Numeric maps each stable code to its registry number.\n")
	b.WriteString("var Numeric = map[string]int{\n")
	for _, c := range in.errorCodes {
		b.WriteString(fmt.Sprintf("\t%s: %d,\n", upperCamel(c.Code), c.Numeric))
	}
	b.WriteString("}\n\n")

	b.WriteString("// Known reports whether code is in the stable registry.\n")
	b.WriteString("func Known(code string) bool {\n\t_, ok := Numeric[code]\n\treturn ok\n}\n")
	return b.String()
}

// ValidateWireCoverage fails generation when a registered class has no payload
// rule or a rule's ref does not resolve - the failure modes that must never
// become a silent zero-byte bound.
func ValidateWireCoverage(r *Register, m *Model, in *wireInputs) error {
	ruleByName := map[string]payloadClassRule{}
	for _, rule := range in.payloadClasses {
		if _, dup := ruleByName[rule.Name]; dup {
			return fmt.Errorf("payload schema defines class %s twice", rule.Name)
		}
		ruleByName[rule.Name] = rule
	}
	for _, mem := range m.MessageClass {
		rule, ok := ruleByName[mem.Name]
		if !ok {
			return fmt.Errorf("class %s has no payload rule (TRS-PROTO-007)", mem.Name)
		}
		if _, err := resolveMaxPayload(r, rule); err != nil {
			return err
		}
	}
	seen := map[string]bool{}
	seenNum := map[int64]string{}
	for _, c := range in.errorCodes {
		if seen[c.Code] {
			return fmt.Errorf("error code %s duplicated", c.Code)
		}
		seen[c.Code] = true
		if prev, dup := seenNum[c.Numeric]; dup {
			return fmt.Errorf("error numeric %d used by %s and %s", c.Numeric, prev, c.Code)
		}
		seenNum[c.Numeric] = c.Code
	}
	return nil
}
