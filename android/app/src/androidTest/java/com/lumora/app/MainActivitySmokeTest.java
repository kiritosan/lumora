package com.lumora.app;

import android.content.Intent;
import android.net.Uri;
import android.test.ActivityInstrumentationTestCase2;
import android.view.View;

/**
 * 冒烟：启动、三页签切换、外部 intent 只消费一次。
 * 覆盖范围说明：启动/页签/intent 消费标记在进程内可断言；
 * 真实图片导入落盘已在 ImageRepositoryTest 覆盖，
 * 编辑草稿交互依赖真实触摸链路，未在本类内模拟（不冒充已测）。
 */
@SuppressWarnings("deprecation")
public final class MainActivitySmokeTest
    extends ActivityInstrumentationTestCase2<MainActivity> {

    public MainActivitySmokeTest() {
        super(MainActivity.class);
    }

    public void testLaunchShowsHome() throws Throwable {
        MainActivity a = getActivity();
        assertNotNull(a);
        getInstrumentation().waitForIdleSync();
        View nav = a.findViewById(R.id.nav_home);
        assertNotNull(nav);
    }

    public void testSwitchAllTabs() throws Throwable {
        final MainActivity a = getActivity();
        assertNotNull(a);
        final int[] ids = {R.id.nav_table, R.id.nav_mine, R.id.nav_home};
        for (final int id : ids) {
            runTestOnUiThread(() -> {
                View cell = a.findViewById(id);
                assertNotNull(cell);
                cell.performClick();
            });
            getInstrumentation().waitForIdleSync();
        }
    }

    /** ACTION_SEND 进来被消费并打上标记，不再重放。 */
    public void testExternalSendConsumedOnce() throws Throwable {
        setActivityIntent(new Intent(Intent.ACTION_SEND)
            .setType("image/*")
            .putExtra(Intent.EXTRA_STREAM,
                Uri.parse("content://example.invalid/photo.jpg")));
        final MainActivity a = getActivity();
        assertNotNull(a);
        // store 载入 + intent 处理都在共享 io 上，给它一点时间
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            getInstrumentation().waitForIdleSync();
            String action = a.getIntent().getAction();
            if (MainActivity.ACTION_CONSUMED.equals(action)) return;
            Thread.sleep(100);
        }
        assertEquals("分享 intent 必须被消费一次",
            MainActivity.ACTION_CONSUMED, a.getIntent().getAction());
    }

    /** 畸形分享（EXTRA_STREAM 不是 Uri）同样被消费，不崩溃。 */
    public void testMalformedSendConsumed() throws Throwable {
        setActivityIntent(new Intent(Intent.ACTION_SEND)
            .setType("image/*")
            .putExtra(Intent.EXTRA_STREAM, "not-a-uri"));
        final MainActivity a = getActivity();
        assertNotNull(a);
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            getInstrumentation().waitForIdleSync();
            String action = a.getIntent().getAction();
            if (MainActivity.ACTION_CONSUMED.equals(action)) return;
            Thread.sleep(100);
        }
        assertEquals(MainActivity.ACTION_CONSUMED, a.getIntent().getAction());
    }

    /**
     * 相机生命周期：重建只恢复 token，绝不推断结果——
     * 空暂存文件（相机仍在写）与挂起凭证都必须存活。
     */
    public void testPendingCameraSurvivesRecreation() throws Throwable {
        final MainActivity a = getActivity();
        assertNotNull(a);
        String name = "cam_test_" + java.util.UUID.randomUUID()
            .toString().replace("-", "") + ".jpg";
        java.io.File f = AppState.get(a).repo().cameraFile(name);
        assertNotNull(f);
        assertTrue(f.createNewFile()); // 空文件：相机可能仍未写回
        Settings.setPendingCamera(a, name);
        try {
            runTestOnUiThread(() -> a.restoreCameraState(new android.os.Bundle()));
            assertTrue("空的相机暂存文件不得在重建时被删", f.exists());
            assertEquals("挂起凭证不得被清", name, Settings.pendingCamera(a));
        } finally {
            Settings.clearPendingCamera(a);
            f.delete();
        }
    }

    /** 保存对账判据：同 id 不够，标题/手记/材质/照片/创建时间都要一致。 */
    public void testSameDraftFieldCompare() {
        Item committed = Item.newPhoto("新标题", "新手记", Item.MATERIAL_SEA,
            "abcd1234.jpg");
        Item draft = committed.copy();
        assertTrue(MainActivity.sameDraft(committed, draft));

        // 既有记录是旧标题（写盘失败留下的旧值）→ 不得误判成功
        Item stale = committed.copy();
        stale.title = "旧标题";
        assertFalse(MainActivity.sameDraft(stale, draft));

        Item wrongPhoto = committed.copy();
        wrongPhoto.photoName = "eeee5678.jpg";
        assertFalse(MainActivity.sameDraft(wrongPhoto, draft));

        Item wrongMat = committed.copy();
        wrongMat.material = Item.MATERIAL_GOLD;
        assertFalse(MainActivity.sameDraft(wrongMat, draft));

        // 草稿标题带首尾空格（未 trim）仍应匹配已 trim 的入藏值
        Item padded = committed.copy();
        padded.title = "  " + committed.title + "  ";
        assertTrue(MainActivity.sameDraft(committed, padded));
        assertFalse(MainActivity.sameDraft(null, draft));
    }
}
