package com.lumora.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Presentation-only matting. Reuses geometry to avoid allocating on every tilt frame. */
public final class DisplayFrame {
    public static final int EDGE = 0, MUSEUM = 1, ARCH = 2;
    public static final String[] NAMES = {"无边", "画廊", "拱窗"};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    private final RectF photoRect = new RectF();
    private final Path clip = new Path();

    public void draw(Canvas canvas, int w, int h, Item item, Bitmap photo, int state,
                     float tiltX, float tiltY, int frame, boolean original) {
        if (w <= 0 || h <= 0) return;
        canvas.drawColor(Ui.CARD);
        float pad = frame == EDGE && !original ? 0 : Math.min(w, h) * 0.075f;
        rect.set(pad, pad, w - pad, h - pad);
        if (rect.width() <= 0 || rect.height() <= 0) return;
        if (original && photo != null && !photo.isRecycled()
            && photo.getWidth() > 0 && photo.getHeight() > 0) {
            float scale = Math.min(rect.width() / photo.getWidth(), rect.height() / photo.getHeight());
            float pw = photo.getWidth() * scale, ph = photo.getHeight() * scale;
            photoRect.set((w - pw) / 2, (h - ph) / 2, (w + pw) / 2, (h + ph) / 2);
            canvas.drawBitmap(photo, null, photoRect, paint);
            return;
        }
        clip.rewind();
        if (frame == ARCH && !original) {
            float radius = Math.min(rect.width() / 2, rect.height() * 0.42f);
            clip.moveTo(rect.left, rect.bottom);
            clip.lineTo(rect.left, rect.top + radius);
            clip.cubicTo(rect.left, rect.top - radius * 0.34f,
                rect.right, rect.top - radius * 0.34f, rect.right, rect.top + radius);
            clip.lineTo(rect.right, rect.bottom);
            clip.close();
        } else clip.addRect(rect, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        canvas.translate(rect.left, rect.top);
        ArtworkRenderer.drawArtwork(canvas, Math.max(1, Math.round(rect.width())),
            Math.max(1, Math.round(rect.height())), item, photo,
            original ? 0f : tiltX, original ? 0f : tiltY, state);
        canvas.restore();
        if (pad > 0) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1, w / 550f));
            paint.setColor(0x887A765F);
            canvas.drawPath(clip, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }
}
