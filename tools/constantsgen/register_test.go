// Lumos - tools/constantsgen / Tests that the register faithfully encodes TRS Sec. 23.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CONST-001, TRS-CONST-002, TRS-CONST-003, TRS-CONST-005
// ADRs: ADR-002, ADR-004
// Threading: any
// Notes: expectedRegister and goldenValues are transcribed independently from
// TRS Sec. 23 and EDP Sec. 4 rather than read back out of the JSON. A test that
// derives its expectations from the artefact under test proves only that the
// file equals itself, which is exactly the failure mode ADR-004 exists to stop.
package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"testing"
)

func registerPath(t *testing.T) string {
	t.Helper()
	p, err := filepath.Abs(filepath.Join("..", "..", filepath.FromSlash(RegisterPath)))
	if err != nil {
		t.Fatalf("resolve register path: %v", err)
	}
	return p
}

func loadRegister(t *testing.T) *Register {
	t.Helper()
	r, err := LoadRegister(registerPath(t))
	if err != nil {
		t.Fatalf("LoadRegister: %v", err)
	}
	return r
}

// expectedRegister is TRS Sec. 23 (plus the EDP Sec. 4.1 row TRS Sec. 23 omits),
// transcribed by hand.
var expectedRegister = map[string][]string{
	"identity_discovery": {
		"ADV_AUTH_TAG_LEN", "EID_ROTATION_SEC", "INTEREST_RATE_LIMIT_PER_HOUR",
		"MAX_CONCURRENT_GATT_LINKS", "NEARBY_SYNTHETIC_LOAD_TARGET",
		"PRESENCE_AGEOUT_SEC", "RESCAN_LADDER", "RSSI_BANDS_DBM",
		"SCAN_WINDOW_FG_MS", "SCAN_WINDOW_VENUE_MS",
	},
	"sessions_remote_text": {
		"CLOCK_SKEW_TOLERANCE_SEC", "DEDUP_CACHE", "MAX_SKIP_PER_CHAIN",
		"MESSAGE_ID_LEN_BYTES", "ONE_TIME_PREKEYS_UPLOADED",
		"PREKEY_REPLENISH_THRESHOLD", "RELAY_ENVELOPE_TTL_DAYS",
		"RELAY_MAILBOX_QUOTA", "RELAY_TEXT_ENVELOPE_MAX_BYTES",
		"SAFETY_NUMBER_FORMAT", "SESSION_ID_LEN_BYTES",
		"SIGNED_PREKEY_ROTATION_DAYS", "SKIPPED_KEYS_PER_SESSION_CAP",
		"SKIPPED_KEY_TTL_DAYS",
	},
	"mesh_store_forward": {
		"MESH_FRAME_TTL_SEC", "MESH_HOP_ABSOLUTE_CEILING", "MESH_HOP_DEFAULT",
		"STORE_FORWARD_QUOTA", "STORE_FORWARD_TTL_SEC",
	},
	"transport": {
		"BLE_CHUNK_PAYLOAD", "BLE_TARGET_ATT_MTU", "CIRCUIT_BREAKER",
		"LAN_CHUNK_PAYLOAD_BYTES", "QR_BOOTSTRAP_MAX_BYTES",
		"REASSEMBLY_TIMEOUT_SEC", "RETRY_BACKOFF", "RETRY_BUDGET_PER_PATH",
	},
	"media": {
		"FILE_MAX_MB", "FULL_IMAGE_MAX_MB", "MEDIA_CACHE_QUOTA_MB",
		"PREVIEW_MAX_KB", "THUMBNAIL_MAX_KB", "VOICE_NOTE",
	},
	"config_venue_ops": {
		"CONFIG_PACKAGE_MAX_KB", "CONFIG_REFRESH_INTERVAL_HOURS",
		"SPONSOR_ASSET_MAX_KB", "SUPPORT_RING_BUFFER_MB",
		"VENUE_ROOM_POST_RATE_PER_MIN", "VOUCHER_NONCE_CACHE",
	},
	"resource_budgets": {
		"ACTIVE_VENUE_BATTERY_MAX_PCT_30MIN", "ANDROID_DOWNLOAD_MAX_MB",
		"COLD_LAUNCH_P95_MS", "IDLE_BACKGROUND", "IOS_DOWNLOAD_MAX_MB",
		"STEADY_STATE_RAM_MAX_MB",
	},
}

