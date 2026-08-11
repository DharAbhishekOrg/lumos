// Lumos - tools/constantsgen / Emission model: constant extraction and naming.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CONST-001, TRS-CONST-002, TRS-REP-002
// ADRs: ADR-004
// Threading: any
// Notes: One register path yields three identifiers, each idiomatic to its
// language, because a single shared spelling would raise analyzer warnings in at
// least two of them and TRS-CODE-006 requires zero warnings. Metadata fields
// (overridable, min, max) are emitted alongside values because TRS-CONST-002
// requires clients to reject out-of-bounds signed-config overrides, which they
// cannot do without the bounds in generated code.
package main

import (
	"encoding/json"
	"fmt"
	"sort"
	"strings"
)

// GroupOrder fixes the emission order of the constant groups. Message classes
// are emitted separately as typed enums.
var GroupOrder = []string{
	"identity_discovery",
	"sessions_remote_text",
	"mesh_store_forward",
	"transport",
	"media",
	"config_venue_ops",
	"resource_budgets",
}

// ValueKind discriminates the emitted literal forms.
type ValueKind int

const (
	KindInt ValueKind = iota
	KindBool
	KindString
	KindIntSlice
)

// Constant is one emitted value with its per-language identifiers.
type Constant struct {
	Group     string
	Base      string // SCREAMING_SNAKE, the Java identifier
	Kind      ValueKind
	Int       int64
	Bool      bool
	Str       string
	Ints      []int64
	SourcePth string // dotted register path, for the doc comment
}

// GoName returns the exported Go identifier.
func (c Constant) GoName() string { return upperCamel(c.Base) }

// SwiftName returns the lowerCamelCase Swift identifier.
func (c Constant) SwiftName() string { return lowerCamel(c.Base) }

// EnumMember is one message class or text event kind.
type EnumMember struct {
	Code    int64
	Name    string
	Purpose string
}

// Model is everything the emitters need.
type Model struct {
	ServiceUUID   string
	ConstantsHash string
	SchemaVersion int64
	Baseline      string
	Constants     []Constant
	MessageClass  []EnumMember
	TextEventKind []EnumMember
}

// BuildModel extracts the emission model from the register.
func BuildModel(r *Register, hash string) (*Model, error) {
	m := &Model{ConstantsHash: hash}

	var err error
	if m.ServiceUUID, err = r.String("service_uuid"); err != nil {
		return nil, err
	}
	if m.Baseline, err = r.String("baseline"); err != nil {
		return nil, err
	}
	sv, ok := r.Decode["schema_version"].(json.Number)
	if !ok {
		return nil, fmt.Errorf("register schema_version is missing or not a number")
	}
	if m.SchemaVersion, err = sv.Int64(); err != nil {
		return nil, fmt.Errorf("register schema_version: %w", err)
	}

	for _, group := range GroupOrder {
		raw, ok := r.Decode[group]
		if !ok {
			return nil, fmt.Errorf("register is missing group %q", group)
		}
		entries, ok := raw.(map[string]any)
		if !ok {
			return nil, fmt.Errorf("register group %q is not an object", group)
		}
		names := make([]string, 0, len(entries))
		for name := range entries {
			if excluded(name) {
				continue
			}
			names = append(names, name)
		}
		sort.Strings(names)

		for _, name := range names {
			fields, ok := entries[name].(map[string]any)
			if !ok {
				return nil, fmt.Errorf("register entry %s.%s is not an object", group, name)
			}
			fieldNames := make([]string, 0, len(fields))
			for f := range fields {
				if excluded(f) {
					continue
				}
				fieldNames = append(fieldNames, f)
			}
			sort.Strings(fieldNames)

			for _, f := range fieldNames {
				c, err := newConstant(group, name, f, fields[f])
				if err != nil {
					return nil, fmt.Errorf("%s.%s.%s: %w", group, name, f, err)
				}
				m.Constants = append(m.Constants, c)
			}
		}
	}

	if m.MessageClass, err = enumMembers(r, "classes"); err != nil {
		return nil, err
	}
	if m.TextEventKind, err = enumMembers(r, "text_event_kinds"); err != nil {
		return nil, err
	}

	if err := assertUniqueIdentifiers(m.Constants); err != nil {
		return nil, err
	}
	return m, nil
}

func newConstant(group, name, field string, v any) (Constant, error) {
	c := Constant{
		Group:     group,
		Base:      strings.ToUpper(name + "_" + field),
		SourcePth: group + "." + name + "." + field,
	}
	switch t := v.(type) {
	case json.Number:
		n, err := t.Int64()
		if err != nil {
			return c, fmt.Errorf("non-integer value %q", t.String())
		}
		c.Kind, c.Int = KindInt, n
	case bool:
		c.Kind, c.Bool = KindBool, t
	case string:
		c.Kind, c.Str = KindString, t
	case []any:
		c.Kind = KindIntSlice
		for _, e := range t {
			n, ok := e.(json.Number)
			if !ok {
				return c, fmt.Errorf("array elements must be integers")
			}
			iv, err := n.Int64()
			if err != nil {
				return c, fmt.Errorf("array element %q is not an integer", n.String())
			}
			c.Ints = append(c.Ints, iv)
		}
	default:
		return c, fmt.Errorf("unsupported type %T", v)
	}
	return c, nil
}

