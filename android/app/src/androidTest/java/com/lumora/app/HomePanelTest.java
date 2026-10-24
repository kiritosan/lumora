package com.lumora.app;

import android.test.InstrumentationTestCase;
import android.view.View;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 藏馆页常驻「拾取」入口：非空藏馆里 header 按钮可见可点，
 * 搜索无结果 / 空列表时依然可达。不触碰外部图片选择器。
 */
public final class HomePanelTest extends InstrumentationTestCase {

    private static final class FakeHost implements HomePanel.Host {
        final AtomicInteger addCalls = new AtomicInteger();
        final AtomicInteger opens = new AtomicInteger();
        @Override public void onOpen(Item item) { opens.incrementAndGet(); }
        @Override public void onRequestAdd() { addCalls.incrementAndGet(); }
        @Override public void onOpenStage() { }
    }

    private HomePanel panel;
    private FakeHost host;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        host = new FakeHost();
        final File base = new File(getInstrumentation().getTargetContext()
            .getCacheDir(), "hp-" + UUID.randomUUID());
        getInstrumentation().runOnMainSync(() -> {
            panel = new HomePanel(getInstrumentation().getTargetContext(), host,
                new ImageRepository(new File(base, "photos"),
                    new File(base, "cache")),
                Runnable::run); // 直跑 executor：测试内解码同步完成
        });
    }

    private void onMain(Runnable r) throws Throwable {
        getInstrumentation().runOnMainSync(r);
    }

    private static List<Item> samples(int n) {
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(Item.newProcedural("示例 " + i, "", i % 3, i % 4));
        }
        return out;
    }

    public void testAddPhotoVisibleAndClickableWithNonEmpty() throws Throwable {
        onMain(() -> panel.setItems(samples(4)));
        View add = panel.findViewById(R.id.add_photo);
        assertNotNull("非空藏馆必须有常驻拾取入口", add);
        assertTrue(add.isEnabled());
        onMain(add::performClick);
        assertEquals(1, host.addCalls.get());
    }

    public void testAddPhotoStillReachableOnEmptyResult() throws Throwable {
        onMain(() -> panel.setItems(new ArrayList<Item>()));
        View add = panel.findViewById(R.id.add_photo);
        assertNotNull(add);
        assertTrue("空结果时拾取入口仍可用", add.isEnabled());
        onMain(add::performClick);
        assertEquals(1, host.addCalls.get());
    }

    public void testAddDisabledWhileLoading() throws Throwable {
        onMain(() -> panel.setLoading(true));
        View add = panel.findViewById(R.id.add_photo);
        assertNotNull(add);
        assertFalse(add.isEnabled());
    }

    public void testNewDisplayModesRestoreWithoutChangingItems() throws Throwable {
        List<Item> items = samples(2);
        items.get(0).note = "那天的光落在窗边。";
        android.os.Bundle state = new android.os.Bundle();
        onMain(() -> {
            panel.setItems(items);
            panel.findViewById(R.id.view_notes).performClick();
            panel.saveState(state);
        });
        assertEquals(3, state.getInt("homeLens"));
        onMain(() -> {
            panel.findViewById(R.id.view_gallery).performClick();
            panel.restoreState(state);
        });
        assertTrue(panel.findViewById(R.id.view_notes).isSelected());
        assertEquals("那天的光落在窗边。", items.get(0).note);
        assertFalse(items.get(0).favorite);
    }
}
