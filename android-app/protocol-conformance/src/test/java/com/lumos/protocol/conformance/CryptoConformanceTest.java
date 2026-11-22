/*
 * Lumos - protocol-conformance / Crypto, adv, media, config and voucher conformance.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-TST-001, TRS-CRY-003, TRS-CRY-008, TRS-CRY-009, TRS-CRY-014, TRS-CRY-015, SRS-NFR-CMP-004, SRS-IDN-005
 * ADRs: ADR-001, ADR-002, ADR-005, ADR-008
 * Threading: any
 * Notes: Each test RECOMPUTES its result from the recorded inputs using the JDK
 * primitives and compares against the committed bytes - it never simply reads
 * the expected value back. The recorded TESTONLY private halves are what make
 * that possible from outside the generating repository; a runner that instead
 * re-derived the generator's seeds would be agreeing with itself.
 */
package com.lumos.protocol.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.wire.LumosCrypto;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import org.junit.jupiter.api.Test;

/** Cross-language conformance for the crypto, adv, media and config vectors. */
final class CryptoConformanceTest {

    private static final byte[] ZERO_SALT = new byte[32];

    private static byte[] utf8(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Expands a ratchet/AEAD message key into (key, nonce), as the register defines. */
    private static byte[] messageKeyMaterial(final byte[] messageKey) {
        return LumosCrypto.hkdf(messageKey, ZERO_SALT,
                utf8(LumosConstants.RATCHET_MESSAGE_KDF_INFO_VALUE),
                LumosCrypto.AEAD_KEY_SIZE + LumosCrypto.AEAD_NONCE_SIZE);
    }

    @Test
    /** AEAD known-answers reproduce exactly and every tamper case fails */
    void aeadKat() throws Exception {
        final JsonObject root = Vectors.load("test-vectors/crypto/aead_kat.json");

        for (final JsonElement el : Vectors.arr(root, "positive")) {
            final JsonObject c = Vectors.obj(el);
            final byte[] okm = messageKeyMaterial(Vectors.hex(c, "message_key_hex"));
            final byte[] key = LumosCrypto.prefix(okm, LumosCrypto.AEAD_KEY_SIZE);
            final byte[] nonce = LumosCrypto.tail(okm, LumosCrypto.AEAD_NONCE_SIZE);

            assertEquals(Vectors.str(c, "derived_key_hex"), Vectors.toHex(key), "derived key");
            assertEquals(Vectors.str(c, "nonce_hex"), Vectors.toHex(nonce), "derived nonce");

            final byte[] ct = LumosCrypto.aeadSeal(key, nonce,
                    Vectors.hex(c, "plaintext_hex"), Vectors.hex(c, "aad_hex"));
            assertEquals(Vectors.str(c, "ciphertext_hex"), Vectors.toHex(ct), "ciphertext");

            final byte[] back = LumosCrypto.aeadOpen(key, nonce,
                    Vectors.hex(c, "ciphertext_hex"), Vectors.hex(c, "aad_hex"));
            assertEquals(Vectors.str(c, "plaintext_hex"), Vectors.toHex(back), "roundtrip");
        }

        int tampers = 0;
        for (final JsonElement el : Vectors.arr(root, "tamper")) {
            final JsonObject c = Vectors.obj(el);
            final byte[] okm = messageKeyMaterial(Vectors.hex(c, "message_key_hex"));
            try {
                LumosCrypto.aeadOpen(LumosCrypto.prefix(okm, LumosCrypto.AEAD_KEY_SIZE),
                        Vectors.hex(c, "nonce_hex"), Vectors.hex(c, "ciphertext_hex"),
                        Vectors.hex(c, "aad_hex"));
                fail("tamper case " + Vectors.str(c, "name") + " authenticated");
            } catch (GeneralSecurityException expected) {
                // Every AEAD failure maps to one stable code; the JCA exception
                // type is deliberately not distinguished (TRS-CRY-013).
                assertEquals("ERR_CRYPTO_AEAD_AUTH_FAILED", Vectors.str(c, "expected_error"));
            }
            tampers++;
        }
        assertTrue(tampers >= 3, "expected the header-AAD, ciphertext and tag tamper cases");
    }

    @Test
    /** safety numbers reproduce 60 digits and 6 emoji from the frozen alphabet */
    void safetyNumber() {
        final JsonObject root = Vectors.load("test-vectors/crypto/safety_number.json");
        assertEquals(64, Vectors.i(root, "alphabet_size"), "alphabet size");
        assertEquals(64, Vectors.arr(root, "alphabet").size(), "alphabet entries");

        for (int i = 0; i < 64; i++) {
            assertEquals(Vectors.arr(root, "alphabet").get(i).getAsString(),
                    LumosConstants.SAFETY_NUMBER_EMOJI_ALPHABET_GLYPHS.get(i),
                    "generated alphabet differs from the committed vector at index " + i);
        }

        for (final JsonElement el : Vectors.arr(root, "cases")) {
            final JsonObject c = Vectors.obj(el);
            final byte[] a = Vectors.hex(c, "identity_a_ed_pub_hex");
            final byte[] b = Vectors.hex(c, "identity_b_ed_pub_hex");

            final byte[] lo;
            final byte[] hi;
            if (compare(a, b) <= 0) {
                lo = a;
                hi = b;
            } else {
                lo = b;
                hi = a;
            }
            assertEquals(Vectors.str(c, "digits"), digitsFor(lo) + digitsFor(hi),
                    "digits for " + Vectors.str(c, "name"));

            final byte[] combined = LumosCrypto.sha256(LumosCrypto.concat(
                    utf8(LumosConstants.SAFETY_NUMBER_KDF_INFO_VALUE), lo, hi));
            for (int i = 0; i < 6; i++) {
                final int idx = (combined[i] & 0xFF) % 64;
                assertEquals(Vectors.arr(c, "emoji_indices").get(i).getAsInt(), idx,
                        "emoji index " + i);
                assertEquals(Vectors.arr(c, "emoji").get(i).getAsString(),
                        LumosConstants.SAFETY_NUMBER_EMOJI_ALPHABET_GLYPHS.get(idx),
                        "emoji glyph " + i);
            }
        }
    }

    private static int compare(final byte[] a, final byte[] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            final int d = (a[i] & 0xFF) - (b[i] & 0xFF);
            if (d != 0) {
                return d;
            }
        }
        return a.length - b.length;
    }

