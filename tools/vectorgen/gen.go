// Lumos - tools/vectorgen / Deterministic golden-vector builder (TRS Sec. 18.4).
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-TST-001, TRS-PROTO-014, TRS-PROTO-015, TRS-CONST-005, SRS-NFR-CMP-004
// ADRs: ADR-004, ADR-007
// Threading: any
// Notes: Single generator, three consumers - this tool is the only program that
// writes under /test-vectors, and Java/Swift only ever consume. Everything is
// derived from fixed TESTONLY- labels through SHA-256; there is no clock, no
// CSPRNG and no map-order dependence anywhere in the build path, which is what
// the double-generate byte-compare check relies on.
package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"

	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekorg/lumos/protocol/schema"
	"github.com/dharabhishekorg/lumos/protocol/wire"
)

// KeyProvenance names the deterministic seed namespace. Every derived byte in
// the suite traces to this prefix; nothing here is, or ever becomes, a real key.
const KeyProvenance = "TESTONLY-lumos-vectors-v1"

// GeneratorName is recorded in every emitted file.
const GeneratorName = "tools/vectorgen"

// VectorFile is one emitted artefact.
type VectorFile struct {
	Path       string
	Implements []string
	Content    []byte
}

// fileMeta is the common leading metadata of every vector file (TRS-CONST-005).
type fileMeta struct {
	SchemaVersion int      `json:"schema_version"`
	Generator     string   `json:"generator"`
	ConstantsHash string   `json:"constants_hash"`
	Implements    []string `json:"implements"`
	KeyProvenance string   `json:"key_provenance"`
	Notes         string   `json:"notes"`
}

// testonlyBytes derives n deterministic bytes from a label under KeyProvenance.
func testonlyBytes(label string, n int) []byte {
	out := make([]byte, 0, n+sha256.Size)
	for counter := 0; len(out) < n; counter++ {
		h := sha256.Sum256(fmt.Appendf(nil, "%s|%s|%d", KeyProvenance, label, counter))
		out = append(out, h[:]...)
	}
	return out[:n]
}

func testonly16(label string) (id [16]byte) {
	copy(id[:], testonlyBytes(label, 16))
	return id
}

// ---- framing/frame_roundtrip.json ----

type roundtripFields struct {
	Version       int    `json:"version"`
	MessageType   int    `json:"message_type"`
	Flags         int    `json:"flags"`
	SessionIDHex  string `json:"session_id_hex"`
	MessageIDHex  string `json:"message_id_hex"`
	Sequence      uint32 `json:"sequence"`
	AckBase       uint32 `json:"ack_base"`
	PayloadLength int    `json:"payload_length"`
	ExpirySeconds uint32 `json:"expiry_seconds"`
	HopLimit      int    `json:"hop_limit"`
	PayloadHex    string `json:"payload_hex"`
}

type roundtripExpect struct {
	MessageTypeKnown bool `json:"message_type_known"`
}

type roundtripCase struct {
	Name        string          `json:"name"`
	Description string          `json:"description"`
	DecodeOnly  bool            `json:"decode_only"`
	Fields      roundtripFields `json:"fields"`
	FrameHex    string          `json:"frame_hex"`
	Expect      roundtripExpect `json:"expect"`
}

type roundtripFile struct {
	fileMeta
	Cases []roundtripCase `json:"cases"`
}

// classFlags gives each class a representative, deterministic flag setting so
// the suite exercises every ADR-007 bit at least once.
func classFlags(name string) uint16 {
	switch name {
	case "TEXT_MESSAGE":
		return lumosconstants.FrameFlagCriticalDefault
	case "MESH_RELAY", "STORE_FORWARD":
		return lumosconstants.FrameFlagRelayEligibleDefault
	case "OBJECT_CHUNK":
		return lumosconstants.FrameFlagLocalOnlyDefault
	case "VOICE_META":
		return lumosconstants.FrameFlagCriticalDefault | lumosconstants.FrameFlagLocalOnlyDefault
	}
	return 0
}

func classPayloadLen(name string) int {
	switch name {
	case "HEARTBEAT":
		return 0
	case "OBJECT_CHUNK":
		return 64
	}
	return 24
}

