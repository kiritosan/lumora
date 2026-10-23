package com.lumora.app;

import android.test.AndroidTestCase;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Read-only projections must not mutate stored items or vary with list ordering. */
public final class CollectionInsightsTest extends AndroidTestCase {
    private static Item item(String title, int day, int material) {
        Item it = Item.newProcedural(title, "a quiet afternoon", material, 0);
        it.created = LocalDate.of(2024, 2, day).atStartOfDay(ZoneId.systemDefault())
            .toInstant().toEpochMilli();
        return it;
    }

    public void testDailyStableAcrossOrderAndPrefersPersonalItems() {
        Item sample = item("示例", 1, 0); sample.sample = true;
        Item first = item("first", 2, 1), second = item("second", 3, 2);
        List<Item> items = new ArrayList<>(Arrays.asList(sample, first, second));
        LocalDate today = LocalDate.of(2024, 2, 29);
        Item selected = CollectionInsights.daily(items, today);
        assertFalse(selected.sample);
        Collections.reverse(items);
        assertEquals(selected.id, CollectionInsights.daily(items, today).id);
        selected.title = "changed copy";
        assertFalse("changed copy".equals(CollectionInsights.daily(items, today).title));
        assertNull(CollectionInsights.daily(Collections.emptyList(), today));
        assertEquals(sample.id, CollectionInsights.daily(Arrays.asList(sample), today).id);
    }

    public void testSortingAndCombinedFiltersLeaveSourceUntouched() {
        Item a = item("A", 1, 0), b = item("B", 2, 1), c = item("C", 3, 1);
        b.favorite = true;
        List<Item> source = Arrays.asList(a, b, c);
        List<Item> recent = CollectionInsights.select(source, "", false, null, CollectionInsights.NEWEST);
        assertEquals(c.id, recent.get(0).id);
        assertEquals(a.id, source.get(0).id);
        recent.get(0).title = "copy";
        assertEquals("C", c.title);
        assertEquals(a.id, CollectionInsights.select(source, "", false, null, CollectionInsights.OLDEST).get(0).id);
        assertEquals(a.id, CollectionInsights.select(source, "", false, null, CollectionInsights.TITLE).get(0).id);
        assertEquals(b.id, CollectionInsights.select(source, "", false, null, CollectionInsights.FAVORITES).get(0).id);
        List<Item> filtered = CollectionInsights.select(source, "quiet", true, 1, 0);
        assertEquals(1, filtered.size());
        assertEquals(b.id, filtered.get(0).id);
        assertEquals(1, CollectionInsights.select(source, a.serial(), false, null, 0).size());
    }

    public void testSearchUsesStableLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            Item it = item("LIGHT", 1, 0);
            assertEquals(1, CollectionInsights.select(Arrays.asList(it), "light", false, null, 0).size());
        } finally { Locale.setDefault(original); }
    }

    public void testLeapMonthAndSampleExclusion() {
        Item a = item("a", 29, 0), b = item("b", 29, 1), sample = item("sample", 1, 2);
        sample.sample = true;
        List<Item> items = Arrays.asList(a, b, sample);
        int[] days = CollectionInsights.monthDays(items, YearMonth.of(2024, 2));
        assertEquals(29, days.length);
        assertEquals(2, days[28]);
        assertEquals(0, days[0]);
        assertEquals(1, CollectionInsights.activeDays(items));
        assertEquals(31, CollectionInsights.monthDays(items, YearMonth.of(2024, 3)).length);
    }
}