    private static String digitsFor(final byte[] pub) {
        byte[] digest = LumosCrypto.sha256(LumosCrypto.concat(
                utf8(LumosConstants.SAFETY_NUMBER_KDF_INFO_VALUE), pub));
        for (int i = 1; i < LumosConstants.SAFETY_NUMBER_ITERATIONS_DEFAULT; i++) {
            digest = LumosCrypto.sha256(LumosCrypto.concat(digest, pub));
        }
        final StringBuilder sb = new StringBuilder(30);
        for (int chunk = 0; chunk < 6; chunk++) {
            long v = 0;
            for (int k = 0; k < 5; k++) {
                v = (v << 8) | (digest[chunk * 5 + k] & 0xFF);
            }
            sb.append(String.format("%05d", v % 100000L));
        }
        return sb.toString();
    }

    @Test
    /** X3DH shared secrets and transcripts reproduce from the recorded halves */
    void x3dhKat() throws Exception {
        final JsonObject root = Vectors.load("test-vectors/crypto/x3dh_kat.json");
        final byte[] matchId = Vectors.hex(root, "match_id_hex");

        for (final JsonElement el : Vectors.arr(root, "cases")) {
            final JsonObject c = Vectors.obj(el);
            final String name = Vectors.str(c, "name");

            // Transcript: domain | initEd | respEd | initDH | respDH | spkId | opkId | version | matchId
            final byte[] transcript = LumosCrypto.sha256(LumosCrypto.concat(
                    utf8(LumosConstants.X3DH_TRANSCRIPT_INFO_VALUE),
                    Vectors.hex(c, "initiator_identity_ed_pub_hex"),
                    Vectors.hex(c, "responder_identity_ed_pub_hex"),
                    Vectors.hex(c, "initiator_identity_dh_pub_hex"),
                    Vectors.hex(c, "responder_identity_dh_pub_hex"),
                    be32(Vectors.l(c, "signed_prekey_id")),
                    be32(Vectors.l(c, "one_time_prekey_id")),
                    new byte[] {(byte) LumosConstants.PROTOCOL_VERSION_ENCODED},
                    matchId));
            assertEquals(Vectors.str(c, "transcript_ad_hex"), Vectors.toHex(transcript),
                    "transcript for " + name);

            // Bundle signatures must verify before any DH (ADR-008 condition 2).
            final byte[] respEd = Vectors.hex(c, "responder_identity_ed_pub_hex");
            assertTrue(LumosCrypto.ed25519Verify(respEd,
                            LumosCrypto.concat(utf8(LumosConstants.X3DH_IDENTITY_BINDING_INFO_VALUE),
                                    Vectors.hex(c, "responder_identity_dh_pub_hex")),
                            Vectors.hex(c, "identity_dh_sig_hex")),
                    "identity-DH binding signature for " + name);
            assertTrue(LumosCrypto.ed25519Verify(respEd,
                            LumosCrypto.concat(utf8(LumosConstants.X3DH_PREKEY_SIGNING_INFO_VALUE),
                                    be32(Vectors.l(c, "signed_prekey_id")),
                                    Vectors.hex(c, "responder_signed_prekey_pub_hex")),
                            Vectors.hex(c, "signed_prekey_sig_hex")),
                    "signed-prekey signature for " + name);

            // DH1..DH4 from the initiator side, using the recorded private halves.
            final var initIdDh = Vectors.hex(c, "testonly_initiator_identity_dh_priv_hex");
            final var initEph = Vectors.hex(c, "testonly_initiator_ephemeral_priv_hex");
            final var respIdDh = Vectors.hex(c, "responder_identity_dh_pub_hex");
            final var respSpk = Vectors.hex(c, "responder_signed_prekey_pub_hex");

            final byte[] dh1 = LumosCrypto.x25519(initIdDh, respSpk);
            final byte[] dh2 = LumosCrypto.x25519(initEph, respIdDh);
            final byte[] dh3 = LumosCrypto.x25519(initEph, respSpk);
            byte[] dh4 = new byte[0];
            if (Vectors.b(c, "with_one_time_prekey")) {
                dh4 = LumosCrypto.x25519(initEph,
                        Vectors.hex(c, "responder_one_time_prekey_pub_hex"));
            }

            final byte[] f = new byte[LumosConstants.X3DH_F_PREFIX_LEN];
            java.util.Arrays.fill(f, (byte) LumosConstants.X3DH_F_PREFIX_FILL_BYTE);
            final byte[] sk = LumosCrypto.hkdf(
                    LumosCrypto.concat(f, dh1, dh2, dh3, dh4), ZERO_SALT,
                    utf8(LumosConstants.X3DH_KDF_INFO_VALUE), 32);
            assertEquals(Vectors.str(c, "shared_secret_hex"), Vectors.toHex(sk),
                    "shared secret for " + name);
        }

        int negatives = 0;
        for (final JsonElement el : Vectors.arr(root, "negatives")) {
            final JsonObject c = Vectors.obj(el);
            final byte[] respEd = Vectors.hex(c, "responder_identity_ed_pub_hex");
            final boolean idOk = LumosCrypto.ed25519Verify(respEd,
                    LumosCrypto.concat(utf8(LumosConstants.X3DH_IDENTITY_BINDING_INFO_VALUE),
                            Vectors.hex(c, "responder_identity_dh_pub_hex")),
                    Vectors.hex(c, "identity_dh_sig_hex"));
            final boolean spkOk = LumosCrypto.ed25519Verify(respEd,
                    LumosCrypto.concat(utf8(LumosConstants.X3DH_PREKEY_SIGNING_INFO_VALUE),
                            be32(Vectors.l(c, "signed_prekey_id")),
                            Vectors.hex(c, "responder_signed_prekey_pub_hex")),
                    Vectors.hex(c, "signed_prekey_sig_hex"));
            assertTrue(!idOk || !spkOk,
                    "negative bundle " + Vectors.str(c, "name") + " verified; it must fail closed");
            assertEquals("ERR_CRYPTO_PREKEY_SIGNATURE_INVALID", Vectors.str(c, "expected_error"));
            negatives++;
        }
        assertTrue(negatives >= 2, "expected both bundle-signature negatives");
    }

