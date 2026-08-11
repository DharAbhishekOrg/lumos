// Lumos - tools/constantsgen / Tri-language emitters for the constants register.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CONST-001, TRS-CONST-002, TRS-CONST-005, TRS-REP-002, TRS-PROTO-005
// ADRs: ADR-002, ADR-004
// Threading: any
// Notes: Every emitter writes deterministically from the sorted model, so
// regenerating without editing the register reproduces byte-identical output.
// That property is what makes the -check mode a usable CI gate.
package main

import (
	"fmt"
	"go/format"
	"strconv"
	"strings"
	"unicode/utf16"
)

// File is one generated artefact.
type File struct {
	Path    string
	Content []byte
}

// OutputPaths are the generated artefacts, relative to the repository root.
const (
	GoOutPath           = "protocol/gen/go/lumosconstants/constants.go"
	GoEnumOutPath       = "protocol/gen/go/lumosconstants/message_class.go"
	JavaConstOutPath    = "protocol/gen/java/com/lumos/protocol/LumosConstants.java"
	JavaClassOutPath    = "protocol/gen/java/com/lumos/protocol/MessageClass.java"
	JavaEventKindOut    = "protocol/gen/java/com/lumos/protocol/TextEventKind.java"
	SwiftOutPath        = "protocol/gen/swift/LumosConstants.swift"
	CanonicalFormOut    = "protocol/constants/canonical-form.txt"
	generatorName       = "tools/constantsgen"
	generatedBannerText = "GENERATED - DO NOT EDIT. Generator: " + generatorName
)

// Emit produces every generated artefact for the model. Go output is passed
// through go/format so the generator can never emit code that fails the
// repository's format gate; a formatting error here is a generator bug and is
// surfaced rather than written to disk.
func Emit(r *Register, m *Model, canonical string) ([]File, error) {
	goConstants, err := gofmtSource(GoOutPath, emitGoConstants(m))
	if err != nil {
		return nil, err
	}
	goEnums, err := gofmtSource(GoEnumOutPath, emitGoEnums(m))
	if err != nil {
		return nil, err
	}
	configSchema, err := EmitConfigOverrideSchema(r, m)
	if err != nil {
		return nil, err
	}
	return []File{
		{ConfigSchemaOutPath, configSchema},
		{GoOutPath, goConstants},
		{GoEnumOutPath, goEnums},
		{JavaConstOutPath, []byte(emitJavaConstants(m))},
		{JavaClassOutPath, []byte(emitJavaEnum(m, "MessageClass", m.MessageClass, "TRS Sec. 9.2 canonical message classes."))},
		{JavaEventKindOut, []byte(emitJavaEnum(m, "TextEventKind", m.TextEventKind, "AMD-005 inner event envelope kinds for class 0x10."))},
		{SwiftOutPath, []byte(emitSwift(m))},
		{CanonicalFormOut, []byte(canonical)},
	}, nil
}

func gofmtSource(path, src string) ([]byte, error) {
	out, err := format.Source([]byte(src))
	if err != nil {
		return nil, fmt.Errorf("generated %s is not valid Go: %w", path, err)
	}
	return out, nil
}

func header(commentPrefix, module, purpose string) string {
	lines := []string{
		"Lumos - " + module + " / " + purpose,
		"Author: dhar.abhishek.email@gmail.com",
		"Implements: TRS-CONST-001, TRS-CONST-002, TRS-CONST-005, TRS-REP-002",
		"ADRs: ADR-002, ADR-004",
		"Threading: any",
		generatedBannerText,
	}
	var b strings.Builder
	for _, l := range lines {
		b.WriteString(commentPrefix + " " + l + "\n")
	}
	return b.String()
}

func provenance(m *Model, commentPrefix string) string {
	var b strings.Builder
	b.WriteString(commentPrefix + " Source register: protocol/constants/lumos_constants.json\n")
	b.WriteString(commentPrefix + " Baseline: " + m.Baseline + "\n")
	b.WriteString(commentPrefix + " constants_hash: " + m.ConstantsHash + "\n")
	return b.String()
}

