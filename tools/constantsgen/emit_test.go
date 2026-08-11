// Lumos - tools/constantsgen / Tests for determinism and generated-code integrity.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CONST-001, TRS-CONST-005, TRS-REP-002, TRS-CICD-005
// ADRs: ADR-004
// Threading: any
// Notes: The generated Go is re-read through go/parser and compared back to the
// register, so the assertion covers the emitted text rather than the in-memory
// model the emitter already agreed with.
package main

import (
	"bytes"
	"go/ast"
	"go/parser"
	"go/token"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"testing"
)

func repoRootPath(t *testing.T) string {
	t.Helper()
	p, err := filepath.Abs(filepath.Join("..", ".."))
	if err != nil {
		t.Fatalf("resolve repo root: %v", err)
	}
	return p
}

func buildModel(t *testing.T) (*Register, *Model, string) {
	t.Helper()
	r := loadRegister(t)
	hash, err := r.ConstantsHash()
	if err != nil {
		t.Fatalf("ConstantsHash: %v", err)
	}
	canon, err := r.CanonicalForm()
	if err != nil {
		t.Fatalf("CanonicalForm: %v", err)
	}
	m, err := BuildModel(r, hash)
	if err != nil {
		t.Fatalf("BuildModel: %v", err)
	}
	return r, m, canon
}

// TestEmissionIsDeterministic is the property the -check CI gate depends on.
func TestEmissionIsDeterministic(t *testing.T) {
	r, m, canon := buildModel(t)
	first, err := Emit(r, m, canon)
	if err != nil {
		t.Fatalf("Emit: %v", err)
	}
	second, err := Emit(r, m, canon)
	if err != nil {
		t.Fatalf("Emit (second): %v", err)
	}
	if len(first) != len(second) {
		t.Fatalf("artefact count differs: %d vs %d", len(first), len(second))
	}
	for i := range first {
		if first[i].Path != second[i].Path {
			t.Errorf("artefact %d path differs: %s vs %s", i, first[i].Path, second[i].Path)
		}
		if !bytes.Equal(first[i].Content, second[i].Content) {
			t.Errorf("artefact %s is not byte-identical across two emissions", first[i].Path)
		}
	}
}

// TestCommittedFilesMatchRegister is the same assertion the CI gate makes.
func TestCommittedFilesMatchRegister(t *testing.T) {
	r, m, canon := buildModel(t)
	files, err := Emit(r, m, canon)
	if err != nil {
		t.Fatalf("Emit: %v", err)
	}
	root := repoRootPath(t)
	for _, f := range files {
		got, err := os.ReadFile(filepath.Join(root, filepath.FromSlash(f.Path)))
		if err != nil {
			t.Errorf("%s: %v (run: go run ./tools/constantsgen)", f.Path, err)
			continue
		}
		if !bytes.Equal(got, f.Content) {
			t.Errorf("%s is out of date with the register (run: go run ./tools/constantsgen)", f.Path)
		}
	}
}

func TestGeneratedFilesCarryTheGeneratedBanner(t *testing.T) {
	root := repoRootPath(t)
	for _, p := range []string{GoOutPath, GoEnumOutPath, JavaConstOutPath, JavaClassOutPath, JavaEventKindOut, SwiftOutPath} {
		raw, err := os.ReadFile(filepath.Join(root, filepath.FromSlash(p)))
		if err != nil {
			t.Errorf("%s: %v", p, err)
			continue
		}
		body := string(raw)
		if !strings.Contains(body, "GENERATED - DO NOT EDIT") {
			t.Errorf("%s lacks the TRS Sec. 24.1 generated banner", p)
		}
		if !strings.Contains(body, generatorName) {
			t.Errorf("%s does not name its generator", p)
		}
		if !strings.Contains(body, "Author: dhar.abhishek.email@gmail.com") {
			t.Errorf("%s lacks the mandatory Author header field", p)
		}
	}
}

func TestIdentifiersAreValidAndDistinct(t *testing.T) {
	_, m, _ := buildModel(t)
	goRe := regexp.MustCompile(`^[A-Z][A-Za-z0-9]*$`)
	javaRe := regexp.MustCompile(`^[A-Z][A-Z0-9_]*$`)
	swiftRe := regexp.MustCompile(`^[a-z][A-Za-z0-9]*$`)
	for _, c := range m.Constants {
		if !goRe.MatchString(c.GoName()) {
			t.Errorf("%s: Go identifier %q is not exported UpperCamel", c.SourcePth, c.GoName())
		}
		if !javaRe.MatchString(c.Base) {
			t.Errorf("%s: Java identifier %q is not SCREAMING_SNAKE", c.SourcePth, c.Base)
		}
		if !swiftRe.MatchString(c.SwiftName()) {
			t.Errorf("%s: Swift identifier %q is not lowerCamel", c.SourcePth, c.SwiftName())
		}
	}
	if len(m.Constants) == 0 {
		t.Fatal("model produced no constants")
	}
}

