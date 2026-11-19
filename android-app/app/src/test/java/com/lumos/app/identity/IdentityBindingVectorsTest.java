/*
 * Lumos - android-app/app / JCA conformance to the identity-binding vectors.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-CRY-001, TRS-TST-001, SRS-IDN-001
 * ADRs: ADR-001, ADR-008
 * Threading: any
 * Notes: The JVM quarter of the four-way identity_binding family (Go generates;
 * libsodium consumes on the emulator; CryptoKit on the macOS job). Ed25519
 * signing is deterministic, so the JCA backend is held to the committed
 * signature BYTES, not merely to "a signature that verifies" - byte equality is
 * what makes two backends one identity scheme instead of two.
 *
 * One honest gap, stated rather than papered over: the JCA exposes no
 * public-from-seed or scalar-mult API, so the ed_public and x_public fields are
 * not recomputed here - the three platforms that can derive them do. What the
 * JVM does prove: seed -> committed signature through the real signBinding
 * path (which pins RawKeys' PKCS#8 prefix against the JDK's own parser along
 * the way), verification of the committed signature under the committed
 * public, and rejection of every negative.
 */

package com.lumos.app.identity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** Holds the JCA backend to the committed identity-binding bytes. */
final class IdentityBindingVectorsTest {

    /**
     * Locates the committed vector file by walking up to the repository root.
     *
     * @return the parsed file
     * @throws IOException when the repository root cannot be found
     */
    private static JsonObject vectors() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("test-vectors"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IOException("test-vectors not found above " + Path.of("").toAbsolutePath());
        }
        final Path file = dir.resolve("test-vectors/crypto/identity_binding.json");
        // Files.readString is absent from the android.jar this module compiles
        // against; readAllBytes is the API-26-era equivalent and is fine here.
        return JsonParser.parseString(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    @Test
    void theJcaBackendReproducesEveryCommittedSignature()
            throws IOException, GeneralSecurityException {
        final JsonObject file = vectors();
        final JsonArray positive = file.getAsJsonArray("positive");
        assertTrue(positive.size() > 0, "an empty family proves nothing");
        final JcaIdentityCrypto crypto = new JcaIdentityCrypto();
        final HexFormat hex = HexFormat.of();

        for (int i = 0; i < positive.size(); i++) {
            final JsonObject c = positive.get(i).getAsJsonObject();
            final String name = c.get("name").getAsString();
            final byte[] seed = hex.parseHex(c.get("testonly_ed_seed_hex").getAsString());
            final byte[] message = hex.parseHex(c.get("binding_message_hex").getAsString());
            final byte[] expected = hex.parseHex(c.get("binding_signature_hex").getAsString());
            final byte[] edPublic = hex.parseHex(c.get("ed_public_hex").getAsString());

            final byte[] pkcs8 = RawKeys.ed25519PrivatePkcs8(seed);
            assertArrayEquals(expected, crypto.signBinding(pkcs8, message),
                    name + ": the JCA signature must equal the committed bytes");
            assertTrue(crypto.verifyBinding(edPublic, message, expected),
                    name + ": the committed signature must verify under the committed public");
        }
    }

    @Test
    void everyNegativeFailsVerification() throws IOException {
        final JsonObject file = vectors();
        final JsonArray negative = file.getAsJsonArray("negative");
        assertTrue(negative.size() > 0, "a family without negatives proves nothing");
        final JcaIdentityCrypto crypto = new JcaIdentityCrypto();
        final HexFormat hex = HexFormat.of();
        final byte[] info = file.get("binding_info").getAsString().getBytes(StandardCharsets.UTF_8);

        for (int i = 0; i < negative.size(); i++) {
            final JsonObject c = negative.get(i).getAsJsonObject();
            final byte[] xPublic = hex.parseHex(c.get("x_public_hex").getAsString());
            final byte[] message = new byte[info.length + xPublic.length];
            System.arraycopy(info, 0, message, 0, info.length);
            System.arraycopy(xPublic, 0, message, info.length, xPublic.length);
            assertFalse(crypto.verifyBinding(hex.parseHex(c.get("ed_public_hex").getAsString()),
                    message, hex.parseHex(c.get("binding_signature_hex").getAsString())),
                    c.get("name").getAsString() + ": a forged or transplanted binding verified");
        }
    }

    @Test
    void thePkcs8PrefixesAreTheOnesTheJdkItselfParses() throws GeneralSecurityException {
        // The libsodium backend builds PKCS#8 by prefix concatenation
        // (RawKeys); if either transcribed prefix drifted from RFC 8410, every
        // envelope it wrote would be unreadable to a JCA runtime. Handing the
        // built encodings to the JDK's own parsers pins both prefixes to the
        // specification rather than to this repository's opinion of it.
        final byte[] seed = new byte[32];
        final byte[] scalar = new byte[32];
        seed[0] = 7;
        scalar[0] = 9;
        final byte[] edPkcs8 = RawKeys.ed25519PrivatePkcs8(seed);
        final byte[] xPkcs8 = RawKeys.x25519PrivatePkcs8(scalar);
        java.security.KeyFactory.getInstance("Ed25519")
                .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(edPkcs8));
        java.security.KeyFactory.getInstance("XDH")
                .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(xPkcs8));
        assertArrayEquals(seed, RawKeys.rawPrivateFromPkcs8(edPkcs8, RawKeys.ED25519_PKCS8_PREFIX));
        assertArrayEquals(scalar, RawKeys.rawPrivateFromPkcs8(xPkcs8, RawKeys.X25519_PKCS8_PREFIX));
        org.junit.jupiter.api.Assertions.assertThrows(GeneralSecurityException.class,
                () -> RawKeys.rawPrivateFromPkcs8(edPkcs8, RawKeys.X25519_PKCS8_PREFIX),
                "a wrong-algorithm prefix must be refused, not silently accepted");
    }
}
