package com.lumora.app;

import android.test.InstrumentationTestCase;

import java.util.ArrayList;
import java.util.List;

/** 掌心物理把玩沙盘测试：快照上限、物理步进、磁力与震动均不改动原藏品列表。 */
public final class TactileTrayTest extends InstrumentationTestCase {

    public void testSnapshotCapAndPhysicsStepLeaveSourceUntouched() throws Throwable {
        final List<Item> source = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            source.add(Item.newProcedural("器物 " + i, "私密手记 " + i, i % 3, i % 4));
        }
        final String firstBefore = source.get(0).toJson().toString();
        final TactileTrayView[] holder = new TactileTrayView[1];
        getInstrumentation().runOnMainSync(() -> {
            TactileTrayView tray = new TactileTrayView(getInstrumentation().getTargetContext());
            tray.setSnapshot(source, null, null);
            tray.layoutBodies(480, 640);
            tray.setMagnetMode(TactileTrayView.MAGNET_ATTRACT);
            tray.shake();
            tray.pulseCenter();
            for (int step = 0; step < 15; step++) {
                tray.stepPhysics(1f / 60f, 480, 640);
            }
            holder[0] = tray;
        });
        assertEquals("最多取前 8 件快照", 8, holder[0].bodyCount());
        assertEquals("原列表长度与内容不变", 10, source.size());
        assertEquals(firstBefore, source.get(0).toJson().toString());
        getInstrumentation().runOnMainSync(holder[0]::releaseBitmaps);
    }
}
