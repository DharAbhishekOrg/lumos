/*
 * Lumos - android-app/app / Reads one value from a JPEG's EXIF block: which way up it is.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-PRO-005, SRS-PRO-006
 * ADRs: ADR-003
 * Threading: any
 * Notes: The re-encoder needs exactly one tag, Orientation (0x0112), to draw
 * a photo the right way up before every metadata block is dropped. Reading
 * it here, bounds-checked at every step, avoids both the platform
 * ExifInterface (lint: known security bugs on older releases) and a new
 * dependency for one number. Anything unexpected - no APP1 Exif block, a
 * truncated or lying length, an offset outside the block, a value out of
 * range - reads as NORMAL: the worst case is a sideways photo, never a
 * crash or an out-of-bounds read. Only IFD0 is read; nothing is kept.
 */

package com.lumos.app.media;

/** The EXIF orientation of a JPEG, read defensively. */
public final class ExifOrientation {

    /** Upright as stored (EXIF value 1); also the answer for anything unreadable. */
    public static final int NORMAL = 1;

    /** Mirrored left to right. */
    public static final int FLIP_HORIZONTAL = 2;
    /** Upside down. */
    public static final int ROTATE_180 = 3;
    /** Mirrored top to bottom. */
    public static final int FLIP_VERTICAL = 4;
    /** Mirrored along the top-left diagonal. */
    public static final int TRANSPOSE = 5;
    /** Needs a quarter turn clockwise to stand up. */
    public static final int ROTATE_90 = 6;
    /** Mirrored along the top-right diagonal. */
    public static final int TRANSVERSE = 7;
    /** Needs a quarter turn counter-clockwise to stand up; the largest value. */
    public static final int ROTATE_270 = 8;

    private static final int ORIENTATION_TAG = 0x0112;
    private static final int TYPE_SHORT = 3;
    private static final int IFD_ENTRY_BYTES = 12;
    private static final int TIFF_HEADER_BYTES = 8;
    private static final int TIFF_MAGIC = 42;
    private static final int MARKER = 0xFF;
    private static final int SOI = 0xD8;
    private static final int APP1 = 0xE1;
    private static final int SOS = 0xDA;
    private static final int EOI = 0xD9;
    private static final byte[] EXIF_ID = {'E', 'x', 'i', 'f', 0, 0};

    private ExifOrientation() {
        throw new AssertionError("no instances");
    }

    /**
     * @param jpeg the bytes as picked
     * @return the orientation, 1 to 8; NORMAL when absent or unreadable
     */
    public static int of(final byte[] jpeg) {
        if (jpeg.length < 4 || (jpeg[0] & 0xFF) != MARKER || (jpeg[1] & 0xFF) != SOI) {
            return NORMAL;
        }
        int at = 2;
        while (at + 4 <= jpeg.length && (jpeg[at] & 0xFF) == MARKER) {
            final int marker = jpeg[at + 1] & 0xFF;
            if (marker == SOS || marker == EOI) {
                return NORMAL;
            }
            final int length = u16(jpeg, at + 2, true);
            if (length < 2 || at + 2 + length > jpeg.length) {
                return NORMAL;
            }
            if (marker == APP1 && startsWith(jpeg, at + 4, at + 2 + length, EXIF_ID)) {
                return fromTiff(jpeg, at + 4 + EXIF_ID.length, at + 2 + length);
            }
            at += 2 + length;
        }
        return NORMAL;
    }

    private static int fromTiff(final byte[] b, final int start, final int end) {
        if (start + TIFF_HEADER_BYTES > end || !(isOrder(b, start, 'M') || isOrder(b, start, 'I'))) {
            return NORMAL;
        }
        final boolean bigEndian = b[start] == 'M';
        if (u16(b, start + 2, bigEndian) != TIFF_MAGIC) {
            return NORMAL;
        }
        final long ifd = u32(b, start + 4, bigEndian);
        if (ifd < TIFF_HEADER_BYTES || start + ifd + 2 > end) {
            return NORMAL;
        }
        final int entriesAt = start + (int) ifd;
        final int count = u16(b, entriesAt, bigEndian);
        for (int i = 0; i < count; i++) {
            final int entry = entriesAt + 2 + i * IFD_ENTRY_BYTES;
            if (entry + IFD_ENTRY_BYTES > end) {
                return NORMAL;
            }
            if (u16(b, entry, bigEndian) == ORIENTATION_TAG) {
                return valueOf(b, entry, bigEndian);
            }
        }
        return NORMAL;
    }

    private static boolean isOrder(final byte[] b, final int at, final char letter) {
        return b[at] == letter && b[at + 1] == letter;
    }

    /** One SHORT holding 1 to 8, or NORMAL. */
    private static int valueOf(final byte[] b, final int entry, final boolean bigEndian) {
        if (u16(b, entry + 2, bigEndian) != TYPE_SHORT || u32(b, entry + 4, bigEndian) != 1) {
            return NORMAL;
        }
        final int value = u16(b, entry + 8, bigEndian);
        return value >= NORMAL && value <= ROTATE_270 ? value : NORMAL;
    }

    private static boolean startsWith(final byte[] b, final int at, final int end, final byte[] prefix) {
        if (at + prefix.length > end) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (b[at + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static int u16(final byte[] b, final int at, final boolean bigEndian) {
        final int hi = b[bigEndian ? at : at + 1] & 0xFF;
        final int lo = b[bigEndian ? at + 1 : at] & 0xFF;
        return (hi << 8) | lo;
    }

    private static long u32(final byte[] b, final int at, final boolean bigEndian) {
        long value = 0;
        for (int i = 0; i < 4; i++) {
            value = (value << 8) | (b[bigEndian ? at + i : at + 3 - i] & 0xFF);
        }
        return value;
    }
}
