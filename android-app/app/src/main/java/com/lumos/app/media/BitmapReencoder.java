/*
 * Lumos - android-app/app / Draws a picked picture again as a fresh, bounded JPEG.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-PRO-005, SRS-PRO-006, TRS-MED-002
 * ADRs: ADR-003
 * Threading: any background thread (decoding is slow and allocates)
 * Notes: Re-drawing is the strongest metadata strip there is: the output is
 * pixels the app encoded itself, so no camera block, location, device name,
 * timestamp or embedded thumbnail can ride along (MediaSanitizer strips again
 * afterwards anyway). Decode safety comes first (SRS-PRO-006): the picker's
 * bytes are capped before anything is read into memory, the image's size is
 * read from its header alone, a header claiming more than MAX_SOURCE_PIXELS
 * is refused, and the full decode is subsampled so the bitmap in memory is
 * never much larger than the output. Orientation is read once (ExifOrientation), applied to
 * the pixels, and then forgotten, so photos stand the right way up without
 * keeping any EXIF. Output: the long edge at most LONG_EDGE_PIXELS, JPEG at
 * quality 85, stepping down until it fits MediaSanitizer.MAX_IMAGE_BYTES.
 */

package com.lumos.app.media;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import com.lumos.app.profile.ProfilePhotos;
import java.io.ByteArrayOutputStream;

/** The Android ProfilePhotos.Reencoder. */
public final class BitmapReencoder implements ProfilePhotos.Reencoder {

    /**
     * The longest edge a profile photo keeps. A named local constant
     * (TRS-CODE-005): sharp on any phone screen, and it keeps a photo well
     * inside FULL_IMAGE_MAX_MB at quality 85.
     */
    public static final int LONG_EDGE_PIXELS = 1600;

    /**
     * The most pixels a source image may claim in its header (200 MP, the
     * largest phone sensors). Beyond it the header is a lie or a bomb.
     */
    public static final long MAX_SOURCE_PIXELS = 200_000_000L;

    /** The most bytes read from the picker; a larger file is refused unread. */
    public static final int MAX_SOURCE_BYTES = 64 * 1024 * 1024;

    /** A half turn, in degrees. */
    private static final float HALF_TURN = 180f;
    /** A quarter turn clockwise, in degrees. */
    private static final float QUARTER_TURN = 90f;
    /** Three quarter turns clockwise, in degrees. */
    private static final float THREE_QUARTER_TURN = 270f;

    /** JPEG qualities tried in turn until the output fits. */
    private static final int[] QUALITIES = {85, 75, 65, 55};

    @Override
    public byte[] reencode(final byte[] picked) throws MediaSanitizer.RejectedException {
        if (picked.length > MAX_SOURCE_BYTES) {
            throw new MediaSanitizer.RejectedException(MediaSanitizer.Rejection.TOO_LARGE,
                    "picked file over the source limit");
        }
        final BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(picked, 0, picked.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                || (long) bounds.outWidth * bounds.outHeight > MAX_SOURCE_PIXELS) {
            throw new MediaSanitizer.RejectedException(MediaSanitizer.Rejection.BAD_DIMENSIONS,
                    "not a decodable picture of a sane size");
        }
        final BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = sampleSize(Math.max(bounds.outWidth, bounds.outHeight));
        decode.inPreferredConfig = Bitmap.Config.ARGB_8888;
        final Bitmap decoded = BitmapFactory.decodeByteArray(picked, 0, picked.length, decode);
        if (decoded == null) {
            throw new MediaSanitizer.RejectedException(MediaSanitizer.Rejection.BAD_DIMENSIONS,
                    "the picture did not decode");
        }
        final Bitmap upright = scaledAndUpright(decoded, ExifOrientation.of(picked));
        try {
            return compress(upright);
        } finally {
            upright.recycle();
        }
    }

    /**
     * The largest power-of-two subsample that still leaves the long edge at
     * least LONG_EDGE_PIXELS, so the final scale is always a reduction.
     *
     * @param longEdge the source's long edge
     * @return the inSampleSize
     */
    static int sampleSize(final int longEdge) {
        int sample = 1;
        while (longEdge / (sample * 2) >= LONG_EDGE_PIXELS) {
            sample *= 2;
        }
        return sample;
    }

    private static Bitmap scaledAndUpright(final Bitmap source, final int orientation) {
        final Matrix matrix = new Matrix();
        final int longEdge = Math.max(source.getWidth(), source.getHeight());
        if (longEdge > LONG_EDGE_PIXELS) {
            final float scale = (float) LONG_EDGE_PIXELS / longEdge;
            matrix.postScale(scale, scale);
        }
        applyOrientation(matrix, orientation);
        final Bitmap out = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
        if (out != source) {
            source.recycle();
        }
        return out;
    }

    /**
     * The EXIF orientation as a transform, for all eight values.
     *
     * @param matrix the matrix to extend
     * @param orientation the EXIF orientation value
     */
    static void applyOrientation(final Matrix matrix, final int orientation) {
        switch (orientation) {
            case ExifOrientation.FLIP_HORIZONTAL:
                matrix.postScale(-1, 1);
                break;
            case ExifOrientation.ROTATE_180:
                matrix.postRotate(HALF_TURN);
                break;
            case ExifOrientation.FLIP_VERTICAL:
                matrix.postScale(1, -1);
                break;
            case ExifOrientation.TRANSPOSE:
                matrix.postRotate(QUARTER_TURN);
                matrix.postScale(-1, 1);
                break;
            case ExifOrientation.ROTATE_90:
                matrix.postRotate(QUARTER_TURN);
                break;
            case ExifOrientation.TRANSVERSE:
                matrix.postRotate(THREE_QUARTER_TURN);
                matrix.postScale(-1, 1);
                break;
            case ExifOrientation.ROTATE_270:
                matrix.postRotate(THREE_QUARTER_TURN);
                break;
            default:
                break;
        }
    }

    private static byte[] compress(final Bitmap bitmap) throws MediaSanitizer.RejectedException {
        for (final int quality : QUALITIES) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                break;
            }
            if (out.size() <= MediaSanitizer.MAX_IMAGE_BYTES) {
                return out.toByteArray();
            }
        }
        throw new MediaSanitizer.RejectedException(MediaSanitizer.Rejection.TOO_LARGE,
                "could not encode within the register bound");
    }
}