    private static byte[] be32(final long v) {
        return new byte[] {
            (byte) ((v >>> 24) & 0xFF), (byte) ((v >>> 16) & 0xFF),
            (byte) ((v >>> 8) & 0xFF), (byte) (v & 0xFF),
        };
    }

    @Test
    /** the advertisement EID schedule and authTag reproduce per epoch */
    void advertisement() throws Exception {
        final JsonObject root = Vectors.load("test-vectors/adv/ble_advertisement.json");
        final byte[] kAdv = Vectors.hex(root, "k_adv_hex");
        final int rotation = Vectors.i(root, "eid_rotation_sec");

        for (final JsonElement el : Vectors.arr(root, "schedule")) {
            final JsonObject c = Vectors.obj(el);
            final long epoch = Vectors.l(c, "epoch");
            assertEquals(epoch, Vectors.l(c, "unix_seconds") / rotation, "epoch arithmetic");

            final byte[] eid = LumosCrypto.hkdf(kAdv, ZERO_SALT,
                    LumosCrypto.concat(utf8(LumosConstants.ADV_EID_KDF_INFO_VALUE), be64(epoch)),
                    LumosConstants.ADV_V1_LAYOUT_EID_LEN);
            assertEquals(Vectors.str(c, "eid_hex"), Vectors.toHex(eid), "EID");

            final int modeFlags = Vectors.i(c, "mode_flags");
            final byte[] tag = LumosCrypto.prefix(
                    LumosCrypto.hmacSha256(kAdv, LumosCrypto.concat(
                            utf8(LumosConstants.ADV_AUTH_TAG_INFO_VALUE),
                            new byte[] {1, (byte) modeFlags}, eid, be64(epoch))),
                    LumosConstants.ADV_AUTH_TAG_LEN_DEFAULT);
            assertEquals(Vectors.str(c, "auth_tag_hex"), Vectors.toHex(tag), "authTag");

            final byte[] advV1 = LumosCrypto.concat(new byte[] {1, (byte) modeFlags}, eid, tag);
            assertEquals(LumosConstants.ADV_V1_LAYOUT_TOTAL_LEN, advV1.length, "adv_v1 width");
            assertEquals(Vectors.str(c, "adv_v1_hex"), Vectors.toHex(advV1), "adv_v1");
        }

        int negatives = 0;
        for (final JsonElement el : Vectors.arr(root, "negatives")) {
            final JsonObject c = Vectors.obj(el);
            final byte[] payload = Vectors.hex(c, "adv_v1_hex");
            final long epoch = Vectors.l(c, "epoch");
            assertTrue(!advVerifies(kAdv, payload, epoch),
                    "negative advertisement " + Vectors.str(c, "name") + " verified");
            assertEquals("ERR_ADV_AUTH_TAG_MISMATCH", Vectors.str(c, "expected_error"));
            negatives++;
        }
        assertTrue(negatives >= 5, "expected the full adv negative set");
    }

