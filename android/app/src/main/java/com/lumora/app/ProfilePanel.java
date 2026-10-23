package com.lumora.app;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;

/** 「我的」：统计、偏好开关、离线说明、桌面小组件指引、SAF 备份/恢复。 */
public final class ProfilePanel extends FrameLayout {

    public interface Host {
        void onBackupExport();

        void onBackupRestore();

        void onPrefsChanged();
    }

    private final Host host;

    private TextView statsTv, stateTv;
    private Switch tiltSw, hapticSw;
    private Button exportB, restoreB;

    public ProfilePanel(Context ctx, Host host) {
        super(ctx);
        this.host = host;
        build(ctx);
    }

    private void build(Context ctx) {
        ScrollView scroll = new ScrollView(ctx);
        scroll.setFillViewport(true);
        LinearLayout root = Ui.vbox(ctx);
        root.setPadding(Ui.dp(ctx, 22), Ui.dp(ctx, 18), Ui.dp(ctx, 22), Ui.dp(ctx, 28));
        scroll.addView(root);
        addView(scroll);

        root.addView(Ui.eyebrow(ctx, "KEEPER · 我的"));
        root.addView(Ui.spacer(ctx, 8));
        root.addView(Ui.serifBold(ctx, "拾光", 30f, Ui.INK));
        root.addView(Ui.spacer(ctx, 6));
        statsTv = Ui.text(ctx, "", 13f, Ui.MUTED);
        root.addView(statsTv);
        root.addView(Ui.spacer(ctx, 6));
        root.addView(Ui.hairline(ctx));
        root.addView(Ui.spacer(ctx, 16));

        // 偏好
        tiltSw = toggleRow(ctx, root, "倾斜追光",
            "转动机身，光会在画面上流动（随时在这里关闭）");
        hapticSw = toggleRow(ctx, root, "轻触反馈",
            "翻面、心动等操作时给出轻微触感");
        tiltSw.setChecked(Settings.tiltEnabled(ctx));
        hapticSw.setChecked(Settings.hapticsEnabled(ctx));
        tiltSw.setOnCheckedChangeListener((b, on) -> {
            Settings.setTilt(ctx, on);
            host.onPrefsChanged();
        });
        hapticSw.setOnCheckedChangeListener((b, on) -> {
            Settings.setHaptics(ctx, on);
            host.onPrefsChanged();
        });

        root.addView(Ui.spacer(ctx, 8));
        root.addView(Ui.hairline(ctx));
        root.addView(Ui.spacer(ctx, 16));

        // 备份
        root.addView(Ui.eyebrow(ctx, "BACKUP · 本地备份"));
        root.addView(Ui.spacer(ctx, 8));
        LinearLayout br = Ui.hbox(ctx);
        exportB = Ui.button(ctx, "导出备份", false);
        restoreB = Ui.button(ctx, "恢复备份", false);
        exportB.setId(R.id.backup_export);
        restoreB.setId(R.id.backup_restore);
        exportB.setOnClickListener(v -> host.onBackupExport());
        restoreB.setOnClickListener(v -> host.onBackupRestore());
        br.addView(exportB, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View g = new View(ctx);
        br.addView(g, new LinearLayout.LayoutParams(Ui.dp(ctx, 10), 1));
        br.addView(restoreB, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(br);
        root.addView(Ui.spacer(ctx, 6));
        TextView bn = Ui.text(ctx,
            "备份是保存在你选定位置的明文 JSON 文件（含内嵌图片，上限 100 件 / 32MB）。"
                + "仅追加恢复，重复条目会被跳过，不会覆盖现有藏品；与网页版备份格式不互通。",
            12f, Ui.MUTED);
        root.addView(bn);
        root.addView(Ui.spacer(ctx, 6));
        stateTv = Ui.text(ctx, "", 13f, Ui.COPPER);
        root.addView(stateTv);

        root.addView(Ui.spacer(ctx, 16));
        root.addView(Ui.hairline(ctx));
        root.addView(Ui.spacer(ctx, 16));

        // 小组件指引
        root.addView(Ui.eyebrow(ctx, "WIDGET · 今日一藏"));
        root.addView(Ui.spacer(ctx, 8));
        root.addView(Ui.text(ctx,
            "长按桌面空白处 → 小组件 → 找到「拾光 · 今日一藏」拖到桌面。"
                + "它会展示你最近一件心动藏品，点一下就能回到它面前。",
            13f, Ui.INK_SOFT));
        root.addView(Ui.spacer(ctx, 16));
        root.addView(Ui.hairline(ctx));
        root.addView(Ui.spacer(ctx, 16));

        // 隐私
        root.addView(Ui.eyebrow(ctx, "PRIVACY · 离线说明"));
        root.addView(Ui.spacer(ctx, 8));
        root.addView(Ui.text(ctx,
            "拾光完全离线运行：不联网、不上传、无统计。照片与藏品数据只保存在本机应用私有目录；"
                + "分享图片经系统分享面板发出后才离开本机。此 v1 版本不含网页版藏品的自动迁移与云端同步。",
            13f, Ui.INK_SOFT));
        root.addView(Ui.spacer(ctx, 24));
        root.addView(Ui.text(ctx, "Lumora · 拾光 v1.0.0 · 原生 Android 版", 11f, Ui.MUTED));
    }

    private Switch toggleRow(Context ctx, LinearLayout root, String title, String desc) {
        LinearLayout row = Ui.hbox(ctx);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setMinHeight(Ui.dp(ctx, 56));
        LinearLayout texts = Ui.vbox(ctx);
        texts.addView(Ui.text(ctx, title, 15f, Ui.INK));
        texts.addView(Ui.text(ctx, desc, 11f, Ui.MUTED));
        row.addView(texts, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch sw = new Switch(ctx);
        sw.setMinWidth(Ui.dp(ctx, 48));
        sw.setMinHeight(Ui.dp(ctx, 48));
        sw.setContentDescription(title);
        row.addView(sw);
        root.addView(row);
        root.addView(Ui.spacer(ctx, 6));
        return sw;
    }

    public void setStats(List<Item> items) {
        int fav = 0, photo = 0;
        for (Item it : items) {
            if (it.favorite) fav++;
            if (it.isPhoto()) photo++;
        }
        statsTv.setText(String.format(java.util.Locale.ROOT,
            "共 %d 件藏品 · 心动 %d 件 · 照片 %d 张", items.size(), fav, photo));
    }

    public void setBusy(boolean busy) {
        exportB.setEnabled(!busy);
        restoreB.setEnabled(!busy);
    }

    public void setState(String msg) {
        stateTv.setText(msg == null ? "" : msg);
    }
}
