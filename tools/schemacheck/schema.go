// Lumos - tools/schemacheck / Cross-platform local-schema drift detector.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-LOC-002, TRS-LOC-003, TRS-CODE-007
// ADRs: ADR-003
// Threading: any
// Notes: The local schema is declared twice - once in Java, once in Swift -
// because neither platform can consume the other's build output and this
// database never crosses a device boundary, so there is nothing to generate it
// from the way protocol constants are generated. Duplication that nothing
// checks is duplication that drifts.
//
// The drift this catches is precisely the kind each platform's own tests
// CANNOT: both suites assert their columns with PRAGMA table_info against
// their OWN constants, so if Java renamed a column and Swift did not, both
// suites would still pass while the two platforms had quietly built different
// databases. That is the same shape of self-agreement the WP-01 closeout Sec.
// 5.2 records, one level up.
package schemacheck

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
)

// JavaSchemaPath is the Java declaration, relative to the repository root.
const JavaSchemaPath = "android-app/app/src/main/java/com/lumos/app/store/LocalSchema.java"

// JavaKindPath is the Java record-kind enum.
const JavaKindPath = "android-app/app/src/main/java/com/lumos/app/store/RecordKind.java"

// SwiftSchemaPath is the Swift declaration, which holds both.
const SwiftSchemaPath = "ios-app/shell/Sources/LumosAppShell/LocalSchema.swift"

// Schema is one platform's declaration, reduced to what must match.
type Schema struct {
	// Version is the declared schema version.
	Version int
	// Names maps a normalised role ("columnid") to the string it resolves to.
	Names map[string]string
	// Tables is the set of record table names.
	Tables []string
	// RecordTableDDL is the CREATE TABLE text with identifiers removed, so the
	// column types and constraints can be compared without the names, which
	// Names already covers.
	RecordTableDDL string
}

var (
	javaIntConst    = regexp.MustCompile(`(?m)public static final int\s+(\w+)\s*=\s*(\d+)\s*;`)
	javaStringConst = regexp.MustCompile(`(?m)public static final String\s+(\w+)\s*=\s*"([^"]*)"\s*;`)
	javaEnumConst   = regexp.MustCompile(`(?m)^\s{4}([A-Z][A-Z0-9_]*)\(`)
	swiftIntConst   = regexp.MustCompile(`(?m)public static let\s+(\w+)\s*=\s*(\d+)\s*$`)
	swiftStrConst   = regexp.MustCompile(`(?m)public static let\s+(\w+)\s*=\s*"([^"]*)"`)
	swiftEnumCase   = regexp.MustCompile(`(?m)^\s*case\s+\w+\s*=\s*"([^"]+)"`)
	stringLiteral   = regexp.MustCompile(`"((?:[^"\\]|\\.)*)"`)
	interpolation   = regexp.MustCompile(`\\\([^)]*\)`)
	whitespace      = regexp.MustCompile(`\s+`)
)

// normaliseRole reduces a constant name to a platform-independent role, so
// COLUMN_LOOKUP_TAG and columnLookupTag are recognisably the same thing.
func normaliseRole(name string) string {
	return strings.ToLower(strings.ReplaceAll(name, "_", ""))
}

// LoadJava reads the Java declaration.
func LoadJava(repoRoot string) (*Schema, error) {
	schemaSrc, err := readFile(repoRoot, JavaSchemaPath)
	if err != nil {
		return nil, err
	}
	kindSrc, err := readFile(repoRoot, JavaKindPath)
	if err != nil {
		return nil, err
	}

	out := &Schema{Names: map[string]string{}}
	for _, m := range javaIntConst.FindAllStringSubmatch(schemaSrc, -1) {
		if normaliseRole(m[1]) == "version" {
			if out.Version, err = strconv.Atoi(m[2]); err != nil {
				return nil, fmt.Errorf("java VERSION: %w", err)
			}
		}
	}
	for _, m := range javaStringConst.FindAllStringSubmatch(schemaSrc, -1) {
		out.Names[normaliseRole(m[1])] = m[2]
	}
	for _, m := range javaEnumConst.FindAllStringSubmatch(kindSrc, -1) {
		out.Tables = append(out.Tables, strings.ToLower(m[1]))
	}
	out.RecordTableDDL = ddlOf(schemaSrc, "createRecordTable")
	sort.Strings(out.Tables)
	return out, nil
}

