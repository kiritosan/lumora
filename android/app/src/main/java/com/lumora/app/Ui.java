package com.lumora.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 视觉常量与程序化 UI 小工具：暖纸/墨青/苔灰/铜/香槟。 */
public final class Ui {

    public static final int PAPER = 0xFFF7F5F0;
    public static final int INK = 0xFF183B38;
    public static final int INK_SOFT = 0xFF2E4A47;
    public static final int MUTED = 0xFF667265;
    public static final int COPPER = 0xFFAF7449;
    public static final int CHAMPAGNE = 0xFFD6B675;
    public static final int CARD = 0xFFFFFDF8;
    public static final int LINE = 0xFFE5E6DF;

    private static final String[] MATERIAL_NAMES = {"蜜金", "海盐", "银雾"};

    private Ui() {
    }

    public static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
            c.getResources().getDisplayMetrics());
    }

    public static String materialName(int m) {
        if (m < 0 || m >= MATERIAL_NAMES.length) return MATERIAL_NAMES[0];
        return MATERIAL_NAMES[m];
    }

    public static String dateStr(long ts) {
        return new SimpleDateFormat("yyyy年M月d日", Locale.ROOT).format(new Date(ts));
    }

    public static String dateCompact(long ts) {
        return new SimpleDateFormat("yyyy.MM.dd", Locale.ROOT).format(new Date(ts));
    }

    public static void toast(Context c, String msg) {
        Toast.makeText(c.getApplicationContext(), msg, Toast.LENGTH_LONG).show();
    }

    public static void haptic(Context c, View v) {
        if (Settings.hapticsEnabled(c)) {
            v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        }
    }

    public static GradientDrawable rounded(Context c, int fill, float rDp,
                                         Integer strokeColor, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, rDp));
        if (strokeColor != null) g.setStroke(Math.max(1, dp(c, strokeDp)), strokeColor);
        return g;
    }

    /** Warm translucent surface: layered rather than flat, with an optical edge. */
    public static GradientDrawable glass(Context c, float radius) {
        GradientDrawable g = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            new int[] {0xFFFFFFFF, 0xF5FFFEFA, 0xEBE8EDE7});
        g.setCornerRadius(dp(c, radius));
        g.setStroke(Math.max(1, dp(c, 1)), 0xDDFFFFFF);
        return g;
    }

    public static GradientDrawable canvasBackground() {
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[] {0xFFF8F5EF, 0xFFF3F5F0, 0xFFECEFE9});
    }

    public static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        t.setIncludeFontPadding(true);
        return t;
    }

    public static TextView serif(Context c, String s, float sp, int color) {
        TextView t = text(c, s, sp, color);
        t.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        return t;
    }

    public static TextView serifBold(Context c, String s, float sp, int color) {
        TextView t = text(c, s, sp, color);
        t.setTypeface(Typeface.create("serif", Typeface.BOLD));
        return t;
    }

    /** 小号大写感展签：字距拉开的上标风格文字。 */
    public static TextView eyebrow(Context c, String s) {
        TextView t = text(c, s, 11f, MUTED);
        t.setLetterSpacing(0.28f);
        return t;
    }

    /** 实心/描边按钮，最小触控高 48dp、无系统默认最小宽。 */
    public static Button button(Context c, String s, boolean filled) {
        Button b = new Button(c);
        b.setText(s);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setAllCaps(false);
        b.setMinHeight(dp(c, 48));
        b.setMinimumHeight(dp(c, 48));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(c, 12), 0, dp(c, 12), 0);
        if (filled) {
            b.setBackground(rounded(c, INK, 24, null, 0));
            b.setTextColor(PAPER);
        } else {
            b.setBackground(glass(c, 24));
            b.setTextColor(INK);
        }
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x247F937F),
            b.getBackground(), rounded(c, 0xFFFFFFFF, 24, null, 0)));
        return b;
    }

    /** 可切换 chips；selected 态由调用方刷新。 */
    public static TextView chip(Context c, String s, boolean selected) {
        TextView t = text(c, s, 13f, selected ? PAPER : INK_SOFT);
        t.setMinHeight(dp(c, 48));
        t.setMinWidth(dp(c, 56));
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(dp(c, 14), dp(c, 6), dp(c, 14), dp(c, 6));
        t.setFocusable(true);
        restyleChip(c, t, selected);
        return t;
    }

    public static void restyleChip(Context c, TextView t, boolean selected) {
        t.setTextColor(selected ? PAPER : INK_SOFT);
        t.setSelected(selected);
        t.setContentDescription(t.getText()
            + (selected ? "（已选中）" : ""));
        t.setBackground(selected ? rounded(c, INK, 20, null, 0) : glass(c, 20));
    }

    public static View hairline(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.75f)));
        v.setLayoutParams(lp);
        return v;
    }

    public static LinearLayout vbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout hbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    public static View spacer(Context c, int hDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, hDp)));
        return v;
    }

    /** 内容最大宽 ~720dp，横屏/平板居中。 */
    public static int contentWidth(Context c) {
        int sw = c.getResources().getDisplayMetrics().widthPixels;
        return Math.min(sw, dp(c, 720));
    }

    public static String materialDescription(int material) {
        switch (material) {
            case Item.MATERIAL_SEA: return "海盐 · 把海风留在青蓝的釉面里";
            case Item.MATERIAL_MIST: return "银雾 · 像清晨薄雾，安静而柔和";
            default: return "蜜金 · 为日常镀上一层温暖的琥珀光";
        }
    }

    /** Actionable cards get the same optical touch feedback as buttons. */
    public static void touchSurface(Context c, View v, float radius) {
        v.setForeground(new RippleDrawable(ColorStateList.valueOf(0x227F937F), null,
            rounded(c, 0xFFFFFFFF, radius, null, 0)));
        v.setFocusable(true);
    }

    /** 动画总开关（animator_duration_scale=0 → 立即到位）。 */
    public static boolean animationsEnabled(Context c) {
        try {
            float scale = android.provider.Settings.Global.getFloat(
                c.getContentResolver(),
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            return scale != 0f;
        } catch (Exception e) {
            return true;
        }
    }
}
