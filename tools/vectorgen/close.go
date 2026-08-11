// Lumos - tools/vectorgen / Closing vectors: adv, media, config, voucher.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-TST-001, TRS-PROTO-009, TRS-PROTO-011, TRS-CRY-008, TRS-CRY-009, TRS-CFG-001
// ADRs: ADR-001, ADR-002, ADR-004
// Threading: any
// Notes: Produced by driving the real protocol/adv, protocol/media and
// protocol/config packages, so committed bytes are what production computes.
// All signing keys are TESTONLY seeds - production config/voucher keys live only
// in owner-controlled KMS/HSM (gate G-05) and never touch this repository.
package main

import (
	"crypto/ed25519"
	"encoding/hex"
	"fmt"

	"github.com/dharabhishekemail-ops/lumos/protocol/adv"
	"github.com/dharabhishekemail-ops/lumos/protocol/config"
	"github.com/dharabhishekemail-ops/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekemail-ops/lumos/protocol/media"
)

// ---- adv/ble_advertisement.json ----

type advEpochCase struct {
	Epoch           int64  `json:"epoch"`
	UnixSeconds     int64  `json:"unix_seconds"`
	ModeFlags       int    `json:"mode_flags"`
	EIDHex          string `json:"eid_hex"`
	AuthTagHex      string `json:"auth_tag_hex"`
	AdvV1Hex        string `json:"adv_v1_hex"`
	ScanResponseHex string `json:"scan_response_hex"`
}

type advNegative struct {
	Name          string `json:"name"`
	Description   string `json:"description"`
	AdvV1Hex      string `json:"adv_v1_hex"`
	Epoch         int64  `json:"epoch"`
	ExpectedError string `json:"expected_error"`
}

type advFile struct {
	fileMeta
	KAdvHex        string         `json:"k_adv_hex"`
	RotationSec    int            `json:"eid_rotation_sec"`
	CapabilityBits int            `json:"capability_bits"`
	VenueHint      int64          `json:"venue_hint"`
	Schedule       []advEpochCase `json:"schedule"`
	Negatives      []advNegative  `json:"negatives"`
}

func buildAdvVectors(constantsHash string) (*advFile, error) {
	kAdv, err := adv.DeriveKAdv(cryptoSeed("adv|secret"))
	if err != nil {
		return nil, err
	}
	rot := lumosconstants.EIDRotationSecDefault
	const capBits = 0x0003
	const venueHint = 0xDEADBEEF

	file := &advFile{
		fileMeta: fileMeta{
			SchemaVersion: 1, Generator: GeneratorName, ConstantsHash: constantsHash,
			Implements:    []string{"TRS-PROTO-009", "TRS-PROTO-017", "TRS-PROTO-018", "SRS-IDN-004"},
			KeyProvenance: KeyProvenance,
			Notes: "adv_v1 EID rotation schedule and authTag derivation per ADR-002. THE " +
				"ADVERTISEMENT CARRIES NO TRUST: a matching authTag means 'worth connecting " +
				"to', never 'authenticated peer' (TRS-PROTO-017). Consumers derive each " +
				"epoch's EID and tag from k_adv and must reproduce adv_v1_hex exactly. iOS " +
				"advertises the service UUID only and produces no adv_v1 at all; a consumer " +
				"without service-data support asserts the negatives and skips the schedule.",
		},
		KAdvHex:        hex.EncodeToString(kAdv),
		RotationSec:    rot,
		CapabilityBits: capBits,
		VenueHint:      venueHint,
	}

	// Consecutive epochs, and one far-future epoch, all under a fixed base time.
	baseUnix := int64(1_700_000_000)
	baseEpoch := adv.Epoch(baseUnix, rot)
	offsets := []int64{0, 1, 2, 3, 100}
	for i, off := range offsets {
		epoch := baseEpoch + off
		modeFlags := uint8(0)
		if i%2 == 1 {
			modeFlags = adv.ModeFlagVenue
		}
		payload, err := adv.Build(kAdv, modeFlags, epoch)
		if err != nil {
			return nil, err
		}
		p, err := adv.Parse(payload)
		if err != nil {
			return nil, err
		}
		file.Schedule = append(file.Schedule, advEpochCase{
			Epoch:           epoch,
			UnixSeconds:     epoch * int64(rot),
			ModeFlags:       int(modeFlags),
			EIDHex:          hex.EncodeToString(p.EID),
			AuthTagHex:      hex.EncodeToString(p.AuthTag),
			AdvV1Hex:        hex.EncodeToString(payload),
			ScanResponseHex: hex.EncodeToString(adv.BuildScanResponse(capBits, venueHint)),
		})
	}

	valid, err := adv.Build(kAdv, 0, baseEpoch)
	if err != nil {
		return nil, err
	}
	mutate := func(f func([]byte)) string {
		b := append([]byte(nil), valid...)
		f(b)
		return hex.EncodeToString(b)
	}
	file.Negatives = []advNegative{
		{"wrong_epoch", "A payload from the previous epoch presented at the current epoch; the tag no longer matches.",
			hex.EncodeToString(valid), baseEpoch + 1, "ERR_ADV_AUTH_TAG_MISMATCH"},
		{"tampered_auth_tag", "One authTag byte flipped.",
			mutate(func(b []byte) { b[len(b)-1] ^= 0x01 }), baseEpoch, "ERR_ADV_AUTH_TAG_MISMATCH"},
		{"tampered_eid", "One EID byte flipped; the tag covers the EID.",
			mutate(func(b []byte) { b[5] ^= 0x01 }), baseEpoch, "ERR_ADV_AUTH_TAG_MISMATCH"},
		{"reserved_mode_flag", "A reserved mode-flag bit set; decode fails before the tag is even compared.",
			mutate(func(b []byte) { b[1] |= 0x02 }), baseEpoch, "ERR_ADV_AUTH_TAG_MISMATCH"},
		{"unsupported_version", "Advertisement version 2 is not understood by this build.",
			mutate(func(b []byte) { b[0] = 0x02 }), baseEpoch, "ERR_ADV_AUTH_TAG_MISMATCH"},
		{"truncated", "17 bytes instead of the fixed 18.",
			hex.EncodeToString(valid[:len(valid)-1]), baseEpoch, "ERR_ADV_AUTH_TAG_MISMATCH"},
	}
	return file, nil
}

