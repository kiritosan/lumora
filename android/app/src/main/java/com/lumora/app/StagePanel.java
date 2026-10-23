package com.lumora.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 「光桌」交互把玩与光学检视展台：
 * 1. 五种器物装裱（无边 / 画廊 / 拱窗 / 徽章 / 相纸）与「完整原图」一键切换；
 * 2. 四种展厅色温聚光灯（暖卤素 / 美术馆昼光 / 青月冷光 / 烛火琥珀）；
 * 3. 「寻光镜（Loupe）」光学放大镜开关 + 双击 2.1x 微距漫游 + 左右轻扫切卡；
 * 4. 3D 翻面背板采用虫漆暗金篆刻火漆章（Wax/Brass Seal）+ 典藏档案规格 + 可滚动私密手记；
 * 5. 「掌心把玩」入口可召唤多体物理碰撞沙盘。
 * 所有交互均为只读展示态，绝不污染底层藏品记录。
 */
public final class StagePanel extends FrameLayout {

    public interface Host {
        void onEdit(Item item);

        void onToggleFavorite(Item item);

        void onShare(Item item);

        void onRequestAdd();

        void onSelectionChanged(String itemId);

        default void onOpenTactileStudy(List<Item> items) { }
    }

    /** 背面火漆黄铜藏书章（純 Canvas 矢量精绘）。 */
    private static final class CollectorSealView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String code = "01";

