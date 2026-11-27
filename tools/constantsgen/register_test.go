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
	// TRS Sec. 9.1 framing, added in slice S3. Flag bit positions are an
	// implementation choice the TRS leaves open; the rest is normative.
	"framing": {
		"FRAME_FLAGS_RESERVED_MASK", "FRAME_FLAG_COMPRESSED", "FRAME_FLAG_CRITICAL",
		"FRAME_FLAG_LOCAL_ONLY", "FRAME_FLAG_RELAY_ELIGIBLE", "FRAME_HEADER_LEN_BYTES",
		"FRAME_MAGIC", "PROTOCOL_VERSION",
	},
	"crypto_protocol": {
		"ADV_AUTH_TAG_INFO", "ADV_EID_KDF_INFO", "ADV_KEY_KDF_INFO",
		"CHAIN_KEY_SEEDS", "CONFIG_SIGNING_INFO",
		// The HELLO_* and PEER_ID_* entries are not Sec. 23 transcriptions:
		// they fix the TRS-PROTO-017 session-bootstrap payloads and the
		// SRS-IDN-002 peer-identifier derivation under the TRS Sec. 19 AI
		// delivery rule (WP-03 S4), the same footing as the framing flag bits
		// and the FRAG_* layout.
		"HELLO_ACK_V1_LAYOUT", "HELLO_AUTH_INFO", "HELLO_V1_LAYOUT",
		// MATCH_ID_* fix the SRS-MAT-007 stable-match-identifier derivation
		// and the accept-accept race tiebreak under the TRS Sec. 19 AI
		// delivery rule (WP-04 S2) - the PEER_ID_* precedent one layer up.
		// INTEREST_*_V1_LAYOUT fix the class-3/4 consent-stage payloads
		// (WP-04 S5, TRS Sec. 19); the response layout's verdict alphabet is
		// deliberately accept|unmatch only - a decline is unencodable.
		"INTEREST_REQUEST_V1_LAYOUT", "INTEREST_RESPONSE_V1_LAYOUT",
		"MATCH_ID_KDF_INFO", "MATCH_ID_LEN",
		// MATCH_TOKEN_* fix the SRS-MAT-008 in-venue verification token
		// (WP-04 S4, TRS Sec. 19): the derivation prefix and the named,
		// colour-blind-safe pulse palette.
		"MATCH_TOKEN_COLORS", "MATCH_TOKEN_KDF_INFO",
		"MEDIA_OBJECT_KEY_INFO", "MEDIA_OBJECT_KEY_WRAP_INFO",
		"PEER_ID_KDF_INFO", "PEER_ID_LEN",
		"RATCHET_HEADER_LAYOUT", "RATCHET_MESSAGE_KDF_INFO",
		"RATCHET_ROOT_KDF_INFO",
		// RECOVERY_ARGON2ID is NOT from TRS Sec. 23. It was added in WP-02
		// slice S4 because ADR-003's content-only RecoveryRecord needs a
		// passphrase KDF and the specification names none - the same shape of
		// addition as NEARBY_SYNTHETIC_LOAD_TARGET, which came from EDP 4.1.
		// Listing it here is what keeps this test a transcription guard rather
		// than a rubber stamp: every entry is either in the TRS or has a
		// recorded reason for not being.
		"RECOVERY_ARGON2ID",
		"SAFETY_NUMBER_EMOJI_ALPHABET",
		"SAFETY_NUMBER_ITERATIONS", "SAFETY_NUMBER_KDF_INFO",
		// DEVICE_AUTH_SIGNING_INFO is NOT from TRS Sec. 23: TRS-BCK-007 requires
		// device-key-bound backend auth but names no signing string, so WP-05
		// S4 froze one here per TRS Sec. 19.
		"DEVICE_AUTH_SIGNING_INFO",
		"VOUCHER_SIGNING_INFO", "X3DH_F_PREFIX", "X3DH_IDENTITY_BINDING_INFO",
		"X3DH_KDF_INFO", "X3DH_OPK_ID_NONE", "X3DH_PREKEY_SIGNING_INFO",
		"X3DH_TRANSCRIPT_INFO",
	},
	"identity_discovery": {
		"ADV_AUTH_TAG_LEN", "ADV_V1_LAYOUT", "ADV_V1_SCAN_RESPONSE_LAYOUT",
		// INTEREST_COOLDOWN_SEC: the TRS states the SRS-MAT-011 limit and its
		// messaging but not how long the cooldown holds, so the hold is
		// frozen in the register (WP-04 S2, TRS Sec. 19).
		"EID_ROTATION_SEC",
		// GATT_LINK_IN_CHAR_UUID, GATT_LINK_OUT_CHAR_UUID and
		// HELLO_HANDSHAKE_TIMEOUT_SEC: ADR-002 fixes the service UUID but no
		// characteristics or handshake bound; added by CR-010
		// (owner-accepted 2026-11-28), design in ADR-013.
		"GATT_LINK_IN_CHAR_UUID", "GATT_LINK_OUT_CHAR_UUID", "HELLO_HANDSHAKE_TIMEOUT_SEC",
		"INTEREST_COOLDOWN_SEC", "INTEREST_RATE_LIMIT_PER_HOUR",
		"MAX_CONCURRENT_GATT_LINKS", "NEARBY_SYNTHETIC_LOAD_TARGET",
		"PRESENCE_AGEOUT_SEC", "RESCAN_LADDER", "RSSI_BANDS_DBM",
		"SCAN_WINDOW_FG_MS", "SCAN_WINDOW_VENUE_MS",
	},
	"sessions_remote_text": {
		// DEVICE_AUTH_CHALLENGE_TTL_SEC: TRS-BCK-007 states no challenge
		// lifetime, so WP-05 S4 froze one here per TRS Sec. 19.
		"CLOCK_SKEW_TOLERANCE_SEC", "DEDUP_CACHE", "DEVICE_AUTH_CHALLENGE_TTL_SEC",
		"MAX_SKIP_PER_CHAIN",
		"MESSAGE_ID_LEN_BYTES", "ONE_TIME_PREKEYS_UPLOADED",
		"PREKEY_REPLENISH_THRESHOLD",
		// RECEIPT_IDS_PER_ENVELOPE: the receipt batch bound, which TRS
		// Sec. 23 does not give; added by CR-005 (owner-accepted 2026-11-23).
		"RECEIPT_IDS_PER_ENVELOPE",
		"RELAY_ENVELOPE_TTL_DAYS",
		"RELAY_MAILBOX_QUOTA", "RELAY_TEXT_ENVELOPE_MAX_BYTES",
		// RELAY_SEND_RATE_PER_MIN: TRS-BCK-004 requires relay rate limits
		// but Sec. 23 gives no value; added by CR-004 (owner-accepted
		// 2026-11-22).
		"RELAY_SEND_RATE_PER_MIN",
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
		// The FRAG_* entries are not TRS Sec. 23 transcriptions: they fix the
		// TRS-PROTO-010 fragment layout under the TRS Sec. 19 AI delivery
		// rule (WP-03 S2), the same footing as the framing flag bits.
		"FRAG_FLAGS_RESERVED_MASK", "FRAG_FLAG_FIRST", "FRAG_FLAG_LAST",
		"FRAG_HEADER_LEN", "FRAG_MAX_FRAGMENTS", "FRAG_REASSEMBLY_MAX_BYTES",
		"FRAG_TOTAL_PREFIX_LEN",
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
	// onboarding_safety is NOT from TRS Sec. 23, which has no onboarding
	// section at all - none of these values crosses a device boundary. It was
	// added in WP-02 slice S4c for the same reason RECOVERY_ARGON2ID was added
	// in S4: ADR-004 forbids a magic number, SRS-NFR-LEG-007 fixes the adult
	// floor at 18, and those two together leave nowhere else for it to live.
	"onboarding_safety": {
		"ADULT_AGE_MINIMUM",
		// WP-03 S5: SRS-NBY-001's bounded visibility durations, fixed under
		// the TRS Sec. 19 AI delivery rule like every derivation entry.
		"VISIBLE_NEARBY_DURATION_SEC",
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
	// CR-004, as accepted by the owner.
	"sessions_remote_text.RELAY_SEND_RATE_PER_MIN.default":  120,
	"sessions_remote_text.RELAY_SEND_RATE_PER_MIN.min":      30,
	"sessions_remote_text.RELAY_SEND_RATE_PER_MIN.max":      600,
	"sessions_remote_text.RECEIPT_IDS_PER_ENVELOPE.default": 32,
	"sessions_remote_text.RECEIPT_IDS_PER_ENVELOPE.min":     1,
	"sessions_remote_text.RECEIPT_IDS_PER_ENVELOPE.max":     64,
	// CR-010, as accepted by the owner.
	"identity_discovery.HELLO_HANDSHAKE_TIMEOUT_SEC.default":   10,
	"identity_discovery.HELLO_HANDSHAKE_TIMEOUT_SEC.min":       5,
	"identity_discovery.HELLO_HANDSHAKE_TIMEOUT_SEC.max":       30,
	"mesh_store_forward.MESH_HOP_DEFAULT.default":              3,
	"mesh_store_forward.MESH_HOP_ABSOLUTE_CEILING.default":     5,
	"transport.BLE_TARGET_ATT_MTU.default":                     251,
	"transport.LAN_CHUNK_PAYLOAD_BYTES.default":                65536,
	"transport.QR_BOOTSTRAP_MAX_BYTES.default":                 1228,
	"transport.RETRY_BACKOFF.cap_ms":                           60000,
	"media.VOICE_NOTE.sample_rate_hz":                          24000,
	"media.VOICE_NOTE.max_sec":                                 120,
	"config_venue_ops.CONFIG_PACKAGE_MAX_KB.default":           512,
	"resource_budgets.COLD_LAUNCH_P95_MS.default":              2000,
	"resource_budgets.STEADY_STATE_RAM_MAX_MB.default":         220,
	"framing.FRAME_MAGIC.default":                              19533,
	"framing.PROTOCOL_VERSION.major":                           1,
	"framing.PROTOCOL_VERSION.minor":                           0,
	"framing.PROTOCOL_VERSION.encoded":                         16,
	"framing.FRAME_HEADER_LEN_BYTES.default":                   58,
	"framing.FRAME_FLAGS_RESERVED_MASK.default":                65520,
	"identity_discovery.ADV_V1_LAYOUT.total_len":               18,
	"identity_discovery.ADV_V1_LAYOUT.eid_len":                 12,
	"identity_discovery.ADV_V1_LAYOUT.auth_tag_len":            4,
	"identity_discovery.ADV_V1_SCAN_RESPONSE_LAYOUT.total_len": 6,
	"crypto_protocol.SAFETY_NUMBER_ITERATIONS.default":         5200,
	"crypto_protocol.CHAIN_KEY_SEEDS.message":                  1,
	"crypto_protocol.CHAIN_KEY_SEEDS.next":                     2,
	"crypto_protocol.RATCHET_HEADER_LAYOUT.total_len":          40,
	"crypto_protocol.X3DH_F_PREFIX.len":                        32,
	"crypto_protocol.X3DH_F_PREFIX.fill_byte":                  255,
	"crypto_protocol.X3DH_OPK_ID_NONE.default":                 4294967295,
	// Argon2id work factors, transcribed by hand so a slip in the register is
	// caught here rather than by a user whose export turns out cheap to crack.
	"crypto_protocol.RECOVERY_ARGON2ID.memory_kib":     65536,
	"crypto_protocol.RECOVERY_ARGON2ID.memory_kib_min": 32768,
	"crypto_protocol.RECOVERY_ARGON2ID.iterations":     3,
	"crypto_protocol.RECOVERY_ARGON2ID.iterations_min": 2,
	"crypto_protocol.RECOVERY_ARGON2ID.parallelism":    1,
	"crypto_protocol.RECOVERY_ARGON2ID.salt_bytes":     16,
	"crypto_protocol.RECOVERY_ARGON2ID.key_bytes":      32,
	// SRS-NFR-LEG-007: the adult-only 18+ gate applies globally.
	"onboarding_safety.ADULT_AGE_MINIMUM.years": 18,
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

// boundsUnspecified was the set of overridable constants for which TRS Sec. 23
// gave no numeric range. ADR-006 is Accepted and supplied bounds for all eleven,
// so the set is now EMPTY and must stay that way. The pin is deliberately kept
// rather than deleted: it is what stops a twelfth unbounded overridable constant
// from being introduced silently.
var boundsUnspecified = map[string]bool{}

// dutyBoundedEntries express their override range as a duty-cycle ceiling rather
// than per-field minima and maxima, because that is how TRS Sec. 23.1 bounds a
// scan window. VOICE_NOTE is listed for the same reason: TRS Sec. 23.5 fixes the
// codec profile and bounds only the duration, via sec_min/sec_max.
var dutyBoundedEntries = map[string]bool{
	"identity_discovery.SCAN_WINDOW_FG_MS":    true,
	"identity_discovery.SCAN_WINDOW_VENUE_MS": true,
	"media.VOICE_NOTE":                        true,
}

// unboundedValueFields returns the tunable numeric fields of an entry that state
// no range a signed config could be validated against (TRS-CONST-002).
func unboundedValueFields(path string, fields map[string]any) []string {
	if dutyBoundedEntries[path] {
		return nil
	}
	var missing []string
	for name := range fields {
		if excluded(name) || metaFieldNames[name] || isBoundFieldName(name) {
			continue
		}
		if _, numeric := intField(fields, name); !numeric {
			continue // strings and arrays are profile identity, not tunables
		}
		if name == "default" {
			_, lo := intField(fields, "min")
			_, hi := intField(fields, "max")
			if lo && hi {
				continue
			}
			missing = append(missing, name)
			continue
		}
		_, lo := intField(fields, name+"_min")
		_, hi := intField(fields, name+"_max")
		if !lo || !hi {
			missing = append(missing, name)
		}
	}
	sort.Strings(missing)
	return missing
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
			missing := unboundedValueFields(path, fields)
			if len(missing) == 0 {
				if flagged {
					t.Errorf("%s declares complete bounds and must not also claim bounds_unspecified", path)
				}
				continue
			}
			if !flagged {
				t.Errorf("%s is overridable but fields %v state no range a signed config can be validated against (TRS-CONST-002; ADR-006)", path, missing)
			}
		}
	}

	for path := range boundsUnspecified {
		if !gotFlagged[path] {
			t.Errorf("%s is expected to carry bounds_unspecified but does not", path)
		}
	}
	for path := range gotFlagged {
		if !boundsUnspecified[path] {
			t.Errorf("%s newly claims bounds_unspecified; ADR-006 is Accepted and every overridable constant must state real bounds", path)
		}
	}
}

