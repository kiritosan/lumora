package com.lumora.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/**
 * 编辑/新建表单：草稿态只活在视图里，「保存」才提交给 store；
 * 取消/返回不改任何数据。新照片卡片的材质预览实时可见。
 */
public final class EditPanel extends FrameLayout {

    public interface Host {
        void onSaveDraft(Item draft, boolean isNew, String stagedPhotoName);

        void onCancelDraft(boolean isNew, String stagedPhotoName);

        void onDeleteItem(Item item);
    }

    private final Host host;
    private final ImageRepository repo;

    private Item original;          // 编辑对象（新建为 null）
    private Item draftItem;         // 新建草稿的稳定身份（id/created 一次生成，保存/重建不变）
    private String stagedPhoto;     // 新建照片已落盘文件名
    private Bitmap stagedBitmap;
    private int material = Item.MATERIAL_GOLD;
    private boolean isNew;
    private boolean frozen = false; // 保存进行中：冻结全部输入与按钮

    private ArtworkView preview;
    private EditText titleEt, noteEt;
    private TextView titleCount, noteCount, errTv;
    private final TextView[] matChips = new TextView[Item.MATERIAL_COUNT];
    private Button saveB, cancelB, deleteB;
    private LinearLayout root;

    public EditPanel(Context ctx, Host host, ImageRepository repo) {
        super(ctx);
        this.host = host;
        this.repo = repo;
        build(ctx);
    }

    private void build(Context ctx) {
        ScrollView scroll = new ScrollView(ctx);
        root = Ui.vbox(ctx);
        root.setPadding(Ui.dp(ctx, 22), Ui.dp(ctx, 18), Ui.dp(ctx, 22), Ui.dp(ctx, 28));
        scroll.addView(root);
        addView(scroll);

        root.addView(Ui.eyebrow(ctx, "EDIT · 展签修改"));
        root.addView(Ui.spacer(ctx, 12));

        preview = new ArtworkView(ctx);
        preview.setInteractive(false);
        int ph = (int) (Ui.contentWidth(ctx) * 0.62f);
        preview.setBackground(Ui.rounded(ctx, Ui.CARD, 16, Ui.LINE, 1));
        preview.setClipToOutline(true);
        root.addView(preview, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ph));
        root.addView(Ui.spacer(ctx, 16));