// ---- media/object_pipeline.json ----

type mediaChunk struct {
	Index int    `json:"index"`
	Hex   string `json:"hex"`
}

type mediaAckCase struct {
	Name           string   `json:"name"`
	Received       []int    `json:"received"`
	BitmapHex      string   `json:"bitmap_hex"`
	MissingRanges  [][2]int `json:"missing_ranges"`
	ResumeTokenHex string   `json:"resume_token_hex"`
	Complete       bool     `json:"complete"`
}

type mediaNegative struct {
	Name          string `json:"name"`
	Description   string `json:"description"`
	ExpectedError string `json:"expected_error"`
}

type mediaFile struct {
	fileMeta
	SessionKeyHex   string          `json:"session_key_hex"`
	ObjectIDHex     string          `json:"object_id_hex"`
	ObjectKeyHex    string          `json:"object_key_hex"`
	WrappedKeyHex   string          `json:"wrapped_object_key_hex"`
	PlaintextHex    string          `json:"plaintext_hex"`
	PlaintextSHA256 string          `json:"plaintext_sha256_hex"`
	CiphertextHex   string          `json:"ciphertext_hex"`
	ChunkSize       int             `json:"chunk_size"`
	ChunkCount      int             `json:"chunk_count"`
	Chunks          []mediaChunk    `json:"chunks"`
	AckCases        []mediaAckCase  `json:"ack_cases"`
	Negatives       []mediaNegative `json:"negatives"`
}

