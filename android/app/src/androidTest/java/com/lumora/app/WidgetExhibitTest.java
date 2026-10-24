package com.lumora.app;

import android.test.InstrumentationTestCase;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class WidgetExhibitTest extends InstrumentationTestCase {
    private List<Item> items() {
        Item gold = Item.newProcedural("金", "记忆", Item.MATERIAL_GOLD, 0);
        Item sea = Item.newProcedural("海", "", Item.MATERIAL_SEA, 1);
        sea.favorite = true;
        return new ArrayList<>(Arrays.asList(gold, sea));
    }

    public void testSourceIsStrictAndDoesNotMutateCollection() {
        List<Item> items = items();
        WidgetExhibit exhibit = new WidgetExhibit();
        exhibit.source = 1;
        List<Item> heart = exhibit.candidates(items);
        assertEquals(1, heart.size());
        assertEquals(items.get(1).id, heart.get(0).id);
        heart.get(0).favorite = false;
        assertTrue(items.get(1).favorite);
        exhibit.source = 4;
        assertTrue("银雾范围不能回退到别的材质", exhibit.candidates(items).isEmpty());
    }

    public void testDailySelectionStableAcrossOrderAndManualSelectionSurvivesSameDay() {
        List<Item> items = items();
        WidgetExhibit exhibit = new WidgetExhibit();
        LocalDate day = LocalDate.of(2026, 10, 24);
        Item daily = exhibit.choose(items, null, false, day, null);
        Collections.reverse(items);
        assertEquals(daily.id, exhibit.choose(items, null, false, day, null).id);
        Item manual = exhibit.choose(items, daily.id, true, day, day.toString());
        assertFalse(daily.id.equals(manual.id));
        assertEquals(manual.id, exhibit.choose(items, manual.id, false, day, day.toString()).id);
        assertEquals(CollectionInsights.daily(items, day.plusDays(1)).id,
            exhibit.choose(items, manual.id, false, day.plusDays(1), day.toString()).id);
    }

    public void testPinSurvivesAdvanceAndDeletedPinFallsBack() {
        List<Item> items = items();
        WidgetExhibit exhibit = new WidgetExhibit();
        exhibit.pinned = items.get(0).id;
        LocalDate day = LocalDate.now();
        assertEquals(exhibit.pinned, exhibit.choose(items, items.get(1).id, true, day, null).id);
        items.remove(0);
        assertEquals(items.get(0).id, exhibit.choose(items, exhibit.pinned, false, day, null).id);
        assertNull(exhibit.choose(Collections.emptyList(), null, false, day, null));
    }

    public void testManualModeDoesNotChangeWhenDayChanges() {
        List<Item> items = items();
        WidgetExhibit exhibit = new WidgetExhibit();
        exhibit.daily = false;
        LocalDate today = LocalDate.now();
        assertEquals(items.get(0).id, exhibit.choose(items, items.get(0).id,
            false, today, today.minusDays(1).toString()).id);
    }

    public void testCombinedDimensionsNeverFallBackOrWriteItems() {
        List<Item> items = items();
        Item photo = Item.newPhoto("照片", " 私人手记 ", Item.MATERIAL_SEA, "photo-1234.jpg");
        photo.favorite = true;
        items.add(photo);
        Item sample = photo.copy();
        sample.id = "sample-1234";
        sample.sample = true;
        items.add(sample);
        WidgetExhibit exhibit = new WidgetExhibit();
        exhibit.source = 1;
        exhibit.personalOnly = true;
        exhibit.photosOnly = true;
        exhibit.notesOnly = true;
        List<Item> matches = exhibit.candidates(items);
        assertEquals(1, matches.size());
        assertEquals(photo.id, matches.get(0).id);
        matches.get(0).note = "改变快照";
        assertEquals(" 私人手记 ", photo.note);
        exhibit.source = 2;
        assertTrue(exhibit.candidates(items).isEmpty());
        exhibit.source = 1;
        photo.note = "  \n ";
        assertTrue(exhibit.candidates(items).isEmpty());
        photo.note = null;
        assertTrue(exhibit.candidates(items).isEmpty());
    }

    public void testPreviousWrapPinAndSameDayRetention() {
        List<Item> items = items();
        WidgetExhibit exhibit = new WidgetExhibit();
        LocalDate day = LocalDate.of(2026, 10, 24);
        Item previous = exhibit.choose(items, items.get(0).id, -1, day, null);
        assertEquals(items.get(1).id, previous.id);
        assertEquals(previous.id, exhibit.choose(items, previous.id, 0, day, day.toString()).id);
        assertEquals(items.get(0).id, exhibit.choose(items, previous.id, 1, day, day.toString()).id);
        exhibit.pinned = items.get(0).id;
        assertEquals(exhibit.pinned, exhibit.choose(items, previous.id, -1, day, null).id);
        exhibit.pinned = null;
        assertEquals(items.get(1).id, exhibit.choose(items, "deleted-item", -1, day, null).id);
        assertNull(exhibit.choose(Collections.emptyList(), null, -1, day, null));
        assertFalse(items.get(0).favorite);
    }

    public void testPreferencesAndCleanupAreIndependentPerWidget() {
        String sandbox = "widget-test-" + java.util.UUID.randomUUID();
        android.content.Context context = new android.content.ContextWrapper(
            getInstrumentation().getTargetContext()) {
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(sandbox, mode);
            }
        };
        try {
            WidgetExhibit first = new WidgetExhibit();
            first.style = 2;
            first.source = 1;
            first.pinned = "a-pinned-item";
            first.theme = 2;
            first.personalOnly = true;
            first.photosOnly = true;
            first.notesOnly = true;
            first.fullImage = true;
            first.save(context, 11);
            WidgetExhibit second = new WidgetExhibit();
            second.style = 1;
            second.daily = false;
            second.save(context, 12);
            assertEquals(2, WidgetExhibit.load(context, 11).style);
            assertEquals("a-pinned-item", WidgetExhibit.load(context, 11).pinned);
            WidgetExhibit restored = WidgetExhibit.load(context, 11);
            assertEquals(2, restored.theme);
            assertTrue(restored.personalOnly && restored.photosOnly && restored.notesOnly && restored.fullImage);
            assertFalse(WidgetExhibit.load(context, 12).fullImage);
            // Launcher restore copies every new dimension to its new widget ID.
            restored.save(context, 13);
            assertTrue(WidgetExhibit.load(context, 13).photosOnly);
            assertEquals(2, WidgetExhibit.load(context, 13).theme);
            WidgetExhibit.remove(context, 11);
            assertNull(WidgetExhibit.load(context, 11).pinned);
            assertEquals(0, WidgetExhibit.load(context, 11).theme);
            assertFalse(WidgetExhibit.load(context, 11).personalOnly);
            assertFalse(WidgetExhibit.load(context, 11).photosOnly);
            assertFalse(WidgetExhibit.load(context, 11).notesOnly);
            assertFalse(WidgetExhibit.load(context, 11).fullImage);
            assertTrue(WidgetExhibit.load(context, 13).fullImage);
            assertEquals(1, WidgetExhibit.load(context, 12).style);
            assertFalse(WidgetExhibit.load(context, 12).daily);
            WidgetExhibit.prefs(context).edit().putInt("theme_12", 999).commit();
            assertEquals(2, WidgetExhibit.load(context, 12).theme);
        } finally { WidgetExhibit.prefs(context).edit().clear().commit(); }
    }
}
