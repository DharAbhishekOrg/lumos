/*
 * Lumos - android-app/app / Consent records as sealed-record payloads.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-002, SRS-DAT-002
 * ADRs: -
 * Threading: any
 * Notes: The same tab-separated line format ProfileCodec uses, for the same
 * reason: it is readable in a test failure, it round-trips exactly, and it does
 * not drag a parser dependency into a record that is four fields long.
 *
 * An unknown version line fails closed. A consent record this build cannot
 * parse is not "no consent" and must not be treated as one - treating it as
 * absent would silently re-ask, and re-asking is the benign case; treating it
 * as present would attest to a version nobody can name.
 */

package com.lumos.app.onboarding;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

/** Encodes and decodes the stored consent record. */
public final class ConsentCodec {

    /** Payload format version; an unknown one fails closed. */
    private static final String VERSION_LINE = "lumos-consent/1";

    /** Field count of an accepted-version line, including its tag. */
    private static final int ACCEPTED_PARTS = 3;

    private ConsentCodec() {
        throw new AssertionError("no instances");
    }

    /**
     * Encodes a consent record.
     *
     * @param record the record
     * @return the payload bytes
     */
    public static byte[] encode(final ConsentRecord record) {
        final StringBuilder out = new StringBuilder(VERSION_LINE).append('\n');
        out.append("affirmed_at\t").append(record.affirmedAtUtcMillis()).append('\n');
        out.append("minimum_age_years\t").append(record.minimumAgeYears()).append('\n');
        for (final LegalDocument document : LegalDocument.values()) {
            final String version = record.acceptedVersions().get(document);
            if (version != null) {
                out.append("accepted\t").append(document.name()).append('\t')
                        .append(version).append('\n');
            }
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Decodes a consent record.
     *
     * @param payload the payload bytes
     * @return the record
     * @throws IllegalArgumentException on an unknown format version or a
     *     malformed line
     */
    public static ConsentRecord decode(final byte[] payload) {
        final String[] lines = new String(payload, StandardCharsets.UTF_8).split("\n", -1);
        if (lines.length == 0 || !VERSION_LINE.equals(lines[0])) {
            throw new IllegalArgumentException("consent payload is not " + VERSION_LINE);
        }
        long affirmedAt = 0;
        int minimumAge = 0;
        final Map<LegalDocument, String> accepted = new EnumMap<>(LegalDocument.class);
        for (int i = 1; i < lines.length; i++) {
            final String line = lines[i];
            if (line.isEmpty()) {
                continue;
            }
            final String[] parts = line.split("\t", -1);
            switch (parts[0]) {
                case "affirmed_at":
                    affirmedAt = Long.parseLong(parts[1]);
                    break;
                case "minimum_age_years":
                    minimumAge = Integer.parseInt(parts[1]);
                    break;
                case "accepted":
                    if (parts.length != ACCEPTED_PARTS) {
                        throw new IllegalArgumentException("malformed accepted line");
                    }
                    accepted.put(LegalDocument.valueOf(parts[1]), parts[2]);
                    break;
                default:
                    throw new IllegalArgumentException("unknown consent line " + parts[0]);
            }
        }
        return new ConsentRecord(affirmedAt, minimumAge, accepted);
    }
}
