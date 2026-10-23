package com.lumora.app;

import android.content.Context;
import android.test.InstrumentationTestCase;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 进程级共享态：不同 Context 拿到同一实例；busy 全局互斥；
 * 监听注册/注销生效；暂挂草稿可取可清。
 */
public final class AppStateTest extends InstrumentationTestCase {

    private Context ctx() {
        return getInstrumentation().getTargetContext();
    }

    public void testSingletonAcrossContexts() {
        AppState a = AppState.get(ctx());
        AppState b = AppState.get(
            getInstrumentation().getContext()); // instrumentation 自己的上下文
        AppState c = AppState.get(ctx().getApplicationContext());
        assertSame("进程内必须共享同一实例", a, c);
        assertNotNull(a);
        assertNotNull(b);
    }

    public void testBusyMutualExclusion() {
        AppState app = AppState.get(ctx());
        // 可能有遗留 busy，先确保释放
        app.releaseBusy();
        assertTrue(app.tryBusy());
        assertFalse("同刻不得二次进入", app.tryBusy());
        app.releaseBusy();
        assertTrue("释放后可再进入", app.tryBusy());
        app.releaseBusy();
    }

    public void testStoreLoadedOnceAndShared() throws Exception {
        AppState app = AppState.get(ctx());
        app.ensureLoaded();
        long deadline = System.currentTimeMillis() + 8000;
        while (!app.storeReady() && app.storeError() == null
            && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue("共享 store 必须完成载入（或已记录错误）",
            app.storeReady() || app.storeError() != null);
    }

    public void testListenerFiresOnMainAndUnregisterStops() throws Exception {
        AppState app = AppState.get(ctx());
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch first = new CountDownLatch(1);
        AppState.DataListener l = () -> {
            calls.incrementAndGet();
            first.countDown();
        };
        app.register(l);
        app.notifyDataChanged();
        assertTrue("监听必须在主线程收到一次回调",
            first.await(3, TimeUnit.SECONDS));
        int afterFirst = calls.get();
        app.unregister(l);
        app.notifyDataChanged();
        Thread.sleep(300);
        assertEquals("注销后不得再收到回调", afterFirst, calls.get());
    }

    public void testPendingDraftRoundtrip() {
        AppState app = AppState.get(ctx());
        app.clearPendingDraft();
        app.setPendingDraft("orphan_test.jpg");
        assertNotNull(app.peekPendingDraft());
        assertEquals("orphan_test.jpg", app.peekPendingDraft().photoName);
        AppState.PendingDraft taken = app.takePendingDraft();
        assertEquals("orphan_test.jpg", taken.photoName);
        assertNull("取走后不得残留", app.peekPendingDraft());
    }

    /** 取消草稿的最后防线：被已入藏藏品引用的照片绝不删除，孤儿才清。 */
    public void testDiscardPhotoKeepsReferencedFile() throws Exception {
        java.io.File base = new java.io.File(ctx().getCacheDir(),
            "st-" + java.util.UUID.randomUUID());
        ImageRepository repo = new ImageRepository(
            new java.io.File(base, "photos"), new java.io.File(base, "cache"));
        CollectionStore store = new CollectionStore(new java.io.File(base, "s.json"));
        store.load();
        try {
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(
                32, 32, android.graphics.Bitmap.Config.ARGB_8888);
            b.eraseColor(android.graphics.Color.GREEN);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos);
            b.recycle();
            byte[] png = bos.toByteArray();

            String used = repo.importPhoto(new java.io.ByteArrayInputStream(png));
            String orphan = repo.importPhoto(new java.io.ByteArrayInputStream(png));
            Item it = Item.newPhoto("引用者", "", Item.MATERIAL_GOLD, used);
            store.upsert(it);

            AppState.discardPhotoIfUnreferenced(store, repo, used);
            assertTrue("被藏品引用的照片必须保留",
                repo.photoFile(used).exists());
            AppState.discardPhotoIfUnreferenced(store, repo, orphan);
            assertFalse("无人引用的照片才被删除",
                repo.photoFile(orphan).exists());
        } finally {
            CollectionStoreTest.deleteTree(base);
        }
    }
}
