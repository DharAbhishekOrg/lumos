/*
 * Lumos - android-app/app / Identity generation on the runtime the app ships to.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-IDN-001, SRS-NFR-SEC-002, TRS-CRY-001, TRS-TST-001
 * ADRs: ADR-001, ADR-008
 * Threading: any
 * Notes: The other half of closeout finding 5.1. PlatformKeyAlgorithmInstrumentedTest
 * records that Android cannot generate an Ed25519 key; this test is the
 * resolution running next to it - the ADR-001 libsodium backend generating,
 * binding and verifying the ADR-008 identity on a real Android runtime, under
 * the real Android Keystore KEK.
 *
 * The vector half is the libsodium quarter of the identity_binding family
 * (Go generates; the JCA consumes on the JVM; CryptoKit on the macOS job).
 * Ed25519 signing is deterministic, so this backend is held to the committed
 * signature BYTES - byte equality across backends is what makes two
 * implementations one identity scheme instead of a fork.
 *
 * The JCA cross-check follows the assert-on-every-host rule (WP-02 S1): where
 * this runtime has JCA Ed25519 (API 33+), a libsodium-made binding must verify
 * under the JCA too; where it does not (the API 30 image), the algorithm must
 * be absent - a third outcome is a finding, never a skip.
 */

package com.lumos.app.identity;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.goterl.lazysodium.LazySodiumAndroid;
import com.goterl.lazysodium.SodiumAndroid;
import com.lumos.app.keystore.AndroidKekStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/** The libsodium identity backend, proven on device against the vectors. */
@RunWith(AndroidJUnit4.class)
public class IdentityGenerationInstrumentedTest {

    private static final String KEK_ALIAS = "lumos-s1-identity-kek";
    private static final String ENVELOPE_AAD = "lumos/local/identity-envelope/v1";

    private AndroidKekStore kek;

    @After
    public void cleanUpKekAlias() {
        if (kek != null) {
            try {
                kek.destroy();
            } catch (final GeneralSecurityException alreadyGone) {
                // Tests that destroy the KEK themselves land here; fine.
            }
        }
    }

