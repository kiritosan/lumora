package com.lumora.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

/**
 * 器物装裱与光学灯光渲染器（仅展示层，不修改藏品数据）。
 * 支持五种器物形态（无边/画廊/拱窗/徽章/相纸）与四种展厅灯温（卤素暖光/天光昼白/月光冷蓝/烛火琥珀）。
 * 复用几何对象避免在倾斜追光高频帧中产生分配抖动。
 */
public final class DisplayFrame {
    public static final int EDGE = 0, MUSEUM = 1, ARCH = 2, MEDALLION = 3, POLAROID = 4;
    public static final String[] NAMES = {"无边", "画廊", "拱窗", "徽章", "相纸"};

    public static final int LAMP_HALOGEN = 0, LAMP_DAYLIGHT = 1, LAMP_MOON = 2, LAMP_CANDLE = 3;
    public static final String[] LAMP_NAMES = {"暖卤素", "美术馆昼光", "青月冷光", "烛火琥珀"};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF outerRect = new RectF();
    private final RectF photoRect = new RectF();
    private final Path clip = new Path();
    private int lamp = LAMP_HALOGEN;

    public void setLamp(int lamp) {
        this.lamp = Math.max(0, Math.min(LAMP_CANDLE, lamp));
    }

    public int getLamp() {
        return lamp;
    }

    public void draw(Canvas canvas, int w, int h, Item item, Bitmap photo, int state,
                     float tiltX, float tiltY, int frame, boolean original) {
        if (w <= 0 || h <= 0) return;
        canvas.drawColor(Ui.CARD);
        int safeFrame = Math.max(0, Math.min(POLAROID, frame));
        float minDim = Math.min(w, h);
        float pad = safeFrame == EDGE && !original ? 0f : minDim * 0.075f;
        rect.set(pad, pad, w - pad, h - pad);
        if (rect.width() <= 0 || rect.height() <= 0) return;

        // 完整原图模式：零裁切、零色偏、零追光叠加，边缘像素与原色彩 100% 忠实呈现
        if (original && photo != null && !photo.isRecycled()
            && photo.getWidth() > 0 && photo.getHeight() > 0) {
            float scale = Math.min(rect.width() / photo.getWidth(), rect.height() / photo.getHeight());
            float pw = photo.getWidth() * scale, ph = photo.getHeight() * scale;
            photoRect.set((w - pw) / 2f, (h - ph) / 2f, (w + pw) / 2f, (h + ph) / 2f);
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFFFFFFF);
            canvas.drawBitmap(photo, null, photoRect, paint);
            return;
        }

