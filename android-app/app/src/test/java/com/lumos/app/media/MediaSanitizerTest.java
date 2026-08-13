/*
 * Lumos - android-app/app / The strip is proven against known-dirty images.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-PRO-005, SRS-PRO-006, SRS-NFR-PRI-008
 * ADRs: ADR-004
 * Threading: any
 * Notes: SRS-PRO-005's verification is "known-EXIF image test; receiver file
 * contains no prohibited metadata", and that is what these assert: the planted
 * strings named for the requirement's own three categories - location, device,
 * timestamp - must not appear ANYWHERE in the output bytes. Searching the whole
 * output rather than re-parsing the segments is the point: a stripper that
 * merely relabelled a segment would still pass a structural check, and a byte
 * search is what a recipient could actually do.
 */

package com.lumos.app.media;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The metadata strip and the SRS-PRO-006 validations. */
final class MediaSanitizerTest {

    @Test
    void theFixtureActuallyContainsTheMetadata() {
        // Without this, a stripper that did nothing would pass every test
        // below simply because the fixture never carried anything.
        final byte[] jpeg = DirtyImages.jpegWithEverything(640, 480);
        final byte[] png = DirtyImages.pngWithEverything(64, 48);
        for (final String secret : java.util.List.of(DirtyImages.PLANTED_LOCATION,
                DirtyImages.PLANTED_DEVICE, DirtyImages.PLANTED_TIMESTAMP,
                DirtyImages.PLANTED_COMMENT)) {
            assertTrue(contains(jpeg, secret), "fixture must plant " + secret);
        }
        assertTrue(contains(png, DirtyImages.PLANTED_PNG_TEXT));
        assertTrue(contains(png, DirtyImages.PLANTED_LOCATION));
    }

    @Test
    void jpegStripRemovesEverySecret() throws Exception {
        final MediaSanitizer.SanitizedImage clean =
                MediaSanitizer.sanitize(DirtyImages.jpegWithEverything(640, 480));
        for (final String secret : DirtyImages.plantedSecrets()) {
            assertFalse(contains(clean.bytes(), secret),
                    "prohibited metadata survived the strip: " + secret);
        }
    }

    @Test
    void pngStripRemovesEverySecret() throws Exception {
        final MediaSanitizer.SanitizedImage clean =
                MediaSanitizer.sanitize(DirtyImages.pngWithEverything(64, 48));
        for (final String secret : DirtyImages.plantedSecrets()) {
            assertFalse(contains(clean.bytes(), secret),
                    "prohibited metadata survived the strip: " + secret);
        }
    }

    @Test
    void theStrippedImageIsStillTheSameImage() throws Exception {
        final MediaSanitizer.SanitizedImage clean =
                MediaSanitizer.sanitize(DirtyImages.jpegWithEverything(640, 480));
        assertEquals(MediaSanitizer.ImageType.JPEG, clean.type());
        assertEquals(640, clean.width(), "stripping must not disturb the frame header");
        assertEquals(480, clean.height());
        // The entropy-coded payload after SOS is copied verbatim.
        assertTrue(contains(clean.bytes(), new byte[] {0x12, 0x34}),
                "the image data itself must survive");
    }

    @Test
    void strippingIsIdempotent() throws Exception {
        final byte[] once = MediaSanitizer.sanitize(DirtyImages.jpegWithEverything(64, 48)).bytes();
        final byte[] twice = MediaSanitizer.sanitize(once).bytes();
        assertArrayEquals(once, twice, "a second pass must find nothing left to remove");
    }

    @Test
    void jfifDensityIsKeptBecauseItCarriesNothingIdentifying() throws Exception {
        final byte[] clean = MediaSanitizer.sanitize(DirtyImages.jpegWithEverything(64, 48)).bytes();
        assertTrue(contains(clean, "JFIF"),
                "APP0 carries density only; dropping it would change rendering for no privacy gain");
    }

