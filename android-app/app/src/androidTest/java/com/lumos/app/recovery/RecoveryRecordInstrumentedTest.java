/*
 * Lumos - android-app/app / The RecoveryRecord against the cross-language vectors.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-007, SRS-DAT-004, TRS-CRY-001, TRS-CRY-013, TRS-TST-001
 * ADRs: ADR-001, ADR-003
 * Threading: any
 * Notes: These are instrumented rather than JVM tests for one reason: Argon2id
 * comes from libsodium, and libsodium arrives as an Android .so. There is no
 * honest way to run this on the authoring host, and a JVM test with a fake KDF
 * would prove only that the fake agrees with itself. So the whole family runs on
 * an emulator, in the path-filtered job the owner ruled for the Room work.
 *
 * If that job is ever skipped these identifiers simply do not appear in the
 * executed set, and the traceability gate treats a runner that produced nothing
 * as a skipped job rather than as drift.
 *
 * ONE WARNING FOR WHOEVER SEES THIS TIME OUT. The inflation cases assert that a
 * record claiming absurd work factors is refused from its header ALONE. If that
 * guard regresses, this test does not fail - it HANGS, or the process is killed
 * by the out-of-memory reaper, because libsodium will honestly try to allocate
 * the 8 GiB the header asked for. A timeout here is the symptom of a real
 * denial-of-service hole, not of a slow emulator.
 */

package com.lumos.app.recovery;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.LumosErrors;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Every committed recovery vector, executed on a real Android runtime. */
@RunWith(AndroidJUnit4.class)
public class RecoveryRecordInstrumentedTest {

    /** Loaded once: SodiumAndroid loads a native library in its constructor. */
    private static LibsodiumRecoveryCrypto crypto;

    private RecoveryRecord record;
    private JSONObject vectors;

    @BeforeClass
    public static void loadLibsodium() {
        crypto = LibsodiumRecoveryCrypto.load();
    }

    @Before
    public void setUp() throws Exception {
        record = new RecoveryRecord(crypto);
        vectors = RecoveryVectors.load(RecoveryVectors.RECOVERY_RECORD);
    }

    @Test
    public void theFormatConstantsMatchTheVectorFile() throws Exception {
        final JSONObject format = vectors.getJSONObject("format");
        assertEquals("format_version", format.getInt("format_version"), RecoveryRecord.FORMAT_VERSION);
        assertEquals("header_bytes", format.getInt("header_bytes"), RecoveryRecord.HEADER_BYTES);
        assertEquals("authenticated_header_bytes",
                format.getInt("authenticated_header_bytes"), RecoveryRecord.AUTHENTICATED_HEADER_BYTES);
        assertEquals("nonce_bytes", format.getInt("nonce_bytes"), RecoveryRecord.NONCE_BYTES);
        assertEquals("salt_bytes",
                format.getInt("salt_bytes"), LumosConstants.RECOVERY_ARGON2ID_SALT_BYTES);
        assertEquals("key_bytes",
                format.getInt("key_bytes"), LumosConstants.RECOVERY_ARGON2ID_KEY_BYTES);
    }

    @Test
    public void theAcceptedBoundsMatchTheVectorFile() throws Exception {
        final JSONObject limits = vectors.getJSONObject("limits");
        assertEquals("max_acceptable_memory_kib",
                limits.getLong("max_acceptable_memory_kib"), RecoveryParams.MAX_ACCEPTABLE_MEMORY_KIB);
        assertEquals("max_acceptable_iterations",
                limits.getLong("max_acceptable_iterations"), RecoveryParams.MAX_ACCEPTABLE_ITERATIONS);
        assertEquals("supported_parallelism",
                limits.getInt("supported_parallelism"), RecoveryParams.SUPPORTED_PARALLELISM);
        // The single-lane rule replaces the register's parallelism minimum. That
        // is only safe while the register's minimum IS one lane; if it ever
        // moves, every record becomes unopenable and this is what says so.
        assertEquals("the register's parallelism minimum is no longer the supported lane count",
                RecoveryParams.SUPPORTED_PARALLELISM, LumosConstants.RECOVERY_ARGON2ID_PARALLELISM_MIN);
    }

    /**
     * Argon2id on its own. When a record will not open and nobody can say why,
     * this is the test that separates "the KDF disagrees" from "the framing
     * disagrees" - and it is the one that would have caught a binding which
     * ignored the work factors, since libsodium takes memory in bytes where the
     * header carries KiB.
     */
    @Test
    public void argon2idAgreesWithTheReferenceDerivation() throws Exception {
        final JSONArray cases = vectors.getJSONArray("derivation");
        assertTrue("no derivation cases", cases.length() > 0);
        for (int i = 0; i < cases.length(); i++) {
            final JSONObject c = cases.getJSONObject(i);
            final byte[] derived = crypto.argon2id(
                    RecoveryVectors.hex(c, "passphrase_hex"),
                    RecoveryVectors.hex(c, "salt_hex"),
                    paramsOf(c.getJSONObject("params")));
            assertEquals("derived key for " + c.getString("name"),
                    c.getString("derived_key_hex"), RecoveryVectors.toHex(derived));
        }
    }

