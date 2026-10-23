package com.lumora.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.util.Base64;
import android.util.LruCache;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 图片与备份仓库：照片导入（限流解码 + EXIF 归正 → 私有 JPEG）、
 * 分享 PNG 落盘、相机临时文件、SAF 备份导出与"先校验后合并"的恢复。
 * 恢复事务：全量校验 → 去重 → 暂存 → 图片就位（任一失败回滚本次文件）→
 * 原子合并元数据（失败回滚本次文件），绝不触碰既有记录与其照片。
 */
public final class ImageRepository {

    public static final class ImportException extends Exception {
        public ImportException(String msg) {
            super(msg);
        }

        public ImportException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    public static final long MAX_INPUT_BYTES = 20L * 1024 * 1024;
    public static final long MAX_BACKUP_BYTES = 32L * 1024 * 1024;
    public static final int MAX_BACKUP_ITEMS = 100;
    public static final int MAX_DIM = 1600;
    public static final int MAX_SRC_DIM = 9000;
    public static final long MAX_SRC_PIXELS = 48_000_000L;
    public static final float MAX_ASPECT = 8f;
    public static final int JPEG_QUALITY = 90;
    public static final String BACKUP_APP = "lumora-android";
    public static final int BACKUP_VERSION = 1;

    private static final Pattern SAFE_NAME =
        Pattern.compile("^[A-Za-z0-9_-]{1,72}\\.(jpg|png)$");
    private static final String STAGED_PREFIX = "stg_";