    @Test
    void pngKeepsItsCriticalChunks() throws Exception {
        final byte[] clean = MediaSanitizer.sanitize(DirtyImages.pngWithEverything(64, 48)).bytes();
        for (final String chunk : java.util.List.of("IHDR", "IDAT", "IEND")) {
            assertTrue(contains(clean, chunk), chunk + " is critical and must survive");
        }
        for (final String chunk : java.util.List.of("tEXt", "iTXt", "eXIf", "tIME")) {
            assertFalse(contains(clean, chunk), chunk + " carries metadata and must not survive");
        }
    }

    // ---- SRS-PRO-006 validation ----

    @Test
    void typeComesFromMagicBytesNotFromAClaim() {
        final MediaSanitizer.RejectedException rejected = assertThrows(
                MediaSanitizer.RejectedException.class,
                () -> MediaSanitizer.sanitize("GIF89a this is not a jpeg".getBytes(StandardCharsets.UTF_8)));
        assertEquals(MediaSanitizer.Rejection.UNSUPPORTED_TYPE, rejected.rejection());
    }

    @Test
    void anOversizedImageIsRejectedBeforeAnythingElse() {
        final byte[] huge = new byte[MediaSanitizer.MAX_IMAGE_BYTES + 1];
        final MediaSanitizer.RejectedException rejected = assertThrows(
                MediaSanitizer.RejectedException.class, () -> MediaSanitizer.sanitize(huge));
        assertEquals(MediaSanitizer.Rejection.TOO_LARGE, rejected.rejection());
    }

    @Test
    void aDecompressionBombIsRejectedOnDimensions() {
        final byte[] bomb = DirtyImages.pngWithEverything(
                MediaSanitizer.MAX_DIMENSION_PIXELS + 1, 64);
        final MediaSanitizer.RejectedException rejected = assertThrows(
                MediaSanitizer.RejectedException.class, () -> MediaSanitizer.sanitize(bomb));
        assertEquals(MediaSanitizer.Rejection.BAD_DIMENSIONS, rejected.rejection(),
                "a small file declaring enormous dimensions is the decompression-bomb shape");
    }

    @Test
    void zeroDimensionsAreRejected() {
        assertThrows(MediaSanitizer.RejectedException.class,
                () -> MediaSanitizer.sanitize(DirtyImages.pngWithEverything(0, 0)));
    }

    @Test
    void aTruncatedSegmentIsRejectedRatherThanReadPastTheEnd() {
        final byte[] whole = DirtyImages.jpegWithEverything(64, 48);
        final byte[] truncated = java.util.Arrays.copyOfRange(whole, 0, whole.length / 2);
        final MediaSanitizer.RejectedException rejected = assertThrows(
                MediaSanitizer.RejectedException.class, () -> MediaSanitizer.sanitize(truncated));
        assertEquals(MediaSanitizer.Rejection.MALFORMED, rejected.rejection());
    }

    @Test
    void aSegmentClaimingAnAbsurdLengthIsRejected() {
        final byte[] jpeg = DirtyImages.jpegWithEverything(64, 48);
        // Overwrite the APP0 length with one that runs past the buffer.
        jpeg[4] = (byte) 0x7F;
        jpeg[5] = (byte) 0xFF;
        assertThrows(MediaSanitizer.RejectedException.class, () -> MediaSanitizer.sanitize(jpeg));
    }

    @Test
    void theSizeBoundComesFromTheRegister() {
        assertEquals(com.lumos.protocol.LumosConstants.FULL_IMAGE_MAX_MB_DEFAULT * 1024 * 1024,
                MediaSanitizer.MAX_IMAGE_BYTES,
                "ADR-004: the bound is the register's, not a literal in the sanitiser");
    }

    /**
     * Reports whether text occurs in the bytes.
     *
     * @param haystack the bytes
     * @param text the ASCII text
     * @return true when found
     */
    private static boolean contains(final byte[] haystack, final String text) {
        return contains(haystack, text.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Reports whether needle occurs in haystack.
     *
     * @param haystack the searched bytes
     * @param needle the pattern
     * @return true when found
     */
    private static boolean contains(final byte[] haystack, final byte[] needle) {
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            boolean hit = true;
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    hit = false;
                    break;
                }
            }
            if (hit) {
                return true;
            }
        }
        return false;
    }
}