func hexCode(code int64) string { return fmt.Sprintf("0x%02X", code) }

// ---------- Go ----------

func emitGoConstants(m *Model) string {
	var b strings.Builder
	b.WriteString(header("//", "protocol/gen/go/lumosconstants", "Generated Lumos protocol and policy constants."))
	b.WriteString(provenance(m, "//"))
	b.WriteString("\npackage lumosconstants\n\n")

	b.WriteString("// ServiceUUID is the one fixed 128-bit Lumos GATT service UUID (ADR-002).\n")
	b.WriteString(fmt.Sprintf("const ServiceUUID = %q\n\n", m.ServiceUUID))
	b.WriteString("// ConstantsHash binds generated code to the register that produced it (TRS-CONST-005).\n")
	b.WriteString(fmt.Sprintf("const ConstantsHash = %q\n\n", m.ConstantsHash))
	b.WriteString("// SchemaVersion is the register schema version.\n")
	b.WriteString(fmt.Sprintf("const SchemaVersion = %d\n\n", m.SchemaVersion))
	b.WriteString("// Baseline names the specification sections this register was derived from.\n")
	b.WriteString(fmt.Sprintf("const Baseline = %q\n", m.Baseline))

	group := ""
	var slices []Constant
	for _, c := range m.Constants {
		if c.Group != group {
			group = c.Group
			b.WriteString("\n// " + groupTitle(group) + "\n")
		}
		if c.Kind == KindIntSlice || c.Kind == KindStringSlice {
			slices = append(slices, c)
			continue
		}
		b.WriteString(fmt.Sprintf("\n// %s is %s.\n", c.GoName(), c.SourcePth))
		b.WriteString(fmt.Sprintf("const %s = %s\n", c.GoName(), goLiteral(c)))
	}
	for _, c := range slices {
		b.WriteString(fmt.Sprintf("\n// %s is %s. It is a var because Go has no array constants; treat it as read-only.\n", c.GoName(), c.SourcePth))
		if c.Kind == KindStringSlice {
			b.WriteString(fmt.Sprintf("var %s = [...]string{%s}\n", c.GoName(), joinQuoted(c.Strs, ", ")))
			continue
		}
		b.WriteString(fmt.Sprintf("var %s = [...]int{%s}\n", c.GoName(), joinInts(c.Ints, ", ")))
	}
	return b.String()
}

func goLiteral(c Constant) string {
	switch c.Kind {
	case KindInt:
		return strconv.FormatInt(c.Int, 10)
	case KindBool:
		return strconv.FormatBool(c.Bool)
	case KindString:
		return strconv.Quote(c.Str)
	}
	return ""
}

func emitGoEnums(m *Model) string {
	var b strings.Builder
	b.WriteString(header("//", "protocol/gen/go/lumosconstants", "Generated canonical message-class enums."))
	b.WriteString(provenance(m, "//"))
	b.WriteString("\npackage lumosconstants\n\n")
	b.WriteString("import \"fmt\"\n")
	b.WriteString(goEnum("MessageClass", "canonical Lumos message class (TRS Sec. 9.2)", m.MessageClass))
	b.WriteString(goEnum("TextEventKind", "inner event kind of a TEXT_MESSAGE envelope (AMD-005)", m.TextEventKind))
	return b.String()
}