    /**
     * Both directions. Opening the committed record proves this build can read
     * what the reference wrote; re-sealing the same inputs and reproducing the
     * same bytes proves it can WRITE something the reference could read, which
     * opening alone never shows.
     */
    @Test
    public void everyCommittedRecordOpensAndReSeals() throws Exception {
        final JSONArray cases = vectors.getJSONArray("positive");
        assertTrue("no positive cases", cases.length() > 0);
        for (int i = 0; i < cases.length(); i++) {
            final JSONObject c = cases.getJSONObject(i);
            final String name = c.getString("name");
            final byte[] passphrase = RecoveryVectors.hex(c, "passphrase_hex");
            final byte[] expected = RecoveryVectors.hex(c, "record_hex");
            final byte[] content = RecoveryVectors.hex(c, "content_hex");

            assertArrayEquals("opened content for " + name,
                    content, record.open(passphrase, expected));

            final byte[] resealed = record.exportWith(passphrase, content,
                    RecoveryVectors.hex(c, "salt_hex"), RecoveryVectors.hex(c, "nonce_hex"),
                    paramsOf(c.getJSONObject("params")));
            assertEquals("re-sealed record for " + name,
                    c.getString("record_hex"), RecoveryVectors.toHex(resealed));

            assertEquals("authenticated header for " + name,
                    c.getString("authenticated_header_hex"),
                    RecoveryVectors.toHex(Arrays.copyOf(
                            resealed, RecoveryRecord.AUTHENTICATED_HEADER_BYTES)));
        }
    }

    /**
     * Every record that must be refused, with the code it must be refused under.
     * See the file header before concluding a timeout here is flakiness.
     */
    @Test
    public void everyNegativeVectorIsRefusedWithTheRightCode() throws Exception {
        final JSONArray cases = vectors.getJSONArray("negative");
        assertTrue("no negative cases", cases.length() > 0);
        final Set<String> families = new HashSet<>();
        for (int i = 0; i < cases.length(); i++) {
            final JSONObject c = cases.getJSONObject(i);
            families.add(c.getString("family"));
            final String name = c.getString("name");
            try {
                record.open(RecoveryVectors.hex(c, "passphrase_hex"),
                        RecoveryVectors.hex(c, "record_hex"));
                fail("the record was ACCEPTED: " + name);
            } catch (RecoveryException e) {
                assertEquals("error code for " + name, c.getString("expected_error"), e.code());
            }
        }
        // Both attacks the format is shaped around must actually be present. A
        // vector file that lost its inflation cases would leave this green while
        // testing half of what the record defends against.
        assertTrue("the vector family carries no downgrade cases", families.contains("downgrade"));
        assertTrue("the vector family carries no inflation cases", families.contains("inflation"));
    }

    /**
     * TRS-CRY-013 as a property of the whole set rather than of one pair: a
     * caller must not be able to tell a wrong passphrase from a tampered file by
     * reading the error, so every case that reaches authentication reports the
     * identical message.
     */
    @Test
    public void openingFailuresAreIndistinguishable() throws Exception {
        final JSONArray cases = vectors.getJSONArray("negative");
        final Set<String> messages = new HashSet<>();
        for (int i = 0; i < cases.length(); i++) {
            final JSONObject c = cases.getJSONObject(i);
            if (!LumosErrors.ERR_CRYPTO_RECOVERY_OPEN_FAILED.equals(c.getString("expected_error"))) {
                continue;
            }
            try {
                record.open(RecoveryVectors.hex(c, "passphrase_hex"),
                        RecoveryVectors.hex(c, "record_hex"));
                fail("the record was ACCEPTED: " + c.getString("name"));
            } catch (RecoveryException e) {
                messages.add(e.getMessage());
            }
        }
        assertEquals("opening failures are distinguishable by their message: " + messages,
                1, messages.size());
    }

    /**
     * ADR-003 as a scan rather than as a claim: export content that does not
     * contain the passphrase, then look for the passphrase in the whole blob.
     * This is what a person holding the file could do, so it is what the test
     * does.
     */
    @Test
    public void anExportedRecordLeaksNeitherPassphraseNorContent() throws Exception {
        final byte[] passphrase = "TESTONLY-instrumented-passphrase".getBytes("UTF-8");
        final byte[] content = "TESTONLY-instrumented-content-alias-and-block-list".getBytes("UTF-8");
        final byte[] exported = record.export(passphrase, content);

        assertEquals("the exported record does not contain the passphrase",
                -1, indexOf(exported, passphrase));
        assertEquals("the exported record does not contain the plaintext content",
                -1, indexOf(exported, content));
        assertArrayEquals("and it still opens", content, record.open(passphrase, exported));
    }

    /**
     * A fresh salt and nonce per export are what stop two exports of the same
     * content being linkable to each other - and, far more seriously, what stop
     * a (key, nonce) pair from ever repeating.
     */
    @Test
    public void twoExportsOfTheSameContentDiffer() throws Exception {
        final byte[] passphrase = "TESTONLY-instrumented-passphrase".getBytes("UTF-8");
        final byte[] content = "TESTONLY-same-content-twice".getBytes("UTF-8");
        assertNotEquals("two exports must not be byte-identical",
                RecoveryVectors.toHex(record.export(passphrase, content)),
                RecoveryVectors.toHex(record.export(passphrase, content)));
    }

    /**
     * @param params the vector's parameter object
     * @return the same work factors as a RecoveryParams
     * @throws JSONException if a field is missing
     */
    private static RecoveryParams paramsOf(final JSONObject params) throws JSONException {
        return new RecoveryParams(
                params.getLong("memory_kib"),
                params.getLong("iterations"),
                params.getInt("parallelism"));
    }

    /**
     * @param haystack the bytes to search
     * @param needle the bytes to look for
     * @return the first index of needle in haystack, or -1
     */
    private static int indexOf(final byte[] haystack, final byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
