/*
 * Lumos - android-app/app / The recovery bundle's on-record encoding.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-007, SRS-DAT-004
 * ADRs: ADR-003
 * Threading: any
 * Notes: Line-oriented and Base64, like the other codecs here, and for a reason
 * this one feels more strongly than the rest: these bytes end up in a file the
 * user keeps for a year and opens on a build nobody has written yet. A format
 * a human can read in a hex editor and a later maintainer can parse from the
 * spec in this comment is worth more than a compact one.
 *
 * The format:
 *
 *   lumos-recovery-bundle/1
 *   exported_at<TAB>1755561600000
 *   unavailable<TAB>match_roster
 *   unavailable<TAB>conversation_history
 *   row<TAB>profile<TAB><id><TAB><base64 content>
 *
 * Unknown line tags are a REFUSAL, not a skip. A later build's bundle read by
 * an older one must fail loudly: silently dropping a section the reader does
 * not recognise would restore a partial profile and report success.
 */

package com.lumos.app.recovery;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Encodes and decodes the plaintext a recovery record seals. */
public final class RecoveryBundleCodec {

    /** Bundle format version; an unknown one fails closed. */
    private static final String VERSION_LINE = "lumos-recovery-bundle/1";

    /** Field count of a row line, including its tag. */
    private static final int ROW_PARTS = 4;

    /** Field count of a two-field line, including its tag. */
    private static final int PAIR_PARTS = 2;

    private RecoveryBundleCodec() {
        throw new AssertionError("no instances");
    }

    /**
     * Encodes a bundle.
     *
     * @param bundle the assembled content
     * @return the plaintext to seal
     */
    public static byte[] encode(final RecoveryBundle bundle) {
        final Base64.Encoder encoder = Base64.getEncoder();
        final StringBuilder out = new StringBuilder(VERSION_LINE).append('\n');
        out.append("exported_at\t").append(bundle.exportedAtUtcMillis()).append('\n');
        for (final RecoverySection section : bundle.unavailableSections()) {
            out.append("unavailable\t").append(section.label()).append('\n');
        }
        for (final RecoverySection section : RecoverySection.values()) {
            for (final RecoveryBundle.Item item : bundle.items(section)) {
                out.append("row\t").append(section.label()).append('\t')
                        .append(item.id()).append('\t')
                        .append(encoder.encodeToString(item.content())).append('\n');
            }
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Decodes a bundle.
     *
     * @param plaintext the opened record content
     * @return the bundle
     * @throws IllegalArgumentException on an unknown version, an unknown line
     *     tag, an unknown section label, or a malformed line
     */
    public static RecoveryBundle decode(final byte[] plaintext) {
        final String[] lines = new String(plaintext, StandardCharsets.UTF_8).split("\n", -1);
        if (lines.length == 0 || !VERSION_LINE.equals(lines[0])) {
            throw new IllegalArgumentException("recovery bundle is not " + VERSION_LINE);
        }
        final Base64.Decoder decoder = Base64.getDecoder();
        final Map<RecoverySection, List<RecoveryBundle.Item>> sections =
                new EnumMap<>(RecoverySection.class);
        long exportedAt = 0;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) {
                continue;
            }
            final String[] parts = lines[i].split("\t", -1);
            switch (parts[0]) {
                case "exported_at":
                    requireParts(parts, PAIR_PARTS);
                    exportedAt = Long.parseLong(parts[1]);
                    break;
                case "unavailable":
                    requireParts(parts, PAIR_PARTS);
                    sectionFor(parts[1]);
                    break;
                case "row":
                    requireParts(parts, ROW_PARTS);
                    sections.computeIfAbsent(sectionFor(parts[1]), k -> new ArrayList<>())
                            .add(new RecoveryBundle.Item(parts[2], decoder.decode(parts[3])));
                    break;
                default:
                    throw new IllegalArgumentException(
                            "unknown recovery bundle line '" + parts[0] + "'");
            }
        }
        return new RecoveryBundle(exportedAt, sections);
    }

    /**
     * Resolves a section label written by some build of this format.
     *
     * @param label the wire label
     * @return the section
     * @throws IllegalArgumentException when no section carries that label
     */
    private static RecoverySection sectionFor(final String label) {
        for (final RecoverySection section : RecoverySection.values()) {
            if (section.label().equals(label)) {
                return section;
            }
        }
        throw new IllegalArgumentException("unknown recovery section '" + label + "'");
    }

    /**
     * Refuses a line with the wrong field count.
     *
     * @param parts the split line
     * @param expected how many fields the tag requires
     */
    private static void requireParts(final String[] parts, final int expected) {
        if (parts.length != expected) {
            throw new IllegalArgumentException("malformed '" + parts[0] + "' line");
        }
    }
}
