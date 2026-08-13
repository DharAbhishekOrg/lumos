// Lumos - protocol/schema / Loader and invariant checks for the canonical protocol schemas.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-PROTO-001, TRS-PROTO-002, TRS-PROTO-005, TRS-PROTO-007, TRS-PROTO-013, TRS-PROTO-014, TRS-CICD-005
// ADRs: ADR-002, ADR-004
// Threading: any
// Notes: Byte offsets are DERIVED from field widths in declaration order rather
// than written down, so an offset cannot drift out of step with a width. Every
// constant the schemas depend on is referenced by register path and resolved at
// load time; a schema that hard-codes a number would defeat ADR-004, so the
// loader offers no way to express one.
package schema

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// Paths of the canonical schemas, relative to the repository root.
const (
	FrameHeaderPath    = "protocol/schemas/frame_header.schema.json"
	AdvV1Path          = "protocol/schemas/adv_v1.schema.json"
	MessagePayloadPath = "protocol/schemas/message_payloads.schema.json"
	ErrorCodesPath     = "protocol/schemas/error_codes.schema.json"
	PrekeyBundlePath   = "protocol/schemas/prekey_bundle.schema.json"
	ConfigPackagePath  = "config-schema/config_package.schema.json"
	// #nosec G101 -- a schema file path, not a credential. gosec's entropy rule
	// matches the word "Token" in the identifier; the value is the name of the
	// voucher-token JSON Schema and no secret exists in this repository.
	VoucherTokenPath   = "config-schema/voucher_token.schema.json"
	ConfigOverridePath = "config-schema/config_overrides.schema.json"
	RegisterPath       = "protocol/constants/lumos_constants.json"
)

// FrameField is one field of the canonical frame header.
type FrameField struct {
	Name         string `json:"name"`
	Type         string `json:"type"`
	Width        int    `json:"width"`
	ConstantRef  string `json:"constant_ref"`
	EnumRef      string `json:"enum_ref"`
	ErrorCode    string `json:"error_code"`
	RelayReadble bool   `json:"relay_readable"`
	Description  string `json:"description"`

	// Offset is derived at load time, never read from the file.
	Offset int `json:"-"`
}

// FrameHeader is the parsed frame-header layout.
type FrameHeader struct {
	SchemaID  string       `json:"schema_id"`
	ByteOrder string       `json:"byte_order"`
	Fields    []FrameField `json:"fields"`
	AAD       struct {
		Covers      string `json:"covers"`
		ConstantRef string `json:"constant_ref"`
	} `json:"aead_associated_data"`
}

// TotalWidth is the sum of every field width, i.e. the authenticated header size.
func (f FrameHeader) TotalWidth() int {
	total := 0
	for _, fld := range f.Fields {
		total += fld.Width
	}
	return total
}

// MessageClassRule is the per-class payload rule.
type MessageClassRule struct {
	Name          string `json:"name"`
	MaxPayloadRef string `json:"max_payload_ref"`
	Encrypted     bool   `json:"encrypted"`
	InnerEnvelope string `json:"inner_envelope"`
	OpaqueToRelay bool   `json:"opaque_to_relay"`
}

// MessagePayloads is the parsed per-class payload schema.
type MessagePayloads struct {
	SchemaID  string             `json:"schema_id"`
	Classes   []MessageClassRule `json:"classes"`
	TextEvent struct {
		KindEnumRef string `json:"kind_enum_ref"`
		Fields      []struct {
			Name          string   `json:"name"`
			WidthRef      string   `json:"width_ref"`
			RequiredFor   []string `json:"required_for"`
			ProhibitedFor []string `json:"prohibited_for"`
		} `json:"fields"`
	} `json:"text_event"`
}

// ErrorCode is one entry of the stable registry.
type ErrorCode struct {
	Code        string  `json:"code"`
	Numeric     int     `json:"numeric"`
	Category    string  `json:"category"`
	Meaning     string  `json:"meaning"`
	UserMessage *string `json:"user_message"`
}

// ErrorCodes is the parsed stable error registry.
type ErrorCodes struct {
	SchemaID string      `json:"schema_id"`
	Codes    []ErrorCode `json:"codes"`
}

// Set returns the codes keyed by symbolic name.
func (e ErrorCodes) Set() map[string]ErrorCode {
	out := make(map[string]ErrorCode, len(e.Codes))
	for _, c := range e.Codes {
		out[c.Code] = c
	}
	return out
}

// Register is the decoded constants register, used to resolve constant_ref paths.
type Register struct {
	data map[string]any
}

// LoadRegister reads the constants register rooted at repoRoot.
func LoadRegister(repoRoot string) (*Register, error) {
	raw, err := os.ReadFile(filepath.Join(repoRoot, filepath.FromSlash(RegisterPath)))
	if err != nil {
		return nil, fmt.Errorf("read register: %w", err)
	}
	dec := json.NewDecoder(strings.NewReader(string(raw)))
	dec.UseNumber()
	var m map[string]any
	if err := dec.Decode(&m); err != nil {
		return nil, fmt.Errorf("parse register: %w", err)
	}
	return &Register{data: m}, nil
}