    @Test
    public void theLibsodiumBackendReproducesEveryCommittedVector()
            throws IOException, JSONException, GeneralSecurityException {
        final JSONObject file = vectors();
        final JSONArray positive = file.getJSONArray("positive");
        assertTrue("an empty family proves nothing", positive.length() > 0);

        final LazySodiumAndroid sodium = new LazySodiumAndroid(new SodiumAndroid());
        final LibsodiumIdentityCrypto crypto = new LibsodiumIdentityCrypto(sodium);

        for (int i = 0; i < positive.length(); i++) {
            final JSONObject c = positive.getJSONObject(i);
            final String name = c.getString("name");
            final byte[] seed = unhex(c.getString("testonly_ed_seed_hex"));
            final byte[] scalar = unhex(c.getString("testonly_x_scalar_hex"));
            final byte[] message = unhex(c.getString("binding_message_hex"));

            final byte[] edPublic = new byte[32];
            final byte[] secret = new byte[64];
            assertTrue(name, sodium.cryptoSignSeedKeypair(edPublic, secret, seed));
            Arrays.fill(secret, (byte) 0);
            assertArrayEquals(name + ": ed_public", unhex(c.getString("ed_public_hex")), edPublic);

            final byte[] xPublic = new byte[32];
            assertTrue(name, sodium.cryptoScalarMultBase(xPublic, scalar));
            assertArrayEquals(name + ": x_public", unhex(c.getString("x_public_hex")), xPublic);

            final byte[] signature =
                    crypto.signBinding(RawKeys.ed25519PrivatePkcs8(seed), message);
            assertArrayEquals(name + ": the libsodium signature must equal the committed bytes",
                    unhex(c.getString("binding_signature_hex")), signature);
            assertTrue(name, crypto.verifyBinding(edPublic, message, signature));
        }

        final JSONArray negative = file.getJSONArray("negative");
        assertTrue("a family without negatives proves nothing", negative.length() > 0);
        final byte[] info = file.getString("binding_info").getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < negative.length(); i++) {
            final JSONObject c = negative.getJSONObject(i);
            final byte[] xPublic = unhex(c.getString("x_public_hex"));
            final byte[] message = new byte[info.length + xPublic.length];
            System.arraycopy(info, 0, message, 0, info.length);
            System.arraycopy(xPublic, 0, message, info.length, xPublic.length);
            assertFalse(c.getString("name") + ": a forged or transplanted binding verified",
                    crypto.verifyBinding(unhex(c.getString("ed_public_hex")), message,
                            unhex(c.getString("binding_signature_hex"))));
        }
    }

    @Test
    public void generationRunsOnThisRuntimeUnderTheRealKeystore()
            throws GeneralSecurityException {
        final LibsodiumIdentityCrypto crypto = LibsodiumIdentityCrypto.load();
        kek = AndroidKekStore.create(KEK_ALIAS);

        final IdentityKeyRecord record = IdentityKeyGenerator.generate(crypto, kek);
        assertEquals(32, record.ed25519Public().length);
        assertEquals(32, record.x25519Public().length);
        assertTrue("the ADR-008 binding must verify on the identity generated here",
                IdentityKeyGenerator.verifyBinding(crypto, record.ed25519Public(),
                        record.x25519Public(), record.bindingSignature()));

        // The wrapped envelope must open under the same KEK and contain exactly
        // the private halves of the publics in the record.
        final byte[] envelope = kek.unwrap(record.wrappedPrivateKeys(),
                ENVELOPE_AAD.getBytes(StandardCharsets.UTF_8));
        try {
            final PrivateKeyEnvelope.Keys keys = PrivateKeyEnvelope.decode(envelope);
            final byte[] seed = RawKeys.rawPrivateFromPkcs8(keys.ed25519Pkcs8(),
                    RawKeys.ED25519_PKCS8_PREFIX);
            final byte[] scalar = RawKeys.rawPrivateFromPkcs8(keys.x25519Pkcs8(),
                    RawKeys.X25519_PKCS8_PREFIX);
            final LazySodiumAndroid sodium = new LazySodiumAndroid(new SodiumAndroid());
            final byte[] edPublic = new byte[32];
            final byte[] secret = new byte[64];
            assertTrue(sodium.cryptoSignSeedKeypair(edPublic, secret, seed));
            Arrays.fill(secret, (byte) 0);
            assertArrayEquals("the enveloped Ed25519 private must match the record's public",
                    record.ed25519Public(), edPublic);
            final byte[] xPublic = new byte[32];
            assertTrue(sodium.cryptoScalarMultBase(xPublic, scalar));
            assertArrayEquals("the enveloped X25519 private must match the record's public",
                    record.x25519Public(), xPublic);
            Arrays.fill(seed, (byte) 0);
            Arrays.fill(scalar, (byte) 0);
        } finally {
            Arrays.fill(envelope, (byte) 0);
        }

        // Crypto-erasure: destroying the KEK makes the envelope unopenable.
        kek.destroy();
        try {
            kek.unwrap(record.wrappedPrivateKeys(),
                    ENVELOPE_AAD.getBytes(StandardCharsets.UTF_8));
            fail("a destroyed KEK must not unwrap the identity envelope");
        } catch (final GeneralSecurityException expected) {
            // The crypto-erasure guarantee holding.
        }
    }

    @Test
    public void whereThisRuntimeHasJcaEd25519ItAgreesWithLibsodium()
            throws GeneralSecurityException {
        final LibsodiumIdentityCrypto crypto = LibsodiumIdentityCrypto.load();
        final IdentityCrypto.KeyMaterial ed = crypto.generateEd25519();
        final byte[] message = ("test-binding-" + System.nanoTime())
                .getBytes(StandardCharsets.UTF_8);
        final byte[] signature = crypto.signBinding(ed.pkcs8(), message);
        ed.destroy();

        boolean jcaPresent;
        try {
            Signature.getInstance("Ed25519");
            jcaPresent = true;
        } catch (final GeneralSecurityException absent) {
            jcaPresent = false;
        }
        if (jcaPresent) {
            assertTrue("a libsodium signature must verify under this runtime's JCA",
                    new JcaIdentityCrypto().verifyBinding(ed.rawPublic(), message, signature));
        } else {
            // The API 26-32 half of the capability boundary: libsodium must be
            // self-sufficient because there is no JCA to agree with.
            assertTrue("libsodium must verify its own signature where the JCA is absent",
                    crypto.verifyBinding(ed.rawPublic(), message, signature));
        }
    }

    /**
     * Loads the committed vector file from the test APK's assets.
     *
     * <p>Read from the INSTRUMENTATION context, the RecoveryVectors rule: the
     * vectors ride the test APK, not the app under test.
     *
     * @return the parsed file
     * @throws IOException on a missing or unreadable asset
     * @throws JSONException on malformed JSON
     */
    private static JSONObject vectors() throws IOException, JSONException {
        try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open("crypto/identity_binding.json")) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] chunk = new byte[8192];
            int read = in.read(chunk);
            while (read > 0) {
                out.write(chunk, 0, read);
                read = in.read(chunk);
            }
            return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
        }
    }

    /**
     * Decodes lowercase hex.
     *
     * @param hex the string
     * @return the bytes
     */
    private static byte[] unhex(final String hex) {
        final byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