// goldenValues are normative numbers transcribed from TRS Sec. 23 / EDP Sec. 4.
// A transcription slip here is a release-blocking protocol defect, so they are
// asserted explicitly rather than trusted.
var goldenValues = map[string]int64{
	"identity_discovery.EID_ROTATION_SEC.default":                600,
	"identity_discovery.EID_ROTATION_SEC.min":                    300,
	"identity_discovery.EID_ROTATION_SEC.max":                    900,
	"identity_discovery.ADV_AUTH_TAG_LEN.default":                4,
	"identity_discovery.PRESENCE_AGEOUT_SEC.default":             90,
	"identity_discovery.RSSI_BANDS_DBM.very_close_gte":           -55,
	"identity_discovery.RSSI_BANDS_DBM.nearby_gte":               -75,
	"identity_discovery.MAX_CONCURRENT_GATT_LINKS.default":       4,
	"identity_discovery.NEARBY_SYNTHETIC_LOAD_TARGET.default":    100,
	"sessions_remote_text.MAX_SKIP_PER_CHAIN.default":            512,
	"sessions_remote_text.SKIPPED_KEYS_PER_SESSION_CAP.default":  2000,
	"sessions_remote_text.MESSAGE_ID_LEN_BYTES.default":          16,
	"sessions_remote_text.SESSION_ID_LEN_BYTES.default":          16,
	"sessions_remote_text.RELAY_TEXT_ENVELOPE_MAX_BYTES.default": 32768,
	"sessions_remote_text.CLOCK_SKEW_TOLERANCE_SEC.default":      300,
	"sessions_remote_text.SAFETY_NUMBER_FORMAT.digits":           60,
	"mesh_store_forward.MESH_HOP_DEFAULT.default":                3,
	"mesh_store_forward.MESH_HOP_ABSOLUTE_CEILING.default":       5,
	"transport.BLE_TARGET_ATT_MTU.default":                       251,
	"transport.LAN_CHUNK_PAYLOAD_BYTES.default":                  65536,
	"transport.QR_BOOTSTRAP_MAX_BYTES.default":                   1228,
	"transport.RETRY_BACKOFF.cap_ms":                             60000,
	"media.VOICE_NOTE.sample_rate_hz":                            24000,
	"media.VOICE_NOTE.max_sec":                                   120,
	"config_venue_ops.CONFIG_PACKAGE_MAX_KB.default":             512,
	"resource_budgets.COLD_LAUNCH_P95_MS.default":                2000,
	"resource_budgets.STEADY_STATE_RAM_MAX_MB.default":           220,
}

func TestRegisterCoversTRSSection23(t *testing.T) {
	r := loadRegister(t)
	for group, wantNames := range expectedRegister {
		raw, ok := r.Decode[group]
		if !ok {
			t.Errorf("register is missing group %q", group)
			continue
		}
		entries, ok := raw.(map[string]any)
		if !ok {
			t.Errorf("group %q is not an object", group)
			continue
		}
		var got []string
		for name := range entries {
			if !excluded(name) {
				got = append(got, name)
			}
		}
		sort.Strings(got)
		want := append([]string(nil), wantNames...)
		sort.Strings(want)
		if strings.Join(got, ",") != strings.Join(want, ",") {
			t.Errorf("group %q constants mismatch:\n got: %v\nwant: %v", group, got, want)
		}
	}
	for group := range r.Decode {
		if excluded(group) {
			continue
		}
		switch group {
		case "schema_version", "baseline", "service_uuid", "constants_hash",
			"constants_hash_algorithm", "message_classes":
			continue
		}
		if _, known := expectedRegister[group]; !known {
			t.Errorf("register has unexpected group %q", group)
		}
	}
}

