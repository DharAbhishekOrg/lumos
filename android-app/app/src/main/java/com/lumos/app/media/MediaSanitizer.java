/*
 * Lumos - android-app/app / Metadata stripping and validation for profile images.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-PRO-005, SRS-PRO-006, SRS-NFR-PRI-008, TRS-MED-002
 * ADRs: ADR-004
 * Threading: any
 * Notes: A byte-level segment rewriter, not an image decoder. That is the
 * central design decision and it is worth the paragraph.
 *
 * SRS-PRO-005 requires EXIF location, device and timestamp metadata to be gone
 * before an image is shared. Metadata in JPEG lives in APPn marker segments and
 * in PNG lives in ancillary text chunks; both can be removed by rewriting the
 * container without ever decoding a pixel. Doing it that way means the whole
 * strip is PURE and runs in CI on any host - so the requirement's own
 * verification, a known-dirty-image test, executes on every build rather than
 * only where a platform codec exists.
 *
 * Re-encoding (decode to pixels, encode afresh) is the second half of the
 * WP-02 brief's pipeline and needs a platform codec, so it lives behind
 * ImageReencoder. It adds one thing this cannot: it destroys anything hidden in
 * the entropy-coded data itself. The split is deliberate and the limitation is
 * stated rather than implied - see ImageReencoder.
 *
 * Validation (SRS-PRO-006) reads type from MAGIC BYTES, never from a filename
 * or a claimed MIME type, and reads dimensions from the container's own header.
 * Size limits come from the Sec. 23 register via generated constants (ADR-004);
 * there is no literal byte count anywhere in this file.
 */

package com.lumos.app.media;

import com.lumos.protocol.LumosConstants;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Strips metadata from and validates profile images. */
public final class MediaSanitizer {

    /** The image types accepted for profile media. */
    public enum ImageType {
        /** JFIF/EXIF JPEG. */
        JPEG("image/jpeg"),
        /** PNG. */
        PNG("image/png");

        private final String mimeType;

        ImageType(final String mimeType) {
            this.mimeType = mimeType;
        }

        /**
         * The MIME type.
         *
         * @return the type string
         */
        public String mimeType() {
            return mimeType;
        }
    }

    /** Why an image was rejected (SRS-PRO-006). */
    public enum Rejection {
        /** The bytes are not a type this product accepts. */
        UNSUPPORTED_TYPE,
        /** The container is truncated or structurally invalid. */
        MALFORMED,
        /** Larger than the register's full-image bound. */
        TOO_LARGE,
        /** Dimensions absent, zero, or implausibly large. */
        BAD_DIMENSIONS
    }

    /** An image failed validation. */
    public static final class RejectedException extends Exception {
        private static final long serialVersionUID = 1L;

        private final Rejection rejection;

        /**
         * Records a rejection.
         *
         * @param rejection why
         * @param detail a message safe to log; never image content
         */
        public RejectedException(final Rejection rejection, final String detail) {
            super(detail);
            this.rejection = rejection;
        }

        /**
         * Why the image was rejected.
         *
         * @return the reason
         */
        public Rejection rejection() {
            return rejection;
        }
    }

    /** A validated, stripped image. */
    public static final class SanitizedImage {
        private final byte[] bytes;
        private final ImageType type;
        private final int width;
        private final int height;

        SanitizedImage(final byte[] bytes, final ImageType type, final int width, final int height) {
            this.bytes = bytes;
            this.type = type;
            this.width = width;
            this.height = height;
        }

        /**
         * The stripped bytes.
         *
         * @return a copy
         */
        public byte[] bytes() {
            return bytes.clone();
        }

        /**
         * The type determined from magic bytes.
         *
         * @return the type
         */
        public ImageType type() {
            return type;
        }

        /**
         * Decoded width.
         *
         * @return pixels
         */
        public int width() {
            return width;
        }

        /**
         * Decoded height.
         *
         * @return pixels
         */
        public int height() {
            return height;
        }
    }

    /**
     * The largest profile image accepted, from the Sec. 23 register.
     *
     * <p>FULL_IMAGE_MAX_MB is the register's own bound for a full image; using
     * it here rather than a literal is ADR-004, and it means a signed-config
     * change to that bound moves this limit with it.
     */
    public static final int MAX_IMAGE_BYTES = LumosConstants.FULL_IMAGE_MAX_MB_DEFAULT * 1024 * 1024;

