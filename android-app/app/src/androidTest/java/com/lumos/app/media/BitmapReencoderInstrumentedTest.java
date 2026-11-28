/*
 * Lumos - android-app/app / The re-encoder on a real Android: upright, bounded, and carrying no camera metadata.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-PRO-005, SRS-PRO-006, TRS-MED-002
 * ADRs: ADR-003
 * Threading: test-only
 * Notes: The source is a real camera-sized JPEG with real EXIF written by the
 * platform's own ExifInterface: a sideways orientation, a GPS position, a
 * device make and model and a timestamp. The output must stand upright, keep
 * its long edge at LONG_EDGE_PIXELS, carry none of those tags, and pass the
 * sanitiser. Bytes that are no picture are refused.
 */

package com.lumos.app.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.ExifInterface;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * BitmapReencoder on the device's own codecs. The platform ExifInterface is
 * used here only to plant and look for tags in test pictures; product code
 * reads orientation with ExifOrientation instead.
 */
@SuppressLint("ExifInterface")
@RunWith(AndroidJUnit4.class)
public final class BitmapReencoderInstrumentedTest {

    private static byte[] sidewaysCameraJpeg() throws Exception {
        final Bitmap bitmap = Bitmap.createBitmap(3200, 2400, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.rgb(200, 120, 90));
        final File file = File.createTempFile("camera", ".jpg",
                InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
        try (FileOutputStream out = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out));
        }
        bitmap.recycle();
        final ExifInterface exif = new ExifInterface(file.getAbsolutePath());
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_ROTATE_90));
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "51/1,30/1,0/1");
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N");
        exif.setAttribute(ExifInterface.TAG_MAKE, "SECRET-MAKE");
        exif.setAttribute(ExifInterface.TAG_MODEL, "SECRET-MODEL");
        exif.setAttribute(ExifInterface.TAG_DATETIME, "2026:08:14 09:15:00");
        exif.saveAttributes();
        final byte[] bytes = Files.readAllBytes(file.toPath());
        assertTrue(file.delete());
        return bytes;
    }

    @Test
    public void aSidewaysCameraPhotoComesOutUprightBoundedAndClean() throws Exception {
        final byte[] source = sidewaysCameraJpeg();
        final ExifInterface before = new ExifInterface(new ByteArrayInputStream(source));
        assertEquals("SECRET-MAKE", before.getAttribute(ExifInterface.TAG_MAKE));

        final byte[] out = new BitmapReencoder().reencode(source);

        final BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(out, 0, out.length, bounds);
        assertEquals("turned upright: portrait", 1200, bounds.outWidth);
        assertEquals(BitmapReencoder.LONG_EDGE_PIXELS, bounds.outHeight);

        final ExifInterface after = new ExifInterface(new ByteArrayInputStream(out));
        assertNull(after.getAttribute(ExifInterface.TAG_MAKE));
        assertNull(after.getAttribute(ExifInterface.TAG_MODEL));
        assertNull(after.getAttribute(ExifInterface.TAG_GPS_LATITUDE));
        assertNull(after.getAttribute(ExifInterface.TAG_DATETIME));
        final String text = new String(out, StandardCharsets.ISO_8859_1);
        assertTrue(!text.contains("SECRET-MAKE") && !text.contains("SECRET-MODEL"));

        final MediaSanitizer.SanitizedImage clean = MediaSanitizer.sanitize(out);
        assertEquals(MediaSanitizer.ImageType.JPEG, clean.type());
        assertTrue(clean.bytes().length <= MediaSanitizer.MAX_IMAGE_BYTES);
    }

    @Test
    public void bytesThatAreNoPictureAreRefused() {
        assertThrows(MediaSanitizer.RejectedException.class, () -> new BitmapReencoder()
                .reencode("<svg onload=alert(1)>".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void aSmallPhotoKeepsItsSize() throws Exception {
        final Bitmap bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.BLUE);
        final java.io.ByteArrayOutputStream jpeg = new java.io.ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, jpeg));

        final byte[] out = new BitmapReencoder().reencode(jpeg.toByteArray());

        final MediaSanitizer.SanitizedImage clean = MediaSanitizer.sanitize(out);
        assertEquals(640, clean.width());
        assertEquals(480, clean.height());
    }
}