// TestGeneratedGoValuesMatchRegister re-parses the emitted Go and checks every
// integer, boolean and string constant against the register.
func TestGeneratedGoValuesMatchRegister(t *testing.T) {
	_, m, _ := buildModel(t)
	root := repoRootPath(t)

	fset := token.NewFileSet()
	file, err := parser.ParseFile(fset, filepath.Join(root, filepath.FromSlash(GoOutPath)), nil, 0)
	if err != nil {
		t.Fatalf("parse generated Go: %v", err)
	}

	found := map[string]string{}
	for _, decl := range file.Decls {
		gd, ok := decl.(*ast.GenDecl)
		if !ok || gd.Tok != token.CONST {
			continue
		}
		for _, spec := range gd.Specs {
			vs, ok := spec.(*ast.ValueSpec)
			if !ok || len(vs.Names) != 1 || len(vs.Values) != 1 {
				continue
			}
			lit, ok := vs.Values[0].(*ast.BasicLit)
			if ok {
				found[vs.Names[0].Name] = lit.Value
				continue
			}
			if id, ok := vs.Values[0].(*ast.Ident); ok {
				found[vs.Names[0].Name] = id.Name
				continue
			}
			if un, ok := vs.Values[0].(*ast.UnaryExpr); ok {
				if l, ok := un.X.(*ast.BasicLit); ok {
					found[vs.Names[0].Name] = un.Op.String() + l.Value
				}
			}
		}
	}

	checked := 0
	for _, c := range m.Constants {
		if c.Kind == KindIntSlice {
			continue // emitted as a var, covered by the byte-comparison test
		}
		got, ok := found[c.GoName()]
		if !ok {
			t.Errorf("generated Go has no constant %s (%s)", c.GoName(), c.SourcePth)
			continue
		}
		var want string
		switch c.Kind {
		case KindInt:
			want = strconv.FormatInt(c.Int, 10)
		case KindBool:
			want = strconv.FormatBool(c.Bool)
		case KindString:
			want = strconv.Quote(c.Str)
		}
		if got != want {
			t.Errorf("%s: generated Go has %s = %s, register says %s", c.SourcePth, c.GoName(), got, want)
		}
		checked++
	}
	if checked < 100 {
		t.Errorf("only %d constants verified; expected the full register", checked)
	}
}

// TestMessageClassesMatchTRS92 transcribes TRS Sec. 9.2 independently.
func TestMessageClassesMatchTRS92(t *testing.T) {
	_, m, _ := buildModel(t)
	want := []struct {
		code int64
		name string
	}{
		{0x01, "HELLO"}, {0x02, "HELLO_ACK"}, {0x03, "INTEREST_REQUEST"},
		{0x04, "INTEREST_RESPONSE"}, {0x05, "PROFILE_PREVIEW"}, {0x06, "PROFILE_FULL"},
		{0x10, "TEXT_MESSAGE"}, {0x11, "DELIVERY_RECEIPT"}, {0x12, "READ_RECEIPT"},
		{0x13, "TYPING"}, {0x20, "OBJECT_META"}, {0x21, "OBJECT_CHUNK"},
		{0x22, "OBJECT_ACK"}, {0x23, "OBJECT_CANCEL"}, {0x24, "VOICE_META"},
		{0x30, "MESH_RELAY"}, {0x31, "STORE_FORWARD"}, {0x40, "VENUE_PACKAGE_HINT"},
		{0x41, "VENUE_ROOM_EVENT"}, {0x42, "VOUCHER_TOKEN"}, {0x50, "HEARTBEAT"},
		{0x7F, "ERROR"},
	}
	if len(m.MessageClass) != len(want) {
		t.Fatalf("message class count = %d, TRS Sec. 9.2 defines %d", len(m.MessageClass), len(want))
	}
	for i, w := range want {
		got := m.MessageClass[i]
		if got.Code != w.code || got.Name != w.name {
			t.Errorf("class[%d] = 0x%02X/%s, TRS Sec. 9.2 says 0x%02X/%s", i, got.Code, got.Name, w.code, w.name)
		}
	}

	wantKinds := []struct {
		code int64
		name string
	}{
		{0, "TEXT"}, {1, "EDIT"}, {2, "DELETE_FOR_EVERYONE"}, {3, "REACTION"},
	}
	if len(m.TextEventKind) != len(wantKinds) {
		t.Fatalf("text event kind count = %d, AMD-005 defines %d", len(m.TextEventKind), len(wantKinds))
	}
	for i, w := range wantKinds {
		got := m.TextEventKind[i]
		if got.Code != w.code || got.Name != w.name {
			t.Errorf("kind[%d] = %d/%s, AMD-005 says %d/%s", i, got.Code, got.Name, w.code, w.name)
		}
	}
}

// TestGeneratedSwiftIsStructurallyComplete is a text-level check only. Swift is
// not executable on the Windows authoring host; LumosProtocolConformance runs the
// real assertions on the macOS CI runner in WP-01 slice S5.
func TestGeneratedSwiftIsStructurallyComplete(t *testing.T) {
	root := repoRootPath(t)
	raw, err := os.ReadFile(filepath.Join(root, filepath.FromSlash(SwiftOutPath)))
	if err != nil {
		t.Fatalf("read generated Swift: %v", err)
	}
	body := string(raw)
	_, m, _ := buildModel(t)

	for _, c := range m.Constants {
		if !strings.Contains(body, "public static let "+c.SwiftName()) {
			t.Errorf("generated Swift is missing %s (%s)", c.SwiftName(), c.SourcePth)
		}
	}
	for _, mem := range m.MessageClass {
		if !strings.Contains(body, "case "+swiftCase(mem.Name)+" = ") {
			t.Errorf("generated Swift is missing MessageClass case %s", mem.Name)
		}
	}
	for _, decl := range []string{
		"public enum LumosConstants {",
		"public enum MessageClass: UInt8, CaseIterable, Sendable {",
		"public enum TextEventKind: UInt8, CaseIterable, Sendable {",
	} {
		if !strings.Contains(body, decl) {
			t.Errorf("generated Swift is missing declaration %q", decl)
		}
	}
}
