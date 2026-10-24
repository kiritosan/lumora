package com.lumora.app;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Small, deterministic colour study. Reads image pixels, never modifies an artwork. */
public final class CollectionPalette {
    public static final String[] NAMES = {"朱砂", "晨金", "苔绿", "海蓝", "鸢尾", "蔷薇", "素色"};
    public final int family;
    public final int[] colors;
    private CollectionPalette(int family, int[] colors) {
        this.family = family;
        this.colors = colors;
    }

    public static CollectionPalette from(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return null;
        List<Integer> pixels = new ArrayList<>();
        for (int y = 0; y < bitmap.getHeight(); y += Math.max(1, bitmap.getHeight() / 32)) {
            for (int x = 0; x < bitmap.getWidth(); x += Math.max(1, bitmap.getWidth() / 32)) {
                int color = bitmap.getPixel(x, y);
                if (Color.alpha(color) > 128) pixels.add(color);
            }
        }
        return fromColors(pixels);
    }

    static CollectionPalette fromColors(List<Integer> pixels) {
        // Hue families and three value bands keep a real light/mid/shadow palette.
        long[][] sums = new long[21][4];
        float[] hsv = new float[3];
        int[] families = new int[7];
        for (int color : pixels) {
            Color.colorToHSV(color, hsv);
            int family = family(hsv);
            int band = Math.min(2, (int) (hsv[2] * 3));
            long[] bucket = sums[family * 3 + band];
            bucket[0] += Color.red(color);
            bucket[1] += Color.green(color);
            bucket[2] += Color.blue(color);
            bucket[3]++;
            families[family]++;
        }
        int dominant = 0;
        for (int i = 1; i < families.length; i++) if (families[i] > families[dominant]) dominant = i;
        List<Integer> buckets = new ArrayList<>();
        for (int i = 0; i < sums.length; i++) if (sums[i][3] > 0) buckets.add(i);
        if (buckets.isEmpty()) return null;
        buckets.sort(Comparator.<Integer>comparingLong(i -> sums[i][3]).reversed().thenComparingInt(i -> i));
        int[] colors = new int[Math.min(3, buckets.size())];
        for (int i = 0; i < colors.length; i++) {
            long[] b = sums[buckets.get(i)];
            colors[i] = Color.rgb((int) (b[0] / b[3]), (int) (b[1] / b[3]), (int) (b[2] / b[3]));
        }
        return new CollectionPalette(dominant, colors);
    }

    private static int family(float[] hsv) {
        if (hsv[1] < 0.16f || hsv[2] < 0.15f) return 6;
        float hue = hsv[0];
        if (hue < 25 || hue >= 350) return 0;
        if (hue < 75) return 1;
        if (hue < 170) return 2;
        if (hue < 255) return 3;
        if (hue < 295) return 4;
        return 5;
    }

    static String key(Item item) {
        return item.id + ":" + item.photoName + ":" + item.artworkSeed + ":" + item.material;
    }
}