func classHopLimit(name string) uint8 {
	switch name {
	case "MESH_RELAY", "STORE_FORWARD":
		return lumosconstants.MeshHopDefaultDefault
	}
	return 0
}

// buildFrame encodes one frame through the canonical codec and returns the case.
func buildFrame(name string, class lumosconstants.MessageClass, seq uint32) (roundtripCase, error) {
	flags := classFlags(name)
	payload := testonlyBytes("payload|"+name, classPayloadLen(name))
	f := &wire.Frame{
		Header: wire.Header{
			Type:          class,
			Flags:         flags,
			SessionID:     testonly16("session|" + name),
			MessageID:     testonly16("message|" + name),
			Sequence:      seq,
			ExpirySeconds: lumosconstants.MeshFrameTTLSecDefault,
			HopLimit:      classHopLimit(name),
		},
		Payload: payload,
	}
	encoded, err := wire.Encode(f)
	if err != nil {
		return roundtripCase{}, fmt.Errorf("encode %s: %w", name, err)
	}
	return roundtripCase{
		Name:        name,
		Description: "Canonical " + name + " frame: fields -> exact bytes (TRS Sec. 9.1/9.2).",
		Fields: roundtripFields{
			Version:       int(lumosconstants.ProtocolVersionEncoded),
			MessageType:   int(class),
			Flags:         int(flags),
			SessionIDHex:  hex.EncodeToString(f.Header.SessionID[:]),
			MessageIDHex:  hex.EncodeToString(f.Header.MessageID[:]),
			Sequence:      seq,
			AckBase:       0,
			PayloadLength: len(payload),
			ExpirySeconds: lumosconstants.MeshFrameTTLSecDefault,
			HopLimit:      int(classHopLimit(name)),
			PayloadHex:    hex.EncodeToString(payload),
		},
		FrameHex: hex.EncodeToString(encoded),
		Expect:   roundtripExpect{MessageTypeKnown: true},
	}, nil
}

// decodeOnlyCase mutates a valid HELLO frame into one of the skippable
// conditions of TRS-PROTO-006 / ADR-007 and records the expected decode result.
func decodeOnlyCase(name, description string, mutate func([]byte) roundtripFields) (roundtripCase, error) {
	base, err := buildFrame("HELLO", lumosconstants.MessageClassHello, 1)
	if err != nil {
		return roundtripCase{}, err
	}
	raw, err := hex.DecodeString(base.FrameHex)
	if err != nil {
		return roundtripCase{}, err
	}
	fields := mutate(raw)
	known := lumosconstants.MessageClass(fields.MessageType).Known()
	return roundtripCase{
		Name:        name,
		Description: description,
		DecodeOnly:  true,
		Fields:      fields,
		FrameHex:    hex.EncodeToString(raw),
		Expect:      roundtripExpect{MessageTypeKnown: known},
	}, nil
}

func buildRoundtrips(reg *schema.Register, constantsHash string) (*roundtripFile, error) {
	names, err := reg.MessageClassNames()
	if err != nil {
		return nil, err
	}
	file := &roundtripFile{
		fileMeta: fileMeta{
			SchemaVersion: 1,
			Generator:     GeneratorName,
			ConstantsHash: constantsHash,
			Implements:    []string{"TRS-PROTO-001", "TRS-PROTO-002", "TRS-PROTO-004", "TRS-PROTO-014", "SRS-NFR-CMP-004"},
			KeyProvenance: KeyProvenance,
			Notes: "Positive vectors: every TRS Sec. 9.2 message class, fields -> exact bytes. " +
				"decode_only cases cover the skippable conditions of TRS-PROTO-006 and the " +
				"ADR-007 minor-version rule; consumers assert decode succeeds with the given fields.",
		},
	}

	byName := map[string]lumosconstants.MessageClass{}
	for mc, n := range lumosconstants.MessageClassNames {
		byName[n] = mc
	}
	for i, name := range names {
		class, ok := byName[name]
		if !ok {
			return nil, fmt.Errorf("register class %s has no generated enum member", name)
		}
		c, err := buildFrame(name, class, uint32(i+1))
		if err != nil {
			return nil, err
		}
		file.Cases = append(file.Cases, c)
	}

	decodeOnly := []struct {
		name, description string
		mutate            func([]byte) roundtripFields
	}{
		{"minor_version_accepted",
			"Version 0x11 (v1.1): differing MINOR nibble is accepted per ADR-007.",
			func(raw []byte) roundtripFields { raw[2] = 0x11; return fieldsFromRaw(raw) }},
		{"unknown_type_noncritical_skippable",
			"Unknown class 0x0F without the critical flag decodes and is skipped by the caller (TRS-PROTO-006).",
			func(raw []byte) roundtripFields { raw[3] = 0x0F; return fieldsFromRaw(raw) }},
		{"reserved_flag_noncritical_ignored",
			"Reserved flag bit 8 set without the critical flag is ignored (ADR-007).",
			func(raw []byte) roundtripFields { raw[4], raw[5] = 0x01, 0x00; return fieldsFromRaw(raw) }},
		{"reserved_byte_noncritical_ignored",
			"Non-zero reserved header byte without the critical flag is ignored (TRS Sec. 9.1).",
			func(raw []byte) roundtripFields { raw[57] = 0x01; return fieldsFromRaw(raw) }},
	}
	for _, d := range decodeOnly {
		c, err := decodeOnlyCase(d.name, d.description, d.mutate)
		if err != nil {
			return nil, err
		}
		file.Cases = append(file.Cases, c)
	}
	return file, nil
}