// adr006Bounds transcribes the bounds the owner accepted in ADR-006, by hand,
// from the decision itself rather than from the register they were written into.
var adr006Bounds = map[string][2]int64{
	"identity_discovery.RESCAN_LADDER.step_pct":             {25, 100},
	"identity_discovery.RESCAN_LADDER.trigger_empty_cycles": {1, 4},
	"identity_discovery.RESCAN_LADDER.max_steps":            {1, 5},
	"identity_discovery.RSSI_BANDS_DBM.very_close_gte":      {-60, -45},
	"identity_discovery.RSSI_BANDS_DBM.nearby_gte":          {-85, -65},
	"transport.REASSEMBLY_TIMEOUT_SEC.ble":                  {60, 300},
	"transport.REASSEMBLY_TIMEOUT_SEC.lan":                  {15, 120},
	"sessions_remote_text.RELAY_MAILBOX_QUOTA.envelopes":    {200, 5000},
	"sessions_remote_text.RELAY_MAILBOX_QUOTA.bytes":        {2097152, 33554432},
	"sessions_remote_text.DEDUP_CACHE.ids":                  {5000, 50000},
	"sessions_remote_text.DEDUP_CACHE.days":                 {3, 30},
	"mesh_store_forward.STORE_FORWARD_QUOTA.envelopes":      {10, 200},
	"mesh_store_forward.STORE_FORWARD_QUOTA.bytes":          {524288, 8388608},
	"media.MEDIA_CACHE_QUOTA_MB.default":                    {128, 2048},
	"media.MEDIA_CACHE_QUOTA_MB.ttl_days":                   {7, 90},
	"config_venue_ops.SPONSOR_ASSET_MAX_KB.default":         {100, 512},
	"config_venue_ops.SPONSOR_ASSET_MAX_KB.cache_count":     {4, 16},
	"config_venue_ops.VENUE_ROOM_POST_RATE_PER_MIN.default": {3, 30},
	"config_venue_ops.VOUCHER_NONCE_CACHE.count":            {1000, 20000},
	"config_venue_ops.VOUCHER_NONCE_CACHE.days":             {30, 365},
	"config_venue_ops.SUPPORT_RING_BUFFER_MB.default":       {1, 16},
}

