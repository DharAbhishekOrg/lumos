// Lumos - tools/constantsgen / Cross-language check: compile and run the generated Java.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-REP-002, TRS-CONST-001, TRS-TST-001
// ADRs: ADR-004
// Threading: any
// Notes: This is the only WP-01 slice S2 assertion that proves a second language
// agrees with the register, so it compiles and EXECUTES the generated Java and
// reflects over the emitted fields rather than pattern-matching the source text.
// TRS-REP-002 is about a real build consuming generated constants; grepping the
// file would not test that. Swift's equivalent runs on the macOS CI host in S5.
package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"testing"
)

// javaTool locates a JDK binary, preferring JAVA_HOME so CI containers pin the
// same JDK the toolchain manifest records.
func javaTool(t *testing.T, name string) (string, bool) {
	t.Helper()
	if name == "" {
		return "", false
	}
	exe := name
	if runtime.GOOS == "windows" {
		exe += ".exe"
	}
	if home := os.Getenv("JAVA_HOME"); home != "" {
		p := filepath.Join(home, "bin", exe)
		if _, err := os.Stat(p); err == nil {
			return p, true
		}
	}
	if p, err := exec.LookPath(name); err == nil {
		return p, true
	}
	return "", false
}

const javaDumpProgram = `import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.MessageClass;
import com.lumos.protocol.TextEventKind;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Dump {
    public static void main(String[] args) throws Exception {
        List<String> out = new ArrayList<>();
        for (Field f : LumosConstants.class.getDeclaredFields()) {
            int mod = f.getModifiers();
            if (!Modifier.isStatic(mod) || !Modifier.isPublic(mod)) {
                continue;
            }
            out.add("C " + f.getName() + "=" + render(f.get(null)));
        }
        for (MessageClass v : MessageClass.values()) {
            out.add("M " + v.name() + "=" + v.code());
        }
        for (TextEventKind v : TextEventKind.values()) {
            out.add("K " + v.name() + "=" + v.code());
        }
        Collections.sort(out);
        for (String s : out) {
            System.out.println(s);
        }
    }

    private static String render(Object v) {
        if (v instanceof List<?> list) {
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    b.append(",");
                }
                b.append(list.get(i));
            }
            return b.append("]").toString();
        }
        return String.valueOf(v);
    }
}
`

func TestGeneratedJavaCompilesAndMatchesRegister(t *testing.T) {
	javac, ok := javaTool(t, "javac")
	if !ok {
		t.Skip("javac not found (set JAVA_HOME); the Java cross-language check is a CI gate")
	}
	java, ok := javaTool(t, "java")
	if !ok {
		t.Skip("java not found (set JAVA_HOME)")
	}

	root := repoRootPath(t)
	work := t.TempDir()
	classes := filepath.Join(work, "classes")
	if err := os.MkdirAll(classes, 0o755); err != nil {
		t.Fatalf("mkdir: %v", err)
	}

	dump := filepath.Join(work, "Dump.java")
	if err := os.WriteFile(dump, []byte(javaDumpProgram), 0o644); err != nil {
		t.Fatalf("write Dump.java: %v", err)
	}

	sources := []string{dump}
	for _, p := range []string{JavaConstOutPath, JavaClassOutPath, JavaEventKindOut} {
		sources = append(sources, filepath.Join(root, filepath.FromSlash(p)))
	}

	args := append([]string{"-Xlint:all", "-Werror", "-encoding", "UTF-8", "--release", "17", "-d", classes}, sources...)
	if out, err := exec.Command(javac, args...).CombinedOutput(); err != nil {
		t.Fatalf("javac failed (generated Java must compile warning-free under TRS-CODE-006):\n%s", out)
	}

	// -Dfile.encoding=UTF-8 keeps System.out UTF-8 on Windows JDK 17, so the
	// emoji alphabet survives the pipe byte-for-byte.
	out, err := exec.Command(java, "-Dfile.encoding=UTF-8", "-cp", classes, "Dump").CombinedOutput()
	if err != nil {
		t.Fatalf("running Dump failed:\n%s", out)
	}

	got := map[string]string{}
	for _, line := range strings.Split(strings.ReplaceAll(string(out), "\r\n", "\n"), "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		kv := strings.SplitN(line, "=", 2)
		if len(kv) != 2 {
			t.Fatalf("unparseable dump line %q", line)
		}
		got[kv[0]] = kv[1]
	}

	_, m, _ := buildModel(t)

	want := map[string]string{
		"C SERVICE_UUID":   m.ServiceUUID,
		"C CONSTANTS_HASH": m.ConstantsHash,
		"C SCHEMA_VERSION": strconv.FormatInt(m.SchemaVersion, 10),
		"C BASELINE":       m.Baseline,
	}
	for _, c := range m.Constants {
		key := "C " + c.Base
		switch c.Kind {
		case KindInt:
			want[key] = strconv.FormatInt(c.Int, 10)
		case KindBool:
			want[key] = strconv.FormatBool(c.Bool)
		case KindString:
			want[key] = c.Str
		case KindIntSlice:
			parts := make([]string, len(c.Ints))
			for i, v := range c.Ints {
				parts[i] = strconv.FormatInt(v, 10)
			}
			want[key] = "[" + strings.Join(parts, ",") + "]"
		case KindStringSlice:
			want[key] = "[" + strings.Join(c.Strs, ",") + "]"
		}
	}
	for _, mem := range m.MessageClass {
		want["M "+mem.Name] = strconv.FormatInt(mem.Code, 10)
	}
	for _, mem := range m.TextEventKind {
		want["K "+mem.Name] = strconv.FormatInt(mem.Code, 10)
	}

	var missing, wrong []string
	for k, w := range want {
		g, ok := got[k]
		if !ok {
			missing = append(missing, k)
			continue
		}
		if g != w {
			wrong = append(wrong, k+": java="+g+" register="+w)
		}
	}
	var extra []string
	for k := range got {
		if _, ok := want[k]; !ok {
			extra = append(extra, k)
		}
	}
	sort.Strings(missing)
	sort.Strings(wrong)
	sort.Strings(extra)

	for _, k := range missing {
		t.Errorf("generated Java is missing %s", k)
	}
	for _, k := range wrong {
		t.Errorf("value disagreement %s", k)
	}
	for _, k := range extra {
		t.Errorf("generated Java declares %s, which is not in the register", k)
	}
	if len(want) < 100 {
		t.Fatalf("only %d symbols compared; expected the full register", len(want))
	}
	t.Logf("verified %d generated Java symbols against the register by execution", len(want))
}