// fieldsFromRaw re-reads the mutated header so the recorded fields always match
// the bytes, whatever the mutation did.
func fieldsFromRaw(raw []byte) roundtripFields {
	return roundtripFields{
		Version:       int(raw[2]),
		MessageType:   int(raw[3]),
		Flags:         int(raw[4])<<8 | int(raw[5]),
		SessionIDHex:  hex.EncodeToString(raw[8:24]),
		MessageIDHex:  hex.EncodeToString(raw[24:40]),
		Sequence:      beU32(raw[40:44]),
		AckBase:       beU32(raw[44:48]),
		PayloadLength: int(beU32(raw[48:52])),
		ExpirySeconds: beU32(raw[52:56]),
		HopLimit:      int(raw[56]),
		PayloadHex:    hex.EncodeToString(raw[lumosconstants.FrameHeaderLenBytesDefault:]),
	}
}

func beU32(b []byte) uint32 {
	return uint32(b[0])<<24 | uint32(b[1])<<16 | uint32(b[2])<<8 | uint32(b[3])
}

// ---- framing/frame_invalid.json ----

type invalidCase struct {
	Name          string `json:"name"`
	Description   string `json:"description"`
	FrameHex      string `json:"frame_hex"`
	ExpectedError string `json:"expected_error"`
}

type invalidFile struct {
	fileMeta
	Cases []invalidCase `json:"cases"`
}

