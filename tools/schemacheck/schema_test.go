// Lumos - tools/schemacheck / The two platform schemas must agree.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-LOC-002, TRS-LOC-003, TRS-CODE-007
// ADRs: -
// Threading: any
// Notes: The extractor is tested against fabricated drift as well as against
// the real files, because a comparison tool that cannot fail is a comparison
// tool that proves nothing - and this one exists specifically to catch the
// case where both platforms' own suites stay green while the platforms
// disagree.
package schemacheck

import (
	"path/filepath"
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

func load(t *testing.T) (*Schema, *Schema) {
	t.Helper()
	root := repoRoot(t)
	java, err := LoadJava(root)
	if err != nil {
		t.Fatalf("load java schema: %v", err)
	}
	swift, err := LoadSwift(root)
	if err != nil {
		t.Fatalf("load swift schema: %v", err)
	}
	return java, swift
}

// TestBothPlatformsDeclareTheSameSchema is the gate itself.
func TestBothPlatformsDeclareTheSameSchema(t *testing.T) {
	java, swift := load(t)
	for _, difference := range Differences(java, swift) {
		t.Errorf("schema drift: %s", difference)
	}
}

// TestExtractionActuallyFoundSomething guards the extractor. A regex that
// silently matched nothing would make every comparison above vacuously true,
// which is the quiet-green failure this repository has met repeatedly.
func TestExtractionActuallyFoundSomething(t *testing.T) {
	java, swift := load(t)

	if java.Version == 0 || swift.Version == 0 {
		t.Errorf("no schema version parsed: java %d, swift %d", java.Version, swift.Version)
	}
	if len(java.Tables) == 0 || len(swift.Tables) == 0 {
		t.Fatalf("no record tables parsed: java %d, swift %d", len(java.Tables), len(swift.Tables))
	}
	if java.RecordTableDDL == "" || swift.RecordTableDDL == "" {
		t.Error("no CREATE TABLE skeleton parsed from one or both platforms")
	}
	for _, missing := range MissingRoles(java) {
		t.Errorf("java declaration is missing the %s role", missing)
	}
	for _, missing := range MissingRoles(swift) {
		t.Errorf("swift declaration is missing the %s role", missing)
	}
}

// TestTheWpTwoKindsArePresent pins the entity subset this work package
// persists, so a table quietly disappearing is a test failure rather than a
// smaller store. Seven kinds at slice S2; consent_record and
// discovery_visibility joined at S4c with schema version 2; app_lock joined
// at WP-03 S5b with schema version 3; hidden_peer joined at WP-04 S1a with
// schema version 4; interest_record, match_record and interest_budget
// joined at WP-04 S3 with schema version 5; conversation, chat_message,
// outbox_item, ratchet_session, message_dedup and prekey_secret joined at
// WP-05 S7b with schema version 6; relay_account joined at WP-05 S7c with
// schema version 7.
func TestTheWpTwoKindsArePresent(t *testing.T) {
	java, swift := load(t)
	want := []string{
		"app_lock", "block_entry", "chat_message", "consent_record", "conversation",
		"diagnostic_event", "discovery_visibility", "hidden_peer", "identity_key_record",
		"interest_budget", "interest_record", "local_profile", "match_record",
		"message_dedup", "outbox_item", "prekey_secret", "profile_field_policy",
		"profile_media", "ratchet_session", "recovery_record", "relay_account",
	}
	if !equalStrings(java.Tables, want) {
		t.Errorf("java tables = %v, want %v", java.Tables, want)
	}
	if !equalStrings(swift.Tables, want) {
		t.Errorf("swift tables = %v, want %v", swift.Tables, want)
	}
}

// TestDifferencesDetectsEveryKindOfDrift proves the comparison can fail.
func TestDifferencesDetectsEveryKindOfDrift(t *testing.T) {
	base := func() *Schema {
		return &Schema{
			Version:        1,
			Names:          map[string]string{"columnid": "id", "columnpayload": "payload"},
			Tables:         []string{"local_profile"},
			RecordTableDDL: "CREATE TABLE ( TEXT PRIMARY KEY NOT NULL)",
		}
	}

	cases := map[string]func(*Schema){
		"version":  func(s *Schema) { s.Version = 2 },
		"tables":   func(s *Schema) { s.Tables = []string{"local_profile", "extra"} },
		"ddl":      func(s *Schema) { s.RecordTableDDL = "CREATE TABLE ( TEXT NULL)" },
		"a column": func(s *Schema) { s.Names["columnid"] = "identifier" },
	}
	for name, drift := range cases {
		t.Run(name, func(t *testing.T) {
			java, swift := base(), base()
			drift(swift)
			if len(Differences(java, swift)) == 0 {
				t.Errorf("drift in %s went undetected", name)
			}
		})
	}

	if got := Differences(base(), base()); len(got) != 0 {
		t.Errorf("identical schemas reported as different: %v", got)
	}
}
