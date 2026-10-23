package com.lumora.app;

import android.test.InstrumentationTestCase;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 元数据仓库：隔离目录，只碰临时文件，绝不触真实藏品。 */
public final class CollectionStoreTest extends InstrumentationTestCase {

    private File dir;
    private File file;
    private CollectionStore store;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        dir = new File(getInstrumentation().getTargetContext().getCacheDir(),
            "store-" + UUID.randomUUID());
        assertTrue(dir.mkdirs());
        file = new File(dir, "collection.json");
        store = new CollectionStore(file);
    }

    @Override
    protected void tearDown() throws Exception {
        deleteTree(dir);
        super.tearDown();
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private CollectionStore freshStore() {
        return new CollectionStore(file);
    }

    public void testFirstRunSeedsFourSamples() throws Exception {
        store.load();
        List<Item> all = store.all();
        assertEquals(4, all.size());
        for (Item it : all) {
            assertTrue("示例应带标记", it.sample);
            assertNull(it.validationError());
        }
    }

    public void testPersistedEmptyStaysEmpty() throws Exception {
        store.load();
        for (Item it : store.all()) store.delete(it.id);
        assertEquals(0, store.count());

        CollectionStore again = freshStore();
        again.load();
        assertEquals("空的用户收藏不得重播种", 0, again.count());
    }

    public void testFieldRoundtrip() throws Exception {
        store.load();
        Item it = Item.newProcedural("暮色合辑", "一段手记。\n第二行。", Item.MATERIAL_SEA, 3);
        it.favorite = true;
        store.upsert(it);

        CollectionStore again = freshStore();
        again.load();
        Item got = again.find(it.id);
        assertNotNull(got);
        assertEquals("暮色合辑", got.title);
        assertEquals("一段手记。\n第二行。", got.note);
        assertEquals(Item.MATERIAL_SEA, got.material);
        assertEquals(3, got.artworkSeed);
        assertTrue(got.favorite);
        assertFalse(got.sample);
    }

    public void testDraftCopyDoesNotMutateStore() throws Exception {
        store.load();
        Item it = store.all().get(0);
        Item draft = it.copy();
        draft.title = "改掉的名字";
        draft.favorite = !draft.favorite;
        // 未保存：store 内仍是原值
        Item persisted = store.find(it.id);
        assertEquals(it.title, persisted.title);
        assertEquals(it.favorite, persisted.favorite);
    }

    public void testCorruptStoreNotReseeded() throws Exception {
        store.load();
        assertEquals(4, store.count());
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write("{ corrupted json !!!".getBytes(StandardCharsets.UTF_8));
        }
        CollectionStore broken = freshStore();
        try {
            broken.load();
            fail("损坏档案必须报错");
        } catch (CollectionStore.StoreException expected) {
            // 非破坏式错误：文件原样保留
        }
        byte[] raw = CollectionStore.readCapped(file, 1 << 20);
        assertEquals("{ corrupted json !!!", new String(raw, StandardCharsets.UTF_8));
    }

    public void testUpsertRejectsInvalidItem() throws Exception {
        store.load();
        int before = store.count();
        Item bad = Item.newProcedural("", "x", Item.MATERIAL_GOLD, 0); // 空标题
        try {
            store.upsert(bad);
            fail("非法字段必须被拒绝");
        } catch (CollectionStore.StoreException expected) {
        }
        assertEquals(before, store.count());
    }

    public void testPhotoNameTraversalRejected() throws Exception {
        store.load();
        Item it = Item.newPhoto("x", "", Item.MATERIAL_GOLD, "../evil.jpg");
        try {
            store.upsert(it);
            fail("路径穿越文件名必须被拒绝");
        } catch (CollectionStore.StoreException expected) {
        }
    }

    public void testMergeSkipsDuplicateAndKeepsOriginal() throws Exception {
        store.load();
        Item original = store.all().get(0);
        Item incoming = original.copy();
        incoming.title = "备份里的另一个标题";

        Item fresh = Item.newProcedural("新来的", "", Item.MATERIAL_MIST, 1);
        List<Item> pack = new ArrayList<>();
        pack.add(incoming);
        pack.add(fresh);

        int[] res = store.mergeFromBackup(pack);
        assertEquals(1, res[0]); // added
        assertEquals(1, res[1]); // skipped
        assertEquals("重复 id 保留原记录", original.title, store.find(original.id).title);
        assertNotNull(store.find(fresh.id));
    }

    // ---- .bak 恢复与写保护 ----

    private static void write(File f, byte[] data) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        }
    }

    public void testBakRecoveredWhenBaseMissing() throws Exception {
        store.load();
        Item keep = store.all().get(0);
        byte[] valid = CollectionStore.readCapped(file, 1 << 20);
        assertTrue(file.delete());
        File bak = new File(file.getPath() + ".bak");
        write(bak, valid);

        CollectionStore again = freshStore();
        again.load();
        assertEquals("base 缺失时应回滚 .bak 而非重播种",
            keep.id, again.all().get(0).id);
        assertTrue(bak.getParentFile().exists());
    }

    public void testUnsupportedSchemaRejected() throws Exception {
        store.load();
        write(file, ("{\"app\":\"lumora-android\",\"storeVersion\":99,"
            + "\"items\":[]}").getBytes(StandardCharsets.UTF_8));
        CollectionStore broken = freshStore();
        try {
            broken.load();
            fail("未知 storeVersion 必须报错");
        } catch (CollectionStore.StoreException expected) {
        }
        byte[] raw = CollectionStore.readCapped(file, 1 << 20);
        assertTrue(new String(raw, StandardCharsets.UTF_8)
            .contains("\"storeVersion\":99")); // 原文件不动
    }

    public void testWrongAppRejected() throws Exception {
        write(file, ("{\"app\":\"other-app\",\"storeVersion\":1,"
            + "\"items\":[]}").getBytes(StandardCharsets.UTF_8));
        CollectionStore broken = freshStore();
        try {
            broken.load();
            fail("异构档案必须报错");
        } catch (CollectionStore.StoreException expected) {
        }
    }

    public void testDuplicateIdInFileRejected() throws Exception {
        Item a = Item.newProcedural("一", "", Item.MATERIAL_GOLD, 0);
        org.json.JSONArray arr = new org.json.JSONArray();
        arr.put(a.toJson());
        arr.put(a.toJson());
        org.json.JSONObject root = new org.json.JSONObject();
        root.put("app", "lumora-android");
        root.put("storeVersion", 1);
        root.put("items", arr);
        write(file, root.toString().getBytes(StandardCharsets.UTF_8));
        CollectionStore broken = freshStore();
        try {
            broken.load();
            fail("文件内重复 id 必须报错");
        } catch (CollectionStore.StoreException expected) {
        }
    }

    public void testWritesRefusedUntilLoaded() throws Exception {
        CollectionStore cold = freshStore(); // 未 load
        Item it = Item.newProcedural("x", "", Item.MATERIAL_GOLD, 0);
        try {
            cold.upsert(it);
            fail("未载入不得写");
        } catch (CollectionStore.StoreException expected) {
        }
        try {
            cold.delete(it.id);
            fail("未载入不得删");
        } catch (CollectionStore.StoreException expected) {
        }
        try {
            cold.mergeFromBackup(new ArrayList<Item>());
            fail("未载入不得合并");
        } catch (CollectionStore.StoreException expected) {
        }
        assertFalse(cold.isLoaded());
    }

    public void testCorruptThenWritesStillRefused() throws Exception {
        store.load();
        write(file, "{ bad".getBytes(StandardCharsets.UTF_8));
        CollectionStore broken = freshStore();
        try {
            broken.load();
            fail();
        } catch (CollectionStore.StoreException expected) {
        }
        try {
            broken.upsert(Item.newProcedural("x", "", Item.MATERIAL_GOLD, 0));
            fail("载入失败后写入必须继续被拒绝");
        } catch (CollectionStore.StoreException expected) {
        }
    }

    public void testFailedPersistLeavesMemoryUntouched() throws Exception {
        store.load();
        int before = store.count();
        Item it = Item.newProcedural("写不进去", "", Item.MATERIAL_GOLD, 0);
        assertTrue(dir.setWritable(false));
        try {
            store.upsert(it);
            fail("目录不可写时写入必须报错");
        } catch (CollectionStore.StoreException expected) {
        } finally {
            assertTrue(dir.setWritable(true));
        }
        assertEquals("落盘失败不得改内存", before, store.count());
        assertNull(store.find(it.id));
    }
}