func goEnum(typeName, doc string, members []EnumMember) string {
	var b strings.Builder
	b.WriteString(fmt.Sprintf("\n// %s is a %s.\n", typeName, doc))
	b.WriteString(fmt.Sprintf("type %s uint8\n\n", typeName))
	b.WriteString("const (\n")
	for _, mem := range members {
		b.WriteString(fmt.Sprintf("\t// %s%s: %s\n", typeName, upperCamel(mem.Name), mem.Purpose))
		b.WriteString(fmt.Sprintf("\t%s%s %s = %s\n", typeName, upperCamel(mem.Name), typeName, hexCode(mem.Code)))
	}
	b.WriteString(")\n\n")

	b.WriteString(fmt.Sprintf("// %sNames maps each known %s to its canonical name.\n", typeName, typeName))
	b.WriteString(fmt.Sprintf("var %sNames = map[%s]string{\n", typeName, typeName))
	for _, mem := range members {
		b.WriteString(fmt.Sprintf("\t%s%s: %q,\n", typeName, upperCamel(mem.Name), mem.Name))
	}
	b.WriteString("}\n\n")

	b.WriteString("// String returns the canonical name, or an explicit unknown form. It never\n")
	b.WriteString("// panics: unknown values are a protocol condition handled per TRS-PROTO-006.\n")
	b.WriteString(fmt.Sprintf("func (v %s) String() string {\n", typeName))
	b.WriteString(fmt.Sprintf("\tif n, ok := %sNames[v]; ok {\n\t\treturn n\n\t}\n", typeName))
	b.WriteString(fmt.Sprintf("\treturn fmt.Sprintf(\"UNKNOWN_%s(0x%%02X)\", uint8(v))\n}\n\n", strings.ToUpper(typeName)))

	b.WriteString(fmt.Sprintf("// Known reports whether v is a %s defined by this protocol version.\n", typeName))
	b.WriteString(fmt.Sprintf("func (v %s) Known() bool {\n\t_, ok := %sNames[v]\n\treturn ok\n}\n", typeName, typeName))
	return b.String()
}

func groupTitle(group string) string {
	return strings.ToUpper(group[:1]) + strings.ReplaceAll(group[1:], "_", " ")
}

func joinQuoted(xs []string, sep string) string {
	parts := make([]string, len(xs))
	for i, x := range xs {
		parts[i] = strconv.Quote(x)
	}
	return strings.Join(parts, sep)
}

// javaEscape renders s as a Java string literal with every non-ASCII character
// escaped as UTF-16 \u units, so generated Java stays pure ASCII and compiles
// identically under any platform default source encoding.
func javaEscape(s string) string {
	var b strings.Builder
	b.WriteByte('"')
	for _, r := range s {
		switch {
		case r == '"':
			b.WriteString(`\"`)
		case r == '\\':
			b.WriteString(`\\`)
		case r < 0x20 || r > 0x7E:
			for _, u := range utf16.Encode([]rune{r}) {
				fmt.Fprintf(&b, `\u%04X`, u)
			}
		default:
			b.WriteRune(r)
		}
	}
	b.WriteByte('"')
	return b.String()
}

func joinInts(xs []int64, sep string) string {
	parts := make([]string, len(xs))
	for i, x := range xs {
		parts[i] = strconv.FormatInt(x, 10)
	}
	return strings.Join(parts, sep)
}

// ---------- Java ----------

