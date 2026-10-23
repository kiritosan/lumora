package com.lumora.app;

import android.test.InstrumentationTestCase;

import java.util.ArrayList;
import java.util.List;

/** Widget navigation is display-only; removed items fall back to a favorite. */
public final class WidgetSelectionTest extends InstrumentationTestCase {
    public void testIndependentSelectionAndWrap() {
        List<Item> items = new ArrayList<>();
        Item first = Item.newProcedural("第一件", "", Item.MATERIAL_GOLD, 0);
        Item second = Item.newProcedural("第二件", "", Item.MATERIAL_SEA, 1);
        second.favorite = true;
        items.add(first);
        items.add(second);
        assertEquals(second.id, LumoraWidget.choose(items, null, false).id);
        assertEquals(first.id, LumoraWidget.choose(items, second.id, true).id);
        assertEquals(second.id, LumoraWidget.choose(items, first.id, true).id);
        assertEquals(first.id, LumoraWidget.choose(items, first.id, false).id);
        assertEquals(second.id, LumoraWidget.choose(items, "removed-id", true).id);
        assertNull(LumoraWidget.choose(new ArrayList<Item>(), null, false));
        assertFalse(first.favorite);
    }
}