    /**
     * The largest dimension accepted.
     *
     * <p>A named local constant with rationale, which TRS-CODE-005 permits: the
     * register bounds image BYTES but not pixels, and a decompression bomb is a
     * small file with enormous dimensions. 8192 is comfortably above any real
     * phone camera's output and far below a size that would exhaust memory on
     * decode.
     */
    public static final int MAX_DIMENSION_PIXELS = 8192;

    private MediaSanitizer() {
        throw new AssertionError("no instances");
    }

    /**
     * Validates an image and strips every metadata container from it.
     *
     * @param raw the bytes as supplied
     * @return the validated, stripped image
     * @throws RejectedException when the image fails any SRS-PRO-006 check
     */
    public static SanitizedImage sanitize(final byte[] raw) throws RejectedException {
        if (raw.length > MAX_IMAGE_BYTES) {
            throw new RejectedException(Rejection.TOO_LARGE,
                    raw.length + " bytes exceeds the register bound " + MAX_IMAGE_BYTES);
        }
        final ImageType type = detectType(raw);
        final byte[] stripped = type == ImageType.JPEG ? stripJpeg(raw) : stripPng(raw);
        final int[] size = type == ImageType.JPEG ? jpegDimensions(stripped) : pngDimensions(stripped);
        if (size[0] <= 0 || size[1] <= 0
                || size[0] > MAX_DIMENSION_PIXELS || size[1] > MAX_DIMENSION_PIXELS) {
            throw new RejectedException(Rejection.BAD_DIMENSIONS,
                    "dimensions " + size[0] + "x" + size[1] + " are outside the accepted range");
        }
        return new SanitizedImage(stripped, type, size[0], size[1]);
    }

    /**
     * Determines the type from magic bytes.
     *
     * <p>Never from a filename or a caller-supplied MIME type: those are claims
     * by whoever produced the file, and SRS-PRO-006 asks the application to
     * validate the type rather than believe it.
     *
     * @param raw the bytes
     * @return the type
     * @throws RejectedException when the bytes are neither JPEG nor PNG
     */
    public static ImageType detectType(final byte[] raw) throws RejectedException {
        if (raw.length >= 3 && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xD8
                && (raw[2] & 0xFF) == 0xFF) {
            return ImageType.JPEG;
        }
        final byte[] pngMagic = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        if (raw.length >= pngMagic.length
                && Arrays.equals(Arrays.copyOfRange(raw, 0, pngMagic.length), pngMagic)) {
            return ImageType.PNG;
        }
        throw new RejectedException(Rejection.UNSUPPORTED_TYPE,
                "not a JPEG or PNG by magic bytes");
    }

