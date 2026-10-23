package com.lumora.app;

import android.util.AtomicFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 藏品元数据仓库：应用私有目录内单个 JSON 文件，AtomicFile 原子写 + .bak 回滚读。
 * 规则：base/.bak/.new 全都不存在 → 播种 4 张示例卡；文件存在但损坏/版本不符 →
 * 抛错交给 UI 提示，绝不重播种、绝不覆盖原文件；内存列表只在写盘成功后替换；
 * 未成功 load 前拒绝一切写操作；写出的字节同样不得超过读取上限。
 */
public final class CollectionStore {

    public static final class StoreException extends Exception {
        public StoreException(String msg) {
            super(msg);
        }

        public StoreException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    private static final String STORE_APP = "lumora-android";
    private static final int STORE_VERSION = 1;
    private static final long MAX_STORE_BYTES = 8L * 1024 * 1024;

    private final AtomicFile file;
    private final List<Item> items = new ArrayList<>();
    private boolean loaded = false;

    public CollectionStore(File jsonFile) {
        this.file = new AtomicFile(jsonFile);
    }

    public synchronized boolean isLoaded() {
        return loaded;
    }

    public File baseFile() {
        return file.getBaseFile();
    }

    /**
     * 首次载入。三种结局：
     *  1) base/.bak/.new 全无 → 播种并落盘；
     *  2) 任一存在 → openRead（损坏 base 自动回滚 .bak），解析+全量校验；
     *  3) 解析/校验失败 → StoreException，items 保持空、文件不动。
     */
    public synchronized void load() throws StoreException {
        File base = file.getBaseFile();
        boolean hasRemnants = base.exists()
            || new File(base.getPath() + ".bak").exists()
            || new File(base.getPath() + ".new").exists();

        if (!hasRemnants) {
            List<Item> seeds = seedItems();
            persist(seeds);
            items.clear();
            items.addAll(seeds);
            loaded = true;
            return;
        }

        List<Item> parsed;
        try {
            byte[] data;
            try (FileInputStream in = file.openRead()) {
                data = readCapped(in, MAX_STORE_BYTES);
            } catch (FileNotFoundException e) {
                throw new StoreException("藏品档案缺失（残留文件仍在，未做覆盖）", e);
            }
            JSONObject root = new JSONObject(new String(data, StandardCharsets.UTF_8));
            if (!STORE_APP.equals(root.optString("app"))) {
                throw new StoreException("档案不是拾光数据（已保留原文件）");
            }
            if (root.optInt("storeVersion", -1) != STORE_VERSION) {
                throw new StoreException("档案版本不受支持（已保留原文件）");
            }
            JSONArray arr = root.getJSONArray("items");
            parsed = new ArrayList<>(arr.length());
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < arr.length(); i++) {
                Item it = Item.fromJson(arr.getJSONObject(i));
                String err = it.validationError();
                if (err != null) {
                    throw new StoreException("档案第 " + (i + 1) + " 条非法：" + err);
                }
                if (!seen.add(it.id)) {
                    throw new StoreException("档案含重复 id：" + it.serial());
                }
                parsed.add(it);
            }
        } catch (IOException e) {
            throw new StoreException("藏品档案读取失败（已保留原文件，未做覆盖）", e);
        } catch (JSONException e) {
            throw new StoreException("藏品档案解析失败（已保留原文件，未做覆盖）", e);
        }
        sortDesc(parsed);
        items.clear();
        items.addAll(parsed);
        loaded = true;
    }

    private void requireLoaded() throws StoreException {
        if (!loaded) {
            throw new StoreException("档案未成功载入，拒绝写入（保护原始数据）");
        }
    }

    /** 返回深拷贝列表，外部改草稿不污染存储态。 */
    public synchronized List<Item> all() {
        List<Item> out = new ArrayList<>(items.size());
        for (Item it : items) out.add(it.copy());
        return out;
    }

    public synchronized Item find(String id) {
        for (Item it : items) {
            if (it.id.equals(id)) return it.copy();
        }
        return null;
    }

    /**
     * 过滤备份输入：返回仅含新 id 的列表；重复项保留原记录不动。
     * 恢复方用它先挑出要真正导入的照片，避免无谓的暂存/孤儿文件。
     */
    public synchronized List<Item> filterFresh(List<Item> incoming) {
        List<Item> fresh = new ArrayList<>();
        Set<String> have = new HashSet<>();
        for (Item it : items) have.add(it.id);
        for (Item it : incoming) {
            if (!have.contains(it.id)) fresh.add(it.copy());
        }
        return fresh;
    }

