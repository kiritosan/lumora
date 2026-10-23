package com.lumora.app;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Read-only collection projections. Dates follow the device's local calendar. */
public final class CollectionInsights {
    public static final int NEWEST = 0, OLDEST = 1, TITLE = 2, FAVORITES = 3;
    public static final String[] SORT_NAMES = {"最近入藏", "最早入藏", "标题顺序", "心动优先"};

    private CollectionInsights() { }

    public static LocalDate date(long timestamp) {
        return Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    public static List<Item> select(List<Item> source, String query, boolean favorites,
                                    Integer material, int sort) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Item> out = new ArrayList<>();
        for (Item it : source) {
            if (favorites && !it.favorite) continue;
            if (material != null && material != it.material) continue;
            String hay = (it.title + " " + it.note + " " + it.serial())
                .toLowerCase(Locale.ROOT);
            if (!q.isEmpty() && !hay.contains(q)) continue;
            out.add(it.copy());
        }
        Comparator<Item> recent = Comparator.comparingLong((Item it) -> it.created)
            .reversed().thenComparing(it -> it.id);
        Comparator<Item> comparator = recent;
        if (sort == OLDEST) comparator = Comparator.comparingLong((Item it) -> it.created)
            .thenComparing(it -> it.id);
        if (sort == TITLE) comparator = Comparator.comparing(
            (Item it) -> it.title == null ? "" : it.title, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(recent);
        if (sort == FAVORITES) comparator = Comparator.comparing((Item it) -> !it.favorite)
            .thenComparing(recent);
        out.sort(comparator);
        return out;
    }

    /** Stable for a local day and independent of incoming order. Personal items come first. */
    public static Item daily(List<Item> source, LocalDate day) {
        List<Item> candidates = new ArrayList<>();
        for (Item it : source) if (!it.sample) candidates.add(it);
        if (candidates.isEmpty()) candidates.addAll(source);
        if (candidates.isEmpty()) return null;
        candidates.sort(Comparator.comparing(it -> it.id));
        int pick = (int) Math.floorMod(day.toEpochDay(), (long) candidates.size());
        return candidates.get(pick).copy();
    }

    public static int[] monthDays(List<Item> items, YearMonth month) {
        int[] days = new int[month.lengthOfMonth()];
        for (Item it : items) {
            if (it.sample) continue;
            LocalDate d = date(it.created);
            if (YearMonth.from(d).equals(month)) days[d.getDayOfMonth() - 1]++;
        }
        return days;
    }

    public static int activeDays(List<Item> items) {
        Set<LocalDate> days = new HashSet<>();
        for (Item it : items) if (!it.sample) days.add(date(it.created));
        return days.size();
    }

    public static String monthLabel(YearMonth month) {
        return month.getYear() + " 年 " + month.getMonthValue() + " 月";
    }
}
