package com.lumora.app;

import android.test.InstrumentationTestCase;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 光桌选中同步：setItems 后宿主必须拿到实际选中的 id
 * （含 keepId 缺失时的回退项），空列表回传 null。
 */
public final class StagePanelTest extends InstrumentationTestCase {

    private StagePanel panel;
    private final AtomicReference<String> selected = new AtomicReference<>();

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        final File base = new File(getInstrumentation().getTargetContext()
            .getCacheDir(), "sp-" + UUID.randomUUID());
        StagePanel.Host host = new StagePanel.Host() {
            @Override public void onEdit(Item item) { }
            @Override public void onToggleFavorite(Item item) { }
            @Override public void onShare(Item item) { }
            @Override public void onRequestAdd() { }
            @Override public void onSelectionChanged(String id) {
                selected.set(id);
            }
        };
        getInstrumentation().runOnMainSync(() -> {
            panel = new StagePanel(getInstrumentation().getTargetContext(), host,
                new ImageRepository(new File(base, "photos"),
                    new File(base, "cache")),
                Runnable::run);
        });
    }

    private void onMain(Runnable r) throws Throwable {
        getInstrumentation().runOnMainSync(r);
    }

    private static List<Item> items(int n) {
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(Item.newProcedural("藏品 " + i, "", i % 3, i % 4));
        }
        return out;
    }

    public void testFallbackSelectionNotifiesHost() throws Throwable {
        List<Item> its = items(3);
        onMain(() -> panel.setItems(its, null));
        assertEquals("无 keepId 时回退到第一件并通知宿主",
            its.get(0).id, selected.get());
    }

    public void testKeepIdSelectionNotifiesHost() throws Throwable {
        List<Item> its = items(3);
        onMain(() -> panel.setItems(its, its.get(2).id));
        assertEquals(its.get(2).id, selected.get());
    }

    public void testEmptySelectionIsNull() throws Throwable {
        onMain(() -> panel.setItems(new ArrayList<Item>(), null));
        assertNull(selected.get());
    }
}
