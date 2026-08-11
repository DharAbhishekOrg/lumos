// Lumos - tools/constantsgen / Master constants register: load, canonicalize, hash.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CONST-001, TRS-CONST-003, TRS-CONST-005, TRS-REP-002
// ADRs: ADR-002, ADR-004
// Threading: any
// Notes: JSON numbers are decoded with UseNumber so the register's exact decimal
// text survives into the canonical form. Decoding through float64 would make the
// constants hash depend on binary floating-point formatting, which is precisely
// the kind of cross-language drift TRS-CONST-005 exists to detect.
package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"sort"
	"strings"
)

// HashPrefix is the encoding marker required by the register's own algorithm
// description.
const HashPrefix = "sha256:"

// fixedExcludedKeys are documentation-only fields that must not influence
// constants_hash. Any key beginning with '$' is excluded in addition to these.
var fixedExcludedKeys = map[string]bool{
	"constants_hash":           true,
	"constants_hash_algorithm": true,
	"anchors":                  true,
	"source":                   true,
	"notes":                    true,
}

// Register is the parsed master constants file. The raw form is retained so the
// file can be rewritten with its formatting and key order intact.
type Register struct {
	raw    []byte
	Data   map[string]json.RawMessage
	Decode map[string]any
}

// LoadRegister reads and parses the master constants file at path.
func LoadRegister(path string) (*Register, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("read constants register: %w", err)
	}
	r := &Register{raw: raw}

	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber()
	if err := dec.Decode(&r.Decode); err != nil {
		return nil, fmt.Errorf("parse constants register: %w", err)
	}
	dec2 := json.NewDecoder(bytes.NewReader(raw))
	if err := dec2.Decode(&r.Data); err != nil {
		return nil, fmt.Errorf("parse constants register (raw): %w", err)
	}
	return r, nil
}

// Raw returns the original file bytes.
func (r *Register) Raw() []byte { return r.raw }

// String returns a top-level string field.
func (r *Register) String(key string) (string, error) {
	v, ok := r.Decode[key]
	if !ok {
		return "", fmt.Errorf("register has no %q", key)
	}
	s, ok := v.(string)
	if !ok {
		return "", fmt.Errorf("register field %q is not a string", key)
	}
	return s, nil
}

// excluded reports whether a key is documentation-only.
func excluded(key string) bool {
	return strings.HasPrefix(key, "$") || fixedExcludedKeys[key]
}

// CanonicalForm renders the hashable view of the register: one '<path>=<value>'
// line per hashable leaf, sorted bytewise by path, newline-joined, with a single
// trailing newline.
func (r *Register) CanonicalForm() (string, error) {
	var lines []string
	if err := flatten("", r.Decode, &lines); err != nil {
		return "", err
	}
	sort.Strings(lines)
	if len(lines) == 0 {
		return "", fmt.Errorf("register produced no hashable leaves")
	}
	return strings.Join(lines, "\n") + "\n", nil
}

// ConstantsHash computes the register's hash over its canonical form.
func (r *Register) ConstantsHash() (string, error) {
	canon, err := r.CanonicalForm()
	if err != nil {
		return "", err
	}
	sum := sha256.Sum256([]byte(canon))
	return HashPrefix + hex.EncodeToString(sum[:]), nil
}

// DeclaredHash returns the constants_hash currently recorded in the file.
func (r *Register) DeclaredHash() (string, error) {
	return r.String("constants_hash")
}

// WithHash returns the register bytes with constants_hash set to hash. Only that
// one field is rewritten, so the file's formatting is otherwise preserved.
func (r *Register) WithHash(hash string) ([]byte, error) {
	declared, err := r.DeclaredHash()
	if err != nil {
		return nil, err
	}
	old := fmt.Sprintf("%q: %q", "constants_hash", declared)
	replacement := fmt.Sprintf("%q: %q", "constants_hash", hash)
	if !bytes.Contains(r.raw, []byte(old)) {
		return nil, fmt.Errorf("could not locate the constants_hash field to rewrite")
	}
	if bytes.Count(r.raw, []byte(old)) != 1 {
		return nil, fmt.Errorf("constants_hash field is not unique in the register")
	}
	return bytes.Replace(r.raw, []byte(old), []byte(replacement), 1), nil
}

// flatten walks v, appending '<path>=<value>' lines for every hashable leaf.
func flatten(path string, v any, out *[]string) error {
	switch t := v.(type) {
	case map[string]any:
		keys := make([]string, 0, len(t))
		for k := range t {
			if excluded(k) {
				continue
			}
			keys = append(keys, k)
		}
		sort.Strings(keys)
		for _, k := range keys {
			if err := flatten(join(path, k), t[k], out); err != nil {
				return err
			}
		}
	case []any:
		if scalars, ok := renderScalarSlice(t); ok {
			*out = append(*out, path+"="+scalars)
			return nil
		}
		for i, elem := range t {
			if err := flatten(join(path, fmt.Sprintf("%d", i)), elem, out); err != nil {
				return err
			}
		}
	default:
		s, err := renderScalar(v)
		if err != nil {
			return fmt.Errorf("%s: %w", path, err)
		}
		*out = append(*out, path+"="+s)
	}
	return nil
}

func join(path, key string) string {
	if path == "" {
		return key
	}
	return path + "." + key
}

// renderScalarSlice renders an all-scalar array inline, reporting false if any
// element is a container.
func renderScalarSlice(items []any) (string, bool) {
	parts := make([]string, 0, len(items))
	for _, it := range items {
		switch it.(type) {
		case map[string]any, []any:
			return "", false
		}
		s, err := renderScalar(it)
		if err != nil {
			return "", false
		}
		parts = append(parts, s)
	}
	return "[" + strings.Join(parts, ",") + "]", true
}

// renderScalar renders one leaf value. Floating-point values are rejected
// outright: the register must stay integer-only so the canonical form needs no
// float-formatting rule.
func renderScalar(v any) (string, error) {
	switch t := v.(type) {
	case json.Number:
		s := t.String()
		if strings.ContainsAny(s, ".eE") {
			return "", fmt.Errorf("floating-point value %q is prohibited in the constants register", s)
		}
		return s, nil
	case string:
		return t, nil
	case bool:
		if t {
			return "true", nil
		}
		return "false", nil
	case nil:
		return "", fmt.Errorf("null is not a permitted constant value")
	default:
		return "", fmt.Errorf("unsupported value type %T", v)
	}
}
