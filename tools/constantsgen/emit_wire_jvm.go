// Lumos - tools/constantsgen / Java and Swift emitters for error codes and class limits.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-PROTO-007, TRS-PROTO-013, TRS-CONST-001, TRS-REP-002
// ADRs: ADR-004
// Threading: any
// Notes: The Java and Swift conformance runners need the stable error registry
// and the per-class payload bounds as typed symbols. Hand-copying either into
// two more languages is exactly the duplicated-constant defect ADR-004 exists to
// prevent, so both are generated from the same schema files the Go emitters use.
package main

import (
	"fmt"
	"strings"
)

// Generated Java and Swift wire artefacts, relative to the repository root.
const (
	ErrorCodesJavaOutPath   = "protocol/gen/java/com/lumos/protocol/LumosErrors.java"
	ClassLimitsJavaOutPath  = "protocol/gen/java/com/lumos/protocol/MessageClassLimits.java"
	ErrorCodesSwiftOutPath  = "protocol/gen/swift/LumosErrors.swift"
	ClassLimitsSwiftOutPath = "protocol/gen/swift/MessageClassLimits.swift"
)

// classLimit is one resolved per-class payload bound.
type classLimit struct {
	Name  string
	Ref   string
	Bytes int64
}

// resolvedLimits returns each class's byte bound, in register class order.
func resolvedLimits(r *Register, m *Model, in *wireInputs) ([]classLimit, error) {
	byName := map[string]payloadClassRule{}
	for _, rule := range in.payloadClasses {
		byName[rule.Name] = rule
	}
	out := make([]classLimit, 0, len(m.MessageClass))
	for _, mem := range m.MessageClass {
		rule, ok := byName[mem.Name]
		if !ok {
			return nil, fmt.Errorf("class %s has no payload rule", mem.Name)
		}
		b, err := resolveMaxPayload(r, rule)
		if err != nil {
			return nil, err
		}
		out = append(out, classLimit{Name: mem.Name, Ref: rule.MaxPayloadRef, Bytes: b})
	}
	return out, nil
}

// EmitJVMWireFiles produces the Java and Swift error-code and class-limit files.
func EmitJVMWireFiles(r *Register, m *Model, in *wireInputs) ([]File, error) {
	limits, err := resolvedLimits(r, m, in)
	if err != nil {
		return nil, err
	}
	return []File{
		{ErrorCodesJavaOutPath, []byte(emitErrorCodesJava(m, in))},
		{ClassLimitsJavaOutPath, []byte(emitClassLimitsJava(m, limits))},
		{ErrorCodesSwiftOutPath, []byte(emitErrorCodesSwift(m, in))},
		{ClassLimitsSwiftOutPath, []byte(emitClassLimitsSwift(m, limits))},
	}, nil
}

func javaFileHeader(m *Model, purpose string) string {
	var b strings.Builder
	b.WriteString("/*\n")
	b.WriteString(strings.TrimRight(header(" *", "protocol/gen/java", purpose), "\n"))
	b.WriteString("\n")
	b.WriteString(provenance(m, " *"))
	b.WriteString(" */\n")
	b.WriteString("package com.lumos.protocol;\n\n")
	return b.String()
}

func emitErrorCodesJava(m *Model, in *wireInputs) string {
	var b strings.Builder
	b.WriteString(javaFileHeader(m, "Generated stable protocol error codes."))
	b.WriteString("import java.util.Map;\n\n")
	b.WriteString("/** Stable machine-readable error codes of TRS-PROTO-013. Append-only. */\n")
	b.WriteString("public final class LumosErrors {\n\n")
	b.WriteString("    private LumosErrors() {\n")
	b.WriteString("        throw new AssertionError(\"no instances\");\n")
	b.WriteString("    }\n")

	for _, c := range in.errorCodes {
		fmt.Fprintf(&b, "\n    /** %s (%d, %s): %s */\n", c.Code, c.Numeric, c.Category, c.Meaning)
		fmt.Fprintf(&b, "    public static final String %s = %q;\n", c.Code, c.Code)
	}

	b.WriteString("\n    /** Maps each stable code to its registry number. */\n")
	b.WriteString("    public static final Map<String, Integer> NUMERIC = Map.ofEntries(\n")
	for i, c := range in.errorCodes {
		sep := ","
		if i == len(in.errorCodes)-1 {
			sep = ""
		}
		fmt.Fprintf(&b, "        Map.entry(%s, %d)%s\n", c.Code, c.Numeric, sep)
	}
	b.WriteString("    );\n\n")
	b.WriteString("    /**\n")
	b.WriteString("     * Reports whether a code is in the stable registry.\n")
	b.WriteString("     *\n")
	b.WriteString("     * @param code a candidate stable error code\n")
	b.WriteString("     * @return true when the registry defines it\n")
	b.WriteString("     */\n")
	b.WriteString("    public static boolean known(final String code) {\n")
	b.WriteString("        return NUMERIC.containsKey(code);\n")
	b.WriteString("    }\n")
	b.WriteString("}\n")
	return b.String()
}

