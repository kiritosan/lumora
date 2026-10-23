package com.lumora.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 「藏馆」首页：品牌头 + 搜索 + 组合筛选（心动/材质）+ 精选卡 + 双列藏馆网格。
 * 缩略图解码在后台线程，只回写当前仍绑定同名照片的视图，
 * 不做「列表重排整页 refresh」式刷新，缓存淘汰不会造成无限重载。
 */
public final class HomePanel extends ScrollView {

    public interface Host {
        void onOpen(Item item);

        void onRequestAdd();

        void onOpenStage();
    }

    public interface Filter {
        boolean match(Item item);

        String key();
    }

    private final Host host;
    private final ImageRepository repo;
    private final Executor io;

    private final List<Item> all = new ArrayList<>();
    private String query = "";
    private boolean onlyFav = false;
    private Integer material = null; // null=全部
    private String storeError = null;
    private boolean loading = true;
    private int bindGen = 0;

    /** 当前网格里「照片名 → 绑定它的缩略图视图」；解码完只更新还在的这些。 */
    private final Map<String, List<ArtworkView>> boundThumbs = new HashMap<>();

    private LinearLayout content;
    private LinearLayout chipsRow;
    private LinearLayout grid;
    private TextView featuredTitle, featuredMeta, stateTv;
    private ArtworkView featuredArt;
    private EditText search;
    private FrameLayout featuredCard;
    private android.widget.Button addB;

    private Item featured;

    public HomePanel(Context ctx, Host host, ImageRepository repo, Executor io) {
        super(ctx);
        this.host = host;
        this.repo = repo;
        this.io = io;
        build(ctx);
    }

    private void build(Context ctx) {
        setFillViewport(true);
        content = Ui.vbox(ctx);
        content.setPadding(Ui.dp(ctx, 22), Ui.dp(ctx, 20), Ui.dp(ctx, 22), Ui.dp(ctx, 28));
        addView(content);

        LinearLayout brandRow = Ui.hbox(ctx);
        brandRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView brand = Ui.serifBold(ctx, "Lumora", 30f, Ui.INK);
        brandRow.addView(brand, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addB = Ui.button(ctx, "＋ 拾取", false);
        addB.setId(R.id.add_photo);
        addB.setContentDescription("从相册或相机拾取新照片");
        addB.setOnClickListener(v -> host.onRequestAdd());
        brandRow.addView(addB, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(brandRow);
        TextView sub = Ui.eyebrow(ctx, "拾光 · 口袋里的光影藏馆　" + Ui.dateCompact(System.currentTimeMillis()));
        content.addView(sub);
        content.addView(Ui.spacer(ctx, 10));
        TextView heading = Ui.serif(ctx, "把片刻，\n藏进光里。", 34f, Ui.INK);
        heading.setLineSpacing(Ui.dp(ctx, 6), 1f);
        content.addView(heading);
        content.addView(Ui.spacer(ctx, 18));

        // 搜索
        search = new EditText(ctx);
        search.setHint("搜标题或手记…");
        search.setHintTextColor(Ui.MUTED);
        search.setTextColor(Ui.INK);
        search.setTextSize(14f);
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setMinHeight(Ui.dp(ctx, 48));
        search.setContentDescription("搜索藏品");
        search.setBackground(Ui.rounded(ctx, Ui.CARD, 22, Ui.LINE, 1));
        search.setPadding(Ui.dp(ctx, 18), 0, Ui.dp(ctx, 18), 0);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = s == null ? "" : s.toString().trim();
                render();
            }
            public void afterTextChanged(Editable s) { }
        });
        content.addView(search, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(Ui.spacer(ctx, 14));

        // 筛选 chips：横向滚动，窄屏不截断
        HorizontalScrollView chipScroll = new HorizontalScrollView(ctx);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipsRow = Ui.hbox(ctx);
        chipScroll.addView(chipsRow);
        content.addView(chipScroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(Ui.spacer(ctx, 18));

        renderChips(ctx);
        content.addView(Ui.hairline(ctx));
        content.addView(Ui.spacer(ctx, 16));

        // 精选卡
        featuredCard = new FrameLayout(ctx);
        featuredCard.setBackground(Ui.rounded(ctx, Ui.CARD, 20, Ui.LINE, 1));
        featuredCard.setClipToOutline(true);
        featuredArt = new ArtworkView(ctx);
        featuredArt.setInteractive(false);
        int featH = (int) (Ui.contentWidth(ctx) * 0.62f);
        featuredCard.addView(featuredArt, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, featH));
        content.addView(featuredCard);
        featuredTitle = Ui.serifBold(ctx, "", 20f, Ui.INK);
        featuredMeta = Ui.eyebrow(ctx, "");
        LinearLayout ft = Ui.vbox(ctx);
        ft.setPadding(Ui.dp(ctx, 18), 0, Ui.dp(ctx, 18), Ui.dp(ctx, 16));
        ft.addView(featuredMeta);
        ft.addView(Ui.spacer(ctx, 4));
        ft.addView(featuredTitle);
        FrameLayout.LayoutParams ftp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ftp.topMargin = featH;
        featuredCard.addView(ft, ftp);
        featuredCard.setOnClickListener(v -> {
            if (featured != null) host.onOpen(featured);
        });
        content.addView(Ui.spacer(ctx, 20));

        content.addView(Ui.eyebrow(ctx, "COLLECTION · 藏馆"));
        content.addView(Ui.spacer(ctx, 12));

        grid = Ui.vbox(ctx);
        content.addView(grid);

        stateTv = Ui.text(ctx, "", 14f, Ui.MUTED);
        stateTv.setPadding(0, Ui.dp(ctx, 10), 0, 0);
        content.addView(stateTv);
    }

