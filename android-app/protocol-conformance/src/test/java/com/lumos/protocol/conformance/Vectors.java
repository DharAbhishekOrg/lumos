/*
 * Lumos - protocol-conformance / Committed golden-vector loader.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-TST-001, TRS-CONST-005, TRS-PROTO-015
 * ADRs: ADR-004
 * Threading: any
 * Notes: Loads the COMMITTED /test-vectors files - never generates anything
 * (single-generator rule). A missing file fails the run outright: TRS Sec. 18.4
 * is explicit that a skipped vector file is a failed gate, so there is
 * deliberately no "skip if absent" path here. Every file's constants_hash is
 * checked against the register, so stale vectors fail loudly rather than
 * passing against an old protocol.
 */
package com.lumos.protocol.conformance;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lumos.protocol.LumosConstants;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Reads and validates the committed vector files. */
public final class Vectors {

    private Vectors() {
        throw new AssertionError("no instances");
    }

    /** @return the repository root, supplied by the Gradle test task */
    public static Path repoRoot() {
        final String root = System.getProperty("lumos.repoRoot");
        if (root == null || root.isEmpty()) {
            throw new IllegalStateException("lumos.repoRoot system property is not set");
        }
        return Paths.get(root);
    }

    /**
     * Loads a vector file and verifies it is current.
     *
     * @param relativePath path under the repository root
     * @return the parsed root object
     */
    public static JsonObject load(final String relativePath) {
        final Path p = repoRoot().resolve(relativePath);
        if (!Files.exists(p)) {
            throw new AssertionError(
                    "committed vector file is missing: " + relativePath
                    + " - a skipped vector file is a FAILED GATE (TRS Sec. 18.4)");
        }
        final JsonObject root;
        try {
            root = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + relativePath, e);
        }
        final String hash = root.get("constants_hash").getAsString();
        if (!LumosConstants.CONSTANTS_HASH.equals(hash)) {
            throw new AssertionError(
                    "stale vector file " + relativePath + ": constants_hash " + hash
                    + " does not match this build's " + LumosConstants.CONSTANTS_HASH
                    + " (TRS-CONST-005)");
        }
        return root;
    }

    /**
     * @param o containing object
     * @param field field name
     * @return the field decoded from hex
     */
    public static byte[] hex(final JsonObject o, final String field) {
        return hexToBytes(o.get(field).getAsString());
    }

    /**
     * @param hex a lowercase hex string
     * @return the decoded bytes
     */
    public static byte[] hexToBytes(final String hex) {
        if ((hex.length() & 1) != 0) {
            throw new IllegalArgumentException("odd-length hex");
        }
        final byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /**
     * @param b bytes
     * @return lowercase hex
     */
    public static String toHex(final byte[] b) {
        final StringBuilder sb = new StringBuilder(b.length * 2);
        for (final byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * @param o containing object
     * @param field array field name
     * @return the array
     */
    public static JsonArray arr(final JsonObject o, final String field) {
        return o.getAsJsonArray(field);
    }

    /**
     * @param e element
     * @return it as an object
     */
    public static JsonObject obj(final JsonElement e) {
        return e.getAsJsonObject();
    }

    /**
     * @param o containing object
     * @param field field name
     * @return the string value, or null when absent
     */
    public static String str(final JsonObject o, final String field) {
        final JsonElement e = o.get(field);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    /**
     * @param o containing object
     * @param field field name
     * @return the int value
     */
    public static int i(final JsonObject o, final String field) {
        return o.get(field).getAsInt();
    }

    /**
     * @param o containing object
     * @param field field name
     * @return the long value
     */
    public static long l(final JsonObject o, final String field) {
        return o.get(field).getAsLong();
    }

    /**
     * @param o containing object
     * @param field field name
     * @return the boolean value
     */
    public static boolean b(final JsonObject o, final String field) {
        final JsonElement e = o.get(field);
        return e != null && !e.isJsonNull() && e.getAsBoolean();
    }
}
