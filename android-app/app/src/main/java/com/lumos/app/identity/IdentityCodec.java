/*
 * Lumos - android-app/app / The identity record as a sealed-record payload.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-IDN-001, SRS-ONB-006, SRS-DAT-002
 * ADRs: ADR-003, ADR-008, ADR-010
 * Threading: any
 * Notes: What goes to disk is two public keys, the ADR-008 binding signature,
 * the KEK-wrapped private envelope and the recorded protection tier. The
 * envelope is already ciphertext when it arrives here and this codec does not
 * open it; the row is then sealed again by the store's own AEAD, so the private
 * material is wrapped twice and the outer wrapping is the one wipe destroys.
 *
 * Base64 rather than raw bytes because the payload format is line-oriented like
 * every other codec in this application, and a tab-separated format that could
 * contain a tab is not a format. The cost is a third more bytes for a record
 * written once.
 */

package com.lumos.app.identity;

import com.lumos.app.keystore.KeyProtection;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Encodes and decodes the stored identity record. */
public final class IdentityCodec {

    /** Payload format version; an unknown one fails closed. */
    private static final String VERSION_LINE = "lumos-identity/1";

    /** Lines a well-formed payload carries after its version line. */
    private static final int FIELD_LINES = 5;

    private IdentityCodec() {
        throw new AssertionError("no instances");
    }

    /**
     * Encodes an identity record.
     *
     * @param record the record
     * @return the payload bytes
     */
    public static byte[] encode(final IdentityKeyRecord record) {
        final Base64.Encoder encoder = Base64.getEncoder();
        final StringBuilder out = new StringBuilder(VERSION_LINE).append('\n');
        out.append(encoder.encodeToString(record.ed25519Public())).append('\n');
        out.append(encoder.encodeToString(record.x25519Public())).append('\n');
        out.append(encoder.encodeToString(record.bindingSignature())).append('\n');
        out.append(encoder.encodeToString(record.wrappedPrivateKeys())).append('\n');
        out.append(record.protection().name()).append('\n');
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Decodes an identity record.
     *
     * @param payload the payload bytes
     * @return the record
     * @throws IllegalArgumentException on an unknown version or a short payload
     */
    public static IdentityKeyRecord decode(final byte[] payload) {
        final String[] lines = new String(payload, StandardCharsets.UTF_8).split("\n", -1);
        if (lines.length < FIELD_LINES + 1 || !VERSION_LINE.equals(lines[0])) {
            throw new IllegalArgumentException("identity payload is not " + VERSION_LINE);
        }
        final Base64.Decoder decoder = Base64.getDecoder();
        return new IdentityKeyRecord(
                decoder.decode(lines[1]),
                decoder.decode(lines[2]),
                decoder.decode(lines[3]),
                decoder.decode(lines[4]),
                KeyProtection.valueOf(lines[5]));
    }
}