// TestADR006BoundsWereAppliedExactly checks each accepted bound landed with the
// value the owner specified, and that the shipped default sits inside it.
func TestADR006BoundsWereAppliedExactly(t *testing.T) {
	r := loadRegister(t)
	for valuePath, want := range adr006Bounds {
		cut := strings.LastIndex(valuePath, ".")
		entry, field := valuePath[:cut], valuePath[cut+1:]

		minPath, maxPath := entry+"."+field+"_min", entry+"."+field+"_max"
		if field == "default" {
			minPath, maxPath = entry+".min", entry+".max"
		}

		gotMin, err := lookupInt(r, minPath)
		if err != nil {
			t.Errorf("%s: %v", minPath, err)
			continue
		}
		gotMax, err := lookupInt(r, maxPath)
		if err != nil {
			t.Errorf("%s: %v", maxPath, err)
			continue
		}
		if gotMin != want[0] || gotMax != want[1] {
			t.Errorf("%s bounds = %d..%d, ADR-006 accepted %d..%d", valuePath, gotMin, gotMax, want[0], want[1])
		}

		value, err := lookupInt(r, valuePath)
		if err != nil {
			t.Errorf("%s: %v", valuePath, err)
			continue
		}
		if value < gotMin || value > gotMax {
			t.Errorf("%s default %d falls outside its accepted bounds %d..%d", valuePath, value, gotMin, gotMax)
		}
	}
}