    /**
     * Removes every metadata-bearing JPEG segment.
     *
     * <p>Dropped: APP1 (EXIF and XMP), APP2 through APP15 (Flashpix, IPTC,
     * Ducky, Photoshop resources), and COM comments. Kept: APP0 (JFIF, which
     * carries only density) and everything from SOF onward, because that is the
     * image. ICC profiles ride in APP2 and go with it - a colour-profile loss
     * is a visible cost, and it is the right trade against a segment that has
     * carried camera serial numbers in the wild.
     *
     * @param raw the JPEG bytes
     * @return the stripped JPEG
     * @throws RejectedException when the segment structure is invalid
     */
    public static byte[] stripJpeg(final byte[] raw) throws RejectedException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length);
        out.write(0xFF);
        out.write(0xD8);

        int at = 2;
        while (at + 3 < raw.length) {
            if ((raw[at] & 0xFF) != 0xFF) {
                throw new RejectedException(Rejection.MALFORMED, "expected a marker");
            }
            final int marker = raw[at + 1] & 0xFF;
            if (marker == 0xD9) {
                break; // EOI
            }
            if (marker == 0xDA) {
                // Start of scan: the rest is entropy-coded image data, copied
                // verbatim. Nothing after this point is a metadata segment.
                out.write(raw, at, raw.length - at);
                return out.toByteArray();
            }
            final int length = ((raw[at + 2] & 0xFF) << 8) | (raw[at + 3] & 0xFF);
            if (length < 2 || at + 2 + length > raw.length) {
                throw new RejectedException(Rejection.MALFORMED, "segment length out of range");
            }
            if (!isMetadataMarker(marker)) {
                out.write(raw, at, 2 + length);
            }
            at += 2 + length;
        }
        out.write(0xFF);
        out.write(0xD9);
        return out.toByteArray();
    }

    /**
     * Whether a JPEG marker introduces a metadata segment.
     *
     * @param marker the marker byte
     * @return true for APP1..APP15 and COM
     */
    private static boolean isMetadataMarker(final int marker) {
        final boolean applicationSegment = marker >= 0xE1 && marker <= 0xEF;
        final boolean comment = marker == 0xFE;
        return applicationSegment || comment;
    }

    /**
     * Removes every ancillary PNG chunk that can carry metadata.
     *
     * <p>PNG chunk names are case-sensitive: a lower-case first letter marks an
     * ancillary chunk, which a decoder may ignore. Everything ancillary except
     * transparency and gamma is dropped, which covers tEXt, zTXt, iTXt (where
     * XMP lives), eXIf, and timestamps.
     *
     * @param raw the PNG bytes
     * @return the stripped PNG
     * @throws RejectedException when the chunk structure is invalid
     */
    public static byte[] stripPng(final byte[] raw) throws RejectedException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length);
        out.write(raw, 0, 8);

        int at = 8;
        while (at + 8 <= raw.length) {
            final int length = readInt(raw, at);
            if (length < 0 || at + 12 + length > raw.length) {
                throw new RejectedException(Rejection.MALFORMED, "chunk length out of range");
            }
            final String name = new String(raw, at + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (isCriticalOrRenderingChunk(name)) {
                out.write(raw, at, 12 + length);
            }
            at += 12 + length;
            if ("IEND".equals(name)) {
                break;
            }
        }
        return out.toByteArray();
    }

    /**
     * Whether a PNG chunk must be preserved.
     *
     * @param name the four-character chunk name
     * @return true for critical chunks and the two rendering-relevant ancillaries
     */
    private static boolean isCriticalOrRenderingChunk(final String name) {
        if (name.isEmpty()) {
            return false;
        }
        final boolean critical = Character.isUpperCase(name.charAt(0));
        return critical || "tRNS".equals(name) || "gAMA".equals(name);
    }

    /**
     * Reads a JPEG's dimensions from its first SOF segment.
     *
     * @param jpeg the JPEG bytes
     * @return width and height
     * @throws RejectedException when no frame header is present
     */
    public static int[] jpegDimensions(final byte[] jpeg) throws RejectedException {
        int at = 2;
        while (at + 9 < jpeg.length) {
            if ((jpeg[at] & 0xFF) != 0xFF) {
                throw new RejectedException(Rejection.MALFORMED, "expected a marker");
            }
            final int marker = jpeg[at + 1] & 0xFF;
            final int length = ((jpeg[at + 2] & 0xFF) << 8) | (jpeg[at + 3] & 0xFF);
            // SOF0..SOF15 carry the frame header; DHT, DAC and RST do not.
            final boolean startOfFrame = marker >= 0xC0 && marker <= 0xCF
                    && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (startOfFrame) {
                final int height = ((jpeg[at + 5] & 0xFF) << 8) | (jpeg[at + 6] & 0xFF);
                final int width = ((jpeg[at + 7] & 0xFF) << 8) | (jpeg[at + 8] & 0xFF);
                return new int[] {width, height};
            }
            if (marker == 0xDA || length < 2) {
                break;
            }
            at += 2 + length;
        }
        throw new RejectedException(Rejection.MALFORMED, "no frame header");
    }

    /**
     * Reads a PNG's dimensions from its IHDR chunk.
     *
     * @param png the PNG bytes
     * @return width and height
     * @throws RejectedException when IHDR is absent
     */
    public static int[] pngDimensions(final byte[] png) throws RejectedException {
        if (png.length < 24
                || !"IHDR".equals(new String(png, 12, 4, java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new RejectedException(Rejection.MALFORMED, "no IHDR chunk");
        }
        return new int[] {readInt(png, 16), readInt(png, 20)};
    }

    /**
     * Reads a big-endian 32-bit integer.
     *
     * @param b the bytes
     * @param at the offset
     * @return the value
     */
    private static int readInt(final byte[] b, final int at) {
        return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16)
                | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }
}
