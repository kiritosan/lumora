package com.lumora.app;

import android.content.Context;
import android.app.AlertDialog;
import java.time.LocalDate;
import java.time.YearMonth;
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

        default void onEdit(Item item) { }
        default void onToggleFavorite(Item item) { }
        default void onShare(Item item) { }
        default void onOpenTactileStudy(List<Item> items) { }
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
    private boolean onlyOwn = false;
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
    private int sort = CollectionInsights.NEWEST;
    private boolean timeline = false;
    private TextView resultCount, sortControl, galleryControl, timelineControl, summary;
    private TextView featuredNote;

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
        android.widget.Button studyB = Ui.button(ctx, "掌心把玩", false);
        studyB.setContentDescription("打开掌心物理把玩沙盘");
        studyB.setOnClickListener(v -> {
            if (!all.isEmpty()) {
                Ui.haptic(ctx, v);
                host.onOpenTactileStudy(new ArrayList<>(all));
            }
        });
        LinearLayout.LayoutParams studyLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        studyLp.rightMargin = Ui.dp(ctx, 8);
        brandRow.addView(studyB, studyLp);
        addB = Ui.button(ctx, "＋ 拾取", true);
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
        LinearLayout cover = Ui.hbox(ctx);
        cover.setGravity(android.view.Gravity.CENTER_VERTICAL);
        cover.addView(heading, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        cover.addView(new GalleryEmblem(ctx), new LinearLayout.LayoutParams(Ui.dp(ctx, 102), Ui.dp(ctx, 138)));
        content.addView(cover);
        content.addView(Ui.spacer(ctx, 12));
        summary = Ui.text(ctx, "每一个平凡的日子，都有值得收藏的光。", 12f, Ui.MUTED);
        content.addView(summary);
        content.addView(Ui.spacer(ctx, 20));

        // 搜索
        search = new EditText(ctx);
        search.setHint("搜标题、手记或编号…");
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
        search.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            android.view.inputmethod.InputMethodManager keyboard =
                (android.view.inputmethod.InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
            search.clearFocus();
            return true;
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
        featuredCard.setBackground(Ui.glass(ctx, 26));
        featuredCard.setElevation(Ui.dp(ctx, 7));
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
        ft.setPadding(Ui.dp(ctx, 18), Ui.dp(ctx, 16), Ui.dp(ctx, 18), Ui.dp(ctx, 18));
        ft.addView(featuredMeta);
        ft.addView(Ui.spacer(ctx, 4));
        ft.addView(featuredTitle);
        FrameLayout.LayoutParams ftp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ftp.topMargin = featH;
        featuredCard.addView(ft, ftp);
        featuredNote = Ui.text(ctx, "", 12f, Ui.MUTED);
        featuredNote.setMaxLines(2);
        featuredNote.setEllipsize(android.text.TextUtils.TruncateAt.END);
        ft.addView(Ui.spacer(ctx, 6));
        ft.addView(featuredNote);
        Ui.touchSurface(ctx, featuredCard, 26);
        featuredCard.setOnLongClickListener(v -> {
            if (featured != null) showActions(featured);
            return true;
        });
        featuredCard.setOnClickListener(v -> {
            if (featured != null) host.onOpen(featured);
        });
        content.addView(Ui.spacer(ctx, 20));

        LinearLayout collectionHeading = Ui.hbox(ctx);
        collectionHeading.setGravity(android.view.Gravity.CENTER_VERTICAL);
        collectionHeading.addView(Ui.eyebrow(ctx, "COLLECTION · 藏馆"),
            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        resultCount = Ui.text(ctx, "", 12f, Ui.MUTED);
        resultCount.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        collectionHeading.addView(resultCount);
        content.addView(collectionHeading);
        content.addView(Ui.spacer(ctx, 10));
        LinearLayout controls = Ui.hbox(ctx);
        galleryControl = Ui.chip(ctx, "画廊", true);
        galleryControl.setId(R.id.view_gallery);
        timelineControl = Ui.chip(ctx, "时间档案", false);
        timelineControl.setId(R.id.view_timeline);
        galleryControl.setOnClickListener(v -> setTimeline(false));
        timelineControl.setOnClickListener(v -> setTimeline(true));
        controls.addView(galleryControl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f));
        controls.addView(gap(ctx));
        controls.addView(timelineControl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f));
        controls.addView(gap(ctx));
        sortControl = Ui.chip(ctx, "最近入藏 ▾", false);
        sortControl.setId(R.id.collection_sort);
        sortControl.setOnClickListener(v -> new AlertDialog.Builder(ctx)
            .setTitle("藏馆排序")
            .setSingleChoiceItems(timeline
                ? new String[]{"最近入藏", "最早入藏"} : CollectionInsights.SORT_NAMES,
                sort, (dialog, which) -> {
                    sort = which;
                    dialog.dismiss();
                    render();
                }).setNegativeButton("取消", null).show());
        controls.addView(sortControl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f));
        content.addView(controls);
        content.addView(Ui.spacer(ctx, 8));
        content.addView(Ui.text(ctx, "轻触赏光 · 长按藏品可心动、编辑或分享", 11, Ui.MUTED));
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
        out.putInt("homeSort", sort);
        out.putBoolean("homeTimeline", timeline);
        out.putString("homeQuery", query);
        out.putBoolean("homeFav", onlyFav);
        out.putBoolean("homeOwn", onlyOwn);
        out.putInt("homeMat", material == null ? -1 : material);
        out.putInt("homeScroll", getScrollY());
    }

    public void restoreState(Bundle in) {
        sort = Math.max(0, Math.min(3, in.getInt("homeSort", 0)));
        timeline = in.getBoolean("homeTimeline", false);
        if (timeline && sort > CollectionInsights.OLDEST) sort = CollectionInsights.NEWEST;
        query = in.getString("homeQuery", "");
        onlyFav = in.getBoolean("homeFav", false);
        onlyOwn = in.getBoolean("homeOwn", false);
        int m = in.getInt("homeMat", -1);
        material = m < 0 ? null : m;
        search.setText(query);
        renderChips(getContext());
        render();
        final int y = in.getInt("homeScroll", 0);
        if (y > 0) post(() -> scrollTo(0, y));
    }

    private List<Item> filtered() {
        List<Item> source = new ArrayList<>();
        for (Item item : all) if (!onlyOwn || !item.sample) source.add(item);
        return CollectionInsights.select(source, query, onlyFav, material, sort);
    }

    private void setTimeline(boolean enabled) {
        timeline = enabled;
        if (timeline && sort > CollectionInsights.OLDEST) sort = CollectionInsights.NEWEST;
        Ui.haptic(getContext(), enabled ? timelineControl : galleryControl);
        render();
    }

    private void renderChips(Context ctx) {
        chipsRow.removeAllViews();
        addChip(ctx, "全部", material == null && !onlyFav && !onlyOwn,
            () -> { material = null; onlyFav = false; onlyOwn = false; });
        addChip(ctx, "我的收藏", onlyOwn, () -> { onlyOwn = !onlyOwn; });
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
        Ui.restyleChip(ctx, galleryControl, !timeline);
        Ui.restyleChip(ctx, timelineControl, timeline);
        sortControl.setText(CollectionInsights.SORT_NAMES[sort] + " ▾");
        resultCount.setText(loading ? "整理中" : (storeError != null ? "暂不可用" : ""));

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
        boolean searching = !query.isEmpty() || onlyFav || onlyOwn || material != null;

        resultCount.setText(searching ? items.size() + " / " + all.size() + " 件" : all.size() + " 件藏品");
        int personal = 0;
        for (Item item : all) if (!item.sample) personal++;
        summary.setText(personal == 0 ? "从一束光开始，慢慢建起你的私人藏馆。"
            : "已珍藏 " + personal + " 个片刻 · 在 " + CollectionInsights.activeDays(all) + " 个日子里发现光");

        // A daily reunion, independent of sort and hidden while narrowing results.
        if (items.isEmpty() || searching || timeline) {
            featured = null;
            featuredCard.setVisibility(GONE);
        } else {
            featured = CollectionInsights.daily(all, LocalDate.now());
            featuredCard.setVisibility(VISIBLE);
            featuredCard.setContentDescription("精选藏品 " + featured.title);
            featuredMeta.setText((featured.favorite ? "♥ 今日一藏 · " : "今日一藏 · ")
                + featured.serial() + " · " + Ui.materialName(featured.material)
                + (featured.sample ? " · 示例" : ""));
            featuredTitle.setText(featured.title == null ? "" : featured.title);
            featuredNote.setText(featured.note == null || featured.note.trim().isEmpty()
                ? "与收藏里的光，再相遇一次。" : featured.note);
            featuredArt.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            bindThumb(featuredArt, featured, gen);
        }

        // Every item remains present in the archive, including the daily feature.
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

        if (timeline) {
            String previousMonth = "";
            for (Item it : items) {
                String month = CollectionInsights.monthLabel(YearMonth.from(CollectionInsights.date(it.created)));
                if (!month.equals(previousMonth)) {
                    TextView monthTitle = Ui.serifBold(ctx, month, 21, Ui.INK);
                    monthTitle.setPadding(0, Ui.dp(ctx, 18), 0, Ui.dp(ctx, 12));
                    grid.addView(monthTitle);
                    previousMonth = month;
                }
                grid.addView(makeTimelineCard(ctx, it, gen));
            }
            return;
        }
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (i % 2 == 0) {
                row = Ui.hbox(ctx);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = Ui.dp(ctx, 14);
                grid.addView(row, rlp);
            }
            row.addView(makeCard(ctx, it, gen), cellLp(ctx));
            if (i % 2 == 0) {
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
            onlyOwn = false;
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
        card.setBackground(Ui.glass(ctx, 22));
        card.setElevation(Ui.dp(ctx, 4));
        card.setClipToOutline(true);
        Ui.touchSurface(ctx, card, 22);
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
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        meta.addView(t);
        meta.addView(Ui.spacer(ctx, 3));
        meta.addView(Ui.eyebrow(ctx,
            (it.favorite ? "♥ " : "") + it.serial() + " · " + Ui.materialName(it.material)
                + (it.sample ? " · 示例" : "")));
        card.addView(meta);

        card.setOnClickListener(v -> host.onOpen(it));
        card.setOnLongClickListener(v -> { showActions(it); return true; });
        bindThumb(thumb, it, gen);
        return card;
    }

    private void showActions(Item item) {
        new AlertDialog.Builder(getContext()).setTitle(item.title)
            .setItems(new String[]{item.favorite ? "取消心动" : "标记心动", "编辑展签与手记", "制作分享卡"},
                (dialog, which) -> {
                    if (which == 0) host.onToggleFavorite(item.copy());
                    else if (which == 1) host.onEdit(item.copy());
                    else host.onShare(item.copy());
                }).setNegativeButton("关闭", null).show();
    }

    private View makeTimelineCard(Context ctx, Item it, int gen) {
        LinearLayout row = Ui.hbox(ctx);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 10), Ui.dp(ctx, 12), Ui.dp(ctx, 10));
        row.setBackground(Ui.glass(ctx, 20));
        Ui.touchSurface(ctx, row, 20);
        ArtworkView thumb = new ArtworkView(ctx);
        thumb.setInteractive(false);
        thumb.setBackground(Ui.rounded(ctx, Ui.PAPER, 12, null, 0));
        thumb.setClipToOutline(true);
        row.addView(thumb, new LinearLayout.LayoutParams(Ui.dp(ctx, 76), Ui.dp(ctx, 88)));
        LinearLayout text = Ui.vbox(ctx);
        text.setPadding(Ui.dp(ctx, 14), 0, 0, 0);
        text.addView(Ui.eyebrow(ctx, Ui.dateCompact(it.created) + (it.favorite ? " · ♥" : "")));
        TextView title = Ui.serifBold(ctx, it.title, 17, Ui.INK);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(title);
        TextView note = Ui.text(ctx, it.note == null || it.note.isEmpty()
            ? Ui.materialName(it.material) + " · " + it.serial() : it.note, 12, Ui.MUTED);
        note.setMaxLines(2);
        note.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(note);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.setContentDescription(Ui.dateStr(it.created) + "，" + it.title + "，" + Ui.materialName(it.material));
        row.setOnClickListener(v -> host.onOpen(it));
        row.setOnLongClickListener(v -> { showActions(it); return true; });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(ctx, 10);
        row.setLayoutParams(lp);
        bindThumb(thumb, it, gen);
        return row;
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