func TestGoldenValues(t *testing.T) {
	r := loadRegister(t)
	for path, want := range goldenValues {
		got, err := lookupInt(r, path)
		if err != nil {
			t.Errorf("%s: %v", path, err)
			continue
		}
		if got != want {
			t.Errorf("%s = %d, TRS Sec. 23 says %d", path, got, want)
		}
	}
}

func lookupInt(r *Register, dotted string) (int64, error) {
	var cur any = r.Decode
	for _, seg := range strings.Split(dotted, ".") {
		m, ok := cur.(map[string]any)
		if !ok {
			return 0, fmt.Errorf("path segment %q is not reachable", seg)
		}
		cur, ok = m[seg]
		if !ok {
			return 0, fmt.Errorf("missing %q", seg)
		}
	}
	n, ok := cur.(json.Number)
	if !ok {
		return 0, fmt.Errorf("not a number")
	}
	return n.Int64()
}

// TestBoundsAreCoherent enforces min <= default <= max wherever bounds exist,
// which is the precondition for TRS-CONST-002 override validation.
func TestBoundsAreCoherent(t *testing.T) {
	r := loadRegister(t)
	for _, group := range GroupOrder {
		entries := r.Decode[group].(map[string]any)
		for name, raw := range entries {
			if excluded(name) {
				continue
			}
			fields := raw.(map[string]any)
			def, hasDef := intField(fields, "default")
			min, hasMin := intField(fields, "min")
			max, hasMax := intField(fields, "max")
			if hasMin && hasMax && min > max {
				t.Errorf("%s.%s: min %d > max %d", group, name, min, max)
			}
			if hasDef && hasMin && def < min {
				t.Errorf("%s.%s: default %d < min %d", group, name, def, min)
			}
			if hasDef && hasMax && def > max {
				t.Errorf("%s.%s: default %d > max %d", group, name, def, max)
			}
			if (hasMin || hasMax) && !hasDef {
				t.Errorf("%s.%s: has bounds but no default", group, name)
			}
		}
	}
}

// boundsUnspecified is the exact set of overridable constants for which TRS
// Sec. 23 gives no numeric range, only the words "bounded" or "calibrated".
// See docs/adr/ADR-006-draft-unspecified-override-bounds.md. Pinning the set
// here means a new unbounded overridable constant cannot be added silently.
var boundsUnspecified = map[string]bool{
	"identity_discovery.RESCAN_LADDER":              true,
	"identity_discovery.RSSI_BANDS_DBM":             true,
	"sessions_remote_text.RELAY_MAILBOX_QUOTA":      true,
	"sessions_remote_text.DEDUP_CACHE":              true,
	"mesh_store_forward.STORE_FORWARD_QUOTA":        true,
	"transport.REASSEMBLY_TIMEOUT_SEC":              true,
	"media.MEDIA_CACHE_QUOTA_MB":                    true,
	"config_venue_ops.SPONSOR_ASSET_MAX_KB":         true,
	"config_venue_ops.VENUE_ROOM_POST_RATE_PER_MIN": true,
	"config_venue_ops.VOUCHER_NONCE_CACHE":          true,
	"config_venue_ops.SUPPORT_RING_BUFFER_MB":       true,
}

// hasNumericBounds reports whether an entry states a range a signed config can
// be validated against. TRS Sec. 23 expresses ranges three ways: min/max on a
// scalar, duty-cycle percentages on a scan window, and sec_min/sec_max on the
// voice-note duration.
func hasNumericBounds(fields map[string]any) bool {
	for _, pair := range [][2]string{
		{"min", "max"},
		{"duty_min_pct", "duty_max_pct"},
		{"sec_min", "sec_max"},
	} {
		_, lo := intField(fields, pair[0])
		_, hi := intField(fields, pair[1])
		if lo && hi {
			return true
		}
	}
	if _, only := intField(fields, "duty_max_pct"); only {
		return true // SCAN_WINDOW_VENUE states a ceiling only
	}
	return false
}

