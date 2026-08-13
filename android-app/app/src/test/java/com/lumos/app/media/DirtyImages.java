/*
 * Lumos - android-app/app / The known-dirty image fixtures.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-PRO-005, SRS-NFR-PRI-008, TRS-TST-001
 * ADRs: -
 * Threading: any
 * Notes: The fixtures are BUILT IN CODE rather than committed as binaries, and
 * that is a deliberate choice about reviewability. A committed .jpg with EXIF
 * is a blob nobody can read in a diff: a reviewer has to take on trust both
 * that it contains the metadata the test claims and that it contains nothing
 * else. Assembled here, every planted byte is visible in the source, and the
 * markers being planted are named where they are written.
 *
 * The planted values are the three SRS-PRO-005 names exactly - LOCATION,
 * DEVICE and TIMESTAMP - so a test asserting their absence is asserting the
 * requirement's own words rather than a proxy for them. They are written as
 * ASCII inside the EXIF payload, which is how a real EXIF string tag stores
 * them and which makes a byte search a fair test.
 *
 * The same fixtures are mirrored in Swift (DirtyImages.swift) so both platforms
 * are proven against identical planted metadata.
 */

package com.lumos.app.media;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Builds JPEG and PNG fixtures carrying known metadata. */
final class DirtyImages {

    /** The GPS string planted in EXIF; its absence is the SRS-PRO-005 location check. */
    static final String PLANTED_LOCATION = "GPS 51.5074N 0.1278W SECRET-LOCATION";

    /** The camera identity planted in EXIF; the device check. */
    static final String PLANTED_DEVICE = "Make=ACME Model=PHONE-9 Serial=SECRET-DEVICE";

    /** The capture time planted in EXIF; the timestamp check. */
    static final String PLANTED_TIMESTAMP = "2026:08:14 09:15:00 SECRET-TIMESTAMP";

    /** An XMP payload, which rides in APP1 alongside EXIF. */
    static final String PLANTED_XMP = "<x:xmpmeta>SECRET-XMP</x:xmpmeta>";

    /** A JPEG comment segment. */
    static final String PLANTED_COMMENT = "SECRET-COMMENT";

    /** Text planted in PNG ancillary chunks. */
    static final String PLANTED_PNG_TEXT = "SECRET-PNG-TEXT";

    private DirtyImages() {
        throw new AssertionError("no instances");
    }

    /**
     * Every string the sanitiser must remove.
     *
     * @return the planted secrets
     */
    static java.util.List<String> plantedSecrets() {
        return java.util.List.of(PLANTED_LOCATION, PLANTED_DEVICE, PLANTED_TIMESTAMP,
                PLANTED_XMP, PLANTED_COMMENT, PLANTED_PNG_TEXT);
    }

