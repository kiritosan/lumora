package com.lumora.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 单 Activity 三页签：藏馆 / 光桌 / 我的；编辑与新建为覆盖页。
 * 数据、IO 线程、忙碌旗标、未入藏草稿全部托管给进程级 AppState——
 * 旋转重建不另起仓库快照，后台完成的任务经数据监听回到当前前台页。
 * 外部 Intent（分享/小组件）只消费一次，不随重建重放；
 * 编辑草稿打开期间拒绝新的导入/跳转，避免静默覆盖。
 */
public final class MainActivity extends Activity
    implements HomePanel.Host, StagePanel.Host, EditPanel.Host, ProfilePanel.Host {

    private static final int TAB_HOME = 0;
    private static final int TAB_TABLE = 1;
    private static final int TAB_MINE = 2;

    private static final int REQ_PICK = 41;
    private static final int REQ_CAPTURE = 42;
    private static final int REQ_BACKUP_OUT = 43;
    private static final int REQ_BACKUP_IN = 44;

    /** 已消费的 Intent 被打上此 action，重建后不再重放（包级可见供测试断言）。 */
    static final String ACTION_CONSUMED = "com.lumora.app.CONSUMED";

    private AppState app;
    private final AppState.DataListener dataListener = this::onDataChanged;
    private boolean resumed = false;

    private int currentTab = TAB_HOME;
    private boolean tableIsDetail = false;
    private int tableReturnTab = TAB_HOME;
    private String selectedId = null;
    private boolean editVisible = false;
    private boolean editIsNew = false;
    private String editItemId = null;
    private String stagedPhoto = null;
    private String pendingCameraName = null;
    private boolean savingDraft = false;      // 有在途草稿保存（重建后对账）
    private String savingDraftId = null;
    private Intent deferredIntent = null;
    private int editPhotoGen = 0;
    private String restoringEditId = null;
    private String restoringTitle = "";
    private String restoringNote = "";
    private int restoringMaterial = Item.MATERIAL_GOLD;

    private FrameLayout content;
    private FrameLayout overlay;
    private HomePanel home;
    private StagePanel stage;
    private ProfilePanel mine;
    private EditPanel editPanel;
    private final TextView[] navLabels = new TextView[3];
    private final View[] navBars = new View[3];
    private final View[] navCells = new View[3];

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        app = AppState.get(this);
        app.ensureLoaded();
        buildRoot();
        if (saved != null) {
            restoreSaved(saved);
        } else {
            refreshAll();
            switchTab(TAB_HOME);
            adoptPendingDraft();
            handleIntent(getIntent());
        }
    }

    // ---- 布局骨架 ----

    private void buildRoot() {
        LinearLayout root = Ui.vbox(this);
        root.setBackgroundColor(Ui.PAPER);

        FrameLayout body = new FrameLayout(this);
        content = new FrameLayout(this);
        body.addView(content, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Ui.PAPER);
        overlay.setVisibility(View.GONE);
        body.addView(overlay, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(body, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(Ui.CARD);
        nav.setShowDividers(LinearLayout.SHOW_DIVIDER_NONE);
        String[] names = {"藏馆", "光桌", "我的"};
        int[] ids = {R.id.nav_home, R.id.nav_table, R.id.nav_mine};
        for (int i = 0; i < 3; i++) {
            final int tab = i;
            LinearLayout cell = Ui.vbox(this);
            cell.setGravity(Gravity.CENTER);
            cell.setMinimumHeight(Ui.dp(this, 56));
            cell.setId(ids[i]);
            cell.setClickable(true);
            cell.setFocusable(true);
            View bar = new View(this);
            bar.setBackgroundColor(Ui.COPPER);
            LinearLayout.LayoutParams barLp =
                new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 3));
            bar.setVisibility(View.INVISIBLE);
            cell.addView(bar, barLp);
            TextView label = Ui.text(this, names[i], 14f, Ui.MUTED);
            label.setGravity(Gravity.CENTER);
            cell.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            cell.setOnClickListener(v -> {
                Ui.haptic(this, v);
                if (editVisible && app.isBusy()) {
                    Ui.toast(this, "正在保存，请稍候");
                    return; // 有在途写盘：不切页不丢草稿
                }
                if (editVisible) cancelEdit();
                tableIsDetail = false;
                switchTab(tab);
            });
            nav.addView(cell, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            navLabels[i] = label;
            navBars[i] = bar;
            navCells[i] = cell;
        }
        LinearLayout navWrap = Ui.vbox(this);
        View rule = new View(this);
        rule.setBackgroundColor(Ui.LINE);
        navWrap.addView(rule, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 0.75f))));
        navWrap.addView(nav, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 58)));
        root.addView(navWrap);

        setContentView(root);

        home = new HomePanel(this, this, app.repo(), app.io());
        stage = new StagePanel(this, this, app.repo(), app.io());
        mine = new ProfilePanel(this, this);
        editPanel = new EditPanel(this, this, app.repo());
    }

    private void switchTab(int tab) {
        currentTab = tab;
        for (int i = 0; i < 3; i++) {
            boolean on = i == tab;
            navLabels[i].setTextColor(on ? Ui.INK : Ui.MUTED);
            navLabels[i].setTypeface(on
                ? android.graphics.Typeface.DEFAULT_BOLD
                : android.graphics.Typeface.DEFAULT);
            navBars[i].setVisibility(on ? View.VISIBLE : View.INVISIBLE);
            if (navCells[i] != null) navCells[i].setSelected(on);
            Object parent = navLabels[i].getParent();
            if (parent instanceof View) {
                ((View) parent).setContentDescription(
                    tabName(i) + (on ? "页签，已选中" : "页签"));
            }
        }
        content.removeAllViews();
        View panel = panelFor(tab);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            Ui.contentWidth(this), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(panel, lp);
        applyTiltState();
    }

    private static String tabName(int i) {
        switch (i) {
            case TAB_TABLE: return "光桌";
            case TAB_MINE: return "我的";
            default: return "藏馆";
        }
    }

    private View panelFor(int tab) {
        switch (tab) {
            case TAB_TABLE: return stage;
            case TAB_MINE: return mine;
            default: return home;
        }
    }

    private void applyTiltState() {
        // 传感器只在「前台 + 光桌页 + 无编辑覆盖 + 画作正面」时注册
        if (resumed && currentTab == TAB_TABLE && !editVisible) {
            stage.onPanelShown();
        } else {
            stage.onPanelHidden();
        }
    }

    private void refreshAll() {
        if (app.storeReady()) {
            List<Item> items = app.store().all();
            home.setItems(items);
            stage.setItems(items, selectedId);
            mine.setStats(items);
        } else {
            String err = app.storeError();
            if (err != null) {
                home.setStoreError(err);
                stage.setStoreError(err);
            } else {
                home.setLoading(true);
            }
        }
    }

    /**
     * 数据变更回调（主线程）：刷新视图与 busy 态，
     * 再按序补处理：待恢复编辑（先于一切）→ 迟到的外部 intent → 暂挂导入草稿 →
     * 保存/删除对账。旧实例的监听已注销，销毁后不再被调用。
     */
    private void onDataChanged() {
        if (!resumed || isDestroyed() || isFinishing()) return;
        refreshAll();
        mine.setBusy(app.isBusy());
        if (!app.storeReady()) return;
        // 1) 恢复中的既有编辑优先：先把草稿摆回去，再考虑别的入口
        if (restoringEditId != null && !editVisible) {
            finishEditRestore();
        }
        // 2) 在途保存对账先于任何新入口：成功关页 / 失败保草稿
        reconcileDraftState();
        // 3) 迟到的外部 intent：编辑开着则按规则消费+拒绝
        if (deferredIntent != null) {
            Intent i = deferredIntent;
            deferredIntent = null;
            handleIntent(i);
        }
        // 4) 暂挂导入草稿（内部守卫：编辑页/待恢复编辑/在途保存都拒绝领养）
        adoptPendingDraft();
    }

    /**
     * 对账判据：只有已入藏记录与提交的草稿逐字段一致才算本次保存成功；
     * 同 id 但内容不符（既有编辑写盘失败）不能误判成功丢草稿。
     */
    static boolean sameDraft(Item committed, Item draft) {
        if (committed == null || draft == null) return false;
        if (!committed.id.equals(draft.id)) return false;
        if (committed.created != draft.created) return false;
        String t = draft.title == null ? "" : draft.title.trim();
        String n = draft.note == null ? "" : draft.note;
        if (!committed.title.equals(t)) return false;
        if (!committed.note.equals(n)) return false;
        if (committed.material != draft.material) return false;
        String cp = committed.photoName;
        String dp = draft.photoName;
        if (cp == null ? dp != null : !cp.equals(dp)) return false;
        return true;
    }

    /** 在途保存与删除结果对账（旋转/暂停期间完成的任务在此落账）。 */
    private void reconcileDraftState() {
        if (!app.storeReady()) return;
        if (savingDraft && savingDraftId != null && !app.isBusy()) {
            Item committed = app.store().find(savingDraftId);
            if (!editVisible) {
                // 编辑页未能恢复（兜底）：只清旗标，结果由列表呈现
                savingDraft = false;
                savingDraftId = null;
            } else if (committed != null
                && sameDraft(committed, editPanel.peekDraft())) {
                finishSaveSuccess(committed.id); // 照片已被引用，绝不删除
            } else {
                // 写盘未成功（失败或被打断）：解冻草稿，用户可重试
                savingDraft = false;
                savingDraftId = null;
                editPanel.setBusy(false);
                Ui.toast(this, "上次保存未成功，草稿已保留");
            }
        }
        // 冻结态跟随在途保存（含重建后恢复出的编辑页）
        if (editVisible) {
            editPanel.setBusy(savingDraft && app.isBusy());
        }
        // 编辑目标已在别处删除（例如旧实例完成的删除）：关闭残留编辑
        if (editVisible && !editIsNew && editItemId != null && !savingDraft
            && app.store().find(editItemId) == null) {
            cancelEditStateOnly();
        }
    }

    // ---- 后台任务封装：全局 busy + 完成通知 ----

    private interface IoTask {
        void run() throws Exception;
    }

    private interface Done {
        void run(String err);
    }

    private boolean ioBusy(IoTask task) {
        return ioBusy(task, null);
    }

    /**
     * 共享 busy + IO 任务。返回是否被接受（busy 占用中则拒绝）。
     * done 只在「本 Activity 仍存活」时执行：旋转销毁的旧实例不跑 UI 回调，
     * 数据变更则经 AppState 监听送达当前前台实例。
     */
    private boolean ioBusy(final IoTask task, final Done done) {
        if (!app.tryBusy()) {
            Ui.toast(this, "正在处理中，请稍候");
            return false;
        }
        mine.setBusy(true);
        app.io().execute(() -> {
            String err = null;
            try {
                task.run();
            } catch (Exception e) {
                err = e.getMessage() == null ? "操作失败" : e.getMessage();
            } finally {
                app.releaseBusy();
            }
            app.notifyDataChanged();
            final String fErr = err;
            app.main().post(() -> {
                if (isDestroyed()) return;      // 旧实例不跑 UI 回调
                mine.setBusy(false);
                if (done != null) {
                    done.run(fErr);
                } else if (fErr != null) {
                    Ui.toast(MainActivity.this, fErr);
                }
            });
        });
        return true;
    }

    // ---- Host 回调：藏馆 ----

    @Override
    public void onOpen(Item item) {
        selectedId = item.id;
        if (currentTab != TAB_TABLE) {
            tableReturnTab = currentTab;
            tableIsDetail = true;
        }
        if (app.storeReady()) stage.setItems(app.store().all(), item.id);
        stage.show(item.id);
        switchTab(TAB_TABLE);
    }

    @Override
    public void onOpenStage() {
        tableIsDetail = false;
        switchTab(TAB_TABLE);
    }

    @Override
    public void onRequestAdd() {
        if (app.isBusy() || editVisible) return;
        String[] actions = {"从相册拾取", "拍下这一刻"};
        new AlertDialog.Builder(this)
            .setTitle("藏入新照片")
            .setItems(actions, (d, which) -> {
                if (which == 0) pickFromGallery();
                else launchCamera();
            })
            .show();
    }

    private void pickFromGallery() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*");
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (ActivityNotFoundException | SecurityException e) {
            Ui.toast(this, "没有找到可选图片的应用");
        }
    }

    private void launchCamera() {
        final File tmp;
        try {
            tmp = app.repo().createCameraFile();
        } catch (Exception e) {
            Ui.toast(this, "无法准备相机暂存文件");
            return;
        }
        pendingCameraName = tmp.getName();
        Settings.setPendingCamera(this, pendingCameraName);
        Uri out = ShareProvider.cameraUri(this, pendingCameraName);
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, out)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        // OEM 兼容：ClipData 也带上授权 URI
        i.setClipData(ClipData.newRawUri("lumora-camera", out));
        try {
            startActivityForResult(i, REQ_CAPTURE);
        } catch (ActivityNotFoundException | SecurityException e) {
            pendingCameraName = null;
            Settings.clearPendingCamera(this);
            final String name = tmp.getName();
            app.io().execute(() -> app.repo().deleteCameraFile(name));
            Ui.toast(this, "没有可用的相机应用");
        }
    }

    // ---- Host 回调：光桌 ----

    @Override
    public void onSelectionChanged(String itemId) {
        selectedId = itemId;
    }

    @Override
    public void onEdit(Item item) {
        if (app.isBusy()) {
            Ui.toast(this, "正在处理中，请稍候");
            return;
        }
        editItemId = item.id;
        editIsNew = false;
        stagedPhoto = null;
        editPanel.openEdit(item, null);
        showOverlay();
        if (item.isPhoto()) {
            editPanel.setPhotoState(ArtworkRenderer.PHOTO_LOADING);
            loadEditPhoto(item.id, item.photoName);
        }
    }

    /** 后台解码编辑预览图，代次 + 目标校验丢弃陈旧结果。 */
    private void loadEditPhoto(String forItemId, String photoName) {
        final int gen = ++editPhotoGen;
        app.io().execute(() -> {
            Bitmap bmp = app.repo().loadPhoto(photoName, 1200);
            app.main().post(() -> {
                if (gen != editPhotoGen || isDestroyed()) return;
                if (!photoName.equals(stagedPhoto)
                    && (editItemId == null || !editItemId.equals(forItemId))) return;
                if (bmp != null) {
                    editPanel.setPhoto(bmp);
                    editPanel.setPhotoState(ArtworkRenderer.PHOTO_OK);
                } else {
                    editPanel.setPhotoState(ArtworkRenderer.PHOTO_FAILED);
                }
            });
        });
    }

    @Override
    public void onToggleFavorite(Item item) {
        if (!app.storeReady()) return;
        final Item next = item.copy();
        next.favorite = !next.favorite;
        ioBusy(() -> app.store().upsert(next),
            err -> { if (err == null) Ui.haptic(this, content); });
    }

    @Override
    public void onShare(Item item) {
        final File[] out = {null};
        ioBusy(() -> {
            Bitmap photo = item.isPhoto()
                ? app.repo().loadPhoto(item.photoName, 1600) : null;
            out[0] = app.repo().writeSharePng(item, photo);
        }, err -> {
            if (isFinishing() || isDestroyed()) return;
            if (err != null || out[0] == null) {
                Ui.toast(this, "分享图片生成失败" + (err == null ? "" : "：" + err));
                return;
            }
            Uri uri = ShareProvider.shareUri(this, out[0].getName());
            Intent send = new Intent(Intent.ACTION_SEND)
                .setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(ClipData.newUri(getContentResolver(),
                "lumora-collectible", uri));
            try {
                startActivity(Intent.createChooser(send, "分享这张藏品"));
            } catch (ActivityNotFoundException | SecurityException e) {
                Ui.toast(this, "没有可分享的应用");
            }
        });
    }

    // ---- 编辑/新建覆盖页 ----

    private void showOverlay() {
        editVisible = true;
        overlay.removeAllViews();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            Ui.contentWidth(this), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        overlay.addView(editPanel, lp);
        overlay.setVisibility(View.VISIBLE);
        applyTiltState();
    }

    private void hideOverlay() {
        editVisible = false;
        overlay.removeAllViews();
        overlay.setVisibility(View.GONE);
        applyTiltState();
        // 编辑关闭后领养暂挂草稿（后台导入完成时本页正开着编辑）
        adoptPendingDraft();
    }

    private void cancelEdit() {
        onCancelDraft(editIsNew, stagedPhoto);
    }

    /** 导入完成 → 进入新照片草稿；宿主可能已销毁则走 pendingDraft。 */
    private void openNewPhoto(String name, AppState.PendingDraft pd) {
        stagedPhoto = name;
        editIsNew = true;
        editItemId = null;
        editPanel.openNew(name, null);
        if (pd != null) {
            editPanel.applyDraft(pd.title, pd.note, pd.material);
        }
        showOverlay();
        editPanel.setPhotoState(ArtworkRenderer.PHOTO_LOADING);
        final int gen = ++editPhotoGen;
        app.io().execute(() -> {
            Bitmap bmp = app.repo().loadPhoto(name, 1200);
            app.main().post(() -> {
                if (gen != editPhotoGen || isDestroyed() || !name.equals(stagedPhoto)) {
                    return;
                }
                if (bmp != null) {
                    editPanel.setPhoto(bmp);
                    editPanel.setPhotoState(ArtworkRenderer.PHOTO_OK);
                } else {
                    editPanel.setPhotoState(ArtworkRenderer.PHOTO_FAILED);
                }
            });
        });
    }

    /** 领养 AppState 暂挂的未入藏照片（导入完成时宿主已销毁/正编辑）。 */
    private void adoptPendingDraft() {
        // 编辑开着、既有编辑待恢复、或有在途保存时绝不领养——草稿优先
        if (editVisible || restoringEditId != null || savingDraft) return;
        AppState.PendingDraft pd = app.peekPendingDraft();
        if (pd == null || pd.photoName == null) return;
        if (!app.storeReady()) return;
        File f = app.repo().photoFile(pd.photoName);
        if (f == null || !f.exists()) {
            app.clearPendingDraft();
            return;
        }
        app.takePendingDraft();
        openNewPhoto(pd.photoName, pd);
    }

    @Override
    public void onSaveDraft(Item draft, boolean isNew, String staged) {
        if (!app.storeReady()) return;
        final String draftId = draft.id;
        boolean accepted = ioBusy(() -> app.store().upsert(draft), err -> {
            // 幂等：notifyDataChanged 先于本回调落账时对账已处理，跳过重复结算
            if (!savingDraft || !draftId.equals(savingDraftId)) return;
            if (err != null) {
                savingDraft = false;
                savingDraftId = null;
                editPanel.setBusy(false);
                Ui.toast(this, "保存失败：" + err);
                return; // 草稿保留，可重试
            }
            finishSaveSuccess(draftId);
        });
        if (accepted) {
            // 标记在途保存 + 冻结输入：重建后凭 draftId 对账
            savingDraft = true;
            savingDraftId = draftId;
            editPanel.setBusy(true);
        }
    }

    /** 保存确认成功：照片已被藏品引用，绝不删除；关编辑页并选中。 */
    private void finishSaveSuccess(String savedId) {
        savingDraft = false;
        savingDraftId = null;
        selectedId = savedId;
        stagedPhoto = null;
        editIsNew = false;
        editItemId = null;
        editPanel.setBusy(false);
        hideOverlay(); // 若有暂挂草稿（编辑期间完成导入）会被领养
        if (app.storeReady()) stage.setItems(app.store().all(), savedId);
        stage.show(savedId);
        tableIsDetail = false;
        switchTab(TAB_TABLE);
        Ui.toast(this, "已入藏");
    }

    @Override
    public void onCancelDraft(boolean isNew, String staged) {
        if (app.isBusy()) {
            Ui.toast(this, "正在处理中，请稍候");
            return;
        }
        if (isNew && staged != null) {
            final String s = staged;
            app.io().execute(() -> AppState.discardPhotoIfUnreferenced(
                app.store(), app.repo(), s));
        }
        savingDraft = false;
        savingDraftId = null;
        stagedPhoto = null;
        editIsNew = false;
        editItemId = null;
        hideOverlay();
    }

    @Override
    public void onDeleteItem(Item item) {
        new AlertDialog.Builder(this)
            .setTitle("删除藏品")
            .setMessage("「" + item.title + "」将从藏馆移除"
                + (item.isPhoto() ? "，照片文件一并删除。" : "。") + "此操作不可撤销。")
            .setNegativeButton("再想想", null)
            .setPositiveButton("删除", (d, w) -> performDelete(item))
            .show();
    }

    private void performDelete(Item item) {
        if (!app.storeReady()) return;
        ioBusy(() -> {
            app.store().delete(item.id);                    // 先提交元数据
            if (item.isPhoto()) app.repo().deletePhoto(item.photoName); // 再删照片
        }, err -> {
            if (err != null) {
                Ui.toast(this, "删除失败：" + err);
                return;
            }
            if (item.id.equals(selectedId)) selectedId = null;
            cancelEditStateOnly();
            Ui.toast(this, "已删除");
        });
    }

    private void cancelEditStateOnly() {
        stagedPhoto = null;
        editIsNew = false;
        editItemId = null;
        hideOverlay();
    }

    // ---- 我的 ----

    @Override
    public void onPrefsChanged() {
        applyTiltState();
    }

    @Override
    public void onBackupExport() {
        if (app.isBusy() || !app.storeReady()) return;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, "lumora-backup.json");
        try {
            startActivityForResult(i, REQ_BACKUP_OUT);
        } catch (ActivityNotFoundException | SecurityException e) {
            Ui.toast(this, "没有找到文件选择器");
        }
    }

    @Override
    public void onBackupRestore() {
        if (app.isBusy() || !app.storeReady()) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json");
        try {
            startActivityForResult(i, REQ_BACKUP_IN);
        } catch (ActivityNotFoundException | SecurityException e) {
            Ui.toast(this, "没有找到文件选择器");
        }
    }

    // ---- 结果回传 ----

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        switch (req) {
            case REQ_PICK:
                if (res == RESULT_OK && data != null && data.getData() != null) {
                    importFromUri(data.getData());
                }
                break;
            case REQ_CAPTURE:
                finishCamera(res == RESULT_OK);
                break;
            case REQ_BACKUP_OUT:
                if (res == RESULT_OK && data != null && data.getData() != null) {
                    writeBackup(data.getData());
                }
                break;
            case REQ_BACKUP_IN:
                if (res == RESULT_OK && data != null && data.getData() != null) {
                    readBackup(data.getData());
                }
                break;
            default:
                break;
        }
    }

    private void finishCamera(boolean ok) {
        String name = pendingCameraName;
        pendingCameraName = null;
        Settings.clearPendingCamera(this);
        if (name == null) return;
        Uri out = ShareProvider.cameraUri(this, name);
        try {
            revokeUriPermission(out, Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        if (!ok) {
            app.io().execute(() -> app.repo().deleteCameraFile(name));
            return; // 用户取消：静默清理，不当成失败
        }
        File f = app.repo().cameraFile(name);
        if (f == null || !f.exists() || f.length() == 0) {
            app.io().execute(() -> app.repo().deleteCameraFile(name));
            Ui.toast(this, "没有收到照片");
            return;
        }
        final String camName = name;
        importTask(() -> new FileInputStream(f),
            () -> app.repo().deleteCameraFile(camName));
    }

    private interface StreamSource {
        InputStream open() throws Exception;
    }

    private interface Cleanup {
        void run();
    }

    private void importFromUri(Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme())) {
            Ui.toast(this, "仅支持内容来源的图片");
            return;
        }
        if (editVisible) {
            Ui.toast(this, "请先保存或取消当前编辑");
            return;
        }
        try {
            importTask(() -> getContentResolver().openInputStream(uri), null);
        } catch (SecurityException e) {
            Ui.toast(this, "没有该图片的读取权限");
        }
    }

    /** io 线程解码落盘 → 主线程进入新照片草稿；宿主不在场则暂挂 AppState。 */
    private void importTask(StreamSource src, Cleanup cleanup) {
        if (!app.tryBusy()) {
            Ui.toast(this, "正在处理另一张图片");
            return;
        }
        app.io().execute(() -> {
            String name = null;
            String err = null;
            try (InputStream in = src.open()) {
                if (in == null) throw new ImageRepository.ImportException("无法打开图片");
                name = app.repo().importPhoto(in);
            } catch (Exception e) {
                err = e.getMessage() == null ? "导入失败" : e.getMessage();
            } finally {
                if (cleanup != null) cleanup.run();
                app.releaseBusy();
            }
            final String fName = name;
            final String fErr = err;
            app.main().post(() -> {
                if (fName == null) {
                    Ui.toast(MainActivity.this, "照片导入失败：" + fErr);
                    return;
                }
                if (isFinishing() || isDestroyed() || editVisible) {
                    // 宿主不在场：暂挂草稿，绝不静默丢弃照片文件
                    app.setPendingDraft(fName);
                    app.notifyDataChanged();
                    return;
                }
                openNewPhoto(fName, null);
            });
        });
    }

    private void writeBackup(Uri uri) {
        mine.setState("正在导出…");
        ioBusy(() -> {
            // 先整体序列化/预检，再打开目标流（避免截断后才发现导不出）
            byte[] data = app.repo().buildBackup(app.store().all())
                .toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new ImageRepository.ImportException("无法写入");
                out.write(data);
            }
        }, err -> {
            if (isFinishing() || isDestroyed()) return;
            mine.setState(err == null ? "备份已导出。" : "备份导出失败：" + err);
        });
    }

    /**
     * 备份恢复事务（共享 io 线程）：
     * 全量校验（含图片重编码）→ 去掉与现有重复 id → 暂存 → 全部图片就位 →
     * 原子合并元数据。任何一步失败只回滚本次创建的文件，既有藏品不动。
     */
    private void readBackup(Uri uri) {
        mine.setState("正在校验备份…");
        if (!app.tryBusy()) {
            Ui.toast(this, "正在处理中，请稍候");
            return;
        }
        mine.setBusy(true);
        app.io().execute(() -> {
            String err = null;
            int added = 0;
            int total = 0;
            ImageRepository.RestorePlan plan = null;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new ImageRepository.ImportException("无法读取");
                byte[] data = CollectionStore.readCapped(in,
                    ImageRepository.MAX_BACKUP_BYTES);
                plan = app.repo().prepareRestore(data);        // 全量校验+重编码
                total = plan.items.size();
                List<Item> fresh = app.store().filterFresh(plan.items);
                if (fresh.size() < plan.items.size()) {
                    Set<String> keep = new HashSet<>();
                    for (Item it : fresh) keep.add(it.id);
                    plan.retainItems(keep);                    // 跳过项的照片不留孤儿
                }
                app.repo().stagePhotos(plan);                  // 暂存全部图片
                app.repo().installStaged(plan);                // 全部就位（失败自动回滚+抛）
                try {
                    int[] res = app.store().mergeFromBackup(plan.items);
                    added = res[0];
                } catch (CollectionStore.StoreException se) {
                    app.repo().discardAttempt(plan);           // 元数据失败 → 回滚本次图片
                    throw se;
                }
            } catch (Exception e) {
                err = e.getMessage() == null ? "恢复失败" : e.getMessage();
            } finally {
                app.releaseBusy();
            }
            app.notifyDataChanged();
            final String fErr = err;
            final int fAdded = added;
            final int fSkipped = Math.max(0, total - added);
            app.main().post(() -> {
                mine.setBusy(false);
                if (isFinishing() || isDestroyed()) return;
                if (fErr == null) {
                    mine.setState("恢复完成：新增 " + fAdded + " 件，跳过重复 "
                        + fSkipped + " 件。");
                } else {
                    mine.setState("恢复失败：" + fErr + "（现有藏品未受影响）");
                }
            });
        });
    }

    // ---- Intent 入口：只消费一次 ----

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        final String action;
        try {
            action = intent.getAction();
        } catch (RuntimeException e) {
            return; // 畸形 intent：不消费、不崩溃
        }
        if (action == null || ACTION_CONSUMED.equals(action)) return;
        // 只认识两种外部动作；MAIN/其它一律不消费不拒绝
        if (!LumoraWidget.ACTION_OPEN_ITEM.equals(action)
            && !Intent.ACTION_SEND.equals(action)) {
            return;
        }
        if (!app.storeReady()) {
            deferredIntent = intent; // 数据未就绪：就绪后由 onDataChanged 补处理
            return;
        }
        if (editVisible) {
            // 草稿未落地前拒绝新入口：可见提示，绝不静默替换草稿/丢文件
            intent.setAction(ACTION_CONSUMED);
            Ui.toast(this, "请先保存或取消当前编辑");
            return;
        }
        try {
            if (LumoraWidget.ACTION_OPEN_ITEM.equals(action)) {
                intent.setAction(ACTION_CONSUMED);
                String id = intent.getStringExtra(LumoraWidget.EXTRA_ITEM_ID);
                if (id != null) {
                    Item it = app.store().find(id);
                    if (it != null) onOpen(it);
                }
            } else {
                intent.setAction(ACTION_CONSUMED);
                Object extra = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (extra instanceof Uri) {
                    importFromUri((Uri) extra);
                } else {
                    Ui.toast(this, "分享内容格式不支持");
                }
            }
        } catch (RuntimeException e) {
            // BadParcelable/ClassCast 等畸形载荷：已消费，只提示不崩溃
            Ui.toast(this, "分享内容无法读取");
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent); // 供后续状态读取；处理只此一次
        handleIntent(intent);
    }

    // ---- 生命周期 / 状态 ----

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        app.register(dataListener);
        onDataChanged();       // 含 refreshAll/busy 同步/迟到 intent/草稿对账
        applyTiltState();
    }

    @Override
    protected void onPause() {
        resumed = false;
        app.unregister(dataListener);
        stage.onPanelHidden();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (editVisible) {
            cancelEdit();
            return;
        }
        if (currentTab == TAB_TABLE && tableIsDetail) {
            tableIsDetail = false;
            switchTab(tableReturnTab);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", currentTab);
        out.putBoolean("detail", tableIsDetail);
        out.putInt("returnTab", tableReturnTab);
        if (selectedId != null) out.putString("selectedId", selectedId);
        if (pendingCameraName != null) out.putString("pendingCamera", pendingCameraName);
        out.putBoolean("editVisible", editVisible);
        out.putBoolean("editIsNew", editIsNew);
        if (editItemId != null) out.putString("editItemId", editItemId);
        if (stagedPhoto != null) out.putString("stagedPhoto", stagedPhoto);
        if (editVisible) {
            Item d = editPanel.peekDraft();
            if (d != null) {
                out.putString("draftTitle", d.title);
                out.putString("draftNote", d.note);
                out.putInt("draftMaterial", d.material);
                if (editIsNew) {
                    // 新草稿的稳定身份：重建后仍是同一件，保存对账可对上
                    out.putString("draftId", d.id);
                    out.putLong("draftCreated", d.created);
                }
            }
        }
        out.putBoolean("savingDraft", savingDraft);
        if (savingDraftId != null) out.putString("savingDraftId", savingDraftId);
        if (deferredIntent != null) out.putParcelable("deferredIntent", deferredIntent);
        home.saveState(out);
    }

    private void restoreSaved(Bundle in) {
        tableIsDetail = in.getBoolean("detail", false);
        tableReturnTab = in.getInt("returnTab", TAB_HOME);
        selectedId = in.getString("selectedId", null);
        pendingCameraName = in.getString("pendingCamera", null);
        int tab = in.getInt("tab", TAB_HOME);
        switchTab(tab);
        home.restoreState(in);
        refreshAll();
        if (selectedId != null) stage.show(selectedId);

        if (in.getBoolean("editVisible", false)) {
            editIsNew = in.getBoolean("editIsNew", false);
            editItemId = in.getString("editItemId", null);
            stagedPhoto = in.getString("stagedPhoto", null);
            String dT = in.getString("draftTitle", "");
            String dN = in.getString("draftNote", "");
            int dM = in.getInt("draftMaterial", Item.MATERIAL_GOLD);
            if (editIsNew && stagedPhoto != null) {
                File f = app.repo().photoFile(stagedPhoto);
                if (f == null || !f.exists()) {
                    editIsNew = false;
                    stagedPhoto = null;
                } else {
                    editPanel.openNew(stagedPhoto, null);
                    String dId = in.getString("draftId", null);
                    if (dId != null) {
                        editPanel.restoreNewIdentity(dId,
                            in.getLong("draftCreated", System.currentTimeMillis()));
                    }
                    editPanel.applyDraft(dT, dN, dM);
                    showOverlay();
                    editPanel.setPhotoState(ArtworkRenderer.PHOTO_LOADING);
                    loadEditPhoto(stagedPhoto, stagedPhoto);
                }
            } else if (!editIsNew && editItemId != null) {
                // store 可能还没就绪：记下待恢复项，就绪后由 onDataChanged 补
                restoringEditId = editItemId;
                restoringTitle = dT;
                restoringNote = dN;
                restoringMaterial = dM;
            }
        }

        // 相机 token：绝不推断拍摄结果（见 restoreCameraState）
        restoreCameraState(in);

        // 保存中旋转：恢复冻结态，等对账
        savingDraft = in.getBoolean("savingDraft", false);
        savingDraftId = in.getString("savingDraftId", null);
        if (savingDraft && editVisible) editPanel.setBusy(true);
        if (!savingDraft) savingDraftId = null;

        // 迟到的外部 intent 随状态恢复（仍未消费）
        Intent di = in.getParcelable("deferredIntent");
        if (di != null) deferredIntent = di;

        // 暂挂草稿统一交给 onDataChanged 领养；此处只做去重：
        // 若 pendingDraft 就是 bundle 已恢复出的草稿，清掉防重复领养。
        if (editVisible && editIsNew && stagedPhoto != null) {
            AppState.PendingDraft pd = app.peekPendingDraft();
            if (pd != null && stagedPhoto.equals(pd.photoName)) {
                app.clearPendingDraft(); // 草稿已由 bundle 恢复，不重复领养
            }
        }
        // 注意：刻意不再 handleIntent(getIntent())——外部 intent 只消费一次，
        // 不随重建重放。
    }

    /**
     * 相机 token 恢复：绝不推断拍摄结果。外部相机可能仍持有输出授权并在写入，
     * 提前删文件/撤授权会让它写失败。token 仅作状态恢复；
     * 正常 REQ_CAPTURE 回传由 finishCamera 统一清理。
     * 包级可见供生命周期测试断言「空暂存文件与 token 在重建后仍然存活」。
     */
    void restoreCameraState(Bundle in) {
        if (pendingCameraName == null && in != null) {
            pendingCameraName = in.getString("pendingCamera", null);
        }
        if (pendingCameraName == null) {
            pendingCameraName = Settings.pendingCamera(this);
        }
    }

    /** store 就绪后补做编辑页恢复（编辑的是既有藏品）。 */
    private void finishEditRestore() {
        String id = restoringEditId;
        restoringEditId = null;
        Item it = app.store().find(id);
        if (it == null) return;
        editItemId = id;
        editIsNew = false;
        stagedPhoto = null;
        editPanel.openEdit(it, null);
        editPanel.applyDraft(restoringTitle, restoringNote, restoringMaterial);
        showOverlay();
        // 保存仍在途时保持冻结（openEdit 会重置 frozen）
        editPanel.setBusy(savingDraft && app.isBusy());
        if (it.isPhoto()) {
            editPanel.setPhotoState(ArtworkRenderer.PHOTO_LOADING);
            loadEditPhoto(it.id, it.photoName);
        }
    }

    @Override
    protected void onDestroy() {
        // 共享 io 线程归 AppState，绝不在此 shutdown
        super.onDestroy();
    }
}