func enumMembers(r *Register, key string) ([]EnumMember, error) {
	mc, ok := r.Decode["message_classes"].(map[string]any)
	if !ok {
		return nil, fmt.Errorf("register is missing message_classes")
	}
	raw, ok := mc[key].([]any)
	if !ok {
		return nil, fmt.Errorf("message_classes.%s is missing or not an array", key)
	}
	out := make([]EnumMember, 0, len(raw))
	seenCode := map[int64]bool{}
	seenName := map[string]bool{}
	for i, e := range raw {
		obj, ok := e.(map[string]any)
		if !ok {
			return nil, fmt.Errorf("message_classes.%s[%d] is not an object", key, i)
		}
		num, ok := obj["code"].(json.Number)
		if !ok {
			return nil, fmt.Errorf("message_classes.%s[%d] has no numeric code", key, i)
		}
		code, err := num.Int64()
		if err != nil {
			return nil, fmt.Errorf("message_classes.%s[%d] code: %w", key, i, err)
		}
		name, _ := obj["name"].(string)
		purpose, _ := obj["purpose"].(string)
		if name == "" {
			return nil, fmt.Errorf("message_classes.%s[%d] has no name", key, i)
		}
		if code < 0 || code > 255 {
			return nil, fmt.Errorf("%s %s: code %d does not fit in a u8", key, name, code)
		}
		if seenCode[code] {
			return nil, fmt.Errorf("%s: duplicate code %d", key, code)
		}
		if seenName[name] {
			return nil, fmt.Errorf("%s: duplicate name %s", key, name)
		}
		seenCode[code], seenName[name] = true, true
		out = append(out, EnumMember{Code: code, Name: name, Purpose: purpose})
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("message_classes.%s is empty", key)
	}
	return out, nil
}

// assertUniqueIdentifiers guards against two register paths colliding after
// name mangling, which would emit code that does not compile.
func assertUniqueIdentifiers(cs []Constant) error {
	for _, lang := range []struct {
		name string
		fn   func(Constant) string
	}{
		{"java", func(c Constant) string { return c.Base }},
		{"go", Constant.GoName},
		{"swift", Constant.SwiftName},
	} {
		seen := map[string]string{}
		for _, c := range cs {
			id := lang.fn(c)
			if prev, dup := seen[id]; dup {
				return fmt.Errorf("%s identifier %q collides: %s and %s", lang.name, id, prev, c.SourcePth)
			}
			seen[id] = c.SourcePth
		}
	}
	return nil
}

// acronyms stay fully capitalised in Go identifiers and fully lower-cased when
// they lead a Swift identifier.
var acronyms = map[string]bool{
	"EID": true, "RSSI": true, "DBM": true, "BLE": true, "LAN": true,
	"GATT": true, "MTU": true, "TTL": true, "QR": true, "UUID": true,
	"ID": true, "IDS": true, "IOS": true, "RAM": true, "AAC": true,
	"KB": true, "MB": true, "MS": true, "HZ": true, "PCT": true,
}

func upperCamel(screamingSnake string) string {
	var b strings.Builder
	for _, w := range strings.Split(screamingSnake, "_") {
		if w == "" {
			continue
		}
		if acronyms[w] {
			b.WriteString(w)
			continue
		}
		b.WriteString(w[:1] + strings.ToLower(w[1:]))
	}
	return b.String()
}

func lowerCamel(screamingSnake string) string {
	words := strings.Split(screamingSnake, "_")
	var b strings.Builder
	for i, w := range words {
		if w == "" {
			continue
		}
		switch {
		case i == 0 && acronyms[w]:
			b.WriteString(strings.ToLower(w))
		case i == 0:
			b.WriteString(strings.ToLower(w))
		case acronyms[w]:
			b.WriteString(w)
		default:
			b.WriteString(w[:1] + strings.ToLower(w[1:]))
		}
	}
	return b.String()
}

// pascalFromEnum converts HELLO_ACK to HelloAck for Swift case names.
func pascalFromEnum(name string) string {
	var b strings.Builder
	for _, w := range strings.Split(name, "_") {
		if w == "" {
			continue
		}
		b.WriteString(w[:1] + strings.ToLower(w[1:]))
	}
	return b.String()
}

// swiftCase converts HELLO_ACK to helloAck.
func swiftCase(name string) string {
	p := pascalFromEnum(name)
	if p == "" {
		return p
	}
	return strings.ToLower(p[:1]) + p[1:]
}