func buildInvalids(constantsHash string) (*invalidFile, error) {
	base, err := buildFrame("HELLO", lumosconstants.MessageClassHello, 1)
	if err != nil {
		return nil, err
	}
	valid, err := hex.DecodeString(base.FrameHex)
	if err != nil {
		return nil, err
	}
	clone := func() []byte { return append([]byte(nil), valid...) }
	critical := func() []byte {
		b := clone()
		b[5] |= lumosconstants.FrameFlagCriticalDefault
		return b
	}

	file := &invalidFile{
		fileMeta: fileMeta{
			SchemaVersion: 1,
			Generator:     GeneratorName,
			ConstantsHash: constantsHash,
			Implements:    []string{"TRS-PROTO-001", "TRS-PROTO-006", "TRS-PROTO-007", "TRS-PROTO-013"},
			KeyProvenance: KeyProvenance,
			Notes: "Negative vectors. Every case MUST fail with exactly expected_error; a decoder " +
				"that accepts the frame, or fails with a different stable code, fails conformance. " +
				"The decode validation order is part of the wire contract (protocol/wire/frame.go).",
		},
	}

	add := func(name, description string, frame []byte, code string) {
		file.Cases = append(file.Cases, invalidCase{
			Name:          name,
			Description:   description,
			FrameHex:      hex.EncodeToString(frame),
			ExpectedError: code,
		})
	}

	add("empty_input", "Zero bytes cannot hold the magic.", nil, "ERR_FRAME_TRUNCATED")
	add("one_byte", "A single byte cannot hold the magic.", []byte{0x4C}, "ERR_FRAME_TRUNCATED")
	b := clone()
	b[0], b[1] = 0x4D, 0x4C
	add("bad_magic", "Byte-swapped magic 0x4D4C.", b, "ERR_FRAME_BAD_MAGIC")
	add("truncated_header", "Valid magic but only 20 of 58 header bytes.", clone()[:20], "ERR_FRAME_TRUNCATED")
	b = clone()
	b[2] = 0x20
	add("unsupported_major_version", "Version 0x20 (v2.0): differing MAJOR nibble fails closed (ADR-007).", b, "ERR_FRAME_UNSUPPORTED_VERSION")
	b = clone()
	b[7] = 57
	add("bad_header_length", "header_length declares 57; protocol v1 headers are exactly 58 bytes.", b, "ERR_FRAME_BAD_HEADER_LENGTH")
	b = critical()
	b[3] = 0x77
	add("unknown_type_critical", "Unknown class 0x77 with the critical flag fails closed (TRS-PROTO-006).", b, "ERR_FRAME_UNKNOWN_TYPE")
	b = critical()
	b[4] |= 0x01 // flag bit 8, inside the reserved mask
	add("reserved_flag_critical", "Reserved flag bit set together with the critical flag.", b, "ERR_FRAME_RESERVED_FLAG")
	b = critical()
	b[57] = 0x01
	add("reserved_byte_critical", "Non-zero reserved byte together with the critical flag.", b, "ERR_FRAME_RESERVED_FIELD")
	b = clone()[:lumosconstants.FrameHeaderLenBytesDefault]
	b[48], b[49], b[50], b[51] = 0x00, 0x00, 0x07, 0xD0
	add("payload_exceeds_class_bound", "HELLO declaring a 2000-byte payload; the class bound is QR_BOOTSTRAP_MAX_BYTES=1228. Checked before allocation (TRS-PROTO-007).", b, "ERR_FRAME_PAYLOAD_TOO_LARGE")
	b = clone()
	add("payload_truncated", "Payload declares 24 bytes but only 20 are present.", b[:len(b)-4], "ERR_FRAME_TRUNCATED")
	b = clone()
	add("trailing_garbage", "One byte beyond the declared payload.", append(b, 0xEE), "ERR_PAYLOAD_MALFORMED")

	return file, nil
}

// ---- errors/error_codes.json ----

type errorSnapshotEntry struct {
	Code     string `json:"code"`
	Numeric  int    `json:"numeric"`
	Category string `json:"category"`
	Meaning  string `json:"meaning"`
}

type errorSnapshotFile struct {
	fileMeta
	Codes []errorSnapshotEntry `json:"codes"`
}

func buildErrorSnapshot(root, constantsHash string) (*errorSnapshotFile, error) {
	registry, err := schema.LoadErrorCodes(root)
	if err != nil {
		return nil, err
	}
	file := &errorSnapshotFile{
		fileMeta: fileMeta{
			SchemaVersion: 1,
			Generator:     GeneratorName,
			ConstantsHash: constantsHash,
			Implements:    []string{"TRS-PROTO-013"},
			KeyProvenance: KeyProvenance,
			Notes: "Stable code registry snapshot. Codes are append-only; a consumer failing a " +
				"negative vector must report exactly one of these codes.",
		},
	}
	for _, c := range registry.Codes {
		file.Codes = append(file.Codes, errorSnapshotEntry{
			Code: c.Code, Numeric: c.Numeric, Category: c.Category, Meaning: c.Meaning,
		})
	}
	return file, nil
}

// ---- manifest.json ----

type manifestEntry struct {
	Path       string   `json:"path"`
	Implements []string `json:"implements"`
}

type manifest struct {
	SchemaVersion   int             `json:"schema_version"`
	Generator       string          `json:"generator"`
	GeneratorCommit string          `json:"generator_commit"`
	ConstantsHash   string          `json:"constants_hash"`
	KeyProvenance   string          `json:"key_provenance"`
	Notes           string          `json:"notes"`
	Files           []manifestEntry `json:"files"`
}