func buildMediaVectors(constantsHash string) (*mediaFile, error) {
	sessionKey := cryptoSeed("media|session")
	objectKey := cryptoSeed("media|objectkey")
	var objectID [16]byte
	copy(objectID[:], cryptoSeed("media|objectid")[:16])
	plaintext := testonlyBytes("media|plaintext", 2500)
	const chunkSize = 512

	meta, ciphertext, err := media.Seal(sessionKey, objectID, objectKey, plaintext, chunkSize)
	if err != nil {
		return nil, err
	}

	file := &mediaFile{
		fileMeta: fileMeta{
			SchemaVersion: 1, Generator: GeneratorName, ConstantsHash: constantsHash,
			Implements:    []string{"TRS-CRY-008", "TRS-PROTO-010", "TRS-PROTO-011", "TRS-MED-002"},
			KeyProvenance: KeyProvenance,
			Notes: "Object pipeline: the object is encrypted WHOLE before fragmentation, so no " +
				"chunk boundary is a crypto boundary and a single chunk never decrypts alone. " +
				"The declared SHA-256 is over the PLAINTEXT, so a reassembly that decrypts " +
				"cleanly but reconstructs the wrong bytes still fails. Object keys are wrapped " +
				"to the match session. Media is local-only in flagship (SRS-TRN-009) and must " +
				"never be offered to the internet relay.",
		},
		SessionKeyHex:   hex.EncodeToString(sessionKey),
		ObjectIDHex:     hex.EncodeToString(objectID[:]),
		ObjectKeyHex:    hex.EncodeToString(objectKey),
		WrappedKeyHex:   hex.EncodeToString(meta.WrappedKey),
		PlaintextHex:    hex.EncodeToString(plaintext),
		PlaintextSHA256: hex.EncodeToString(meta.PlaintextSHA256[:]),
		CiphertextHex:   hex.EncodeToString(ciphertext),
		ChunkSize:       meta.ChunkSize,
		ChunkCount:      meta.ChunkCount,
	}
	for i := 0; i < meta.ChunkCount; i++ {
		c, err := media.Chunk(ciphertext, meta.ChunkSize, i)
		if err != nil {
			return nil, err
		}
		file.Chunks = append(file.Chunks, mediaChunk{Index: i, Hex: hex.EncodeToString(c)})
	}

	ackCases := []struct {
		name     string
		received []int
	}{
		{"all_received", allIndices(meta.ChunkCount)},
		{"gaps_in_middle", []int{0, 1, 4}},
		{"only_first", []int{0}},
	}
	for _, ac := range ackCases {
		ack := media.NewSelectiveAck(meta.ChunkCount)
		for _, i := range ac.received {
			if err := ack.Mark(i); err != nil {
				return nil, fmt.Errorf("ack %s: %w", ac.name, err)
			}
		}
		ranges := ack.MissingRanges()
		if ranges == nil {
			ranges = [][2]int{}
		}
		file.AckCases = append(file.AckCases, mediaAckCase{
			Name:           ac.name,
			Received:       ac.received,
			BitmapHex:      hex.EncodeToString(ack.Bitmap),
			MissingRanges:  ranges,
			ResumeTokenHex: hex.EncodeToString(ack.ResumeToken()),
			Complete:       ack.Complete(),
		})
	}

	file.Negatives = []mediaNegative{
		{"digest_mismatch", "Ciphertext that decrypts cleanly under the same key but whose plaintext does not match the declared SHA-256.", "ERR_MEDIA_DIGEST_MISMATCH"},
		{"truncated_reassembly", "One byte short of the declared ciphertext size.", "ERR_MEDIA_DIGEST_MISMATCH"},
		{"foreign_session_unwrap", "The wrapped object key presented under a different session key.", "ERR_MEDIA_OBJECT_KEY_UNWRAP_FAILED"},
		{"chunk_index_out_of_range", "A chunk index beyond the declared plan.", "ERR_MEDIA_CHUNK_OUT_OF_RANGE"},
	}
	return file, nil
}

func allIndices(n int) []int {
	out := make([]int, n)
	for i := range out {
		out[i] = i
	}
	return out
}

// ---- config/package_valid.json and package_invalid.json ----

type configPayload struct {
	Version       int              `json:"version"`
	Environment   string           `json:"environment"`
	ConstantsHash string           `json:"constants_hash"`
	ValidFrom     int64            `json:"valid_from"`
	ValidUntil    int64            `json:"valid_until"`
	Overrides     map[string]int64 `json:"overrides"`
	KillSwitches  map[string]bool  `json:"kill_switches"`
}

type configValidCase struct {
	Name           string        `json:"name"`
	Description    string        `json:"description"`
	Payload        configPayload `json:"payload"`
	CanonicalBytes string        `json:"canonical_bytes"`
	PayloadHash    string        `json:"payload_hash"`
	SignatureHex   string        `json:"signature_hex"`
	NowUnix        int64         `json:"now_unix"`
	ActiveVersion  int           `json:"active_version"`
}

