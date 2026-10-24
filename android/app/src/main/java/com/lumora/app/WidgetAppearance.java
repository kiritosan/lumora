package com.lumora.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.widget.RemoteViews;

/** Widget-only matting and colors; never transforms the stored photo. */
final class WidgetAppearance {
    private WidgetAppearance() { }

    static int paper(int theme) {
        return theme == 1 ? 0xff183b38 : theme == 2 ? 0xffefe0c8 : 0xfff5f1e8;
    }

    static void apply(RemoteViews views, int theme) {
        int ink = theme == 1 ? 0xfff5f1e8 : 0xff183b38;
        int muted = theme == 1 ? 0xffc3d2c7 : 0xff58665a;
        views.setInt(R.id.widget_root, "setBackgroundResource", theme == 1
            ? R.drawable.widget_bg_night : theme == 2 ? R.drawable.widget_bg_sand : R.drawable.widget_bg);
        for (int id : new int[]{R.id.widget_title, R.id.widget_note, R.id.widget_previous,
                R.id.widget_next, R.id.widget_configure}) views.setTextColor(id, ink);
        views.setTextColor(R.id.widget_sub, muted);
        views.setTextColor(R.id.widget_brand, theme == 1 ? 0xffdfc28b : 0xff8c5935);
        views.setInt(R.id.widget_art, "setBackgroundColor", paper(theme));
    }

    static void draw(Canvas canvas, int width, int height, Item item, Bitmap photo,
                     int state, boolean fullImage, int theme) {
        if (!fullImage || photo == null || !item.isPhoto()) {
            ArtworkRenderer.drawArtwork(canvas, width, height, item, photo, 0, 0, state);
            return;
        }
        canvas.drawColor(paper(theme));
        float scale = Math.min(width / (float) photo.getWidth(), height / (float) photo.getHeight());
        float w = photo.getWidth() * scale, h = photo.getHeight() * scale;
        canvas.drawBitmap(photo, null, new RectF((width - w) / 2, (height - h) / 2,
            (width + w) / 2, (height + h) / 2), new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
    }
}