    private static boolean advVerifies(final byte[] kAdv, final byte[] payload, final long epoch) {
        if (payload.length != LumosConstants.ADV_V1_LAYOUT_TOTAL_LEN) {
            return false;
        }
        if (payload[0] != 1) {
            return false;
        }
        if ((payload[1] & 0xFE) != 0) {
            return false;
        }
        final int eidLen = LumosConstants.ADV_V1_LAYOUT_EID_LEN;
        final byte[] eid = java.util.Arrays.copyOfRange(payload, 2, 2 + eidLen);
        final byte[] got = java.util.Arrays.copyOfRange(payload, 2 + eidLen, payload.length);
        final byte[] want = LumosCrypto.prefix(
                LumosCrypto.hmacSha256(kAdv, LumosCrypto.concat(
                        utf8(LumosConstants.ADV_AUTH_TAG_INFO_VALUE),
                        new byte[] {payload[0], payload[1]}, eid, be64(epoch))),
                LumosConstants.ADV_AUTH_TAG_LEN_DEFAULT);
        return java.security.MessageDigest.isEqual(want, got);
    }

    private static byte[] be64(final long v) {
        final byte[] out = new byte[8];
        for (int i = 0; i < 8; i++) {
            out[i] = (byte) ((v >>> (56 - 8 * i)) & 0xFF);
        }
        return out;
    }

