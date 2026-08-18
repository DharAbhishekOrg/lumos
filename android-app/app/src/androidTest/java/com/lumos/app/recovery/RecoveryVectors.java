/*
 * Lumos - android-app/app / Committed recovery-vector loader for the device.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-TST-001, TRS-CONST-005, SRS-NFR-CMP-004
 * ADRs: ADR-003, ADR-004
 * Threading: any
 * Notes: An instrumented test runs on a device, where the repository does not
 * exist, so the committed vectors ride into the TEST apk as assets - declared in
 * build.gradle.kts as a source directory pointing at /test-vectors itself, never
 * as a copy. Asset paths are therefore relative to that directory.
 *
 * Read from the INSTRUMENTATION context, not the application one: assets
 * declared on the androidTest source set live in the test apk, and
 * getTargetContext() would look in the app apk and find nothing.
 *
 * A missing file fails the run outright. TRS Sec. 18.4 is explicit that a
 * skipped vector file is a failed gate, so there is deliberately no "skip if
 * absent" path here - which matters more on this platform than on the others,
 * because a path-filtered emulator job that quietly found no vectors would
 * otherwise report green.
 */

package com.lumos.app.recovery;

import androidx.test.platform.app.InstrumentationRegistry;
import com.lumos.protocol.LumosConstants;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

/** Reads the committed vector files out of the test apk's assets. */
final class RecoveryVectors {

    /** Asset path, relative to the /test-vectors source directory. */
    static final String RECOVERY_RECORD = "crypto/recovery_record.json";

    private RecoveryVectors() {
        throw new AssertionError("no instances");
    }

    /**
     * Loads a vector file and verifies it is current.
     *
     * @param assetPath path under /test-vectors
     * @return the parsed root object
     * @throws IOException if the asset cannot be read
     * @throws JSONException if it is not the JSON object it should be
     */
    static JSONObject load(final String assetPath) throws IOException, JSONException {
        final byte[] raw;
        try (InputStream in = InstrumentationRegistry.getInstrumentation()
                .getContext().getAssets().open(assetPath)) {
            raw = in.readAllBytes();
        }
        final JSONObject root = new JSONObject(new String(raw, StandardCharsets.UTF_8));
        final String hash = root.getString("constants_hash");
        if (!LumosConstants.CONSTANTS_HASH.equals(hash)) {
            throw new AssertionError(
                    "stale vector file " + assetPath + ": constants_hash " + hash
                    + " does not match this build's " + LumosConstants.CONSTANTS_HASH
                    + " (TRS-CONST-005)");
        }
        return root;
    }

    /**
     * @param object the containing object
     * @param field the field name
     * @return the field decoded from hex
     * @throws JSONException if the field is absent
     */
    static byte[] hex(final JSONObject object, final String field) throws JSONException {
        return hexToBytes(object.getString(field));
    }

    /**
     * @param hex an even-length hex string, possibly empty
     * @return the bytes it encodes
     */
    static byte[] hexToBytes(final String hex) {
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("odd-length hex: " + hex.length() + " characters");
        }
        final byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /**
     * @param bytes any byte string
     * @return its lower-case hex form, for failure messages that can be diffed
     *     against the vector file by eye
     */
    static String toHex(final byte[] bytes) {
        final StringBuilder out = new StringBuilder(bytes.length * 2);
        for (final byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }
}
