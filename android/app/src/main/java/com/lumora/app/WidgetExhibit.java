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
    public static final String[] THEMES = {"纸白 · 日光展厅", "夜馆 · 墨青微光", "暖砂 · 琥珀展签"};
    public int source;
    public int style;
    public boolean daily = true;
    public int theme;
    public boolean personalOnly;
    public boolean photosOnly;
    public boolean notesOnly;
    public boolean fullImage;
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
        out.theme = Math.max(0, Math.min(THEMES.length - 1, p.getInt("theme_" + id, 0)));
        out.personalOnly = p.getBoolean("personal_" + id, false);
        out.photosOnly = p.getBoolean("photos_" + id, false);
        out.notesOnly = p.getBoolean("notes_" + id, false);
        out.fullImage = p.getBoolean("full_" + id, false);
        out.pinned = p.getString("pin_" + id, null);
        return out;
    }

    void save(Context context, int id) {
        prefs(context).edit().putInt("source_" + id, source).putInt("style_" + id, style)
            .putBoolean("daily_" + id, daily).putString("pin_" + id, pinned)
            .putInt("theme_" + id, theme).putBoolean("personal_" + id, personalOnly)
            .putBoolean("photos_" + id, photosOnly).putBoolean("notes_" + id, notesOnly)
            .putBoolean("full_" + id, fullImage)
            .remove("selection_" + id).remove("day_" + id).apply();
    }

    static void remove(Context context, int id) {
        SharedPreferences.Editor e = prefs(context).edit();
        for (String key : new String[]{"source_", "style_", "daily_", "pin_", "selection_", "day_",
                "theme_", "personal_", "photos_", "notes_", "full_"}) e.remove(key + id);
        e.apply();
    }

    public List<Item> candidates(List<Item> items) {
        List<Item> out = new ArrayList<>();
        for (Item item : items) {
            if (personalOnly && item.sample) continue;
            if (photosOnly && !item.isPhoto()) continue;
            if (notesOnly && (item.note == null || item.note.trim().isEmpty())) continue;
            if (source == 1 && !item.favorite) continue;
            if (source >= 2 && item.material != source - 2) continue;
            out.add(item.copy());
        }
        return CollectionInsights.select(out, "", false, null, CollectionInsights.NEWEST);
    }

    Item choose(List<Item> items, String selected, boolean advance, LocalDate today, String previousDay) {
        return choose(items, selected, advance ? 1 : 0, today, previousDay);
    }

    Item choose(List<Item> items, String selected, int direction, LocalDate today, String previousDay) {
        if (items.isEmpty()) return null;
        for (Item item : items) if (item.id.equals(pinned)) return item.copy();
        if (daily && direction == 0 && !today.toString().equals(previousDay)) {
            return CollectionInsights.daily(items, today);
        }
        if (direction < 0) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).id.equals(selected)) return items.get((i + items.size() - 1) % items.size()).copy();
            }
        }
        return LumoraWidget.choose(items, selected, direction > 0).copy();
    }

    boolean isPinned(Item item) { return item != null && item.id.equals(pinned); }
}