type configInvalidCase struct {
	Name          string        `json:"name"`
	Description   string        `json:"description"`
	Payload       configPayload `json:"payload"`
	SignatureHex  string        `json:"signature_hex"`
	NowUnix       int64         `json:"now_unix"`
	ActiveVersion int           `json:"active_version"`
	Environment   string        `json:"expected_environment"`
	ExpectedError string        `json:"expected_error"`
}

type configValidFile struct {
	fileMeta
	TrustAnchorHex string            `json:"trust_anchor_ed25519_pub_hex"`
	Cases          []configValidCase `json:"cases"`
}

type configInvalidFile struct {
	fileMeta
	TrustAnchorHex string              `json:"trust_anchor_ed25519_pub_hex"`
	Cases          []configInvalidCase `json:"cases"`
}

func toPayload(p *config.Package) configPayload {
	return configPayload{
		Version: p.Version, Environment: p.Environment, ConstantsHash: p.ConstantsHash,
		ValidFrom: p.ValidFrom, ValidUntil: p.ValidUntil,
		Overrides: p.Overrides, KillSwitches: p.KillSwitches,
	}
}

func basePackage(constantsHash string) *config.Package {
	return &config.Package{
		Version:       5,
		Environment:   config.EnvStaging,
		ConstantsHash: constantsHash,
		ValidFrom:     1_699_000_000,
		ValidUntil:    1_800_000_000,
		Overrides: map[string]int64{
			"EID_ROTATION_SEC": 900,
			"MESH_HOP_DEFAULT": 4,
		},
		KillSwitches: map[string]bool{"disable_venue_rooms": true},
	}
}

const configNow = int64(1_700_000_000)

func buildConfigValid(constantsHash string) (*configValidFile, error) {
	priv := edFromSeed("config|signer")
	file := &configValidFile{
		fileMeta: fileMeta{
			SchemaVersion: 1, Generator: GeneratorName, ConstantsHash: constantsHash,
			Implements:    []string{"TRS-CFG-001", "TRS-CRY-009", "TRS-CONST-005"},
			KeyProvenance: KeyProvenance,
			Notes: "Signed configuration packages that MUST validate. canonical_bytes is the " +
				"deterministic sorted key=value signing form - sorted flat text rather than " +
				"JSON so Java and Swift reproduce it without depending on key ordering. The " +
				"signature covers a domain prefix plus those bytes, so a config signature can " +
				"never be replayed as a voucher signature. TESTONLY signing key; production " +
				"keys live only in owner-controlled KMS/HSM (gate G-05).",
		},
		TrustAnchorHex: hex.EncodeToString(priv.Public().(ed25519.PublicKey)),
	}

	full := basePackage(constantsHash)
	minimal := &config.Package{
		Version: 2, Environment: config.EnvProduction, ConstantsHash: constantsHash,
		ValidFrom: 1_699_000_000, ValidUntil: 1_800_000_000,
		Overrides: map[string]int64{}, KillSwitches: map[string]bool{},
	}

	for _, c := range []struct {
		name, desc string
		p          *config.Package
		active     int
	}{
		{"full_package", "Overrides plus a kill switch, superseding an older active version.", full, 4},
		{"minimal_package", "No overrides and no kill switches; the envelope alone must still validate.", minimal, 1},
	} {
		file.Cases = append(file.Cases, configValidCase{
			Name: c.name, Description: c.desc,
			Payload:        toPayload(c.p),
			CanonicalBytes: string(c.p.CanonicalBytes()),
			PayloadHash:    c.p.PayloadHash(),
			SignatureHex:   hex.EncodeToString(c.p.Sign(priv)),
			NowUnix:        configNow,
			ActiveVersion:  c.active,
		})
	}
	return file, nil
}