func emitJavaConstants(m *Model) string {
	var b strings.Builder
	b.WriteString("/*\n")
	for _, l := range strings.Split(strings.TrimRight(header(" *", "protocol/gen/java", "Generated Lumos protocol and policy constants."), "\n"), "\n") {
		b.WriteString(l + "\n")
	}
	b.WriteString(provenance(m, " *"))
	b.WriteString(" */\n")
	b.WriteString("package com.lumos.protocol;\n\n")

	needsList := false
	for _, c := range m.Constants {
		if c.Kind == KindIntSlice {
			needsList = true
		}
	}
	if needsList {
		b.WriteString("import java.util.List;\n\n")
	}

	b.WriteString("/** Generated Lumos constants. Consume these; never re-declare a value. */\n")
	b.WriteString("public final class LumosConstants {\n\n")
	b.WriteString("    private LumosConstants() {\n")
	b.WriteString("        throw new AssertionError(\"no instances\");\n")
	b.WriteString("    }\n\n")

	b.WriteString("    /** The one fixed 128-bit Lumos GATT service UUID (ADR-002). */\n")
	b.WriteString(fmt.Sprintf("    public static final String SERVICE_UUID = %q;\n\n", m.ServiceUUID))
	b.WriteString("    /** Binds generated code to the register that produced it (TRS-CONST-005). */\n")
	b.WriteString(fmt.Sprintf("    public static final String CONSTANTS_HASH = %q;\n\n", m.ConstantsHash))
	b.WriteString("    /** Register schema version. */\n")
	b.WriteString(fmt.Sprintf("    public static final int SCHEMA_VERSION = %d;\n\n", m.SchemaVersion))
	b.WriteString("    /** Specification sections this register was derived from. */\n")
	b.WriteString(fmt.Sprintf("    public static final String BASELINE = %q;\n", m.Baseline))

	group := ""
	for _, c := range m.Constants {
		if c.Group != group {
			group = c.Group
			b.WriteString("\n    // " + groupTitle(group) + "\n")
		}
		b.WriteString(fmt.Sprintf("\n    /** %s */\n", c.SourcePth))
		b.WriteString(fmt.Sprintf("    public static final %s %s = %s;\n", javaType(c), c.Base, javaLiteral(c)))
	}
	b.WriteString("}\n")
	return b.String()
}

func javaType(c Constant) string {
	switch c.Kind {
	case KindInt:
		if c.Int > 2147483647 || c.Int < -2147483648 {
			return "long"
		}
		return "int"
	case KindBool:
		return "boolean"
	case KindString:
		return "String"
	case KindIntSlice:
		return "List<Integer>"
	case KindStringSlice:
		return "List<String>"
	}
	return "Object"
}

func javaLiteral(c Constant) string {
	switch c.Kind {
	case KindInt:
		if c.Int > 2147483647 || c.Int < -2147483648 {
			return strconv.FormatInt(c.Int, 10) + "L"
		}
		return strconv.FormatInt(c.Int, 10)
	case KindBool:
		return strconv.FormatBool(c.Bool)
	case KindString:
		return strconv.Quote(c.Str)
	case KindIntSlice:
		return "List.of(" + joinInts(c.Ints, ", ") + ")"
	case KindStringSlice:
		parts := make([]string, len(c.Strs))
		for i, v := range c.Strs {
			parts[i] = javaEscape(v)
		}
		return "List.of(" + strings.Join(parts, ", ") + ")"
	}
	return ""
}

func emitJavaEnum(m *Model, typeName string, members []EnumMember, doc string) string {
	var b strings.Builder
	b.WriteString("/*\n")
	b.WriteString(strings.TrimRight(header(" *", "protocol/gen/java", "Generated "+typeName+" enum."), "\n") + "\n")
	b.WriteString(provenance(m, " *"))
	b.WriteString(" */\n")
	b.WriteString("package com.lumos.protocol;\n\n")
	b.WriteString("/** " + doc + " */\n")
	b.WriteString("public enum " + typeName + " {\n")
	for i, mem := range members {
		sep := ","
		if i == len(members)-1 {
			sep = ";"
		}
		b.WriteString(fmt.Sprintf("    /** %s */\n", mem.Purpose))
		b.WriteString(fmt.Sprintf("    %s(%s)%s\n", mem.Name, hexCode(mem.Code), sep))
	}
	b.WriteString("\n    private final int code;\n\n")
	b.WriteString("    " + typeName + "(final int code) {\n        this.code = code;\n    }\n\n")
	b.WriteString("    /** @return the canonical wire code. */\n")
	b.WriteString("    public int code() {\n        return code;\n    }\n\n")
	b.WriteString("    /**\n")
	b.WriteString("     * Resolves a wire code.\n")
	b.WriteString("     *\n")
	b.WriteString("     * @param code the wire code\n")
	b.WriteString("     * @return the matching constant, or null when the code is not defined by this\n")
	b.WriteString("     *         protocol version; callers apply TRS-PROTO-006 fail-closed handling.\n")
	b.WriteString("     */\n")
	b.WriteString("    public static " + typeName + " fromCode(final int code) {\n")
	b.WriteString("        for (final " + typeName + " v : values()) {\n")
	b.WriteString("            if (v.code == code) {\n                return v;\n            }\n")
	b.WriteString("        }\n        return null;\n    }\n")
	b.WriteString("}\n")
	return b.String()
}