    /**
     * A structurally valid JPEG carrying EXIF, XMP and a comment.
     *
     * <p>Segment order mirrors a real camera file: SOI, APP0/JFIF, APP1/EXIF,
     * APP1/XMP, COM, SOF0, SOS, EOI.
     *
     * @param width the frame width written into SOF0
     * @param height the frame height written into SOF0
     * @return the dirty JPEG
     */
    static byte[] jpegWithEverything(final int width, final int height) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8);

        // APP0/JFIF: density only, and the one APPn the sanitiser keeps.
        writeSegment(out, 0xE0, concat("JFIF\0".getBytes(StandardCharsets.US_ASCII),
                new byte[] {1, 2, 0, 0, 1, 0, 1, 0, 0}));

        // APP1/EXIF, carrying the three SRS-PRO-005 categories.
        final byte[] exif = concat(
                "Exif\0\0".getBytes(StandardCharsets.US_ASCII),
                "MM\0*".getBytes(StandardCharsets.US_ASCII),
                PLANTED_LOCATION.getBytes(StandardCharsets.US_ASCII), new byte[] {0},
                PLANTED_DEVICE.getBytes(StandardCharsets.US_ASCII), new byte[] {0},
                PLANTED_TIMESTAMP.getBytes(StandardCharsets.US_ASCII), new byte[] {0});
        writeSegment(out, 0xE1, exif);

        // APP1/XMP: a second APP1, which a stripper that stops at the first
        // would miss - hence a fixture with two.
        writeSegment(out, 0xE1, concat(
                "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.US_ASCII),
                PLANTED_XMP.getBytes(StandardCharsets.US_ASCII)));

        // COM comment segment.
        writeSegment(out, 0xFE, PLANTED_COMMENT.getBytes(StandardCharsets.US_ASCII));

        // SOF0: the frame header the dimension reader parses.
        writeSegment(out, 0xC0, new byte[] {
            8,
            (byte) ((height >> 8) & 0xFF), (byte) (height & 0xFF),
            (byte) ((width >> 8) & 0xFF), (byte) (width & 0xFF),
            1, 1, 0x11, 0,
        });

        // SOS, then a token of entropy-coded data, then EOI.
        writeSegment(out, 0xDA, new byte[] {1, 1, 0, 0, 63, 0});
        out.write(0x12);
        out.write(0x34);
        out.write(0xFF);
        out.write(0xD9);
        return out.toByteArray();
    }

    /**
     * A structurally valid PNG carrying tEXt, iTXt and eXIf chunks.
     *
     * @param width the width written into IHDR
     * @param height the height written into IHDR
     * @return the dirty PNG
     */
    static byte[] pngWithEverything(final int width, final int height) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});

        final ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        writeInt(ihdr, width);
        writeInt(ihdr, height);
        ihdr.writeBytes(new byte[] {8, 6, 0, 0, 0});
        writeChunk(out, "IHDR", ihdr.toByteArray());

        writeChunk(out, "tEXt", concat("Comment\0".getBytes(StandardCharsets.US_ASCII),
                PLANTED_PNG_TEXT.getBytes(StandardCharsets.US_ASCII)));
        writeChunk(out, "iTXt", concat("XML:com.adobe.xmp\0\0\0\0\0".getBytes(StandardCharsets.US_ASCII),
                PLANTED_XMP.getBytes(StandardCharsets.US_ASCII)));
        writeChunk(out, "eXIf", concat(
                PLANTED_LOCATION.getBytes(StandardCharsets.US_ASCII), new byte[] {0},
                PLANTED_DEVICE.getBytes(StandardCharsets.US_ASCII)));
        writeChunk(out, "tIME", new byte[] {7, (byte) 0xEA, 8, 14, 9, 15, 0});
        writeChunk(out, "IDAT", new byte[] {0x78, (byte) 0x9C, 0x03, 0, 0, 0, 0, 1});
        writeChunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    /**
     * Writes a length-prefixed JPEG segment.
     *
     * @param out the destination
     * @param marker the marker byte
     * @param body the segment body
     */
    private static void writeSegment(final ByteArrayOutputStream out, final int marker,
            final byte[] body) {
        out.write(0xFF);
        out.write(marker);
        final int length = body.length + 2;
        out.write((length >> 8) & 0xFF);
        out.write(length & 0xFF);
        out.writeBytes(body);
    }

    /**
     * Writes a PNG chunk with a computed CRC.
     *
     * @param out the destination
     * @param name the four-character chunk name
     * @param body the chunk body
     */
    private static void writeChunk(final ByteArrayOutputStream out, final String name,
            final byte[] body) {
        writeInt(out, body.length);
        final byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(nameBytes);
        out.writeBytes(body);
        final java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(nameBytes);
        crc.update(body);
        writeInt(out, (int) crc.getValue());
    }

    /**
     * Writes a big-endian 32-bit integer.
     *
     * @param out the destination
     * @param value the value
     */
    private static void writeInt(final ByteArrayOutputStream out, final int value) {
        out.write((value >> 24) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    /**
     * Concatenates byte arrays.
     *
     * @param parts the arrays
     * @return their concatenation
     */
    private static byte[] concat(final byte[]... parts) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (final byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
