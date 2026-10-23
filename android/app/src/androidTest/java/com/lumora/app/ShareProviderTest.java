package com.lumora.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.test.InstrumentationTestCase;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.UUID;

/**
 * ShareProvider 出口约束：只读分享、相机写需挂起凭证、拒绝穿越。
 * 所有目录与偏好都走隔离 ContextWrapper：cache/files 落独立子目录、
 * SharedPreferences 加测试前缀，清理只碰自己的命名空间，绝不动应用真实数据。
 */
public final class ShareProviderTest extends InstrumentationTestCase {

    /** 隔离上下文：应用级调用也会被重定向进测试命名空间。 */
    private static final class IsolatedContext extends ContextWrapper {
        private final File root;

        IsolatedContext(Context base, File root) {
            super(base);
            this.root = root;
        }

        @Override
        public Context getApplicationContext() {
            return this; // Settings 经 applicationContext 取 prefs，一并隔离
        }

        @Override
        public File getCacheDir() {
            File d = new File(root, "cache");
            d.mkdirs();
            return d;
        }

        @Override
        public File getFilesDir() {
            File d = new File(root, "files");
            d.mkdirs();
            return d;
        }

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            return super.getSharedPreferences("isotest_" + name, mode);
        }
    }

    private ShareProvider provider;
    private Context ctx;          // 隔离上下文
    private File isoRoot;
    private File shareDir;
    private File cameraDir;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        Context target = getInstrumentation().getTargetContext();
        isoRoot = new File(target.getCacheDir(),
            "provider-" + UUID.randomUUID());
        ctx = new IsolatedContext(target, isoRoot);
        provider = new ShareProvider();
        provider.attachInfo(ctx, null);
        shareDir = new File(ctx.getCacheDir(), "share");
        cameraDir = new File(ctx.getCacheDir(), "camera");
        shareDir.mkdirs();
        cameraDir.mkdirs();
    }

    @Override
    protected void tearDown() throws Exception {
        Settings.clearPendingCamera(ctx);   // 只清测试命名空间
        CollectionStoreTest.deleteTree(isoRoot);
        super.tearDown();
    }

    private File makeShareFile() throws Exception {
        String name = "lumora-" + UUID.randomUUID().toString().replace("-", "")
            + ".png";
        File f = new File(shareDir, name);
        java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
        fos.write(new byte[]{1, 2, 3});
        fos.close();
        return f;
    }

    public void testAuthorityFollowsPackage() {
        assertEquals(ctx.getPackageName() + ".fileprovider",
            ShareProvider.authority(ctx));
        assertEquals(ctx.getPackageName() + ".fileprovider",
            ShareProvider.shareUri(ctx, "a.png").getAuthority());
    }

    public void testShareReadAllowedWriteDenied() throws Exception {
        File f = makeShareFile();
        Uri uri = ShareProvider.shareUri(ctx, f.getName());
        ParcelFileDescriptor pfd = provider.openFile(uri, "r");
        assertNotNull(pfd);
        pfd.close();
        try {
            provider.openFile(uri, "w");
            fail("分享文件必须只读");
        } catch (FileNotFoundException expected) {
        }
        f.delete();
    }

    public void testTraversalDenied() throws Exception {
        String auth = ShareProvider.authority(ctx);
        Uri evil = Uri.parse("content://" + auth
            + "/share/..%2F..%2Ffiles%2Fcollection.json");
        try {
            provider.openFile(evil, "r");
            fail("穿越必须被拒绝");
        } catch (FileNotFoundException expected) {
        }
        Uri deep = Uri.parse("content://" + auth + "/share/sub/../../x.png");
        try {
            provider.openFile(deep, "r");
            fail("多段路径必须被拒绝");
        } catch (FileNotFoundException expected) {
        }
        Uri privateFile = Uri.parse("content://" + auth + "/files/collection.json");
        try {
            provider.openFile(privateFile, "r");
            fail("未知段必须被拒绝");
        } catch (FileNotFoundException expected) {
        }
    }

    public void testCameraWriteNeedsPendingToken() throws Exception {
        String name = "cam_" + UUID.randomUUID().toString().replace("-", "")
            + ".jpg";
        File f = new File(cameraDir, name);
        assertTrue(f.createNewFile());
        Uri uri = ShareProvider.cameraUri(ctx, name);

        Settings.clearPendingCamera(ctx);
        try {
            provider.openFile(uri, "w");
            fail("无凭证相机写必须拒绝");
        } catch (FileNotFoundException expected) {
        }

        Settings.setPendingCamera(ctx, name);
        ParcelFileDescriptor pfd = provider.openFile(uri, "w");
        assertNotNull(pfd);
        pfd.close();
        Settings.clearPendingCamera(ctx);
        f.delete();
    }

    public void testCameraReadDoesNotLeakArbitrary() throws Exception {
        String name = "cam_missing_" + UUID.randomUUID().toString()
            .replace("-", "") + ".jpg";
        Uri uri = ShareProvider.cameraUri(ctx, name);
        try {
            provider.openFile(uri, "r");
            fail("不存在的文件不可读");
        } catch (FileNotFoundException expected) {
        }
    }

    public void testQueryReportsDisplayNameAndSize() throws Exception {
        File f = makeShareFile();
        Uri uri = ShareProvider.shareUri(ctx, f.getName());
        Cursor c = provider.query(uri,
            new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
            null, null, null);
        assertNotNull(c);
        assertTrue(c.moveToFirst());
        assertEquals(f.getName(), c.getString(0));
        assertEquals(3L, c.getLong(1));
        c.close();
        f.delete();
    }

    public void testGetType() {
        Uri png = ShareProvider.shareUri(ctx, "a.png");
        Uri jpg = ShareProvider.shareUri(ctx, "b.jpg");
        assertEquals("image/png", provider.getType(png));
        assertEquals("image/jpeg", provider.getType(jpg));
    }
}