// ---------- Swift ----------

func emitSwift(m *Model) string {
	var b strings.Builder
	b.WriteString(header("///", "protocol/gen/swift", "Generated Lumos protocol and policy constants."))
	b.WriteString(provenance(m, "///"))
	b.WriteString("\npublic enum LumosConstants {\n")
	b.WriteString("\n    /// The one fixed 128-bit Lumos GATT service UUID (ADR-002).\n")
	b.WriteString(fmt.Sprintf("    public static let serviceUUID = %q\n", m.ServiceUUID))
	b.WriteString("\n    /// Binds generated code to the register that produced it (TRS-CONST-005).\n")
	b.WriteString(fmt.Sprintf("    public static let constantsHash = %q\n", m.ConstantsHash))
	b.WriteString("\n    /// Register schema version.\n")
	b.WriteString(fmt.Sprintf("    public static let schemaVersion = %d\n", m.SchemaVersion))
	b.WriteString("\n    /// Specification sections this register was derived from.\n")
	b.WriteString(fmt.Sprintf("    public static let baseline = %q\n", m.Baseline))

	group := ""
	for _, c := range m.Constants {
		if c.Group != group {
			group = c.Group
			b.WriteString("\n    // MARK: - " + groupTitle(group) + "\n")
		}
		b.WriteString(fmt.Sprintf("\n    /// %s\n", c.SourcePth))
		b.WriteString(fmt.Sprintf("    public static let %s%s = %s\n", c.SwiftName(), swiftTypeAnnotation(c), swiftLiteral(c)))
	}
	b.WriteString("}\n")

	b.WriteString(swiftEnum("MessageClass", "Canonical Lumos message class (TRS Sec. 9.2).", m.MessageClass))
	b.WriteString(swiftEnum("TextEventKind", "Inner event kind of a TEXT_MESSAGE envelope (AMD-005).", m.TextEventKind))
	return b.String()
}

func swiftTypeAnnotation(c Constant) string {
	switch c.Kind {
	case KindIntSlice:
		return ": [Int]"
	case KindStringSlice:
		return ": [String]"
	}
	return ""
}

func swiftLiteral(c Constant) string {
	switch c.Kind {
	case KindInt:
		return strconv.FormatInt(c.Int, 10)
	case KindBool:
		return strconv.FormatBool(c.Bool)
	case KindString:
		return strconv.Quote(c.Str)
	case KindIntSlice:
		return "[" + joinInts(c.Ints, ", ") + "]"
	case KindStringSlice:
		return "[" + joinQuoted(c.Strs, ", ") + "]"
	}
	return ""
}

func swiftEnum(typeName, doc string, members []EnumMember) string {
	var b strings.Builder
	b.WriteString("\n/// " + doc + "\n")
	b.WriteString("public enum " + typeName + ": UInt8, CaseIterable, Sendable {\n")
	for _, mem := range members {
		b.WriteString(fmt.Sprintf("    /// %s\n", mem.Purpose))
		b.WriteString(fmt.Sprintf("    case %s = %s\n", swiftCase(mem.Name), hexCode(mem.Code)))
	}
	b.WriteString("\n    /// The canonical name as written in the register.\n")
	b.WriteString("    public var canonicalName: String {\n        switch self {\n")
	for _, mem := range members {
		b.WriteString(fmt.Sprintf("        case .%s: return %q\n", swiftCase(mem.Name), mem.Name))
	}
	b.WriteString("        }\n    }\n")
	b.WriteString("}\n")
	return b.String()
}
