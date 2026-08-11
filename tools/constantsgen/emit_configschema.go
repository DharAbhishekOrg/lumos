// Lumos - tools/constantsgen / Derives the signed-config override schema from the register.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CONST-002, TRS-CONST-005, TRS-CFG-001, TRS-CICD-005
// ADRs: ADR-004, ADR-006
// Threading: any
// Notes: ADR-004 requires the config schema's min/max validation to be derived
// mechanically from the register rather than maintained beside it. Only entries
// with overridable=true appear; everything else is unlisted, and the schema sets
// additionalProperties=false so an override naming a frozen constant is rejected
// rather than silently ignored. Fail closed is the required posture (SRS 2.4).
package main

import (
	"encoding/json"
	"fmt"
	"sort"
	"strings"
)

// ConfigSchemaOutPath is the generated override schema, relative to the repo root.
const ConfigSchemaOutPath = "config-schema/config_overrides.schema.json"

// jsonSchemaDraft is the dialect the generated schema declares.
const jsonSchemaDraft = "https://json-schema.org/draft/2020-12/schema"

// overrideProperty is one validatable override target.
type overrideProperty struct {
	Type        string `json:"type"`
	Minimum     *int64 `json:"minimum,omitempty"`
	Maximum     *int64 `json:"maximum,omitempty"`
	Description string `json:"description"`
}

// EmitConfigOverrideSchema builds the JSON Schema that a signed configuration
// package's "overrides" object must satisfy.
func EmitConfigOverrideSchema(r *Register, m *Model) ([]byte, error) {
	props := map[string]any{}
	var covered, unbounded []string
	crossField := []map[string]any{}

	for _, group := range GroupOrder {
		entries, ok := r.Decode[group].(map[string]any)
		if !ok {
			return nil, fmt.Errorf("register group %q is not an object", group)
		}
		names := make([]string, 0, len(entries))
		for name := range entries {
			if !excluded(name) {
				names = append(names, name)
			}
		}
		sort.Strings(names)

		for _, name := range names {
			fields, ok := entries[name].(map[string]any)
			if !ok {
				return nil, fmt.Errorf("register entry %s.%s is not an object", group, name)
			}
			if ovr, _ := fields["overridable"].(bool); !ovr {
				continue
			}
			entryProps, entryUnbounded, err := overridePropsFor(group, name, fields)
			if err != nil {
				return nil, err
			}
			for k, v := range entryProps {
				props[k] = v
			}
			covered = append(covered, name)
			unbounded = append(unbounded, entryUnbounded...)

			if c, ok := dutyCycleConstraint(name, fields); ok {
				crossField = append(crossField, c)
			}
		}
	}

	sort.Strings(covered)
	sort.Strings(unbounded)

	schema := map[string]any{
		"$schema": jsonSchemaDraft,
		"$id":     "https://lumos.invalid/schemas/config_overrides.schema.json",
		"title":   "Lumos signed-configuration overrides",
		"$comment": "GENERATED - DO NOT EDIT. Generator: " + generatorName +
			". Derived mechanically from protocol/constants/lumos_constants.json per ADR-004. " +
			"Every bound below comes from the register; editing this file by hand would " +
			"reintroduce the duplicated-constant defect ADR-004 exists to prevent.",
		"x-constants-hash":     m.ConstantsHash,
		"x-register-baseline":  m.Baseline,
		"x-overridable-count":  len(covered),
		"x-overridable-names":  covered,
		"type":                 "object",
		"additionalProperties": false,
		"properties":           props,
	}
	if len(crossField) > 0 {
		schema["x-cross-field-constraints"] = crossField
	}
	if len(unbounded) > 0 {
		// Defensive: ADR-006 closed every gap, so this must stay empty. If a future
		// register entry loses its bounds, the schema says so out loud instead of
		// emitting an unbounded - and therefore unenforceable - property.
		schema["x-unbounded-properties"] = unbounded
	}

	out, err := json.MarshalIndent(schema, "", " ")
	if err != nil {
		return nil, fmt.Errorf("marshal config override schema: %w", err)
	}
	return append(out, '\n'), nil
}