// jsonVectorFile encodes one vector document into the committed file form. Every
// vector file in the suite is produced through this single function, so
// indentation and the trailing newline cannot drift between domains - which
// would show up as a spurious determinism failure rather than as a formatting
// difference.
func jsonVectorFile(path string, implements []string, doc any) (VectorFile, error) {
	content, err := json.MarshalIndent(doc, "", "  ")
	if err != nil {
		return VectorFile{}, fmt.Errorf("marshal %s: %w", path, err)
	}
	return VectorFile{Path: path, Implements: implements, Content: append(content, '\n')}, nil
}

// Build produces the complete S4a vector set. It is pure: same inputs, same
// bytes, every time.
//
// The per-domain builders below exist to keep this assembly within the
// TRS-CODE-004 complexity budget: emitting twelve files inline puts two error
// branches per file in one function. The grouping is by protocol domain rather
// than by an arbitrary split, so a reader looking for the crypto vectors finds
// them together.
func Build(root, generatorCommit string) ([]VectorFile, error) {
	reg, err := schema.LoadRegister(root)
	if err != nil {
		return nil, err
	}
	constantsHash, err := reg.String("constants_hash")
	if err != nil {
		return nil, err
	}

	var files []VectorFile
	for _, group := range []func(string, *schema.Register, string) ([]VectorFile, error){
		buildFramingVectors,
		buildCryptoVectors,
		buildRadioAndMediaVectors,
		buildConfigVectors,
	} {
		got, err := group(root, reg, constantsHash)
		if err != nil {
			return nil, err
		}
		files = append(files, got...)
	}

	// TRS Sec. 18.4 completeness gate: the emitted set must be exactly the
	// prescribed tree. A missing file is a FAILED GATE, not a smaller suite.
	if err := assertSuiteComplete(files); err != nil {
		return nil, err
	}

	manifestFile, err := buildManifestFile(files, generatorCommit, constantsHash)
	if err != nil {
		return nil, err
	}
	return append(files, manifestFile), nil
}

// buildFramingVectors emits the frame round-trip, frame negative and error-code
// files. It is the only group that needs the register and the repository root.
func buildFramingVectors(root string, reg *schema.Register, constantsHash string) ([]VectorFile, error) {
	roundtrips, err := buildRoundtrips(reg, constantsHash)
	if err != nil {
		return nil, err
	}
	invalids, err := buildInvalids(constantsHash)
	if err != nil {
		return nil, err
	}
	errSnapshot, err := buildErrorSnapshot(root, constantsHash)
	if err != nil {
		return nil, err
	}
	fragmentation, err := buildFragmentation(constantsHash)
	if err != nil {
		return nil, err
	}
	return collectVectorFiles(
		vectorDoc{"test-vectors/framing/frame_roundtrip.json", roundtrips.Implements, roundtrips},
		vectorDoc{"test-vectors/framing/frame_invalid.json", invalids.Implements, invalids},
		vectorDoc{"test-vectors/framing/fragmentation.json", fragmentation.implementsIDs(), fragmentation},
		vectorDoc{"test-vectors/errors/error_codes.json", errSnapshot.Implements, errSnapshot},
	)
}

// buildCryptoVectors emits the four TRS Sec. 18.4 cryptographic vector files.
func buildCryptoVectors(_ string, _ *schema.Register, constantsHash string) ([]VectorFile, error) {
	x3dh, err := buildX3DHKAT(constantsHash)
	if err != nil {
		return nil, err
	}
	ratchet, err := buildRatchetSequences(constantsHash)
	if err != nil {
		return nil, err
	}
	aead, err := buildAEADKAT(constantsHash)
	if err != nil {
		return nil, err
	}
	safety, err := buildSafetyNumber(constantsHash)
	if err != nil {
		return nil, err
	}
	recoveryRecord, err := buildRecoveryVectors(constantsHash)
	if err != nil {
		return nil, err
	}
	identityBinding, err := buildIdentityBinding(constantsHash)
	if err != nil {
		return nil, err
	}
	helloExchange, err := buildHelloVectors(constantsHash)
	if err != nil {
		return nil, err
	}
	matchTimelines, err := buildMatchVectors(constantsHash)
	if err != nil {
		return nil, err
	}
	deliveryStates, err := buildDeliveryVectors(constantsHash)
	if err != nil {
		return nil, err
	}
	return collectVectorFiles(
		vectorDoc{"test-vectors/crypto/x3dh_kat.json", x3dh.Implements, x3dh},
		vectorDoc{"test-vectors/crypto/ratchet_sequences.json", ratchet.Implements, ratchet},
		vectorDoc{"test-vectors/crypto/aead_kat.json", aead.Implements, aead},
		vectorDoc{"test-vectors/crypto/safety_number.json", safety.Implements, safety},
		vectorDoc{"test-vectors/crypto/recovery_record.json", recoveryRecord.Implements, recoveryRecord},
		vectorDoc{"test-vectors/crypto/identity_binding.json", identityBinding.implementsIDs(), identityBinding},
		vectorDoc{"test-vectors/session/hello_exchange.json", helloExchange.implementsIDs(), helloExchange},
		vectorDoc{"test-vectors/matches/match_timelines.json", matchTimelines.implementsIDs(), matchTimelines},
		vectorDoc{"test-vectors/chat/delivery_states.json", deliveryStates.implementsIDs(), deliveryStates},
	)
}