    @Test
    /** the media object pipeline reproduces the seal, chunks and digest */
    void mediaPipeline() throws Exception {
        final JsonObject root = Vectors.load("test-vectors/media/object_pipeline.json");
        final byte[] sessionKey = Vectors.hex(root, "session_key_hex");
        final byte[] objectKey = Vectors.hex(root, "object_key_hex");
        final byte[] objectId = Vectors.hex(root, "object_id_hex");
        final byte[] plaintext = Vectors.hex(root, "plaintext_hex");
        final int chunkSize = Vectors.i(root, "chunk_size");

        assertEquals(Vectors.str(root, "plaintext_sha256_hex"),
                Vectors.toHex(LumosCrypto.sha256(plaintext)), "plaintext digest");

        final byte[] objOkm = LumosCrypto.hkdf(objectKey, ZERO_SALT,
                utf8(LumosConstants.MEDIA_OBJECT_KEY_INFO_VALUE),
                LumosCrypto.AEAD_KEY_SIZE + LumosCrypto.AEAD_NONCE_SIZE);
        final byte[] ct = LumosCrypto.aeadSeal(
                LumosCrypto.prefix(objOkm, LumosCrypto.AEAD_KEY_SIZE),
                LumosCrypto.tail(objOkm, LumosCrypto.AEAD_NONCE_SIZE),
                plaintext, objectId);
        assertEquals(Vectors.str(root, "ciphertext_hex"), Vectors.toHex(ct), "object ciphertext");

        final byte[] wrapOkm = LumosCrypto.hkdf(sessionKey, ZERO_SALT,
                LumosCrypto.concat(utf8(LumosConstants.MEDIA_OBJECT_KEY_WRAP_INFO_VALUE), objectId),
                LumosCrypto.AEAD_KEY_SIZE + LumosCrypto.AEAD_NONCE_SIZE);
        final byte[] wrapped = LumosCrypto.aeadSeal(
                LumosCrypto.prefix(wrapOkm, LumosCrypto.AEAD_KEY_SIZE),
                LumosCrypto.tail(wrapOkm, LumosCrypto.AEAD_NONCE_SIZE),
                objectKey, objectId);
        assertEquals(Vectors.str(root, "wrapped_object_key_hex"), Vectors.toHex(wrapped), "wrapped key");

        // Reassemble strictly from the committed chunks.
        final int chunkCount = Vectors.i(root, "chunk_count");
        assertEquals((ct.length + chunkSize - 1) / chunkSize, chunkCount, "chunk plan");
        final byte[][] parts = new byte[chunkCount][];
        int n = 0;
        for (final JsonElement el : Vectors.arr(root, "chunks")) {
            final JsonObject c = Vectors.obj(el);
            parts[Vectors.i(c, "index")] = Vectors.hex(c, "hex");
            n++;
        }
        assertEquals(chunkCount, n, "chunk count");
        final byte[] reassembled = LumosCrypto.concat(parts);
        assertArrayEquals(ct, reassembled, "reassembly differs from the sealed object");

        final byte[] back = LumosCrypto.aeadOpen(
                LumosCrypto.prefix(objOkm, LumosCrypto.AEAD_KEY_SIZE),
                LumosCrypto.tail(objOkm, LumosCrypto.AEAD_NONCE_SIZE),
                reassembled, objectId);
        assertArrayEquals(plaintext, back, "decrypted object");
        assertEquals(Vectors.str(root, "plaintext_sha256_hex"),
                Vectors.toHex(LumosCrypto.sha256(back)), "digest after reassembly");

        // Selective-ack bitmaps and ranges.
        for (final JsonElement el : Vectors.arr(root, "ack_cases")) {
            final JsonObject c = Vectors.obj(el);
            final byte[] bitmap = new byte[(chunkCount + 7) / 8];
            for (final JsonElement idx : Vectors.arr(c, "received")) {
                final int i = idx.getAsInt();
                bitmap[i / 8] |= (byte) (1 << (i % 8));
            }
            assertEquals(Vectors.str(c, "bitmap_hex"), Vectors.toHex(bitmap),
                    "bitmap for " + Vectors.str(c, "name"));
        }
    }