// overridePropsFor maps one register entry to its validatable properties.
func overridePropsFor(group, name string, fields map[string]any) (map[string]any, []string, error) {
	props := map[string]any{}
	var unbounded []string

	fieldNames := make([]string, 0, len(fields))
	for f := range fields {
		fieldNames = append(fieldNames, f)
	}
	sort.Strings(fieldNames)

	allowed := narrowedFields(fields)

	for _, f := range fieldNames {
		if excluded(f) || metaFieldNames[f] || isBoundFieldName(f) {
			continue
		}
		if allowed != nil && !allowed[f] {
			continue // entry narrows overridability to specific fields
		}
		value, numeric := numberField(fields, f)
		if !numeric {
			continue // codec identity and similar are not signed-config tunables
		}

		key := name
		if f != "default" {
			key = name + "." + f
		}
		lo, hi, bounded := boundsFor(fields, f)
		desc := fmt.Sprintf("%s.%s.%s (register default %d)", group, name, f, value)

		p := overrideProperty{Type: "integer", Description: desc}
		if bounded {
			p.Minimum, p.Maximum = &lo, &hi
		} else {
			unbounded = append(unbounded, key)
		}
		props[key] = p
	}
	return props, unbounded, nil
}

// boundsFor resolves the range constraining field f, matching the register's two
// encodings: default/min/max, and <field>_min/<field>_max for multi-field entries.
func boundsFor(fields map[string]any, f string) (lo, hi int64, ok bool) {
	loKey, hiKey := f+"_min", f+"_max"
	if f == "default" {
		loKey, hiKey = "min", "max"
	}
	if f == "max_sec" {
		loKey, hiKey = "sec_min", "sec_max" // TRS 23.5 names the duration bounds this way
	}
	lo, okLo := numberField(fields, loKey)
	hi, okHi := numberField(fields, hiKey)
	return lo, hi, okLo && okHi
}

func numberField(fields map[string]any, key string) (int64, bool) {
	n, ok := fields[key].(json.Number)
	if !ok {
		return 0, false
	}
	v, err := n.Int64()
	if err != nil {
		return 0, false
	}
	return v, true
}

// narrowedFields returns the field allow-list when an entry restricts its
// entry-level overridable flag to specific fields, or nil when all value fields
// are overridable. TRS Sec. 23.5 fixes the voice-note codec profile while leaving
// its duration tunable, so entry-level granularity is not always sufficient.
func narrowedFields(fields map[string]any) map[string]bool {
	raw, ok := fields["overridable_fields"].([]any)
	if !ok {
		return nil
	}
	allowed := map[string]bool{}
	for _, v := range raw {
		if s, ok := v.(string); ok {
			allowed[s] = true
		}
	}
	return allowed
}

// dutyCycleConstraint describes a scan window's duty-cycle bound. JSON Schema
// 2020-12 cannot express a relationship between two properties, so the rule is
// emitted as machine-readable metadata and MUST be enforced by the config client
// before activation. Schema validation alone is not sufficient for these two.
func dutyCycleConstraint(name string, fields map[string]any) (map[string]any, bool) {
	on, okOn := numberField(fields, "on")
	off, okOff := numberField(fields, "off")
	if !okOn || !okOff {
		return nil, false
	}
	hi, okHi := numberField(fields, "duty_max_pct")
	if !okHi {
		return nil, false
	}
	c := map[string]any{
		"constant":     name,
		"rule":         "on / (on + off) * 100 must fall within the stated duty-cycle range",
		"properties":   []string{name + ".on", name + ".off"},
		"duty_max_pct": hi,
		// lumoslint:allow-literal - 100 is the percentage denominator, not a constant.
		"register_default_duty_pct": on * 100 / (on + off),
		"enforced_by":               "config-client (WP-08). JSON Schema cannot express a cross-property relationship; an override passing schema validation is NOT yet proven in-bounds.",
	}
	if lo, okLo := numberField(fields, "duty_min_pct"); okLo {
		c["duty_min_pct"] = lo
	}
	return c, true
}

// metaFieldNames describe a constant rather than carrying a tunable value.
var metaFieldNames = map[string]bool{
	"overridable": true, "calibrated": true, "budget": true,
	"test_only": true, "jitter": true, "overridable_fields": true,
}

// isBoundFieldName reports whether a field states a bound rather than a value.
func isBoundFieldName(name string) bool {
	switch name {
	case "min", "max", "duty_min_pct", "duty_max_pct", "sec_min", "sec_max":
		return true
	}
	return strings.HasSuffix(name, "_min") || strings.HasSuffix(name, "_max")
}