func buildConfigInvalid(constantsHash string) (*configInvalidFile, error) {
	priv := edFromSeed("config|signer")
	foreign := edFromSeed("config|mallory")

	file := &configInvalidFile{
		fileMeta: fileMeta{
			SchemaVersion: 1, Generator: GeneratorName, ConstantsHash: constantsHash,
			Implements:    []string{"TRS-CFG-001", "TRS-CONST-005", "TRS-ARCH-010"},
			KeyProvenance: KeyProvenance,
			Notes: "Packages that MUST be rejected, each with its exact stable code. Validation " +
				"is fail closed and ORDERED - signature, constants hash, environment, validity " +
				"window, rollback - so a package failing several gates reports the first. " +
				"Rejecting a package leaves the last-known-good active; there is no partial " +
				"activation and no fail-open path (TRS-ARCH-010).",
		},
		TrustAnchorHex: hex.EncodeToString(priv.Public().(ed25519.PublicKey)),
	}

	add := func(name, desc string, p *config.Package, sig []byte, now int64, active int, env, code string) {
		file.Cases = append(file.Cases, configInvalidCase{
			Name: name, Description: desc, Payload: toPayload(p),
			SignatureHex: hex.EncodeToString(sig), NowUnix: now,
			ActiveVersion: active, Environment: env, ExpectedError: code,
		})
	}

	tampered := basePackage(constantsHash)
	badSig := tampered.Sign(priv)
	badSig[0] ^= 0x01
	add("tampered_signature", "One signature byte flipped.", tampered, badSig,
		configNow, 4, config.EnvStaging, "ERR_CONFIG_SIGNATURE_INVALID")

	forged := basePackage(constantsHash)
	add("foreign_signer", "Correctly formed but signed by a key that is not the trust anchor.",
		forged, forged.Sign(foreign), configNow, 4, config.EnvStaging, "ERR_CONFIG_SIGNATURE_INVALID")

	mutated := basePackage(constantsHash)
	sigOverOriginal := mutated.Sign(priv)
	mutated.Overrides["EID_ROTATION_SEC"] = 300 // signed bytes no longer match
	add("mutated_override_after_signing", "An override value changed after signing; every field is covered by the signature.",
		mutated, sigOverOriginal, configNow, 4, config.EnvStaging, "ERR_CONFIG_SIGNATURE_INVALID")

	wrongHash := basePackage(constantsHash)
	wrongHash.ConstantsHash = "sha256:" + hex.EncodeToString(cryptoSeed("config|wronghash"))
	add("constants_hash_mismatch", "Built against a different constants register (TRS-CONST-005).",
		wrongHash, wrongHash.Sign(priv), configNow, 4, config.EnvStaging, "ERR_CONFIG_CONSTANTS_HASH_MISMATCH")

	wrongEnv := basePackage(constantsHash)
	wrongEnv.Environment = config.EnvProduction
	add("wrong_environment", "A production package offered to a staging build.",
		wrongEnv, wrongEnv.Sign(priv), configNow, 4, config.EnvStaging, "ERR_CONFIG_WRONG_ENVIRONMENT")

	expired := basePackage(constantsHash)
	expired.ValidUntil = configNow - 10*config.DefaultSkew()
	add("expired", "Validity window closed, beyond the clock-skew tolerance.",
		expired, expired.Sign(priv), configNow, 4, config.EnvStaging, "ERR_CONFIG_EXPIRED")

	notYet := basePackage(constantsHash)
	notYet.ValidFrom = configNow + 10*config.DefaultSkew()
	add("not_yet_valid", "Validity window has not opened yet.",
		notYet, notYet.Sign(priv), configNow, 4, config.EnvStaging, "ERR_CONFIG_EXPIRED")

	rollback := basePackage(constantsHash)
	add("rollback_attempt", "Re-offering a version that does not supersede the active one.",
		rollback, rollback.Sign(priv), configNow, rollback.Version, config.EnvStaging, "ERR_CONFIG_ROLLBACK")

	older := basePackage(constantsHash)
	older.Version = 2
	add("older_version", "An older package replayed against a newer active version.",
		older, older.Sign(priv), configNow, 9, config.EnvStaging, "ERR_CONFIG_ROLLBACK")

	return file, nil
}

// ---- voucher/voucher_tokens.json ----

type voucherPayload struct {
	VoucherID  string `json:"voucher_id"`
	VenueID    string `json:"venue_id"`
	Nonce      string `json:"nonce"`
	ValidFrom  int64  `json:"valid_from"`
	ValidUntil int64  `json:"valid_until"`
	PerkID     string `json:"perk_id"`
	Revocable  bool   `json:"revocable"`
}

