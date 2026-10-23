package com.lumora.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 进程级共享状态：唯一 store / repo / IO 线程池 / busy 旗标 / 数据变更监听 /
 * 未入藏的照片草稿。Activity 旋转重建不另起 store、不杀共享线程；
 * Widget 与 Activity 读写同一份内存态，后台完成的变更经监听回到当前前台页。
 */
public final class AppState {

    public interface DataListener {
        void onDataChanged();
    }

    /** 新建照片草稿：导入完成但宿主已被销毁时暂挂，下一个 Activity 领养。 */
    public static final class PendingDraft {
        public String photoName;
        public String title = "";
        public String note = "";
        public int material = Item.MATERIAL_GOLD;
    }

    private static AppState instance;

    public static synchronized AppState get(Context c) {
        if (instance == null) {
            instance = new AppState(c.getApplicationContext());
        }
        return instance;
    }

    private final Context app;
    private final CollectionStore store;
    private final ImageRepository repo;
    private final ExecutorService io;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<DataListener> listeners = new ArrayList<>();

    private volatile boolean loadStarted = false;
    private volatile boolean storeReady = false;
    private volatile String storeError = null;
    private boolean busy = false;
    private PendingDraft pendingDraft;

    private AppState(Context app) {
        this.app = app;
        this.repo = new ImageRepository(app);
        this.store = new CollectionStore(new File(app.getFilesDir(), "collection.json"));
        this.io = Executors.newSingleThreadExecutor();
    }

    public Context app() {
        return app;
    }

    public CollectionStore store() {
        return store;
    }

    public ImageRepository repo() {
        return repo;
    }

    public ExecutorService io() {
        return io;
    }

    public Handler main() {
        return main;
    }

    public boolean storeReady() {
        return storeReady;
    }

    public String storeError() {
        return storeError;
    }

    /** 只触发一次后台载入；之后所有组件共享同一份内存态。 */
    public synchronized void ensureLoaded() {
        if (loadStarted) return;
        loadStarted = true;
        io.execute(() -> {
            try {
                store.load();
                storeReady = true;
            } catch (CollectionStore.StoreException e) {
                storeError = "藏品档案读取失败，已保留原始数据。可尝试从备份恢复，或联系记录者。";
            }
            notifyDataChanged();
        });
    }

    /** 全局互斥：导入/导出/保存/删除/恢复共用，旋转重建也不丢。 */
    public synchronized boolean tryBusy() {
        if (busy) return false;
        busy = true;
        return true;
    }

    public synchronized void releaseBusy() {
        busy = false;
    }

    public synchronized boolean isBusy() {
        return busy;
    }

    // ---- 数据监听（仅主线程回调）----

    public synchronized void register(DataListener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public synchronized void unregister(DataListener l) {
        listeners.remove(l);
    }

    /** 数据变更：桌面小组件刷新 + 当前前台页刷新。可在任意线程调用。 */
    public void notifyDataChanged() {
        List<DataListener> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(listeners);
        }
        for (DataListener l : snapshot) {
            main.post(() -> {
                synchronized (AppState.this) {
                    if (!listeners.contains(l)) return; // 快照后已注销：不投递
                }
                l.onDataChanged();
            });
        }
        LumoraWidget.notifyChanged(app);
    }

    // ---- 未入藏草稿暂挂 ----

    public synchronized void setPendingDraft(String photoName) {
        PendingDraft d = new PendingDraft();
        d.photoName = photoName;
        pendingDraft = d;
    }

    public synchronized PendingDraft takePendingDraft() {
        PendingDraft d = pendingDraft;
        pendingDraft = null;
        return d;
    }

    public synchronized PendingDraft peekPendingDraft() {
        return pendingDraft;
    }

    public synchronized void clearPendingDraft() {
        pendingDraft = null;
    }

    /**
     * 删除照片前的最后防线：重新查库，若任何已入藏藏品仍引用该文件则不动它。
     * 覆盖「草稿取消」与「迟到的保存提交」在旋转窗口交错的情形。
     * 纯静态便于隔离测试；调用方负责在 io 线程执行。
     */
    static void discardPhotoIfUnreferenced(CollectionStore store,
                                           ImageRepository repo, String name) {
        if (!store.isLoaded()) return; // 档案未就绪宁可不删
        for (Item it : store.all()) {
            if (it.isPhoto() && name.equals(it.photoName)) return;
        }
        repo.deletePhoto(name);
    }
}