// TestEveryOverridableEntryDeclaresBoundsOrTheGap enforces TRS-CONST-002. An
// overridable constant must either state the range a signed config may move it
// within, or explicitly admit that TRS Sec. 23 does not state one. Inventing a
// plausible range would write normative policy under the guise of transcription,
// so the gap is recorded rather than filled.
func TestEveryOverridableEntryDeclaresBoundsOrTheGap(t *testing.T) {
	r := loadRegister(t)
	gotFlagged := map[string]bool{}

	for _, group := range GroupOrder {
		entries := r.Decode[group].(map[string]any)
		for name, raw := range entries {
			if excluded(name) {
				continue
			}
			path := group + "." + name
			fields := raw.(map[string]any)

			ovr, ok := fields["overridable"].(bool)
			if !ok {
				t.Errorf("%s: missing an explicit overridable flag", path)
				continue
			}
			flagged, _ := fields["bounds_unspecified"].(bool)
			if flagged {
				gotFlagged[path] = true
			}
			if !ovr {
				if flagged {
					t.Errorf("%s is not overridable, so bounds_unspecified is meaningless", path)
				}
				continue
			}
			if hasNumericBounds(fields) {
				if flagged {
					t.Errorf("%s declares numeric bounds and must not also claim bounds_unspecified", path)
				}
				continue
			}
			if !flagged {
				t.Errorf("%s is overridable but declares neither numeric bounds nor bounds_unspecified (TRS-CONST-002; see ADR-006 draft)", path)
			}
		}
	}

	for path := range boundsUnspecified {
		if !gotFlagged[path] {
			t.Errorf("%s is expected to carry bounds_unspecified per ADR-006 draft but does not", path)
		}
	}
	for path := range gotFlagged {
		if !boundsUnspecified[path] {
			t.Errorf("%s newly claims bounds_unspecified; supply real bounds or extend ADR-006 deliberately", path)
		}
	}
}

func intField(fields map[string]any, key string) (int64, bool) {
	n, ok := fields[key].(json.Number)
	if !ok {
		return 0, false
	}
	v, err := n.Int64()
	if err != nil {
		return 0, false
	}
	return v, true
}

// TestNoFloatingPointValues protects the hash algorithm's stated precondition.
func TestNoFloatingPointValues(t *testing.T) {
	raw, err := os.ReadFile(registerPath(t))
	if err != nil {
		t.Fatalf("read register: %v", err)
	}
	if _, err := loadRegister(t).CanonicalForm(); err != nil {
		t.Fatalf("canonical form rejected the register: %v", err)
	}
	if regexp.MustCompile(`:\s*-?\d+\.\d`).Match(raw) {
		t.Error("register contains a floating-point literal; the canonical form defines no float rendering")
	}
}

func TestServiceUUIDIsFrozenAndWellFormed(t *testing.T) {
	r := loadRegister(t)
	u, err := r.String("service_uuid")
	if err != nil {
		t.Fatalf("service_uuid: %v", err)
	}
	if u != "E853EB6A-775C-4A49-A6BF-320E6A5CC609" {
		t.Errorf("service_uuid changed to %q; ADR-002 freezes this value and changing it is a breaking protocol change", u)
	}
	if !regexp.MustCompile(`^[0-9A-F]{8}-[0-9A-F]{4}-4[0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12}$`).MatchString(u) {
		t.Errorf("service_uuid %q is not an uppercase RFC 4122 version-4 UUID", u)
	}
}

