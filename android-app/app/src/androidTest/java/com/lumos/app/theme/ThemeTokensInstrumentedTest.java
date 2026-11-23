/*
 * Lumos - android-app/app / The CR-008 tokens, resolved by Android itself.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-UX-001, TRS-UX-002, SRS-UX-003, SRS-RMT-005
 * ADRs: -
 * Threading: test-only
 * Notes: tools/tokensgen proves the generated XML matches the token file and
 * that every promised pair is readable. This proves the other half on a real
 * device: that the resource system resolves them as designed - Midnight
 * under night mode, Daylight otherwise - that the Material theme's roles point
 * at the tokens, that the ticks follow them, that the app defaults to
 * Midnight, and that Sora is a font the platform can load.
 */

package com.lumos.app.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.lumos.app.LumosApplication;
import com.lumos.app.R;
import org.junit.Test;
import org.junit.runner.RunWith;

/** The design tokens as the device resolves them. */
@RunWith(AndroidJUnit4.class)
public final class ThemeTokensInstrumentedTest {

    private static Context inMode(final int nightFlag) {
        final Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final Configuration config = new Configuration(base.getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | nightFlag;
        return new ContextThemeWrapper(base.createConfigurationContext(config), R.style.Theme_Lumos);
    }

    private static int color(final Context c, final int res) {
        return c.getResources().getColor(res, c.getTheme());
    }

    private static int attr(final Context c, final int attr) {
        final TypedValue v = new TypedValue();
        c.getTheme().resolveAttribute(attr, v, true);
        return v.data;
    }

    @Test
    public void midnightAndDaylightResolveToTheTokenValues() {
        final Context night = inMode(Configuration.UI_MODE_NIGHT_YES);
        final Context day = inMode(Configuration.UI_MODE_NIGHT_NO);
        assertEquals(0xFF0A0D1A, color(night, R.color.lumos_canvas));
        assertEquals(0xFFF6F6FB, color(day, R.color.lumos_canvas));
        assertEquals(0xFF9D86FF, color(night, R.color.lumos_violet));
        assertEquals(0xFF5A3BE0, color(day, R.color.lumos_violet));
        assertEquals("the scrim keeps its alpha", 0xA305070F, color(night, R.color.lumos_scrim));
    }

    @Test
    public void theMaterialRolesPointAtTheTokens() {
        for (final int mode : new int[] {Configuration.UI_MODE_NIGHT_YES, Configuration.UI_MODE_NIGHT_NO}) {
            final Context c = inMode(mode);
            assertEquals(color(c, R.color.lumos_violet), attr(c, androidx.appcompat.R.attr.colorPrimary));
            assertEquals(color(c, R.color.lumos_on_violet),
                    attr(c, com.google.android.material.R.attr.colorOnPrimary));
            assertEquals(color(c, R.color.lumos_surface),
                    attr(c, com.google.android.material.R.attr.colorSurface));
            assertEquals(color(c, R.color.lumos_ink),
                    attr(c, com.google.android.material.R.attr.colorOnSurface));
            assertEquals(color(c, R.color.lumos_danger), attr(c, androidx.appcompat.R.attr.colorError));
            assertEquals(color(c, R.color.lumos_outline),
                    attr(c, com.google.android.material.R.attr.colorOutline));
        }
    }

    @Test
    public void theTicksFollowTheTokensInBothThemes() {
        for (final int mode : new int[] {Configuration.UI_MODE_NIGHT_YES, Configuration.UI_MODE_NIGHT_NO}) {
            final Context c = inMode(mode);
            assertEquals(color(c, R.color.lumos_read), color(c, R.color.tick_read));
            assertEquals(color(c, R.color.lumos_tick), color(c, R.color.tick_sent));
            assertEquals(color(c, R.color.lumos_danger), color(c, R.color.tick_failed));
            assertEquals(color(c, R.color.lumos_aqua), color(c, R.color.tick_nearby));
        }
    }

    @Test
    public void midnightIsTheDefaultAppearance() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, LumosApplication.DEFAULT_NIGHT_MODE);
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.getDefaultNightMode());
    }

    @Test
    public void soraLoadsAsAFont() {
        final Typeface sora = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getResources().getFont(R.font.sora);
        assertNotNull("the display face must load on the device", sora);
    }
}
