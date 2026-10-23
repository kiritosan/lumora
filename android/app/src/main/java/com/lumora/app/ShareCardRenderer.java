package com.lumora.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

/** The preview and exported image use exactly the same layout and font metrics. */
public final class ShareCardRenderer {
    public static final int GALLERY = 0, NIGHT = 1, LETTER = 2;
    public static final String[] NAMES = {"美术馆", "夜色", "来信"};
    public static final int WIDTH = 1080, HEIGHT = 1440;
    private ShareCardRenderer() { }

    public static Bitmap render(Item item, Bitmap photo, int style, boolean includeNote, int width) {
        if (item == null) throw new IllegalArgumentException("缺少藏品");
        if (style < GALLERY || style > LETTER) throw new IllegalArgumentException("未知分享版式");
        if (width < 1 || width > WIDTH) throw new IllegalArgumentException("分享尺寸非法");
        Bitmap result = Bitmap.createBitmap(width, Math.round(width * (HEIGHT / (float) WIDTH)), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(result);
        c.scale(width / (float) WIDTH, width / (float) WIDTH);
        boolean night = style == NIGHT, letter = style == LETTER;
        boolean note = includeNote && item.note != null && !item.note.trim().isEmpty();
        int ink = night ? Ui.PAPER : Ui.INK;
        int secondary = night ? 0xFFBECBBF : Ui.MUTED;
        int accent = night ? Ui.CHAMPAGNE : Ui.COPPER;
        c.drawColor(night ? 0xFF102C29 : letter ? 0xFFF0E7D6 : Ui.CARD);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(accent); p.setStrokeWidth(2);
        c.drawLine(72, 90, 1008, 90, p);
        small(c, "LUMORA  /  " + (night ? "AFTER HOURS" : letter ? "A LETTER OF LIGHT" : "PRIVATE GALLERY"), 72, 64, accent, 24);
        float left = letter ? 96 : 72, top = 132;
        float artHeight = note ? 650 : 870;
        if (letter) {
            p.setColor(Ui.CARD);
            c.drawRect(64, 112, 1016, top + artHeight + 28, p);
        }
        c.save();
        if (night) {
            Path arch = new Path();
            arch.moveTo(left, top + artHeight); arch.lineTo(left, top + 220);
            arch.cubicTo(left, top - 74, WIDTH - left, top - 74, WIDTH - left, top + 220);
            arch.lineTo(WIDTH - left, top + artHeight); arch.close();
            c.clipPath(arch);
        } else c.clipRect(left, top, WIDTH - left, top + artHeight);
        c.translate(left, top);
        boolean photoOk = photo != null && !photo.isRecycled();
        ArtworkRenderer.drawArtwork(c, (int) (WIDTH - left * 2), (int) artHeight, item,
            photoOk ? photo : null, 0, 0,
            item.isPhoto() && !photoOk ? ArtworkRenderer.PHOTO_FAILED : ArtworkRenderer.PHOTO_OK);
        c.restore();
        float y = top + artHeight + 42;
        String safeTitle = item.title == null || item.title.trim().isEmpty() ? "未命名光景" : item.title.trim();
        y += paragraph(c, safeTitle, 72, y, 936, 48, ink, 2,
            Typeface.create("serif", letter ? Typeface.ITALIC : Typeface.NORMAL));
        y += 16;
        small(c, Ui.dateCompact(item.created) + "  ·  " + Ui.materialName(item.material)
            + "  ·  " + item.serial(), 72, y + 28, secondary, 27);
        y += 64;
        if (note) {
            p.setColor(accent); c.drawLine(72, y, 128, y, p);
            y += 20;
            // Fixed six-line excerpt keeps every layout inside the postcard margin.
            paragraph(c, item.note.trim(), 72, y, 936, 31, secondary, 6,
                Typeface.create("serif", Typeface.NORMAL));
        }
        p.setColor(accent); c.drawCircle(83, 1353, 8, p);
        small(c, "拾光 · 留住日常的光", 108, 1362, secondary, 25);
        return result;
    }

    private static float paragraph(Canvas c, String text, float x, float y, int width,
                                   float size, int color, int lines, Typeface face) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color); p.setTextSize(size); p.setTypeface(face);
        StaticLayout layout = StaticLayout.Builder.obtain(text == null ? "" : text, 0,
                text == null ? 0 : text.length(), p, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
            .setLineSpacing(10, 1).setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).build();
        c.save(); c.translate(x, y); layout.draw(c); c.restore();
        return layout.getHeight();
    }

    private static void small(Canvas c, String text, float x, float y, int color, float size) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color); p.setTextSize(size); p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        c.drawText(text, x, y, p);
    }
}
