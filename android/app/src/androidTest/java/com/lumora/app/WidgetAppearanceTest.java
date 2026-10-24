package com.lumora.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.TextView;

public final class WidgetAppearanceTest extends InstrumentationTestCase {
    public void testFullPhotoKeepsEdgesColorsAndSourceAcrossThemes() {
        Bitmap photo = Bitmap.createBitmap(40, 100, Bitmap.Config.ARGB_8888);
        photo.eraseColor(Color.RED);
        for (int y = 10; y < 90; y++) for (int x = 5; x < 35; x++) photo.setPixel(x, y, Color.BLUE);
        Item item = Item.newPhoto("原图", "不应出现在画面中的手记", Item.MATERIAL_GOLD, "photo-1234.jpg");
        Bitmap output = Bitmap.createBitmap(480, 360, Bitmap.Config.ARGB_8888);
        try {
            for (int theme = 0; theme < 3; theme++) {
                WidgetAppearance.draw(new Canvas(output), 480, 360, item, photo,
                    ArtworkRenderer.PHOTO_OK, true, theme);
                assertEquals(WidgetAppearance.paper(theme), output.getPixel(20, 180));
                assertEquals(Color.RED, output.getPixel(171, 180));
                assertEquals(Color.RED, output.getPixel(309, 180));
                assertEquals(Color.RED, output.getPixel(240, 4));
                assertEquals(Color.RED, output.getPixel(240, 355));
                assertEquals(Color.BLUE, output.getPixel(240, 180));
            }
            assertEquals(Color.RED, photo.getPixel(0, 0));
            assertEquals(Color.BLUE, photo.getPixel(20, 50));
            assertFalse(photo.isRecycled());
        } finally { photo.recycle(); output.recycle(); }
    }

    public void testEveryLayoutAcceptsRemoteThemeOperations() throws Throwable {
        runTestOnUiThread(() -> {
            Context context = getInstrumentation().getTargetContext();
            for (int layout : new int[]{R.layout.widget_compact, R.layout.widget_wide, R.layout.widget_collectible}) {
                for (int theme = 0; theme < 3; theme++) {
                    RemoteViews remote = new RemoteViews(context.getPackageName(), layout);
                    WidgetAppearance.apply(remote, theme);
                    View view = remote.apply(context, null);
                    TextView title = view.findViewById(R.id.widget_title);
                    assertEquals(theme == 1 ? 0xfff5f1e8 : 0xff183b38, title.getCurrentTextColor());
                    TextView note = view.findViewById(R.id.widget_note);
                    assertEquals(View.GONE, note.getVisibility());
                    assertEquals("", note.getText().toString());
                    assertNotNull(view.findViewById(R.id.widget_previous));
                }
            }
        });
    }
}