        CollectorSealView(Context c) {
            super(c);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setCode(String code) {
            this.code = code == null ? "01" : code;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            float r = Math.min(w, h) * 0.44f;
            c.save();
            c.rotate(-8f, cx, cy);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF1F2D2A);
            c.drawCircle(cx, cy, r, p);

            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.5f, w * 0.025f));
            p.setColor(Ui.CHAMPAGNE);
            c.drawCircle(cx, cy, r, p);

            p.setStrokeWidth(Math.max(1f, w * 0.012f));
            p.setColor(0x66D6B675);
            c.drawCircle(cx, cy, r * 0.82f, p);

            p.setStyle(Paint.Style.FILL);
            p.setColor(Ui.CHAMPAGNE);
            p.setTypeface(Typeface.create("serif", Typeface.ITALIC));
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(r * 0.62f);
            Paint.FontMetrics fm = p.getFontMetrics();
            float ty = cy - (fm.ascent + fm.descent) / 2f;
            c.drawText(code, cx, ty, p);
            c.restore();
        }
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
    private CollectorSealView sealView;
    private TextView backSerialTv, backTitleTv;
    private TextView serialTv, titleTv, dateTv, metaTv, noteTv, hintTv;
    private Button prevB, nextB, flipB, editB, shareB, emptyAddB;
    private TextView favB, lampB, loupeB;
    private LinearLayout content;
    private LinearLayout emptyBox;
    private ScrollView mainScroll;
    private LinearLayout focusBox;
    private ArtworkView focusArt;
    private TextView focusTitle, focusMeta, positionTv;
    private Button focusB, shuffleB, tactileB, focusPrev, focusNext;
    private boolean focused;
    private int frame;
    private int lamp;
    private boolean originalPhoto;
    private boolean loupeActive;
    private TextView originalB;
    private final TextView[] frameChips = new TextView[DisplayFrame.NAMES.length];

    public StagePanel(Context ctx, Host host, ImageRepository repo, Executor io) {
        super(ctx);
        this.host = host;
        this.repo = repo;
        this.io = io;
        frame = Settings.displayFrame(ctx);
        lamp = Settings.stageLamp(ctx);
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

        LinearLayout headerRow = Ui.hbox(ctx);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.addView(Ui.eyebrow(ctx, "LIGHT TABLE · 光桌"),
            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        positionTv = Ui.text(ctx, "", 11f, Ui.COPPER);
        headerRow.addView(positionTv);
        content.addView(headerRow);
        content.addView(Ui.spacer(ctx, 8));

        hintTv = Ui.text(ctx, "单指抚摸追光 · 双击微距细赏 · 左右轻扫切换", 12f, Ui.MUTED);
        content.addView(hintTv);
        content.addView(Ui.spacer(ctx, 12));

        // 装裱形态 + 完整原图 + 展厅灯温 + 寻光放大镜 一体化横向工具栏
        HorizontalScrollView presentationScroll = new HorizontalScrollView(ctx);
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
            if (originalPhoto) loupeActive = false;
            Ui.haptic(ctx, v);
            applyPresentation();
        });
        presentation.addView(originalB);
        presentation.addView(hgap(ctx));

        lampB = Ui.chip(ctx, "灯光 · " + DisplayFrame.LAMP_NAMES[lamp], false);
        lampB.setId(R.id.stage_lamp);
        lampB.setOnClickListener(v -> {
            lamp = (lamp + 1) % DisplayFrame.LAMP_NAMES.length;
            Settings.setStageLamp(ctx, lamp);
            Ui.haptic(ctx, v);
            applyPresentation();
        });
        presentation.addView(lampB);
        presentation.addView(hgap(ctx));

        loupeB = Ui.chip(ctx, "寻光镜", false);
        loupeB.setId(R.id.stage_loupe);
        loupeB.setOnClickListener(v -> {
            loupeActive = !loupeActive;
            if (loupeActive) originalPhoto = false;
            Ui.haptic(ctx, v);
            applyPresentation();
        });
        presentation.addView(loupeB);

        presentationScroll.addView(presentation);
        content.addView(presentationScroll);
        content.addView(Ui.spacer(ctx, 14));

        // 卡面：正面画作 / 背面虫漆暗金档案板
        cardFace = new FrameLayout(ctx);
        int artH = (int) (Ui.contentWidth(ctx) * 1.15f);
        LinearLayout.LayoutParams cardLp =
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, artH);
        cardFace.setLayoutParams(cardLp);

        art = new ArtworkView(ctx);
        art.setBackground(Ui.glass(ctx, 26));
        art.setElevation(Ui.dp(ctx, 10));
        art.setClipToOutline(true);
        art.setGestureListener(new ArtworkView.GestureListener() {
            @Override
            public void onSwipeChange(int delta) {
                move(delta);
            }

            @Override
            public void onZoomStateChanged(boolean zoomed) {
                updateHintText();
            }
        });
        cardFace.addView(art, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView bScroll = new ScrollView(ctx);
        bScroll.setFillViewport(true);
        backFace = Ui.vbox(ctx);
        backFace.setGravity(Gravity.CENTER_HORIZONTAL);
        backFace.setBackground(Ui.rounded(ctx, Ui.OBSIDIAN, 24, 0x88D6B675, 1.5f));
        backFace.setPadding(Ui.dp(ctx, 24), Ui.dp(ctx, 22), Ui.dp(ctx, 24), Ui.dp(ctx, 22));

        TextView backHeader = Ui.eyebrow(ctx, "LUMORA ARCHIVE · 典藏背板");
        backHeader.setTextColor(Ui.CHAMPAGNE);
        backHeader.setGravity(Gravity.CENTER);
        backFace.addView(backHeader);
        backFace.addView(Ui.spacer(ctx, 16));

        sealView = new CollectorSealView(ctx);
        backFace.addView(sealView, new LinearLayout.LayoutParams(Ui.dp(ctx, 74), Ui.dp(ctx, 74)));
        backFace.addView(Ui.spacer(ctx, 14));

        backTitleTv = Ui.serifBold(ctx, "", 20f, Ui.PAPER);
        backTitleTv.setGravity(Gravity.CENTER);
        backFace.addView(backTitleTv);
        backFace.addView(Ui.spacer(ctx, 4));

        backSerialTv = Ui.text(ctx, "", 11f, Ui.CHAMPAGNE);
        backSerialTv.setGravity(Gravity.CENTER);
        backFace.addView(backSerialTv);
        backFace.addView(Ui.spacer(ctx, 16));

        View brassLine = new View(ctx);
        brassLine.setBackgroundColor(0x44D6B675);
        backFace.addView(brassLine, new LinearLayout.LayoutParams(Ui.dp(ctx, 64), Math.max(1, Ui.dp(ctx, 1))));
        backFace.addView(Ui.spacer(ctx, 16));

        noteTv = Ui.serif(ctx, "", 16.5f, 0xFFEDE8DC);
        noteTv.setLineSpacing(Ui.dp(ctx, 8), 1f);
        noteTv.setGravity(Gravity.CENTER_HORIZONTAL);
        backFace.addView(noteTv, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        backFace.addView(Ui.spacer(ctx, 20));

        metaTv = Ui.text(ctx, "", 11.5f, 0xFF9BA8A1);
        metaTv.setGravity(Gravity.CENTER);
        backFace.addView(metaTv);

        bScroll.addView(backFace);
        bScroll.setVisibility(GONE);
        cardFace.addView(bScroll, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        this.backScroll = bScroll;
        content.addView(cardFace);
        content.addView(Ui.spacer(ctx, 16));

        // 典藏铭牌区
        LinearLayout plaque = Ui.vbox(ctx);
        plaque.setPadding(Ui.dp(ctx, 18), Ui.dp(ctx, 14), Ui.dp(ctx, 18), Ui.dp(ctx, 14));
        plaque.setBackground(Ui.glass(ctx, 20));
        serialTv = Ui.eyebrow(ctx, "");
        serialTv.setTextColor(Ui.COPPER);
        plaque.addView(serialTv);
        plaque.addView(Ui.spacer(ctx, 4));
        titleTv = Ui.serifBold(ctx, "", 24f, Ui.INK);
        plaque.addView(titleTv);
        plaque.addView(Ui.spacer(ctx, 4));
        dateTv = Ui.text(ctx, "", 12f, Ui.MUTED);
        plaque.addView(dateTv);
        content.addView(plaque);
        content.addView(Ui.spacer(ctx, 14));

        LinearLayout row1 = Ui.hbox(ctx);
        prevB = Ui.button(ctx, "‹ 上一件", false);
        flipB = Ui.button(ctx, "翻面看背板", false);
        flipB.setId(R.id.stage_flip);
        nextB = Ui.button(ctx, "下一件 ›", false);
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
        editB = Ui.button(ctx, "编辑展签", false);
        shareB = Ui.button(ctx, "分享卡片", true);
        row2.addView(favB, weightLp());
        row2.addView(hgap(ctx));
        row2.addView(editB, weightLp());
        row2.addView(hgap(ctx));
        row2.addView(shareB, weightLp());
        content.addView(row2);
        content.addView(Ui.spacer(ctx, 12));

        // 沉浸赏玩三联：静观展室 / 掌心物理沙盘 / 随机重逢
        LinearLayout experience = Ui.hbox(ctx);
        focusB = Ui.button(ctx, "静观暗室", false);
        focusB.setId(R.id.stage_focus);
        tactileB = Ui.button(ctx, "掌心把玩", false);
        tactileB.setId(R.id.stage_tactile);
        shuffleB = Ui.button(ctx, "随机重逢", false);
        shuffleB.setId(R.id.stage_shuffle);
        experience.addView(focusB, weightLp());
        experience.addView(hgap(ctx));
        experience.addView(tactileB, weightLp());
        experience.addView(hgap(ctx));
        experience.addView(shuffleB, weightLp());
        content.addView(experience);

        focusB.setOnClickListener(v -> enterFocus());
        tactileB.setOnClickListener(v -> {
            if (!order.isEmpty()) {
                Ui.haptic(ctx, v);
                host.onOpenTactileStudy(new ArrayList<>(order));
            }
        });
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

    private void updateHintText() {
        if (art != null && art.isLoupeEnabled()) {
            hintTv.setText("寻光镜已开启：按住画作滑动，检视 2.35× 局部肌理与色散");
        } else if (art != null && art.isZoomed()) {
            hintTv.setText("微距检视中（2.1×）：单指漫游细部 · 再次双击归位");
        } else if (originalPhoto) {
            hintTv.setText("完整原图模式：零裁切、零釉光，呈现照片原始边缘与色彩");
        } else {
            hintTv.setText("单指抚摸追光 · 双击微距细赏 · 左右轻扫切换");
        }
    }

    private static LinearLayout.LayoutParams weightLp() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private static View hgap(Context ctx) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(ctx, 8), 1));
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

    public void onPanelShown() {
        panelShown = true;
        applyMotion();
    }

    public void onPanelHidden() {
        panelShown = false;
        applyMotion();
    }

    private void applyMotion() {
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
            hide.animate().rotationY(90f).setDuration(150).withEndAction(() -> {
                hide.setVisibility(GONE);
                show.setRotationY(-90f);
                show.setVisibility(VISIBLE);
                show.animate().rotationY(0f).setDuration(150)
                    .withEndAction(() -> {
                        flipping = false;
                        applyMotion();
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
        flipB.setText(backShown ? "看正面画作" : "翻面看背板");
    }

    private void resetFace() {
        flipping = false;
        art.animate().cancel();
        backScroll.animate().cancel();
        art.setRotationY(0f);
        backScroll.setRotationY(0f);
        art.setVisibility(backShown ? GONE : VISIBLE);
        backScroll.setVisibility(backShown ? VISIBLE : GONE);
        flipB.setText(backShown ? "看正面画作" : "翻面看背板");
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
            positionTv.setText("");
            art.setItem(null, null);
            applyMotion();
            return;
        }
        emptyBox.setVisibility(GONE);
        applyPresentation();

        focusTitle.setText(it.title);
        focusMeta.setText(it.serial() + " · " + Ui.materialName(it.material) + " · "
            + DisplayFrame.NAMES[frame] + " · " + Ui.dateCompact(it.created));
        positionTv.setText(String.format(java.util.Locale.ROOT, "NO.%02d / %02d", index + 1, order.size()));
        focusB.setEnabled(true);
        tactileB.setEnabled(true);
        shuffleB.setEnabled(order.size() > 1);
        focusPrev.setEnabled(index > 0);
        focusNext.setEnabled(index < order.size() - 1);
        focusPrev.setAlpha(index > 0 ? 1f : 0.4f);
        focusNext.setAlpha(index < order.size() - 1 ? 1f : 0.4f);

        serialTv.setText(it.serial() + "  ·  " + Ui.materialName(it.material)
            + "  ·  " + DisplayFrame.NAMES[frame]
            + (it.sample ? "  ·  馆藏示例" : ""));
        titleTv.setText(it.title == null ? "" : it.title);
        dateTv.setText(Ui.dateStr(it.created)
            + (it.isPhoto() ? " · 摄影原片藏品" : " · 程序化生成光景"));

        sealView.setCode(String.format(java.util.Locale.ROOT, "%02d", (index % 99) + 1));
        backTitleTv.setText(it.title == null ? "" : it.title);
        backSerialTv.setText(it.serial() + "  /  " + Ui.materialName(it.material));
        noteTv.setText(it.note == null || it.note.isEmpty()
            ? "（尚未题写手记。点下方「编辑展签」，为这一刻留下一句只有你懂的注脚。）" : it.note);
        metaTv.setText("编号 " + it.serial() + "　材质 " + Ui.materialName(it.material)
            + "　装裱 " + DisplayFrame.NAMES[frame] + "　入藏 " + Ui.dateCompact(it.created));

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
                            if (gen != loadGen) return;
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
        for (int i = 0; i < frameChips.length; i++) {
            Ui.restyleChip(getContext(), frameChips[i], !original && frame == i);
        }
        originalB.setVisibility(photo ? VISIBLE : GONE);
        Ui.restyleChip(getContext(), originalB, original);

        lampB.setText("灯光 · " + DisplayFrame.LAMP_NAMES[lamp]);
        Ui.restyleChip(getContext(), lampB, lamp != DisplayFrame.LAMP_HALOGEN && !original);

        Ui.restyleChip(getContext(), loupeB, loupeActive && !original);

        art.setLamp(lamp);
        focusArt.setLamp(lamp);
        art.setLoupeEnabled(loupeActive && !original);
        focusArt.setLoupeEnabled(loupeActive && !original);
        art.setPresentation(frame, original);
        focusArt.setPresentation(frame, original);
        updateHintText();
    }

    private void setArtwork(Item item, Bitmap bitmap, int state) {
        art.setPhotoState(state);
        art.setItem(item, bitmap);
        focusArt.setPhotoState(state);
        focusArt.setItem(item, bitmap);
    }

    private void buildFocus(Context ctx) {
        focusBox = Ui.vbox(ctx);
        focusBox.setBackgroundColor(Ui.VELVET);
        focusBox.setPadding(Ui.dp(ctx, 22), Ui.dp(ctx, 18), Ui.dp(ctx, 22), Ui.dp(ctx, 22));
        LinearLayout top = Ui.hbox(ctx);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = Ui.eyebrow(ctx, "PRIVATE VIEWING ROOM · 静观暗室");
        label.setTextColor(Ui.CHAMPAGNE);
        top.addView(label, weightLp());
        Button close = Ui.darkButton(ctx, "退出暗室", false);
        close.setOnClickListener(v -> exitFocus());
        top.addView(close);
        focusBox.addView(top);
        focusBox.addView(Ui.spacer(ctx, 18));

        focusArt = new ArtworkView(ctx);
        focusArt.setBackground(Ui.rounded(ctx, Ui.OBSIDIAN_SURFACE, 24, 0x44D6B675, 1));
        focusArt.setClipToOutline(true);
        focusArt.setGestureListener(new ArtworkView.GestureListener() {
            @Override
            public void onSwipeChange(int delta) {
                move(delta);
            }
        });
        focusBox.addView(focusArt, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        focusBox.addView(Ui.spacer(ctx, 16));

        focusTitle = Ui.serifBold(ctx, "", 24f, Ui.PAPER);
        focusTitle.setMaxLines(2);
        focusTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        focusBox.addView(focusTitle);
        focusBox.addView(Ui.spacer(ctx, 4));
        focusMeta = Ui.text(ctx, "", 12f, 0xFFB8C4BC);
        focusBox.addView(focusMeta);
        focusBox.addView(Ui.spacer(ctx, 16));

        LinearLayout navigation = Ui.hbox(ctx);
        focusPrev = Ui.darkButton(ctx, "‹ 上一件", false);
        focusNext = Ui.darkButton(ctx, "下一件 ›", false);
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

    public boolean isFocused() {
        return focused;
    }

    public void saveState(android.os.Bundle out) {
        out.putBoolean("stageOriginal", originalPhoto);
        out.putBoolean("stageLoupe", loupeActive);
        out.putBoolean("stageBack", backShown);
        out.putBoolean("stageFocus", focused);
    }

    public void restoreState(android.os.Bundle in) {
        originalPhoto = in.getBoolean("stageOriginal", false);
        loupeActive = in.getBoolean("stageLoupe", false);
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
        prevB.setEnabled(false);
        nextB.setEnabled(false);
        flipB.setEnabled(false);
        editB.setEnabled(false);
        shareB.setEnabled(false);
        favB.setEnabled(false);
        focusB.setEnabled(false);
        tactileB.setEnabled(false);
        shuffleB.setEnabled(false);

        emptyBox.removeAllViews();
        Context ctx = getContext();
        emptyBox.addView(Ui.spacer(ctx, 12));
        emptyBox.addView(Ui.text(ctx, msg, 14f, Ui.MUTED));
        if (cta != null) {
            emptyBox.addView(Ui.spacer(ctx, 10));
            emptyAddB = Ui.button(ctx, cta, true);
            emptyAddB.setOnClickListener(v -> host.onRequestAdd());
            emptyBox.addView(emptyAddB);
        }
        emptyBox.setVisibility(VISIBLE);
    }
}