// LoadSwift reads the Swift declaration.
func LoadSwift(repoRoot string) (*Schema, error) {
	src, err := readFile(repoRoot, SwiftSchemaPath)
	if err != nil {
		return nil, err
	}

	out := &Schema{Names: map[string]string{}}
	for _, m := range swiftIntConst.FindAllStringSubmatch(src, -1) {
		if normaliseRole(m[1]) == "version" {
			if out.Version, err = strconv.Atoi(strings.TrimSpace(m[2])); err != nil {
				return nil, fmt.Errorf("swift version: %w", err)
			}
		}
	}
	for _, m := range swiftStrConst.FindAllStringSubmatch(src, -1) {
		out.Names[normaliseRole(m[1])] = m[2]
	}
	for _, m := range swiftEnumCase.FindAllStringSubmatch(src, -1) {
		out.Tables = append(out.Tables, m[1])
	}
	out.RecordTableDDL = ddlOf(src, "createRecordTable")
	sort.Strings(out.Tables)
	return out, nil
}

// ddlOf extracts the concatenated string literals of one function, with Swift
// interpolations removed and whitespace collapsed. What survives is the SQL
// skeleton - types, constraints and punctuation - which is exactly the part
// that must be identical and that the Names comparison does not already cover.
func ddlOf(src, function string) string {
	start := strings.Index(src, function)
	if start < 0 {
		return ""
	}
	body := src[start:]
	// Stop at the next declaration so a later function's literals do not leak in.
	if end := strings.Index(body, "\n    }"); end > 0 {
		body = body[:end]
	}
	var parts []string
	for _, m := range stringLiteral.FindAllStringSubmatch(body, -1) {
		parts = append(parts, interpolation.ReplaceAllString(m[1], ""))
	}
	return strings.TrimSpace(whitespace.ReplaceAllString(strings.Join(parts, ""), " "))
}

// Differences reports every way two declarations disagree. An empty result
// means the platforms declare the same schema.
func Differences(java, swift *Schema) []string {
	var out []string
	if java.Version != swift.Version {
		out = append(out, fmt.Sprintf("schema version: java %d, swift %d", java.Version, swift.Version))
	}
	if !equalStrings(java.Tables, swift.Tables) {
		out = append(out, fmt.Sprintf("record tables: java %v, swift %v", java.Tables, swift.Tables))
	}
	if java.RecordTableDDL != swift.RecordTableDDL {
		out = append(out, fmt.Sprintf("CREATE TABLE skeleton:\n    java  %q\n    swift %q",
			java.RecordTableDDL, swift.RecordTableDDL))
	}
	for _, role := range sharedRoles(java.Names, swift.Names) {
		if java.Names[role] != swift.Names[role] {
			out = append(out, fmt.Sprintf("%s: java %q, swift %q",
				role, java.Names[role], swift.Names[role]))
		}
	}
	sort.Strings(out)
	return out
}

// RequiredRoles are the names both platforms must declare. Listing them means a
// role deleted from BOTH files is still a failure, which a pairwise comparison
// of whatever happens to be present would miss.
var RequiredRoles = []string{
	"columnid", "columnpayload", "columnlookuptag",
	"columncreatedat", "columnupdatedat", "columnpayloadversion",
	"tablemeta", "metakey", "metavalue", "metaschemaversion",
}

// MissingRoles reports required roles a declaration does not carry.
func MissingRoles(s *Schema) []string {
	var out []string
	for _, role := range RequiredRoles {
		if _, ok := s.Names[role]; !ok {
			out = append(out, role)
		}
	}
	return out
}

// sharedRoles lists roles present in both maps, sorted.
func sharedRoles(a, b map[string]string) []string {
	var out []string
	for role := range a {
		if _, ok := b[role]; ok {
			out = append(out, role)
		}
	}
	sort.Strings(out)
	return out
}

func equalStrings(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func readFile(repoRoot, rel string) (string, error) {
	raw, err := os.ReadFile(filepath.Join(repoRoot, filepath.FromSlash(rel)))
	if err != nil {
		return "", fmt.Errorf("read %s: %w", rel, err)
	}
	return string(raw), nil
}