    // ---- 数据 ----

    public void setLoading(boolean on) {
        loading = on;
        render();
    }

    public void setStoreError(String msg) {
        storeError = msg;
        loading = false;
        render();
    }

    public void setItems(List<Item> items) {
        all.clear();
        all.addAll(items);
        loading = false;
        storeError = null;
        render();
    }

    public String currentQuery() {
        return query;
    }

    // ---- 状态保存/恢复（搜索词 + 筛选 + 滚动）----

    public void saveState(Bundle out) {
        out.putString("homeQuery", query);
        out.putBoolean("homeFav", onlyFav);
        out.putInt("homeMat", material == null ? -1 : material);
        out.putInt("homeScroll", getScrollY());
    }

    public void restoreState(Bundle in) {
        query = in.getString("homeQuery", "");
        onlyFav = in.getBoolean("homeFav", false);
        int m = in.getInt("homeMat", -1);
        material = m < 0 ? null : m;
        search.setText(query);
        renderChips(getContext());
        render();
        final int y = in.getInt("homeScroll", 0);
        if (y > 0) post(() -> scrollTo(0, y));
    }

    private List<Item> filtered() {
        List<Item> out = new ArrayList<>();
        for (Item it : all) {
            if (onlyFav && !it.favorite) continue;
            if (material != null && it.material != material) continue;
            if (!query.isEmpty()) {
                String q = query.toLowerCase();
                String hay = ((it.title == null ? "" : it.title)
                    + " " + (it.note == null ? "" : it.note)).toLowerCase();
                if (!hay.contains(q)) continue;
            }
            out.add(it);
        }
        return out;
    }

    private void renderChips(Context ctx) {
        chipsRow.removeAllViews();
        addChip(ctx, "全部", material == null && !onlyFav,
            () -> { material = null; onlyFav = false; });
        addChip(ctx, "♥ 心动", onlyFav,
            () -> { onlyFav = !onlyFav; });
        String[] names = {"蜜金", "海盐", "银雾"};
        for (int i = 0; i < names.length; i++) {
            final int m = i;
            addChip(ctx, names[i], material != null && material == m,
                () -> { material = (material != null && material == m) ? null : m; });
        }
    }

