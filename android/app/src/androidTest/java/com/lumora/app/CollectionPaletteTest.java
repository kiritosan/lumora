package com.lumora.app;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.test.InstrumentationTestCase;
import java.util.Arrays;

public final class CollectionPaletteTest extends InstrumentationTestCase {
    public void testDominantFamilyAndLightBandsAreActualImageColors() {
        CollectionPalette palette = CollectionPalette.fromColors(Arrays.asList(
            Color.RED, Color.RED, Color.RED, 0xFF990000, Color.BLUE));
        assertEquals("红色像素占多数", 0, palette.family);
        assertEquals(Color.RED, palette.colors[0]);
        assertEquals(3, palette.colors.length);
        assertEquals(6, CollectionPalette.fromColors(Arrays.asList(Color.GRAY)).family);
    }

    public void testMissingOrTransparentImageHasNoInventedPalette() {
        assertNull(CollectionPalette.from(null));
        Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        assertNull(CollectionPalette.from(bitmap));
        bitmap.recycle();
        assertNull(CollectionPalette.from(bitmap));
    }

    public void testSamplingDoesNotModifyImage() {
        Bitmap bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.BLUE);
        CollectionPalette palette = CollectionPalette.from(bitmap);
        assertEquals(3, palette.family);
        assertEquals(Color.BLUE, palette.colors[0]);
        assertEquals(Color.BLUE, bitmap.getPixel(32, 24));
        bitmap.recycle();
    }
}