// Int resolves a dotted register path to an integer.
func (r *Register) Int(dotted string) (int64, error) {
	v, err := r.lookup(dotted)
	if err != nil {
		return 0, err
	}
	n, ok := v.(json.Number)
	if !ok {
		return 0, fmt.Errorf("register path %q is not a number", dotted)
	}
	return n.Int64()
}

// Has reports whether a dotted register path resolves.
func (r *Register) Has(dotted string) bool {
	_, err := r.lookup(dotted)
	return err == nil
}

// String resolves a dotted register path to a string value.
func (r *Register) String(dotted string) (string, error) {
	v, err := r.lookup(dotted)
	if err != nil {
		return "", err
	}
	s, ok := v.(string)
	if !ok {
		return "", fmt.Errorf("register path %q is not a string", dotted)
	}
	return s, nil
}

func (r *Register) lookup(dotted string) (any, error) {
	var cur any = r.data
	for _, seg := range strings.Split(dotted, ".") {
		m, ok := cur.(map[string]any)
		if !ok {
			return nil, fmt.Errorf("register path %q: %q is not traversable", dotted, seg)
		}
		cur, ok = m[seg]
		if !ok {
			return nil, fmt.Errorf("register path %q: no member %q", dotted, seg)
		}
	}
	return cur, nil
}

// MessageClassNames returns the canonical class names from the register, which is
// the single source for them.
func (r *Register) MessageClassNames() ([]string, error) {
	v, err := r.lookup("message_classes.classes")
	if err != nil {
		return nil, err
	}
	arr, ok := v.([]any)
	if !ok {
		return nil, fmt.Errorf("message_classes.classes is not an array")
	}
	names := make([]string, 0, len(arr))
	for _, e := range arr {
		obj, ok := e.(map[string]any)
		if !ok {
			return nil, fmt.Errorf("message class entry is not an object")
		}
		name, _ := obj["name"].(string)
		if name == "" {
			return nil, fmt.Errorf("message class entry has no name")
		}
		names = append(names, name)
	}
	return names, nil
}

// LoadFrameHeader parses the frame-header layout and derives field offsets.
func LoadFrameHeader(repoRoot string) (*FrameHeader, error) {
	var fh FrameHeader
	if err := loadJSON(repoRoot, FrameHeaderPath, &fh); err != nil {
		return nil, err
	}
	offset := 0
	for i := range fh.Fields {
		if fh.Fields[i].Width <= 0 {
			return nil, fmt.Errorf("frame field %q has non-positive width %d", fh.Fields[i].Name, fh.Fields[i].Width)
		}
		fh.Fields[i].Offset = offset
		offset += fh.Fields[i].Width
	}
	return &fh, nil
}

// LoadMessagePayloads parses the per-class payload rules.
func LoadMessagePayloads(repoRoot string) (*MessagePayloads, error) {
	var mp MessagePayloads
	if err := loadJSON(repoRoot, MessagePayloadPath, &mp); err != nil {
		return nil, err
	}
	return &mp, nil
}

// LoadErrorCodes parses the stable error registry.
func LoadErrorCodes(repoRoot string) (*ErrorCodes, error) {
	var ec ErrorCodes
	if err := loadJSON(repoRoot, ErrorCodesPath, &ec); err != nil {
		return nil, err
	}
	return &ec, nil
}

// LoadRaw parses any schema file into a generic map, for schemas whose shape is
// JSON Schema rather than a Lumos-specific layout description.
func LoadRaw(repoRoot, path string) (map[string]any, error) {
	var m map[string]any
	if err := loadJSON(repoRoot, path, &m); err != nil {
		return nil, err
	}
	return m, nil
}

func loadJSON(repoRoot, rel string, into any) error {
	raw, err := os.ReadFile(filepath.Join(repoRoot, filepath.FromSlash(rel)))
	if err != nil {
		return fmt.Errorf("read %s: %w", rel, err)
	}
	if err := json.Unmarshal(raw, into); err != nil {
		return fmt.Errorf("parse %s: %w", rel, err)
	}
	return nil
}

// CollectConstantRefs walks any decoded schema and returns every register path it
// references through a *_ref key. This is what lets a test prove that no schema
// points at a constant that does not exist.
func CollectConstantRefs(v any) []string {
	var out []string
	collectRefs(v, &out)
	return out
}

func collectRefs(v any, out *[]string) {
	switch t := v.(type) {
	case map[string]any:
		for k, val := range t {
			if strings.HasSuffix(k, "_ref") {
				if s, ok := val.(string); ok && isRegisterPath(s) {
					*out = append(*out, s)
				}
				continue
			}
			collectRefs(val, out)
		}
	case []any:
		for _, e := range t {
			collectRefs(e, out)
		}
	}
}

// isRegisterPath filters out the handful of *_ref values that name a policy
// rather than a register location.
func isRegisterPath(s string) bool {
	switch s {
	case "per_message_class", "entire_header", "service_uuid":
		return false
	}
	return strings.Contains(s, ".")
}