    private void addChip(Context ctx, String label, boolean selected, Runnable onTap) {
        TextView chip = Ui.chip(ctx, label, selected);
        chip.setOnClickListener(v -> {
            Ui.haptic(ctx, v);
            onTap.run();
            renderChips(ctx);
            render();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(ctx, 8);
        chipsRow.addView(chip, lp);
    }

    private void render() {
        boundThumbs.clear();
        final int gen = ++bindGen;
        Context ctx = getContext();
        addB.setEnabled(!loading && storeError == null);

        if (loading && storeError == null) {
            featuredCard.setVisibility(GONE);
            grid.removeAllViews();
            stateTv.setText("正在整理藏馆…");
            stateTv.setVisibility(VISIBLE);
            return;
        }
        if (storeError != null) {
            featuredCard.setVisibility(GONE);
            grid.removeAllViews();
            stateTv.setText(storeError);
            stateTv.setVisibility(VISIBLE);
            return;
        }

        List<Item> items = filtered();
        boolean searching = !query.isEmpty() || onlyFav || material != null;

        // 精选：第一个结果
        if (items.isEmpty()) {
            featured = null;
            featuredCard.setVisibility(GONE);
        } else {
            featured = items.get(0);
            featuredCard.setVisibility(VISIBLE);
            featuredCard.setContentDescription("精选藏品 " + featured.title);
            featuredMeta.setText((featured.favorite ? "♥ 今日一藏 · " : "今日一藏 · ")
                + featured.serial() + " · " + Ui.materialName(featured.material)
                + (featured.sample ? " · 示例" : ""));
            featuredTitle.setText(featured.title == null ? "" : featured.title);
            bindThumb(featuredArt, featured, gen);
        }

        // 网格：其余藏品两列
        grid.removeAllViews();
        if (items.isEmpty()) {
            stateTv.setVisibility(VISIBLE);
            if (all.isEmpty()) {
                stateTv.setText("藏馆还是空的。点下方按钮，把第一束光放进来。");
                grid.addView(makeEmptyCta(ctx));
            } else {
                stateTv.setText("没有找到匹配的藏品");
                grid.addView(makeClearFilters(ctx));
            }
            return;
        }
        if (items.size() <= 1) {
            stateTv.setVisibility(searching && items.size() == 1 ? VISIBLE : GONE);
            if (searching && items.size() == 1) {
                stateTv.setText("只找到这一件");
            }
        } else {
            stateTv.setVisibility(GONE);
        }

        LinearLayout row = null;
        for (int i = 1; i < items.size(); i++) {
            Item it = items.get(i);
            if ((i - 1) % 2 == 0) {
                row = Ui.hbox(ctx);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = Ui.dp(ctx, 14);
                grid.addView(row, rlp);
            }
            row.addView(makeCard(ctx, it, gen), cellLp(ctx));
            if ((i - 1) % 2 == 0) {
                row.addView(gap(ctx));
                if (i == items.size() - 1) {
                    // 奇数末行只有左列：补等权占位，卡片不撑满
                    View stub = new View(ctx);
                    row.addView(stub, cellLp(ctx));
                }
            }
        }
    }

    private LinearLayout.LayoutParams cellLp(Context ctx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        return lp;
    }

    private static View gap(Context ctx) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(ctx, 12), 1));
        return v;
    }

    private View makeEmptyCta(Context ctx) {
        LinearLayout box = Ui.vbox(ctx);
        box.setPadding(0, Ui.dp(ctx, 10), 0, 0);
        android.widget.Button b = Ui.button(ctx, "藏入第一张照片", true);
        b.setOnClickListener(v -> host.onRequestAdd());
        box.addView(b, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private View makeClearFilters(Context ctx) {
        android.widget.Button b = Ui.button(ctx, "清除搜索与筛选", false);
        b.setOnClickListener(v -> {
            query = "";
            onlyFav = false;
            material = null;
            search.setText("");
            renderChips(ctx);
            render();
        });
        LinearLayout box = Ui.vbox(ctx);
        box.setPadding(0, Ui.dp(ctx, 6), 0, 0);
        box.addView(b, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private View makeCard(Context ctx, Item it, int gen) {
        LinearLayout card = Ui.vbox(ctx);
        card.setBackground(Ui.rounded(ctx, Ui.CARD, 16, Ui.LINE, 1));
        card.setClipToOutline(true);
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription("藏品 " + (it.title == null ? "" : it.title)
            + " " + Ui.materialName(it.material));

        ArtworkView thumb = new ArtworkView(ctx);
        thumb.setInteractive(false);
        int thumbH = (int) (Ui.contentWidth(ctx) * 0.36f);
        card.addView(thumb, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, thumbH));

        LinearLayout meta = Ui.vbox(ctx);
        meta.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 10), Ui.dp(ctx, 12), Ui.dp(ctx, 12));
        TextView t = Ui.serifBold(ctx, it.title == null ? "" : it.title, 15f, Ui.INK);
        t.setMaxLines(1);
        meta.addView(t);
        meta.addView(Ui.spacer(ctx, 3));
        meta.addView(Ui.eyebrow(ctx,
            (it.favorite ? "♥ " : "") + it.serial() + " · " + Ui.materialName(it.material)
                + (it.sample ? " · 示例" : "")));
        card.addView(meta);

        card.setOnClickListener(v -> host.onOpen(it));
        bindThumb(thumb, it, gen);
        return card;
    }

    /**
     * 绑定缩略图：程序画作直接画；照片先查缩略缓存，
     * 没有则登记视图 → 后台解码 → 仅当该视图仍绑定同名照片才回写。
     */
    private void bindThumb(ArtworkView view, Item it, int gen) {
        if (!it.isPhoto()) {
            view.setPhotoState(ArtworkRenderer.PHOTO_OK);
            view.setItem(it, null);
            return;
        }
        Bitmap cached = repo.peekThumb(it.photoName, 640);
        if (cached != null) {
            view.setPhotoState(ArtworkRenderer.PHOTO_OK);
            view.setItem(it, cached);
            return;
        }
        java.io.File f = repo.photoFile(it.photoName);
        if (f == null || !f.exists()) {
            view.setPhotoState(ArtworkRenderer.PHOTO_FAILED);
            view.setItem(it, null);
            return;
        }
        view.setPhotoState(ArtworkRenderer.PHOTO_LOADING);
        view.setItem(it, null);
        boundThumbs.computeIfAbsent(it.photoName, k -> new ArrayList<>()).add(view);
        io.execute(() -> {
            Bitmap bmp = repo.loadPhoto(it.photoName, 640);
            post(() -> {
                if (gen != bindGen) return;               // 已重排，交给新绑定
                List<ArtworkView> views = boundThumbs.get(it.photoName);
                if (views == null) return;
                for (ArtworkView v : views) {
                    Item bound = v.getItem();
                    if (bound == null || !it.photoName.equals(bound.photoName)) continue;
                    if (bmp != null) {
                        v.setPhotoState(ArtworkRenderer.PHOTO_OK);
                        v.setItem(bound, bmp);
                    } else {
                        v.setPhotoState(ArtworkRenderer.PHOTO_FAILED);
                        v.setItem(bound, null);
                    }
                }
            });
        });
    }
}
