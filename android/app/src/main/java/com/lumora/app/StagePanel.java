package com.lumora.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 「光桌」/详情：大幅可交互画作（触摸/倾斜追光），翻面看手记与档案，
 * 上一张/下一张、心动、编辑、分享。浏览行为本身绝不写藏品数据；
 * 照片解码按选中代次追踪，陈旧回调不落地。
 */
public final class StagePanel extends FrameLayout {

    public interface Host {
        void onEdit(Item item);

        void onToggleFavorite(Item item);

        void onShare(Item item);

        void onRequestAdd();

        void onSelectionChanged(String itemId);
    }

    private final Host host;
    private final ImageRepository repo;
    private final Executor io;

    private final List<Item> order = new ArrayList<>();
    private int index = 0;
    private boolean backShown = false;
    private boolean flipping = false;
    private boolean panelShown = false;
    private String storeError = null;
    private int loadGen = 0;

    private ArtworkView art;
    private FrameLayout cardFace;
    private ScrollView backScroll;
    private LinearLayout backFace;
    private TextView serialTv, titleTv, dateTv, metaTv, noteTv;
    private Button prevB, nextB, flipB, editB, shareB, emptyAddB;
    private TextView favB;
    private LinearLayout content;
    private LinearLayout emptyBox;
    private ScrollView mainScroll;
    private LinearLayout focusBox;
    private ArtworkView focusArt;
    private TextView focusTitle, focusMeta, positionTv;
    private Button focusB, shuffleB, focusPrev, focusNext;
    private boolean focused;
    private int frame;
    private boolean originalPhoto;
    private TextView originalB;
    private final TextView[] frameChips = new TextView[3];

    public StagePanel(Context ctx, Host host, ImageRepository repo, Executor io) {
        super(ctx);
        this.host = host;
        this.repo = repo;
        this.io = io;
        frame = Settings.displayFrame(ctx);
        build(ctx);
    }

    private void build(Context ctx) {
        ScrollView scroll = new ScrollView(ctx);
        mainScroll = scroll;
        scroll.setFillViewport(true);
        content = Ui.vbox(ctx);
        content.setPadding(Ui.dp(ctx, 22), Ui.dp(ctx, 18), Ui.dp(ctx, 22), Ui.dp(ctx, 28));
        scroll.addView(content);
        addView(scroll);

        content.addView(Ui.eyebrow(ctx, "LIGHT TABLE · 光桌"));
        content.addView(Ui.spacer(ctx, 12));
        TextView hint = Ui.text(ctx, "轻触画面，或缓缓转动手机，让光掠过藏品", 12f, Ui.MUTED);
        content.addView(hint);
        content.addView(Ui.spacer(ctx, 14));
        android.widget.HorizontalScrollView presentationScroll = new android.widget.HorizontalScrollView(ctx);
        presentationScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout presentation = Ui.hbox(ctx);
        for (int i = 0; i < frameChips.length; i++) {
            final int selected = i;
            frameChips[i] = Ui.chip(ctx, DisplayFrame.NAMES[i], frame == i);
            frameChips[i].setOnClickListener(v -> {
                frame = selected;
                originalPhoto = false;
                Settings.setDisplayFrame(ctx, selected);
                Ui.haptic(ctx, v);
                applyPresentation();
            });
            presentation.addView(frameChips[i]);
            presentation.addView(hgap(ctx));
        }
        originalB = Ui.chip(ctx, "完整原图", false);
        originalB.setId(R.id.stage_original);
        originalB.setOnClickListener(v -> {
            originalPhoto = !originalPhoto;
            applyPresentation();
        });
        presentation.addView(originalB);
        presentationScroll.addView(presentation);
        content.addView(presentationScroll);
        content.addView(Ui.spacer(ctx, 14));

        // 卡面：正面画作 / 背面手记（背面可滚动，手记再长也读得完）
        cardFace = new FrameLayout(ctx);
        int artH = (int) (Ui.contentWidth(ctx) * 1.15f);
        LinearLayout.LayoutParams cardLp =
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, artH);
        cardFace.setLayoutParams(cardLp);

