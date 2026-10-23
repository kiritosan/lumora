package com.lumora.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 受限文件出口：非导出、grantUriPermissions，仅放行两段式 URI。
 *   /share/<名>.png|jpg → cacheDir/share 下文件，只读（分享卡片）
 *   /camera/<名>.jpg    → cacheDir/camera 下文件，写模式仅限当前挂起的
 *                         相机回写文件名（Settings.pendingCamera）
 * 任何 ..、越界、未知段都拒绝；私有藏品与照片目录从不经此出口。
 */
public final class ShareProvider extends ContentProvider {

    private static final Pattern NAME_RE =
        Pattern.compile("^[A-Za-z0-9_-]{1,72}\\.(png|jpg)$");

    /** manifest 里 authorities=${applicationId}.fileprovider，与其保持一致。 */
    public static String authority(Context c) {
        return c.getPackageName() + ".fileprovider";
    }

    public static Uri shareUri(Context c, String fileName) {
        return new Uri.Builder().scheme("content").authority(authority(c))
            .appendPath("share").appendPath(fileName).build();
    }

    public static Uri cameraUri(Context c, String fileName) {
        return new Uri.Builder().scheme("content").authority(authority(c))
            .appendPath("camera").appendPath(fileName).build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File dirFor(String kind) {
        Context ctx = getContext();
        if (ctx == null) return null;
        File base;
        switch (kind) {
            case "share":
                base = new File(ctx.getCacheDir(), "share");
                break;
            case "camera":
                base = new File(ctx.getCacheDir(), "camera");
                break;
            default:
                return null;
        }
        base.mkdirs();
        return base;
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        List<String> seg = uri.getPathSegments();
        if (seg == null || seg.size() != 2) {
            throw new FileNotFoundException("bad path");
        }
        String kind = seg.get(0);
        String name = seg.get(1);
        if (!NAME_RE.matcher(name).matches() || name.contains("..")) {
            throw new FileNotFoundException("bad name");
        }
        File dir = dirFor(kind);
        if (dir == null) throw new FileNotFoundException("bad kind");
        File f = new File(dir, name);
        try {
            String base = dir.getCanonicalPath();
            if (!f.getCanonicalPath().startsWith(base + File.separator)) {
                throw new FileNotFoundException("traversal");
            }
        } catch (IOException e) {
            throw new FileNotFoundException("resolve failed");
        }
        return f;
    }

    private boolean isCamera(Uri uri) {
        List<String> seg = uri.getPathSegments();
        return seg != null && seg.size() == 2 && "camera".equals(seg.get(0));
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode)
        throws FileNotFoundException {
        File f = resolve(uri);
        int pmode = ParcelFileDescriptor.parseMode(mode);
        boolean write = (pmode & (ParcelFileDescriptor.MODE_WRITE_ONLY
            | ParcelFileDescriptor.MODE_READ_WRITE
            | ParcelFileDescriptor.MODE_TRUNCATE
            | ParcelFileDescriptor.MODE_APPEND)) != 0;

        if (isCamera(uri)) {
            Context ctx = getContext();
            String pending = ctx != null ? Settings.pendingCamera(ctx) : null;
            if (write) {
                // 写只放行当前挂起的相机文件名（拍照前由 MainActivity 预登记）
                if (pending == null || !pending.equals(f.getName())) {
                    throw new FileNotFoundException("camera write denied");
                }
            }
        } else {
            // 分享文件：只读，且必须已存在
            if (write) throw new FileNotFoundException("share is read-only");
            if (!f.exists() || !f.isFile()) {
                throw new FileNotFoundException("not found");
            }
        }
        return ParcelFileDescriptor.open(f, pmode);
    }

    @Override
    public String getType(Uri uri) {
        String last = uri.getLastPathSegment();
        if (last == null) return null;
        if (last.endsWith(".png")) return "image/png";
        if (last.endsWith(".jpg")) return "image/jpeg";
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File f;
        try {
            f = resolve(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        if (f.exists()) {
            c.addRow(new Object[]{f.getName(), f.length()});
        }
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        return 0;
    }
}
