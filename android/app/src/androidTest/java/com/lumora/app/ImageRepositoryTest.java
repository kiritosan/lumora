package com.lumora.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.media.ExifInterface;
import android.test.InstrumentationTestCase;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONObject;

/** 图片导入/备份事务：隔离目录；只喂自己生成的图，不读用户文件。 */
public final class ImageRepositoryTest extends InstrumentationTestCase {

    private File base;
    private ImageRepository repo;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        base = new File(getInstrumentation().getTargetContext().getCacheDir(),
            "repo-" + UUID.randomUUID());
        repo = new ImageRepository(new File(base, "photos"), new File(base, "cache"));
    }

    @Override
    protected void tearDown() throws Exception {
        CollectionStoreTest.deleteTree(base);
        super.tearDown();
    }

    // ---- 造图工具 ----

    private static byte[] pngBytes(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.rgb(0xAF, 0x74, 0x49));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, out);
        b.recycle();
        return out.toByteArray();
    }

    /** 左半红右半蓝的非对称图，EXIF 断言用。 */
    private static Bitmap twoToneBitmap(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawRect(0, 0, w / 2f, h, redPaint());
        c.drawRect(w / 2f, 0, w, h, bluePaint());
        return b;
    }

    private static android.graphics.Paint redPaint() {
        android.graphics.Paint p = new android.graphics.Paint();
        p.setColor(Color.RED);
        return p;
    }

    private static android.graphics.Paint bluePaint() {
        android.graphics.Paint p = new android.graphics.Paint();
        p.setColor(Color.BLUE);
        return p;
    }

    /** 生成带指定 EXIF 朝向的 JPEG 字节（临时文件落在隔离目录）。 */
    private byte[] jpegWithOrientation(Bitmap src, int orientation)
        throws Exception {
        File tmp = File.createTempFile("exif", ".jpg", base);
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            src.compress(Bitmap.CompressFormat.JPEG, 95, fos);
        }
        ExifInterface exif = new ExifInterface(tmp.getAbsolutePath());
        exif.setAttribute(ExifInterface.TAG_ORIENTATION,
            String.valueOf(orientation));
        exif.saveAttributes();
        return CollectionStore.readCapped(tmp, ImageRepository.MAX_INPUT_BYTES);
    }

    private static JSONObject backupJson(Item it, byte[] imageBytes)
        throws Exception {
        JSONObject o = it.toJson();
        o.remove("photo");
        if (imageBytes != null) {
            o.put("imageBase64",
                Base64.encodeToString(imageBytes, Base64.NO_WRAP));
        }
        JSONObject root = new JSONObject();
        root.put("app", "lumora-android");
        root.put("version", 1);
        root.put("items", new JSONArray().put(o));
        return root;
    }

    /** 与 MainActivity 同序的恢复事务步骤。 */
    private static int[] runRestore(ImageRepository r, CollectionStore s,
                                    byte[] backupBytes) throws Exception {
        ImageRepository.RestorePlan plan = r.prepareRestore(backupBytes);
        List<Item> fresh = s.filterFresh(plan.items);
        if (fresh.size() < plan.items.size()) {
            Set<String> keep = new HashSet<>();
            for (Item it : fresh) keep.add(it.id);
            plan.retainItems(keep);
        }
        r.stagePhotos(plan);
        r.installStaged(plan);
        try {
            return s.mergeFromBackup(plan.items);
        } catch (CollectionStore.StoreException e) {
            r.discardAttempt(plan);
            throw e;
        }
    }

    private static int photoFileCount(ImageRepository r) {
        File[] fs = r.photosDir().listFiles();
        return fs == null ? 0 : fs.length;
    }

    // ---- 导入 ----

    public void testImportDecodesAndBounds() throws Exception {
        String name = repo.importPhoto(new ByteArrayInputStream(pngBytes(2400, 1200)));
        assertNotNull(name);
        assertTrue(name.endsWith(".jpg"));
        File f = repo.photoFile(name);
        assertNotNull(f);
        assertTrue(f.exists());
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        assertTrue("长边应被采样到上限内", Math.max(o.outWidth, o.outHeight)
            <= ImageRepository.MAX_DIM);
    }

    public void testImportRejectsInvalidBytes() {
        try {
            repo.importPhoto(new ByteArrayInputStream(
                "definitely not an image".getBytes(StandardCharsets.UTF_8)));
            fail("非法图片内容必须报错");
        } catch (ImageRepository.ImportException expected) {
        }
    }

    public void testImportRejectsOversizeStream() {
        byte[] huge = new byte[(int) ImageRepository.MAX_INPUT_BYTES + 8];
        try {
            repo.importPhoto(new ByteArrayInputStream(huge));
            fail("超限输入必须报错");
        } catch (ImageRepository.ImportException expected) {
        }
    }

    public void testImportRejectsOversizeDirectBytes() {
        byte[] huge = new byte[(int) ImageRepository.MAX_INPUT_BYTES + 8];
        try {
            repo.importPhotoBytes(huge);
            fail("直传字节同样受上限约束");
        } catch (ImageRepository.ImportException expected) {
        }
    }

    public void testImportRejectsTruncatedPng() throws Exception {
        byte[] full = pngBytes(200, 200);
        byte[] truncated = Arrays.copyOf(full, 32); // 只剩头部签名+IHDR 残片
        try {
            repo.importPhotoBytes(truncated);
            fail("截断的图片必须被拒绝");
        } catch (ImageRepository.ImportException expected) {
        }
        assertEquals("失败不得留下文件", 0, photoFileCount(repo));
    }

    public void testExifRotate90Applied() throws Exception {
        Bitmap src = twoToneBitmap(60, 30);
        byte[] jpeg = jpegWithOrientation(src,
            ExifInterface.ORIENTATION_ROTATE_90);
        src.recycle();
        String name = repo.importPhotoBytes(jpeg);
        Bitmap got = repo.loadPhoto(name, 1600);
        assertNotNull(got);
        // 旋转 90° 后宽高互换（60x30 → 30x60）
        assertTrue("EXIF 旋转应改变宽高比", got.getHeight() > got.getWidth());
        got.recycle();
    }

    public void testExifMirrorApplied() throws Exception {
        Bitmap src = twoToneBitmap(80, 40);
        byte[] jpeg = jpegWithOrientation(src,
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL);
        src.recycle();
        String name = repo.importPhotoBytes(jpeg);
        Bitmap got = repo.loadPhoto(name, 1600);
        assertNotNull(got);
        int left = got.getPixel(2, got.getHeight() / 2);
        assertTrue("镜像后左缘应呈蓝色系（原图左红右蓝）",
            Color.blue(left) > Color.red(left));
        got.recycle();
    }

    // ---- 备份导出 ----

    public void testExportFailsOnMissingPhoto() throws Exception {
        Item ghost = Item.newPhoto("幽灵", "", Item.MATERIAL_GOLD, "ghost.jpg");
        try {
            repo.buildBackup(java.util.Collections.singletonList(ghost));
            fail("照片缺失必须报错，不得产出坏备份");
        } catch (ImageRepository.ImportException expected) {
        }
    }

    public void testExportFailsOverItemLimit() throws Exception {
        List<Item> many = new java.util.ArrayList<>();
        for (int i = 0; i < ImageRepository.MAX_BACKUP_ITEMS + 1; i++) {
            many.add(Item.newProcedural("t" + i, "", Item.MATERIAL_GOLD, 0));
        }
        try {
            repo.buildBackup(many);
            fail("超过条数上限必须报错");
        } catch (ImageRepository.ImportException expected) {
        }
    }

    public void testExportSelfCompatible() throws Exception {
        String photo = repo.importPhotoBytes(pngBytes(300, 200));
        Item it = Item.newPhoto("往返票", "", Item.MATERIAL_GOLD, photo);
        byte[] bytes = repo.buildBackup(
            java.util.Collections.singletonList(it))
            .toString().getBytes(StandardCharsets.UTF_8);
        // 自家导出必须能被自家恢复预检接受
        ImageRepository.RestorePlan plan = repo.prepareRestore(bytes);
        assertEquals(1, plan.items.size());
        assertEquals(1, plan.staged.size());
    }

    // ---- 备份恢复 ----

    public void testBackupRoundtrip() throws Exception {
        String photo = repo.importPhotoBytes(pngBytes(400, 300));
        CollectionStore store = new CollectionStore(new File(base, "s1.json"));
        store.load();
        Item it = Item.newPhoto("海边的午后", "风很大。", Item.MATERIAL_SEA, photo);
        store.upsert(it);

        byte[] bytes = repo.buildBackup(store.all())
            .toString().getBytes(StandardCharsets.UTF_8);

        File base2 = new File(base, "restore");
        ImageRepository repo2 = new ImageRepository(
            new File(base2, "photos"), new File(base2, "cache"));
        CollectionStore store2 = new CollectionStore(new File(base2, "s2.json"));
        store2.load();

        int[] res = runRestore(repo2, store2, bytes);
        assertEquals(5, res[0]); // 4 示例 + 1 照片藏品
        Item got = store2.find(it.id);
        assertNotNull(got);
        assertEquals("海边的午后", got.title);
        assertNotNull(got.photoName);
        assertFalse("恢复时照片名须重新生成", photo.equals(got.photoName));
        File copied = repo2.photoFile(got.photoName);
        assertNotNull(copied);
        assertTrue(copied.exists());
    }

    public void testInstallFailureLeavesNothingBehind() throws Exception {
        byte[] backup = backupJson(
            Item.newPhoto("占位", "", Item.MATERIAL_GOLD, "orig.jpg"),
            pngBytes(64, 64)).toString().getBytes(StandardCharsets.UTF_8);
        ImageRepository.RestorePlan plan = repo.prepareRestore(backup);
        repo.stagePhotos(plan);
        // 人为制造就位失败：最终名被目录占用
        File blocker = new File(repo.photosDir(), plan.staged.get(0).finalName);
        assertTrue(blocker.mkdirs());
        try {
            repo.installStaged(plan);
            fail("就位失败必须抛出而非吞掉");
        } catch (ImageRepository.ImportException expected) {
        }
        assertTrue(blocker.delete());
        File[] left = repo.photosDir().listFiles();
        assertEquals("失败回滚不得留暂存/就位文件", 0,
            left == null ? 0 : left.length);
    }

    public void testDuplicateIdRestoreLeavesNoOrphan() throws Exception {
        // 现有藏品 id 已在藏馆，备份里同 id 带另一张图 → 整条跳过，照片不留孤儿
        String existing = repo.importPhotoBytes(pngBytes(100, 100));
        CollectionStore store = new CollectionStore(new File(base, "s.json"));
        store.load();
        Item cur = Item.newPhoto("原件", "", Item.MATERIAL_GOLD, existing);
        store.upsert(cur);

        Item dup = cur.copy();
        byte[] backup = backupJson(dup, pngBytes(120, 80))
            .toString().getBytes(StandardCharsets.UTF_8);

        int before = photoFileCount(repo);
        ImageRepository.RestorePlan plan = repo.prepareRestore(backup);
        List<Item> fresh = store.filterFresh(plan.items);
        int preSkipped = plan.items.size() - fresh.size();
        Set<String> keep = new HashSet<>();
        for (Item it : fresh) keep.add(it.id);
        plan.retainItems(keep);
        assertEquals("重复项在预筛选计数为跳过", 1, preSkipped);
        assertTrue("重复 id 的暂存图应被丢弃", plan.staged.isEmpty());
        repo.stagePhotos(plan);
        repo.installStaged(plan);
        int[] res = store.mergeFromBackup(plan.items);
        assertEquals(0, res[0]);
        assertEquals("预筛后无可合并项，merge 内不再计跳过", 0, res[1]);
        assertEquals("不得出现孤儿照片", before, photoFileCount(repo));
        assertEquals("原记录照片不动", existing, store.find(cur.id).photoName);
    }

    public void testBackupRejectsWrongApp() throws Exception {
        JSONObject o = new JSONObject();
        o.put("app", "other-app");
        o.put("version", 1);
        o.put("items", new JSONArray());
        try {
            repo.prepareRestore(o.toString().getBytes(StandardCharsets.UTF_8));
            fail("异构备份必须拒绝");
        } catch (ImageRepository.ImportException expected) {
        }
    }

    public void testBackupRejectsWrongVersion() throws Exception {
        JSONObject o = new JSONObject();
        o.put("app", "lumora-android");
        o.put("version", 99);
        o.put("items", new JSONArray());
        try {
            repo.prepareRestore(o.toString().getBytes(StandardCharsets.UTF_8));
            fail("未知版本必须拒绝");
        } catch (ImageRepository.ImportException expected) {
        }
    }

    public void testBackupRejectsTooManyItems() throws Exception {
        JSONObject o = new JSONObject();
        o.put("app", "lumora-android");
        o.put("version", 1);
        JSONArray arr = new JSONArray();
        for (int i = 0; i < ImageRepository.MAX_BACKUP_ITEMS + 1; i++) {
            arr.put(Item.newProcedural("t" + i, "", 0, 0).toJson());
        }
        o.put("items", arr);
        try {
            repo.prepareRestore(o.toString().getBytes(StandardCharsets.UTF_8));
            fail("超限条数必须拒绝");
        } catch (ImageRepository.ImportException expected) {
        }
    }

    public void testBackupNeverTrustsPhotoPath() throws Exception {
        Item it = Item.newPhoto("恶意路径", "", 0, "normal-look.jpg");
        JSONObject o = it.toJson();
        o.put("photo", "../../sdcard/evil.jpg"); // 备份里的路径字段
        // 且不带 imageBase64 → 声称照片却无图 → 应非法
        JSONObject root = new JSONObject();
        root.put("app", "lumora-android");
        root.put("version", 1);
        root.put("items", new JSONArray().put(o));
        try {
            repo.prepareRestore(root.toString().getBytes(StandardCharsets.UTF_8));
            fail("无图的照片型记录必须拒绝");
        } catch (ImageRepository.ImportException expected) {
        }
    }

    public void testBackupWithImageRegeneratesName() throws Exception {
        Item it = Item.newPhoto("有图的", "", 0, "hostile/../../name.jpg");
        JSONObject o = it.toJson();
        o.put("photo", "../../evil.jpg");
        o.put("imageBase64",
            Base64.encodeToString(pngBytes(64, 64), Base64.NO_WRAP));
        JSONObject root = new JSONObject();
        root.put("app", "lumora-android");
        root.put("version", 1);
        root.put("items", new JSONArray().put(o));

        ImageRepository.RestorePlan plan = repo.prepareRestore(
            root.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals(1, plan.items.size());
        String fresh = plan.items.get(0).photoName;
        assertNotNull(fresh);
        assertTrue("文件名必须由本应用生成", fresh.matches("^[A-Za-z0-9_-]+\\.jpg$"));
        assertFalse(fresh.contains(".."));
        repo.discardAttempt(plan);
    }

    public void testBackupRejectsBrokenImage() throws Exception {
        Item it = Item.newPhoto("坏图", "", 0, "x.jpg");
        JSONObject root = backupJson(it,
            "junk".getBytes(StandardCharsets.UTF_8));
        try {
            repo.prepareRestore(root.toString().getBytes(StandardCharsets.UTF_8));
            fail("坏图片必须拒绝");
        } catch (ImageRepository.ImportException expected) {
        }
    }

    public void testSharePngIs1080x1440() throws Exception {
        Item it = Item.newProcedural("分享样张", "", Item.MATERIAL_GOLD, 0);
        File f = repo.writeSharePng(it, null);
        assertTrue(f.exists());
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        assertEquals(ArtworkRenderer.SHARE_W, o.outWidth);
        assertEquals(ArtworkRenderer.SHARE_H, o.outHeight);
    }

    /** 照片型藏品缺图时不许把「照片不可用」占位卡当成果分享出去。 */
    public void testSharePngRefusesMissingPhoto() throws Exception {
        Item it = Item.newPhoto("缺图藏品", "", Item.MATERIAL_GOLD, "ghost001.jpg");
        try {
            repo.writeSharePng(it, null);
            fail("照片缺失必须报错，不能分享占位图");
        } catch (java.io.IOException expected) {
        }
    }

    /** 源图尺寸硬上限：与备份恢复同一判定，巨图/超像素/极端长宽比都拒。 */
    public void testSourceBoundsCheck() {
        try {
            ImageRepository.checkSourceBounds(0, 100, "x");
            fail("无效尺寸必须拒绝");
        } catch (ImageRepository.ImportException expected) {
        }
        try {
            ImageRepository.checkSourceBounds(ImageRepository.MAX_SRC_DIM + 1,
                100, "x");
            fail("超边长必须拒绝");
        } catch (ImageRepository.ImportException expected) {
        }
        try {
            ImageRepository.checkSourceBounds(8000, 7000, "x");
            fail("超总像素必须拒绝");
        } catch (ImageRepository.ImportException expected) {
        }
        try {
            ImageRepository.checkSourceBounds(8000, 800, "x");
            fail("极端长宽比必须拒绝");
        } catch (ImageRepository.ImportException expected) {
        }
        try {
            ImageRepository.checkSourceBounds(1600, 1200, "x");
        } catch (ImageRepository.ImportException e) {
            fail("正常尺寸不应拒绝");
        }
    }
}