        // 材质三选
        root.addView(Ui.text(ctx, "材质", 13f, Ui.MUTED));
        root.addView(Ui.spacer(ctx, 6));
        LinearLayout mats = Ui.hbox(ctx);
        for (int i = 0; i < Item.MATERIAL_COUNT; i++) {
            final int m = i;
            matChips[i] = Ui.chip(ctx, Ui.materialName(i), i == material);
            matChips[i].setOnClickListener(v -> {
                material = m;
                refreshChips(ctx);
                updatePreview();
            });
            mats.addView(matChips[i], new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (i < Item.MATERIAL_COUNT - 1) {
                View g = new View(ctx);
                mats.addView(g, new LinearLayout.LayoutParams(Ui.dp(ctx, 8), 1));
            }
        }
        root.addView(mats);
        root.addView(Ui.spacer(ctx, 16));

        // 标题
        LinearLayout titleRow = Ui.hbox(ctx);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView tl = Ui.text(ctx, "标题 *", 13f, Ui.MUTED);
        titleRow.addView(tl, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titleCount = Ui.text(ctx, "0/" + Item.MAX_TITLE, 11f, Ui.MUTED);
        titleRow.addView(titleCount);
        root.addView(titleRow);
        titleEt = input(ctx, "给这一刻起个名字", true);
        titleEt.setContentDescription("藏品标题（必填）");
        titleEt.addTextChangedListener(counter(titleCount, Item.MAX_TITLE));
        root.addView(titleEt);
        root.addView(Ui.spacer(ctx, 14));

        // 手记
        LinearLayout noteRow = Ui.hbox(ctx);
        TextView nl = Ui.text(ctx, "手记", 13f, Ui.MUTED);
        noteRow.addView(nl, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        noteCount = Ui.text(ctx, "0/" + Item.MAX_NOTE, 11f, Ui.MUTED);
        noteRow.addView(noteCount);
        root.addView(noteRow);
        noteEt = input(ctx, "写点什么，以后翻到会认得", false);
        noteEt.setContentDescription("藏品手记");
        noteEt.setMinLines(4);
        noteEt.setGravity(Gravity.TOP);
        noteEt.addTextChangedListener(counter(noteCount, Item.MAX_NOTE));
        root.addView(noteEt);
        root.addView(Ui.spacer(ctx, 10));

        errTv = Ui.text(ctx, "", 13f, Ui.COPPER);
        errTv.setVisibility(GONE);
        root.addView(errTv);
        root.addView(Ui.spacer(ctx, 12));

        LinearLayout btns = Ui.hbox(ctx);
        cancelB = Ui.button(ctx, "取消", false);
        saveB = Ui.button(ctx, "保存", true);
        btns.addView(cancelB, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View g = new View(ctx);
        btns.addView(g, new LinearLayout.LayoutParams(Ui.dp(ctx, 10), 1));
        btns.addView(saveB, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(btns);
        root.addView(Ui.spacer(ctx, 12));

        deleteB = Ui.button(ctx, "删除此藏品", false);
        deleteB.setTextColor(Ui.COPPER);
        deleteB.setOnClickListener(v -> {
            if (original != null) host.onDeleteItem(original);
        });
        root.addView(deleteB);

        saveB.setOnClickListener(v -> trySave());
        cancelB.setOnClickListener(v -> host.onCancelDraft(isNew, stagedPhoto));
    }

    private static EditText input(Context ctx, String hint, boolean single) {
        EditText e = new EditText(ctx);
        e.setHint(hint);
        e.setHintTextColor(Ui.MUTED);
        e.setTextColor(Ui.INK);
        e.setTextSize(15f);
        e.setBackground(Ui.rounded(ctx, Ui.CARD, 14, Ui.LINE, 1));
        e.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 10), Ui.dp(ctx, 14), Ui.dp(ctx, 10));
        if (single) {
            e.setSingleLine(true);
            e.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        } else {
            e.setImeOptions(EditorInfo.IME_FLAG_NO_ENTER_ACTION);
            e.setSingleLine(false);
        }
        return e;
    }

    private TextWatcher counter(TextView tv, int max) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                int n = s == null ? 0 : s.length();
                tv.setText(String.format(Locale.ROOT, "%d/%d", n, max));
                tv.setTextColor(n > max ? Ui.COPPER : Ui.MUTED);
            }
            @Override public void afterTextChanged(Editable s) {}
        };
    }

    private void refreshChips(Context ctx) {
        for (int i = 0; i < matChips.length; i++) {
            Ui.restyleChip(ctx, matChips[i], i == material);
        }
    }

    /** 打开既有藏品的编辑。 */
    public void openEdit(Item item, Bitmap photo) {
        isNew = false;
        original = item.copy();
        draftItem = null;
        stagedPhoto = null;
        stagedBitmap = photo;
        material = item.material;
        frozen = false;
        titleEt.setText(item.title);
        noteEt.setText(item.note);
        deleteB.setVisibility(VISIBLE);
        errTv.setVisibility(GONE);
        refreshChips(getContext());
        updatePreview();
        setBusy(false); // 复用面板时恢复控件可用态（冻结由宿主按需再开）
    }

    /**
     * 打开新照片草稿（照片已 import 落盘，取消时由 host 删除）。
     * 草稿 Item 在此刻一次性创建：id/created 稳定，保存/横竖屏重建都不变。
     */
    public void openNew(String photoName, Bitmap bmp) {
        isNew = true;
        original = null;
        draftItem = Item.newPhoto("", "", Item.MATERIAL_GOLD, photoName);
        stagedPhoto = photoName;
        stagedBitmap = bmp;
        material = Item.MATERIAL_GOLD;
        frozen = false;
        titleEt.setText("");
        noteEt.setText("");
        deleteB.setVisibility(GONE);
        errTv.setVisibility(GONE);
        refreshChips(getContext());
        updatePreview();
        setBusy(false); // 复用面板时恢复控件可用态（冻结由宿主按需再开）
    }

    /** 重建后回灌新草稿的稳定身份（id/created 与原草稿一致）。 */
    public void restoreNewIdentity(String id, long created) {
        if (isNew && draftItem != null) {
            draftItem.id = id;
            draftItem.created = created;
        }
    }

    /** 保存进行中冻结/解冻输入；冻结时取消键也不可用（草稿有在途写盘）。 */
    public void setBusy(boolean busy) {
        frozen = busy;
        titleEt.setEnabled(!busy);
        noteEt.setEnabled(!busy);
        saveB.setEnabled(!busy);
        cancelB.setEnabled(!busy);
        deleteB.setEnabled(!busy);
        for (TextView chip : matChips) chip.setEnabled(!busy);
        float a = busy ? 0.55f : 1f;
        saveB.setAlpha(a);
        cancelB.setAlpha(a);
        deleteB.setAlpha(a);
    }

    public boolean isFrozen() {
        return frozen;
    }

    private void updatePreview() {
        Item probe;
        if (isNew) {
            probe = draftItem.copy();
            probe.material = material;
        } else {
            probe = original.copy();
            probe.material = material;
        }
        preview.setItem(probe, stagedBitmap);
    }

    private void trySave() {
        if (frozen) return;
        String title = titleEt.getText() == null ? "" : titleEt.getText().toString().trim();
        String note = noteEt.getText() == null ? "" : noteEt.getText().toString();
        String err = null;
        if (title.isEmpty()) err = "标题不能为空";
        else if (title.length() > Item.MAX_TITLE) err = "标题最多 " + Item.MAX_TITLE + " 字";
        else if (note.length() > Item.MAX_NOTE) err = "手记最多 " + Item.MAX_NOTE + " 字";
        if (err != null) {
            errTv.setText(err);
            errTv.setVisibility(VISIBLE);
            return;
        }
        errTv.setVisibility(GONE);

        Item draft = currentDraft(title, note);
        if (draft == null) return;
        host.onSaveDraft(draft, isNew, stagedPhoto);
    }

    /** 以稳定身份生成当前草稿副本（id/created 恒定，字段取输入态）。 */
    private Item currentDraft(String title, String note) {
        Item d;
        if (isNew) {
            if (draftItem == null) return null;
            d = draftItem.copy();
        } else if (original != null) {
            d = original.copy();
        } else {
            return null;
        }
        d.title = title;
        d.note = note;
        d.material = material;
        return d;
    }

    /** 异步解码完成后回灌预览位图（编辑页已打开时）。 */
    public void setPhoto(Bitmap bmp) {
        stagedBitmap = bmp;
        updatePreview();
    }

    public void setPhotoState(int state) {
        preview.setPhotoState(state);
    }

    /** 重建后回灌未提交草稿。 */
    public void applyDraft(String title, String note, int mat) {
        titleEt.setText(title == null ? "" : title);
        noteEt.setText(note == null ? "" : note);
        material = mat;
        refreshChips(getContext());
        updatePreview();
    }

    /** 供宿主保存实例状态时抓取未提交草稿（含稳定 id/created）。 */
    public Item peekDraft() {
        String title = titleEt.getText() == null ? "" : titleEt.getText().toString();
        String note = noteEt.getText() == null ? "" : noteEt.getText().toString();
        return currentDraft(title, note);
    }

    public boolean isEditingNew() {
        return isNew;
    }

    public String getStagedPhoto() {
        return stagedPhoto;
    }
}
