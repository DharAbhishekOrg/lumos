// Lumos - protocol/schema / Tests that the schemas match TRS Sec. 9 and the register.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-PROTO-001, TRS-PROTO-002, TRS-PROTO-005, TRS-PROTO-007, TRS-PROTO-013, TRS-PROTO-014, TRS-CICD-005
// ADRs: ADR-002, ADR-004
// Threading: any
// Notes: The expected frame layout and message-class list are transcribed by hand
// from TRS Sec. 9.1/9.2 rather than read back out of the schema files, for the
// same reason the constants tests are: a test that derives its expectations from
// the artefact under test proves only that the artefact equals itself.
package schema

import (
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"sort"
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

// trsFrameFields is TRS Sec. 9.1, transcribed by hand: name, type, width.
var trsFrameFields = []struct {
	name  string
	typ   string
	width int
}{
	{"magic", "u16", 2},
	{"protocol_version", "u8", 1},
	{"message_type", "u8", 1},
	{"flags", "u16", 2},
	{"header_length", "u16", 2},
	{"session_id", "bytes", 16},
	{"message_id", "bytes", 16},
	{"sequence", "u32", 4},
	{"ack_base", "u32", 4},
	{"payload_length", "u32", 4},
	{"expiry_seconds", "u32", 4},
	{"hop_limit", "u8", 1},
	{"reserved", "u8", 1},
}

func TestFrameHeaderMatchesTRS91(t *testing.T) {
	fh, err := LoadFrameHeader(repoRoot(t))
	if err != nil {
		t.Fatalf("LoadFrameHeader: %v", err)
	}
	if fh.ByteOrder != "big_endian" {
		t.Errorf("byte_order = %q, TRS-PROTO-001 requires network byte order", fh.ByteOrder)
	}
	if len(fh.Fields) != len(trsFrameFields) {
		t.Fatalf("frame has %d fields, TRS Sec. 9.1 defines %d", len(fh.Fields), len(trsFrameFields))
	}
	offset := 0
	for i, want := range trsFrameFields {
		got := fh.Fields[i]
		if got.Name != want.name || got.Type != want.typ || got.Width != want.width {
			t.Errorf("field[%d] = %s/%s/%d, TRS Sec. 9.1 says %s/%s/%d",
				i, got.Name, got.Type, got.Width, want.name, want.typ, want.width)
		}
		if got.Offset != offset {
			t.Errorf("field %s offset = %d, derived order gives %d", got.Name, got.Offset, offset)
		}
		offset += want.width
	}
}

// TestFrameHeaderWidthMatchesRegister is the cross-check that keeps the schema and
// the generated constant from drifting apart.
func TestFrameHeaderWidthMatchesRegister(t *testing.T) {
	root := repoRoot(t)
	fh, err := LoadFrameHeader(root)
	if err != nil {
		t.Fatalf("LoadFrameHeader: %v", err)
	}
	reg, err := LoadRegister(root)
	if err != nil {
		t.Fatalf("LoadRegister: %v", err)
	}
	declared, err := reg.Int("framing.FRAME_HEADER_LEN_BYTES.default")
	if err != nil {
		t.Fatalf("register lookup: %v", err)
	}
	if int64(fh.TotalWidth()) != declared {
		t.Errorf("frame widths sum to %d but framing.FRAME_HEADER_LEN_BYTES is %d", fh.TotalWidth(), declared)
	}
	if fh.AAD.Covers != "entire_header" {
		t.Errorf("AAD covers %q; TRS-PROTO-003 requires the complete immutable header", fh.AAD.Covers)
	}
}

// TestEveryConstantRefResolves proves no schema points at a constant that does
// not exist, which is what stops a schema from silently carrying a stale path.
func TestEveryConstantRefResolves(t *testing.T) {
	root := repoRoot(t)
	reg, err := LoadRegister(root)
	if err != nil {
		t.Fatalf("LoadRegister: %v", err)
	}
	paths := []string{FrameHeaderPath, AdvV1Path, MessagePayloadPath}
	seen := 0
	for _, p := range paths {
		raw, err := LoadRaw(root, p)
		if err != nil {
			t.Errorf("%s: %v", p, err)
			continue
		}
		refs := CollectConstantRefs(raw)
		if len(refs) == 0 {
			t.Errorf("%s references no register constants; a schema with hard-coded numbers defeats ADR-004", p)
		}
		for _, ref := range refs {
			seen++
			if !reg.Has(ref) && !reg.Has(ref+".default") {
				t.Errorf("%s references register path %q, which does not resolve", p, ref)
			}
		}
	}
	if seen < 15 {
		t.Errorf("only %d constant references found across the schemas; expected the layouts to be fully register-driven", seen)
	}
}

// TestMessagePayloadsCoverEveryRegisteredClass enforces that adding a message
// class forces a payload-bound decision (TRS-PROTO-007).
func TestMessagePayloadsCoverEveryRegisteredClass(t *testing.T) {
	root := repoRoot(t)
	reg, err := LoadRegister(root)
	if err != nil {
		t.Fatalf("LoadRegister: %v", err)
	}
	registered, err := reg.MessageClassNames()
	if err != nil {
		t.Fatalf("MessageClassNames: %v", err)
	}
	mp, err := LoadMessagePayloads(root)
	if err != nil {
		t.Fatalf("LoadMessagePayloads: %v", err)
	}

	inSchema := map[string]MessageClassRule{}
	for _, c := range mp.Classes {
		if _, dup := inSchema[c.Name]; dup {
			t.Errorf("message class %s appears twice in the payload schema", c.Name)
		}
		inSchema[c.Name] = c
	}

	for _, name := range registered {
		rule, ok := inSchema[name]
		if !ok {
			t.Errorf("class %s is in the register but has no payload rule (TRS-PROTO-007)", name)
			continue
		}
		if rule.MaxPayloadRef == "" {
			t.Errorf("class %s declares no max_payload_ref", name)
			continue
		}
		if !reg.Has(rule.MaxPayloadRef) && !reg.Has(rule.MaxPayloadRef+".default") {
			t.Errorf("class %s max_payload_ref %q does not resolve in the register", name, rule.MaxPayloadRef)
		}
	}
	regSet := map[string]bool{}
	for _, n := range registered {
		regSet[n] = true
	}
	for name := range inSchema {
		if !regSet[name] {
			t.Errorf("payload schema defines class %s, which is not in the register", name)
		}
	}
	if len(registered) != 22 {
		t.Errorf("register holds %d message classes; TRS Sec. 9.2 defines 22", len(registered))
	}
}

// TestTextEventEnvelopeMatchesAMD005 pins the 0x10 inner envelope.
func TestTextEventEnvelopeMatchesAMD005(t *testing.T) {
	root := repoRoot(t)
	mp, err := LoadMessagePayloads(root)
	if err != nil {
		t.Fatalf("LoadMessagePayloads: %v", err)
	}

	var text *MessageClassRule
	for i := range mp.Classes {
		if mp.Classes[i].Name == "TEXT_MESSAGE" {
			text = &mp.Classes[i]
		}
	}
	if text == nil {
		t.Fatal("TEXT_MESSAGE has no payload rule")
	}
	if text.InnerEnvelope != "text_event" {
		t.Errorf("TEXT_MESSAGE inner_envelope = %q, AMD-005 requires the inner event envelope", text.InnerEnvelope)
	}
	if !text.Encrypted {
		t.Error("TEXT_MESSAGE must be encrypted")
	}

	fields := map[string]bool{}
	for _, f := range mp.TextEvent.Fields {
		fields[f.Name] = true
		if f.Name == "target_message_id" {
			required := strings.Join(f.RequiredFor, ",")
			for _, kind := range []string{"EDIT", "DELETE_FOR_EVERYONE", "REACTION"} {
				if !strings.Contains(required, kind) {
					t.Errorf("target_message_id must be required for %s (AMD-005)", kind)
				}
			}
			if len(f.ProhibitedFor) == 0 || f.ProhibitedFor[0] != "TEXT" {
				t.Error("target_message_id must be prohibited for a plain TEXT event")
			}
		}
	}
	for _, want := range []string{"event_kind", "target_message_id", "body"} {
		if !fields[want] {
			t.Errorf("text event envelope is missing field %q", want)
		}
	}
}

// TestErrorCodesAreStableAndUnique guards the registry TRS-PROTO-013 requires.
func TestErrorCodesAreStableAndUnique(t *testing.T) {
	ec, err := LoadErrorCodes(repoRoot(t))
	if err != nil {
		t.Fatalf("LoadErrorCodes: %v", err)
	}
	if len(ec.Codes) == 0 {
		t.Fatal("error registry is empty")
	}
	byName := map[string]bool{}
	byNum := map[int]string{}
	for _, c := range ec.Codes {
		if !strings.HasPrefix(c.Code, "ERR_") {
			t.Errorf("error code %q does not use the ERR_ prefix", c.Code)
		}
		if byName[c.Code] {
			t.Errorf("duplicate error code %s", c.Code)
		}
		byName[c.Code] = true
		if prev, dup := byNum[c.Numeric]; dup {
			t.Errorf("numeric %d used by both %s and %s", c.Numeric, prev, c.Code)
		}
		byNum[c.Numeric] = c.Code
		if c.Meaning == "" {
			t.Errorf("%s has no meaning", c.Code)
		}
		if c.UserMessage != nil && *c.UserMessage == "" {
			t.Errorf("%s has an empty user message; use null for deliberately silent errors", c.Code)
		}
	}
}

// TestUserMessagesAvoidProtocolJargon enforces TRS-UX-005 on the surface that is
// most likely to leak it.
func TestUserMessagesAvoidProtocolJargon(t *testing.T) {
	ec, err := LoadErrorCodes(repoRoot(t))
	if err != nil {
		t.Fatalf("LoadErrorCodes: %v", err)
	}
	// Matched on word boundaries, not as substrings: several of these terms are
	// short enough to appear inside ordinary words ("availa-ble", "s-hop").
	jargon := []string{
		"aead", "ratchet", "nonce", "ciphertext", "payload", "frame", "mtu",
		"gatt", "ble", "ttl", "hop", "schema", "sha256", "x25519", "ed25519",
		"prekey", "envelope", "protocol", "decrypt", "hash", "aes", "chacha",
	}
	pattern := regexp.MustCompile(`(?i)\b(` + strings.Join(jargon, "|") + `)\b`)
	for _, c := range ec.Codes {
		if c.UserMessage == nil {
			continue
		}
		if m := pattern.FindString(*c.UserMessage); m != "" {
			t.Errorf("%s user message leaks protocol jargon %q: %q", c.Code, m, *c.UserMessage)
		}
	}
}

// TestJargonDetectorActuallyDetects guards the guard: a word-boundary regex that
// silently matched nothing would let every jargon leak through unnoticed.
func TestJargonDetectorActuallyDetects(t *testing.T) {
	pattern := regexp.MustCompile(`(?i)\b(aead|ratchet|payload|ble|hop)\b`)
	for _, bad := range []string{
		"The AEAD check failed.",
		"Ratchet state was lost.",
		"Payload too large.",
		"BLE link dropped.",
		"Hop limit exceeded.",
	} {
		if pattern.FindString(bad) == "" {
			t.Errorf("detector failed to flag jargon in %q", bad)
		}
	}
	for _, good := range []string{
		"This offer is no longer available.",
		"We hope you enjoy it.",
		"That was too large to send.",
	} {
		if m := pattern.FindString(good); m != "" {
			t.Errorf("detector false-positived on %q with %q", good, m)
		}
	}
}

// TestSchemaErrorCodesExistInRegistry stops a schema naming an undefined code.
func TestSchemaErrorCodesExistInRegistry(t *testing.T) {
	root := repoRoot(t)
	ec, err := LoadErrorCodes(root)
	if err != nil {
		t.Fatalf("LoadErrorCodes: %v", err)
	}
	known := ec.Set()

	var referenced []string
	for _, p := range []string{FrameHeaderPath, MessagePayloadPath} {
		raw, err := LoadRaw(root, p)
		if err != nil {
			t.Fatalf("%s: %v", p, err)
		}
		collectErrorCodes(raw, &referenced)
	}
	sort.Strings(referenced)
	if len(referenced) == 0 {
		t.Fatal("no error codes referenced by the schemas")
	}
	for _, code := range referenced {
		if _, ok := known[code]; !ok {
			t.Errorf("schema references error code %s, which is not in the registry", code)
		}
	}
}

func collectErrorCodes(v any, out *[]string) {
	switch t := v.(type) {
	case map[string]any:
		for k, val := range t {
			if strings.HasSuffix(k, "error_code") || k == "error_code_unknown_critical" {
				if s, ok := val.(string); ok {
					*out = append(*out, s)
				}
				continue
			}
			collectErrorCodes(val, out)
		}
	case []any:
		for _, e := range t {
			collectErrorCodes(e, out)
		}
	}
}

// TestAdvV1LayoutMatchesADR002 pins the 18-byte hint and the platform asymmetry.
func TestAdvV1LayoutMatchesADR002(t *testing.T) {
	root := repoRoot(t)
	reg, err := LoadRegister(root)
	if err != nil {
		t.Fatalf("LoadRegister: %v", err)
	}
	for path, want := range map[string]int64{
		"identity_discovery.ADV_V1_LAYOUT.total_len":               18,
		"identity_discovery.ADV_V1_LAYOUT.version_len":             1,
		"identity_discovery.ADV_V1_LAYOUT.mode_flags_len":          1,
		"identity_discovery.ADV_V1_LAYOUT.eid_len":                 12,
		"identity_discovery.ADV_V1_LAYOUT.auth_tag_len":            4,
		"identity_discovery.ADV_V1_SCAN_RESPONSE_LAYOUT.total_len": 6,
	} {
		got, err := reg.Int(path)
		if err != nil {
			t.Errorf("%s: %v", path, err)
			continue
		}
		if got != want {
			t.Errorf("%s = %d, TRS Sec. 9.3 says %d", path, got, want)
		}
	}

	sum := int64(0)
	for _, f := range []string{"version_len", "mode_flags_len", "eid_len", "auth_tag_len"} {
		v, err := reg.Int("identity_discovery.ADV_V1_LAYOUT." + f)
		if err != nil {
			t.Fatalf("%s: %v", f, err)
		}
		sum += v
	}
	total, _ := reg.Int("identity_discovery.ADV_V1_LAYOUT.total_len")
	if sum != total {
		t.Errorf("adv_v1 field widths sum to %d but total_len is %d", sum, total)
	}

	raw, err := LoadRaw(root, AdvV1Path)
	if err != nil {
		t.Fatalf("LoadRaw: %v", err)
	}
	body, err := json.Marshal(raw)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	text := string(body)
	if !strings.Contains(text, "uuid_only") {
		t.Error("adv schema must record the iOS and Android-degraded UUID-only modes (ADR-002)")
	}
	if !strings.Contains(text, "unauthenticated routing hint") &&
		!strings.Contains(text, "UNAUTHENTICATED ROUTING HINT") {
		t.Error("adv schema must state that the advertisement is an unauthenticated hint (TRS-PROTO-017)")
	}
}

// TestGeneratedConfigOverridesAreRegisterDriven checks the ADR-004 requirement
// that config validation bounds are derived, not maintained by hand.
func TestGeneratedConfigOverridesAreRegisterDriven(t *testing.T) {
	root := repoRoot(t)
	raw, err := LoadRaw(root, ConfigOverridePath)
	if err != nil {
		t.Fatalf("read generated override schema: %v", err)
	}
	if ap, ok := raw["additionalProperties"].(bool); !ok || ap {
		t.Error("override schema must set additionalProperties=false so a frozen constant is rejected, not ignored")
	}
	comment, _ := raw["$comment"].(string)
	if !strings.Contains(comment, "GENERATED - DO NOT EDIT") {
		t.Error("override schema must carry the generated banner")
	}

	reg, err := LoadRegister(root)
	if err != nil {
		t.Fatalf("LoadRegister: %v", err)
	}
	declaredHash, _ := raw["x-constants-hash"].(string)
	registerHash, err := registerString(reg, "constants_hash")
	if err != nil {
		t.Fatalf("register constants_hash: %v", err)
	}
	if declaredHash != registerHash {
		t.Errorf("override schema constants hash %q does not match the register %q", declaredHash, registerHash)
	}

	props, ok := raw["properties"].(map[string]any)
	if !ok || len(props) == 0 {
		t.Fatal("override schema declares no properties")
	}

	// Every bounded property's min/max must equal the register's.
	for key, v := range props {
		p, ok := v.(map[string]any)
		if !ok {
			continue
		}
		if _, bounded := p["minimum"]; !bounded {
			continue
		}
		desc, _ := p["description"].(string)
		regPath := strings.Fields(desc)[0] // "group.NAME.field (register default N)"
		if !reg.Has(regPath) {
			t.Errorf("override property %s describes register path %q, which does not resolve", key, regPath)
		}
	}
}

func registerString(r *Register, key string) (string, error) {
	v, err := r.lookup(key)
	if err != nil {
		return "", err
	}
	s, ok := v.(string)
	if !ok {
		return "", os.ErrInvalid
	}
	return s, nil
}

// TestConfigAndVoucherSchemasForbidFreeFormObjects is the structural half of
// TRS-ARCH-006: configuration must never be able to carry executable content.
func TestConfigAndVoucherSchemasForbidFreeFormObjects(t *testing.T) {
	root := repoRoot(t)
	for _, p := range []string{ConfigPackagePath, VoucherTokenPath, PrekeyBundlePath} {
		raw, err := LoadRaw(root, p)
		if err != nil {
			t.Errorf("%s: %v", p, err)
			continue
		}
		var offenders []string
		findOpenObjects(raw, "", &offenders)
		for _, o := range offenders {
			t.Errorf("%s: object at %s does not set additionalProperties=false; configuration must admit no free-form area (TRS-ARCH-006)", p, o)
		}
	}
}

// findOpenObjects reports every JSON Schema object node that fails to close
// itself against unknown members.
func findOpenObjects(v any, path string, out *[]string) {
	m, ok := v.(map[string]any)
	if !ok {
		if arr, isArr := v.([]any); isArr {
			for i, e := range arr {
				findOpenObjects(e, path, out)
				_ = i
			}
		}
		return
	}
	if typ, _ := m["type"].(string); typ == "object" {
		if ap, present := m["additionalProperties"]; !present {
			*out = append(*out, orRoot(path))
		} else if b, isBool := ap.(bool); !isBool || b {
			*out = append(*out, orRoot(path))
		}
	}
	for k, val := range m {
		if strings.HasPrefix(k, "$") || k == "x-implements" || k == "x-adrs" {
			continue
		}
		findOpenObjects(val, path+"/"+k, out)
	}
}

func orRoot(p string) string {
	if p == "" {
		return "(root)"
	}
	return p
}
