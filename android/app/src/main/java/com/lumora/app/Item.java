package com.lumora.app;

import java.util.UUID;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

/** 一件藏品：标题/手记/材质/画作种子或照片文件名/时间/心动标记。 */
public final class Item {

    public static final int MATERIAL_GOLD = 0; // 蜜金
    public static final int MATERIAL_SEA = 1;  // 海盐
    public static final int MATERIAL_MIST = 2; // 银雾
    public static final int MATERIAL_COUNT = 3;
    public static final int SEED_PHOTO = -1;
    public static final int SEED_COUNT = 4;

    public static final int MAX_TITLE = 60;
    public static final int MAX_NOTE = 500;

    private static final Pattern ID_RE = Pattern.compile("^[A-Za-z0-9_-]{8,72}$");
    private static final Pattern PHOTO_RE = Pattern.compile("^[A-Za-z0-9_-]{8,72}\\.jpg$");

    public String id;
    public String title;
    public String note;
    public int material;
    public int artworkSeed;   // 0..SEED_COUNT-1 程序化画作；SEED_PHOTO 表示照片
    public String photoName;  // 照片文件名（photos 私有目录内），非照片为 null
    public long created;      // epoch millis
    public boolean favorite;
    public boolean sample;    // 首次引导示例标记

    public Item() {
    }

    public static Item newProcedural(String title, String note, int material, int seed) {
        Item it = new Item();
        it.id = UUID.randomUUID().toString();
        it.title = title;
        it.note = note;
        it.material = material;
        it.artworkSeed = seed;
        it.photoName = null;
        it.created = System.currentTimeMillis();
        it.favorite = false;
        it.sample = false;
        return it;
    }

    public static Item newPhoto(String title, String note, int material, String photoName) {
        Item it = new Item();
        it.id = UUID.randomUUID().toString();
        it.title = title;
        it.note = note;
        it.material = material;
        it.artworkSeed = SEED_PHOTO;
        it.photoName = photoName;
        it.created = System.currentTimeMillis();
        it.favorite = false;
        it.sample = false;
        return it;
    }

    public boolean isPhoto() {
        return photoName != null;
    }

    /** 展签编号：由稳定 id 派生，不存数据库冗余字段。 */
    public String serial() {
        String flat = id.replace("-", "").toUpperCase(java.util.Locale.ROOT);
        return "LMR-" + flat.substring(0, Math.min(6, flat.length()));
    }

    public Item copy() {
        Item c = new Item();
        c.id = id;
        c.title = title;
        c.note = note;
        c.material = material;
        c.artworkSeed = artworkSeed;
        c.photoName = photoName;
        c.created = created;
        c.favorite = favorite;
        c.sample = sample;
        return c;
    }

    /** 严格字段校验；返回错误描述，合法返回 null。 */
    public String validationError() {
        if (id == null || !ID_RE.matcher(id).matches()) return "id 非法";
        if (title == null || title.trim().isEmpty()) return "标题必填";
        if (title.length() > MAX_TITLE) return "标题过长";
        if (note != null && note.length() > MAX_NOTE) return "手记过长";
        if (material < 0 || material >= MATERIAL_COUNT) return "材质非法";
        if (created <= 0) return "created 非法";
        if (photoName != null) {
            if (artworkSeed != SEED_PHOTO) return "照片藏品 seed 必须为 -1";
            if (!PHOTO_RE.matcher(photoName).matches()) return "photoName 非法";
        } else if (artworkSeed < 0 || artworkSeed >= SEED_COUNT) {
            return "artworkSeed 非法";
        }
        return null;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("title", title);
        o.put("note", note == null ? "" : note);
        o.put("material", material);
        o.put("artworkSeed", artworkSeed);
        if (photoName != null) o.put("photo", photoName);
        o.put("created", created);
        o.put("favorite", favorite);
        if (sample) o.put("sample", true);
        return o;
    }

    /** 仅解析字段；合法性由 {@link #validationError()} / store 统一把关。 */
    public static Item fromJson(JSONObject o) throws JSONException {
        Item it = new Item();
        it.id = o.getString("id");
        it.title = o.optString("title", "");
        it.note = o.optString("note", "");
        it.material = o.optInt("material", -1);
        it.artworkSeed = o.optInt("artworkSeed", SEED_PHOTO);
        it.photoName = o.has("photo") ? o.getString("photo") : null;
        it.created = o.optLong("created", 0);
        it.favorite = o.optBoolean("favorite", false);
        it.sample = o.optBoolean("sample", false);
        return it;
    }
}
