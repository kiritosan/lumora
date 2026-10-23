package com.lumora.app;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 程序化画作渲染器：四种光景（琥珀沙丘/青月水面/石间雾/朱色黄昏），
 * 照片藏品居中裁切后统一走材质釉光 + 追光箔面，分享大图走同一入口保证一致。
 */
public final class ArtworkRenderer {

    public static final int SHARE_W = 1080;
    public static final int SHARE_H = 1440;

    private static final int INK = Color.rgb(0x18, 0x3B, 0x38);
    private static final int PAPER = Color.rgb(0xF5, 0xF1, 0xE8);
    private static final int MUTED = Color.rgb(0x7B, 0x84, 0x77);
    private static final int CHAMPAGNE = Color.rgb(0xD6, 0xB6, 0x75);

    public static final int PHOTO_OK = 0;
    public static final int PHOTO_LOADING = 1;
    public static final int PHOTO_FAILED = 2;

    private ArtworkRenderer() {
    }

    /**
     * 主入口：按藏品类型画完整作品（含材质釉光与倾斜追光）。
     * 照片缺失时画诚实的占位（载入中/不可用），绝不拿别的美景冒充用户照片。
     */
    public static void drawArtwork(Canvas c, int w, int h, Item item, Bitmap photo,
                                   float tiltX, float tiltY, int photoState) {
        if (w <= 0 || h <= 0) return;
        if (item != null && item.isPhoto()) {
            if (photo != null && !photo.isRecycled()) {
                drawPhotoFill(c, w, h, photo);
            } else {
                drawPhotoPlaceholder(c, w, h, photoState);
            }
        } else {
            drawScene(c, w, h, item != null ? item.artworkSeed : 0);
        }
        int material = item != null ? item.material : Item.MATERIAL_GOLD;
        drawGlaze(c, w, h, material);
        drawFoil(c, w, h, tiltX, tiltY, material);
    }

    /** 卡片/小组件用位图。 */
    public static Bitmap renderArtBitmap(int w, int h, Item item, Bitmap photo, int photoState) {
        Bitmap out = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        drawArtwork(c, w, h, item, photo, 0f, 0f, photoState);
        return out;
    }

