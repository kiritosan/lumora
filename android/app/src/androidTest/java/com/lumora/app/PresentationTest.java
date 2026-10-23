package com.lumora.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.test.AndroidTestCase;

/** Pixel-level contracts for private-note opt-in and preserving the entire original photograph. */
public final class PresentationTest extends AndroidTestCase {
    public void testPrivateNoteExcludedUntilExplicitlyEnabled() {
        Item item = Item.newProcedural("午后", "不公开的手记", 0, 0);
        Bitmap first = ShareCardRenderer.render(item, null, 0, false, 270);
        item.note = "另一段完全不同的私人内容";
        Bitmap second = ShareCardRenderer.render(item, null, 0, false, 270);
        assertTrue("未勾选手记时，手记内容不能影响任何输出像素", first.sameAs(second));
        Bitmap included = ShareCardRenderer.render(item, null, 0, true, 270);
        assertFalse(first.sameAs(included));
        first.recycle(); second.recycle(); included.recycle();
    }

    public void testEveryShareStyleProducesOpaquePortraitWithoutChangingItem() throws Exception {
        Item item = Item.newProcedural("长标题测试".repeat(12), "完整手记内容".repeat(70), 1, 1);
        String before = item.toJson().toString();
        int lastBackground = 0;
        for (int style = 0; style < ShareCardRenderer.NAMES.length; style++) {
            Bitmap bitmap = ShareCardRenderer.render(item, null, style, true, 1080);
            assertEquals(1080, bitmap.getWidth());
            assertEquals(1440, bitmap.getHeight());
            assertEquals(255, Color.alpha(bitmap.getPixel(0, 0)));
            if (style > 0) assertFalse(lastBackground == bitmap.getPixel(0, 0));
            lastBackground = bitmap.getPixel(0, 0);
            assertEquals(before, item.toJson().toString());
            bitmap.recycle();
        }
    }

    public void testOriginalModePreservesPhotoEdgesAndColorsForEveryFrame() {
        Bitmap photo = Bitmap.createBitmap(400, 100, Bitmap.Config.ARGB_8888);
        Canvas source = new Canvas(photo);
        source.drawColor(Color.GRAY);
        Paint p = new Paint(); p.setColor(Color.RED); source.drawRect(0, 0, 40, 100, p);
        p.setColor(Color.BLUE); source.drawRect(360, 0, 400, 100, p);
        Item item = Item.newPhoto("横幅", "", 0, "photo.jpg");
        DisplayFrame frame = new DisplayFrame();
        for (int style = 0; style < DisplayFrame.NAMES.length; style++) {
            Bitmap rendered = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888);
            frame.draw(new Canvas(rendered), 200, 300, item, photo,
                ArtworkRenderer.PHOTO_OK, 8, -8, style, true);
            assertEquals(Color.RED, rendered.getPixel(20, 150));
            assertEquals(Color.BLUE, rendered.getPixel(180, 150));
            assertEquals(Color.GRAY, rendered.getPixel(100, 150));
            assertEquals(Ui.CARD, rendered.getPixel(100, 40));
            rendered.recycle();
        }
        photo.recycle();
    }

    public void testShareCardHandlesBlankTitleAndWhitespaceNoteSafely() {
        Item item = Item.newProcedural("   ", "   \n  ", Item.MATERIAL_SEA, 2);
        item.title = null;
        Bitmap withoutNote = ShareCardRenderer.render(item, null, ShareCardRenderer.LETTER, false, 270);
        Bitmap withBlankNote = ShareCardRenderer.render(item, null, ShareCardRenderer.LETTER, true, 270);
        assertTrue("空白手记即使勾选也不应绘制额外摘录区域", withoutNote.sameAs(withBlankNote));
        withoutNote.recycle();
        withBlankNote.recycle();
    }

    public void testAllDisplayFramesAndLampAtmospheresRenderWithoutMutatingItem() throws Exception {
        Item item = Item.newProcedural("器物光景", "手记", Item.MATERIAL_GOLD, 1);
        String before = item.toJson().toString();
        DisplayFrame frame = new DisplayFrame();
        for (int lamp = 0; lamp < DisplayFrame.LAMP_NAMES.length; lamp++) {
            frame.setLamp(lamp);
            assertEquals(lamp, frame.getLamp());
            for (int style = 0; style < DisplayFrame.NAMES.length; style++) {
                Bitmap rendered = Bitmap.createBitmap(180, 220, Bitmap.Config.ARGB_8888);
                frame.draw(new Canvas(rendered), 180, 220, item, null,
                    ArtworkRenderer.PHOTO_OK, 4f, -3f, style, false);
                assertEquals(255, Color.alpha(rendered.getPixel(90, 110)));
                rendered.recycle();
            }
        }
        assertEquals(before, item.toJson().toString());
    }
}
