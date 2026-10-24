package com.lumora.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Per-widget display preferences. Collection data is never written here. */
public final class WidgetExhibit {
    static final String PREFS = "lumora_widget_display";
    public static final String[] SOURCES = {"全部藏品", "只看心动", "蜜金藏品", "海盐藏品", "银雾藏品"};
    public static final String[] STYLES = {"一件光景", "三联展陈", "一页手记"};
    public int source;
    public int style;
    public boolean daily = true;
    public String pinned;

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static WidgetExhibit load(Context context, int id) {
        SharedPreferences p = prefs(context);
        WidgetExhibit out = new WidgetExhibit();
        out.source = Math.max(0, Math.min(4, p.getInt("source_" + id, 0)));
        out.style = Math.max(0, Math.min(2, p.getInt("style_" + id, 0)));
        out.daily = p.getBoolean("daily_" + id, true);
        out.pinned = p.getString("pin_" + id, null);
        return out;
    }

    void save(Context context, int id) {
        prefs(context).edit().putInt("source_" + id, source).putInt("style_" + id, style)
            .putBoolean("daily_" + id, daily).putString("pin_" + id, pinned)
            .remove("selection_" + id).remove("day_" + id).apply();
    }

    static void remove(Context context, int id) {
        SharedPreferences.Editor e = prefs(context).edit();
        for (String key : new String[]{"source_", "style_", "daily_", "pin_", "selection_", "day_"}) e.remove(key + id);
        e.apply();
    }

    public List<Item> candidates(List<Item> items) {
        List<Item> out = new ArrayList<>();
        for (Item item : items) {
            if (source == 1 && !item.favorite) continue;
            if (source >= 2 && item.material != source - 2) continue;
            out.add(item.copy());
        }
        return CollectionInsights.select(out, "", false, null, CollectionInsights.NEWEST);
    }

    Item choose(List<Item> items, String selected, boolean advance, LocalDate today, String previousDay) {
        if (items.isEmpty()) return null;
        for (Item item : items) if (item.id.equals(pinned)) return item.copy();
        if (daily && !advance && !today.toString().equals(previousDay)) {
            return CollectionInsights.daily(items, today);
        }
        return LumoraWidget.choose(items, selected, advance).copy();
    }

    boolean isPinned(Item item) { return item != null && item.id.equals(pinned); }
}