// buildRadioAndMediaVectors emits the BLE advertisement and object-pipeline
// files.
func buildRadioAndMediaVectors(_ string, _ *schema.Register, constantsHash string) ([]VectorFile, error) {
	adv, err := buildAdvVectors(constantsHash)
	if err != nil {
		return nil, err
	}
	media, err := buildMediaVectors(constantsHash)
	if err != nil {
		return nil, err
	}
	return collectVectorFiles(
		vectorDoc{"test-vectors/adv/ble_advertisement.json", adv.Implements, adv},
		vectorDoc{"test-vectors/media/object_pipeline.json", media.Implements, media},
	)
}

// buildConfigVectors emits the signed-config positive and negative files and the
// voucher-token file.
func buildConfigVectors(_ string, _ *schema.Register, constantsHash string) ([]VectorFile, error) {
	valid, err := buildConfigValid(constantsHash)
	if err != nil {
		return nil, err
	}
	invalid, err := buildConfigInvalid(constantsHash)
	if err != nil {
		return nil, err
	}
	voucher, err := buildVoucherVectors(constantsHash)
	if err != nil {
		return nil, err
	}
	return collectVectorFiles(
		vectorDoc{"test-vectors/config/package_valid.json", valid.Implements, valid},
		vectorDoc{"test-vectors/config/package_invalid.json", invalid.Implements, invalid},
		vectorDoc{"test-vectors/voucher/voucher_tokens.json", voucher.Implements, voucher},
	)
}

// vectorDoc pairs a built document with the path and requirement IDs it is
// emitted under.
type vectorDoc struct {
	path       string
	implements []string
	doc        any
}

// collectVectorFiles encodes a group's documents in declaration order.
func collectVectorFiles(docs ...vectorDoc) ([]VectorFile, error) {
	out := make([]VectorFile, 0, len(docs))
	for _, d := range docs {
		f, err := jsonVectorFile(d.path, d.implements, d.doc)
		if err != nil {
			return nil, err
		}
		out = append(out, f)
	}
	return out, nil
}

// buildManifestFile indexes the emitted suite. It runs after
// assertSuiteComplete, so the manifest can never describe a partial tree.
func buildManifestFile(files []VectorFile, generatorCommit, constantsHash string) (VectorFile, error) {
	man := manifest{
		SchemaVersion:   1,
		Generator:       GeneratorName,
		GeneratorCommit: generatorCommit,
		ConstantsHash:   constantsHash,
		KeyProvenance:   KeyProvenance,
		Notes: "Golden-vector manifest (TRS Sec. 18.4). generator_commit is the HEAD at " +
			"generation time, same convention as the trace matrix. The suite is COMPLETE: " +
			"assertSuiteComplete enforces that the emitted set is exactly the Sec. 18.4 tree, " +
			"so a missing file fails generation rather than producing a quietly smaller suite.",
	}
	for _, f := range files {
		man.Files = append(man.Files, manifestEntry{Path: f.Path, Implements: f.Implements})
	}
	return jsonVectorFile(
		"test-vectors/manifest.json",
		[]string{"TRS-TST-001", "TRS-CONST-005"},
		man,
	)
}