    private final File photosDir;
    private final File shareDir;
    private final File cameraDir;
    private final LruCache<String, Bitmap> thumbs =
        new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8)) {
            @Override
            protected int sizeOf(String k, Bitmap v) {
                return v.getByteCount();
            }
        };

    public ImageRepository(Context ctx) {
        this(new File(ctx.getFilesDir(), "photos"), ctx.getCacheDir());
    }

    public ImageRepository(File photosDir, File cacheDir) {
        this.photosDir = photosDir;
        this.shareDir = new File(cacheDir, "share");
        this.cameraDir = new File(cacheDir, "camera");
        photosDir.mkdirs();
        shareDir.mkdirs();
        cameraDir.mkdirs();
        // 刻意不在构造时清理暂存文件：别的组件（Widget/新 Activity）
        // 随时可能新建本仓库实例，不能删掉进行中的恢复事务。
    }

    public File photosDir() {
        return photosDir;
    }

    // ---- 照片导入 ----

    /** 解码外部输入流为私有 JPEG，返回文件名；失败抛 ImportException。 */
    public String importPhoto(InputStream in) throws ImportException {
        byte[] data;
        try {
            data = CollectionStore.readCapped(in, MAX_INPUT_BYTES);
        } catch (IOException e) {
            throw new ImportException("图片读取失败或超过 20MB 上限", e);
        }
        return importPhotoBytes(data);
    }

    public String importPhotoBytes(byte[] data) throws ImportException {
        if (data == null || data.length == 0) {
            throw new ImportException("图片为空");
        }
        if (data.length > MAX_INPUT_BYTES) {
            throw new ImportException("图片超过 20MB 上限");
        }
        // 与备份恢复同一套源图上限：畸形/巨尺寸先拒再解码
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        checkSourceBounds(bounds.outWidth, bounds.outHeight, "图片");
        Bitmap bmp = null;
        Bitmap oriented = null;
        try {
            bmp = decodeBounded(data, MAX_DIM);
            if (bmp == null) throw new ImportException("无法识别的图片内容");
            oriented = applyExif(bmp, data);
            String name = UUID.randomUUID().toString().replace("-", "") + ".jpg";
            File out = photoFile(name);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                if (!oriented.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)) {
                    out.delete();
                    throw new ImportException("图片写入失败");
                }
            } catch (IOException e) {
                out.delete();
                throw new ImportException("图片写入失败", e);
            }
            return name;
        } catch (OutOfMemoryError e) {
            throw new ImportException("图片过大，设备内存不足", e);
        } finally {
            if (oriented != null && oriented != bmp) oriented.recycle();
            if (bmp != null) bmp.recycle();
        }
    }

    public File photoFile(String name) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) return null;
        return new File(photosDir, name);
    }

    /** 仅查缓存不解码；UI 线程用它拿缩略图，miss 交给后台批量解码。 */
    public Bitmap peekThumb(String name, int maxDim) {
        Bitmap hit = thumbs.get(name + "@" + maxDim);
        return (hit != null && !hit.isRecycled()) ? hit : null;
    }

    /** 解码缩略/展示位图（带 LruCache）。可能耗时，勿在 UI 线程调未命中项。 */
    public Bitmap loadPhoto(String name, int maxDim) {
        String key = name + "@" + maxDim;
        Bitmap hit = thumbs.get(key);
        if (hit != null && !hit.isRecycled()) return hit;
        File f = photoFile(name);
        if (f == null || !f.exists()) return null;
        Bitmap bmp = decodeFileBounded(f, maxDim);
        if (bmp != null) thumbs.put(key, bmp);
        return bmp;
    }

    public boolean deletePhoto(String name) {
        File f = photoFile(name);
        for (int dim : new int[]{384, 512, 640, 800, 1200, 1600}) {
            thumbs.remove(name + "@" + dim);
        }
        return f != null && (!f.exists() || f.delete());
    }

    // ---- 相机临时文件 ----

    /** 供 ACTION_IMAGE_CAPTURE 回写：先建空文件，名字交 ShareProvider 鉴权。 */
    public File createCameraFile() throws IOException {
        String name = "cam_" + UUID.randomUUID().toString().replace("-", "") + ".jpg";
        File f = new File(cameraDir, name);
        if (!f.createNewFile()) throw new IOException("相机临时文件创建失败");
        return f;
    }

    public File cameraFile(String name) {
        return resolveInside(cameraDir, name);
    }

    public File shareFile(String name) {
        return resolveInside(shareDir, name);
    }

    private static File resolveInside(File dir, String name) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) return null;
        try {
            File f = new File(dir, name);
            String base = dir.getCanonicalPath();
            String full = f.getCanonicalPath();
            if (!full.startsWith(base + File.separator)) return null;
            return f;
        } catch (IOException e) {
            return null;
        }
    }

    public void deleteCameraFile(String name) {
        File f = cameraFile(name);
        if (f != null && f.exists()) f.delete();
    }

    // ---- 分享 PNG ----

    /** 生成分享卡 PNG 到 share 目录，返回文件。 */
    public File writeSharePng(Item item, Bitmap photo) throws IOException {
        return writeSharePng(item, photo, ShareCardRenderer.GALLERY, false);
    }

    public File writeSharePng(Item item, Bitmap photo, int style, boolean note) throws IOException {
        if (item == null) {
            throw new IOException("缺少藏品，无法生成分享图");
        }
        // 照片型藏品缺图时不分享「占位图伪装成品」：直接报错
        if (item.isPhoto() && (photo == null || photo.isRecycled())) {
            throw new IOException("照片不可用，无法生成分享图");
        }
        final Bitmap bmp;
        try {
            bmp = ShareCardRenderer.render(item, photo, style, note, ShareCardRenderer.WIDTH);
        } catch (IllegalArgumentException | OutOfMemoryError e) {
            throw new IOException("分享图渲染失败", e);
        }
        if (bmp == null) {
            throw new IOException("分享图渲染失败");
        }
        String name = "lumora-" + UUID.randomUUID().toString().replace("-", "") + ".png";
        File out = new File(shareDir, name);
        try {
            try (FileOutputStream fos = new FileOutputStream(out)) {
                if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                    throw new IOException("分享图编码失败");
                }
            }
        } catch (IOException e) {
            out.delete();
            throw e;
        } finally {
            bmp.recycle();
        }
        return out;
    }

    // ---- 备份导出 ----

    /**
     * 备份 JSON：{app,version,items:[…, imageBase64?]}。
     * 与恢复同口径：条目 ≤100、序列化 ≤32MB；照片缺失/不可读直接报错，
     * 绝不产出"看起来成功但恢复不了"的文件。
     */
    public JSONObject buildBackup(List<Item> items) throws ImportException {
        if (items.size() > MAX_BACKUP_ITEMS) {
            throw new ImportException("藏品数超过 " + MAX_BACKUP_ITEMS
                + "，超出备份上限，暂不支持分段导出");
        }
        try {
            JSONObject root = new JSONObject();
            root.put("app", BACKUP_APP);
            root.put("version", BACKUP_VERSION);
            JSONArray arr = new JSONArray();
            for (Item it : items) {
                JSONObject o = it.toJson();
                o.remove("photo"); // 照片路径不外出
                if (it.isPhoto()) {
                    File f = photoFile(it.photoName);
                    if (f == null || !f.exists() || !f.isFile()) {
                        throw new ImportException("藏品「" + it.title
                            + "」的照片文件缺失，备份中止（数据未受影响）");
                    }
                    if (f.length() > MAX_BACKUP_BYTES) {
                        throw new ImportException("藏品「" + it.title
                            + "」的照片超过备份上限");
                    }
                    byte[] raw = CollectionStore.readCapped(f, MAX_BACKUP_BYTES);
                    o.put("imageBase64", Base64.encodeToString(raw, Base64.NO_WRAP));
                }
                arr.put(o);
            }
            root.put("items", arr);
            byte[] check = root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (check.length > MAX_BACKUP_BYTES) {
                throw new ImportException("备份体超过 32MB 上限");
            }
            return root;
        } catch (IOException | JSONException e) {
            throw new ImportException("备份序列化失败", e);
        }
    }

    // ---- 备份恢复（校验 → 暂存 → 就位 → 合并）----

    public static final class StagedPhoto {
        final String finalName;
        final byte[] bytes;      // 已重编码的私有 JPEG
        File tmpFile;
        File installedFile;

        StagedPhoto(String finalName, byte[] bytes) {
            this.finalName = finalName;
            this.bytes = bytes;
        }
    }

    public static final class RestorePlan {
        public final List<Item> items = new ArrayList<>();
        public final List<StagedPhoto> staged = new ArrayList<>();

        /** 元数据去重后调用：丢弃被跳过条目的暂存图，避免孤儿文件。 */
        public void retainItems(Set<String> keepIds) {
            if (keepIds.size() == items.size()) return;
            Set<String> keepPhotos = new HashSet<>();
            items.removeIf(it -> !keepIds.contains(it.id));
            for (Item it : items) {
                if (it.photoName != null) keepPhotos.add(it.photoName);
            }
            staged.removeIf(sp -> !keepPhotos.contains(sp.finalName));
        }
    }

    /**
     * 解析并全量校验备份。不信任备份内任何路径：photoName 一律由本仓库重生成；
     * 无 imageBase64 的照片型记录视为非法；图片必须能完整解码并会重编码为私有 JPEG。
     */
    public RestorePlan prepareRestore(byte[] data) throws ImportException {
        if (data == null || data.length == 0) throw new ImportException("备份为空");
        if (data.length > MAX_BACKUP_BYTES) throw new ImportException("备份超过 32MB 上限");
        final JSONObject root;
        final JSONArray arr;
        try {
            root = new JSONObject(new String(data, java.nio.charset.StandardCharsets.UTF_8));
            if (!BACKUP_APP.equals(root.optString("app"))) {
                throw new ImportException("不是拾光的备份文件");
            }
            if (root.optInt("version", -1) != BACKUP_VERSION) {
                throw new ImportException("备份版本不兼容");
            }
            arr = root.getJSONArray("items");
        } catch (JSONException e) {
            throw new ImportException("备份格式无法解析", e);
        }
        if (arr.length() > MAX_BACKUP_ITEMS) {
            throw new ImportException("备份条目超过 " + MAX_BACKUP_ITEMS + " 上限");
        }

        RestorePlan plan = new RestorePlan();
        Set<String> seenIds = new HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o;
            try {
                o = arr.getJSONObject(i);
            } catch (JSONException e) {
                throw new ImportException("第 " + (i + 1) + " 条记录不是对象");
            }
            String imageB64 = o.optString("imageBase64", "");
            o.remove("photo"); // 绝不采信备份里的路径
            Item it;
            try {
                it = Item.fromJson(o);
            } catch (JSONException e) {
                throw new ImportException("第 " + (i + 1) + " 条记录字段缺失");
            }
            if (!imageB64.isEmpty()) {
                byte[] img = decodeBase64Image(imageB64, i);
                byte[] normalized = normalizeToJpeg(img, i);
                String freshName =
                    UUID.randomUUID().toString().replace("-", "") + ".jpg";
                it.photoName = freshName;
                it.artworkSeed = Item.SEED_PHOTO;
                plan.staged.add(new StagedPhoto(freshName, normalized));
            }
            // 声称照片型却没有图 → validationError 拒绝
            String err = it.validationError();
            if (err != null) {
                throw new ImportException("第 " + (i + 1) + " 条记录非法：" + err);
            }
            if (!seenIds.add(it.id)) {
                throw new ImportException("备份含重复 id：" + it.serial());
            }
            it.sample = false; // 恢复的用户数据不标示例
            plan.items.add(it);
        }
        return plan;
    }

    private static byte[] decodeBase64Image(String b64, int index) throws ImportException {
        final byte[] img;
        try {
            img = Base64.decode(b64, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            throw new ImportException("第 " + (index + 1) + " 条图片编码损坏");
        }
        if (img.length == 0 || img.length > MAX_INPUT_BYTES) {
            throw new ImportException("第 " + (index + 1) + " 条图片超过大小上限");
        }
        return img;
    }

    /**
     * 备份内图片的完整校验 + 归一化：有界全解码（不是只看头部），
     * EXIF 归正，重编码为 ≤1600 私有 JPEG——截断/畸形/巨图一律拒绝。
     */
    static byte[] normalizeToJpeg(byte[] img, int index) throws ImportException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(img, 0, img.length, bounds);
        checkSourceBounds(bounds.outWidth, bounds.outHeight,
            "第 " + (index + 1) + " 条图片");
        Bitmap bmp = null;
        Bitmap oriented = null;
        try {
            bmp = decodeBounded(img, MAX_DIM);
            if (bmp == null) {
                throw new ImportException("第 " + (index + 1) + " 条图片无法完整解码");
            }
            oriented = applyExif(bmp, img);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!oriented.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                throw new ImportException("第 " + (index + 1) + " 条图片转存失败");
            }
            return out.toByteArray();
        } catch (OutOfMemoryError e) {
            throw new ImportException("第 " + (index + 1) + " 条图片过大", e);
        } finally {
            if (oriented != null && oriented != bmp) oriented.recycle();
            if (bmp != null) bmp.recycle();
        }
    }

    /** 源图尺寸硬上限：导入与备份恢复共用同一判定。 */
    static void checkSourceBounds(int w, int h, String prefix)
        throws ImportException {
        if (w <= 0 || h <= 0) {
            throw new ImportException(prefix + "内容无效");
        }
        if (w > MAX_SRC_DIM || h > MAX_SRC_DIM
            || (long) w * h > MAX_SRC_PIXELS
            || w > h * MAX_ASPECT || h > w * MAX_ASPECT) {
            throw new ImportException(prefix + "尺寸超出合理范围");
        }
    }

    /** 暂存所有照片为 stg_ 临时文件；任一个失败则清掉本次已暂存文件。 */
    public void stagePhotos(RestorePlan plan) throws ImportException {
        try {
            for (StagedPhoto sp : plan.staged) {
                File tmp = new File(photosDir, STAGED_PREFIX + sp.finalName);
                try (FileOutputStream fos = new FileOutputStream(tmp)) {
                    fos.write(sp.bytes);
                }
                sp.tmpFile = tmp;
            }
        } catch (IOException e) {
            discardAttempt(plan);
            throw new ImportException("备份图片暂存失败", e);
        }
    }

    /**
     * 图片就位：全部暂存文件改名为最终名（同目录 rename，错误必抛）。
     * 任一个失败 → 清掉本次已就位的文件并抛错，元数据绝不先行提交。
     */
    public void installStaged(RestorePlan plan) throws ImportException {
        for (StagedPhoto sp : plan.staged) {
            if (sp.tmpFile == null) {
                discardAttempt(plan);
                throw new ImportException("暂存文件缺失，恢复中止");
            }
            File target = new File(photosDir, sp.finalName);
            if (!sp.tmpFile.renameTo(target)) {
                discardAttempt(plan);
                throw new ImportException("备份图片就位失败（同目录改名被拒绝）");
            }
            sp.installedFile = target;
            sp.tmpFile = null;
        }
    }

    /**
     * 回滚本次恢复创建的一切文件：已就位的删除 + 剩余暂存删除。
     * 只动 plan 记录的名字（全是本事务生成的 UUID），绝不碰既有照片。
     */
    public void discardAttempt(RestorePlan plan) {
        for (StagedPhoto sp : plan.staged) {
            if (sp.tmpFile != null) sp.tmpFile.delete();
            if (sp.installedFile != null) sp.installedFile.delete();
            File strag = new File(photosDir, STAGED_PREFIX + sp.finalName);
            if (strag.exists()) strag.delete();
        }
    }

    // ---- 解码工具 ----

    /** 先读尺寸再按目标边长采样解码，超出上限的输入返回 null 不 OOM。 */
    public static Bitmap decodeBounded(byte[] data, int maxDim) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDim);
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            if (bmp == null) return null;
            return scaleDown(bmp, maxDim);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    private static Bitmap decodeFileBounded(File f, int maxDim) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDim);
            Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
            if (bmp == null) return null;
            Bitmap oriented = applyExifFile(bmp, f);
            return scaleDown(oriented, maxDim);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    static int sampleSize(int w, int h, int maxDim) {
        int s = 1;
        while (w / (s * 2) >= maxDim || h / (s * 2) >= maxDim) s *= 2;
        return s;
    }

    private static Bitmap scaleDown(Bitmap bmp, int maxDim) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        if (w <= maxDim && h <= maxDim) return bmp;
        float k = Math.min((float) maxDim / w, (float) maxDim / h);
        Bitmap out = Bitmap.createScaledBitmap(bmp,
            Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), true);
        if (out != bmp) bmp.recycle();
        return out;
    }

    private static Bitmap applyExif(Bitmap bmp, byte[] jpegData) {
        try {
            ExifInterface exif = new ExifInterface(new ByteArrayInputStream(jpegData));
            return rotate(bmp, exif);
        } catch (IOException e) {
            return bmp; // 非 JPEG / 无 EXIF：原样
        }
    }

    private static Bitmap applyExifFile(Bitmap bmp, File f) {
        try {
            ExifInterface exif = new ExifInterface(f.getAbsolutePath());
            return rotate(bmp, exif);
        } catch (IOException e) {
            return bmp;
        }
    }

    /** 全部 8 种 EXIF 朝向（含镜像）归一化为正立像素。 */
    private static Bitmap rotate(Bitmap bmp, ExifInterface exif) {
        int ori = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL);
        if (ori == ExifInterface.ORIENTATION_NORMAL
            || ori == ExifInterface.ORIENTATION_UNDEFINED) {
            return bmp;
        }
        Matrix m = new Matrix();
        switch (ori) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                m.setScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                m.setRotate(180f);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                m.setScale(1f, -1f);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                m.setRotate(90f);
                m.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                m.setRotate(90f);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                m.setRotate(270f);
                m.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                m.setRotate(270f);
                break;
            default:
                return bmp;
        }
        try {
            Bitmap out = Bitmap.createBitmap(bmp, 0, 0,
                bmp.getWidth(), bmp.getHeight(), m, true);
            if (out != bmp) bmp.recycle();
            return out;
        } catch (OutOfMemoryError e) {
            return bmp;
        }
    }
}