        // 计算特殊器物形态的内窗与外衬
        if (!original && safeFrame == MEDALLION) {
            float diameter = minDim * 0.82f;
            float cx = w / 2f, cy = h / 2f;
            float r = diameter / 2f;
            outerRect.set(cx - r * 1.07f, cy - r * 1.07f, cx + r * 1.07f, cy + r * 1.07f);
            // 金属外环底座
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(
                outerRect.left, outerRect.top, outerRect.right, outerRect.bottom,
                new int[]{0xFFFDF8EC, 0xFFD2B47C, 0xFF5D5344, 0xFFEADAB8, 0xFF8C734B},
                new float[]{0f, 0.25f, 0.52f, 0.78f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawOval(outerRect, paint);
            paint.setShader(null);
            rect.set(cx - r, cy - r, cx + r, cy + r);
        } else if (!original && safeFrame == POLAROID) {
            float cardM = minDim * 0.055f;
            outerRect.set(cardM, cardM, w - cardM, h - cardM);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(
                outerRect.left, outerRect.top, outerRect.right, outerRect.bottom,
                0xFFFAF5EA, 0xFFE5DCCB, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(outerRect, minDim * 0.025f, minDim * 0.025f, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, w / 480f));
            paint.setColor(0x558A7E68);
            canvas.drawRoundRect(outerRect, minDim * 0.025f, minDim * 0.025f, paint);
            paint.setStyle(Paint.Style.FILL);

            float innerPad = minDim * 0.095f;
            float bottomCap = Math.max(innerPad * 2.35f, h * 0.20f);
            rect.set(innerPad, innerPad, w - innerPad, h - bottomCap);
        } else if (!original && safeFrame == MUSEUM) {
            // 画廊卡纸内凹衬底
            outerRect.set(pad * 0.55f, pad * 0.55f, w - pad * 0.55f, h - pad * 0.55f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, w / 640f));
            paint.setColor(0x44AF7449);
            canvas.drawRect(outerRect, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        clip.rewind();
        if (!original && safeFrame == ARCH) {
            float radius = Math.min(rect.width() / 2f, rect.height() * 0.42f);
            clip.moveTo(rect.left, rect.bottom);
            clip.lineTo(rect.left, rect.top + radius);
            clip.cubicTo(rect.left, rect.top - radius * 0.34f,
                rect.right, rect.top - radius * 0.34f, rect.right, rect.top + radius);
            clip.lineTo(rect.right, rect.bottom);
            clip.close();
        } else if (!original && safeFrame == MEDALLION) {
            clip.addOval(rect, Path.Direction.CW);
        } else {
            clip.addRect(rect, Path.Direction.CW);
        }

        canvas.save();
        canvas.clipPath(clip);
        canvas.translate(rect.left, rect.top);
        int rw = Math.max(1, Math.round(rect.width()));
        int rh = Math.max(1, Math.round(rect.height()));
        ArtworkRenderer.drawArtwork(canvas, rw, rh, item, photo,
            original ? 0f : tiltX, original ? 0f : tiltY, state);
        if (!original) {
            drawLampAtmosphere(canvas, rw, rh, tiltX, tiltY, lamp);
        }
        canvas.restore();

        // 描边与器物压纹细节
        if (pad > 0 || safeFrame == MEDALLION || safeFrame == POLAROID) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1.2f, w / 460f));
            paint.setColor(safeFrame == MEDALLION ? 0xAAFFF5D6 : 0x887A765F);
            canvas.drawPath(clip, paint);
            if (!original && safeFrame == MEDALLION) {
                float inset = minDim * 0.026f;
                photoRect.set(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset);
                paint.setStrokeWidth(Math.max(1f, w / 620f));
                paint.setColor(0x66FFF8E7);
                canvas.drawOval(photoRect, paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }

        // 即影相纸底部手写感展签与编号
        if (!original && safeFrame == POLAROID && item != null) {
            float capCenterY = rect.bottom + (outerRect.bottom - rect.bottom) * 0.52f;
            String title = item.title == null || item.title.trim().isEmpty()
                ? "Untitled Light" : item.title.trim();
            textPaint.setTypeface(Typeface.create("serif", Typeface.ITALIC));
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(Math.max(11f, minDim * 0.052f));
            textPaint.setColor(0xFF36312C);
            if (textPaint.measureText(title) > rect.width() * 0.92f) {
                int len = title.length();
                while (len > 1 && textPaint.measureText(title.substring(0, len) + "…") > rect.width() * 0.92f) {
                    len--;
                }
                title = title.substring(0, len) + "…";
            }
            canvas.drawText(title, w / 2f, capCenterY, textPaint);

            textPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            textPaint.setTextSize(Math.max(8f, minDim * 0.027f));
            textPaint.setColor(0xFF7D7467);
            canvas.drawText(item.serial() + "  ·  " + Ui.materialName(item.material),
                w / 2f, capCenterY + minDim * 0.045f, textPaint);
            textPaint.setTextAlign(Paint.Align.LEFT);
        }
    }

    private void drawLampAtmosphere(Canvas c, int w, int h, float tiltX, float tiltY, int lampMode) {
        if (lampMode == LAMP_HALOGEN) return; // 默认暖光已融于基础箔面
        float nx = tiltX / 8f, ny = tiltY / 8f;
        float lx = w * (0.5f - nx * 0.38f);
        float ly = h * (0.28f - ny * 0.32f);
        float rad = Math.max(w, h) * 0.72f;
        int spotColor;
        int washColor;
        if (lampMode == LAMP_DAYLIGHT) {
            spotColor = Color.argb(44, 0xF8, 0xFC, 0xFF);
            washColor = Color.argb(14, 0xE8, 0xF4, 0xFA);
        } else if (lampMode == LAMP_MOON) {
            spotColor = Color.argb(58, 0x9E, 0xD8, 0xE6);
            washColor = Color.argb(26, 0x16, 0x38, 0x44);
        } else {
            spotColor = Color.argb(66, 0xFF, 0xB8, 0x6B);
            washColor = Color.argb(24, 0x52, 0x26, 0x0E);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(washColor);
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(new RadialGradient(lx, ly, rad, spotColor, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
    }
}
