package com.lumora.app;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** A local, cancellable print studio. Previewing never writes files or alters the collection. */
public final class ShareComposer extends Dialog {
    public interface Host { void export(int style, boolean includeNote, boolean saveFile); }
    private final Item item;
    private final AppState app;
    private final Host host;
    private int style;
    private boolean includeNote;
    private volatile int generation;
    private volatile boolean closed;
    private ImageView preview;
    private TextView status;
    private Button share, save;
    private final TextView[] styles = new TextView[3];

    public ShareComposer(Context context, Item item, AppState app, Host host) {
        super(context);
        this.item = item.copy(); this.app = app; this.host = host;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Context c = getContext();
        LinearLayout root = Ui.vbox(c);
        root.setPadding(Ui.dp(c, 20), Ui.dp(c, 16), Ui.dp(c, 20), Ui.dp(c, 18));
        root.setBackground(Ui.rounded(c, Ui.PAPER, 28, null, 0));
        LinearLayout heading = Ui.hbox(c);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(Ui.serif(c, "把光寄出去", 25, Ui.INK), new LinearLayout.LayoutParams(0, -2, 1));
        Button close = Ui.button(c, "关闭", false);
        close.setOnClickListener(v -> dismiss()); heading.addView(close); root.addView(heading);
        TextView subtitle = Ui.text(c, "选一张纸，留下一份属于你的展签。", 12, Ui.MUTED);
        root.addView(subtitle); root.addView(Ui.spacer(c, 12));
        ScrollView scroll = new ScrollView(c);
        LinearLayout body = Ui.vbox(c);
        scroll.addView(body);
        preview = new ImageView(c);
        preview.setAdjustViewBounds(true);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setContentDescription("分享卡片预览");
        int previewHeight = Math.min(Ui.dp(c, 380), c.getResources().getDisplayMetrics().heightPixels / 2);
        body.addView(preview, new LinearLayout.LayoutParams(-1, previewHeight));
        body.addView(Ui.spacer(c, 14));
        LinearLayout choices = Ui.hbox(c);
        for (int i = 0; i < styles.length; i++) {
            final int selected = i;
            styles[i] = Ui.chip(c, ShareCardRenderer.NAMES[i], i == style);
            styles[i].setOnClickListener(v -> { style = selected; renderPreview(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) lp.leftMargin = Ui.dp(c, 8);
            choices.addView(styles[i], lp);
        }
        body.addView(choices); body.addView(Ui.spacer(c, 12));
        Switch note = new Switch(c);
        note.setText("附上手记摘录（最多六行）");
        note.setTextSize(13); note.setTextColor(Ui.INK);
        note.setMinHeight(Ui.dp(c, 48));
        boolean hasNote = item.note != null && !item.note.trim().isEmpty();
        note.setEnabled(hasNote);
        note.setChecked(false); // Private notes are always opt-in, including subsequent openings.
        note.setOnCheckedChangeListener((button, checked) -> { includeNote = checked; renderPreview(); });
        body.addView(note);
        status = Ui.text(c, "正在准备预览…", 12, Ui.MUTED);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(status);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(Ui.spacer(c, 12));
        LinearLayout actions = Ui.hbox(c);
        save = Ui.button(c, "保存图片", false);
        share = Ui.button(c, "分享卡片", true);
        actions.addView(save, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.leftMargin = Ui.dp(c, 10); actions.addView(share, lp); root.addView(actions);
        share.setOnClickListener(v -> finish(false));
        save.setOnClickListener(v -> finish(true));
        setContentView(root);
    }

    @Override protected void onStart() {
        super.onStart();
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(Math.min(Ui.contentWidth(getContext()),
                getContext().getResources().getDisplayMetrics().widthPixels - Ui.dp(getContext(), 20)),
                (int) (getContext().getResources().getDisplayMetrics().heightPixels * 0.88f));
        }
        closed = false;
        renderPreview();
    }

    private void finish(boolean saveFile) {
        if (!share.isEnabled()) return;
        int selected = style;
        boolean note = includeNote;
        dismiss();
        host.export(selected, note, saveFile);
    }

    private void renderPreview() {
        if (preview == null || closed) return;
        for (int i = 0; i < styles.length; i++) Ui.restyleChip(getContext(), styles[i], i == style);
        share.setEnabled(false); save.setEnabled(false);
        status.setText("正在准备预览…");
        final int request = ++generation, selected = style;
        final boolean note = includeNote;
        app.io().execute(() -> {
            if (closed || request != generation) return;
            Bitmap bitmap = null;
            String failure = null;
            try {
                Bitmap photo = item.isPhoto() ? app.repo().loadPhoto(item.photoName, 1600) : null;
                if (item.isPhoto() && (photo == null || photo.isRecycled())) {
                    throw new IllegalStateException("原照片不可用，请先恢复照片");
                }
                if (closed || request != generation) return;
                bitmap = ShareCardRenderer.render(item, photo, selected, note, 540);
            } catch (IllegalStateException e) {
                failure = e.getMessage();
            } catch (RuntimeException | OutOfMemoryError e) {
                failure = "预览生成失败，请关闭后重试";
            }
            final Bitmap rendered = bitmap;
            final String error = failure;
            app.main().post(() -> {
                if (closed || request != generation) {
                    if (rendered != null) rendered.recycle();
                    return;
                }
                preview.setImageBitmap(rendered);
                preview.setContentDescription(ShareCardRenderer.NAMES[selected] + "版式，" + item.title
                    + (note ? "，包含手记摘录" : "，不包含手记"));
                share.setEnabled(error == null); save.setEnabled(error == null);
                status.setText(error != null ? error : "1080 × 1440 PNG · "
                    + (note ? "包含手记摘录" : "仅分享画作与展签"));
            });
        });
    }

    @Override public void dismiss() {
        closed = true; generation++;
        if (preview != null) preview.setImageDrawable(null);
        super.dismiss();
    }
}