func emitClassLimitsJava(m *Model, limits []classLimit) string {
	var b strings.Builder
	b.WriteString(javaFileHeader(m, "Generated per-class maximum payload sizes."))
	b.WriteString("import java.util.Map;\n\n")
	b.WriteString("/** Per-class payload bounds resolved from the constants register (TRS-PROTO-007). */\n")
	b.WriteString("public final class MessageClassLimits {\n\n")
	b.WriteString("    private MessageClassLimits() {\n")
	b.WriteString("        throw new AssertionError(\"no instances\");\n")
	b.WriteString("    }\n\n")
	b.WriteString("    /** Maximum ciphertext payload in bytes, by message class. */\n")
	b.WriteString("    public static final Map<MessageClass, Long> MAX_PAYLOAD_BYTES = Map.ofEntries(\n")
	for i, row := range limits {
		sep := ","
		if i == len(limits)-1 {
			sep = ""
		}
		fmt.Fprintf(&b, "        Map.entry(MessageClass.%s, %dL)%s\n", row.Name, row.Bytes, sep)
	}
	b.WriteString("    );\n\n")
	b.WriteString("    /**\n")
	b.WriteString("     * Returns the payload bound for a message class.\n")
	b.WriteString("     *\n")
	b.WriteString("     * @param mc a message class\n")
	b.WriteString("     * @return the bound in bytes, or null when this protocol version does not\n")
	b.WriteString("     *         define the class; callers apply TRS-PROTO-006 handling\n")
	b.WriteString("     */\n")
	b.WriteString("    public static Long maxPayloadBytes(final MessageClass mc) {\n")
	b.WriteString("        return MAX_PAYLOAD_BYTES.get(mc);\n")
	b.WriteString("    }\n")
	b.WriteString("}\n")
	return b.String()
}

func emitErrorCodesSwift(m *Model, in *wireInputs) string {
	var b strings.Builder
	b.WriteString(header("///", "protocol/gen/swift", "Generated stable protocol error codes."))
	b.WriteString(provenance(m, "///"))
	b.WriteString("\n/// Stable machine-readable error codes of TRS-PROTO-013. Append-only.\n")
	b.WriteString("public enum LumosErrors {\n")
	for _, c := range in.errorCodes {
		fmt.Fprintf(&b, "\n    /// %s (%d, %s): %s\n", c.Code, c.Numeric, c.Category, c.Meaning)
		fmt.Fprintf(&b, "    public static let %s = %q\n", lowerCamel(c.Code), c.Code)
	}
	b.WriteString("\n    /// Maps each stable code to its registry number.\n")
	b.WriteString("    public static let numeric: [String: Int] = [\n")
	for _, c := range in.errorCodes {
		fmt.Fprintf(&b, "        %q: %d,\n", c.Code, c.Numeric)
	}
	b.WriteString("    ]\n\n")
	b.WriteString("    /// Reports whether a code is in the stable registry.\n")
	b.WriteString("    public static func known(_ code: String) -> Bool {\n")
	b.WriteString("        numeric[code] != nil\n")
	b.WriteString("    }\n")
	b.WriteString("}\n")
	return b.String()
}

func emitClassLimitsSwift(m *Model, limits []classLimit) string {
	var b strings.Builder
	b.WriteString(header("///", "protocol/gen/swift", "Generated per-class maximum payload sizes."))
	b.WriteString(provenance(m, "///"))
	b.WriteString("\n/// Per-class payload bounds resolved from the constants register (TRS-PROTO-007).\n")
	b.WriteString("public enum MessageClassLimits {\n\n")
	b.WriteString("    /// Maximum ciphertext payload in bytes, by message class.\n")
	b.WriteString("    public static let maxPayloadBytes: [MessageClass: Int] = [\n")
	for _, row := range limits {
		fmt.Fprintf(&b, "        .%s: %d,\n", swiftCase(row.Name), row.Bytes)
	}
	b.WriteString("    ]\n")
	b.WriteString("}\n")
	return b.String()
}