        art = new ArtworkView(ctx);
        art.setBackground(Ui.glass(ctx, 26));
        art.setElevation(Ui.dp(ctx, 8));
        art.setClipToOutline(true);
        cardFace.addView(art, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView backScroll = new ScrollView(ctx);
        backFace = Ui.vbox(ctx);
        backFace.setBackground(Ui.rounded(ctx, Ui.CARD, 18, Ui.LINE, 1));
        backFace.setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 18), Ui.dp(ctx, 20), Ui.dp(ctx, 18));
        backFace.addView(Ui.eyebrow(ctx, "手记 · NOTE"));
        backFace.addView(Ui.spacer(ctx, 10));
        noteTv = Ui.serif(ctx, "", 17f, Ui.INK_SOFT);
        noteTv.setLineSpacing(Ui.dp(ctx, 8), 1f);
        backFace.addView(noteTv);
        backFace.addView(Ui.spacer(ctx, 16));
        metaTv = Ui.text(ctx, "", 12f, Ui.MUTED);
        backFace.addView(metaTv);
        backScroll.addView(backFace);
        backScroll.setVisibility(GONE);
        cardFace.addView(backScroll, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        this.backScroll = backScroll;
        content.addView(cardFace);
        content.addView(Ui.spacer(ctx, 14));

        serialTv = Ui.eyebrow(ctx, "");
        content.addView(serialTv);
        content.addView(Ui.spacer(ctx, 6));
        titleTv = Ui.serifBold(ctx, "", 26f, Ui.INK);
        content.addView(titleTv);
        content.addView(Ui.spacer(ctx, 4));
        dateTv = Ui.text(ctx, "", 12f, Ui.MUTED);
        content.addView(dateTv);
        content.addView(Ui.spacer(ctx, 18));

        LinearLayout row1 = Ui.hbox(ctx);
        prevB = Ui.button(ctx, "‹ 上一张", false);
        flipB = Ui.button(ctx, "翻面", false);
        flipB.setId(R.id.stage_flip);
        nextB = Ui.button(ctx, "下一张 ›", false);
        row1.addView(prevB, weightLp());
        row1.addView(hgap(ctx));
        row1.addView(flipB, weightLp());
        row1.addView(hgap(ctx));
        row1.addView(nextB, weightLp());
        content.addView(row1);
        content.addView(Ui.spacer(ctx, 10));

        LinearLayout row2 = Ui.hbox(ctx);
        favB = Ui.chip(ctx, "♡ 心动", false);
        favB.setMinHeight(Ui.dp(ctx, 48));
        editB = Ui.button(ctx, "编辑", false);
        shareB = Ui.button(ctx, "分享卡片", true);
        row2.addView(favB, weightLp());
        row2.addView(hgap(ctx));
        row2.addView(editB, weightLp());
        row2.addView(hgap(ctx));
        row2.addView(shareB, weightLp());
        content.addView(row2);
        content.addView(Ui.spacer(ctx, 16));
        LinearLayout experience = Ui.hbox(ctx);
        focusB = Ui.button(ctx, "静观 · 沉浸赏光", false);
        focusB.setId(R.id.stage_focus);
        shuffleB = Ui.button(ctx, "随机重逢", false);
        shuffleB.setId(R.id.stage_shuffle);
        experience.addView(focusB, weightLp());
        experience.addView(hgap(ctx));
        experience.addView(shuffleB, weightLp());
        content.addView(experience);
        positionTv = Ui.text(ctx, "", 11, Ui.MUTED);
        positionTv.setGravity(android.view.Gravity.CENTER);
        positionTv.setPadding(0, Ui.dp(ctx, 14), 0, 0);
        content.addView(positionTv);
        focusB.setOnClickListener(v -> enterFocus());
        shuffleB.setOnClickListener(v -> {
            if (order.size() < 2) return;
            int next = java.util.concurrent.ThreadLocalRandom.current().nextInt(order.size() - 1);
            if (next >= index) next++;
            move(next - index);
        });

        emptyBox = Ui.vbox(ctx);
        emptyBox.setVisibility(GONE);
        content.addView(emptyBox);

        prevB.setOnClickListener(v -> move(-1));
        nextB.setOnClickListener(v -> move(1));
        flipB.setOnClickListener(v -> flip());
        favB.setOnClickListener(v -> {
            Item cur = current();
            if (cur != null) host.onToggleFavorite(cur);
        });
        editB.setOnClickListener(v -> {
            Item cur = current();
            if (cur != null) host.onEdit(cur);
        });
        shareB.setOnClickListener(v -> {
            Item cur = current();
            if (cur != null) host.onShare(cur);
        });
        buildFocus(ctx);
    }

    private static LinearLayout.LayoutParams weightLp() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private static View hgap(Context ctx) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(ctx, 10), 1));
        return v;
    }

    // ---- 数据 ----

    public void setItems(List<Item> items, String keepId) {
        order.clear();
        order.addAll(items);
        int idx = 0;
        if (keepId != null) {
            for (int i = 0; i < order.size(); i++) {
                if (order.get(i).id.equals(keepId)) {
                    idx = i;
                    break;
                }
            }
        }
        index = Math.min(idx, Math.max(0, order.size() - 1));
        storeError = null;
        bind();
        // 回退选中（无 keepId/找不到）也要让宿主同步 selectedId
        Item cur = current();
        host.onSelectionChanged(cur != null ? cur.id : null);
    }

    public void setStoreError(String msg) {
        storeError = msg;
        bind();
    }

    public void show(String itemId) {
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).id.equals(itemId)) {
                index = i;
                originalPhoto = false;
                backShown = false;
                bind();
                host.onSelectionChanged(itemId);
                return;
            }
        }
    }

    public Item current() {
        if (order.isEmpty() || index < 0 || index >= order.size()) return null;
        return order.get(index);
    }

    /** 面板可见且前台 → 允许追光；否则停掉传感器。 */
    public void onPanelShown() {
        panelShown = true;
        applyMotion();
    }

    public void onPanelHidden() {
        panelShown = false;
        applyMotion();
    }

    private void applyMotion() {
        // 传感器只在正面画作可见、面板前台且有藏品时注册
        art.setMotionActive(panelShown && !focused && !backShown && current() != null);
        focusArt.setMotionActive(panelShown && focused && current() != null);
    }

    private void move(int d) {
        int ni = index + d;
        if (ni < 0 || ni >= order.size()) return;
        index = ni;
        originalPhoto = false;
        backShown = false;
        Ui.haptic(getContext(), d < 0 ? prevB : nextB);
        bind();
        Item cur = current();
        if (cur != null) host.onSelectionChanged(cur.id);
    }

    private void flip() {
        if (flipping || current() == null) return;
        Ui.haptic(getContext(), flipB);
        backShown = !backShown;
        View show = backShown ? backScroll : art;
        View hide = backShown ? art : backScroll;
        if (Ui.animationsEnabled(getContext())) {
            flipping = true;
            hide.animate().rotationY(90f).setDuration(140).withEndAction(() -> {
                hide.setVisibility(GONE);
                show.setRotationY(-90f);
                show.setVisibility(VISIBLE);
                show.animate().rotationY(0f).setDuration(140)
                    .withEndAction(() -> {
                        flipping = false;
                        applyMotion(); // 正面画回到可见后才允许注册传感器
                    })
                    .start();
            }).start();
        } else {
            hide.setVisibility(GONE);
            hide.setRotationY(0f);
            show.setRotationY(0f);
            show.setVisibility(VISIBLE);
            applyMotion();
        }
        flipB.setText(backShown ? "看画作" : "翻面");
    }

    /** 每次绑定都归位卡面：不留半途动画、不留错误朝向。 */
    private void resetFace() {
        flipping = false;
        art.animate().cancel();
        backScroll.animate().cancel();
        art.setRotationY(0f);
        backScroll.setRotationY(0f);
        art.setVisibility(backShown ? GONE : VISIBLE);
        backScroll.setVisibility(backShown ? VISIBLE : GONE);
        flipB.setText(backShown ? "看画作" : "翻面");
    }

    private void bind() {
        Context ctx = getContext();
        ++loadGen;
        resetFace();
        if (storeError != null) {
            showEmpty(storeError, null);
            return;
        }
        Item it = current();
        if (it == null) {
            showEmpty("去「藏馆」收下第一件作品，或点下面按钮添加。", "藏入第一张照片");
            serialTv.setText("LIGHT TABLE");
            titleTv.setText("还没有藏品");
            dateTv.setText("");
            art.setItem(null, null);
            applyMotion();
            return;
        }
        emptyBox.setVisibility(GONE);
        applyPresentation();

        focusTitle.setText(it.title);
        focusMeta.setText(Ui.materialName(it.material) + " · " + Ui.dateCompact(it.created));
        positionTv.setText(String.format(java.util.Locale.ROOT, "%02d / %02d · 每一件，都值得慢慢看", index + 1, order.size()));
        focusB.setEnabled(true);
        shuffleB.setEnabled(order.size() > 1);
        focusPrev.setEnabled(index > 0);
        focusNext.setEnabled(index < order.size() - 1);
        focusPrev.setAlpha(index > 0 ? 1 : 0.4f);
        focusNext.setAlpha(index < order.size() - 1 ? 1 : 0.4f);
        serialTv.setText(it.serial() + " · " + Ui.materialName(it.material)
            + (it.sample ? " · 示例" : ""));
        titleTv.setText(it.title == null ? "" : it.title);
        dateTv.setText(Ui.dateStr(it.created)
            + (it.isPhoto() ? " · 照片藏品" : " · 程序化画作"));
        noteTv.setText(it.note == null || it.note.isEmpty() ? "（还没有手记）" : it.note);
        metaTv.setText("编号 " + it.serial() + "　材质 " + Ui.materialName(it.material)
            + "　入藏 " + Ui.dateCompact(it.created));
        favB.setText(it.favorite ? "♥ 已心动" : "♡ 心动");
        Ui.restyleChip(ctx, favB, it.favorite);

        prevB.setEnabled(index > 0);
        nextB.setEnabled(index < order.size() - 1);
        prevB.setAlpha(index > 0 ? 1f : 0.4f);
        nextB.setAlpha(index < order.size() - 1 ? 1f : 0.4f);
        flipB.setEnabled(true);
        editB.setEnabled(true);
        shareB.setEnabled(true);
        favB.setEnabled(true);

        // 照片解码走后台，按代次丢弃陈旧结果
        if (it.isPhoto()) {
            final String name = it.photoName;
            Bitmap cached = repo.peekThumb(name, 1200);
            if (cached != null) {
                setArtwork(it, cached, ArtworkRenderer.PHOTO_OK);
            } else {
                setArtwork(it, null, ArtworkRenderer.PHOTO_LOADING);
                java.io.File f = repo.photoFile(name);
                if (f == null || !f.exists()) {
                    setArtwork(it, null, ArtworkRenderer.PHOTO_FAILED);
                } else {
                    art.setPhotoState(ArtworkRenderer.PHOTO_LOADING);
                    final int gen = ++loadGen;
                    io.execute(() -> {
                        Bitmap bmp = repo.loadPhoto(name, 1200);
                        final int state = bmp != null
                            ? ArtworkRenderer.PHOTO_OK : ArtworkRenderer.PHOTO_FAILED;
                        post(() -> {
                            if (gen != loadGen) return;       // 已切走
                            Item cur = current();
                            if (cur == null || !name.equals(cur.photoName)) return;
                            setArtwork(cur, bmp, state);
                        });
                    });
                }
            }
        } else {
            setArtwork(it, null, ArtworkRenderer.PHOTO_OK);
        }
        applyMotion();
    }

    private void applyPresentation() {
        Item item = current();
        boolean photo = item != null && item.isPhoto();
        boolean original = photo && originalPhoto;
        for (int i = 0; i < frameChips.length; i++)
            Ui.restyleChip(getContext(), frameChips[i], !original && frame == i);
        originalB.setVisibility(photo ? VISIBLE : GONE);
        Ui.restyleChip(getContext(), originalB, original);
        art.setPresentation(frame, original);
        focusArt.setPresentation(frame, original);
    }

    private void setArtwork(Item item, Bitmap bitmap, int state) {
        art.setPhotoState(state);
        art.setItem(item, bitmap);
        focusArt.setPhotoState(state);
        focusArt.setItem(item, bitmap);
    }

    private void buildFocus(Context ctx) {
        focusBox = Ui.vbox(ctx);
        focusBox.setBackgroundColor(0xFF102C29);
        focusBox.setPadding(Ui.dp(ctx, 22), Ui.dp(ctx, 18), Ui.dp(ctx, 22), Ui.dp(ctx, 22));
        LinearLayout top = Ui.hbox(ctx);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView label = Ui.eyebrow(ctx, "STILL · 静观");
        label.setTextColor(Ui.CHAMPAGNE);
        top.addView(label, weightLp());
        Button close = Ui.button(ctx, "退出静观", false);
        close.setOnClickListener(v -> exitFocus());
        top.addView(close);
        focusBox.addView(top);
        focusBox.addView(Ui.spacer(ctx, 22));
        focusArt = new ArtworkView(ctx);
        focusArt.setBackground(Ui.rounded(ctx, Ui.INK, 22, null, 0));
        focusArt.setClipToOutline(true);
        focusBox.addView(focusArt, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        focusBox.addView(Ui.spacer(ctx, 18));
        focusTitle = Ui.serif(ctx, "", 25, Ui.PAPER);
        focusTitle.setMaxLines(2);
        focusTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        focusBox.addView(focusTitle);
        focusMeta = Ui.text(ctx, "", 12, 0xFFCDD4C6);
        focusBox.addView(focusMeta);
        focusBox.addView(Ui.spacer(ctx, 16));
        LinearLayout navigation = Ui.hbox(ctx);
        focusPrev = Ui.button(ctx, "‹ 上一张", false);
        focusNext = Ui.button(ctx, "下一张 ›", false);
        focusPrev.setOnClickListener(v -> move(-1));
        focusNext.setOnClickListener(v -> move(1));
        navigation.addView(focusPrev, weightLp());
        navigation.addView(hgap(ctx));
        navigation.addView(focusNext, weightLp());
        focusBox.addView(navigation);
        focusBox.setVisibility(GONE);
        addView(focusBox, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void enterFocus() {
        if (current() == null) return;
        focused = true;
        mainScroll.setVisibility(GONE);
        focusBox.setVisibility(VISIBLE);
        Ui.haptic(getContext(), focusB);
        applyMotion();
    }

    public boolean exitFocus() {
        if (!focused) return false;
        focused = false;
        focusBox.setVisibility(GONE);
        mainScroll.setVisibility(VISIBLE);
        applyMotion();
        return true;
    }

    public boolean isFocused() { return focused; }

    public void saveState(android.os.Bundle out) {
        out.putBoolean("stageOriginal", originalPhoto);
        out.putBoolean("stageBack", backShown);
        out.putBoolean("stageFocus", focused);
    }

    public void restoreState(android.os.Bundle in) {
        originalPhoto = in.getBoolean("stageOriginal", false);
        backShown = in.getBoolean("stageBack", false);
        bind();
        if (in.getBoolean("stageFocus", false)) enterFocus();
    }

    private void showEmpty(String msg, String cta) {
        exitFocus();
        originalB.setVisibility(GONE);
        ++loadGen;
        setArtwork(null, null, ArtworkRenderer.PHOTO_OK);
        art.setMotionActive(false);
        focusArt.setMotionActive(false);
        focusB.setEnabled(false);
        shuffleB.setEnabled(false);
        positionTv.setText("");
        serialTv.setText("LIGHT TABLE");
        titleTv.setText("等待第一束光");
        dateTv.setText("");
        emptyBox.removeAllViews();
        emptyBox.setVisibility(VISIBLE);
        emptyBox.addView(Ui.text(getContext(), msg, 14f, Ui.MUTED));
        if (cta != null) {
            emptyBox.addView(Ui.spacer(getContext(), 14));
            emptyAddB = Ui.button(getContext(), cta, true);
            emptyAddB.setOnClickListener(v -> host.onRequestAdd());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            emptyBox.addView(emptyAddB, lp);
        }
        prevB.setEnabled(false);
        nextB.setEnabled(false);
        flipB.setEnabled(false);
        editB.setEnabled(false);
        shareB.setEnabled(false);
        favB.setEnabled(false);
        prevB.setAlpha(0.4f);
        nextB.setAlpha(0.4f);
    }
}