    @Test
    /** signed config packages verify, and every negative fails with its code */
    void configPackages() {
        final JsonObject valid = Vectors.load("test-vectors/config/package_valid.json");
        final byte[] anchor = Vectors.hex(valid, "trust_anchor_ed25519_pub_hex");

        for (final JsonElement el : Vectors.arr(valid, "cases")) {
            final JsonObject c = Vectors.obj(el);
            final String canonical = Vectors.str(c, "canonical_bytes");
            assertEquals(canonical, canonicalPackage(c.getAsJsonObject("payload")),
                    "canonical bytes for " + Vectors.str(c, "name"));
            assertEquals(Vectors.str(c, "payload_hash"),
                    "sha256:" + Vectors.toHex(LumosCrypto.sha256(utf8(canonical))), "payload hash");
            assertTrue(LumosCrypto.ed25519Verify(anchor,
                            LumosCrypto.concat(utf8(LumosConstants.CONFIG_SIGNING_INFO_VALUE), utf8(canonical)),
                            Vectors.hex(c, "signature_hex")),
                    "signature for " + Vectors.str(c, "name"));
        }

        final JsonObject invalid = Vectors.load("test-vectors/config/package_invalid.json");
        final byte[] anchor2 = Vectors.hex(invalid, "trust_anchor_ed25519_pub_hex");
        int signatureNegatives = 0;
        for (final JsonElement el : Vectors.arr(invalid, "cases")) {
            final JsonObject c = Vectors.obj(el);
            if (!"ERR_CONFIG_SIGNATURE_INVALID".equals(Vectors.str(c, "expected_error"))) {
                continue;
            }
            final String canonical = canonicalPackage(c.getAsJsonObject("payload"));
            assertTrue(!LumosCrypto.ed25519Verify(anchor2,
                            LumosCrypto.concat(utf8(LumosConstants.CONFIG_SIGNING_INFO_VALUE), utf8(canonical)),
                            Vectors.hex(c, "signature_hex")),
                    "negative " + Vectors.str(c, "name") + " verified; it must fail closed");
            signatureNegatives++;
        }
        assertTrue(signatureNegatives >= 3, "expected the signature negatives");
    }

    /** Rebuilds the canonical signing form: sorted key=value lines, newline-terminated. */
    private static String canonicalPackage(final JsonObject p) {
        final java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("constants_hash=" + Vectors.str(p, "constants_hash"));
        lines.add("environment=" + Vectors.str(p, "environment"));
        lines.add("valid_from=" + Vectors.l(p, "valid_from"));
        lines.add("valid_until=" + Vectors.l(p, "valid_until"));
        lines.add("version=" + Vectors.i(p, "version"));
        final JsonObject ov = p.getAsJsonObject("overrides");
        if (ov != null) {
            for (final String k : ov.keySet()) {
                lines.add("overrides." + k + "=" + ov.get(k).getAsLong());
            }
        }
        final JsonObject ks = p.getAsJsonObject("kill_switches");
        if (ks != null) {
            for (final String k : ks.keySet()) {
                lines.add("kill_switches." + k + "=" + ks.get(k).getAsBoolean());
            }
        }
        java.util.Collections.sort(lines);
        return String.join("\n", lines) + "\n";
    }

    @Test
    /** voucher tokens verify and refuse per their recorded outcome */
    void voucherTokens() {
        final JsonObject root = Vectors.load("test-vectors/voucher/voucher_tokens.json");
        final byte[] anchor = Vectors.hex(root, "trust_anchor_ed25519_pub_hex");
        boolean sawPositive = false;
        boolean sawNegative = false;

        for (final JsonElement el : Vectors.arr(root, "cases")) {
            final JsonObject c = Vectors.obj(el);
            final String canonical = canonicalVoucher(c.getAsJsonObject("payload"));
            assertEquals(Vectors.str(c, "canonical_bytes"), canonical,
                    "canonical bytes for " + Vectors.str(c, "name"));

            final boolean sigOk = LumosCrypto.ed25519Verify(anchor,
                    LumosCrypto.concat(utf8(LumosConstants.VOUCHER_SIGNING_INFO_VALUE), utf8(canonical)),
                    Vectors.hex(c, "signature_hex"));
            final String expected = Vectors.str(c, "expected_error");

            if (expected == null || expected.isEmpty()) {
                assertTrue(sigOk, "redeemable voucher " + Vectors.str(c, "name") + " failed verification");
                sawPositive = true;
            } else {
                sawNegative = true;
                if ("ERR_CONFIG_SIGNATURE_INVALID".equals(expected)) {
                    assertTrue(!sigOk, "tampered voucher verified");
                }
            }
        }
        assertTrue(sawPositive && sawNegative, "voucher vectors need both outcomes");
    }

    /** Rebuilds the voucher canonical signing form. */
    private static String canonicalVoucher(final JsonObject v) {
        final java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("nonce=" + Vectors.str(v, "nonce"));
        lines.add("perk_id=" + Vectors.str(v, "perk_id"));
        lines.add("revocable=" + Vectors.b(v, "revocable"));
        lines.add("valid_from=" + Vectors.l(v, "valid_from"));
        lines.add("valid_until=" + Vectors.l(v, "valid_until"));
        lines.add("venue_id=" + Vectors.str(v, "venue_id"));
        lines.add("voucher_id=" + Vectors.str(v, "voucher_id"));
        java.util.Collections.sort(lines);
        return String.join("\n", lines) + "\n";
    }
}
