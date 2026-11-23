// Lumos - tools/lumoslint / No weak randomness and no TLS trust bypass in shipped code.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CRY-012, SRS-NFR-SEC-009
// ADRs: ADR-001
// Threading: any
// Notes: TRS-CRY-012 asks that every random value come from the platform
// CSPRNG, and SRS-NFR-SEC-009 that certificate validation use platform trust
// with no trust-all or hostname-verification bypass. Both are verified by
// static review; this rule makes that review run on every file of every
// change instead of once. It is a denylist of the ways each language reaches
// a non-cryptographic generator or switches validation off. Test code is
// exempt (a seeded generator in a test is a fixture, not a key), and so are
// the tools, which never ship. A real need - certificate pinning, say - is an
// ADR first and an exemption here second, never a quiet edit.
package main

import (
	"bufio"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
)

// hygieneDenied maps a file extension to the patterns refused there.
var hygieneDenied = map[string][]struct {
	pattern *regexp.Regexp
	why     string
}{
	".go": {
		{regexp.MustCompile(`"math/rand(/v2)?"`), "TRS-CRY-012: math/rand is not a CSPRNG; use crypto/rand"},
		{regexp.MustCompile(`\bInsecureSkipVerify\b`), "SRS-NFR-SEC-009: certificate verification may not be switched off"},
	},
	".java": {
		{regexp.MustCompile(`\bjava\.util\.Random\b|\bnew Random\(|\bMath\.random\(|\b(ThreadLocalRandom|SplittableRandom)\b`),
			"TRS-CRY-012: not a CSPRNG; use SecureRandom or libsodium"},
		{regexp.MustCompile(`\b(X509TrustManager|X509ExtendedTrustManager|HostnameVerifier|setHostnameVerifier|setDefaultHostnameVerifier|ALLOW_ALL_HOSTNAME_VERIFIER)\b`),
			"SRS-NFR-SEC-009: a custom trust manager or hostname verifier bypasses platform trust"},
	},
	".swift": {
		{regexp.MustCompile(`\b(drand48|rand|random)\(\)|\bsrand(48)?\(`), "TRS-CRY-012: libc generators are not a CSPRNG; use SystemRandomNumberGenerator"},
		{regexp.MustCompile(`\bURLCredential\(trust:|\.serverTrust\b|\bSecTrustSetAnchorCertificates\b`),
			"SRS-NFR-SEC-009: answering a trust challenge yourself bypasses platform trust"},
	},
	".ts": {
		{regexp.MustCompile(`\bMath\.random\(`), "TRS-CRY-012: Math.random is not a CSPRNG; use crypto.getRandomValues"},
		{regexp.MustCompile(`\brejectUnauthorized\s*:\s*false\b|NODE_TLS_REJECT_UNAUTHORIZED`),
			"SRS-NFR-SEC-009: certificate verification may not be switched off"},
	},
}

// isShippedSource reports whether a repository-relative path is product code:
// not a test, not a tool.
func isShippedSource(rel string) bool {
	rel = filepath.ToSlash(rel)
	switch {
	case strings.HasPrefix(rel, "tools/"),
		strings.HasSuffix(rel, "_test.go"),
		strings.Contains(rel, "/src/test/"), strings.Contains(rel, "/src/androidTest/"),
		strings.Contains(rel, "/Tests/"), strings.HasSuffix(rel, ".test.ts"), strings.HasSuffix(rel, ".spec.ts"):
		return false
	}
	return true
}

// CheckCryptoHygiene reports weak-random and trust-bypass uses in shipped code.
func CheckCryptoHygiene(root, path string) []Finding {
	rel, err := filepath.Rel(root, path)
	if err != nil || !isShippedSource(rel) {
		return nil
	}
	denied := hygieneDenied[strings.ToLower(filepath.Ext(path))]
	if len(denied) == 0 {
		return nil
	}
	f, err := os.Open(path)
	if err != nil {
		return []Finding{{Rule: "crypto-hygiene", Path: rel, Detail: "unreadable: " + err.Error()}}
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
		for _, d := range denied {
			if d.pattern.MatchString(text) {
				out = append(out, Finding{Rule: "crypto-hygiene", Path: rel,
					Detail: "line " + strconv.Itoa(line) + ": " + d.why})
			}
		}
	}
	return out
}