// TestDeclaredHashMatchesContent is the TRS-CONST-005 drift gate.
func TestDeclaredHashMatchesContent(t *testing.T) {
	r := loadRegister(t)
	computed, err := r.ConstantsHash()
	if err != nil {
		t.Fatalf("ConstantsHash: %v", err)
	}
	declared, err := r.DeclaredHash()
	if err != nil {
		t.Fatalf("DeclaredHash: %v", err)
	}
	if declared != computed {
		t.Errorf("constants_hash is stale:\n declared %s\n computed %s\nrun: go run ./tools/constantsgen -stamp", declared, computed)
	}
	if !strings.HasPrefix(declared, HashPrefix) {
		t.Errorf("constants_hash %q lacks the %q prefix", declared, HashPrefix)
	}
}

// TestCanonicalFormExcludesDocumentation proves that editing prose does not move
// the hash, and that editing a value does.
func TestCanonicalFormExcludesDocumentation(t *testing.T) {
	r := loadRegister(t)
	canon, err := r.CanonicalForm()
	if err != nil {
		t.Fatalf("CanonicalForm: %v", err)
	}
	for _, forbidden := range []string{"anchors", "$comment", "constants_hash=", "notes="} {
		if strings.Contains(canon, forbidden) {
			t.Errorf("canonical form leaks documentation field %q", forbidden)
		}
	}
	for _, required := range []string{
		"identity_discovery.EID_ROTATION_SEC.default=600",
		"identity_discovery.RSSI_BANDS_DBM.very_close_gte=-55",
		"media.VOICE_NOTE.bitrate_kbps=[24,32]",
		"message_classes.classes.0.code=1",
		"service_uuid=E853EB6A-775C-4A49-A6BF-320E6A5CC609",
	} {
		if !strings.Contains(canon, required) {
			t.Errorf("canonical form is missing line %q", required)
		}
	}
	if !strings.HasSuffix(canon, "\n") || strings.HasSuffix(canon, "\n\n") {
		t.Error("canonical form must end with exactly one trailing newline")
	}
	if !sort.StringsAreSorted(strings.Split(strings.TrimSuffix(canon, "\n"), "\n")) {
		t.Error("canonical form lines are not sorted")
	}
}

// TestHashIsSensitiveToValueChanges is the negative half of the drift gate.
func TestHashIsSensitiveToValueChanges(t *testing.T) {
	original := loadRegister(t)
	baseline, err := original.ConstantsHash()
	if err != nil {
		t.Fatalf("ConstantsHash: %v", err)
	}

	raw, err := os.ReadFile(registerPath(t))
	if err != nil {
		t.Fatalf("read register: %v", err)
	}

	t.Run("value change moves the hash", func(t *testing.T) {
		tampered := strings.Replace(string(raw), `"default": 600, "min": 300, "max": 900`, `"default": 601, "min": 300, "max": 900`, 1)
		if tampered == string(raw) {
			t.Fatal("tamper fixture did not apply")
		}
		got := hashOfSource(t, tampered)
		if got == baseline {
			t.Error("changing EID_ROTATION_SEC did not move constants_hash")
		}
	})

	t.Run("comment change does not move the hash", func(t *testing.T) {
		tampered := strings.Replace(string(raw), `"source": "TRS 23.1"`, `"source": "TRS 23.1 (reworded)"`, 1)
		if tampered == string(raw) {
			t.Fatal("tamper fixture did not apply")
		}
		if got := hashOfSource(t, tampered); got != baseline {
			t.Error("editing a documentation-only field moved constants_hash")
		}
	})
}

func hashOfSource(t *testing.T, src string) string {
	t.Helper()
	dir := t.TempDir()
	p := filepath.Join(dir, "lumos_constants.json")
	if err := os.WriteFile(p, []byte(src), 0o644); err != nil {
		t.Fatalf("write fixture: %v", err)
	}
	r, err := LoadRegister(p)
	if err != nil {
		t.Fatalf("LoadRegister fixture: %v", err)
	}
	h, err := r.ConstantsHash()
	if err != nil {
		t.Fatalf("ConstantsHash fixture: %v", err)
	}
	return h
}
