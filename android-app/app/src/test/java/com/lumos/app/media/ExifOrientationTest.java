/*
 * Lumos - android-app/app / The orientation reader: right on real layouts, NORMAL on anything else, never out of bounds.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-PRO-005, SRS-PRO-006, TRS-TST-001
 * ADRs: ADR-003
 * Threading: test-only
 * Notes: Hand-built JPEG heads in both TIFF byte orders, then the ways a file
 * can lie (truncated lengths, offsets past the block, wrong types, values out
 * of range), then random bytes behind a JPEG start marker: none may throw.
 */

package com.lumos.app.media;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** ExifOrientation over built and hostile bytes. */
final class ExifOrientationTest {

    /** SOI, one APP1 Exif block whose IFD0 holds one entry, then SOS. */
    private static byte[] jpeg(final boolean bigEndian, final int tag, final int type, final long count,
            final int value) {
        final ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        if (bigEndian) {
            tiff.write('M');
            tiff.write('M');
        } else {
            tiff.write('I');
            tiff.write('I');
        }
        u16(tiff, 42, bigEndian);
        u32(tiff, 8, bigEndian);
        u16(tiff, 1, bigEndian);
        u16(tiff, tag, bigEndian);
        u16(tiff, type, bigEndian);
        u32(tiff, count, bigEndian);
        u16(tiff, value, bigEndian);
        u16(tiff, 0, bigEndian);
        u32(tiff, 0, bigEndian);
        final byte[] body = tiff.toByteArray();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8);
        out.write(0xFF);
        out.write(0xE1);
        u16(out, 2 + 6 + body.length, true);
        out.writeBytes(new byte[] {'E', 'x', 'i', 'f', 0, 0});
        out.writeBytes(body);
        out.write(0xFF);
        out.write(0xDA);
        return out.toByteArray();
    }

    private static void u16(final ByteArrayOutputStream out, final int v, final boolean bigEndian) {
        if (bigEndian) {
            out.write((v >> 8) & 0xFF);
            out.write(v & 0xFF);
        } else {
            out.write(v & 0xFF);
            out.write((v >> 8) & 0xFF);
        }
    }

    private static void u32(final ByteArrayOutputStream out, final long v, final boolean bigEndian) {
        for (int i = 0; i < 4; i++) {
            final int shift = bigEndian ? 24 - 8 * i : 8 * i;
            out.write((int) (v >> shift) & 0xFF);
        }
    }

    @Test
    void bothByteOrdersAndEveryValueReadBack() {
        for (int value = 1; value <= 8; value++) {
            assertEquals(value, ExifOrientation.of(jpeg(true, 0x0112, 3, 1, value)), "MM " + value);
            assertEquals(value, ExifOrientation.of(jpeg(false, 0x0112, 3, 1, value)), "II " + value);
        }
    }

    @Test
    void anythingElseIsUpright() {
        assertEquals(ExifOrientation.NORMAL, ExifOrientation.of(new byte[0]));
        assertEquals(ExifOrientation.NORMAL, ExifOrientation.of(new byte[] {(byte) 0x89, 'P', 'N', 'G'}));
        assertEquals(ExifOrientation.NORMAL, ExifOrientation.of(jpeg(true, 0x0112, 3, 1, 9)), "out of range");
        assertEquals(ExifOrientation.NORMAL, ExifOrientation.of(jpeg(true, 0x0112, 3, 1, 0)), "zero");
        assertEquals(ExifOrientation.NORMAL, ExifOrientation.of(jpeg(true, 0x0112, 4, 1, 6)), "not a SHORT");
        assertEquals(ExifOrientation.NORMAL, ExifOrientation.of(jpeg(true, 0x0112, 3, 2, 6)), "two values");
        assertEquals(ExifOrientation.NORMAL, ExifOrientation.of(jpeg(true, 0x010F, 3, 1, 6)), "another tag");
        assertEquals(ExifOrientation.NORMAL, ExifOrientation.of(DirtyImages.pngWithEverything(4, 4)));
    }

    @Test
    void lyingLengthsAndOffsetsNeverReadOutOfBounds() {
        final byte[] good = jpeg(true, 0x0112, 3, 1, 6);
        for (int cut = 0; cut < good.length; cut++) {
            final byte[] truncated = Arrays.copyOf(good, cut);
            final int read = ExifOrientation.of(truncated);
            assertEquals(cut >= good.length - 2 ? 6 : ExifOrientation.NORMAL, read, "cut at " + cut);
        }
        final byte[] farOffset = good.clone();
        farOffset[4 + 2 + 6 + 4] = 0x7F; // IFD0 offset's high byte: far past the block
        assertEquals(ExifOrientation.NORMAL, ExifOrientation.of(farOffset));
        final byte[] longBlock = good.clone();
        longBlock[4] = 0x7F; // APP1 length claims more than the file holds
        assertEquals(ExifOrientation.NORMAL, ExifOrientation.of(longBlock));
    }

    @Test
    void randomBytesNeverThrow() {
        final Random random = new Random(20261128L);
        final byte[] head = jpeg(random.nextBoolean(), 0x0112, 3, 1, 6);
        for (int i = 0; i < 5000; i++) {
            final byte[] noise = head.clone();
            for (int j = 0; j < 1 + random.nextInt(6); j++) {
                noise[2 + random.nextInt(noise.length - 2)] = (byte) random.nextInt(256);
            }
            final int read = ExifOrientation.of(noise);
            assertEquals(true, read >= 1 && read <= 8, "always a valid orientation");
        }
    }
}