    public synchronized void upsert(Item it) throws StoreException {
        requireLoaded();
        String err = it.validationError();
        if (err != null) throw new StoreException("非法藏品字段：" + err);
        List<Item> next = snapshot();
        boolean replaced = false;
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i).id.equals(it.id)) {
                next.set(i, it.copy());
                replaced = true;
                break;
            }
        }
        if (!replaced) next.add(it.copy());
        sortDesc(next);
        persist(next);              // 先落盘
        items.clear();              // 成功后再替换内存
        items.addAll(next);
    }

    public synchronized void delete(String id) throws StoreException {
        requireLoaded();
        List<Item> next = snapshot();
        boolean removed = next.removeIf(it -> it.id.equals(id));
        if (!removed) throw new StoreException("藏品不存在");
        persist(next);
        items.clear();
        items.addAll(next);
    }

    /**
     * 备份合并：仅追加，重复 id 保留原有记录。
     * @return int[]{added, skipped}
     */
    public synchronized int[] mergeFromBackup(List<Item> incoming) throws StoreException {
        requireLoaded();
        List<Item> next = snapshot();
        int added = 0;
        int skipped = 0;
        for (Item it : incoming) {
            String err = it.validationError();
            if (err != null) throw new StoreException("备份含非法字段：" + err);
            boolean dup = false;
            for (Item cur : next) {
                if (cur.id.equals(it.id)) {
                    dup = true;
                    break;
                }
            }
            if (dup) {
                skipped++;
            } else {
                next.add(it.copy());
                added++;
            }
        }
        sortDesc(next);
        persist(next);
        items.clear();
        items.addAll(next);
        return new int[]{added, skipped};
    }

    public synchronized int count() {
        return items.size();
    }

    // ---- 内部 ----

    private List<Item> snapshot() {
        List<Item> out = new ArrayList<>(items.size());
        for (Item it : items) out.add(it.copy());
        return out;
    }

    private void persist(List<Item> next) throws StoreException {
        try {
            JSONObject root = new JSONObject();
            root.put("app", STORE_APP);
            root.put("storeVersion", STORE_VERSION);
            JSONArray arr = new JSONArray();
            for (Item it : next) arr.put(it.toJson());
            root.put("items", arr);
            byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_STORE_BYTES) {
                throw new StoreException("档案超过大小上限，拒绝写入");
            }
            FileOutputStream fos = file.startWrite();
            try {
                fos.write(bytes);
                file.finishWrite(fos);
            } catch (IOException | RuntimeException e) {
                file.failWrite(fos);
                throw e;
            }
        } catch (IOException | JSONException | RuntimeException e) {
            throw new StoreException("藏品档案写入失败", e);
        }
    }

    private static void sortDesc(List<Item> list) {
        list.sort(Comparator.comparingLong((Item it) -> it.created).reversed());
    }

    static byte[] readCapped(File f, long cap) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            return readCapped(in, cap);
        }
    }

    static byte[] readCapped(InputStream in, long cap) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) >= 0) {
            total += n;
            if (total > cap) throw new IOException("输入超出大小上限");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static List<Item> seedItems() {
        List<Item> seeds = new ArrayList<>(4);
        long now = System.currentTimeMillis();
        long day = 24L * 3600 * 1000;
        String[] titles = {"示例 · 琥珀沙丘", "示例 · 青月水面", "示例 · 石间雾", "示例 · 朱色黄昏"};
        String[] notes = {
            "示例藏品：琥珀色的午后，沙丘背光处像一张旧相纸。",
            "示例藏品：月亮落进水里，风一吹就碎成青色的鳞片。",
            "示例藏品：石头在雾里坐了很久，久到忘了自己的棱角。",
            "示例藏品：朱砂色的黄昏，是白昼合上书页前的一枚印章。"
        };
        int[] materials = {Item.MATERIAL_GOLD, Item.MATERIAL_SEA, Item.MATERIAL_MIST, Item.MATERIAL_GOLD};
        for (int i = 0; i < 4; i++) {
            Item it = Item.newProcedural(titles[i], notes[i], materials[i], i);
            it.created = now - i * day;
            it.sample = true;
            seeds.add(it);
        }
        Collections.sort(seeds, Comparator.comparingLong((Item it) -> it.created).reversed());
        return seeds;
    }
}