    /** 照片占位：载入中 / 不可用两态，中性纸面 + 拱形线描，诚实不装。 */
    private static void drawPhotoPlaceholder(Canvas c, int w, int h, int state) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h,
            Color.rgb(0xEF, 0xEA, 0xDC), Color.rgb(0xE3, 0xDC, 0xC9), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        // 细线拱
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2f, w * 0.006f));
        p.setColor(Color.argb(160, 0x7B, 0x84, 0x77));
        float aw = w * 0.26f, ah = h * 0.30f;
        float ax = w / 2f - aw / 2f, ay = h * 0.5f - ah * 0.42f;
        Path arch = new Path();
        arch.moveTo(ax, ay + ah);
        arch.lineTo(ax, ay + ah * 0.4f);
        arch.arcTo(new RectF(ax, ay, ax + aw, ay + ah * 0.8f), 180f, 180f);
        arch.lineTo(ax + aw, ay + ah);
        c.drawPath(arch, p);

        p.setStyle(Paint.Style.FILL);
        p.setColor(MUTED);
        p.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        p.setTextSize(Math.max(12f, w * 0.045f));
        p.setTextAlign(Paint.Align.CENTER);
        String label = state == PHOTO_LOADING ? "照片载入中…" : "照片不可用";
        c.drawText(label, w / 2f, ay + ah + Math.max(20f, h * 0.07f), p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    /** 导出分享卡 1080x1440：暖纸边框 + 画作 + 标题 + 编号展签。 */
    public static Bitmap renderShareBitmap(Item item, Bitmap photo) {
        return ShareCardRenderer.render(item, photo, ShareCardRenderer.GALLERY, false, SHARE_W);
    }

    private static int materialOf(Item item) {
        return item != null ? item.material : Item.MATERIAL_GOLD;
    }

    private static String ellipsize(Paint p, String s, float maxW) {
        if (p.measureText(s) <= maxW) return s;
        String dots = "…";
        int end = s.length();
        while (end > 0 && p.measureText(s.substring(0, end) + dots) > maxW) end--;
        return s.substring(0, Math.max(0, end)) + dots;
    }

    // ---- 四种光景 ----

    static void drawScene(Canvas c, int w, int h, int seed) {
        switch (seed) {
            case 1:
                sceneMoon(c, w, h);
                break;
            case 2:
                sceneMist(c, w, h);
                break;
            case 3:
                sceneDusk(c, w, h);
                break;
            case 0:
            default:
                sceneDunes(c, w, h);
                break;
        }
    }

    /** 琥珀沙丘：暖金天空、一轮日、层叠沙丘、拱形剪影。 */
    private static void sceneDunes(Canvas c, int w, int h) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h,
            Color.rgb(0xF7, 0xE3, 0xB4), Color.rgb(0xE3, 0xB0, 0x72), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        // 日
        float sx = w * 0.62f, sy = h * 0.30f, r = Math.min(w, h) * 0.16f;
        Paint sun = new Paint(Paint.ANTI_ALIAS_FLAG);
        sun.setShader(new RadialGradient(sx, sy, r * 2.4f,
            Color.argb(200, 0xF2, 0xD9, 0x9E), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        c.drawCircle(sx, sy, r * 2.4f, sun);
        sun.setShader(null);
        sun.setColor(Color.rgb(0xEA, 0xC9, 0x8C));
        c.drawCircle(sx, sy, r, sun);

        // 沙丘三层
        dunePath(c, w, h, 0.46f, 0.09f, Color.rgb(0xD9, 0xA2, 0x63));
        dunePath(c, w, h, 0.58f, 0.12f, Color.rgb(0xC8, 0x88, 0x4E));
        dunePath(c, w, h, 0.72f, 0.15f, Color.rgb(0xAF, 0x74, 0x49));

        // 拱形剪影
        Paint arch = new Paint(Paint.ANTI_ALIAS_FLAG);
        arch.setColor(Color.argb(235, 0x2A, 0x3B, 0x35));
        float aw = w * 0.34f, ah = h * 0.42f, ax = w * 0.12f, ay = h * 0.52f;
        Path path = new Path();
        path.moveTo(ax, ay + ah);
        path.lineTo(ax, ay + ah * 0.42f);
        path.arcTo(new RectF(ax, ay, ax + aw, ay + ah * 0.84f), 180f, 180f);
        path.lineTo(ax + aw, ay + ah);
        path.close();
        c.drawPath(path, arch);
    }

    /** 青月水面：深青夜色、月与光晕、水面碎光。 */
    private static void sceneMoon(Canvas c, int w, int h) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h,
            Color.rgb(0x14, 0x2E, 0x33), Color.rgb(0x2A, 0x55, 0x54), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        float mx = w * 0.40f, my = h * 0.26f, mr = Math.min(w, h) * 0.13f;
        Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        halo.setShader(new RadialGradient(mx, my, mr * 3f,
            Color.argb(170, 0xE9, 0xE4, 0xD2), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        c.drawCircle(mx, my, mr * 3f, halo);
        halo.setShader(null);
        halo.setColor(Color.rgb(0xE9, 0xE4, 0xD2));
        c.drawCircle(mx, my, mr, halo);
        halo.setColor(Color.argb(90, 0x2A, 0x55, 0x54));
        c.drawCircle(mx - mr * 0.3f, my - mr * 0.25f, mr * 0.82f, halo);

        // 地平线 + 水纹
        float horizon = h * 0.52f;
        Paint ridge = new Paint(Paint.ANTI_ALIAS_FLAG);
        ridge.setColor(Color.argb(220, 0x0E, 0x24, 0x28));
        Path rp = new Path();
        rp.moveTo(0, horizon + h * 0.05f);
        rp.lineTo(w * 0.25f, horizon - h * 0.02f);
        rp.lineTo(w * 0.55f, horizon + h * 0.01f);
        rp.lineTo(w, horizon - h * 0.04f);
        rp.lineTo(w, h);
        rp.lineTo(0, h);
        rp.close();
        c.drawPath(rp, ridge);

        Paint glint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 9; i++) {
            float y = horizon + h * 0.06f + i * h * 0.05f;
            float len = w * (0.10f + (i % 3) * 0.08f);
            float x = mx - len / 2 + (i % 2 == 0 ? -w * 0.04f : w * 0.05f);
            int alpha = Math.max(24, 150 - i * 14);
            glint.setColor(Color.argb(alpha, 0xE9, 0xE4, 0xD2));
            glint.setStrokeWidth(Math.max(2f, h * 0.008f));
            c.drawLine(x, y, x + len, y, glint);
        }
    }

    /** 石间雾：灰绿底、叠石、雾带。 */
    private static void sceneMist(Canvas c, int w, int h) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h,
            Color.rgb(0xCE, 0xD3, 0xC2), Color.rgb(0x99, 0xA3, 0x92), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        int[] stones = {0xFF6E7A6B, 0xFF7B8477, 0xFF5C675C, 0xFF87907E};
        float[] ys = {0.58f, 0.66f, 0.75f, 0.85f};
        for (int i = 0; i < stones.length; i++) {
            Paint s = new Paint(Paint.ANTI_ALIAS_FLAG);
            s.setColor(stones[i]);
            float cw = w * (0.62f - i * 0.06f);
            float cx = w * (i % 2 == 0 ? 0.34f : 0.62f);
            c.drawRoundRect(new RectF(cx - cw / 2, h * ys[i] - h * 0.055f,
                cx + cw / 2, h * ys[i] + h * 0.055f), h * 0.05f, h * 0.05f, s);
        }

        Paint fog = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < 4; i++) {
            float y = h * (0.30f + i * 0.15f);
            fog.setShader(new LinearGradient(0, y - h * 0.03f, 0, y + h * 0.03f,
                Color.TRANSPARENT, Color.argb(70 + i * 12, 0xF5, 0xF1, 0xE8),
                Shader.TileMode.CLAMP));
            c.drawRect(0, y - h * 0.05f, w, y + h * 0.05f, fog);
        }
        fog.setShader(null);

        Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        disc.setColor(Color.argb(120, 0xE8, 0xE2, 0xCE));
        c.drawCircle(w * 0.68f, h * 0.20f, Math.min(w, h) * 0.09f, disc);
    }

    /** 朱色黄昏：朱红渐变、低日、远山脊线。 */
    private static void sceneDusk(Canvas c, int w, int h) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h,
            Color.rgb(0xE0, 0x8A, 0x5F), Color.rgb(0x8E, 0x3A, 0x2C), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        float sx = w * 0.5f, sy = h * 0.46f, r = Math.min(w, h) * 0.17f;
        Paint sun = new Paint(Paint.ANTI_ALIAS_FLAG);
        sun.setShader(new RadialGradient(sx, sy, r * 2.6f,
            Color.argb(180, 0xF2, 0xC9, 0x8C), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        c.drawCircle(sx, sy, r * 2.6f, sun);
        sun.setShader(null);
        sun.setColor(Color.rgb(0xF0, 0xC8, 0x88));
        c.drawCircle(sx, sy, r, sun);

        ridge(c, w, h, 0.62f, 0.10f, Color.argb(235, 0x4A, 0x24, 0x1E));
        ridge(c, w, h, 0.76f, 0.13f, Color.argb(245, 0x33, 0x18, 0x16));
    }

    private static void dunePath(Canvas c, int w, int h, float base, float amp, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        Path path = new Path();
        path.moveTo(0, h * base);
        path.cubicTo(w * 0.3f, h * (base - amp), w * 0.7f, h * (base + amp), w, h * (base - amp * 0.5f));
        path.lineTo(w, h);
        path.lineTo(0, h);
        path.close();
        c.drawPath(path, p);
    }

    private static void ridge(Canvas c, int w, int h, float base, float amp, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        Path path = new Path();
        path.moveTo(0, h * base);
        for (int i = 1; i <= 6; i++) {
            float x = w * i / 6f;
            float y = h * (base + ((i % 2 == 0) ? amp : -amp * 0.6f));
            path.lineTo(x, y);
        }
        path.lineTo(w, h);
        path.lineTo(0, h);
        path.close();
        c.drawPath(path, p);
    }

    // ---- 照片与光 ----

    private static void drawPhotoFill(Canvas c, int w, int h, Bitmap photo) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setColor(PAPER);
        c.drawRect(0, 0, w, h, p);
        BitmapShader shader = new BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix mtx = new Matrix();
        float scale = Math.max((float) w / photo.getWidth(), (float) h / photo.getHeight());
        mtx.setScale(scale, scale);
        mtx.postTranslate((w - photo.getWidth() * scale) / 2f,
            (h - photo.getHeight() * scale) / 2f);
        shader.setLocalMatrix(mtx);
        p.setShader(shader);
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
    }

    /** 材质釉光：统一色调层 + 材质细腻肌理（蜜金拉丝/海盐晶面/银雾丝绢），照片与画作共用。 */
    private static void drawGlaze(Canvas c, int w, int h, int material) {
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
        switch (material) {
            case Item.MATERIAL_SEA:
                g.setColor(Color.argb(26, 0x6E, 0x9E, 0x96));
                break;
            case Item.MATERIAL_MIST:
                g.setColor(Color.argb(30, 0xB9, 0xC0, 0xB4));
                break;
            case Item.MATERIAL_GOLD:
            default:
                g.setColor(Color.argb(22, 0xAF, 0x74, 0x49));
                break;
        }
        c.drawRect(0, 0, w, h, g);

        if (material == Item.MATERIAL_GOLD) {
            // 暖金细微拉丝金属光泽
            g.setShader(new LinearGradient(0, 0, w, h * 0.45f,
                new int[]{Color.TRANSPARENT, Color.argb(20, 0xFB, 0xE6, 0xAE),
                    Color.argb(12, 0x30, 0x25, 0x18), Color.argb(18, 0xFF, 0xF6, 0xDD),
                    Color.TRANSPARENT},
                new float[]{0f, 0.28f, 0.48f, 0.68f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, g);
            g.setShader(null);
        } else if (material == Item.MATERIAL_SEA) {
            // 海盐矿物清透折光
            g.setShader(new LinearGradient(0, 0, w * 0.85f, h,
                new int[]{Color.TRANSPARENT, Color.argb(22, 0xE4, 0xFC, 0xFF),
                    Color.TRANSPARENT, Color.argb(18, 0xA8, 0xDD, 0xEC), Color.TRANSPARENT},
                new float[]{0f, 0.25f, 0.46f, 0.72f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, g);
            g.setShader(null);
        } else {
            // 银雾丝绸柔焦斜纹
            g.setShader(new LinearGradient(0, h * 0.15f, w, h * 0.85f,
                new int[]{Color.argb(12, 0x16, 0x20, 0x1E), Color.argb(22, 0xF5, 0xF1, 0xE8),
                    Color.argb(10, 0x2E, 0x4A, 0x47), Color.argb(18, 0xEC, 0xE8, 0xDC)},
                new float[]{0f, 0.36f, 0.66f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, g);
            g.setShader(null);
        }
    }

    /** 集中的柔光、色散虹彩与斜向箔带，随倾角流动，照片和暗色作品上也清晰动人。 */
    private static void drawFoil(Canvas c, int w, int h, float tiltX, float tiltY, int material) {
        float nx = tiltX / 8f, ny = tiltY / 8f; // 已钳制 ±8deg
        float cx = w * (0.5f + nx * 0.54f);
        float cy = h * (0.42f + ny * 0.52f);
        float rad = Math.max(w, h) * 0.47f;
        int centerColor = material == Item.MATERIAL_SEA
            ? Color.argb(148, 0xDF, 0xF4, 0xF2)
            : material == Item.MATERIAL_MIST
                ? Color.argb(132, 0xF7, 0xF4, 0xEC)
                : Color.argb(154, 0xFF, 0xF0, 0xCC);
        Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
        f.setShader(new RadialGradient(cx, cy, rad, centerColor, Color.TRANSPARENT,
            Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, f);
        f.setShader(null);

        // 双重全息色散流光带（主高光带 + 副棱镜带）
        Paint streak = new Paint(Paint.ANTI_ALIAS_FLAG);
        int prismA = material == Item.MATERIAL_SEA
            ? Color.argb(46, 0x98, 0xEC, 0xE8)
            : material == Item.MATERIAL_MIST
                ? Color.argb(38, 0xE2, 0xD8, 0xF0)
                : Color.argb(48, 0xF9, 0xDF, 0xA4);
        int prismB = material == Item.MATERIAL_SEA
            ? Color.argb(36, 0xC8, 0xD8, 0xFA)
            : Color.argb(38, 0xF4, 0xC2, 0xB8);
        streak.setShader(new LinearGradient(cx - w * 0.38f, cy - h * 0.24f,
            cx + w * 0.38f, cy + h * 0.24f,
            new int[] {Color.TRANSPARENT, prismA, Color.argb(72, 0xFF, 0xF9, 0xE6),
                prismB, Color.TRANSPARENT},
            new float[] {0f, 0.34f, 0.5f, 0.66f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, streak);
        streak.setShader(null);

        // 内缘高光细框（增加光学晶面厚度感）
        float inset = Math.max(1.5f, Math.min(w, h) * 0.012f);
        streak.setStyle(Paint.Style.STROKE);
        streak.setStrokeWidth(Math.max(1f, Math.min(w, h) * 0.003f));
        streak.setColor(Color.argb(44, 0xFF, 0xFD, 0xF6));
        c.drawRect(inset, inset, w - inset, h - inset, streak);

        // 边缘暗角，收拢视线
        Paint v = new Paint(Paint.ANTI_ALIAS_FLAG);
        v.setShader(new RadialGradient(w / 2f, h / 2f, Math.max(w, h) * 0.75f,
            Color.TRANSPARENT, Color.argb(48, 0x10, 0x20, 0x1E), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, v);
        v.setShader(null);
    }
}
