// Lumos - tools/lumoslint / Java APIs the Android minSdk does not have, in the library Android Lint cannot see.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CODE-006, TRS-AND-001
// ADRs: -
// Threading: any
// Notes: protocol-java ships inside the Android app, which runs from minSdk 26
// with no core-library desugaring, but it is a plain Java module: Android
// Lint's NewApi check never reads it. WP-05 S7b found the cost - code that
// compiled and passed on the JVM used ByteArrayOutputStream.writeBytes (API 33)
// and java.util.HexFormat (API 34), and would have thrown NoSuchMethodError
// on Android 8 to 13 the first time a chat message was sealed. This rule is a
// denylist of the Java 9+ APIs that D8 does NOT backport and that this code
// base is likely to reach for. It is deliberately narrow: the app module
// itself stays under Android Lint, which knows every API level exactly.
package main

import (
	"bufio"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
)

// minSdkLibraryRoot is the Java library the app ships but Lint does not check.
const minSdkLibraryRoot = "android-app/protocol-java/src/main/java/"

// minSdkDenied maps a pattern to the reason it cannot run at minSdk 26.
var minSdkDenied = []struct {
	pattern *regexp.Regexp
	why     string
}{
	{regexp.MustCompile(`\.writeBytes\(`), "ByteArrayOutputStream.writeBytes is API 33; use write(b, 0, b.length)"},
	{regexp.MustCompile(`\bHexFormat\b`), "java.util.HexFormat is API 34; encode hex by hand"},
	{regexp.MustCompile(`\.readAllBytes\(|\.readNBytes\(|\.transferTo\(`), "InputStream bulk reads are API 33"},
	{regexp.MustCompile(`\.toList\(\)`), "Stream.toList is API 34; use collect(Collectors.toList())"},
	{regexp.MustCompile(`\.isBlank\(\)|\.strip\(\)|\.lines\(\)|\.repeat\(`), "these String methods are API 33"},
}

// CheckMinSdkApis reports denied API uses in the protocol library's main sources.
func CheckMinSdkApis(root, path string) []Finding {
	rel, err := filepath.Rel(root, path)
	if err != nil || !strings.HasPrefix(filepath.ToSlash(rel), minSdkLibraryRoot) || !strings.HasSuffix(path, ".java") {
		return nil
	}
	f, err := os.Open(path)
	if err != nil {
		return []Finding{{Rule: "minsdk-api", Path: rel, Detail: "unreadable: " + err.Error()}}
	}
	defer func() { _ = f.Close() }() // read-only: nothing to lose on close
	var out []Finding
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 0, 64*1024), 1<<20)
	line := 0
	for sc.Scan() {
		line++
		text := strings.TrimSpace(sc.Text())
		if strings.HasPrefix(text, "//") || strings.HasPrefix(text, "*") || strings.HasPrefix(text, "/*") {
			continue
		}
		for _, d := range minSdkDenied {
			if d.pattern.MatchString(text) {
				out = append(out, Finding{Rule: "minsdk-api", Path: rel,
					Detail: "line " + strconv.Itoa(line) + ": " + d.why})
			}
		}
	}
	return out
}