// TestSafetyNumberFormatMatchesADR005 pins the accepted resolution: 6 emoji in
// total, not 6 pairs, from a 64-emoji alphabet.
func TestSafetyNumberFormatMatchesADR005(t *testing.T) {
	r := loadRegister(t)
	for path, want := range map[string]int64{
		"sessions_remote_text.SAFETY_NUMBER_FORMAT.digits":              60,
		"sessions_remote_text.SAFETY_NUMBER_FORMAT.emoji_count":         6,
		"sessions_remote_text.SAFETY_NUMBER_FORMAT.emoji_alphabet_size": 64,
	} {
		got, err := lookupInt(r, path)
		if err != nil {
			t.Errorf("%s: %v", path, err)
			continue
		}
		if got != want {
			t.Errorf("%s = %d, ADR-005 accepted %d", path, got, want)
		}
	}
}

// boundValueField maps a bound prefix to the value field it constrains. TRS
// Sec. 23.5 names the voice-note duration bounds sec_min/sec_max while the value
// itself is max_sec, so the prefix and the value field do not coincide there.
func boundValueField(base string) string {
	if base == "sec" {
		return "max_sec"
	}
	return base
}

// TestEveryBoundTripleIsCoherent enforces min <= value <= max for every
// per-field bound triple, not just the default/min/max form.
func TestEveryBoundTripleIsCoherent(t *testing.T) {
	r := loadRegister(t)
	checked := 0
	for _, group := range GroupOrder {
		entries := r.Decode[group].(map[string]any)
		for name, raw := range entries {
			if excluded(name) {
				continue
			}
			fields := raw.(map[string]any)
			for field := range fields {
				if !strings.HasSuffix(field, "_min") {
					continue
				}
				base := strings.TrimSuffix(field, "_min")
				lo, okLo := intField(fields, field)
				hi, okHi := intField(fields, base+"_max")
				val, okVal := intField(fields, boundValueField(base))
				if !okLo || !okHi {
					t.Errorf("%s.%s.%s has no matching _max", group, name, base)
					continue
				}
				if !okVal {
					t.Errorf("%s.%s.%s declares bounds but has no value", group, name, base)
					continue
				}
				if lo > hi {
					t.Errorf("%s.%s.%s: min %d > max %d", group, name, base, lo, hi)
				}
				if val < lo || val > hi {
					t.Errorf("%s.%s.%s: value %d outside %d..%d", group, name, base, val, lo, hi)
				}
				checked++
			}
		}
	}
	if checked < 15 {
		t.Errorf("only %d per-field bound triples checked; ADR-006 introduced more", checked)
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