type voucherCase struct {
	Name           string         `json:"name"`
	Description    string         `json:"description"`
	Payload        voucherPayload `json:"payload"`
	CanonicalBytes string         `json:"canonical_bytes"`
	SignatureHex   string         `json:"signature_hex"`
	NowUnix        int64          `json:"now_unix"`
	SeenNonces     []string       `json:"seen_nonces"`
	RevokedIDs     []string       `json:"revoked_voucher_ids"`
	ExpectedError  string         `json:"expected_error"`
}

type voucherFile struct {
	fileMeta
	TrustAnchorHex string        `json:"trust_anchor_ed25519_pub_hex"`
	Cases          []voucherCase `json:"cases"`
}

func toVoucherPayload(v *config.Voucher) voucherPayload {
	return voucherPayload{
		VoucherID: v.VoucherID, VenueID: v.VenueID, Nonce: v.Nonce,
		ValidFrom: v.ValidFrom, ValidUntil: v.ValidUntil,
		PerkID: v.PerkID, Revocable: v.Revocable,
	}
}

func baseVoucher() *config.Voucher {
	return &config.Voucher{
		VoucherID:  "welcome-drink",
		VenueID:    "the-blue-room",
		Nonce:      hex.EncodeToString(cryptoSeed("voucher|nonce")[:16]),
		ValidFrom:  1_699_000_000,
		ValidUntil: 1_800_000_000,
		PerkID:     "free-first-drink",
		Revocable:  true,
	}
}

func buildVoucherVectors(constantsHash string) (*voucherFile, error) {
	priv := edFromSeed("voucher|signer")
	file := &voucherFile{
		fileMeta: fileMeta{
			SchemaVersion: 1, Generator: GeneratorName, ConstantsHash: constantsHash,
			Implements:    []string{"TRS-CFG-009", "TRS-CRY-009", "TRS-TRN-014"},
			KeyProvenance: KeyProvenance,
			Notes: "Voucher tokens. expected_error is empty for the case that must redeem and " +
				"a stable code for every case that must not. The token carries no personal " +
				"data because it may be displayed, photographed and shared in a venue. " +
				"TESTONLY signing key.",
		},
		TrustAnchorHex: hex.EncodeToString(priv.Public().(ed25519.PublicKey)),
	}

	add := func(name, desc string, v *config.Voucher, sig []byte, now int64, seen, revoked []string, code string) {
		if seen == nil {
			seen = []string{}
		}
		if revoked == nil {
			revoked = []string{}
		}
		file.Cases = append(file.Cases, voucherCase{
			Name: name, Description: desc, Payload: toVoucherPayload(v),
			CanonicalBytes: string(v.CanonicalBytes()), SignatureHex: hex.EncodeToString(sig),
			NowUnix: now, SeenNonces: seen, RevokedIDs: revoked, ExpectedError: code,
		})
	}

	valid := baseVoucher()
	add("valid", "A fresh single-use voucher inside its window.", valid, valid.Sign(priv),
		configNow, nil, nil, "")

	replayed := baseVoucher()
	add("replayed_nonce", "The same nonce presented twice; the second redemption is refused.",
		replayed, replayed.Sign(priv), configNow, []string{replayed.Nonce}, nil, "ERR_VOUCHER_NONCE_REPLAY")

	revoked := baseVoucher()
	add("revoked", "A revocable voucher listed in the revocation set.",
		revoked, revoked.Sign(priv), configNow, nil, []string{revoked.VoucherID}, "ERR_VOUCHER_REVOKED")

	expired := baseVoucher()
	expired.ValidUntil = configNow - 10*config.DefaultSkew()
	add("expired", "Validity window closed beyond the clock-skew tolerance.",
		expired, expired.Sign(priv), configNow, nil, nil, "ERR_CONFIG_EXPIRED")

	tampered := baseVoucher()
	badSig := tampered.Sign(priv)
	badSig[0] ^= 0x01
	add("tampered_signature", "One signature byte flipped.", tampered, badSig,
		configNow, nil, nil, "ERR_CONFIG_SIGNATURE_INVALID")

	nonRevocable := baseVoucher()
	nonRevocable.VoucherID = "permanent-perk"
	nonRevocable.Revocable = false
	add("non_revocable_ignores_revocation_set",
		"A non-revocable voucher is not checked against the revocation set and still redeems.",
		nonRevocable, nonRevocable.Sign(priv), configNow, nil, []string{nonRevocable.VoucherID}, "")

	return file, nil
}
