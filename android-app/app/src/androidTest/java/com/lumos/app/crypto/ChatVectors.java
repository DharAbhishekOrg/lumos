/*
 * Lumos - android-app/app / Committed vector loader for the on-device crypto replays.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-TST-001, TRS-CONST-005
 * ADRs: ADR-001, ADR-004
 * Threading: any
 * Notes: The same rules as RecoveryVectors: the vectors ride into the test
 * apk as assets from /test-vectors itself, are read from the INSTRUMENTATION
 * context, are read by hand (InputStream.readAllBytes is API 33), and a
 * stale or missing file fails the run - never a skip (TRS Sec. 18.4).
 */

package com.lumos.app.crypto;

import androidx.test.platform.app.InstrumentationRegistry;
import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.ratchet.DoubleRatchet;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayDeque;
import java.util.Deque;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Reads the committed vector files out of the test apk's assets. */
final class ChatVectors {

    private static final int READ_BUFFER_BYTES = 8192;
    private static final String DIGITS = "0123456789abcdef";

    private ChatVectors() {
        throw new AssertionError("no instances");
    }

    static JSONObject load(final String assetPath) throws IOException, JSONException {
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(assetPath)) {
            final byte[] buffer = new byte[READ_BUFFER_BYTES];
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                raw.write(buffer, 0, read);
            }
        }
        final JSONObject root = new JSONObject(new String(raw.toByteArray(), StandardCharsets.UTF_8));
        final String hash = root.getString("constants_hash");
        if (!LumosConstants.CONSTANTS_HASH.equals(hash)) {
            throw new AssertionError("stale vector file " + assetPath + ": constants_hash " + hash
                    + " does not match this build's " + LumosConstants.CONSTANTS_HASH + " (TRS-CONST-005)");
        }
        return root;
    }

    static byte[] hex(final JSONObject object, final String field) throws JSONException {
        return unhex(object.getString(field));
    }

    static byte[] unhex(final String hex) {
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("odd-length hex");
        }
        final byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    static String toHex(final byte[] b) {
        final StringBuilder s = new StringBuilder(b.length * 2);
        for (final byte x : b) {
            s.append(DIGITS.charAt((x >> 4) & 0xF)).append(DIGITS.charAt(x & 0xF));
        }
        return s.toString();
    }

    /** Replays a recorded list of ratchet turn scalars, as every other consumer does. */
    static DoubleRatchet.KeyProvider recorded(final JSONArray hexScalars) throws JSONException {
        final Deque<byte[]> keys = new ArrayDeque<>();
        for (int i = 0; i < hexScalars.length(); i++) {
            keys.add(unhex(hexScalars.getString(i)));
        }
        return () -> {
            if (keys.isEmpty()) {
                throw new GeneralSecurityException("recorded turn-key list exhausted");
            }
            return keys.poll();
        };
    }
}
