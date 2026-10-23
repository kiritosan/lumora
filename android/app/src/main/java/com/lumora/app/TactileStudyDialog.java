package com.lumora.app;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 「掌心把玩」物理沙盘展室：
 * 将馆藏器物置于天鹅绒暗盘之上，支持手势抛掷、弹性碰撞、失重悬浮、倾斜重力与磁吸/斥力力场。
 * 纯只读快照体验，退出后自动释放临时缩略图，绝不改动任何馆藏记录。
 */
public final class TactileStudyDialog extends Dialog {

    private final List<Item> snapshot = new ArrayList<>();
    private final ImageRepository repo;
    private final Executor io;
    private TactileTrayView tray;
    private TextView statusTv;

    public TactileStudyDialog(Context context, List<Item> items, ImageRepository repo, Executor io) {
        super(context);
        this.repo = repo;
        this.io = io;
        if (items != null) {
            for (int i = 0; i < Math.min(8, items.size()); i++) {
                snapshot.add(items.get(i).copy());
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Context c = getContext();

        LinearLayout root = Ui.vbox(c);
        root.setPadding(Ui.dp(c, 18), Ui.dp(c, 16), Ui.dp(c, 18), Ui.dp(c, 16));
        root.setBackground(Ui.rounded(c, Ui.OBSIDIAN, 28, 0x55D6B675, 1));

        LinearLayout header = Ui.hbox(c);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = Ui.vbox(c);
        TextView eye = Ui.eyebrow(c, "TACTILE STUDY · 掌心把玩");
        eye.setTextColor(Ui.CHAMPAGNE);
        titles.addView(eye);
        titles.addView(Ui.serifBold(c, "器物与重力沙盘", 22f, Ui.PAPER));
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button closeB = Ui.darkButton(c, "收起展盘", false);
        closeB.setOnClickListener(v -> dismiss());
        header.addView(closeB);
        root.addView(header);
        root.addView(Ui.spacer(c, 10));

        statusTv = Ui.text(c, "拖拽器物抛掷碰撞 · 仅只读把玩，不改动藏品", 11.5f, 0xFFB8C2BC);
        statusTv.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(statusTv);
        root.addView(Ui.spacer(c, 10));

        tray = new TactileTrayView(c);
        tray.setStatusListener(msg -> statusTv.setText(msg));
        tray.setSnapshot(snapshot, repo, io);
        root.addView(tray, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(Ui.spacer(c, 12));

        // 第一排：物理法则开关（重力 / 机身倾斜 / 磁吸力场）
        LinearLayout row1 = Ui.hbox(c);
        final TextView gravityChip = Ui.darkChip(c, "重力下落", true);
        gravityChip.setId(R.id.tactile_gravity);
        final TextView tiltChip = Ui.darkChip(c, "倾斜感应", false);
        final TextView magnetChip = Ui.darkChip(c, "磁力：关", false);
        magnetChip.setId(R.id.tactile_magnet);

        gravityChip.setOnClickListener(v -> {
            boolean next = !tray.isGravityEnabled();
            tray.setGravityEnabled(next);
            Ui.restyleDarkChip(c, gravityChip, next);
            gravityChip.setText(next ? "重力下落" : "失重悬浮");
        });
        tiltChip.setOnClickListener(v -> {
            boolean next = !tray.isTiltSensorEnabled();
            tray.setTiltSensorEnabled(next);
            Ui.restyleDarkChip(c, tiltChip, next);
        });
        magnetChip.setOnClickListener(v -> {
            int next = (tray.getMagnetMode() + 1) % 3;
            tray.setMagnetMode(next);
            String label = next == TactileTrayView.MAGNET_ATTRACT ? "磁力：吸引"
                : next == TactileTrayView.MAGNET_REPEL ? "磁力：排斥" : "磁力：关";
            magnetChip.setText(label);
            Ui.restyleDarkChip(c, magnetChip, next != TactileTrayView.MAGNET_OFF);
        });

        row1.addView(gravityChip, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row1.addView(gap(c));
        row1.addView(tiltChip, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row1.addView(gap(c));
        row1.addView(magnetChip, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f));
        root.addView(row1);
        root.addView(Ui.spacer(c, 8));

        // 第二排：瞬间交互动作（轻摇展盘 / 中心施力 / 重新摆放）
        LinearLayout row2 = Ui.hbox(c);
        Button shakeB = Ui.darkButton(c, "轻摇一下", true);
        shakeB.setId(R.id.tactile_shake);
        Button pulseB = Ui.darkButton(c, "中心施力", false);
        Button resetB = Ui.darkButton(c, "重新陈列", false);
        shakeB.setOnClickListener(v -> tray.shake());
        pulseB.setOnClickListener(v -> tray.pulseCenter());
        resetB.setOnClickListener(v -> tray.relayout());

        row2.addView(shakeB, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row2.addView(gap(c));
        row2.addView(pulseB, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row2.addView(gap(c));
        row2.addView(resetB, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row2);
        root.addView(Ui.spacer(c, 8));

        TextView foot = Ui.text(c,
            "提示：开启磁力后按住空白处可吸引或排斥器物；展出前 " + snapshot.size() + " 件快照，不写回数据库。",
            10.5f, 0xFF83918B);
        root.addView(foot);

        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int w = Math.min(Ui.contentWidth(getContext()),
                getContext().getResources().getDisplayMetrics().widthPixels - Ui.dp(getContext(), 16));
            int h = (int) (getContext().getResources().getDisplayMetrics().heightPixels * 0.86f);
            window.setLayout(w, h);
        }
    }

    @Override
    public void dismiss() {
        if (tray != null) {
            tray.setTiltSensorEnabled(false);
            tray.releaseBitmaps();
        }
        super.dismiss();
    }

    private static View gap(Context c) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(c, 8), 1));
        return v;
    }
}
